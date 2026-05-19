# Bug Investigation: `:max-concurrency` Not Honored in map-each

**Date discovered:** 2026-05-18
**Date fixed:** 2026-05-19
**Status:** ✓ FIXED — two distinct bugs found and corrected. Test passes.

## Symptom

The model designs trees with `:max-concurrency 5` (or any value > 1) on `:map-each` nodes, but the runtime executes child iterations **strictly serially** — only one sub-LLM call is in flight at any moment.

## Evidence

Added two debug logs to `execute-leaf-node` in `components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj`:

- `[EVENT RECEIVED]` — printed when the `:sheet/node-execution-started` event reaches the processor (BEFORE the future is spawned), with thread info
- `[SUBCALL START]` — printed inside the future, just before the LLM call
- `[SUBCALL DONE]` — printed inside the future, after the LLM call completes

### Captured timing (G02 risk-analysis test run)

```
[EVENT RECEIVED] map-idx=0 thread=async-thread-macro-6
[SUBCALL START] node=42f72b61 map-idx=0 thread=clojure-agent-send-off-pool-1 ...
[SUBCALL DONE]  node=42f72b61 status=success duration=6379ms tokens=4022
[EVENT RECEIVED] map-idx=1 thread=async-thread-macro-1     ← arrives ONLY AFTER idx 0 finished
[SUBCALL START] node=42f72b61 map-idx=1 thread=clojure-agent-send-off-pool-1 ...
[SUBCALL DONE]  node=42f72b61 status=success duration=17435ms tokens=6672
[EVENT RECEIVED] map-idx=2 thread=async-thread-macro-9     ← only after idx 1 finished
[SUBCALL START] node=42f72b61 map-idx=2 thread=clojure-agent-send-off-pool-1 ...
```

The critical observation: **`EVENT RECEIVED` for idx=1 only fires after `SUBCALL DONE` for idx=0**.

### What this proves

1. The 5 events from the initial batch ARE getting emitted (`mapcat` produces them)
2. But the **event delivery to the leaf-node processor is serialized** with the prior subcall's completion
3. The thread names confirm:
   - Each `EVENT RECEIVED` runs on a different `async-thread-macro-N` thread (from `async/thread` in execution-fn)
   - All futures run on `clojure-agent-send-off-pool-1` (Java's standard future pool)

So the threading is correct in isolation. The bottleneck is somewhere in event delivery itself.

## Root Cause Hypothesis

Looking at the event pipeline:

```
map-each handler emits 11 events ({:result/events [...]})
                  ↓
   command-processor appends them to event-store
                  ↓
        events published to pubsub
                  ↓
        topic subscriber's in-chan (size 1024)
                  ↓
   thread-pool worker (thread-count=1) picks them up
                  ↓
   execution-fn calls (async/thread (handler-fn ...))
                  ↓
   handler-fn (execute-leaf-node) spawns (future ...) for LLM call
```

The candidate causes:
1. **`command-processor` may serialize event emission** — appending to event store then publishing might happen synchronously per event, with the next event blocked on the prior one's full propagation cycle (including all subscribers' handlers returning)
2. **`process-command` is called from inside the future after the LLM completes** — to emit `:sheet/complete-node-execution`. If `process-command` is synchronous AND `:result/events` from one handler's invocation must fully process before the next event is delivered, that would serialize the whole pipeline

Looking at `execute-leaf-node`, the future does:
```clojure
(future
  (try
    (let [result (executor/execute-leaf ...)]
      ...
      (cp/process-command (assoc context :command {... :complete-node-execution ...})))))
```

If `cp/process-command` is what triggers the next batch's processing via the completion handler, then yes — the next event won't fire until the prior one's `process-command` for completion is fully handled.

## Where the bug actually lives

The `handle-map-each-child-completion` function at line 1219+ in `todo_processors.clj`:

When a child completes, it returns `{:type :start-next ...}` and emits **exactly ONE** start event for the next item. So even though the initial batch should start 5 simultaneously, after each completion only ONE new item is started.

That means **the steady state is 1 in flight, not 5**.

Reviewing the initial batch logic at line 1180:
```clojure
batch-size (min max-concurrency total-items)
batch-indices (range batch-size)
```

This is correct — it computes `(range 5) = (0 1 2 3 4)` and emits 10 events (5 writes + 5 starts).

**So the initial batch is correct, but they're processed sequentially due to event-pipeline serialization, not because of the map-each logic itself.**

## Why we previously thought parallelism worked

Looking at the G02 risk-analysis baseline run (156s for 19 chunks at ~15K each):
- 19 chunks × ~8s avg = 152s sequential
- Plus ~4s for synthesis = 156s

That matches the observed total. So the prior runs were also serial — we just didn't notice because the per-chunk durations made the sequential total look reasonable.

## Fix Direction (not yet implemented)

Three possible fixes:

### Option A: Run handler async in execute-leaf-node entry
Instead of returning quickly after spawning a future, make the WHOLE execute-leaf-node async. But this might already be the case via `async/thread`.

### Option B: Use a thread pool size > 1 for the processor
In `todo_processor_v2/core.clj`, `:thread-count 1` is hardcoded. Increasing to a higher number (e.g., 8) would allow multiple events to be processed concurrently. This is the simplest fix but applies globally.

### Option C: Fix the in-batch emission to truly fire all 5 starts at once
The mapcat emits 5 start events, but if the command-processor's append+publish cycle is synchronous per event, only the first proceeds quickly. We'd need to investigate command-processor-v2 internals.

### Option D: Use Java's parallelStream or similar in execute-map-each-node
Instead of relying on event-driven parallelism, the map-each processor could directly invoke 5 parallel work units that bypass the pubsub round-trip.

## Resolution (2026-05-19)

The actual root cause was **two distinct bugs**, neither in the area I had originally hypothesized:

### Bug 1: `:max-concurrency` was silently dropped during DSL → ORC DSL → command compilation

**Location:** `components/orc-service/src/ai/obney/orc/orc_service/core/rlm_tree_executor.clj` lines 238-247

The `:set-map-each-config` command was being built with only `:source-key`, `:item-key`, and `:output-key` — the `:max-concurrency` value from the parsed DSL opts was never forwarded. The command itself supports it (commands.clj:504 destructures `max-concurrency`), and the read model picks it up (read_models.clj:368). The pipeline simply skipped passing it through.

**Diagnostic that found it:** Added `[MAP-EACH INIT]` debug log to `execute-map-each-node` showing `max-conc=1` for a DSL that declared `:max-concurrency 3`. That immediately localized the bug to the compilation path.

**Fix:** Conditionally add `:max-concurrency (:max-concurrency opts)` to the command map when present.

### Bug 2: Concurrent completions all picked the same `next-to-start`

**Location:** `components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj` line 1300-1320 (`handle-map-each-child-completion`)

Once Bug 1 was fixed, the test still failed — but for a different reason. With 3 items running in parallel (`:max-concurrency 3`), when all 3 completed at nearly the same time, the completion handler's `swap!` ran 3 times in quick succession. Each time it asked "what's the next idx with `nil` results?" and all 3 picked idx=3 (the first nil in results). idx=3, 4, 5 each ran 3 times instead of once — call-count was 12 instead of 6.

The `:in-flight` set was declared in the initial state but **never updated** by the completion handler. It existed in the data structure but was inert.

**Fix:**
- Initialize `:in-flight` to `(set batch-indices)` in the initial batch
- In the completion handler's atomic `swap!`: remove the just-completed idx, then choose `next-to-start` only from indices that are BOTH nil in results AND not in `:in-flight`, then add the chosen idx to `:in-flight`

### Test (locked in)

`components/orc-service/test/ai/obney/orc/orc_service/rlm_tree_executor_test.clj` — `map-each-max-concurrency-runs-iterations-in-parallel`:
- Tree with `:map-each {:max-concurrency 3}` over 6 items
- Mock LLM sleeps 200ms per call and tracks peak in-flight count
- Asserts:
  - 6 total calls (no duplicates)
  - peak in-flight > 1
  - peak in-flight ≥ 3 (matches max-concurrency)

Result: ✓ All 4 assertions pass. Without either fix, the test fails (peak = 1 OR call-count = 12).

## Impact on prior benchmark results

All prior benchmark runs (baseline, G02, G03 both runs, doc-analysis on 280K) ran **strictly serially** because `:max-concurrency` was being silently dropped. Expected speedups for re-runs:
- G02 (19 chunks): 156s → roughly 30-40s
- doc-analysis 280K (24 chunks): 251s → roughly 60-80s
- G03 contract-comparison: 109-205s → roughly 30-60s

Output quality is unaffected — the bug only impacted speed.

Re-running the benchmarks is tracked in G06, G07, G08.
