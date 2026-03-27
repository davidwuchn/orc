---
name: orc-optimize
description: Set up GEPA prompt optimization for an ORC workflow
---

# GEPA Prompt Optimization

Optimize LLM instructions in an ORC workflow using GEPA (Genetic-Pareto Prompt Optimizer). Read `docs/GEPA-GUIDE.md` for the full reference.

## Require

```clojure
(require '[ai.obney.orc.gepa.interface :as gepa])
```

## Overview

GEPA improves LLM node instructions through:
1. **Reflective mutation** — LLM analyzes failures and proposes better instructions
2. **Pareto selection** — Maintains diversity by tracking per-example best candidates
3. **Iterative generations** — Each generation proposes and evaluates instruction variants

## Setup

### 1. Define Metrics

Metrics score how well a candidate instruction performs on each training example.

```clojure
;; Exact match (binary: 0 or 1)
(gepa/exact-match-metric "answer" "expected-answer")

;; Contains check
(gepa/contains-metric "answer" "expected-substring")

;; Judge-based (LLM evaluates quality)
(gepa/judge-metric "answer"
  {:instruction "Rate the answer quality 0-1."
   :model "google/gemini-2.0-flash-001"})
```

### 2. Define Training Examples

```clojure
(def examples
  [{"question" "What is 2+2?" "expected-answer" "4"}
   {"question" "Capital of France?" "expected-answer" "Paris"}
   {"question" "Largest ocean?" "expected-answer" "Pacific"}])
```

### 3. Start Optimization

```clojure
(gepa/optimize! ctx
  {:sheet-id sheet-id
   :node-name "answer-node"          ;; which LLM node to optimize
   :examples examples
   :metrics [(gepa/exact-match-metric "answer" "expected-answer")]
   :budget {:max-generations 5
            :candidates-per-generation 3}
   :seed-instruction "Answer the question concisely."})
```

### 4. Check Results

```clojure
;; Get the best candidate
(gepa/get-best-candidate ctx optimization-id)

;; Get the Pareto frontier
(gepa/get-pareto-frontier ctx optimization-id)

;; Get optimization progress
(gepa/get-optimization-progress ctx optimization-id)
```

### 5. Apply the Best Instruction

```clojure
;; Update the LLM node with the optimized instruction
(gepa/apply-best-instruction! ctx optimization-id sheet-id "answer-node")
```

## Key Concepts

- **Candidate** — An instruction variant with scores per training example
- **Pareto frontier** — Set of non-dominated candidates (no single candidate beats another on all examples)
- **Generation** — One round of propose → evaluate → select
- **Budget** — Controls how many generations and candidates per generation

## Reference
- `docs/GEPA-GUIDE.md` — Full GEPA integration guide
- `docs/FEEDBACK-LOOP.md` — How evaluation feeds back into optimization
