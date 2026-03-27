# GEPA Integration Guide

**GEPA** (Genetic-Pareto Prompt Optimizer) automatically improves LLM instructions through reflective mutation and Pareto selection. This guide documents how GEPA integrates with the ORC (Orchestration Runtime for Clojure) workflow service.

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Core Components](#core-components)
4. [Creating GEPA-Compatible Workflows](#creating-gepa-compatible-workflows)
5. [Running GEPA Optimization](#running-gepa-optimization)
6. [Evaluation Judges](#evaluation-judges)
7. [Event Store & Data Collection](#event-store--data-collection)
8. [Verification Test](#verification-test)
9. [Testing GEPA Workflows](#testing-gepa-workflows)
10. [Troubleshooting](#troubleshooting)

---

## Overview

### What is GEPA?

GEPA is a prompt optimization framework that:
- **Reflective Mutation**: Uses an LLM to analyze failures and propose improved instructions (not reinforcement learning)
- **Pareto Selection**: Maintains diversity by tracking per-example best candidates (not a single global best)
- **Iterative Improvement**: Runs multiple generations, each proposing better instruction variants

### Integration Approach

We use **libpython-clj2** to bridge the real Python GEPA library directly to Clojure:
- Clojure evaluation functions are passed as callbacks to Python GEPA
- GEPA handles the optimization loop (candidate generation, selection, mutation)
- Our Clojure judges provide the evaluation scores and feedback

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Python GEPA Library                          │
│  gepa.optimize(adapter=ClojureORCAdapter(...))                  │
└────────────────────────────┬────────────────────────────────────┘
                             │ libpython-clj2
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│              ClojureORCAdapter (Python)                          │
│  development/python/clojure_adapter.py                          │
│                                                                  │
│  evaluate():                                                     │
│    → Calls Clojure evaluate-fn for each example                 │
│    → Returns scores + feedback for GEPA                         │
│                                                                  │
│  make_reflective_dataset():                                      │
│    → Formats low-scoring traces for GEPA reflection             │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│           Clojure GEPA Integration                               │
│  components/orc-service/src/.../core/gepa.clj                 │
│                                                                  │
│  optimize-instruction:                                           │
│    1. Load GEPA Python package                                  │
│    2. Create evaluate-fn that calls ORC workflow + judges       │
│    3. Pass evaluate-fn to ClojureORCAdapter                     │
│    4. Run GEPA optimization loop                                │
│    5. Return best instruction + scores                          │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│           ORC Sheet Service + Evaluation Judges                  │
│                                                                  │
│  sheet/execute:                                                  │
│    → Runs behavior tree with candidate instruction              │
│    → Returns outputs                                            │
│                                                                  │
│  eval/evaluate-trace:                                            │
│    → Runs grounding, instruction-following, reasoning judges    │
│    → Returns weighted aggregate score (0.0 - 1.0)               │
└─────────────────────────────────────────────────────────────────┘
```

---

## Core Components

### 1. GEPA Integration (`gepa.clj`)

**File:** `components/orc-service/src/ai/obney/workshop/orc_service/core/gepa.clj`

Key functions:

#### `optimize-instruction`
Main entry point for GEPA optimization.

```clojure
(require '[ai.obney.orc.orc-service.interface :as sheet])

(sheet/optimize-instruction context sheet-id trainset
  :judges [:grounding :instruction-following :reasoning]
  :max-metric-calls 50
  :seed-instruction "Answer the question."
  :task-lm "google/gemini-2.0-flash-001"
  :reflection-lm "openrouter/anthropic/claude-sonnet-4")
```

**Arguments:**
| Arg | Type | Description |
|-----|------|-------------|
| `context` | map | Grain context with `:event-store` |
| `sheet-id` | uuid | Workflow to optimize |
| `trainset` | vector | Examples with `:inputs` and optional `:expected` |

**Options:**
| Option | Default | Description |
|--------|---------|-------------|
| `:judges` | `[:grounding :instruction-following :reasoning]` | Judge keywords to use |
| `:max-metric-calls` | 50 | Budget limit (total evaluations) |
| `:seed-instruction` | workflow's instruction | Starting instruction |
| `:task-lm` | `"openai/gpt-4o-mini"` | LLM for task execution |
| `:reflection-lm` | `"anthropic/claude-sonnet-4"` | LLM for GEPA reflection |

**Returns:**
```clojure
{:initial-score 0.765
 :final-score 0.783
 :best-instruction "When provided with a question, answer it directly..."
 :improvement 0.018
 :total-metric-calls 30
 :num-candidates 3}
```

#### `evaluate-candidate`
Evaluate a single candidate instruction on one example.

```clojure
(sheet/evaluate-candidate context sheet-id
  [:grounding :instruction-following]
  {:instruction "Answer concisely."}
  {:inputs {:question "What is 2+2?"}})
;; => {:score 0.85 :feedback "..." :outputs {:answer "4"}}
```

#### `manual-evaluation-loop`
Run evaluation without GEPA (for baseline measurement).

```clojure
(sheet/manual-evaluation-loop context sheet-id trainset
  :judges [:grounding :instruction-following]
  :instruction "Answer the question.")
;; => {:avg-score 0.78 :min-score 0.6 :max-score 0.95 :results [...] :low-scoring [...]}
```

### 2. Python Adapter (`clojure_adapter.py`)

**File:** `development/python/clojure_adapter.py`

The adapter implements GEPA's `GEPAAdapter` protocol:

```python
class ClojureORCAdapter(GEPAAdapter):
    def __init__(self, evaluate_fn: Callable[[dict, dict], dict]):
        """
        Args:
            evaluate_fn: Clojure function that takes (candidate, example)
                         and returns {"score": float, "feedback": str, "outputs": dict}
        """
        self.evaluate_fn = evaluate_fn

    def evaluate(self, batch, candidate, capture_traces=False):
        """Run Clojure workflow with candidate instruction on each example."""
        ...

    def make_reflective_dataset(self, candidate, eval_batch, components_to_update):
        """Format execution traces for GEPA's reflective mutation."""
        ...
```

### 3. Evaluation Judges

**File:** `components/evaluation/src/ai/obney/workshop/evaluation/core/judges.clj`

Four judges evaluate LLM outputs:

| Judge | Weight | Evaluates |
|-------|--------|-----------|
| `:grounding` | 0.35 | Is response grounded in inputs? No hallucinations? |
| `:instruction-following` | 0.25 | Did LLM follow the instruction? |
| `:reasoning` | 0.20 | Is reasoning clear and logical? |
| `:completeness` | 0.20 | Are all aspects of the task addressed? |

Each judge returns:
```clojure
{:score 0.85          ;; 0.0 - 1.0
 :feedback "..."      ;; Actionable improvement suggestions
 :details {...}}      ;; Judge-specific details (grounded-claims, etc.)
```

---

## Creating GEPA-Compatible Workflows

### Critical Pattern: Dynamic Instructions

For GEPA to optimize a workflow's instruction, the instruction must be **passed as input** rather than hardcoded. This is achieved by including `:instruction` in the `:reads` vector.

#### Wrong Pattern (Static Instruction)

```clojure
;; DON'T DO THIS - instruction is hardcoded, GEPA can't override it
(sheet/workflow "qa-static"
  (sheet/blackboard
    {:question :string
     :answer :string})

  (sheet/llm "answer"
    :model "google/gemini-2.0-flash-001"
    :instruction "Answer the question."  ;; Static, can't be changed by GEPA
    :reads [:question]
    :writes [:answer]))
```

#### Correct Pattern (Dynamic Instruction)

```clojure
;; DO THIS - instruction comes from blackboard, GEPA can override it
(sheet/workflow "qa-dynamic"
  (sheet/blackboard
    {:question :string
     :instruction :string    ;; Add instruction to blackboard
     :answer :string})

  (sheet/llm "answer"
    :model "google/gemini-2.0-flash-001"
    :instruction "Follow the instruction provided in the 'instruction' field to answer the question."
    :reads [:question :instruction]  ;; CRITICAL: include instruction in reads
    :writes [:answer]))
```

**Why this works:** When `:instruction` is in `:reads`, the executor includes it in the LLM's context. GEPA passes candidate instructions as input values, which then appear in the prompt context for the LLM to follow.

### Complete Example Workflow

```clojure
(require '[ai.obney.orc.orc-service.interface :as sheet])

(def qa-workflow
  (sheet/workflow "gepa-qa"
    (sheet/blackboard
      {:question [:string {:description "The user's question to answer"}]
       :instruction [:string {:description "Instructions for how to answer"}]
       :answer [:string {:description "The answer to the question"}]})

    (sheet/llm "answer"
      :model "google/gemini-2.0-flash-001"
      :instruction "Follow the instruction provided in the 'instruction' field to answer the question in the 'question' field."
      :reads [:question :instruction]
      :writes [:answer]))

;; Build the workflow
(def sheet-id (sheet/build-workflow! context qa-workflow))
```

---

## Running GEPA Optimization

### Prerequisites

1. **Python environment with GEPA:**
   ```bash
   cd development
   python -m venv .venv
   source .venv/bin/activate
   pip install git+https://github.com/gepa-ai/gepa.git
   ```

2. **API Keys (in environment):**
   ```bash
   export OPENROUTER_API_KEY="your-key"
   # Or ANTHROPIC_API_KEY, OPENAI_API_KEY depending on LLM choice
   ```

3. **Running REPL with system started:**
   ```clojure
   ;; In development/src/repl_stuff.clj
   (def service (start))
   (def context (:context service))
   ```

### Step-by-Step REPL Workflow

```clojure
;; 1. Require namespaces
(require '[ai.obney.orc.orc-service.interface :as sheet])

;; 2. Create GEPA-compatible workflow
(def qa-workflow
  (sheet/workflow "gepa-qa"
    (sheet/blackboard
      {:question :string
       :instruction :string
       :answer :string})
    (sheet/llm "answer"
      :model "google/gemini-2.0-flash-001"
      :instruction "Follow the instruction in 'instruction' to answer the question."
      :reads [:question :instruction]
      :writes [:answer]))

(def sheet-id (sheet/build-workflow! context qa-workflow))

;; 3. Define trainset
(def trainset
  [{:inputs {"question" "What is 2 + 2?"}
    :expected {"answer" "4"}}
   {:inputs {"question" "What is the capital of France?"}
    :expected {"answer" "Paris"}}
   {:inputs {"question" "Who wrote Romeo and Juliet?"}
    :expected {"answer" "William Shakespeare"}}
   {:inputs {"question" "What year did World War II end?"}
    :expected {"answer" "1945"}}
   {:inputs {"question" "What is the chemical symbol for water?"}
    :expected {"answer" "H2O"}}])

;; 4. Run baseline evaluation first
(def baseline (sheet/manual-evaluation-loop context sheet-id trainset
                :judges [:grounding :instruction-following :reasoning]
                :instruction "Answer the question."))

(println "Baseline avg score:" (:avg-score baseline))

;; 5. Run GEPA optimization
(def result (sheet/optimize-instruction context sheet-id trainset
              :judges [:grounding :instruction-following :reasoning]
              :max-metric-calls 30
              :seed-instruction "Answer the question."
              :reflection-lm "openrouter/anthropic/claude-sonnet-4"))

;; 6. Review results
(println "Initial score:" (:initial-score result))
(println "Final score:" (:final-score result))
(println "Improvement:" (:improvement result))
(println "Best instruction:" (:best-instruction result))
```

### Configuration Options

#### Choosing Judges

Select judges based on what matters for your use case:

```clojure
;; Factual Q&A - focus on accuracy
:judges [:grounding :completeness]

;; Complex reasoning tasks
:judges [:reasoning :instruction-following]

;; All judges (default)
:judges [:grounding :instruction-following :reasoning :completeness]
```

#### LLM Configuration

GEPA uses two LLMs with different roles:

| Parameter | Purpose | Default | When to Change |
|-----------|---------|---------|----------------|
| `:task-lm` | Executes the workflow (answers questions) | `"openai/gpt-4o-mini"` | Use faster/cheaper model for simple tasks |
| `:reflection-lm` | Analyzes failures and proposes improved instructions | `"anthropic/claude-sonnet-4"` | Use smarter model for complex optimization |

**Task LLM Options:**
```clojure
;; Fast and cheap - good for simple tasks
:task-lm "google/gemini-2.0-flash-001"
:task-lm "openai/gpt-4o-mini"

;; More capable - for complex reasoning
:task-lm "anthropic/claude-sonnet-4"
:task-lm "openai/gpt-4o"
```

**Reflection LLM Options:**
```clojure
;; Via OpenRouter (recommended - single API key for all providers)
:reflection-lm "openrouter/anthropic/claude-sonnet-4"
:reflection-lm "openrouter/openai/gpt-4o"

;; Direct provider access
:reflection-lm "anthropic/claude-sonnet-4"
:reflection-lm "openai/gpt-4o"
```

**Example: Cost-Optimized Configuration:**
```clojure
(sheet/optimize-instruction context sheet-id trainset
  :task-lm "google/gemini-2.0-flash-001"      ;; Cheap for task execution
  :reflection-lm "openrouter/anthropic/claude-sonnet-4"  ;; Smart for reflection
  :max-metric-calls 30)
```

**Example: Quality-Optimized Configuration:**
```clojure
(sheet/optimize-instruction context sheet-id trainset
  :task-lm "anthropic/claude-sonnet-4"        ;; Best task execution
  :reflection-lm "openrouter/anthropic/claude-sonnet-4"  ;; Best reflection
  :max-metric-calls 50)
```

**API Key Requirements:**

The LLM provider is determined by the model string prefix:
- `google/*` → Requires `GOOGLE_API_KEY`
- `openai/*` → Requires `OPENAI_API_KEY`
- `anthropic/*` → Requires `ANTHROPIC_API_KEY`
- `openrouter/*` → Requires `OPENROUTER_API_KEY` (can access all providers)

#### Budget Control

```clojure
;; Quick test (fewer iterations)
:max-metric-calls 20

;; Thorough optimization
:max-metric-calls 100
```

---

## Evaluation Judges

### How Judges Work

Each judge is an LLM-as-judge that evaluates trace data:

```clojure
;; Trace data structure passed to judges
{:inputs {:question "What is 2+2?"}    ;; What was sent to LLM
 :outputs {:answer "4"}                 ;; What LLM returned
 :instruction "Answer concisely."}       ;; The instruction being evaluated
```

### Judge Rubrics

Rubrics are defined in `evaluation/core/rubrics.clj`. Each rubric provides:
- Scoring criteria (0.0 - 1.0 scale)
- What constitutes excellent vs poor scores
- Prompt template for the judge LLM

**Example: Grounding Rubric**
```
1.0 (Excellent): Every claim is traceable to inputs, no hallucinations
0.8 (Good): Almost all claims grounded, very minor extrapolations only
0.6 (Adequate): Most claims grounded, some unsupported but plausible inferences
0.4 (Poor): Significant ungrounded claims that could mislead
0.2 (Very Poor): Most information not from inputs
0.0 (Failed): Contains clear hallucinations or contradicts inputs
```

### Using Judges Directly

```clojure
(require '[ai.obney.orc.evaluation.interface :as eval])

;; Evaluate a single trace
(def trace-data
  {:inputs {:question "What is 2+2?"}
   :outputs {:answer "4"}
   :instruction "Answer math questions"})

;; Single judge
(eval/evaluate-single :grounding trace-data)
;; => {:score 0.95 :feedback "All claims grounded..." :grounded-claims [...]}

;; All judges with aggregation
(eval/evaluate-trace trace-data)
;; => {:score 0.87 :feedback "..." :dimensions [...]}

;; Custom judge selection
(eval/evaluate-trace trace-data {:judges [:grounding :reasoning]})
```

### Judge Weights

When aggregating scores, judges have different weights:

| Judge | Weight | Rationale |
|-------|--------|-----------|
| Grounding | 0.35 | Most critical - no hallucinations |
| Instruction Following | 0.25 | Task compliance |
| Reasoning | 0.20 | Quality of thought |
| Completeness | 0.20 | Thoroughness |

---

## Event Store & Data Collection

The event store captures rich data at both tree and node levels, which can be used for building trainsets and analyzing optimization results.

### Tree-Level Data

#### Execution Traces

Every workflow execution creates a `:sheet/execution-traced` event:

```clojure
(require '[ai.obney.orc.orc-service.interface :as sheet])

;; Get all traces for a sheet
(def traces (sheet/get-traces-for-sheet event-store sheet-id))

;; Each trace contains:
{:trace-id #uuid "..."
 :sheet-id #uuid "..."
 :version-number 1
 :started-at #inst "..."
 :completed-at #inst "..."
 :duration-ms 1234
 :status :success
 :input-snapshot {:question "What is 2+2?" :instruction "..."}
 :output-snapshot {:answer "4"}
 :node-traces [{:node-id #uuid "..." :duration-ms 100 :status :success}]
 :error nil}
```

#### Tree Metadata

```clojure
;; Get metadata about a workflow
(sheet/get-tree-metadata event-store sheet-id)
;; => {:problem-types [:qa :factual] :avg-score 0.85 ...}

;; Find workflows by problem type
(sheet/find-trees-by-problem-type event-store :qa {:min-score 0.7 :limit 10})
```

### Node-Level Data

#### Rolling Metrics

Track per-node performance over recent executions:

```clojure
;; Get metrics for a specific node
(sheet/get-node-rolling-metrics event-store sheet-id node-id)
;; => {:execution-count 50
;;     :success-rate 0.92
;;     :failure-rate 0.08
;;     :avg-duration-ms 1500
;;     :recent-trend :improving}  ;; or :declining, :stable

;; Get metrics for all nodes in a tree
(sheet/get-tree-rolling-metrics event-store sheet-id)
;; => {:sheet-id #uuid "..."
;;     :nodes [{:node-id #uuid "..." :success-rate 0.95 ...}]
;;     :total-executions 100}
```

### Building Trainsets from Event Store

You can extract training data from past executions:

```clojure
;; Get recent traces
(def traces (sheet/get-traces-for-sheet event-store sheet-id))

;; Convert to trainset format
(def trainset
  (->> traces
       (filter #(= :success (:status %)))
       (map (fn [trace]
              {:inputs (dissoc (:input-snapshot trace) :instruction)
               :expected (:output-snapshot trace)}))
       (take 20)
       vec))
```

### Extracting Low-Scoring Examples

For targeted improvement, find examples that scored poorly:

```clojure
;; Run evaluation on traces to identify weak spots
(require '[ai.obney.orc.evaluation.interface :as eval])

(def evaluated-traces
  (for [trace traces]
    (let [trace-data {:inputs (:input-snapshot trace)
                      :outputs (:output-snapshot trace)
                      :instruction (get (:input-snapshot trace) :instruction)}
          result (eval/evaluate-trace trace-data)]
      (assoc trace :eval-score (:score result)
                   :eval-feedback (:feedback result)))))

;; Find low-scoring examples
(def low-scoring
  (->> evaluated-traces
       (filter #(< (:eval-score %) 0.7))
       (sort-by :eval-score)))

;; These are prime candidates for GEPA's reflective improvement
```

---

## Verification Test

A complete verification test is available at:

**File:** `development/src/gepa_verification.clj`

### Running Verification

```clojure
(require '[gepa-verification :as gv])

;; Full verification suite
(gv/run-full-verification context)

;; Or step by step:
(def sheet-id (gv/setup-test-workflow! context))
(gv/run-single-evaluation context sheet-id)
(gv/run-manual-baseline context sheet-id)
(gv/run-gepa-optimization context sheet-id)
```

### Expected Output

```
============================================================
GEPA INTEGRATION VERIFICATION
============================================================

Step 1: Creating test workflow...
Created test workflow: #uuid "..."

Step 2: Testing single evaluation...
Question: What is 2 + 2?
Score: 0.85
Feedback: Response correctly answers...

Step 3: Running manual baseline...
Average score: 0.78
Min score: 0.65
Max score: 0.92

Step 4: Running GEPA optimization...
Initial score: 0.78
Final score: 0.85
Improvement: 0.07
Best instruction: When provided with a question...

VERIFICATION RESULTS
--------------------------------------------
✓ GEPA loop completed successfully
✓ Scores computed from Clojure judges
✓ Training loop executed 3 iterations
✓ Score improved by 0.07
```

---

## Testing GEPA Workflows

Unit tests for GEPA integration are available in:

**File:** `components/orc-service/test/ai/obney/workshop/orc_service/gepa_integration_test.clj`

### Test Context Setup

Use `with-async-test-context` for tests that need full event flow:

```clojure
(ns my-app.gepa-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.evaluation.interface :as eval]
            [ai.obney.orc.evaluation.core.judges :as judges]))

(deftest my-gepa-test
  (h/with-async-test-context [ctx]
    ;; Test code with full event store support
    ))
```

### Mock Executors

Create deterministic executors for testing without real LLM calls:

```clojure
(defn mock-qa-executor
  "Echoes instruction and question in the answer."
  [{:keys [inputs]}]
  (let [question (get inputs :question)
        instruction (get inputs :instruction "default")]
    {:answer (str "Instruction: " instruction " | Question: " question)}))
```

### Mock Judges

Bind `*use-mock-llm*` to avoid real LLM calls during evaluation:

```clojure
(binding [judges/*use-mock-llm* true]
  (let [trace-data {:inputs {:question "What is 2+2?"}
                    :outputs {:answer "4"}
                    :instruction "Answer accurately."}
        result (eval/evaluate-trace trace-data)]
    (is (number? (:score result)))
    (is (<= 0.0 (:score result) 1.0))))
```

### GEPA-Compatible Workflow Test Pattern

```clojure
(deftest gepa-workflow-test
  (testing "workflow with instruction in blackboard is GEPA-compatible"
    (h/with-async-test-context [ctx]
      (let [;; Create sheet
            sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "GEPA Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)

            ;; Create node
            node-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf))
            node-id (-> node-result :command-result/events first :node-id)

            ;; Declare GEPA-compatible blackboard
            _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :question :string))
            _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :instruction :string))
            _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :answer :string))

            ;; Configure node with instruction in reads
            _ (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id node-id
                                      [:question :instruction]  ;; instruction in reads!
                                      [:answer]))
            _ (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id node-id :code
                                      :fn "my-app.test/mock-qa-executor"))]

        ;; Verify instruction is in reads
        (let [nodes (sheet/get-nodes-for-sheet (:event-store ctx) sheet-id)
              node (first (filter #(= :leaf (:type %)) nodes))]
          (is (some #{:instruction} (:reads node))))

        ;; Execute with dynamic instruction
        (let [result (sheet/execute ctx sheet-id
                       {:question "test?" :instruction "Be concise."})]
          (is (= :success (:status result)))
          (is (clojure.string/includes?
                (get (:outputs result) :answer)
                "Be concise.")))))))
```

### Running Tests

```bash
clojure -M:dev:test -e "(do
  (require '[clojure.test :refer [run-tests]]
           '[ai.obney.orc.orc-service.gepa-integration-test])
  (run-tests 'ai.obney.orc.orc-service.gepa-integration-test))"
```

Expected output:
```
Testing ai.obney.orc.orc-service.gepa-integration-test

Ran 13 tests containing 62 assertions.
0 failures, 0 errors.
```

### Test Coverage

The integration tests verify:

| Test | Validates |
|------|-----------|
| `gepa-workflow-structure-test` | Instruction in blackboard + reads |
| `gepa-workflow-executes-with-instruction-test` | Execution with dynamic instruction |
| `instruction-override-test` | Different instructions produce different outputs |
| `execution-events-test` | Correct events emitted during execution |
| `tick-events-emitted-test` | Tree tick lifecycle events |
| `judge-aggregation-test` | Weighted score aggregation |
| `judge-subset-test` | Subset judge selection |
| `read-model-queries-test` | Read model data accuracy |
| `manual-evaluation-pattern-test` | Manual loop with mock judges |
| `execution-duration-test` | Duration tracking |
| `execution-isolation-test` | Parallel execution isolation |
| `trace-storage-for-training-test` | Trace queryability for trainsets |
| `rolling-metrics-test` | Node performance metrics |

---

## Troubleshooting

### Common Issues

#### 1. "GEPA module not found"
Ensure GEPA is installed in your Python environment:
```bash
pip install git+https://github.com/gepa-ai/gepa.git
```

#### 2. "Instruction not being used"
Check that:
- `:instruction` is in the blackboard schema
- `:instruction` is in the `:reads` vector of the LLM node
- The node's static instruction tells the LLM to follow the dynamic instruction

#### 3. "All scores are 0"
Check:
- Workflow executes successfully without GEPA first
- API keys are set correctly
- Judge LLM can be reached

#### 4. "No improvement after optimization"
This can happen if:
- The seed instruction is already near-optimal
- The trainset is too small (try 5-10 examples minimum)
- The task is inherently variable (some randomness expected)

### Debug Mode

Add logging to see what's happening:

```clojure
;; Run single evaluation with detailed output
(let [result (sheet/evaluate-candidate context sheet-id
               [:grounding :instruction-following]
               {:instruction "Test instruction"}
               {:inputs {:question "What is 2+2?"}})]
  (println "Score:" (:score result))
  (println "Feedback:" (:feedback result))
  (println "Outputs:" (:outputs result)))
```

---

## Future: Native Clojure GEPA

Phase 1 of the roadmap involves porting GEPA to pure Clojure/ORC for full event-sourced optimization. This would enable:

- All GEPA state stored as events in PostgreSQL
- Pareto frontier as a read model
- Instruction proposer as an ORC workflow
- Full observability of the optimization process

See `docs/GEPA-INTEGRATION-PLAN.md` for the detailed implementation plan.
