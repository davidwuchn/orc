# CLAUDE.md

## Project Overview

ORC (Orchestrator) is a behavior-tree-based workflow execution engine built on the Grain event-sourcing framework. It provides composable primitives for building, optimizing, and evaluating LLM-powered workflows.

**Top namespace**: `ai.obney.orc`

This is a **library** — no web layer, no auth, no database config. Consumers pull it in as a git dep and provide their own Grain infrastructure (event store, cache, web server).

## Components

| Component | Purpose |
|-----------|---------|
| **orc-service** | Core behavior tree execution engine, DSL for workflow building, event-sourced state |
| **gepa** | LLM instruction optimization with Pareto frontier selection |
| **evaluation** | LLM-as-judge evaluation with grounding, reasoning, completeness judges |
| **colbert** | Late-interaction retrieval via Python ColBERT bridge |
| **ontology** | Three-layer concept graph with embeddings and pattern discovery |
| **mcp-sheet-builder** | Dynamic workflow generation from MCP tool schemas |

## Architecture

Built on **Grain v2** (event sourcing + CQRS):
- `defcommand` — validate and emit events
- `defreadmodel` — project events into queryable state
- `defquery` — compose read models, return data
- `defprocessor` — event-driven side effects (auto-registered)
- `defperiodic` — scheduled trigger events (auto-registered)

## Development Setup

```bash
# Start nREPL
clj -M:dev -m nrepl.cmdline --port 7888
```

## Running Tests

```bash
clj -M:poly test                    # changed bricks only
clj -M:poly test :all-bricks        # all bricks
clj -M:poly test brick:orc-service  # specific brick
```

## Consumer Usage

Add to your project's `deps.edn`:

```clojure
obneyai/orc {:git/url "https://github.com/ObneyAI/orc.git"
             :git/sha "..."
             :deps/root "projects/orc"}
```

Then require components:

```clojure
(require '[ai.obney.orc.orc-service.interface :as orc])
(require '[ai.obney.orc.gepa.interface :as gepa])
```

## Polylith Structure

```
components/{service}/
├── src/ai/obney/orc/{service}/
│   ├── interface.clj              # Public API
│   ├── interface/schemas.clj      # Malli schemas
│   └── core/
│       ├── commands.clj           # defcommand handlers
│       ├── read_models.clj        # defreadmodel projections
│       ├── queries.clj            # defquery handlers
│       └── todo_processors.clj    # defprocessor side effects
└── test/ai/obney/orc/{service}/
```

## RLM Generalization Benchmark

The repo includes an end-to-end benchmark suite that demonstrates ORC RLM (`emit-tree!`) genuinely adapts behavior tree design to task type:

- Entry point: [`development/bench/README.md`](development/bench/README.md)
- Headline report: [`development/bench/RESULTS.md`](development/bench/RESULTS.md)
- Runner: `development/bench/runner.clj`
- Aggregator: `development/bench/all.clj`
- Per-task examples: `development/bench/{document_analysis,risk_analysis,contract_comparison,contract_comparison_validated,legal_issue_detection}.clj`

5 task types tested with goal-only instructions (no hardcoded trees) on real documents. The model designed 4 distinct tree patterns + 1 "no tree" decision, with zero hallucinations across spot-checks. See the headline report for the story.

```bash
export OPENROUTER_API_KEY="sk-or-v1-..."
# Run a single task
clj -M:dev -e '(require (quote [risk-analysis :as t])) (require (quote [runner])) (runner/start!) (runner/run! t/task) (runner/stop!)'
# Or run the full 5-task suite
clj -M:dev -e '(require (quote [all :as bench])) (bench/start!) (bench/run-all!) (bench/summary!) (bench/stop!)'
```

## RLM Mode (repl-researcher node)

The `:repl-researcher` node type provides a two-phase execution pattern. See [`docs/RLM-GUIDE.md`](docs/RLM-GUIDE.md) for the comprehensive guide.

**Two modes**, controlled by the `:rlm` config on the node:

```clojure
;; Terminal mode (default) — model designs ONE tree, Phase 2 runs, result returns to caller
{:rlm true}                          ; or {:rlm {:debug? true}}

;; Recursive mode (R-1, opt-in) — emit-tree! returns to Phase 1; model can inspect and continue
{:rlm {:recursive? true}}            ; or {:rlm {:recursive? true :debug? true}}
```

In recursive mode, after Phase 2 completes (any status):
- Tree's `:writes`-declared outputs merge into sandbox-vars (model calls `(get-var :summary)` naturally)
- A lightweight summary entry appends to `:tree-results` (chronological history with `:status`, `:elapsed-ms`, `:nodes-succeeded/failed`, `:failure-indices`/`:failure-reasons` on `:partial`/`:failure`, timeout fields on `:timeout`)
- Control returns to Phase 1 — model can run follow-up `(llm ...)` / `(code ...)`, emit another tree, or call `(final! ...)` to terminate

Existing callers using `:rlm true` are unaffected — terminal behavior is preserved.

### Recent observability + resilience additions

The repo recently shipped foundational work that the RLM layers build on:

- **O01–O03**: per-node usage events with structured `:node-path`, per-node `:input-profile` (length, word-count, line-count), bookend `:sheet/rlm-tree-execution-completed` event with full trajectory + `:task-fingerprint` placeholder
- **D-008**: first-class `:partial` map-each status with `:partial-summary` block (verbatim failure-indices + failure-reasons); successes-only output blackboard key; sticky `:partial` propagation through sequences
- **D-003**: `:timeout-ms` on the repl-researcher node bounds total Phase-1+Phase-2 wall-time; on Phase-2 timeout, dispatches existing `:sheet cancel-tick` command + ~500 ms drain so in-flight nodes settle their writes
- **R-1**: recursive `emit-tree!` (opt-in via `:rlm {:recursive? true}`)

All build on Grain v2's event-sourcing patterns — commands emit events, processors react. Detailed PRDs and per-slice issues live as local-only files under `docs/prd/` and `docs/issues/` on the `feature/core-orc-upgrades` branch.

## Skills

### ORC Domain
- `/orc-workflow` — Build a behavior tree workflow using the DSL
- `/orc-optimize` — Set up GEPA prompt optimization for a workflow
- `/orc-evaluate` — Set up LLM-as-judge evaluation

### Grain Framework
- `/grain-command-handler`, `/grain-read-model`, `/grain-query-handler`, `/grain-todo-processor`, `/grain-schema`, `/grain-service` — Framework patterns for building new features
