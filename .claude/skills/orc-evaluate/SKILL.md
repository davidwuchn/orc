---
name: orc-evaluate
description: Set up LLM-as-judge evaluation for ORC workflows
---

# ORC Evaluation (LLM-as-Judge)

Set up automated quality evaluation for ORC workflow outputs. Read `docs/EVALUATION-COMPONENT.md` for the full reference.

## Require

```clojure
(require '[ai.obney.orc.evaluation.interface :as eval])
```

## Overview

The evaluation component provides LLM-as-judge scoring:
- **Grounding** — Is the output faithful to the input?
- **Instruction-following** — Does the output follow the instruction?
- **Reasoning** — Is the reasoning sound?
- **Completeness** — Does the output address all aspects?

Each judge returns a score (0.0-1.0) with structured feedback.

## Built-in Judges

### Grounding Judge
```clojure
(eval/grounding-judge ctx
  {:instruction "Original instruction given to the LLM"
   :input "The input that was provided"
   :output "The output that was generated"})
;; => {:score 0.85 :feedback {:strengths [...] :weaknesses [...]}}
```

### Completeness Judge
```clojure
(eval/completeness-judge ctx
  {:instruction "Summarize the article covering all main points"
   :input "Long article text..."
   :output "Short summary..."})
;; => {:score 0.7 :feedback {:missing-aspects [...]}}
```

## Workflow Judges (DSL Integration)

Attach judges to workflow nodes for automatic scoring during execution:

```clojure
(orc/workflow "evaluated-pipeline"
  (orc/blackboard {:question :string :answer :string})

  ;; Define judge configurations
  (orc/judges
    {"grounding"    {:type :grounding :weight 0.5}
     "completeness" {:type :completeness :weight 0.5}})

  ;; Attach judges to a node
  (orc/llm "answer"
    :instruction "Answer the question thoroughly."
    :reads ["question"]
    :writes ["answer"]
    :judges ["grounding" "completeness"]))
```

When executed with tracing enabled, each judged node produces evaluation scores in the trace.

## Batch Evaluation

Evaluate a workflow across multiple test cases:

```clojure
(eval/evaluate-batch ctx
  {:sheet-id sheet-id
   :examples [{"question" "What is AI?" "expected" "Artificial intelligence..."}
              {"question" "What is ML?" "expected" "Machine learning..."}]
   :judges [{:type :grounding :weight 0.5}
            {:type :completeness :weight 0.5}]})
;; => {:avg-score 0.82 :per-example [{:scores {...}} ...]}
```

## GEPA Integration

Evaluation judges feed directly into GEPA optimization. When GEPA proposes instruction candidates, judges score them — the scores drive Pareto selection.

```clojure
;; GEPA uses judges automatically when configured
(gepa/optimize! ctx
  {:sheet-id sheet-id
   :node-name "answer"
   :metrics [(gepa/judge-metric "answer"
               {:instruction "Rate answer quality"
                :model "google/gemini-2.0-flash-001"})]
   ...})
```

## Reference
- `docs/EVALUATION-COMPONENT.md` — Full evaluation guide
- `docs/FEEDBACK-LOOP.md` — Evaluation → GEPA feedback loop
- `docs/GEPA-GUIDE.md` — How judges integrate with optimization
