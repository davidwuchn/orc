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
- Runner: `development/bench/rlm_gen_bench.clj`

5 task types tested with goal-only instructions (no hardcoded trees) on real documents. The model designed 4 distinct tree patterns + 1 "no tree" decision, with zero hallucinations across spot-checks. See the headline report for the story.

```bash
export OPENROUTER_API_KEY="sk-or-v1-..."
clj -M:dev -e '(require (quote [rlm-gen-bench :as bench])) (bench/start!) (bench/run-task! :risk-analysis) (bench/stop!)'
```

## Skills

### ORC Domain
- `/orc-workflow` — Build a behavior tree workflow using the DSL
- `/orc-optimize` — Set up GEPA prompt optimization for a workflow
- `/orc-evaluate` — Set up LLM-as-judge evaluation

### Grain Framework
- `/grain-command-handler`, `/grain-read-model`, `/grain-query-handler`, `/grain-todo-processor`, `/grain-schema`, `/grain-service` — Framework patterns for building new features
