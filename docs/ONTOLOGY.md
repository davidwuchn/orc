# Ontology Component

The ontology component provides a three-layer semantic knowledge system for understanding AI workflow performance, integrated with the Grain event-sourcing framework and ORC behavior trees.

## Overview

The ontology component enables:

1. **Failure Classification** - Map evaluation results to semantic failure categories
2. **Tree Profiling** - Track strengths, weaknesses, and capabilities per workflow tree
3. **Node Learning** - Aggregate patterns across all nodes of the same type
4. **TTL/SKOS Export** - Serialize ontology to standard RDF formats for graph databases
5. **Embedding Generation** - Create semantic embeddings for concepts and profiles (Phase 4)
6. **Hybrid Search** - Combine graph BFS + embedding similarity via RRF fusion (Phase 4)

### Three-Layer Ontology System

| Layer | Purpose | Examples |
|-------|---------|----------|
| **Failure Ontology** | Why things go wrong | Hallucination, Contradiction, LogicalGap, FormatViolation |
| **Success Ontology** | What makes things work | ValidationLoop, ExplicitSchema, ChainOfThought |
| **Problem Ontology** | What types of problems exist | Classification, Summarization, DataExtraction |

## Architecture

```
                    ┌─────────────────────────────────────────┐
                    │         ONTOLOGY COMPONENT              │
                    ├─────────────────────────────────────────┤
    Commands ──────►│  commands.clj                           │
                    │    - :ontology/initialize-static-ontology│
                    │    - :ontology/record-tree-strength      │
                    │    - :ontology/record-tree-weakness      │
                    │    - :ontology/record-problem-mapping    │
                    │    - :ontology/classify-evaluation       │
                    │    - :ontology/embed-concept (Phase 4)   │
                    │    - :ontology/embed-concepts-batch      │
                    ├─────────────────────────────────────────┤
                    │  Events → Event Store                   │
                    │    - :ontology/concept-created          │
                    │    - :ontology/tree-strength-recorded   │
                    │    - :ontology/tree-weakness-recorded   │
                    │    - :ontology/node-pattern-learned     │
                    │    - :ontology/concept-embedded (Phase 4)│
                    │    - :ontology/tree-profile-embedded    │
                    ├─────────────────────────────────────────┤
                    │  Read Models (Projections)              │
                    │    - concepts* → URI→Concept graph      │
                    │    - tree-profiles* → Strengths/Weak    │
                    │    - node-experiences* → Patterns       │
                    │    - concept-embeddings* → Vectors      │
                    ├─────────────────────────────────────────┤
    Queries ◄──────│  queries.clj + retrieval.clj            │
                    │    - :ontology/get-concepts             │
                    │    - :ontology/get-tree-profile         │
                    │    - :ontology/find-similar-trees       │
                    │    - :ontology/export-ttl               │
                    │    - :ontology/hybrid-search (Phase 4)  │
                    │    - :ontology/semantic-search          │
                    └─────────────────────────────────────────┘
                               │
                               ▼
                    ┌─────────────────────────────────────────┐
                    │         embedding.clj (Phase 4)         │
                    │    - DJL model loading (HuggingFace)    │
                    │    - embed-text → 384-dim vectors       │
                    │    - cosine-similarity                  │
                    │    - detect-embeddable-fields           │
                    │    - search-concepts-by-embedding       │
                    └─────────────────────────────────────────┘
```

## Quick Start

### 1. Access Static Ontology

```clojure
(require '[ai.obney.orc.ontology.interface :as ontology])

;; Get all failure concepts (20+ predefined)
(ontology/get-static-concepts {:scope :failure})

;; Get specific concept
(ontology/get-static-concept-by-uri "failure:Hallucination")
;; => {:uri "failure:Hallucination"
;;     :label "Hallucination"
;;     :description "Generated claims not present in sources"
;;     :scope :failure
;;     :broader ["failure:Grounding"]
;;     :indicators ["hallucinated" "made up" "invented"]}

;; Map evaluation dimension to failure URI
(ontology/get-failure-concept-for-dimension "Grounding")
;; => "failure:Grounding"
```

### 2. Classify Evaluation Results

```clojure
;; Classify an evaluation from the evaluation component
(ontology/classify-evaluation
  {:score 0.4
   :dimensions [{:name "Grounding" :score 0.3
                 :feedback "Output contained hallucinated claims"}
                {:name "Instruction Following" :score 0.9
                 :feedback "Good"}]})

;; Returns:
;; {:failures [{:uri "failure:Hallucination"
;;              :base-uri "failure:Grounding"
;;              :subtype-uri "failure:Hallucination"
;;              :confidence 0.57
;;              :evidence "Output contained hallucinated claims"
;;              :dimension "Grounding"}]
;;  :primary-failure-uri "failure:Hallucination"
;;  :overall-score 0.4}
```

### 3. Record Tree Profile (via Commands)

```clojure
(require '[ai.obney.orc.ontology.core.commands :as cmd])

;; Record a weakness
(cmd/ontology-record-tree-weakness
  (assoc ctx :command
    {:tree-id tree-id
     :failure-uri "failure:Hallucination"
     :frequency 0.3
     :severity :high
     :triggers ["missing context" "ambiguous input"]
     :evidence-trace-ids [trace-id]}))

;; Record a strength
(cmd/ontology-record-tree-strength
  (assoc ctx :command
    {:tree-id tree-id
     :pattern-uri "success:ValidationLoop"
     :confidence 0.85
     :evidence-trace-ids [trace-id-1 trace-id-2]
     :avg-score 0.9}))

;; Record problem mapping
(cmd/ontology-record-problem-mapping
  (assoc ctx :command
    {:tree-id tree-id
     :problem-uri "problem:Classification"
     :success-rate 0.85
     :execution-count 100}))
```

### 4. Query Tree Profile

```clojure
;; Get profile for a specific tree
(ontology/get-tree-profile event-store tree-id)
;; => {:tree-id #uuid "..."
;;     :strengths [{:pattern "success:ValidationLoop"
;;                  :confidence 0.85
;;                  :evidence-count 2
;;                  :avg-score 0.9}]
;;     :weaknesses [{:failure "failure:Hallucination"
;;                   :frequency 0.3
;;                   :severity :high
;;                   :triggers ["missing context"]}]
;;     :solves [{:problem-uri "problem:Classification"
;;               :success-rate 0.85
;;               :execution-count 100}]}
```

### 5. Export to TTL/SKOS

```clojure
;; Export concepts to SKOS Turtle format
(ontology/concepts->turtle concepts {:base-uri "http://example.org/"})

;; Full export with profiles
(ontology/export-turtle event-store
  {:scope :failure
   :include-profiles? true
   :include-experiences? true})
```

## API Reference

### Static Ontology Access

| Function | Description |
|----------|-------------|
| `get-static-concepts` | Get all concepts, optionally filtered by `:scope` |
| `get-static-concept-by-uri` | Look up concept by URI |
| `get-static-relationships` | Get all SKOS relationships |
| `get-failure-concept-for-dimension` | Map evaluation dimension name to failure URI |

### Classification

| Function | Description |
|----------|-------------|
| `classify-evaluation` | Classify evaluation result into failure URIs |
| `classify-trace-evaluations` | Batch classify with aggregation |
| `estimate-severity` | Estimate severity level for a failure |
| `extract-triggers` | Extract trigger phrases from evidence |

### Read Models

| Function | Description |
|----------|-------------|
| `get-concepts` | Query concept graph from events |
| `get-concept-by-uri` | Get single concept by URI |
| `get-tree-profile` | Get tree profile with strengths/weaknesses |
| `get-all-tree-profiles` | Get all tree profiles |
| `get-node-type-learnings` | Get patterns for a node type |
| `get-all-node-learnings` | Get all node patterns |

### Serialization

| Function | Description |
|----------|-------------|
| `concepts->turtle` | Serialize concepts to SKOS Turtle |
| `tree-profile->turtle` | Serialize tree profile to OWL Turtle |
| `tree-profiles->turtle` | Serialize multiple profiles |
| `node-experiences->turtle` | Serialize node patterns |
| `export-turtle` | Full ontology export |
| `validate-turtle` | Basic TTL syntax validation |

## Event Schemas

### Concept Events

```clojure
:ontology/concept-created
[:map
 [:ontology-id :uuid]
 [:concept-id :uuid]
 [:uri :string]                    ;; "failure:Hallucination"
 [:label :string]
 [:description :string]
 [:scope [:enum :failure :success :problem :node-type :custom]]
 [:broader {:optional true} [:vector :string]]
 [:indicators {:optional true} [:vector :string]]]
```

### Tree Profile Events

```clojure
:ontology/tree-strength-recorded
[:map
 [:tree-id :uuid]
 [:pattern-uri :string]            ;; "success:ValidationLoop"
 [:confidence :double]
 [:evidence-trace-ids [:vector :uuid]]
 [:avg-score :double]]

:ontology/tree-weakness-recorded
[:map
 [:tree-id :uuid]
 [:failure-uri :string]            ;; "failure:Hallucination"
 [:subtype-uri {:optional true} :string]
 [:frequency :double]
 [:severity [:enum :low :medium :high :critical]]
 [:triggers [:vector :string]]
 [:evidence-trace-ids [:vector :uuid]]]

:ontology/tree-problem-mapping-created
[:map
 [:tree-id :uuid]
 [:problem-uri :string]            ;; "problem:Classification"
 [:success-rate :double]
 [:execution-count :int]]
```

### Node Learning Events

```clojure
:ontology/node-pattern-learned
[:map
 [:node-id :uuid]
 [:sheet-id :uuid]
 [:node-type [:enum :llm :repl-researcher :code :map-each :condition :llm-condition]]
 [:pattern-type [:enum :search :instruction :execution :structural]]
 [:effective? :boolean]
 [:pattern-description :string]
 [:metrics [:map
            [:success-rate {:optional true} :double]
            [:avg-score {:optional true} :double]]]
 [:evidence-trace-ids [:vector :uuid]]]
```

## Integration with ORC Sheets

### Automatic Evaluation Classification

After executing an ORC sheet, classify and record failures:

```clojure
(require '[ai.obney.orc.orc-service.interface :as sheet])
(require '[ai.obney.orc.ontology.core.commands :as ontology-cmd])

;; In a code node executor:
(defn record-evaluation-failures [{:keys [inputs ctx]}]
  (let [result (ontology-cmd/ontology-classify-evaluation
                 (assoc ctx :command
                   {:trace-id (get inputs "trace-id")
                    :sheet-id (get inputs "sheet-id")
                    :node-id (get inputs "node-id")
                    :evaluation-result (get inputs "evaluation-result")
                    :auto-record? true}))]  ;; Auto-emit weakness events
    {"recorded-count" (-> result :command-result/data :recorded-weaknesses)
     "primary-failure" (-> result :command-result/data :classification :primary-failure-uri)}))
```

### Workflow Definition

```clojure
(sheet/workflow "evaluation-recorder"
  (sheet/blackboard
    {:trace-id :uuid
     :sheet-id :uuid
     :node-id :uuid
     :evaluation-result [:map
                         [:score :double]
                         [:dimensions [:vector [:map
                                                [:name :string]
                                                [:score :double]
                                                [:feedback :string]]]]]
     :recorded-count :int
     :primary-failure [:maybe :string]})

  (sheet/sequence "main"
    (sheet/code "record-failures"
      :fn "my.module/record-evaluation-failures"
      :reads ["trace-id" "sheet-id" "node-id" "evaluation-result"]
      :writes ["recorded-count" "primary-failure"])))
```

## Behavior Tree Integration Guide

This section explains how to connect ontologies to ORC behavior trees for intelligent context retrieval during workflow execution.

### Three Integration Patterns

#### Pattern A: Blackboard Initialization

Pass ontology data as initial inputs when executing a sheet:

```clojure
(require '[ai.obney.orc.orc-service.interface :as sheet])
(require '[ai.obney.orc.ontology.interface :as ontology])

;; Load ontology data at execution time
(sheet/execute ctx sheet-id
  {"ontology-concepts" (ontology/get-static-concepts {:scope :failure})
   "problem-type" "problem:Classification"
   "failure-patterns" (ontology/find-failure-patterns event-store
                        {:problem-type "problem:Classification"})
   "source-text" user-input})
```

Nodes can then read these via `:reads`:

```clojure
(sheet/llm "classify-with-context"
  :instruction "Classify the input. Known failure patterns: {{failure-patterns}}"
  :reads ["source-text" "failure-patterns"]
  :writes ["classification"])
```

#### Pattern B: Code Node Ontology Lookup

Code nodes can query ontologies directly during execution:

```clojure
(defn search-ontology-for-context
  "Code node executor that searches ontology for relevant context."
  [{:keys [inputs]}]
  (let [problem-type (get inputs "problem-type")
        ;; Build graph from static concepts
        graph (ontology/concepts->graph (ontology/get-static-concepts))
        ;; BFS expansion from problem type
        related (ontology/bfs-spreading-activation graph [problem-type]
                  {:max-depth 2 :decay 0.6})
        ;; Format for LLM consumption
        context-str (str "Related concepts:\n"
                        (->> related
                             (take 10)
                             (map #(str "- " (:uri %) " (score: " (:score %) ")"))
                             (clojure.string/join "\n")))]
    {"ontology-context" context-str
     "related-concepts" related}))

;; In workflow definition
(sheet/code "fetch-ontology-context"
  :fn "my.ns/search-ontology-for-context"
  :reads ["problem-type"]
  :writes ["ontology-context" "related-concepts"])
```

#### Pattern C: LLM Instruction with Ontology Context

Prepare ontology context in a code node, then use it in an LLM instruction:

```clojure
(sheet/sequence "classify-with-ontology"
  ;; Step 1: Fetch ontology context
  (sheet/code "prepare-context"
    :fn "my.ns/search-ontology-for-context"
    :reads ["problem-type"]
    :writes ["ontology-context"])

  ;; Step 2: LLM uses the context
  (sheet/llm "classify"
    :model "google/gemini-2.5-flash"
    :instruction "You are classifying failures using this ontology:

{{ontology-context}}

Analyze the evaluation and identify which failure concepts apply."
    :reads ["evaluation" "ontology-context"]
    :writes ["failure-classification"]))
```

#### Pattern D: Automatic Context Injection (Recommended)

The `:context` parameter on LLM nodes automates ontology injection - no code nodes needed:

```clojure
(sheet/llm "analyze-with-ontology"
  :model "google/gemini-2.5-flash"
  :instruction "Analyze this lead and identify qualification factors."
  :reads ["lead-data"]
  :writes ["analysis"]
  :context {:problem-type "problem:Classification"
            :domain "sales"
            :include #{:patterns :failures :related}
            :max-items 5})
```

**How it works:**

1. **At execution time**, the executor calls `build-ontology-context` with your config
2. **Retrieves** success patterns, failure patterns, related concepts, and similar trees
3. **Formats** results as markdown via `format-context-for-llm`
4. **Prepends** the formatted context to your instruction

**Context options:**

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `:problem-type` | string | nil | Ontology problem URI |
| `:domain` | string | nil | User domain filter |
| `:include` | set | `#{:patterns :related}` | Sections to include |
| `:max-items` | int | 5 | Max items per section |

**Available sections for `:include`:**

| Section | Content |
|---------|---------|
| `:hierarchy` | Problem type label, description, parent/children |
| `:patterns` | Recommended success patterns |
| `:failures` | Failure patterns to avoid |
| `:related` | Related concepts via BFS expansion |
| `:examples` | Similar successful trees (few-shot examples) |

**Example output injected into prompt:**

```markdown
## Relevant Knowledge

### Problem Context
**Type:** Classification
**Description:** Categorizing items into predefined classes

### Recommended Patterns
- **Explicit Schema**: Define clear output structure with field types
- **Chain of Thought**: Use step-by-step reasoning before conclusions

### Related Concepts
- **Entity Extraction**: Identifying and extracting named entities (score: 0.85)
- **Hallucination**: Generated claims not present in sources (score: 0.72)
```

**When to use Pattern D vs Pattern C:**

| Use Case | Pattern |
|----------|---------|
| Standard ontology injection | **Pattern D** (`:context` param) |
| Custom ontology queries | Pattern C (code node) |
| Dynamic problem type selection | Pattern C (code node) |
| Embedding-based semantic search | Pattern C (code node) |

**Error handling:**

If ontology context building fails (e.g., event store unavailable), the LLM executes with the original instruction - no failure occurs.

### Node Access Patterns

Different node types have different levels of ontology access:

| Node Type | Ontology Access | How to Access |
|-----------|-----------------|---------------|
| `llm` | **Built-in (Pattern D)** | Via `:context` parameter |
| `llm` | Via preparation | Through `:reads` from blackboard (Pattern C) |
| `code` | **Full access** | Direct function calls in executor |
| `condition` | None (static) | Cannot call functions |
| `llm-condition` | Via preparation | Through `:reads` from blackboard |
| `map-each` | Per-item | Code nodes inside loop can query |
| `parallel` | Per-branch | Independent queries in each branch |

### Multi-Ontology Parallel Search

Search multiple ontology scopes simultaneously using parallel branches:

```clojure
(sheet/parallel "search-all-ontologies"
  ;; Branch 1: Search failure ontology
  (sheet/code "search-failures"
    :fn "my.ns/search-failure-ontology"
    :reads ["evaluation"]
    :writes ["failure-matches"])

  ;; Branch 2: Search success patterns
  (sheet/code "search-success"
    :fn "my.ns/search-success-ontology"
    :reads ["evaluation"]
    :writes ["success-matches"])

  ;; Branch 3: Search problem types
  (sheet/code "search-problems"
    :fn "my.ns/search-problem-ontology"
    :reads ["evaluation"]
    :writes ["problem-matches"]))

;; After parallel completes, merge results
(sheet/code "merge-ontology-results"
  :fn "my.ns/merge-results"
  :reads ["failure-matches" "success-matches" "problem-matches"]
  :writes ["combined-ontology-context"])
```

**Example executor for parallel search:**

```clojure
(defn search-failure-ontology [{:keys [inputs]}]
  (let [evaluation (get inputs "evaluation")
        feedback (get-in evaluation ["dimensions" 0 "feedback"])
        ;; Semantic search for similar failures
        matches (ontology/semantic-search-concepts event-store feedback
                  :scope :failure :limit 5)]
    {"failure-matches" matches}))

(defn search-success-ontology [{:keys [inputs]}]
  (let [evaluation (get inputs "evaluation")
        ;; Find patterns from high-performing trees
        patterns (ontology/find-success-patterns event-store
                   {:min-success-rate 0.8 :limit 5})]
    {"success-matches" patterns}))

(defn merge-results [{:keys [inputs]}]
  (let [failures (get inputs "failure-matches")
        successes (get inputs "success-matches")
        problems (get inputs "problem-matches")]
    {"combined-ontology-context"
     {:failures-to-avoid failures
      :patterns-to-use successes
      :problem-context problems}}))
```

### Blackboard Schema for Ontology Data

Define explicit schemas for ontology data on the blackboard:

```clojure
(sheet/blackboard
  {;; === Ontology Inputs ===
   :problem-type [:string {:description "Problem URI (e.g., 'problem:Classification')"}]

   ;; === Ontology Context (retrieved) ===
   :ontology-context [:string {:description "Formatted context for LLM instruction"}]

   :related-concepts [:vector [:map
                               [:uri :string]
                               [:label :string]
                               [:score :double]
                               [:depth :int]]]

   :failure-matches [:vector [:map
                              [:uri :string]
                              [:similarity :double]
                              [:label :string]]]

   :success-patterns [:vector [:map
                               [:pattern-uri :string]
                               [:avg-confidence :double]
                               [:tree-count :int]]]

   ;; === Combined Context ===
   :combined-ontology-context [:map
                               [:failures-to-avoid [:vector :map]]
                               [:patterns-to-use [:vector :map]]
                               [:problem-context [:vector :map]]]})
```

## Ontology Lifecycle

### Creating New Ontologies

The system includes a built-in three-layer ontology (failure, success, problem). You can extend it:

```clojure
;; Initialize the static ontology (built-in concepts)
(ontology/initialize-static-ontology)

;; Add custom concepts to extend the ontology
(require '[ai.obney.orc.ontology.core.commands :as cmd])

(cmd/ontology-create-concept
  (assoc ctx :command
    {:ontology-id ontology-id
     :uri "custom:MyDomainConcept"
     :label "My Domain Concept"
     :description "A custom concept for my domain"
     :scope :custom
     :broader ["problem:Analysis"]  ;; Parent concept
     :indicators ["indicator1" "indicator2"]}))

;; Create relationships between concepts
(cmd/ontology-create-relationship
  (assoc ctx :command
    {:source-uri "custom:MyDomainConcept"
     :target-uri "custom:RelatedConcept"
     :predicate "skos:related"}))
```

### Evolutionary Data Flow

Data flows through the system and becomes searchable over time:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Tree Execution → Evaluation → Classification → Recording → Embedding  │
│       │               │              │               │           │      │
│       └───────────────┴──────────────┴───────────────┴───────────┘      │
│                          Event Sourcing (Grain)                         │
│                                   │                                     │
│                                   ▼                                     │
│                        Read Models (Projections)                        │
│                                   │                                     │
│                                   ▼                                     │
│                          Searchable Context                             │
└─────────────────────────────────────────────────────────────────────────┘
```

### Adding Data Over Time

Record learnings as trees execute and get evaluated:

```clojure
;; Record a successful pattern usage
(cmd/ontology-record-tree-strength
  (assoc ctx :command
    {:tree-id tree-id
     :pattern-uri "success:ValidationLoop"
     :confidence 0.92
     :evidence-trace-ids [trace-id-1 trace-id-2]
     :avg-score 0.88}))

;; Record a failure occurrence
(cmd/ontology-record-tree-weakness
  (assoc ctx :command
    {:tree-id tree-id
     :failure-uri "failure:Hallucination"
     :frequency 0.15
     :severity :high
     :triggers ["missing context" "ambiguous input"]
     :evidence-trace-ids [trace-id]}))

;; Map tree to problem type
(cmd/ontology-record-problem-mapping
  (assoc ctx :command
    {:tree-id tree-id
     :problem-uri "problem:Classification"
     :success-rate 0.85
     :execution-count 100}))

;; Record node-level patterns
(cmd/ontology-record-node-pattern
  (assoc ctx :command
    {:node-id node-id
     :sheet-id sheet-id
     :node-type :llm
     :pattern-type :instruction
     :effective? true
     :pattern-description "Chain-of-thought prompting"
     :metrics {:success-rate 0.9 :avg-score 0.85}
     :evidence-trace-ids [trace-id]}))
```

### Discovering New Concepts

Propose new failure subtypes based on observed patterns:

```clojure
;; Propose a new failure subtype
(cmd/ontology-propose-failure-subtype
  (assoc ctx :command
    {:parent-uri "failure:Hallucination"
     :proposed-uri "failure:TemporalHallucination"
     :label "Temporal Hallucination"
     :description "Made-up dates, times, or temporal sequences"
     :indicators ["wrong date" "incorrect timeline" "temporal confusion"]
     :evidence-count 15}))
```

### Referencing Ontologies in Future Sheets

Query the event store to retrieve learned knowledge:

```clojure
;; Get tree profile (accumulated learnings)
(ontology/get-tree-profile event-store tree-id)

;; Find similar trees for few-shot examples
(ontology/find-similar-trees event-store
  {:problem-type "problem:Classification"
   :min-success-rate 0.8})

;; Build comprehensive context for generation
(ontology/build-ontology-context event-store
  {:problem-type "problem:Classification"
   :required-patterns #{"success:ValidationLoop"}})
```

## Search Guide

### Search Flexibility - You Are NOT Locked to RRF

The ontology component provides **three independent search modes**. Use whichever fits your needs:

| Mode | Function | Best For | Speed |
|------|----------|----------|-------|
| **Graph-only** | `bfs-spreading-activation` | Structural relationships | ~1-5ms |
| **Embedding-only** | `semantic-search-concepts` | Meaning similarity | ~10-50ms |
| **Hybrid (RRF)** | `hybrid-search` | Best of both | ~15-60ms |

### Graph-Only Search

Fast structural traversal based on SKOS relationships:

```clojure
;; Build graph from concepts
(def graph (ontology/concepts->graph (ontology/get-static-concepts)))

;; BFS spreading activation
(ontology/bfs-spreading-activation graph ["failure:Grounding"]
  {:max-depth 2        ;; How many hops
   :decay 0.6          ;; Score decay per hop
   :min-activation 0.01})

;; Returns:
;; [{:uri "failure:Grounding" :score 1.0 :depth 0}
;;  {:uri "failure:Hallucination" :score 0.54 :depth 1}
;;  {:uri "failure:Contradiction" :score 0.54 :depth 1}
;;  {:uri "failure:FactHallucination" :score 0.29 :depth 2}
;;  ...]

;; Fast link expansion (2-3 hops)
(ontology/link-expansion graph ["problem:Classification"] :hops 2)
```

**When to use:** You know the starting concept and want structurally related concepts.

### Embedding-Only Search

Semantic similarity search using embeddings:

```clojure
;; Requires embeddings to be generated first
(ontology/semantic-search-concepts event-store
  "the model made up facts that weren't in the source"
  :scope :failure
  :limit 10
  :min-similarity 0.5)

;; Returns:
;; [{:uri "failure:Hallucination" :similarity 0.89 :label "Hallucination"}
;;  {:uri "failure:FactHallucination" :similarity 0.84 :label "Fact Hallucination"}
;;  {:uri "failure:Misattribution" :similarity 0.71 :label "Misattribution"}
;;  ...]

;; Search tree profiles
(ontology/semantic-search-tree-profiles event-store
  "workflow for document classification with validation")
```

**When to use:** You have natural language and want conceptually similar matches.

### Hybrid Search (RRF Fusion)

Combines graph structure + semantic similarity via Reciprocal Rank Fusion:

```clojure
(ontology/hybrid-search event-store
  {:seed-uris ["failure:Grounding"]     ;; Starting points for graph BFS
   :query-text "made up facts"          ;; Text for embedding search
   :scope :failure
   :limit 10
   :min-similarity 0.3
   :max-depth 2
   :decay 0.6})

;; Returns:
;; {:results [{:uri "failure:Hallucination"
;;             :score 0.033               ;; RRF fused score
;;             :graph-rank 2              ;; Position in BFS results
;;             :embedding-rank 1          ;; Position in embedding results
;;             :label "Hallucination"}
;;            ...]
;;  :graph-results [...]
;;  :embedding-results [...]
;;  :method "rrf"
;;  :batches-used [:graph :embedding]}
```

**RRF Formula:** `score(d) = sum(1 / (k + rank_i))` where k=60

**When to use:** You want the most comprehensive results combining structure and meaning.

### Specialized Hybrid Searches

```clojure
;; Search only failures
(ontology/hybrid-search-failures event-store
  "output contained invented information"
  :seed-failure-uri "failure:Grounding"
  :limit 5)

;; Search only success patterns
(ontology/hybrid-search-patterns event-store
  "validate output before returning"
  :seed-pattern-uri "success:StructuralPattern"
  :limit 5)
```

### Manual Search (Outside Behavior Trees)

You can search ontologies directly from the REPL or any code:

```clojure
(require '[ai.obney.orc.ontology.interface :as ontology])

;; 1. Direct static concept lookup (instant)
(ontology/get-static-concept-by-uri "failure:Hallucination")
;; => {:uri "failure:Hallucination"
;;     :label "Hallucination"
;;     :description "Generated claims not present in sources"
;;     :broader ["failure:Grounding"]
;;     :indicators ["hallucinated" "made up" "invented"]}

;; 2. Get all concepts by scope
(ontology/get-static-concepts {:scope :failure})
(ontology/get-static-concepts {:scope :success})
(ontology/get-static-concepts {:scope :problem})

;; 3. Graph traversal
(let [graph (ontology/concepts->graph (ontology/get-static-concepts))]
  (ontology/bfs-spreading-activation graph ["failure:Grounding"]
    {:max-depth 2}))

;; 4. Few-shot tree retrieval
(ontology/find-similar-trees event-store
  {:problem-type "problem:Classification"
   :min-success-rate 0.8
   :required-patterns #{"success:ValidationLoop"}
   :limit 5})

;; 5. Failure pattern aggregation
(ontology/find-failure-patterns event-store
  {:problem-type "problem:Classification"
   :min-frequency 0.2})

;; 6. Success pattern discovery
(ontology/find-success-patterns event-store
  {:min-success-rate 0.8
   :min-confidence 0.7})

;; 7. Full context building (for MCP sheet builder)
(ontology/build-ontology-context event-store
  {:problem-type "problem:Classification"
   :required-patterns #{"success:ExplicitSchema"}
   :user-domain "education"})
;; Returns: {:few-shot-trees [...] :recommended-patterns [...]
;;           :patterns-to-avoid [...] :related-concepts [...]}

;; 8. Enhanced context with embeddings
(ontology/build-ontology-context-with-embeddings event-store
  {:problem-type "problem:Classification"
   :problem-description "categorize customer support tickets"})
```

## Dynamic Field Detection (Phase 4B)

When embedding data, you need to determine which fields contain semantic content worth embedding. Phase 4B provides two approaches:

### Heuristic Detection (No LLM)

Fast, rule-based detection using field names and data samples:

```clojure
(ontology/detect-embeddable-fields-heuristic schema sample-data)

;; Example:
(ontology/detect-embeddable-fields-heuristic
  [:map
   [:id :uuid]
   [:label :string]
   [:description :string]
   [:strategic-positioning :string]
   [:created-at :string]
   [:indicators [:vector :string]]]
  [{:id #uuid "..."
    :label "Acme Corp"
    :description "Enterprise software company"
    :strategic-positioning "Focus on cloud migration with executive sponsorship"
    :created-at "2024-01-15"
    :indicators ["cloud" "enterprise"]}])

;; Returns:
;; {:embeddable-fields [:label :description :strategic-positioning :indicators]
;;  :confidence-scores {:label 0.8 :description 0.8 :strategic-positioning 0.7
;;                      :indicators 0.6 :id 0.02 :created-at 0.05}
;;  :reasoning {:label "Field name matches semantic pattern"
;;              :strategic-positioning "String field with substantial average length"
;;              :id "Non-string type not suitable for embedding"}
;;  :method :heuristic}
```

**Heuristics used:**
- Field names matching semantic patterns (`:description`, `:feedback`, `:label`, etc.)
- String fields with average length > 50 characters
- Vector of strings (`:indicators`, `:triggers`, `:keywords`)
- Excludes IDs, timestamps, URLs, emails

### LLM-Driven Analysis (Explicit Step)

For novel field names or when you need more accurate detection:

```clojure
;; Explicit analysis step - you control when LLM is called
(ontology/analyze-fields-for-embedding ctx schema
  :sample-data [record1 record2 record3]
  :confidence-threshold 0.7)

;; Returns:
;; {:embeddable-fields [:strategic-positioning :executive-engagement-history]
;;  :confidence-scores {:strategic-positioning 0.94
;;                      :executive-engagement-history 0.89
;;                      :revenue-tier 0.15
;;                      :account-id 0.02}
;;  :reasoning {:strategic-positioning "Rich natural language describing business context"
;;              :account-id "Technical identifier, not semantic content"}
;;  :method :llm
;;  :trace-id #uuid "..."}
```

### Usage Workflow

```clojure
;; Step 1: Analyze schema (explicit, can review results)
(def analysis (ontology/analyze-fields-for-embedding ctx crm-schema
                :sample-data sample-records
                :confidence-threshold 0.7))

;; Step 2: Review recommendations
(println "Recommended fields:" (:embeddable-fields analysis))
(println "Reasoning:" (:reasoning analysis))

;; Step 3: Optionally modify
(def final-fields (conj (:embeddable-fields analysis) :extra-field))

;; Step 4: Embed using chosen fields
(ontology/embed-with-fields record final-fields)
;; => "Strategic positioning text. Executive engagement history text. ..."
```

### Demo

Run the field analyzer demo:

```clojure
(require '[field-analyzer-demo :refer :all])

;; Heuristic demos (no LLM required)
(run-demo)

;; LLM-based analysis (requires running context)
(demo-llm-analysis ctx)
```

## Performance Characteristics

### Operation Timing

| Operation | Complexity | Typical Time | Notes |
|-----------|-----------|--------------|-------|
| Static concept lookup | O(1) | <1ms | In-memory map lookup |
| BFS 2-hop traversal | O(V+E) | 1-5ms | Decay limits expansion |
| Link expansion | O(S×D) | <1ms | S=seeds, D=avg degree |
| Embedding generation | O(text_len) | 10-30ms | First call loads model (~2s) |
| Semantic search | O(C×D) | 10-50ms | C=concepts, D=dimensions |
| Hybrid search (RRF) | O(B×logB) | 15-60ms | B=batch size |
| LLM field analysis | O(fields) | 500-2000ms | Depends on LLM |

### Scale Characteristics

```
Graph Size:
├── Static concepts: ~50 (failure + success + problem)
├── With event-sourced additions: scales to 1000s
└── Embedding dimensions: 384 per concept

Memory:
├── Static ontology: ~100KB
├── Embedding model: ~90MB (loaded once)
└── Concept embeddings: 384 × 8 bytes × concept_count
```

### Configuration Sweet Spots

```clojure
;; BFS spreading activation
{:max-depth 2          ;; 3-hop neighborhood
 :decay 0.6            ;; 60% activation per hop
 :min-activation 0.01} ;; Stop at 1% activation

;; RRF fusion
{:k 60}                ;; Original paper constant

;; Semantic search
{:min-similarity 0.3   ;; Catches related concepts
 :limit 10}            ;; Reasonable result set

;; Hybrid search
{:weights {:graph 1.0 :embedding 1.0}}  ;; Equal balance
```

## Tag Strategy for Multi-Level Scoping

Events use tags for efficient querying:

```clojure
(->event
  {:type :ontology/tree-weakness-recorded
   :tags #{[:ontology ontology-id]      ;; Query all events in ontology
           [:tree tree-id]              ;; Query by tree
           [:failure failure-uri]       ;; Query by failure type
           [:domain domain-name]        ;; Query by domain
           [:node-type node-type]}      ;; Query by node type
   :body {...}})
```

Query examples:

```clojure
;; All events for a specific tree
(es/read event-store {:types tree-profile-events
                      :tags #{[:tree tree-id]}})

;; All hallucination failures
(es/read event-store {:types #{:ontology/tree-weakness-recorded}
                      :tags #{[:failure "failure:Hallucination"]}})

;; All LLM node patterns
(es/read event-store {:types node-learning-events
                      :tags #{[:node-type :llm]}})
```

## Static Ontology Reference

### Failure Ontology (20+ concepts)

**Level 1 (Evaluation Dimensions):**
- `failure:Grounding` - Output not supported by input
- `failure:InstructionFollowing` - Did not follow instructions
- `failure:Reasoning` - Logical defects
- `failure:Completeness` - Missing content

**Level 2 (Subtypes):**

| Parent | Subtypes |
|--------|----------|
| Grounding | Hallucination, FactHallucination, RelationshipHallucination, Contradiction, Misattribution |
| InstructionFollowing | FormatViolation, ConstraintViolation, RequirementMissed, ScopeViolation |
| Reasoning | LogicalGap, UnjustifiedLeap, CircularReasoning, FalseEquivalence |
| Completeness | MissingEntity, InsufficientDetail, TruncatedOutput, PartialCoverage |

### Success Ontology (15+ patterns)

**Structural Patterns:**
- ValidationLoop, FallbackRecovery, ParallelIndependent, SequentialPipeline, IterativeRefinement

**Instruction Patterns:**
- ExplicitSchema, FewShotExamples, ChainOfThought, ExplicitConstraints, ContextGrounding

**Data Flow Patterns:**
- MultiSourceGathering, ProgressiveEnrichment, ExplicitBlackboardKeys, ScopedContext

### Problem Ontology (15+ types)

**Information Retrieval:**
- DocumentSearch, DataExtraction, KnowledgeQuery, ResearchGathering

**Content Generation:**
- Summarization, Translation, CreativeWriting, StructuredOutput

**Analysis:**
- Classification, Scoring, Comparison, QualityAssessment, SentimentAnalysis

**Workflow:**
- MultiStepProcess, ConditionalBranching, DataPipeline

## File Structure

```
components/ontology/
├── deps.edn
└── src/ai/obney/workshop/ontology/
    ├── interface.clj              ;; Public API
    ├── interface/schemas.clj      ;; Malli schemas
    └── core/
        ├── static_ontology.clj    ;; Static concept definitions
        ├── read_models.clj        ;; Event projections (incl. embeddings)
        ├── commands.clj           ;; Command handlers (incl. embedding cmds)
        ├── queries.clj            ;; Query handlers
        ├── serialization.clj      ;; TTL export
        ├── classifier.clj         ;; Evaluation classification
        ├── graph.clj              ;; BFS, RRF, temporal (Phase 3)
        ├── retrieval.clj          ;; Few-shot + hybrid retrieval (Phase 3-4)
        └── embedding.clj          ;; DJL embeddings, schema analysis (Phase 4)
```

## Phase 3: Graph Traversal & Few-Shot Retrieval

### Graph Algorithms

The ontology component includes powerful graph traversal algorithms for semantic search:

```clojure
;; BFS spreading activation with exponential decay
(ontology/bfs-spreading-activation
  graph
  ["failure:Hallucination"]  ;; seed concepts
  {:max-depth 2 :decay 0.5})
;; => [{:uri "failure:Hallucination" :score 1.0 :depth 0}
;;     {:uri "failure:Grounding" :score 0.45 :depth 1}
;;     {:uri "failure:FactHallucination" :score 0.425 :depth 1}
;;     ...]

;; Fast 2-3 hop link expansion
(ontology/link-expansion graph ["problem:Classification"] :hops 2)

;; RRF (Reciprocal Rank Fusion) for merging multiple result sets
(ontology/compute-rrf-scores
  [[{:uri "A" :score 0.9} {:uri "B" :score 0.8}]
   [{:uri "B" :score 0.95} {:uri "C" :score 0.7}]])
;; => [["B" 0.0328] ["A" 0.0164] ["C" 0.0161]]  ;; B ranks highest

;; Temporal relevance scoring
(ontology/compute-temporal-relevance 2023 2024)  ;; => 0.85
(ontology/compute-temporal-relevance 2024 2024)  ;; => 1.0
(ontology/compute-temporal-relevance 2025 2024)  ;; => 1.1 (future boost)
```

### Few-Shot Retrieval

Find similar trees and patterns to guide new workflow generation:

```clojure
;; Find similar successful trees for a problem type
(ontology/find-similar-trees event-store
  {:problem-type "problem:Classification"
   :required-patterns #{"success:ValidationLoop"}
   :min-success-rate 0.8
   :limit 5})
;; => [{:tree-id #uuid "..."
;;      :profile {...}
;;      :score 0.85
;;      :matching-patterns ["success:ValidationLoop"]
;;      :problem-match {:uri "problem:Classification" :success-rate 0.88}}
;;     ...]

;; Find common failure patterns to avoid
(ontology/find-failure-patterns event-store
  {:problem-type "problem:Classification"
   :min-frequency 0.2})
;; => [{:failure-uri "failure:Hallucination"
;;      :total-occurrences 15
;;      :avg-frequency 0.28
;;      :severity-distribution {:high 8 :medium 5 :low 2}
;;      :common-triggers ["missing context" "ambiguous input"]}
;;     ...]

;; Find effective success patterns
(ontology/find-success-patterns event-store
  {:problem-type "problem:Classification"
   :min-success-rate 0.8
   :min-confidence 0.7})
;; => [{:pattern-uri "success:ValidationLoop"
;;      :label "Validation Loop"
;;      :avg-confidence 0.87
;;      :tree-count 12}
;;     ...]
```

### MCP Sheet Builder Integration

Build comprehensive context for AI workflow generation:

```clojure
(ontology/build-ontology-context event-store
  {:problem-type "problem:Classification"
   :required-patterns #{"success:ExplicitSchema"}
   :user-domain "education"})

;; Returns:
{:few-shot-trees [...]           ;; Similar successful trees
 :recommended-patterns [...]     ;; Success patterns to use
 :patterns-to-avoid [...]        ;; Common failure patterns
 :related-concepts [...]         ;; Neighborhood expansion
 :problem-hierarchy              ;; Problem with parents/children
   {:uri "problem:Classification"
    :label "Classification"
    :broader [{:uri "problem:Analysis" :label "Analysis"}]
    :narrower []}
 :context-metadata
   {:problem-type "problem:Classification"
    :generated-at "2024-01-15T..."}}
```

### Concept Neighborhood Expansion

Expand from seed concepts using BFS:

```clojure
;; Expand from a problem type to find related concepts
(ontology/expand-concept-neighborhood
  ["problem:Classification"]
  :max-depth 2
  :decay 0.6)
;; => [{:uri "problem:Classification" :score 1.0 :depth 0}
;;     {:uri "problem:Analysis" :score 0.54 :depth 1}
;;     {:uri "problem:Scoring" :score 0.37 :depth 1}
;;     ...]
```

## Testing

Run the integration tests:

```clojure
;; In REPL
(require '[ontology-integration-test :as oit])
(oit/run-all-tests)

;; Quick test
(oit/quick-test)

;; Phase 3 tests specifically
(oit/test-graph-traversal)
(oit/test-few-shot-retrieval)
(oit/test-simulated-retrieval)
```

Run unit tests:

```clojure
(require '[clojure.test :refer [run-tests]])
(require 'ai.obney.orc.ontology.core-test)
(run-tests 'ai.obney.orc.ontology.core-test)
```

### Verification Suite

The verification suite tests all ontology infrastructure including algorithms, projections, and integrations:

```clojure
(require '[ontology-verification :as ov])
(ov/run-all-verifications)
```

#### What Gets Verified

| Test | Purpose |
|------|---------|
| RRF Fusion | Reciprocal Rank Fusion algorithm (k=60) |
| BFS Spreading Activation | Graph traversal with exponential decay |
| Concept Graph | Three-layer ontology structure (failure/success/problem) |
| Tree Profiles | Projection of tree strength/weakness/mapping events |
| Node Learnings | Aggregation of node pattern events |
| Hybrid Search | BFS + embedding fusion via RRF |
| Context Formatting | Markdown output generation for LLM prompts |
| Context Injection | Integration with LLM node `:context` parameter |
| Ontology Context | Interface function connectivity |

#### Expected Output

```
SUMMARY
------------------------------------------------------------
Passed: 9/9
Skipped: 0
Failed: 0
```

#### Test Data

The verification suite includes static test data for testing projections without a live event store:

- **Tree profile events**: Strengths, weaknesses, problem mappings
- **Node learning events**: Effective and ineffective patterns

See `development/src/ontology_verification.clj` for implementation details.

## Phase 4: Embedding Integration & Hybrid Search

### Overview

Phase 4 adds semantic embeddings to the ontology, enabling:
- **Embedding Generation** - Convert concepts and profiles to 384-dim vectors using DJL/HuggingFace
- **Semantic Search** - Find concepts by meaning, not just graph structure
- **Hybrid Search** - Combine graph BFS + embedding similarity via RRF fusion
- **Schema Analysis** - Automatically detect embeddable fields in Malli schemas

### Embedding Generation

Generate embeddings using DJL (Deep Java Library) with HuggingFace sentence-transformers:

```clojure
;; Generate embedding for text (384 dimensions)
(ontology/embed-text "The model hallucinated facts about the company")
;; => [0.0234 -0.0891 0.1523 ...] (384 doubles)

;; Batch embedding
(ontology/embed-texts-batch
  ["Classification task" "Information retrieval" "Text generation"])

;; Compute similarity between embeddings
(ontology/cosine-similarity embedding-a embedding-b)
;; => 0.847 (range -1 to 1)
```

### Concept-to-Text Conversion

Convert concepts to text suitable for embedding:

```clojure
(ontology/concept->embedding-text
  {:label "Hallucination"
   :description "Generated claims not present in sources"
   :indicators ["hallucinated" "made up" "invented"]}
  #{:label :description :indicators})
;; => "Hallucination. Generated claims not present in sources. Indicators: hallucinated, made up, invented"

(ontology/tree-profile->embedding-text profile)
;; => "Strengths: ValidationLoop, ExplicitSchema. Weaknesses: Hallucination. Solves: Classification"
```

### Schema Analysis for Embeddable Fields

Automatically detect which fields in a Malli schema should be embedded:

```clojure
(ontology/detect-embeddable-fields
  [:map
   [:id :uuid]
   [:label :string]
   [:description :string]
   [:created-at :string]
   [:indicators [:vector :string]]
   [:feedback :string]])

;; => {:embeddable-fields [:label :description :indicators :feedback]
;;     :reasoning {:label "Semantic field name indicates natural language content"
;;                 :description "Semantic field name indicates natural language content"
;;                 :indicators "Vector field with semantic content items"
;;                 :feedback "Semantic field name indicates natural language content"}}
```

**Heuristics:**
- Field names like `:description`, `:feedback`, `:label`, `:instructions` are semantic
- Vector fields like `:indicators`, `:triggers`, `:keywords` contain embeddable items
- Fields with `:description` metadata property are embeddable
- Excludes `:id`, `:uuid`, `:uri`, `:created-at`, etc.

### Embedding Commands (Event-Sourced)

Embed concepts and store embeddings in the event store:

```clojure
(require '[ai.obney.orc.ontology.core.commands :as cmd])

;; Configure embedding model for a scope
(cmd/ontology-configure-embedding-model
  (assoc ctx :command
    {:scope :failure
     :model-id "sentence-transformers/all-MiniLM-L6-v2"
     :dimensions 384}))

;; Embed a single concept
(cmd/ontology-embed-concept
  (assoc ctx :command
    {:uri "failure:Hallucination"
     :fields #{:label :description :indicators}}))

;; Batch embed all concepts in a scope
(cmd/ontology-embed-concepts-batch
  (assoc ctx :command
    {:scope :failure
     :fields #{:label :description :indicators}}))

;; Embed a tree profile
(cmd/ontology-embed-tree-profile
  (assoc ctx :command
    {:tree-id tree-id}))
```

### Embedding Events

```clojure
:ontology/embedding-model-configured
[:map
 [:ontology-id :uuid]
 [:scope [:enum :failure :success :problem :node-type :custom :all]]
 [:model-id :string]           ;; "sentence-transformers/all-MiniLM-L6-v2"
 [:dimensions :int]            ;; 384
 [:configured-at :string]]

:ontology/concept-embedded
[:map
 [:concept-id :uuid]
 [:uri :string]                ;; "failure:Hallucination"
 [:text-embedded :string]      ;; Source text that was embedded
 [:field-source :string]       ;; "label+description+indicators"
 [:embedding [:vector :double]];; 384-dim vector
 [:model-id :string]
 [:embedded-at :string]]

:ontology/tree-profile-embedded
[:map
 [:tree-id :uuid]
 [:text-embedded :string]      ;; Serialized profile summary
 [:embedding [:vector :double]]
 [:model-id :string]
 [:embedded-at :string]]
```

### Semantic Search

Search concepts using embedding similarity:

```clojure
;; Search failure concepts by semantic similarity
(ontology/semantic-search-concepts event-store
  "model made up facts that weren't true"
  :scope :failure
  :limit 5
  :min-similarity 0.5)

;; => [{:uri "failure:Hallucination"
;;      :similarity 0.892
;;      :label "Hallucination"
;;      :description "Generated claims not present in sources"}
;;     {:uri "failure:FactHallucination"
;;      :similarity 0.845
;;      :label "Fact Hallucination"
;;      :description "Made-up facts or statistics"}
;;     ...]

;; Search tree profiles by similarity
(ontology/semantic-search-tree-profiles event-store
  "workflow for classifying documents with validation"
  :limit 5)
;; => [{:tree-id #uuid "..."
;;      :similarity 0.78
;;      :profile {...}}]
```

### Hybrid Search (Graph + Embeddings via RRF)

Combine graph-based BFS with embedding similarity using RRF fusion:

```clojure
(ontology/hybrid-search event-store
  {:seed-uris ["failure:Grounding"]           ;; Starting points for graph BFS
   :query-text "model hallucinated facts"     ;; Text for embedding search
   :scope :failure
   :limit 10
   :min-similarity 0.3
   :max-depth 2                               ;; BFS depth
   :decay 0.6                                 ;; BFS activation decay
   :weights {:graph 1.0 :embedding 1.0}})     ;; Balance between signals

;; Returns:
{:results
 [{:uri "failure:Hallucination"
   :score 0.0328                              ;; RRF fused score
   :graph-rank 2                              ;; Position in BFS results
   :embedding-rank 1                          ;; Position in embedding results
   :label "Hallucination"
   :description "..."
   :scope :failure}
  ...]
 :graph-results [...]                         ;; Raw BFS results
 :embedding-results [...]                     ;; Raw embedding results
 :method "rrf"
 :batches-used [:graph :embedding]}
```

**How RRF Fusion Works:**
1. BFS spreading activation produces ranked results based on graph structure
2. Embedding search produces ranked results based on semantic similarity
3. RRF (Reciprocal Rank Fusion) merges both lists: `score(d) = sum(1 / (k + rank_i))`
4. Items appearing high in BOTH lists get the highest fused scores

### Specialized Hybrid Search

```clojure
;; Search only failure concepts
(ontology/hybrid-search-failures event-store
  "the output contained made up information"
  :seed-failure-uri "failure:Grounding"
  :limit 5)

;; Search only success patterns
(ontology/hybrid-search-patterns event-store
  "validate output before returning"
  :seed-pattern-uri "success:StructuralPattern"
  :limit 5)
```

### Enhanced Context with Embeddings

Build ontology context using hybrid search:

```clojure
(ontology/build-ontology-context-with-embeddings event-store
  {:problem-type "problem:Classification"
   :problem-description "categorize customer support tickets"
   :required-patterns #{"success:ExplicitSchema"}})

;; Returns (extends build-ontology-context):
{:few-shot-trees [...]
 :recommended-patterns [...]
 :patterns-to-avoid [...]
 :related-concepts [...]
 :problem-hierarchy {...}
 ;; NEW: Embedding-enhanced fields
 :embedding-recommended-patterns [...]        ;; Semantically similar patterns
 :embedding-patterns-to-avoid [...]           ;; Semantically similar failures
 :hybrid-related-concepts [...]               ;; RRF-fused related concepts
 :context-metadata {:embeddings-used true}}
```

### Read Model Queries

```clojure
;; Get embedding for a concept
(ontology/get-concept-embedding event-store "failure:Hallucination")
;; => {:uri "failure:Hallucination"
;;     :embedding [0.0234 -0.0891 ...]
;;     :text-embedded "Hallucination. Generated claims..."
;;     :model-id "sentence-transformers/all-MiniLM-L6-v2"}

;; Get all embeddings (optionally by scope)
(ontology/get-all-concept-embeddings event-store {:scope :failure})

;; Get tree profile embedding
(ontology/get-tree-profile-embedding event-store tree-id)

;; Get embedding configuration
(ontology/get-embedding-config event-store :failure)
;; => {:model-id "sentence-transformers/all-MiniLM-L6-v2"
;;     :dimensions 384
;;     :configured-at "2024-..."}

;; Statistics
(ontology/embedding-statistics event-store)
;; => {:concept-embeddings-count 45
;;     :tree-profile-embeddings-count 12
;;     :configured-scopes [:failure :success :problem]
;;     :by-model {"sentence-transformers/all-MiniLM-L6-v2" 45}}
```

### API Reference (Phase 4)

#### Embedding Functions

| Function | Description |
|----------|-------------|
| `embed-text` | Generate 384-dim embedding for text |
| `embed-texts-batch` | Batch embedding generation |
| `cosine-similarity` | Compute similarity between vectors |
| `detect-embeddable-fields` | Analyze Malli schema for embeddable fields |
| `concept->embedding-text` | Convert concept to embeddable text |
| `tree-profile->embedding-text` | Convert profile to embeddable text |
| `get-embedding-model` | Get/load DJL embedding model |
| `close-all-embedding-models!` | Clean up loaded models |

#### Search Functions

| Function | Description |
|----------|-------------|
| `semantic-search-concepts` | Search concepts by embedding similarity |
| `semantic-search-tree-profiles` | Search profiles by embedding similarity |
| `hybrid-search` | Graph BFS + embeddings via RRF |
| `hybrid-search-failures` | Hybrid search for failure concepts |
| `hybrid-search-patterns` | Hybrid search for success patterns |
| `build-ontology-context-with-embeddings` | Enhanced context with embeddings |

#### Read Model Queries

| Function | Description |
|----------|-------------|
| `get-concept-embedding` | Get embedding for a concept by URI |
| `get-all-concept-embeddings` | Get all embeddings, optionally by scope |
| `get-tree-profile-embedding` | Get embedding for a tree profile |
| `get-all-tree-profile-embeddings` | Get all tree profile embeddings |
| `get-embedding-config` | Get embedding model config for scope |
| `embedding-statistics` | Get embedding statistics |

### Testing Phase 4

```clojure
(require '[ontology-integration-test :as oit])

;; Run embedding tests (includes model loading)
(oit/run-embedding-tests)

;; Individual tests
(oit/test-embedding-functions)      ;; Vector ops, schema analysis
(oit/test-embedding-generation)     ;; DJL model loading (slow first time)
(oit/test-embedding-read-models)    ;; Event projections
(oit/test-hybrid-search)            ;; RRF fusion
```

## Future Enhancements

1. **pgvector Read Model** - Project embeddings to PostgreSQL pgvector for scale
2. **Discovery** - LLM-powered discovery of new failure subtypes
3. **Active Learning** - Suggest which evaluations to label next
4. **Cross-Ontology Retrieval** - Search across failure/success/problem simultaneously
