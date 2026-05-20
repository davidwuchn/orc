# RLM (Research Language Model) Mode

RLM is a two-phase execution pattern for the `:repl-researcher` node type. The LLM iteratively generates Clojure code in a sandboxed REPL (Phase 1) and can optionally emit behavior trees that ORC executes as child ticks (Phase 2). Recent work makes the loop **truly recursive**: `emit-tree!` is no longer a terminator — the model can inspect tree outputs and continue reasoning.

## When to use RLM

RLM fits problems where the *right tree shape isn't known up front*. The model designs the workflow as it learns about the input. Examples:

- **Analytical tasks on large documents** — model decides whether to chunk, how many parallel iterations, how to synthesize
- **Multi-step extraction** — emit a tree to get summary, then inspect, then call follow-up LLM/code for derived metrics
- **Adaptive recovery** — when a tree returns `:partial` with some chunks failed, the model can decide to retry, fall back, or accept what it has

If your tree shape is fixed and known, use the regular ORC DSL directly — don't pay the Phase 1 code-gen overhead.

## Basic usage (terminal mode — default)

```clojure
(require '[ai.obney.orc.orc-service.interface :as orc])

;; Build a sheet with a repl-researcher node
;; ... declare blackboard keys, create the node ...

;; Configure the researcher
(orc/set-repl-researcher-config!
  ctx sheet-id node-id
  {:instruction "Analyze the document for risks and obligations..."
   :reads [:document]
   :writes [:risk-matrix :executive-summary]
   :mcp-tools []
   :model "google/gemini-2.5-flash"
   :max-iterations 5
   :rlm {:debug? true}})

;; Execute
(orc/execute ctx sheet-id {:document doc-text})
```

The model:
1. Iterates up to `:max-iterations` times, generating Clojure code each iteration
2. Code can call `(llm ...)`, `(code ...)`, `(store! ...)`, `(get-var ...)`, etc.
3. Optionally calls `(emit-tree! [...])` to design a behavior tree for ORC to execute
4. Terminal `emit-tree!`: Phase 2 runs and the result returns to the caller — loop ends.

## Recursive mode (`:rlm {:recursive? true}`)

When the `:recursive?` flag is set, `emit-tree!` is no longer terminal:

```clojure
{:rlm {:recursive? true :debug? true}}
```

After Phase 2 completes:
- Tree's `:writes`-declared outputs merge into sandbox-vars (model calls `(get-var :summary)` naturally)
- A lightweight summary entry appends to `:tree-results` (chronological history)
- Control returns to Phase 1 loop
- Model gets a fresh iteration to reason about the result, run follow-up `(llm ...)` / `(code ...)`, emit another tree, or call `(final! ...)` to terminate

The loop ends ONLY when:
- Model calls `(final! {...})` with the declared `:writes` keys
- `:max-iterations` is exhausted (returns `:failure :error "Max iterations reached without final!"`)
- D-003's `:timeout-ms` budget is exhausted

### What the model sees after a tree

Each entry in `:tree-results` (visible via `(get-var :tree-results)`):

```clojure
{:tick-id <uuid>
 :tree-raw [:sequence [...]]                   ; the tree the model designed
 :status :success | :partial | :failure | :timeout
 :elapsed-ms 4523
 :outputs-keys [:summary :chunk-summaries]     ; what's now in sandbox-vars
 :nodes-succeeded 22
 :nodes-failed 2
 :nodes-total 24
 :usage {:prompt-tokens N :completion-tokens N :total-tokens N}

 ;; ONLY when :partial or :failure (from D-008 :partial-summary):
 :failure-indices [7 17]
 :failure-reasons {7 "Rate limit exhausted" 17 "Schema validation failed"}

 ;; ONLY when :status :timeout (from D-003 response):
 :phase2-elapsed-ms 4500
 :budget-remaining-ms 0
 :nodes-completed-before-cancel 4}
```

The summary is **descriptive, not prescriptive** — raw enums, factual counts, verbatim error strings. The model interprets for its own task. No `:severity :high`, no `:recommended-action`. The executor doesn't editorialize.

### Example: two-step task

```clojure
;; Task: summarize document, then count distinct entities, return both.

;; The model writes (in iteration 1):
(emit-tree!
  [:sequence
   [:llm {:instruction "Produce a one-paragraph summary"
          :reads [:document]
          :writes [:summary_text]}]
   [:final {:keys [:summary_text]}]])
;; → Phase 2 runs, recur fires

;; The model writes (in iteration 2 — :tree-results now visible):
(let [summary (get-var :summary_text)
      entities (llm "extract-entities"
                    :instruction "List all distinct named entities"
                    :reads [:document]
                    :writes [:entities_list])]
  (final! {:summary summary
           :entity-count (count (clojure.string/split (:entities_list entities) #","))}))
;; → final! reached, loop terminates with both outputs
```

Real LLM verification: `:status :success`, `:entity-count` reflects actual entity count from the doc, `:cumulative-thinking-ms` + `:cumulative-tree-ms` surface where wall-time was spent.

## Sandbox primitives

When the model's Phase 1 code executes in the SCI sandbox, the following primitives are available:

| Primitive | Purpose |
|---|---|
| `(llm "name" :instruction ... :reads [...] :writes [...])` | Sub-LLM call (synchronous; result merged into sandbox-vars) |
| `(code "name" :writes [...] :body ...)` | Pure Clojure computation (no LLM call) |
| `(sequence ...)` | Run primitives in order |
| `(parallel ...)` | Run primitives concurrently, merge results |
| `(map-each ...)` | Iterate over a collection with a child primitive |
| `(fallback ...)` | Behavioral combinator — first success wins |
| `(condition ...)` | Conditional execution |
| `(store! :key value)` | Store a value in sandbox-vars |
| `(get-var :key)` | Retrieve a value from sandbox-vars |
| `(list-vars)` | List all sandbox-vars with previews |
| `(get-input :key)` | Read a declared input |
| `(final! {...})` | **Terminate** with validated output |
| `(emit-tree! [...])` | Emit a behavior tree for Phase 2 execution |

All primitives execute IMMEDIATELY and return results to sandbox-vars. The model can compose them freely, reason about results across iterations (the history is rebuilt into each subsequent prompt), and decide its next move.

## Output Contract

The model is told:

```
You MUST call (final! {...}) with keys: [<the :writes-declared keys>]
```

And, for the prompt format DSCloj's parser expects:

```
Your response MUST start with `[[ ## code ## ]]` on its own line, followed by
RAW Clojure code (NO markdown code fences, NO ```clojure tags), and end with
`[[ ## completed ## ]]`.
```

Some models (notably gemini-2.5-flash) default to markdown fences which DSCloj's marker-based parser silently drops. The "CRITICAL OUTPUT FORMAT" prompt section is what keeps them aligned.

## Safety surface

| Knob | Purpose |
|---|---|
| `:max-iterations` (default 10) | Caps the number of Phase 1 code-gen iterations |
| `:timeout-ms` (default 900_000) | Total wall-time budget for Phase 1 + cumulative tree execution (D-003) |
| `:llm-call-budget` (opt-in, no default) | Hard cap on total LLM call count per tick |

Trees are "free" from the iteration counter — `:max-iterations` only counts code-gen calls. Budget exhaustion in Phase 1 skips Phase 2 entirely with `:status :failure :error "Budget exhausted in Phase 1"`. Phase 2 timeouts trigger `:sheet cancel-tick` on the child tick with ~500 ms drain.

## How partial outcomes propagate (D-008)

When a `map-each` inside a Phase 2 tree completes with some children succeeded and some failed:

- **All succeeded** → `:status :success`, output is the full vector
- **Some succeeded** (`0 < failed < total`) → `:status :partial`, output is **successes-only** vector
- **All failed** → `:status :failure`, output is `[]`
- **Empty source list** → `:status :success`, output is `[]`

Sequence and fallback parents treat `:partial` as continuation (downstream synthesis runs on successes). Sequences propagate `:partial` *stickily* — if any child was partial, the overall sequence reports `:partial`.

The map-each's `:sheet/node-execution-completed` event body carries a `:partial-summary`:

```clojure
{:total N :succeeded N :failed M
 :failure-indices [...]
 :failure-reasons {idx error-string ...}}
```

This is the data your judge layer subscribes to. The model in recursive mode sees the same data via `:tree-results`'s `:failure-indices` / `:failure-reasons` fields.

## Observability events emitted

Per the Grain methodology — every event is monitorable.

| Event | When emitted | Body |
|---|---|---|
| `:sheet/node-execution-completed` | Every node finishes | `:status`, `:writes`, `:duration-ms`, `:usage`, optional `:partial-summary` |
| `:sheet/rlm-tree-node-completed` | Per-node inside RLM Phase 2 trees | Structured `:node-path`, `:usage`, `:input-profile` |
| `:sheet/rlm-tree-execution-completed` | Bookend per Phase 2 tree | `:trajectory` (full per-event log), `:total-usage`, `:task-fingerprint` placeholder |
| `:sheet/tick-cancelled` | When D-003 cancels Phase 2 mid-flight | `:sheet-id`, `:tick-id` |

Judges in future work can subscribe to any of these for granular signal.

## Generated tree result EDN

When you run a Phase 2 tree, the saved EDN result has:

```clojure
{:status :success | :partial | :failure | :timeout
 :outputs {<your :writes keys> ...}
 :usage {:prompt-tokens N :completion-tokens N :total-tokens N
         :by-node {<structured-path> {tokens per node}}}      ; from O02
 :duration-ms N
 :trace-id <uuid>                                              ; for drill-down
 ;; D-003 timing breakdown:
 :phase1-elapsed-ms N
 :phase2-elapsed-ms N
 :phase2-tick-id <uuid>}
```

When recursive mode is on, additional response fields:

```clojure
{:cumulative-thinking-ms N    ; sum of Phase 1 code-gen wall-time
 :cumulative-tree-ms N}       ; sum of tree-execution wall-time
```

## Generalization benchmark

The repo ships a 5-task benchmark that demonstrates RLM generalizes — the model designs **structurally different trees** for **structurally different tasks** given only goal-only instructions (no example trees, no algorithm hints). See [`development/bench/RESULTS.md`](../development/bench/RESULTS.md) for the headline report and [`development/bench/README.md`](../development/bench/README.md) for run instructions.

The bench tasks use the *terminal* `emit-tree!` mode (`:rlm {:debug? true}`). They demonstrate the model's ability to design one good tree for a task — not the recursive flow. A separate slice will migrate the bench to recursive mode and validate no-regression there.

## Quick reference

```clojure
;; Terminal mode (existing — model designs ONE tree, Phase 2 runs, done)
{:rlm true}
{:rlm {:debug? true}}

;; Recursive mode (new — emit-tree! returns to Phase 1; model decides next move)
{:rlm {:recursive? true}}
{:rlm {:recursive? true :debug? true}}
```

Same node config either way. The flag controls one thing: whether Phase 2's result is returned to the caller (terminal) or merged back into sandbox-vars + recurred (recursive).

## Related guides

- [`docs/ORC-SERVICE-GUIDE.md`](ORC-SERVICE-GUIDE.md) — Core execution engine and DSL reference
- [`docs/dsl-tutorial.md`](dsl-tutorial.md) — Step-by-step DSL tutorial
- [`docs/EVENT-STORE-PATTERNS.md`](EVENT-STORE-PATTERNS.md) — Grain event-sourcing patterns
- [`development/bench/RESULTS.md`](../development/bench/RESULTS.md) — Generalization benchmark headline report
