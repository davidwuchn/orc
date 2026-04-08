# Manual Self-Learning with Ontology Context

This guide explains how to use ORC's self-learning capabilities to improve your workflows over time. The system uses Grain's event sourcing infrastructure, providing full audit trails and reliable pattern storage.

## Overview

ORC's self-learning system is built on Grain's event sourcing:

| Layer | Component | Purpose |
|-------|-----------|---------|
| **Commands** | `defcommand` | Record successes and failures from any domain |
| **Events** | Event Store | Store all learning decisions with full audit trail |
| **Read Models** | Projections | Aggregate events into queryable tree profiles |
| **Context Injection** | Executor | Prepend learned patterns to LLM instructions |

## Grain Event Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                        YOUR CODE                                 │
│  (ontology/record-tree-strength ctx {...})                       │
│  (ontology/record-tree-weakness ctx {...})                       │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                     GRAIN COMMAND                                │
│  :ontology/record-tree-strength                                  │
│  :ontology/record-tree-weakness                                  │
│  → Validates against Malli schema                                │
│  → Emits :ontology/tree-strength-recorded event                  │
│  → Emits :ontology/tree-weakness-recorded event                  │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                     GRAIN EVENT STORE                            │
│  Event persisted with:                                           │
│  - :type :ontology/tree-strength-recorded                        │
│  - :tags #{[:tree tree-id]}  (for efficient filtering)           │
│  - :body {...pattern-data...}                                    │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                     GRAIN READ MODEL                             │
│  :ontology/tree-profiles projection                              │
│  → Reduces events into per-tree state                            │
│  → Cached in L2 for fast access                                  │
│  → Query via (ontology/get-tree-profile ctx tree-id)            │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                     CONTEXT INJECTION                            │
│  (orc/execute ctx sheet-id inputs)                               │
│  → Executor detects :context parameter on LLM node               │
│  → Queries tree-profiles read model                              │
│  → Formats patterns as markdown via format-context-for-llm       │
│  → Prepends to instruction before LLM call                       │
└─────────────────────────────────────────────────────────────────┘
```

---

## Quick Start

### Step 1: Record a Success

When your workflow performs well, record the successful pattern:

```clojure
(require '[ai.obney.grain.command-processor-v2.interface :as cp])

(cp/process-command
  (assoc ctx :command
    {:command/name :ontology/record-tree-strength
     :tree-id sheet-id
     :pattern-uri "success:HighQualityOutput"
     :confidence 0.92
     :evidence-trace-ids [trace-id]
     :avg-score 0.88

     ;; Domain-agnostic fields (works for any domain)
     :domain-type "sales-outreach"
     :context-conditions {:lead-score 85
                          :days-since-contact 3
                          :decision-maker? true}
     :action-taken {:type "personalized-email"
                    :template "value-prop"
                    :tone "professional"}
     :expected-outcome "meeting-scheduled"}))
```

### Step 2: Record a Failure

When your workflow fails, record what went wrong:

```clojure
(cp/process-command
  (assoc ctx :command
    {:command/name :ontology/record-tree-weakness
     :tree-id sheet-id
     :failure-uri "failure:Hallucination"
     :frequency 0.15
     :severity :high
     :triggers ["missing-context" "vague-input"]
     :evidence-trace-ids [trace-id]

     ;; Domain-agnostic fields
     :domain-type "sales-outreach"
     :failure-context {:lead-score 30
                       :missing-fields ["company-size" "budget"]}}))
```

### Step 3: Query Learned Patterns

Check what a tree has learned:

```clojure
(require '[ai.obney.orc.ontology.interface :as ontology])

;; Get full tree profile
(ontology/get-tree-profile ctx sheet-id)
;; => {:strengths [{:pattern-uri "success:..." :confidence 0.92 ...}]
;;     :weaknesses [{:failure-uri "failure:..." :severity :high ...}]
;;     :solves [...]}

;; Get only self-patterns (this tree's history)
(ontology/find-self-patterns ctx sheet-id {})
;; => {:strengths [...] :weaknesses [...]}

;; Build formatted context for injection
(ontology/build-actionable-context ctx sheet-id "problem:Classification" {})
;; => "## Learned Patterns\n\n### What Works Well\n..."
```

### Step 4: Enable Automatic Context Injection

Add `:context` parameter to LLM nodes:

```clojure
(orc/llm "analyze"
  :instruction "Analyze the input data..."
  :reads [:input-data]
  :writes [:analysis]
  ;; This enables automatic context injection
  :context {:problem-type "problem:Classification"
            :self-learning? true
            :tree-id sheet-id
            :include-patterns true
            :include-failures true})
```

At execution time, the executor will:
1. Query the tree's learned patterns
2. Format them as markdown
3. Prepend to your instruction

---

## Grain Events Reference

### Events Emitted

| Event | When | Key Fields |
|-------|------|------------|
| `:ontology/tree-strength-recorded` | Recording success | `tree-id`, `pattern-uri`, `confidence`, `context-conditions`, `action-taken` |
| `:ontology/tree-weakness-recorded` | Recording failure | `tree-id`, `failure-uri`, `severity`, `triggers`, `failure-context` |
| `:ontology/learned-rule-extracted` | Rule extraction | `tree-id`, `condition`, `action`, `success-rate` |

### Event Tagging

All events use Grain's tagging for efficient filtering:

```clojure
{:type :ontology/tree-strength-recorded
 :tags #{[:tree tree-id]}  ;; Filter by tree
 :body {...}}
```

Query events by tag:
```clojure
(es/read event-store {:types #{:ontology/tree-strength-recorded}
                      :tags #{[:tree tree-id]}})
```

---

## Read Model Queries

### Tree Profile Functions

| Function | Returns | Use Case |
|----------|---------|----------|
| `get-tree-profile` | Single tree's full profile | Inspect what a tree has learned |
| `get-all-tree-profiles` | All tree profiles | Cross-tree analysis |
| `find-trees-by-problem` | Trees solving a problem | Find similar workflows |
| `find-trees-with-weakness` | Trees with specific failure | Pattern analysis |

### Self-Learning Functions

| Function | Returns | Use Case |
|----------|---------|----------|
| `find-self-patterns` | This tree's strengths + weaknesses | Self-reinforcing learning |
| `build-actionable-context` | Formatted markdown string | Inject into LLM prompts |
| `extract-rules` | Condition-action rules | Explicit pattern mining |

---

## Domain-Agnostic Fields

The self-learning system works for **any domain** - sales, legal, construction, drone control, etc. Use these flexible fields:

### For Strengths

```clojure
{:domain-type "your-domain"           ;; e.g., "legal-review", "sales-outreach"
 :context-conditions {:any-key "value"  ;; Conditions when success happened
                      :another 123}
 :action-taken {:type "action-type"     ;; What led to success
                :target "target"
                :reason "why"}
 :expected-outcome "description"}       ;; What success looks like
```

### For Weaknesses

```clojure
{:domain-type "your-domain"
 :failure-context {:any-key "value"    ;; Conditions when failure happened
                   :another 123}
 :attempted-action {:type "action-type" ;; What was attempted
                    :target "target"}}
```

---

## Complete Example

```clojure
(require '[ai.obney.orc.orc-service.interface :as orc]
         '[ai.obney.orc.ontology.interface :as ontology]
         '[ai.obney.grain.command-processor-v2.interface :as cp])

;; 1. Define workflow with self-learning enabled
(def my-workflow
  (orc/workflow "lead-qualifier"
    (orc/blackboard
      {:lead-data :map
       :qualification [:map
                       [:score :double]
                       [:reasoning :string]
                       [:qualified? :boolean]]})

    (orc/llm "qualify"
      :instruction "Qualify the lead based on BANT criteria."
      :reads [:lead-data]
      :writes [:qualification]
      ;; Enable self-learning
      :context {:problem-type "problem:Classification"
                :self-learning? true})))

;; 2. Build the workflow
(def sheet-id (orc/build-workflow! ctx my-workflow))

;; 3. Execute
(def result (orc/execute ctx sheet-id
              {:lead-data {:company "Acme" :revenue 5000000}}))

;; 4. Record outcome (after human review or automated evaluation)
(when (good-result? result)
  (cp/process-command
    (assoc ctx :command
      {:command/name :ontology/record-tree-strength
       :tree-id sheet-id
       :pattern-uri "success:AccurateQualification"
       :confidence 0.9
       :evidence-trace-ids [(:trace-id result)]
       :avg-score 0.88
       :domain-type "sales"
       :context-conditions {:company-size "enterprise"
                            :has-budget? true}
       :action-taken {:type "bant-analysis"}
       :expected-outcome "accurate qualification"})))

;; 5. Future executions automatically include learned patterns
;; The :context parameter causes injection of patterns into the prompt
```

---

## Testing Self-Learning

Run the integration test to verify everything works:

```clojure
;; 1. Start dev environment
(dev/start!)

;; 2. Load integration test
(load-file "development/src/self_learning_integration_test.clj")

;; 3. Run all tests
(run-all-tests)
```

The tests verify:
- Recording strengths flows through Grain events → read models
- Recording weaknesses works the same way
- `find-self-patterns` returns correct tree's patterns
- Context injection prepends patterns to LLM instructions

---

## Related Documentation

| File | Description |
|------|-------------|
| `docs/ONTOLOGY.md` | Full ontology component reference |
| `docs/FEEDBACK-LOOP.md` | 7-stage continuous improvement cycle |
| `docs/dsl-tutorial.md` | DSL reference including `:context` parameter |
| `development/src/self_learning_integration_test.clj` | Integration tests |
