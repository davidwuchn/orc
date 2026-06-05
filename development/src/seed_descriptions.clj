(ns seed-descriptions
  "Hand-authored seed descriptions for the Living Description system (C-2a-1).

   Each seed is a principle-shaped description for a target the system
   has NOT yet had a chance to learn from (cold-start). Hand-authored
   seeds get :confidence 1.0 and ground every claim against either a
   real benchmark tick-id or against the canonical behavior the DSL
   guarantees.

   Three granularities:
   - node-type        — keyword target-id like :llm or :map-each
   - tree-fingerprint — string target-id (SHA of canonical S-expression)
   - node-instance    — [sheet-id node-id] tuple (NOT seeded here; only
                        produced by the cold-start LLM baseline on first
                        event for a specific node-instance)

   The 18 seeds (per PRD):
     7 node-type seeds: :llm, :code, :map-each, :parallel, :sequence,
                        :fallback, :condition
     5 benchmark-task-class tree seeds: re-keyed by C-1's existing UUIDs
        from `seed_principles.clj` (document-analysis, risk-analysis,
        contract-comparison, legal-issue-detection, chunked-extraction)
     7 generic-pattern tree seeds: ChunkedExtraction, SequentialPipeline,
        ParallelIndependent, ValidationLoop, FallbackRecovery, MapReduce,
        ResearchThenSynthesize

   Each seed is surfaced individually for HITL audit before commit, per
   the saved memory `audit-untracked-by-name`."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.orc-service.core.rlm-dsl :as rlm-dsl]
            [seed-principles :as principles]))

;; =============================================================================
;; Tree-fingerprint constants (stable hash strings for hand-authored tree seeds)
;; =============================================================================
;;
;; Each generic-pattern tree-seed is keyed by a stable, deliberately-readable
;; fingerprint string (NOT a SHA derived from a snippet). C-2a-2 will compute
;; real SHA-based fingerprints from canonical tree-raw — these constants are
;; the hand-author equivalent that the runtime fingerprint computation will
;; eventually overwrite for any benchmark-task tree that emits.

(def chunked-extraction-fp     "seed:tree:ChunkedExtraction")
(def sequential-pipeline-fp    "seed:tree:SequentialPipeline")
(def parallel-independent-fp   "seed:tree:ParallelIndependent")
(def validation-loop-fp        "seed:tree:ValidationLoop")
(def fallback-recovery-fp      "seed:tree:FallbackRecovery")
(def map-reduce-fp             "seed:tree:MapReduce")
(def research-then-synth-fp    "seed:tree:ResearchThenSynthesize")

;; Re-export the benchmark task-class UUIDs from C-1's seed_principles.clj so
;; we don't drift between principle storage and description storage.
(def document-analysis-task-class-id        principles/document-analysis-task-class-id)
(def risk-analysis-task-class-id            principles/risk-analysis-task-class-id)
(def contract-comparison-task-class-id      principles/contract-comparison-task-class-id)
(def legal-issue-detection-task-class-id    principles/legal-issue-detection-task-class-id)
(def chunked-extraction-task-class-id       principles/chunked-extraction-task-class-id)

;; =============================================================================
;; R02 — hand-authored children for the 5 flat top-level patterns
;;
;; These have NO C-1 principle-injection backing (so they don't belong in
;; seed_principles.clj). They exist purely as description-corpus targets
;; the classifier can match to and the walk-down can descend through.
;; UUIDs are derived stably from descriptive strings so adding more
;; children later doesn't require coordinating numeric slots.
;; =============================================================================

(defn- ^java.util.UUID stable-uuid-from
  "Derive a stable UUID from a deterministic seed string. Mirrors the
   classifier's coerce-to-uuid idiom — preserves identity across JVM
   restarts and ColBERT JSON round-trips."
  [^String s]
  (java.util.UUID/nameUUIDFromBytes (.getBytes s "UTF-8")))

;; --- under sequential-pipeline-fp ---
(def etl-pipeline-task-class-id
  (stable-uuid-from "seed:tree-class:etl-pipeline"))
(def iterative-refinement-task-class-id
  (stable-uuid-from "seed:tree-class:iterative-refinement"))
(def scheduling-task-class-id
  (stable-uuid-from "seed:tree-class:scheduling"))

;; --- under validation-loop-fp ---
(def producer-validator-task-class-id
  (stable-uuid-from "seed:tree-class:producer-validator"))
(def draft-critique-task-class-id
  (stable-uuid-from "seed:tree-class:draft-critique"))

;; --- under fallback-recovery-fp ---
(def primary-backup-task-class-id
  (stable-uuid-from "seed:tree-class:primary-backup"))
(def model-cascade-task-class-id
  (stable-uuid-from "seed:tree-class:model-cascade"))

;; --- under map-reduce-fp ---
(def parallel-sum-task-class-id
  (stable-uuid-from "seed:tree-class:parallel-sum"))
(def parallel-classify-aggregate-task-class-id
  (stable-uuid-from "seed:tree-class:parallel-classify-aggregate"))

;; --- under research-then-synth-fp ---
(def briefing-generation-task-class-id
  (stable-uuid-from "seed:tree-class:briefing-generation"))
(def comparative-summary-task-class-id
  (stable-uuid-from "seed:tree-class:comparative-summary"))

;; =============================================================================
;; Validation helpers — every seed snippet MUST pass these before commit
;; =============================================================================

(defn principle-shaped?
  "True if a strength/weakness entry has the required principle fields."
  [entry]
  (and (map? entry)
       (string? (:trait entry))
       (or (string? (:good-when entry)) (string? (:avoid-when entry)))
       (or (string? (:recommended-pattern entry))
           (string? (:recommended-alternative entry)))
       (number? (:confidence entry))
       (integer? (:evidence-count entry))))

(defn description-body-well-formed?
  "True if a description body has the required top-level fields."
  [body]
  (and (map? body)
       (vector? (:capabilities body))
       (vector? (:strengths body))
       (vector? (:weaknesses body))
       (every? principle-shaped? (concat (:strengths body) (:weaknesses body)))
       (string? (:summary body))
       (integer? (:version body))))

(defn- replace-inline-fn-forms
  "Walk a parsed tree-DSL form; replace any '(fn ...)' list with a dummy
   fn value so that downstream transform validation doesn't trip on the
   list-vs-fn distinction. In production, SCI evals the tree expression
   first — `(fn ...)` becomes a real fn before transform. This validator
   mirrors that by injecting a real fn at the same syntactic positions."
  [form]
  (cond
    (and (seq? form) (= 'fn (first form)))
    (constantly nil)

    (map? form)
    (into {} (map (fn [[k v]] [k (replace-inline-fn-forms v)])) form)

    (sequential? form)
    (into (empty form) (map replace-inline-fn-forms) form)

    :else form))

(defn snippet-validates?
  "True if a string-valued tree-DSL snippet can be parsed + transformed by
   rlm-dsl/rlm-dsl->orc-dsl without throwing.

   Used for any :recommended-pattern field that embeds a tree-DSL form.
   Inline '(fn ...)' forms inside :code nodes are pre-resolved to a
   placeholder fn value (mirrors SCI eval in production). Returns false
   (and the exception is swallowed) if the snippet is malformed."
  [snippet-str]
  (try
    (let [form (read-string snippet-str)
          resolved (replace-inline-fn-forms form)]
      (rlm-dsl/rlm-dsl->orc-dsl resolved)
      true)
    (catch Exception _ false)))

;; =============================================================================
;; Now-string helper (matches the format C-1's seed file uses)
;; =============================================================================

(defn- now-str [] (str (time/now)))

;; =============================================================================
;; SEED #1 — node-type :llm
;; =============================================================================
;;
;; The :llm node is the workhorse — sub-LLM call inside Phase 2 trees.
;; Strengths: per-chunk extraction (predictable schema), classification,
;;   short-form synthesis with bounded output.
;; Weaknesses: long free-form output (truncation risk), structurally-
;;   ambiguous outputs (consumer can't parse).

(def llm-node-type-seed
  {:target-id :llm
   :body
   {:capabilities
    ["produces structured or free-form text output from instruction + reads"
     "per-chunk extraction inside :map-each parents"
     "classification / labeling with bounded output schema"
     "short-form synthesis where the output fits within model max-tokens"]

    :strengths
    [{:trait "produces stable structured output when :output-schemas is declared"
      :good-when "the output is structured (vector/map) and the model honors :output-schemas"
      :recommended-pattern "[:llm {:reads [:chunk] :writes [:entities] :output-schemas {:entities [:vector :string]}}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "succeeds reliably on bounded per-item work inside :map-each"
      :good-when "input is a single chunk / item and the output is concise"
      :recommended-pattern "[:map-each {:from :chunks :as :chunk :into :results :max-concurrency 3} [:llm {:reads [:chunk] :writes [:result]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "free-form long output can truncate mid-response, breaking downstream consumers"
      :avoid-when "an :llm node is asked to produce many KB of free-form prose AND a downstream :code consumer parses the response"
      :recommended-alternative "use :output-schemas to constrain shape; or split the work into smaller per-chunk :llm calls under a :map-each"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "rate-limit exhaustion when many :llm nodes run in unbounded parallel"
      :avoid-when "input has >6 parallel :llm sub-calls AND :max-concurrency is unset on the parent :map-each"
      :recommended-alternative "set :max-concurrency to 3 on the :map-each parent"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["per-chunk entity extraction in document-analysis"
     "per-section classification in legal-issue-detection"
     "executive summary synthesis as the final step in chunked-extraction trees"]

    :avoid-when
    ["the work is deterministic (counts, joins, regex) — use :code instead"
     "the output is non-textual binary — use a different node type"]

    :summary
    "The :llm node calls a sub-LLM with instruction + reads → writes. It is the workhorse of Phase 2 trees: reliable for per-chunk extraction, classification, and bounded synthesis. Prefer :output-schemas for structured outputs to avoid downstream parsing fragility. Inside :map-each, bound :max-concurrency to ~3 to avoid sub-LLM rate-limit exhaustion."

    :version 1
    :consolidated-from-event-count 0}})

;; =============================================================================
;; Seed-all! helper — seeds everything authored so far
;; =============================================================================

;; =============================================================================
;; SEED #2 — node-type :code
;; =============================================================================
;;
;; The :code node is a pure Clojure transform — inline :fn over :reads → :writes.
;; Strengths: deterministic transforms that don't need an LLM (counts, joins,
;;   regex, dedup, format conversion, aggregation of map-each outputs).
;; Weaknesses: the inline :fn must be SCI-safe; exceptions in :fn crash the
;;   node with no LLM-style retry; no schema validation of the return shape.

(def code-node-type-seed
  {:target-id :code
   :body
   {:capabilities
    ["pure Clojure transform: :reads → :fn → :writes (no LLM call)"
     "deterministic aggregation of :map-each results"
     "format conversion (JSON ⇄ Clojure data, vector ⇄ map, scalar ⇄ wrapped)"
     "cheap counts / joins / regex / dedup / filter operations"]

    :strengths
    [{:trait "spends zero tokens on deterministic work that an LLM would otherwise do"
      :good-when "the transform is mechanical (counting, joining, deduping, regex extracting)"
      :recommended-pattern "[:code {:reads [:items] :writes [:total] :fn (fn [{:keys [items]}] {:total (count items)})}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "consolidates :map-each results into a single shape downstream consumers can use"
      :good-when "a :map-each writes a vector of per-item results and a downstream node needs a flat map / single value"
      :recommended-pattern "[:code {:reads [:per-chunk-results] :writes [:all-entities] :fn (fn [{:keys [per-chunk-results]}] {:all-entities (vec (mapcat :entities per-chunk-results))})}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "exceptions in the inline :fn crash the node with no LLM-style retry"
      :avoid-when "the transform depends on the input shape and the input is not schema-guaranteed (e.g. :reads receives an LLM-generated vector whose elements might be nil)"
      :recommended-alternative "defensive destructuring + nil-handling inside :fn; OR upstream :llm node should use :output-schemas to guarantee shape"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "the inline :fn must be SCI-safe — no dynamic require, no Java interop outside the SCI whitelist"
      :avoid-when "the transform requires a library not on the SCI whitelist OR uses dynamic resolution"
      :recommended-alternative "use a qualified-symbol :fn ('my.ns/transform) to reference a real Clojure fn instead of an inline lambda"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["counting / aggregating :map-each results in document-analysis"
     "joining per-chunk extractions into a flat entity list"
     "regex-extracting structured fields from semi-structured LLM output"
     "format conversion between vector-of-maps and map-of-vectors shapes"]

    :avoid-when
    ["the work requires natural-language understanding — use :llm instead"
     "the work involves non-deterministic / external I/O (network, files) — :code is in-process pure compute only"]

    :summary
    "The :code node runs a pure Clojure :fn over :reads to produce :writes — no LLM call, no tokens spent. It is the right tool for deterministic transforms (counts, joins, regex, dedup, aggregation of :map-each results). Inline :fn must be SCI-safe; defensive nil-handling is critical because input shape may not be guaranteed. Prefer :code over :llm whenever the work is mechanical."

    :version 1
    :consolidated-from-event-count 0}})

;; =============================================================================
;; SEED #3 — node-type :map-each
;; =============================================================================
;;
;; The :map-each node runs a child body once per element of a collection,
;; collecting results. The workhorse for parallel per-chunk extraction
;; and per-item classification. The single most-failure-prone node when
;; :max-concurrency is left unbounded.

(def map-each-node-type-seed
  {:target-id :map-each
   :body
   {:capabilities
    [":from collection-key, :as item-name, body fn, :into results-key"
     "bounded parallelism via :max-concurrency"
     "natural fit for per-chunk extraction, per-record classification, per-document analysis"
     "produces a vector of per-iteration results downstream :code or :aggregate consumes"]

    :strengths
    [{:trait "bounded :max-concurrency (3) avoids sub-LLM rate-limit exhaustion on chunked extraction"
      :good-when "the child body contains :llm calls AND the source collection has more than ~5 items"
      :recommended-pattern "[:map-each {:from :chunks :as :chunk :into :results :max-concurrency 3} [:llm {:reads [:chunk] :writes [:result]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "partial-result tolerance — :partial status on some-failed scenarios leaves successful items usable"
      :good-when "the downstream consumer can synthesize from a successes-only vector (e.g., summary from 22 of 24 chunks)"
      :recommended-pattern "[:sequence [:map-each {:from :chunks :as :chunk :into :results :max-concurrency 3} [:llm {:reads [:chunk] :writes [:result]}]] [:code {:reads [:results] :writes [:synthesis] :fn (fn [{:keys [results]}] {:synthesis (filterv some? results)})}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "unbounded :max-concurrency exhausts sub-LLM rate limits on chunked extractions with 6+ items"
      :avoid-when ":max-concurrency is unset AND the source collection has > 6 items AND the child body contains :llm calls"
      :recommended-alternative "always set :max-concurrency to 3 (or a value tuned to the LLM provider's per-second rate limit) on :map-each over chunks"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "ALL iterations failing (e.g., schema mismatch in every item) returns :status :failure with empty results — downstream :code crashes on nil/empty input"
      :avoid-when "downstream :code requires non-empty :results"
      :recommended-alternative "wrap the :code in a :fallback OR add a :condition guard that branches on (seq results)"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["per-chunk entity extraction in document-analysis benchmark"
     "per-section classification in legal-issue-detection benchmark"
     "per-page processing in predict-rlm-comparison image_analysis + document_redaction tasks"]

    :avoid-when
    ["the source collection is empty or single-item — use a direct :llm or :code instead"
     "the child body needs to share state across iterations — :map-each iterations are independent"
     "ordered processing matters AND iterations are not commutative — use :sequence of :llm/:code chain instead"]

    :summary
    "The :map-each node iterates a child body over a source collection's elements with bounded parallelism. THE primary workhorse for chunked extraction and per-record classification. Always set :max-concurrency to ~3 when the child body contains :llm calls to avoid sub-LLM rate-limit exhaustion. Partial-failure tolerance is built-in (:status :partial leaves successful items usable). Avoid when iterations need shared state — use :sequence instead."

    :version 1
    :consolidated-from-event-count 0}})

;; =============================================================================
;; SEED #4 — node-type :parallel
;; =============================================================================
;;
;; The :parallel node runs its children concurrently against the SAME
;; blackboard. Use when children are genuinely independent and write to
;; non-overlapping keys.

(def parallel-node-type-seed
  {:target-id :parallel
   :body
   {:capabilities
    ["runs child nodes concurrently against the same blackboard state"
     "natural fit for independent extractions where each child writes to a distinct key"
     "cross-validation patterns (same input through two different prompts → compare)"
     "speedup over :sequence when child latencies are similar AND children are independent"]

    :strengths
    [{:trait "wall-time reduction when children take similar duration and don't depend on each other"
      :good-when "the same source data must be transformed three+ ways AND each transformation writes to a distinct key"
      :recommended-pattern "[:parallel [:llm {:reads [:document] :writes [:summary]}] [:llm {:reads [:document] :writes [:entities]}] [:llm {:reads [:document] :writes [:key-dates]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "writes to overlapping :writes keys race; last-writer-wins is non-deterministic"
      :avoid-when "two or more children declare the same key in their :writes"
      :recommended-alternative "give each child a distinct :writes key, OR use :sequence so writes happen in order, OR aggregate via :code after"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "total wall-time equals the SLOWEST child — not a speedup if children are very uneven"
      :avoid-when "one child is dramatically slower than the others AND a downstream node needs all children's results before proceeding"
      :recommended-alternative "use :sequence with the slow child first so downstream consumers can start as soon as siblings complete; OR split the slow child into smaller :map-each iterations"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "children cannot read each other's outputs — they execute concurrently"
      :avoid-when "a child's :reads include any key another sibling writes to"
      :recommended-alternative "use :sequence to chain dependent computations, OR restructure so each child reads only from blackboard keys set BEFORE the :parallel"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["concurrent independent extractions in document-analysis (summary + entities + dates in one pass)"
     "cross-validation pattern (two prompts on the same input → compare)"
     "fan-out to multiple sub-LLM models in dual-model evaluation (using per-:llm :model override)"]

    :avoid-when
    ["the work is per-element over a collection — use :map-each instead"
     "any child depends on another child's output — use :sequence"
     "only one child exists — drop the :parallel wrapper"]

    :summary
    "The :parallel node runs its children concurrently against the shared blackboard. Use it for independent transformations of the same input where each child writes to a DISTINCT key. Children cannot read each other's outputs; overlapping :writes keys race; total wall-time equals the slowest child. Choose :sequence when children must chain; choose :map-each when iterating over a collection."

    :version 1
    :consolidated-from-event-count 0}})

;; =============================================================================
;; SEED #5 — node-type :sequence
;; =============================================================================
;;
;; The :sequence node runs children in order, each child's :writes
;; available to subsequent children's :reads. The structural backbone
;; of almost every non-trivial Phase 2 tree.

(def sequence-node-type-seed
  {:target-id :sequence
   :body
   {:capabilities
    ["runs children in order; each child's :writes available to subsequent children's :reads"
     "structural backbone of pipelined trees (chunk → map-each → aggregate → synthesize)"
     "stops at first :failure-status child (use :fallback if you want to try siblings)"
     "propagates :partial status — if any child returns :partial, the sequence reports :partial"]

    :strengths
    [{:trait "natural pipelined flow when each step depends on the prior step's output"
      :good-when "computation has logical phases (extract → aggregate → synthesize) where order matters"
      :recommended-pattern "[:sequence [:chunk-document {:from :document :into :chunks}] [:map-each {:from :chunks :as :chunk :into :results :max-concurrency 3} [:llm {:reads [:chunk] :writes [:result]}]] [:code {:reads [:results] :writes [:summary] :fn (fn [{:keys [results]}] {:summary (str/join \" \" results)})}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "predictable failure semantics: first-failure halts the chain; status of any child propagates"
      :good-when "you want fail-fast behavior — downstream steps shouldn't run if an upstream step failed"
      :recommended-pattern "[:sequence [:llm {:writes [:initial-extract]}] [:llm {:reads [:initial-extract] :writes [:refined]}] [:final {:keys [:refined]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "no parallelism — total wall-time is the SUM of all children's durations"
      :avoid-when "two or more children are independent AND each is non-trivial in latency"
      :recommended-alternative "use :parallel for the independent children; or use :map-each if the work is per-item over a collection"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "over-decomposition into many small :llm children burns sub-LLM tokens that could be one call"
      :avoid-when "the sequence is 3+ :llm children doing successive small transformations on the same data"
      :recommended-alternative "consolidate into ONE :llm node with a longer instruction covering all the steps; OR use :code for any deterministic intermediate steps"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["root of nearly every chunked-extraction tree (chunk → map-each → aggregate → synth)"
     "ETL-style pipelines where each stage refines the prior stage's output"
     "any tree whose final node is a :final marker — :sequence is the natural root container"]

    :avoid-when
    ["children are independent — use :parallel"
     "iterating over a collection — use :map-each"
     "the tree has only one child — drop the :sequence wrapper"]

    :summary
    "The :sequence node runs children in order; each child's :writes are available to the next child's :reads. It is the structural backbone of almost every non-trivial Phase 2 tree (chunk → map-each → aggregate → synthesize). Stops fail-fast on first :failure; propagates :partial. Avoid when children are independent (use :parallel) or when the chain is 3+ small :llm steps (consolidate into one larger :llm or use :code for deterministic intermediates)."

    :version 1
    :consolidated-from-event-count 0}})

;; =============================================================================
;; SEED #6 — node-type :fallback
;; =============================================================================
;;
;; The :fallback node tries children in order; returns the first :success.
;; The recovery composite — use when a primary approach may fail but a
;; simpler/degraded alternative is acceptable.

(def fallback-node-type-seed
  {:target-id :fallback
   :body
   {:capabilities
    ["runs children in order; returns on first :success"
     "tries the next sibling when the previous returns :failure"
     "natural recovery pattern: primary approach + simpler fallback"
     "graceful degradation when the primary might fail but a degraded version is acceptable"]

    :strengths
    [{:trait "graceful recovery when the primary approach has a known partial-failure mode"
      :good-when "you have a high-quality but fragile primary AND a lower-quality but reliable backup that produces equivalent :writes"
      :recommended-pattern "[:fallback [:llm {:model \"openai/gpt-5\" :instruction \"complex extraction\" :writes [:result]}] [:llm {:model \"google/gemini-2.5-flash\" :instruction \"simpler extraction\" :writes [:result]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "each failed child still runs to completion before failure is detected — total cost includes wasted work"
      :avoid-when "the primary child has high token cost AND fails often (you pay full cost on every primary attempt)"
      :recommended-alternative "use a :condition guard to PREDICT which child to run based on input shape, instead of trying the expensive one first; OR put the cheaper child first if its success rate is close to the primary's"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "silently masks real bugs — if the primary always fails and the fallback always covers, the regression is invisible"
      :avoid-when "the primary is intended to be the canonical path AND silent fallback would hide degradation"
      :recommended-alternative "instead of :fallback, use :sequence with a :condition that fails LOUDLY when the primary fails; OR add a judge or downstream :code node that logs/alerts when the fallback fires"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "children must produce equivalent :writes for downstream consumers to work uniformly"
      :avoid-when "primary and fallback write different keys OR different shapes for the same key"
      :recommended-alternative "use :code after the :fallback to normalize the output shape; OR ensure all children's :writes declarations match"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["model-quality fallback: try a high-capability model first, fall back to a cheaper one"
     "schema-strict-then-loose: try :output-schemas first, fall back to free-form parsing"
     "primary-then-rule-based: try LLM extraction, fall back to regex/template matching via :code"]

    :avoid-when
    ["all children would succeed (just use the first one — :fallback adds nothing)"
     "you want both children's results combined — use :parallel"
     "you want only one child to run based on a condition you can predict — use :condition"]

    :summary
    "The :fallback node tries children in order, returning on first :success. THE recovery composite: use for graceful degradation when a primary approach may fail but a simpler alternative is acceptable. Cost: each failed child runs to completion before failure is detected, so the slow/expensive child should NOT be first if its failure rate is high. Risk: silently masks real bugs — pair with a judge or logging :code to surface when the fallback fires."

    :version 1
    :consolidated-from-event-count 0}})

;; =============================================================================
;; SEED #7 — node-type :condition (canonical-DSL only — NOT emit-able by RLM today)
;; =============================================================================
;;
;; The :condition node branches based on a predicate over the current
;; blackboard. EXISTS in the canonical ORC DSL (hand-built workflows) but
;; the RLM tree-DSL transformer (rlm_dsl.clj) does NOT yet support it —
;; the model cannot author :condition nodes in emit-tree! today. This
;; seed describes the node anyway so that:
;;   1. Human developers using canonical ORC DSL have a reference
;;   2. Future RLM DSL extension (if we add :condition support) inherits
;;      a description automatically
;;   3. Tree-fingerprint seeds that include :condition (e.g. ValidationLoop)
;;      can ground in this description

(def condition-node-type-seed
  {:target-id :condition
   :body
   {:capabilities
    ["branches execution based on a code predicate over the blackboard"
     ":check is a fn returning truthy/falsy; node returns :success or the configured :on-fail status"
     "useful for guards that gate downstream work on intermediate-state shape"
     "NOT currently emit-able by RLM in emit-tree! — canonical ORC DSL only as of this seed's authoring"]

    :strengths
    [{:trait "explicit guard that stops a sequence cleanly when a precondition isn't met"
      :good-when "a downstream step requires a specific shape AND you can express the precondition as a small predicate"
      :recommended-pattern "[:sequence [:llm {:writes [:extracted]}] [:condition {:check (fn [{:keys [extracted]}] (seq extracted)) :on-fail :failure}] [:code {:reads [:extracted] :writes [:synth] :fn (fn [{:keys [extracted]}] {:synth extracted})}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "the predicate fn must be SCI-safe — same constraints as :code"
      :avoid-when "the predicate needs library access outside the SCI whitelist OR dynamic resolution"
      :recommended-alternative "extract the predicate to a qualified-symbol fn; OR upstream the check into a :code node that explicitly :writes a :guard-met? boolean and switch on it"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "RLM models cannot emit :condition in emit-tree! today — patterns using :condition are hand-built-only"
      :avoid-when "designing an emit-tree! pattern AND wanting branching behavior"
      :recommended-alternative "use :fallback for the recovery-style branching (try the strict path, fall back on failure); OR design the work as :sequence with always-execute steps"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["preconditions in hand-built workflows (gate downstream work on intermediate-state shape)"
     "schema validation between :llm extraction and :code consumption"
     "fail-loud guards in production workflows that need surface-able failures"]

    :avoid-when
    ["the branching can be expressed via :fallback — prefer that (no SCI predicate surface)"
     "designing for emit-tree! — RLM cannot emit :condition today"]

    :summary
    "The :condition node branches based on a code predicate over the blackboard — exists in canonical ORC DSL for hand-built workflows but is NOT currently emit-able by RLM in emit-tree!. Use as a guard in hand-built pipelines when a downstream step has a precondition. The predicate fn is SCI-constrained (same as :code). For emit-tree! patterns that want branching, prefer :fallback (when supported) or design the work as always-execute :sequence steps."

    :version 1
    :consolidated-from-event-count 0}})

;; =============================================================================
;; SEED #8 — node-type :chunk-document (RLM-emit-able helper)
;; =============================================================================
;;
;; The :chunk-document node is a tree-DSL helper that compiles to a sheet/code
;; node containing the chunking fn. Takes a :from source key, splits the
;; string into chunks of :size (or by :delimiter), writes the vector :into
;; a target key. THE standard preamble in chunked-extraction patterns.

(def chunk-document-node-type-seed
  {:target-id :chunk-document
   :body
   {:capabilities
    ["splits a string source from :from into substrings, writes a vector to :into"
     "supports fixed :size (chunk every N chars) OR :delimiter (split on a separator)"
     "compiles to an inline :code node — no LLM call, deterministic"
     "the standard preamble for any chunked-extraction tree (:chunk-document → :map-each → :llm → :aggregate)"]

    :strengths
    [{:trait "deterministic chunking with zero token cost — the right tool for splitting a large document"
      :good-when "input is a string AND downstream work is per-chunk (extraction, classification)"
      :recommended-pattern "[:sequence [:chunk-document {:from :document :size 5000 :into :chunks}] [:map-each {:from :chunks :as :chunk :into :results :max-concurrency 3} [:llm {:reads [:chunk] :writes [:result]}]]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait ":delimiter-based splitting respects document structure (sections, paragraphs) better than fixed-:size for semantic boundaries"
      :good-when "the document has natural separators (e.g. '===CHUNK===', '\\n\\n' for paragraphs, headers)"
      :recommended-pattern "[:chunk-document {:from :document :delimiter \"===\" :into :sections}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "fixed :size splits ignore semantic structure — entities/dates may straddle chunk boundaries"
      :avoid-when "the document has clear semantic separators that should be preserved AND :size is set without considering them"
      :recommended-alternative "use :delimiter when the document has natural separators; OR set :size large enough that semantic content rarely straddles boundaries (default 5000 chars is usually safe)"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["preamble step in document-analysis benchmark"
     "section-splitting in legal-issue-detection (delimiter-based on heading markers)"
     "page-splitting in predict-rlm-comparison document_redaction (per-page entity extraction)"]

    :avoid-when
    ["input is already a collection — skip directly to :map-each"
     "the document is small enough to fit in one LLM call — use a single :llm without chunking"]

    :summary
    "The :chunk-document node splits a string source into a vector of substrings — the standard preamble for chunked-extraction trees. Use :size for fixed-length chunks (default 5000 chars is safe) or :delimiter to preserve semantic boundaries like sections or paragraphs. Always pair with :map-each + bounded :max-concurrency downstream to process chunks safely."

    :version 1
    :consolidated-from-event-count 0}})

;; =============================================================================
;; SEED #9 — node-type :aggregate (RLM-emit-able helper)
;; =============================================================================
;;
;; The :aggregate node is a tree-DSL helper that consolidates :map-each
;; results into a single shape. Compiles to a sheet/code node containing
;; the merge fn.

(def aggregate-node-type-seed
  {:target-id :aggregate
   :body
   {:capabilities
    ["consolidates :map-each results into a single value or merged map"
     "flattens a vector of per-iteration result maps into per-key vectors"
     "compiles to an inline :code node — no LLM call, deterministic"
     "the standard step AFTER :map-each in chunked-extraction trees, BEFORE downstream synthesis"]

    :strengths
    [{:trait "natural consolidator for :map-each results without spending tokens"
      :good-when "a :map-each writes a vector of per-iteration result maps AND downstream consumer needs a per-key flat map"
      :recommended-pattern "[:sequence [:map-each {:from :chunks :as :chunk :into :chunk-results} [:llm {:reads [:chunk] :writes [:entities :dates]}]] [:aggregate {:from :chunk-results :writes [:entities :dates]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "the default aggregation just appends per-key — doesn't deduplicate, doesn't reconcile conflicting values across iterations"
      :avoid-when "different iterations may produce conflicting values for the same conceptual entity (e.g. the same person mentioned by different names)"
      :recommended-alternative "use a downstream :code node with custom dedup/reconciliation logic instead of relying on :aggregate's default merge"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["final-stage merge in document-analysis (per-chunk entities → all-entities)"
     "consolidating per-section classifications in legal-issue-detection"
     "merging per-page extraction results in predict-rlm-comparison"]

    :avoid-when
    ["the consolidation requires deduplication, sorting, or conflict resolution — use :code with explicit logic"
     ":map-each results are already shaped correctly for downstream consumption"]

    :summary
    "The :aggregate node consolidates a :map-each's result vector into a per-key flat map. The standard post-:map-each step in chunked-extraction trees. Default merge appends per-key without deduplication — for richer consolidation logic (dedup, sort, conflict resolution), use a downstream :code node instead."

    :version 1
    :consolidated-from-event-count 0}})

;; =============================================================================
;; SEED #10 — node-type :final (RLM-emit-able terminal marker)
;; =============================================================================
;;
;; The :final node is a marker — declares which blackboard keys form the
;; tree's final outputs. Not a computational node; tree compilation reads
;; the :keys list and uses it to validate the tree produced all declared
;; outputs.

(def final-node-type-seed
  {:target-id :final
   :body
   {:capabilities
    ["declares which blackboard keys form the tree's final outputs via :keys"
     "marker only — no execution; tree compilation uses it to validate output coverage"
     "REQUIRED as the last child of any emit-tree! root :sequence (or as the root if the tree is one composite)"]

    :strengths
    [{:trait "explicit output contract — makes the tree's intended outputs unambiguous"
      :good-when "every tree (always — :final is required at the root level)"
      :recommended-pattern "[:sequence [:chunk-document {:from :document :size 5000 :into :chunks}] [:map-each {:from :chunks :as :chunk :into :results :max-concurrency 3} [:llm {:reads [:chunk] :writes [:summary :entities :dates]}]] [:aggregate {:from :results :writes [:summary :entities :dates]}] [:final {:keys [:summary :entities :dates]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "omitting :final means the tree has no declared outputs — the parent repl-researcher cannot extract :writes properly"
      :avoid-when "every emit-tree! tree (you must always include :final at the root)"
      :recommended-alternative "always include [:final {:keys [...]}] as the last child of the root composite; OR if your root IS a single :llm/:code, wrap in [:sequence ... [:final {...}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait ":final's :keys must match the parent repl-researcher node's :writes — mismatches cause output reconciliation failures"
      :avoid-when "the :final's declared :keys differs from the parent repl-researcher's :writes declaration"
      :recommended-alternative "include EVERY parent :writes key in :final's :keys; if the tree doesn't produce all of them, redesign upstream nodes to ensure each :writes key is populated"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["terminal marker in every emit-tree! tree"
     "explicit output contract in benchmark tasks (document-analysis, risk-analysis, etc.)"]

    :avoid-when
    ["it's the only node in your tree without other children — a single :llm doesn't need :final wrapping (the parent's :writes covers it)"]

    :summary
    "The :final node is a terminal marker declaring which blackboard keys form the tree's outputs. Required as the last child of any emit-tree! root :sequence (or as the root if the tree is one composite). :final's :keys must match the parent repl-researcher's :writes — mismatches cause output reconciliation failures."

    :version 1
    :consolidated-from-event-count 0}})

(def all-node-type-seeds
  "Vector of all node-type seeds. Grows as we author each one."
  [llm-node-type-seed                ;; #1
   code-node-type-seed               ;; #2
   map-each-node-type-seed           ;; #3
   parallel-node-type-seed           ;; #4
   sequence-node-type-seed           ;; #5
   fallback-node-type-seed           ;; #6 (canonical-only — not emit-able by RLM today)
   condition-node-type-seed          ;; #7 (canonical-only — not emit-able by RLM today)
   chunk-document-node-type-seed     ;; #8 (RLM-emit-able helper)
   aggregate-node-type-seed          ;; #9 (RLM-emit-able helper)
   final-node-type-seed              ;; #10 (RLM-emit-able terminal marker)
   ])

;; =============================================================================
;; SEED #11 — tree class: document-analysis (benchmark)
;; =============================================================================
;;
;; The document-analysis benchmark task: extract a summary + key-dates +
;; entities from a large RFP-style document. Trees solving this class are
;; chunked-extraction shaped (chunk → map-each → aggregate → synth).

(def document-analysis-tree-seed
  {:target-id document-analysis-task-class-id
   :body
   {:capabilities
    ["extracts structured information (summary, dates, entities) from a long single-document input"
     "natural fit for chunked-extraction tree shape: chunk-document → map-each → aggregate → :llm synth"
     "produces three independent output keys (summary, key-dates, entities) consumed by downstream nodes"]

    :strengths
    [{:trait "chunked-extraction with bounded :max-concurrency reliably extracts entities from large documents"
      :good-when "input is a single document >5KB with extractable entities AND output is a structured triple (summary + dates + entities)"
      :recommended-pattern "[:sequence [:chunk-document {:from :document :size 5000 :into :chunks}] [:map-each {:from :chunks :as :chunk :into :results :max-concurrency 3} [:llm {:reads [:chunk] :writes [:entities :dates] :output-schemas {:entities [:vector :string] :dates [:vector :string]}}]] [:aggregate {:from :results :writes [:entities :dates]}] [:llm {:reads [:document] :writes [:summary]}] [:final {:keys [:summary :entities :dates]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "parallel summary + entity extraction reduces wall-time when downstream nodes are independent"
      :good-when "the document size justifies chunking AND summary doesn't need to consume entity-extraction output"
      :recommended-pattern "[:parallel [:llm {:reads [:document] :writes [:summary]}] [:sequence [:chunk-document {:from :document :size 5000 :into :chunks}] [:map-each {:from :chunks :as :chunk :into :results :max-concurrency 3} [:llm {:reads [:chunk] :writes [:risks]}]] [:aggregate {:from :results :writes [:risks]}]]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "unbounded :max-concurrency on per-chunk :llm calls exhausts rate limits on documents with 6+ chunks"
      :avoid-when "input is a large document AND :max-concurrency is unset on the :map-each"
      :recommended-alternative "always set :max-concurrency to 3 on the :map-each parent of per-chunk :llm calls"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "single-shot :llm on a >50KB document either truncates or burns token budget without yielding good output"
      :avoid-when "the document is >5KB and the tree is a single :llm without chunking"
      :recommended-alternative "always chunk first (use :chunk-document) for documents above ~5KB"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["large RFP-style document extraction (a real public-sector RFP, ~280K chars, is the canonical exercise — produces summary + dates + entities triple)"
     "any large structured document extraction (RFP, contract, regulatory filing)"]

    :avoid-when
    ["input is a small (<5KB) document — single :llm call is simpler"
     "output is a single field (just :summary or just :entities) — don't add aggregation overhead"
     "the document is binary (image, PDF) — use the predict-rlm-pdf or predict-rlm-image-tools brick first to extract text"]

    :summary
    "Document-analysis trees extract summary + key-dates + entities from a large single-document input. The proven shape is chunked-extraction: :chunk-document → :map-each (bounded :max-concurrency 3) over per-chunk :llm → :aggregate → final synthesis :llm. Always chunk for documents above ~5KB. Always bound :max-concurrency to avoid sub-LLM rate-limit exhaustion. For independent summary + entity extraction, use :parallel to reduce wall-time."

    :version 1
    :consolidated-from-event-count 0

    ;; C-2d-1: child of the chunked-extraction task-class. Document-analysis
    ;; is a concrete benchmark instance of the canonical chunked-extraction
    ;; shape.
    :parent-tree-id principles/chunked-extraction-task-class-id}})

;; =============================================================================
;; SEED #12 — tree class: risk-analysis (benchmark)
;; =============================================================================
;;
;; The risk-analysis benchmark: identify and categorize risks/obligations
;; in a document. Outputs :risk-matrix (each obligation mapped to severity
;; HIGH/MEDIUM/LOW + justification) and :executive-summary. Classification-
;; flavored extraction; per-clause/per-section processing is the natural
;; decomposition.

(def risk-analysis-tree-seed
  {:target-id risk-analysis-task-class-id
   :body
   {:capabilities
    ["identifies and categorizes risks/obligations in a document with severity ratings"
     "produces a risk-matrix (per-obligation HIGH/MEDIUM/LOW classification + justification) + executive-summary"
     "classification-flavored extraction; per-clause/per-section processing is the natural decomposition"]

    :strengths
    [{:trait "per-section :map-each with structured output schema reliably classifies risks across long documents"
      :good-when "document has natural sections (clauses, paragraphs) AND each section can be independently classified"
      :recommended-pattern "[:sequence [:chunk-document {:from :document :delimiter \"\\n\\n\" :into :sections}] [:map-each {:from :sections :as :section :into :results :max-concurrency 3} [:llm {:reads [:section] :writes [:risks] :output-schemas {:risks [:vector [:map [:obligation :string] [:severity [:enum :high :medium :low]] [:justification :string]]]}}]] [:aggregate {:from :results :writes [:risk-matrix]}] [:llm {:reads [:document :risk-matrix] :writes [:executive-summary]}] [:final {:keys [:risk-matrix :executive-summary]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "single :llm classification on a long document misses many obligations because of attention-budget exhaustion"
      :avoid-when "document is >5KB AND tree is a single :llm without per-section chunking"
      :recommended-alternative "split into sections (use :chunk-document with :delimiter) and run per-section :map-each :llm with bounded concurrency"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "without :output-schemas, severity classifications drift to inconsistent strings ('high', 'HIGH', 'High', 'critical') across iterations — downstream aggregation breaks"
      :avoid-when "the per-section :llm writes severity as a free-form string"
      :recommended-alternative "always declare :output-schemas with severity as [:enum :high :medium :low] so the model is constrained to consistent values"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["contract risk assessment (per-clause obligation extraction)"
     "regulatory-document risk inventory (per-section compliance review)"
     "any classification-heavy extraction where consistent enum values matter"]

    :avoid-when
    ["the document has no natural section boundaries — chunk-by-size and tolerate that some obligations straddle boundaries"
     "output doesn't need per-item categorization — use simpler entity-extraction patterns instead"]

    :summary
    "Risk-analysis trees identify and categorize risks/obligations from a document into a per-item :risk-matrix (HIGH/MEDIUM/LOW + justification) plus an :executive-summary. The proven shape is per-section :map-each with structured :output-schemas: split by section delimiter, classify each section under bounded concurrency, aggregate the matrix, then synthesize the executive summary. Always use :output-schemas to constrain severity to an enum — free-form strings drift across iterations and break aggregation."

    :version 1
    :consolidated-from-event-count 0

    ;; C-2d-1: child of the chunked-extraction task-class. Per-section
    ;; map-each with structured outputs is the same shape; risk-analysis
    ;; is a benchmark variant with severity enum + executive summary.
    :parent-tree-id principles/chunked-extraction-task-class-id}})

;; =============================================================================
;; SEED #13 — tree class: contract-comparison (benchmark)
;; =============================================================================
;;
;; The contract-comparison benchmark: compare two contracts (or two
;; versions of the same contract). Outputs :key-differences,
;; :similarities, :recommendation. Two-input shape distinguishes it
;; from single-document task classes.

(def contract-comparison-tree-seed
  {:target-id contract-comparison-task-class-id
   :body
   {:capabilities
    ["compares two contracts (or two versions of the same contract) for key differences and similarities"
     "produces :key-differences (provision-level diff), :similarities (shared provisions), :recommendation (which version to prefer or what to negotiate)"
     "two-input shape: separate :contract-a and :contract-b blackboard keys, not a single :document"
     "natural fit for :parallel extraction across both contracts followed by sequential diff synthesis"]

    :strengths
    [{:trait "parallel per-contract extraction reduces wall-time when both contracts can be processed independently"
      :good-when "both contracts share a roughly comparable structure AND the per-contract extraction is identical work"
      :recommended-pattern "[:sequence [:parallel [:llm {:reads [:contract-a] :writes [:provisions-a] :output-schemas {:provisions-a [:vector [:map [:topic :string] [:terms :string]]]}}] [:llm {:reads [:contract-b] :writes [:provisions-b] :output-schemas {:provisions-b [:vector [:map [:topic :string] [:terms :string]]]}}]] [:llm {:reads [:provisions-a :provisions-b] :writes [:key-differences :similarities :recommendation]}] [:final {:keys [:key-differences :similarities :recommendation]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "diff-synthesis :llm sees TWO large input contexts simultaneously — hits attention/budget limits sooner than single-doc summarization"
      :avoid-when "both contracts are >5KB AND the synthesis :llm receives the full text of both directly"
      :recommended-alternative "extract structured per-contract provisions first (compact representations), then feed those CONDENSED structures to the synthesis :llm instead of raw text"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "without per-contract structured extraction, the synthesis :llm hallucinates provisions that don't exist in either contract"
      :avoid-when "the synthesis :llm reads raw :contract-a + :contract-b directly without intermediate structured extraction"
      :recommended-alternative "always have an intermediate :llm with :output-schemas that extracts a typed provisions vector PER contract before the diff step — the diff :llm consumes the structured vectors, not raw text"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["contract version-vs-version diff (e.g. provider contract v2.0 vs v3.1 — produces provision-level differences + recommendation on which to prefer)"
     "vendor-contract negotiation review (compare current draft vs prior version)"
     "any two-document structural comparison where provision-level diff matters"]

    :avoid-when
    ["only one contract is provided — use risk-analysis or single-document extraction instead"
     "the comparison is purely textual diff (line-by-line) — use a non-LLM diff tool"
     "more than two contracts — extend with :map-each over a vector of contracts; this seed's pattern doesn't scale beyond two directly"]

    :summary
    "Contract-comparison trees compare two contracts (or two versions) for key differences, similarities, and a recommendation. The proven shape: :parallel per-contract structured-extraction (:output-schemas guaranteeing typed provisions), then sequential synthesis :llm consuming the structured vectors (NOT raw text). Always extract first, then diff — feeding both raw contracts to a single synthesis :llm hits attention limits and induces hallucinated provisions."

    :version 1
    :consolidated-from-event-count 0

    ;; C-2d-1: child of the parallel-independent pattern. The proven shape
    ;; runs per-contract structured extraction in parallel, then synthesis.
    :parent-tree-id parallel-independent-fp}})

;; =============================================================================
;; SEED #14 — tree class: legal-issue-detection (benchmark)
;; =============================================================================
;;
;; The legal-issue-detection benchmark: flag legal concerns in a
;; document. Outputs :issues (each with severity, area-of-law, source
;; citation, recommendation) + :severity-summary (rolled-up by severity).
;; A FLAG-LOUDER variant of risk-analysis — citations to source AND
;; recommendations matter.

(def legal-issue-detection-tree-seed
  {:target-id legal-issue-detection-task-class-id
   :body
   {:capabilities
    ["flags legal concerns in a document — per-issue severity + area-of-law + source citation + recommendation"
     "produces :issues (vector with per-item structure) + :severity-summary (rolled-up counts by severity)"
     "FLAG-LOUDER variant of risk-analysis: citations back to source AND recommendations are first-class outputs"
     "natural decomposition: per-section issue identification, then aggregation, then severity rollup"]

    :strengths
    [{:trait "per-section :map-each with structured :output-schemas reliably surfaces issues with consistent severity + citation shape"
      :good-when "document has natural sections (paragraphs, clauses) AND each section can be independently scanned for issues"
      :recommended-pattern "[:sequence [:chunk-document {:from :document :delimiter \"\\n\\n\" :into :sections}] [:map-each {:from :sections :as :section :into :results :max-concurrency 3} [:llm {:reads [:section] :writes [:section-issues] :output-schemas {:section-issues [:vector [:map [:concern :string] [:severity [:enum :high :medium :low]] [:area-of-law :string] [:citation :string] [:recommendation :string]]]}}]] [:aggregate {:from :results :writes [:issues]}] [:code {:reads [:issues] :writes [:severity-summary] :fn (fn [{:keys [issues]}] {:severity-summary (frequencies (map :severity issues))})}] [:final {:keys [:issues :severity-summary]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "issues without citation fields become 'recommendations the user can't verify' — model needs to be explicitly required to cite"
      :avoid-when "the per-section :llm's :output-schemas does NOT include a :citation field"
      :recommended-alternative "always include :citation in the :output-schemas + word the instruction to require quoting the source phrase that triggered the issue"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "severity-summary computed via :llm wastes tokens — it's pure :code work (frequencies over a vector)"
      :avoid-when "the severity-summary step is a separate :llm call instead of a :code transform"
      :recommended-alternative "use a :code node with (fn [{:keys [issues]}] {:severity-summary (frequencies (map :severity issues))}) — deterministic, fast, zero tokens"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["compliance review of a draft regulation or policy"
     "legal due diligence of a contract or agreement"
     "audit of a corporate document for liability exposure"]

    :avoid-when
    ["the document has no natural section structure — chunk-by-size + tolerate boundary-straddling issues"
     "the task is general risk identification (no legal-specific requirements like citations) — use risk-analysis instead"]

    :summary
    "Legal-issue-detection trees flag legal concerns in a document with per-issue severity, area-of-law, source citation, and recommendation. The proven shape mirrors risk-analysis but with citation as a first-class output: per-section :map-each with :output-schemas requiring :citation field, then :code (NOT :llm) for the severity-summary rollup. Always require citation in the schema so users can verify the model's flags against the source."

    :version 1
    :consolidated-from-event-count 0

    ;; C-2d-1: child of the chunked-extraction task-class. Per-section
    ;; map-each variant with citation as a first-class output + :code
    ;; rollup. Same structural family as risk-analysis.
    :parent-tree-id principles/chunked-extraction-task-class-id}})

;; =============================================================================
;; SEED #15 — tree class: chunked-extraction (cross-cutting structural pattern)
;; =============================================================================
;;
;; The chunked-extraction task class is STRUCTURAL, not domain-specific —
;; it cross-cuts document-analysis, risk-analysis, legal-issue-detection,
;; and any other task where extraction from a large document is the work.
;;
;; THIS is the seed C-1's live-verify exercised — the bounded-map-each
;; principle is grounded in real run data (the 3-way comparison in
;; development/src/c1_live_verify.clj produced a difference exactly in
;; line with this seed's recommendations).

(def chunked-extraction-tree-seed
  {:target-id chunked-extraction-task-class-id
   :body
   {:capabilities
    ["STRUCTURAL pattern (not domain-specific): chunk a large document → process each chunk → aggregate results"
     "cross-cuts every domain-specific task class that involves long-document extraction (document analysis, risk analysis, legal issue detection, per-page document processing)"
     "the canonical 4-stage skeleton: :chunk-document → :map-each → :aggregate → optional final :llm synthesis"
     "the bounded-:max-concurrency rule has been verified end-to-end: when the principle is injected, the model emits :max-concurrency 3 on the :map-each; when absent, the model emits unbounded — a structurally different tree"]

    :strengths
    [{:trait "the bounded-:max-concurrency :map-each pattern is the canonical solution for chunked extraction"
      :good-when "the input is a document larger than ~5KB AND extraction is per-chunk independent"
      :recommended-pattern "[:sequence [:chunk-document {:from :document :size 5000 :into :chunks}] [:map-each {:from :chunks :as :chunk :into :results :max-concurrency 3} [:llm {:reads [:chunk] :writes [:per-chunk-result]}]] [:aggregate {:from :results :writes [:all-results]}] [:final {:keys [:all-results]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-20T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "extending the skeleton with a final synthesis :llm produces a coherent summary alongside the per-chunk results"
      :good-when "the task requires both per-chunk extracted data AND a top-level synthesis"
      :recommended-pattern "[:sequence [:chunk-document {:from :document :size 5000 :into :chunks}] [:map-each {:from :chunks :as :chunk :into :all-results :max-concurrency 3} [:llm {:reads [:chunk] :writes [:items]}]] [:aggregate {:from :all-results :writes [:items]}] [:llm {:reads [:document :all-results] :writes [:summary]}] [:final {:keys [:all-results :summary]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "unbounded :max-concurrency on the :map-each exhausts sub-LLM rate limits on documents with 6+ chunks"
      :avoid-when "input is a document with >6 chunks AND :max-concurrency is unset OR set higher than provider rate-limit tolerance"
      :recommended-alternative "always set :max-concurrency to 3 on the :map-each parent (safe default across major LLM providers); tune higher only after measuring against the specific provider's rate limit"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-20T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "skipping the :aggregate step means downstream consumers see a raw vector of per-iteration maps — usually not the shape they want"
      :avoid-when "the tree has :map-each but no :aggregate AND a downstream node expects per-key flat data"
      :recommended-alternative "always include :aggregate after :map-each (or use :code with explicit merge logic for non-default consolidation)"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "very small documents (<5KB) don't benefit from chunking — chunked-extraction adds overhead with no win"
      :avoid-when "the input document is less than ~5KB AND the work fits in a single :llm context window"
      :recommended-alternative "use a single :llm node directly without :chunk-document / :map-each / :aggregate"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["foundation pattern for any large-document extraction task (document analysis, risk analysis, legal issue detection)"
     "per-page processing of multi-page documents (image, PDF, or structured page-separated content)"
     "any task where the input is too large for a single :llm context window AND the extraction is per-chunk independent"]

    :avoid-when
    ["input is small (<5KB) — single :llm without chunking is simpler"
     "extraction requires cross-chunk context (e.g. detecting trends across the whole document) — chunked-extraction loses that context; consider a non-chunked single-pass :llm or a two-pass tree that does cross-chunk reasoning"
     "the work is generation (writing new content) rather than extraction — chunked-extraction is an extraction pattern"]

    :summary
    "Chunked-extraction is THE structural pattern for processing large documents — cross-cuts every domain-specific task class involving long-document extraction. The canonical 4-stage skeleton is :chunk-document → :map-each (bounded :max-concurrency 3) → :aggregate → optional final :llm synthesis. Empirically, injecting the bounded-:max-concurrency principle changes the model's design: the emitted :map-each carries :max-concurrency 3 when the principle is in scope, and is unbounded when the principle is absent — a structurally different tree. Always bound :max-concurrency to 3 (tune higher only after measuring provider rate limits). Skip the pattern entirely for documents <5KB."

    :version 1
    :consolidated-from-event-count 0

    ;; C-2d-1: child of the abstract chunked-extraction pattern. This
    ;; benchmark task-class is a concrete instantiation of the canonical
    ;; structural pattern (seed #16).
    :parent-tree-id chunked-extraction-fp}})

;; =============================================================================
;; SEED #16 — generic pattern: success:ChunkedExtraction
;; =============================================================================
;;
;; Generic STRUCTURAL pattern (not a task class) — describes the canonical
;; chunked-extraction tree shape itself. Useful when a developer or model
;; asks "what's a tree shape for processing large documents?" — this seed
;; surfaces alongside the domain-specific seeds (document-analysis,
;; risk-analysis, etc.) that USE this pattern.
;;
;; Distinct from seed #15 (chunked-extraction TASK CLASS) — that seed
;; describes principles for trees solving "the chunked-extraction class
;; of problems"; THIS seed describes the SHAPE itself as a reusable pattern.

(def chunked-extraction-pattern-seed
  {:target-id chunked-extraction-fp
   :body
   {:capabilities
    ["the canonical 4-stage shape: :chunk-document → :map-each → :aggregate → optional final :llm synthesis"
     "reusable across any domain where the input is a large document and the work is per-chunk independent"
     "leverages :map-each's bounded-parallelism + :aggregate's per-key merge to produce a clean, downstream-consumable result shape"]

    :strengths
    [{:trait "the 4-stage skeleton is the proven shape for any large-document extraction"
      :good-when "the input is a document larger than ~5KB AND extraction is per-chunk independent AND the output schema is uniform across chunks"
      :recommended-pattern "[:sequence [:chunk-document {:from :document :size 5000 :into :chunks}] [:map-each {:from :chunks :as :chunk :into :results :max-concurrency 3} [:llm {:reads [:chunk] :writes [:per-chunk-data]}]] [:aggregate {:from :results :writes [:all-data]}] [:final {:keys [:all-data]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "the skeleton assumes per-chunk independence — fails when extraction needs cross-chunk context"
      :avoid-when "the extraction depends on cumulative state (e.g. tracking entity mentions across the document) OR requires comparing data across chunks"
      :recommended-alternative "two-pass approach — chunked-extraction first, then a second pass that cross-references results; OR use a non-chunked single-pass :llm if the document fits the context window"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["document analysis — extract summary, dates, entities from large RFPs / contracts"
     "risk-classification — extract per-section risk ratings with structured output"
     "per-page processing of multi-page documents (image, PDF, or structured page-separated content)"]

    :avoid-when
    ["the document is small enough to fit in a single :llm context — chunking adds overhead with no win"
     "extraction requires cross-chunk reasoning (trend detection, entity co-reference across the whole document)"
     "the work is content generation, not extraction"]

    :summary
    "ChunkedExtraction is the canonical 4-stage shape for processing large documents: :chunk-document → :map-each (bounded :max-concurrency 3) → :aggregate → optional final :llm synthesis. Reusable across any domain where the input is a large document and the work is per-chunk independent. Always bound :max-concurrency on the :map-each to avoid sub-LLM rate-limit exhaustion. Skip the pattern for documents <5KB or when cross-chunk reasoning is required."

    :version 1
    :consolidated-from-event-count 0}})

;; =============================================================================
;; SEED #17 — generic pattern: success:SequentialPipeline
;; =============================================================================
;;
;; A chain of :llm (or mixed :llm/:code) nodes where each step transforms
;; the previous step's output. Useful for iterative refinement, ETL-style
;; multi-stage processing, or any work that decomposes into ordered
;; dependent stages.

(def sequential-pipeline-pattern-seed
  {:target-id sequential-pipeline-fp
   :body
   {:capabilities
    [":sequence of two or more :llm/:code nodes where each step consumes the previous step's :writes"
     "natural for ETL-style multi-stage transformation (extract → enrich → format)"
     "natural for iterative refinement (draft → critique → revise)"
     "structurally simple — easy to design, easy to debug"]

    :strengths
    [{:trait "stage-by-stage chaining matches problems with explicit ordered dependencies"
      :good-when "the work has 2-3 distinct phases AND each phase consumes the previous phase's specific output AND each phase is non-trivial"
      :recommended-pattern "[:sequence [:llm {:reads [:input] :writes [:extracted]}] [:llm {:reads [:extracted] :writes [:enriched]}] [:llm {:reads [:enriched] :writes [:formatted]}] [:final {:keys [:formatted]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "3+ small :llm steps burns sub-LLM tokens that could be a single larger :llm call with a multi-step instruction"
      :avoid-when "the chain has 3+ :llm children doing successive small transformations on the same data AND each transformation is simple"
      :recommended-alternative "consolidate into ONE :llm with a longer multi-step instruction; OR use :code for any deterministic intermediate stages (formatting, joining, regex extraction)"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "no parallelism — total wall-time is the sum of all children's durations"
      :avoid-when "two or more stages in the pipeline could run independently"
      :recommended-alternative "use :parallel for the independent stages, then :sequence around the genuinely-dependent ones; OR restructure as :parallel + final synthesis"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["draft → critique → revise iterative refinement (multi-pass quality improvement)"
     "extract → transform → load (classical ETL)"
     "raw-text → structured-extract → format-for-export pipelines"]

    :avoid-when
    ["stages are independent — use :parallel"
     "the work is per-item over a collection — use :map-each"
     "the chain is 3+ small :llm steps — consolidate into a single :llm with multi-step instruction"]

    :summary
    "SequentialPipeline is a :sequence of 2+ :llm/:code nodes where each step consumes the previous step's output. Natural for ETL (extract → enrich → format) and iterative refinement (draft → critique → revise). Avoid when stages are independent (use :parallel) or when 3+ small :llm steps could be one larger :llm — small successive :llm calls burn tokens that a single multi-step instruction would cover."

    :version 1
    :consolidated-from-event-count 0}})

;; =============================================================================
;; SEED #18 — generic pattern: success:ParallelIndependent
;; =============================================================================
;;
;; A :parallel of N children where each child writes to DISTINCT keys.
;; Used when the same input requires multiple independent transformations
;; AND wall-time matters. Distinct from :map-each (which iterates over
;; a COLLECTION of inputs; this iterates over a SET of transformations
;; on ONE input).

(def parallel-independent-pattern-seed
  {:target-id parallel-independent-fp
   :body
   {:capabilities
    [":parallel of N children where each child writes to DISTINCT keys"
     "useful when the same input must be transformed multiple ways AND wall-time matters"
     "complements (not replaces) :map-each — :map-each iterates a collection; this iterates transformations on one input"
     "typically wraps two or three :llm children with non-overlapping :writes"]

    :strengths
    [{:trait "wall-time equals the slowest child instead of the sum of all children"
      :good-when "the same source data needs 2-3+ independent transformations AND children's latencies are similar AND children's :writes don't overlap"
      :recommended-pattern "[:sequence [:parallel [:llm {:reads [:document] :writes [:summary]}] [:llm {:reads [:document] :writes [:entities]}] [:llm {:reads [:document] :writes [:key-dates]}]] [:final {:keys [:summary :entities :key-dates]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "two children with overlapping :writes race — the surviving value is non-deterministic"
      :avoid-when "any two children declare the same key in their :writes"
      :recommended-alternative "give each child a distinct :writes key; OR use :sequence so writes happen in order; OR aggregate via :code after the :parallel"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "uneven child latencies mean the parallel speedup is bottlenecked by the slowest"
      :avoid-when "one child is dramatically slower than the others AND a downstream node needs all children's results"
      :recommended-alternative "split the slow child into smaller pieces via :map-each so its work parallelizes too; OR restructure so the slow child runs in :sequence with a downstream node that doesn't need siblings' results"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "children cannot read each other's outputs — they execute concurrently"
      :avoid-when "a child's :reads include a key another sibling writes to"
      :recommended-alternative "use :sequence to chain dependent computations; OR ensure each child reads only from blackboard keys set BEFORE the :parallel"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["concurrent independent extractions from a shared document (summary + entities + dates in one wall-time pass)"
     "cross-validation: same input through two different prompts → results compared downstream"
     "fan-out to multiple sub-LLM models for evaluation (each model writes to its own key; downstream compares)"]

    :avoid-when
    ["the work is per-item over a collection — use :map-each (collection iteration), not :parallel (independent transformations)"
     "any child depends on any other child's output — use :sequence"
     "only one child exists — drop the :parallel wrapper"]

    :summary
    "ParallelIndependent is a :parallel of N children where each child writes to DISTINCT keys. Wall-time equals the slowest child instead of their sum. Use when the same input needs 2-3+ independent transformations and child latencies are similar. Children CANNOT read each other's outputs; overlapping :writes keys race; the slowest child bottlenecks total time. Distinct from :map-each — :map-each iterates a COLLECTION of inputs; this iterates a SET of TRANSFORMATIONS on one input."

    :version 1
    :consolidated-from-event-count 0}})

;; =============================================================================
;; SEED #19 — generic pattern: success:ValidationLoop
;; =============================================================================
;;
;; A pattern where one node produces output, a second node validates it,
;; and the system reacts to the validation outcome (accept / retry /
;; discard). The classical "loop" requires :condition for branching, which
;; the RLM tree-DSL transformer does NOT yet support — so this seed
;; describes BOTH the ideal canonical-DSL shape AND a constrained-shape
;; workaround the RLM model can author today.

(def validation-loop-pattern-seed
  {:target-id validation-loop-fp
   :body
   {:capabilities
    ["a producer node emits a candidate output; a validator node checks it; the tree reacts to the validation outcome"
     "the classical 'loop' form (produce → validate → conditional retry) requires :condition for branching"
     "RLM's tree-DSL does NOT yet support :condition — the looping form is canonical-DSL-only"
     "RLM can author a CONSTRAINED variant: producer → validator that emits a flag → downstream consumer that reads the flag"]

    :strengths
    [{:trait "explicit validation step catches downstream-consumer-breaking errors early"
      :good-when "the producer output is structurally fragile (free-form text destined for downstream :code parsing) AND a quick validator can flag obvious problems"
      :recommended-pattern "[:sequence [:llm {:reads [:input] :writes [:candidate]}] [:llm {:reads [:candidate] :writes [:valid? :validation-notes] :output-schemas {:valid? :boolean :validation-notes :string}}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "the classical 'loop' form needs :condition for the retry/accept branch — not currently emit-able by RLM"
      :avoid-when "designing an emit-tree! pattern AND wanting true conditional retry behavior"
      :recommended-alternative "use the CONSTRAINED variant above (producer → validator-with-flag); downstream :code or :llm reads the :valid? flag and acts on it; OR use :fallback for retry-style behavior when supported"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "the validator itself can be wrong — false positives (rejecting valid output) or false negatives (accepting bad output) both poison the pipeline"
      :avoid-when "the validator is given a vague instruction OR the validation criteria aren't enumerable"
      :recommended-alternative "tighten the validator's instruction to enumerate concrete pass/fail criteria; OR use a :code validator with explicit schema checks (deterministic, no LLM-judgment ambiguity)"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["validate-then-format: producer writes structured data, validator checks shape, downstream consumer reads the validation flag"
     "two-pass quality check on long-form generation (producer drafts, validator flags concerns)"
     "schema-conformance check before downstream :code parsing"]

    :avoid-when
    ["the producer is already constrained by :output-schemas — that IS the validation; no separate validator needed"
     ":code can do the validation deterministically — use a :code validator instead of an :llm one (faster, cheaper, no judgment ambiguity)"
     "the pipeline can tolerate occasional bad outputs — adding validation overhead may not be worth it"]

    :summary
    "ValidationLoop is a producer-then-validator pattern where one node emits a candidate output and a second node checks it. The classical 'loop' form (produce → validate → conditional retry on failure) requires :condition for branching — NOT emit-able by RLM's tree-DSL today. The constrained variant the model CAN author: producer → validator emitting a :valid? flag → downstream consumer reading the flag. Always prefer :code validators with explicit schema checks over :llm validators when validation criteria are deterministic — faster, cheaper, no judgment ambiguity."

    :version 1
    :consolidated-from-event-count 0}})

;; =============================================================================
;; SEED #20 — generic pattern: success:FallbackRecovery
;; =============================================================================
;;
;; A primary approach paired with a simpler/cheaper backup. The primary
;; runs; on :failure, the backup runs. Useful for graceful degradation
;; (high-quality model → cheaper model, strict schema → free-form parse,
;; LLM extraction → rule-based regex).
;;
;; Requires :fallback in the tree-DSL — NOT currently emit-able by RLM.
;; Seed describes the canonical pattern AND a constrained-shape workaround
;; the RLM model can author today (linear retry inside a single :llm with
;; instruction-level fallback wording).

(def fallback-recovery-pattern-seed
  {:target-id fallback-recovery-fp
   :body
   {:capabilities
    [":fallback of two children: try the primary; on :failure, run the simpler/cheaper backup"
     "useful for graceful degradation when the primary approach can fail but a degraded version is acceptable"
     "the model can pin a per-:llm :model override on each child for cost-tiering (e.g. high-capability primary + cheaper backup)"
     "RLM tree-DSL does NOT yet support :fallback — canonical-DSL only as of this seed's authoring"]

    :strengths
    [{:trait "explicit recovery path captures real-world fragility — primary may fail; backup absorbs"
      :good-when "the primary approach has a known failure mode AND the backup produces equivalent :writes"
      :recommended-pattern "[:fallback [:llm {:model \"openai/gpt-5\" :reads [:input] :writes [:result]}] [:llm {:model \"google/gemini-2.5-flash\" :reads [:input] :writes [:result]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "RLM models cannot emit :fallback in emit-tree! today — pattern is canonical-DSL-only"
      :avoid-when "designing an emit-tree! pattern AND wanting recovery-style branching"
      :recommended-alternative "use a single :llm with an instruction that includes fallback guidance ('if X cannot be done, instead produce Y'); OR design the pipeline as :sequence with a downstream node that handles the missing-output case"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "each failed primary still runs to completion before failure is detected — total cost includes the wasted primary attempt"
      :avoid-when "the primary has high token cost AND fails often"
      :recommended-alternative "switch the order — put the cheaper backup first; OR use a guard upstream that predicts which approach to try based on input shape"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "silently masks real bugs — if the primary always fails and the backup always covers, the regression is invisible"
      :avoid-when "the primary is the canonical path AND silent fallback would hide degradation"
      :recommended-alternative "add a downstream :code or judge that logs/alerts when the backup fires; OR track per-tree-run which child succeeded so degradation is visible in metrics"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["cost-tiered model fallback: try a high-capability model first; on failure, fall back to a cheaper model"
     "schema-strict-then-loose: try a structured-output :llm; on failure, fall back to free-form parsing"
     "LLM-then-rule-based: try semantic extraction; on failure, fall back to deterministic :code regex/template matching"]

    :avoid-when
    ["all children would succeed — just use the primary (no recovery needed)"
     "you want both children's results compared — use :parallel"
     "you want only one child to run based on a known condition — use :condition (when supported) or upfront branching at the parent level"]

    :summary
    "FallbackRecovery is a :fallback of two children where the primary runs first; on :failure, the backup runs. The canonical recovery composite for graceful degradation (cost-tiered model fallback, schema-strict-then-loose, LLM-then-rule-based). NOT currently emit-able by RLM — for emit-tree! patterns wanting recovery semantics, use a single :llm with instruction-level fallback wording. Each failed primary still runs to completion before failure is detected — put the cheaper child first if its success rate is close to the primary's. Risk: silently masks bugs — pair with logging :code or a judge to surface when the backup fires."

    :version 1
    :consolidated-from-event-count 0}})

;; =============================================================================
;; SEED #21 — generic pattern: success:MapReduce
;; =============================================================================
;;
;; A 3-stage shape: split into chunks → map an :llm extraction over each
;; chunk → reduce per-chunk results into a single aggregate via :code
;; (deterministic) rather than :aggregate (LLM synthesis). Distinguished
;; from ChunkedExtraction by the reduction step: MapReduce uses :code for
;; mechanical reduction (counts, sums, dedup, flattening); ChunkedExtraction
;; uses :aggregate for cross-chunk synthesis where reduction is fuzzy.

(def map-reduce-pattern-seed
  {:target-id map-reduce-fp
   :body
   {:capabilities
    ["3-stage shape: :chunk-document → :map-each [:llm] → :code reducer that produces a single deterministic aggregate"
     "the reducer is :code (pure Clojure transform), not :aggregate (LLM synthesis) — zero tokens for the reduction step"
     "ideal when per-chunk results are structured and the cross-chunk reduction is mechanical (count, sum, dedup, flatten, group-by)"
     "distinct from ChunkedExtraction whose reducer is :aggregate — MapReduce makes the reduce step deterministic"]

    :strengths
    [{:trait "deterministic reduction saves tokens AND eliminates LLM ambiguity in the aggregation step"
      :good-when "per-chunk :llm output is uniform and structured (e.g. each chunk writes a vector of entities) AND the cross-chunk combine is a pure transform (flatten, count, dedup)"
      :recommended-pattern "[:sequence [:chunk-document {:from :document :size 5000 :into :chunks}] [:map-each {:from :chunks :as :chunk :into :per-chunk-entities :max-concurrency 3} [:llm {:reads [:chunk] :writes [:entities] :output-schemas {:entities [:sequential :string]}}]] [:code {:reads [:per-chunk-entities] :writes [:all-entities] :fn (fn [{:keys [per-chunk-entities]}] {:all-entities (vec (distinct (mapcat :entities per-chunk-entities)))})}] [:final {:keys [:all-entities]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait ":output-schemas on the per-chunk :llm makes the reducer's destructuring safe"
      :good-when "the :code reducer must destructure per-chunk results AND any chunk producing a malformed shape would crash the reducer"
      :recommended-pattern "[:llm {:reads [:chunk] :writes [:items] :output-schemas {:items [:sequential :string]}}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "the reducer assumes uniform per-chunk shape — one malformed chunk crashes the whole reduce"
      :avoid-when "per-chunk :llm has no :output-schemas constraint AND any chunk's output might be nil or wrong-shaped"
      :recommended-alternative "add :output-schemas on the per-chunk :llm so the shape is enforced; OR write defensive destructuring inside the :code reducer ((mapcat #(or (:entities %) []) per-chunk-entities))"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "the :code reducer must be SCI-safe — no dynamic require, no Java interop outside the SCI whitelist"
      :avoid-when "the reduce step needs a library function not on the SCI whitelist OR requires dynamic resolution"
      :recommended-alternative "reference a real Clojure fn via qualified symbol ('my.ns/reduce-entities) instead of an inline lambda; OR move the non-SCI work upstream / downstream of the reducer"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "if the cross-chunk reduce is fuzzy (e.g. deduplicating semantically-similar items, synthesizing a summary across chunks), :code is the wrong tool"
      :avoid-when "the reduce requires natural-language judgment — semantic dedup, narrative synthesis, prioritization across chunks"
      :recommended-alternative "use ChunkedExtraction's :aggregate + final :llm synthesis instead; OR use a :code reducer to flatten first, then a final :llm to synthesize the flattened collection"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["counting entities or occurrences across a large document (per-chunk extraction + :code (count (mapcat ...))"
     "deduplicating a flat collection extracted per-chunk (per-chunk vectors → :code (distinct (mapcat ...))"
     "summing structured numeric extractions across chunks (per-chunk numbers → :code reduce-sum)"
     "group-by aggregation where per-chunk results carry a category and the reducer buckets them"]

    :avoid-when
    ["the reduce needs LLM judgment (semantic synthesis, narrative summary across chunks) — use ChunkedExtraction with :aggregate instead"
     "the document is small enough to fit a single :llm context — chunking adds overhead with no win"
     "per-chunk results are already what downstream needs — drop the reducer entirely and pass the :map-each output through"]

    :summary
    "MapReduce is a 3-stage shape: :chunk-document → :map-each [:llm] → :code reducer. The reducer is deterministic Clojure (count, sum, dedup, flatten, group-by) rather than LLM synthesis, saving tokens and eliminating aggregation ambiguity. Pair the per-chunk :llm with :output-schemas so the reducer's destructuring is safe. Choose MapReduce over ChunkedExtraction when the cross-chunk combine is mechanical; choose ChunkedExtraction when it requires natural-language judgment."

    :version 1
    :consolidated-from-event-count 0}})

;; =============================================================================
;; SEED #22 — generic pattern: success:ResearchThenSynthesize
;; =============================================================================
;;
;; A 4-stage shape: chunk a document → extract per-chunk material with
;; research-style prompts → aggregate the per-chunk material → run a
;; FINAL :llm synthesis over the aggregated material to produce a
;; cohesive narrative answer. Distinguished from ChunkedExtraction by
;; the REQUIRED synthesis step: ChunkedExtraction's terminal step is
;; the :aggregate (per-key merge); ResearchThenSynthesize's terminal
;; step is an :llm that integrates the aggregated material into a
;; holistic answer. Distinguished from MapReduce by the reducer kind:
;; MapReduce reduces deterministically with :code; this synthesizes
;; with :llm because the integration requires natural-language judgment.

(def research-then-synthesize-pattern-seed
  {:target-id research-then-synth-fp
   :body
   {:capabilities
    ["4-stage shape: :chunk-document → :map-each [:llm extract] → :aggregate → final :llm synthesis"
     "the FINAL :llm reads the aggregated per-chunk material and produces a cohesive narrative output"
     "ideal for research tasks where the answer is a synthesis (briefing, summary, recommendation) rather than a flat extraction"
     "the per-chunk extraction step is intentionally research-shaped — broader prompts asking 'what is relevant here' rather than narrow schema extraction"]

    :strengths
    [{:trait "separating per-chunk research from cross-chunk synthesis lets each :llm focus on a narrower job"
      :good-when "the input is a large document AND the answer requires reasoning across the whole document AND the final answer is a narrative/recommendation (not a flat extraction)"
      :recommended-pattern "[:sequence [:chunk-document {:from :document :size 5000 :into :chunks}] [:map-each {:from :chunks :as :chunk :into :per-chunk-notes :max-concurrency 3} [:llm {:reads [:chunk] :writes [:notes] :instruction \"Extract anything relevant to the research question in this chunk.\"}]] [:aggregate {:from :per-chunk-notes :writes [:all-notes]}] [:llm {:reads [:all-notes] :writes [:synthesis] :instruction \"Integrate the per-chunk notes into a cohesive answer to the research question.\"}] [:final {:keys [:synthesis]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "the synthesis :llm sees the aggregated notes rather than the raw document — fits long documents into a single final context"
      :good-when "the raw document would exceed the synthesis :llm's context window AND the aggregated per-chunk notes are concise enough to fit"
      :recommended-pattern "[:map-each {:from :chunks :as :chunk :into :per-chunk-notes :max-concurrency 3} [:llm {:reads [:chunk] :writes [:notes] :instruction \"Summarize this chunk in <300 tokens, retaining only material relevant to the research question.\"}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :weaknesses
    [{:trait "if per-chunk extraction is too aggressive, the aggregated material can itself exceed the synthesis :llm's context window"
      :avoid-when "per-chunk :llm prompts ask for verbatim quotes / long excerpts AND the document has many chunks"
      :recommended-alternative "constrain the per-chunk :llm via :output-schemas + a token-budget instruction ('summarize in <300 tokens'); OR add an intermediate :map-each [:llm] compression pass before final synthesis; OR split the synthesis into a 2-stage reduce-tree"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "the synthesis :llm can introduce claims not grounded in any per-chunk note — hallucination risk at the integration step"
      :avoid-when "the answer must be strictly traceable to source material AND no grounding judge / citation step follows"
      :recommended-alternative "add :output-schemas requiring per-claim citations on the synthesis :llm; OR add a downstream grounding judge that scores synthesis claims against the aggregated notes; OR ask the synthesis :llm to quote source notes verbatim for each claim"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}
     {:trait "two :llm passes per chunk's worth of work (per-chunk extract + final synthesize) costs more than a single :llm or a :code reducer"
      :avoid-when "the cross-chunk combine is mechanical (count, sum, dedup, flatten) — synthesis :llm is overkill"
      :recommended-alternative "use MapReduce instead (:code reducer for mechanical aggregation); OR use ChunkedExtraction if the :aggregate merge alone is enough"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-26T00:00:00Z"
      :last-reinforced-at "2026-05-26T00:00:00Z"}]

    :representative-uses
    ["research briefings over long documents — extract per-section findings, synthesize into a cohesive briefing"
     "executive summary generation from a multi-page report — per-page key points → aggregated → single synthesis"
     "comparative analysis where per-chunk notes are integrated into a narrative comparison at the end"
     "Q&A over a long document: per-chunk relevance extraction → aggregated → final synthesis answers the question"]

    :avoid-when
    ["the cross-chunk combine is mechanical — use MapReduce (:code reducer) instead"
     "the :aggregate alone is the answer (flat extraction across chunks) — use ChunkedExtraction instead"
     "the document fits in a single :llm context — chunking + 2-pass synthesis adds overhead with no win"
     "the answer must be strictly traceable to source material AND no grounding judge follows — synthesis :llm can hallucinate at the integration step"]

    :summary
    "ResearchThenSynthesize is a 4-stage shape: :chunk-document → :map-each [:llm extract] → :aggregate → final :llm synthesis. The final :llm integrates the aggregated per-chunk material into a cohesive narrative answer. Choose it when the answer requires reasoning across the whole document AND the output is a synthesis (briefing, summary, recommendation), not a flat extraction. Distinct from MapReduce (which uses :code for deterministic mechanical reduction) and from ChunkedExtraction (whose terminal step is the :aggregate merge). Watch the synthesis token budget — aggressive per-chunk extraction can overflow the final context — and pair with a grounding judge if claims must be traceable."

    :version 1
    :consolidated-from-event-count 0}})

;; =============================================================================
;; R02 — children under sequential-pipeline-fp
;; =============================================================================

(def etl-pipeline-tree-seed
  "Multi-stage extract/enrich/format chain where each stage's outputs
   feed the next stage's reads. The canonical ETL flow as a task-class
   under the sequential-pipeline pattern."
  {:target-id etl-pipeline-task-class-id
   :body
   {:capabilities
    ["ordered chain of typically 2-4 stages where each stage transforms the previous stage's output into a richer form"
     "natural fit for raw-input → normalized → enriched → emit-ready flows"
     "each stage's :reads consume the previous stage's :writes — explicit data dependency tracking"]

    :strengths
    [{:trait "explicit per-stage :reads/:writes dependency chain makes ETL debuggable — failures isolate to the stage that produced the broken intermediate"
      :good-when "input requires multiple distinct transformation passes AND each pass produces a named intermediate (normalized, enriched, formatted)"
      :recommended-pattern "[:sequence [:llm {:reads [:raw-input] :writes [:normalized]}] [:llm {:reads [:normalized :reference-data] :writes [:enriched]}] [:code {:reads [:enriched] :writes [:emit-ready] :code (fn [{:keys [enriched]}] {:emit-ready (clojure.string/join \",\" enriched)})}] [:final {:keys [:emit-ready]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "use :code (NOT :llm) for the final emit-format stage when the format is deterministic — saves tokens and avoids drift in field names"
      :good-when "the final stage is mechanical formatting (CSV row assembly, JSON envelope wrapping, fixed-width record padding)"
      :recommended-pattern "[:code {:reads [:enriched] :writes [:row] :code (fn [{:keys [enriched]}] {:row (clojure.string/join \",\" (map :v enriched))})}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "ETL chains break when an intermediate stage silently emits a different shape than the next stage expects — typed :output-schemas catch the shape mismatch at the wrong stage"
      :avoid-when "intermediate stages don't declare :output-schemas and downstream stages assume specific keys"
      :recommended-alternative "always declare :output-schemas on each intermediate stage AND validate at the next stage's :reads — push the shape contract upstream so the failing stage is identified, not the consumer"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "5+ stage chains burn tokens that a single multi-instruction :llm could cover when stages are short"
      :avoid-when "individual stages are 1-2 sentence transformations AND there's no need to inspect intermediates"
      :recommended-alternative "collapse 3+ short successive :llm stages into a single :llm with a multi-step instruction and structured :output-schemas for each intermediate"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["customer record normalization: parse → dedupe-fields → enrich-from-CRM → emit-warehouse-row"
     "log line parsing: tokenize → classify-event-type → annotate-with-context → emit-structured-event"
     "translation pipeline: clean-input → translate → polish-style → emit-final-text"]

    :avoid-when
    ["the work is per-chunk independent — use chunked-extraction instead"
     "stages can run in parallel writing to distinct keys — use :parallel instead"
     "the chain is 1 stage — just use a single :llm or :code node directly"]

    :summary
    "ETL pipelines chain 2-4 sequential stages where each stage's :writes feed the next stage's :reads. The proven shape uses :llm for content transformation and :code for deterministic formatting/aggregation at the tail. Always declare :output-schemas on intermediates so shape mismatches identify the offending stage, not the downstream consumer. Collapse very short successive stages into a single :llm to save tokens. Avoid when work is per-chunk independent (use chunked-extraction) or stages can run in parallel (use :parallel)."

    :version 1
    :consolidated-from-event-count 0

    ;; R02: child of sequential-pipeline — ETL is the canonical
    ;; staged-transformation variant of the sequential pattern.
    :parent-tree-id sequential-pipeline-fp}})

(def iterative-refinement-tree-seed
  "Draft → critique → revise loop expressed as a fixed-stage sequential
   pipeline. Distinct from validation-loop (which has a producer/validator
   semantic with explicit retry control); iterative-refinement is a
   single-pass refinement of one artifact through a sequence of revision
   gates."
  {:target-id iterative-refinement-task-class-id
   :body
   {:capabilities
    [":sequence of typically 3 stages: initial draft → critic feedback → revised draft (sometimes repeated for a fixed N)"
     "natural for content quality improvement where each pass identifies and addresses specific defects from the previous pass"
     "stages share the same content key but transform it forward — :draft → :critique → :revised-draft"]

    :strengths
    [{:trait "explicit critique stage between draft and revision forces the model to articulate specific defects before fixing them — outputs are higher quality than single-pass with self-edit"
      :good-when "the output requires nuance, factual precision, or stylistic care AND a single :llm pass tends to miss issues that re-reading would catch"
      :recommended-pattern "[:sequence [:llm {:reads [:prompt] :writes [:draft]}] [:llm {:reads [:draft :prompt] :writes [:critique]}] [:llm {:reads [:draft :critique :prompt] :writes [:revised-draft]}] [:final {:keys [:revised-draft]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "pass the original prompt into the revision stage's :reads alongside the critique — ensures the revision doesn't drift away from the original ask while addressing critique"
      :good-when "the critique stage tends to focus on style/issues and might lose sight of the original requirement"
      :recommended-pattern "[:llm {:reads [:draft :critique :prompt] :writes [:revised-draft]}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "fixed 3-stage refinement burns 3x the tokens of a single pass — overkill when the output is short or the prompt is straightforward"
      :avoid-when "output is under 200 tokens AND the prompt is concrete (no nuance) — single :llm with a careful instruction is enough"
      :recommended-alternative "use a single :llm with an instruction that explicitly tells the model 'draft, then critique, then revise inline' — much cheaper and often comparable quality"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "open-ended (unbounded N iterations until convergence) is the wrong tool here — that's validation-loop's job, not iterative-refinement's"
      :avoid-when "the loop needs to repeat until quality threshold is met OR the work needs explicit retry-on-fail semantics"
      :recommended-alternative "use validation-loop (with a producer + a validator + a retry budget) for unbounded-quality scenarios; iterative-refinement is for fixed-N refinement passes"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["technical writing improvement: draft documentation → critique for clarity/missing-context → revise"
     "creative writing polish: draft scene → critique for pacing/voice → revise"
     "executive summary tightening: draft summary → critique for jargon/specificity → revise"]

    :avoid-when
    ["the output is short and direct — single :llm with a careful instruction wins"
     "the task needs unbounded iteration until convergence — use validation-loop"
     "the work has clear validation rules (formal/syntactic) — use validation-loop with a code-based validator"]

    :summary
    "Iterative refinement is a fixed-N sequence (typically 3 stages: draft → critique → revise) where each stage transforms a single artifact toward higher quality. The critique stage forces explicit articulation of defects before the revision stage addresses them — measurably improves nuanced outputs over single-pass with self-edit. Always pass the original prompt into the revision stage so the rewrite doesn't drift. Avoid for short/concrete outputs (single :llm is enough) or for unbounded-quality scenarios (use validation-loop instead). Distinct from validation-loop: refinement is a fixed-N polish; validation-loop is producer/validator with retry."

    :version 1
    :consolidated-from-event-count 0

    ;; R02: child of sequential-pipeline — iterative-refinement is a
    ;; fixed-stage chain (draft/critique/revise) where each stage's
    ;; output feeds the next stage's reads.
    :parent-tree-id sequential-pipeline-fp}})

(def scheduling-tree-seed
  "Staged constraint-satisfaction tasks: gather constraints → propose
   assignment → check conflicts → emit final. On-call rotations, meeting
   scheduling, and shift assignment all fit this shape."
  {:target-id scheduling-task-class-id
   :body
   {:capabilities
    [":sequence of typically 3-4 stages: enumerate constraints → propose an assignment satisfying them → conflict-check the proposal → emit final or restart"
     "natural for problems with hard constraints (must-have) and soft preferences (prefer-if-possible) that need both satisfied and ranked"
     "outputs typically include the assignment, a list of unsatisfied soft preferences, and (when applicable) a justification trace per assignment slot"]

    :strengths
    [{:trait "explicit separate stages for constraint enumeration vs proposal vs check makes the failure mode (over-constrained / under-constrained) visible — you see which stage produced the dead-end"
      :good-when "the problem has both hard constraints (e.g., person X is on PTO from date Y to Z) AND soft preferences (e.g., prefer to balance load across the team)"
      :recommended-pattern "[:sequence [:llm {:reads [:team :availability :rules] :writes [:hard-constraints :soft-prefs]}] [:llm {:reads [:hard-constraints :soft-prefs :history] :writes [:proposal]}] [:code {:reads [:proposal :hard-constraints] :writes [:conflicts] :code (fn [{:keys [proposal hard-constraints]}] {:conflicts []})}] [:llm {:reads [:proposal :conflicts] :writes [:final-schedule :justification]}] [:final {:keys [:final-schedule :justification]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "use :code (NOT :llm) for the conflict-check stage — deterministic constraint validation is what code is good at; LLMs miss subtle conflicts"
      :good-when "the conflict rules can be expressed as code (date overlap, capacity sums, role coverage)"
      :recommended-pattern "[:code {:reads [:proposal :hard-constraints] :writes [:conflicts]}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "single-pass :llm scheduling silently violates hard constraints on non-trivial inputs (4+ people, 2+ weeks) — the model 'forgets' constraints partway through"
      :avoid-when "the problem has more than ~3 hard constraints AND a single :llm is being asked to satisfy them all in one shot"
      :recommended-alternative "always separate the constraint-check into its own :code stage with explicit pass/fail per constraint — never trust a single :llm to honor multiple hard constraints"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "no built-in retry-on-conflict — when the conflict-check stage flags conflicts, the pipeline emits a partial result and stops; no automatic re-proposal"
      :avoid-when "the problem space is dense (most proposals conflict) AND retry-until-clean is needed"
      :recommended-alternative "wrap in a validation-loop (producer = proposal stage, validator = conflict-check, with a retry budget) when high conflict rates are expected"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["weekly on-call rotation: enumerate team availability + PTO → propose rotation → check coverage → emit schedule"
     "meeting scheduling across timezones: enumerate participant windows → propose slot → check conflicts → emit invitation"
     "shift assignment for retail/healthcare: enumerate roles + employee constraints → propose week → check coverage gaps → emit final"]

    :avoid-when
    ["the problem has only 1-2 hard constraints — single :llm with a careful instruction is enough"
     "the problem requires retry-until-feasible loops — use validation-loop instead"
     "the problem is unconstrained ranking (just sort) — use a single :llm or :code sort"]

    :summary
    "Scheduling tasks fit a staged sequential pipeline: enumerate constraints → propose assignment → conflict-check with code → emit final + justification. The pattern separates LLM-driven proposal (good at creativity) from code-driven constraint validation (good at precision). Always use :code for the conflict-check stage — LLMs silently violate multiple hard constraints in single-pass. For high-conflict problem spaces where retry-until-feasible is needed, wrap in validation-loop instead. Examples: on-call rotation, meeting scheduling across timezones, shift assignment."

    :version 1
    :consolidated-from-event-count 0

    ;; R02: child of sequential-pipeline — scheduling is the
    ;; constraint-satisfaction variant where each stage transforms
    ;; the partial solution toward feasibility.
    :parent-tree-id sequential-pipeline-fp}})

;; =============================================================================
;; R02 — children under validation-loop-fp
;; =============================================================================

(def producer-validator-tree-seed
  "Producer/validator loop: one node generates a candidate, another
   node checks it against rules, retries on fail up to a budget. The
   canonical validation-loop instance where the producer is an LLM
   and the validator is code or a structured LLM check."
  {:target-id producer-validator-task-class-id
   :body
   {:capabilities
    [":sequence with a producer :llm + a validator :code (or :llm with structured :output-schemas) + an explicit retry budget"
     "natural for any 'generate-then-check' work: code generation + type-check, JSON output + schema validation, claim + grounding check"
     "outputs typically include the validated artifact, the validation result (pass/fail with reasons), and the retry count consumed"]

    :strengths
    [{:trait "the validator stage being :code (not :llm) gives the loop a CORRECTNESS oracle — the producer can be creative, the validator is trusted"
      :good-when "the validation rules are formal/syntactic AND can be expressed in code (schema validation, regex match, parse-and-check)"
      :recommended-pattern "[:sequence [:llm {:reads [:prompt] :writes [:candidate] :options {:max-retries 3}}] [:code {:reads [:candidate] :writes [:valid? :issues] :code (fn [{:keys [candidate]}] (let [ok? (string? candidate)] {:valid? ok? :issues (if ok? [] [\"not a string\"])}))}] [:final {:keys [:candidate :valid? :issues]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "always pass the validator's :issues back to the producer on retry so the model can address specific failures, not just guess again"
      :good-when "the validator can articulate WHY a candidate failed (specific missing key, specific malformed value)"
      :recommended-pattern "[:llm {:reads [:prompt :previous-candidate :issues] :writes [:candidate] :options {:max-retries 3}}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "unbounded retry burns LLM tokens when the producer is structurally incapable of satisfying the validator (asked for an impossible constraint)"
      :avoid-when ":max-retries is not set OR is set above ~5 AND the producer is :llm-driven"
      :recommended-alternative "always set :max-retries 3 on the producer; surface 'budget exhausted' as a distinct outcome so the caller can choose to relax constraints or escalate"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "using :llm for the validator (instead of :code) creates correlated failure — the same LLM that produced a flawed candidate may not catch its own flaw"
      :avoid-when "the validation rules can be expressed in code AND the validator is being implemented as another :llm node"
      :recommended-alternative "always prefer :code for the validator. Use :llm validator only when validation requires natural-language judgment (e.g., 'is this politically neutral?')"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["JSON output generation + schema validation: produce JSON → parse + validate against Malli → retry with errors"
     "code generation + compile check: produce Clojure expression → eval in sandbox → retry with stack trace"
     "regex/grammar generation + sample-input parse: produce regex → run on test inputs → retry with mismatch sample"]

    :avoid-when
    ["the output doesn't have formal validation rules — use iterative-refinement instead (fixed-N polish)"
     "the validation requires deep judgment (taste, factuality) — use a tree-level judge or LLM evaluation instead"
     "the retry rate is expected to be near zero — direct :llm with :max-retries on its own retry budget is simpler"]

    :summary
    "Producer/validator is the canonical validation-loop instance where one node generates a candidate and another checks it against formal rules, with an explicit retry budget. The validator should be :code (not :llm) when rules are syntactic — this provides a true correctness oracle uncorrelated with producer failures. Always pass validator :issues back to the producer on retry so the model addresses specific failures. Always cap :max-retries at 3 to bound LLM cost. Avoid for tasks with no formal validation rules (use iterative-refinement) or when validation requires deep judgment (use LLM evaluation/judge instead)."

    :version 1
    :consolidated-from-event-count 0

    ;; R02: child of validation-loop — the canonical producer/validator
    ;; pair with explicit retry budget.
    :parent-tree-id validation-loop-fp}})

(def draft-critique-tree-seed
  "Single-pass draft + grade for content quality. A degenerate
   validation-loop where the 'validator' is a grading LLM and there
   are typically no retries — used for quality auditing rather than
   correctness enforcement."
  {:target-id draft-critique-task-class-id
   :body
   {:capabilities
    [":sequence with a producer :llm + a critic :llm (structured grading output) — no retry budget; the critic's grade is the loop's output"
     "natural for quality auditing: you want both the artifact AND an explicit quality rating to surface to a human reviewer"
     "outputs typically include the draft, a structured grade (score + categories + specific issues), and (optionally) a revision recommendation"]

    :strengths
    [{:trait "explicit grading stage forces the system to articulate quality dimensions BEFORE a human looks at the artifact — the reviewer reads the grade first and uses it to focus their attention"
      :good-when "the artifact will be reviewed by a human AND specific quality categories matter (factual accuracy, tone, completeness)"
      :recommended-pattern "[:sequence [:llm {:reads [:prompt] :writes [:draft]}] [:llm {:reads [:draft :prompt] :writes [:score :issues :recommendation] :output-schemas {:score :int :issues [:vector :string] :recommendation :string}}] [:final {:keys [:draft :score :issues :recommendation]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "structured :output-schemas on the critic forces specific dimensions (score, issue list, recommendation) — prevents the LLM from emitting vague 'looks good' grades"
      :good-when "quality has clear dimensions that can be enumerated upfront (accuracy, completeness, tone)"
      :recommended-pattern "[:llm {:writes [:score :issues :recommendation] :output-schemas {:score [:and :int [:>= 0] [:<= 10]] :issues [:vector :string] :recommendation [:enum :ship :revise :discard]}}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "using the SAME model for draft and critique correlates errors — the model that drafted a flawed argument may grade it as fine"
      :avoid-when "draft and critique use the same model AND the quality dimensions involve subtle judgment"
      :recommended-alternative "use different models for draft vs critique (e.g., draft with gemini, critique with claude) OR add a third independent reviewer model to break ties"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "no retry means the loop ships a flawed draft with a low grade rather than fixing it — fine for auditing, wrong for production output"
      :avoid-when "the loop is in a path that emits to end-users (the artifact is the output, not the grade)"
      :recommended-alternative "use iterative-refinement (draft → critique → revise) when the loop is in the output path, OR add retry-on-low-grade via producer-validator semantics"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["essay grading: draft answer → grade with rubric → surface to instructor"
     "code review automation: PR diff → critique against style/correctness rules → flag for human review"
     "blog post quality check: draft post → grade for accuracy/tone/completeness → recommend ship/revise/discard"]

    :avoid-when
    ["the loop emits to end-users — use iterative-refinement (which includes a revise stage)"
     "the validation can be expressed in code — use producer-validator (cheaper and uncorrelated)"
     "no quality dimensions are pre-specified — use a single :llm with a free-form quality note"]

    :summary
    "Draft + critique is a single-pass quality-auditing variant of validation-loop: producer LLM emits a draft, critic LLM grades it on structured dimensions, no retry. The structured :output-schemas on the critic force specific grading dimensions (score, issue list, recommendation) — prevents vague 'looks good' grades. Always use DIFFERENT models for draft vs critique to break error correlation. Use for quality auditing where a human reads the grade first; use iterative-refinement instead when the artifact itself is the output. Use producer-validator with code-based check when rules are formal."

    :version 1
    :consolidated-from-event-count 0

    ;; R02: child of validation-loop — degenerate variant where the
    ;; validator is an LLM grader rather than a code-based check.
    :parent-tree-id validation-loop-fp}})

;; =============================================================================
;; R02 — children under fallback-recovery-fp
;; =============================================================================

(def primary-backup-tree-seed
  "Try a preferred path first; fall back to a deterministic alternative
   on detected failure. The canonical fallback pattern for resilience
   against transient or stochastic primary-path failures."
  {:target-id primary-backup-task-class-id
   :body
   {:capabilities
    [":fallback composite with a primary :llm/:code child and a backup :code child"
     "natural for any work where the primary path is fast/preferred but unreliable, and a slower/cheaper deterministic alternative exists"
     "outputs typically include the result + a marker indicating which path produced it (primary success / backup used)"]

    :strengths
    [{:trait "the :fallback node returns on the FIRST child that succeeds — primary's :status :success short-circuits the backup; primary's failure transparently activates backup"
      :good-when "the primary path is preferred (cheaper, more accurate, faster) but has a non-trivial failure rate AND a backup exists that's strictly less preferred but reliable"
      :recommended-pattern "[:fallback [:llm {:reads [:input] :writes [:result] :options {:max-retries 2}}] [:code {:reads [:input] :writes [:result] :code (fn [{:keys [input]}] {:result (str \"backup: \" input)})}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "the backup child should be :code (deterministic) — if backup is also an :llm call, you've doubled the failure surface, not reduced it"
      :good-when "a deterministic fallback exists (string template, default value, last-known-good cache lookup)"
      :recommended-pattern "[:fallback [:llm ...] [:code {:reads [:input] :writes [:result]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "stacking 3+ :fallback children silently masks systemic failures — if primary AND backup both fail, the third option papers over a real problem the operator should see"
      :avoid-when ":fallback has more than 2 children"
      :recommended-alternative "exactly 2 children in :fallback (primary + backup); if a third option is needed, fail loudly to the operator instead of cascading further"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "no usage tracking of which child fired silently hides the primary's failure rate from operators — primary degrades quietly while backup serves traffic"
      :avoid-when "no observability is wired to surface fallback activation rate to operators"
      :recommended-alternative "always wire a usage event or write a marker (e.g., :path :primary | :backup) so operators see the activation rate trend"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["LLM extraction with regex fallback: extract entities via :llm → on failure fall back to regex pattern match"
     "structured-output generation with template fallback: produce JSON via :llm → on parse failure fall back to a code-built template with default values"
     "answer generation with cached-answer fallback: produce fresh answer via :llm → on failure return last-known-good from cache"]

    :avoid-when
    ["the backup path is also an LLM — that's not a fallback, it's a model-cascade (use the model-cascade child instead)"
     "both paths must run unconditionally — use :parallel instead"
     "the failure mode requires retry-with-context — use validation-loop instead"]

    :summary
    "Primary-backup is the canonical fallback-recovery instance: try the preferred path first, fall back to a deterministic alternative on detected failure. The backup MUST be :code (deterministic) to avoid doubling the failure surface. Always exactly 2 children — stacking 3+ silently masks systemic issues. Always emit a marker indicating which path produced the result so operators see the activation rate trend. Use for transient/stochastic primary failures with a known-good fallback. Use model-cascade when the backup is also an LLM (cheap → expensive escalation)."

    :version 1
    :consolidated-from-event-count 0

    ;; R02: child of fallback-recovery — canonical primary + deterministic
    ;; backup pair.
    :parent-tree-id fallback-recovery-fp}})

(def model-cascade-tree-seed
  "Try cheap model first; escalate to expensive model on low confidence
   or detected failure. A variant of primary-backup where both paths
   are :llm — usually the cheap path handles 80%+ of traffic and the
   expensive path only fires on hard cases."
  {:target-id model-cascade-task-class-id
   :body
   {:capabilities
    [":fallback composite with a cheap :llm child and an expensive :llm child — both use the same prompt but different model tier"
     "natural for any work where most inputs are easy (cheap model is enough) but a subset is hard (expensive model is needed)"
     "outputs typically include the result + which model tier produced it (cheap / expensive)"]

    :strengths
    [{:trait "the cheap path handles the common case at fractional cost — typical economics are 10-20x cheaper per call than always-expensive"
      :good-when "the work has a long-tail difficulty distribution AND the expensive model is materially more capable on hard cases"
      :recommended-pattern "[:fallback [:llm {:reads [:input] :writes [:result] :options {:model :gemini-flash :max-retries 1}}] [:llm {:reads [:input] :writes [:result] :options {:model :claude-opus :max-retries 2}}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "wire a confidence-check (validator) between the two LLM tiers to escalate on LOW confidence, not just on outright failure — the cheap model's confident-but-wrong answers are the main risk"
      :good-when "the cheap model has a measurable tendency to be confidently wrong on hard cases"
      :recommended-pattern "[:sequence [:llm {:options {:model :gemini-flash} :writes [:result :confidence]}] [:fallback [:condition {:predicate (fn [{:keys [confidence]}] (>= confidence 0.8))}] [:llm {:options {:model :claude-opus} :writes [:result]}]]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "without a confidence-check between tiers, the cheap model's confident-but-wrong outputs ship without ever escalating — :fallback only catches outright failure (:status :failure), not bad answers"
      :avoid-when "the cheap model's outputs are graded only by structural validation (parses OK / matches schema) without semantic confidence"
      :recommended-alternative "always require a :confidence field on the cheap model's output schema AND escalate to expensive model on confidence < threshold (e.g., 0.7)"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "cascade with both tiers using the SAME provider correlates failures — when the provider has an outage, both tiers fail together"
      :avoid-when "both tiers use the same upstream provider AND availability is a concern"
      :recommended-alternative "use different providers for the two tiers (e.g., gemini for cheap, claude for expensive) so a provider outage degrades one tier but not both"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["question-answering at scale: gemini-flash answers 90% of queries → escalate to claude-opus when confidence < 0.7"
     "code synthesis with cost ceiling: small model attempts simple changes → escalate to large model for complex refactors"
     "extraction from messy documents: cheap model handles clean inputs → expensive model handles low-OCR-confidence inputs"]

    :avoid-when
    ["the backup path is deterministic (regex, template, cache) — use primary-backup instead"
     "the cheap model is roughly capable of the full distribution — single cheap model with :max-retries is simpler"
     "you can't measure confidence on cheap-model output — escalation won't fire correctly"]

    :summary
    "Model cascade is fallback-recovery where both children are :llm but at different tiers (cheap → expensive). The cheap path handles 80%+ of traffic at fractional cost; the expensive path handles hard cases. Critical refinement: insert a confidence-check between tiers — the cheap model's confidently-wrong outputs are the main risk and :fallback alone only catches outright failure. Use DIFFERENT providers for the two tiers to avoid correlated outages. Use primary-backup when the backup is deterministic (regex/template/cache); use model-cascade specifically for LLM-to-LLM escalation with cost economics."

    :version 1
    :consolidated-from-event-count 0

    ;; R02: child of fallback-recovery — LLM-to-LLM escalation variant
    ;; where both children are LLM calls at different tiers.
    :parent-tree-id fallback-recovery-fp}})

;; =============================================================================
;; R02 — children under map-reduce-fp
;; =============================================================================

(def parallel-sum-tree-seed
  "Deterministic per-chunk computation + arithmetic reduce. The canonical
   pure-code MapReduce variant where both map and reduce stages are :code
   (no :llm) — used for counting, summing, averaging across chunks."
  {:target-id parallel-sum-task-class-id
   :body
   {:capabilities
    [":sequence of :chunk-document → :map-each with :code per-chunk → :code reducer that arithmetically aggregates"
     "natural for any 'count/sum/average across chunks' work where the per-chunk computation is deterministic"
     "outputs are typically a single number or a small fixed-shape numeric summary"]

    :strengths
    [{:trait "pure-code map + reduce eliminates LLM cost AND determinism — the same input always produces the same output, byte-for-byte"
      :good-when "the per-chunk work is purely arithmetic (count tokens, sum field values, average a metric) AND the reduce is associative (sum, max, min)"
      :recommended-pattern "[:sequence [:chunk-document {:from :document :size 1000 :into :chunks}] [:map-each {:from :chunks :as :chunk :into :counts :max-concurrency 8} [:code {:reads [:chunk] :writes [:count] :code (fn [{:keys [chunk]}] {:count (count (re-seq #\"\\w+\" chunk))})}]] [:code {:reads [:counts] :writes [:total] :code (fn [{:keys [counts]}] {:total (reduce + 0 (map :count counts))})}] [:final {:keys [:total]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "lift :max-concurrency on :map-each to 8 or higher because :code calls have no rate-limit risk — unlike :llm chunks where 3 is the safe ceiling"
      :good-when "every map step is :code (not :llm) AND the per-chunk compute is CPU-light"
      :recommended-pattern "[:map-each {:max-concurrency 8 :from :chunks :as :chunk :into :results}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "using :llm for the per-chunk map step when the work is arithmetic burns tokens and introduces non-determinism for no quality gain"
      :avoid-when "the per-chunk computation is purely numeric (counting, summing) AND :llm is being used 'because it's easier to write'"
      :recommended-alternative "always use :code for the per-chunk step when the computation is arithmetic; reserve :llm for per-chunk steps requiring natural-language judgment"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "non-associative reducers (median, mode, percentile) silently produce wrong results when applied to per-chunk partial results — they only work on the full input set"
      :avoid-when "the reduce operation requires sorting the full set OR computing a percentile/mode/median across all values"
      :recommended-alternative "for non-associative reducers, emit ALL per-chunk values to the reducer and compute the final statistic on the merged set; never reduce partial percentiles"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["word count across a large document"
     "sum of dollar amounts across a chunked invoice batch"
     "average of a numeric metric across log file chunks"]

    :avoid-when
    ["the per-chunk work requires LLM judgment — use parallel-classify-aggregate instead"
     "the reduce is non-associative (median, mode) — flatten and compute on the full set"
     "the input fits in memory and isn't worth chunking — just compute directly"]

    :summary
    "Parallel-sum is the pure-code MapReduce variant: chunk → :code per-chunk computation → :code arithmetic reduce. Both stages are deterministic — same input produces byte-identical output. Use higher :max-concurrency (8+) on :map-each since :code calls have no rate-limit risk. NEVER use :llm for arithmetic per-chunk work. NEVER apply non-associative reducers (median, percentile) to partial per-chunk results — they require the full set. Use parallel-classify-aggregate when the per-chunk work requires LLM judgment."

    :version 1
    :consolidated-from-event-count 0

    ;; R02: child of map-reduce — pure-code arithmetic variant.
    :parent-tree-id map-reduce-fp}})

(def parallel-classify-aggregate-tree-seed
  "Per-chunk classification (LLM judgment) + count rollup. A hybrid
   MapReduce where the map step is :llm (classification) but the
   reduce step is :code (counting/grouping)."
  {:target-id parallel-classify-aggregate-task-class-id
   :body
   {:capabilities
    [":sequence of :chunk-document → :map-each with :llm classifier per-chunk → :code grouping reducer"
     "natural for any 'classify each chunk into a category and count by category' work — sentiment distribution, error-type histogram, topic mix"
     "outputs are typically a per-category count map plus optionally per-category exemplars"]

    :strengths
    [{:trait "splitting LLM classification (map) from code aggregation (reduce) keeps the LLM scope narrow per chunk — each per-chunk call only needs to emit a single category label, not reason about the full document"
      :good-when "each chunk's classification is independent AND the final analysis is a category distribution"
      :recommended-pattern "[:sequence [:chunk-document {:from :document :size 1500 :into :chunks}] [:map-each {:from :chunks :as :chunk :into :classifications :max-concurrency 3} [:llm {:reads [:chunk] :writes [:category] :output-schemas {:category [:enum :positive :negative :neutral]}}]] [:code {:reads [:classifications] :writes [:distribution] :code (fn [{:keys [classifications]}] {:distribution (frequencies (map :category classifications))})}] [:final {:keys [:distribution]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "always declare :output-schemas with an :enum on the per-chunk :llm classifier — free-form string labels drift across chunks (\"positive\" vs \"Positive\" vs \"pos\") and break the code reducer's frequencies"
      :good-when "the category set is small and pre-specified"
      :recommended-pattern "[:llm {:writes [:category] :output-schemas {:category [:enum :positive :negative :neutral]}}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "unbounded :max-concurrency on the per-chunk :llm classifier hits provider rate limits on documents with 8+ chunks — same as document-analysis"
      :avoid-when ":max-concurrency is unset on :map-each AND the chunk count exceeds 8"
      :recommended-alternative "always bound :max-concurrency to 3 on :map-each parents of per-chunk :llm calls — keeps within provider rate budgets even on long documents"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "code reducer that assumes :category will be present silently produces wrong counts when the LLM returns nil/missing labels (schema validation skipped or per-chunk failure)"
      :avoid-when "the code reducer assumes per-chunk schema-validated output without checking for missing labels"
      :recommended-alternative "the reducer should explicitly handle missing classifications (e.g., add :unknown to the enum AND map nil → :unknown before frequencies) OR fail loudly with a per-chunk diagnostic"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["sentiment distribution across a long product-review document: per-paragraph sentiment → category counts"
     "error-type histogram across a chunked log file: per-chunk error classification → type counts"
     "topic mix in a research paper: per-section topic classification → topic distribution"]

    :avoid-when
    ["the per-chunk work is arithmetic (no LLM judgment needed) — use parallel-sum instead"
     "the work needs cross-chunk reasoning (trend detection, narrative synthesis) — use chunked-extraction or research-then-synthesize instead"
     "the category set is open-ended — single :llm on the full document is simpler"]

    :summary
    "Parallel-classify-aggregate is the hybrid MapReduce variant: chunk → :llm classifier per-chunk → :code reducer for counts. The :llm scope stays narrow (single category label per chunk) while the :code reducer handles deterministic counting. Always declare :output-schemas with an :enum on the classifier to prevent label drift. Always bound :map-each :max-concurrency to 3 (LLM rate limits). The code reducer must explicitly handle nil/missing labels. Use parallel-sum when the per-chunk work is arithmetic; use chunked-extraction when cross-chunk reasoning is needed."

    :version 1
    :consolidated-from-event-count 0

    ;; R02: child of map-reduce — hybrid LLM-map + code-reduce variant.
    :parent-tree-id map-reduce-fp}})

;; =============================================================================
;; R02 — children under research-then-synth-fp
;; =============================================================================

(def briefing-generation-tree-seed
  "Chunked research + final cohesive narrative. The canonical
   ResearchThenSynthesize variant where per-chunk extraction is
   followed by an LLM that integrates the material into a single
   readable briefing document."
  {:target-id briefing-generation-task-class-id
   :body
   {:capabilities
    [":sequence of :chunk-document → :map-each :llm (per-chunk extraction) → :aggregate (merged material) → :llm synthesizer (cohesive narrative)"
     "natural for any 'read X long material and produce a Y-paragraph briefing' work — executive briefings, research summaries, situation reports"
     "outputs are typically a single coherent text artifact organized by topic/section, not a structured key-value extraction"]

    :strengths
    [{:trait "the synthesis stage's :llm has access to ALL per-chunk extracted material in :reads — produces a narrative that reasons across chunks rather than concatenating per-chunk summaries"
      :good-when "the briefing needs to identify cross-chunk patterns (recurring themes, contradictions, progression of an argument)"
      :recommended-pattern "[:sequence [:chunk-document {:from :source :size 4000 :into :chunks}] [:map-each {:from :chunks :as :chunk :into :extracts :max-concurrency 3} [:llm {:reads [:chunk] :writes [:key-points :quotes]}]] [:aggregate {:from :extracts :writes [:all-points :all-quotes]}] [:llm {:reads [:all-points :all-quotes :briefing-prompt] :writes [:briefing]}] [:final {:keys [:briefing]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "extract specific QUOTES alongside summaries in the per-chunk step — the synthesis stage can then ground claims to source quotes, supporting traceability"
      :good-when "the briefing audience may verify claims against source material AND traceability matters"
      :recommended-pattern "[:llm {:reads [:chunk] :writes [:key-points :supporting-quotes] :output-schemas {:key-points [:vector :string] :supporting-quotes [:vector :string]}}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "the synthesis stage's context can overflow when per-chunk extracts are too rich AND the chunk count is high — synthesizer truncates or hallucinates connections"
      :avoid-when "per-chunk extraction emits >500 tokens per chunk AND chunk count is >15"
      :recommended-alternative "either reduce per-chunk extraction granularity (key-points-only, no quotes) OR add an intermediate :code stage that selects top-K per-chunk extracts before synthesis"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "without a grounding judge, the synthesis stage can fabricate connections between per-chunk extracts that aren't actually in the source — a 'plausible narrative' that doesn't survive verification"
      :avoid-when "the briefing is going to a decision-maker AND no grounding check is wired downstream"
      :recommended-alternative "wire a grounding judge on the briefing output that checks each claim against the source quotes — catches fabricated connections before the briefing ships"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["executive briefing from a long research report: chunked extraction → final cohesive 3-paragraph briefing"
     "situation-report generation from a stream of incident logs: per-incident extraction → final narrative summary"
     "literature review summary: chunked paper extraction → final synthesized review section"]

    :avoid-when
    ["the output is a structured triple (summary + entities + dates) — use document-analysis (chunked-extraction-as-task-class) instead"
     "no cross-chunk reasoning needed — use chunked-extraction (terminal :aggregate without synthesis)"
     "the source fits in a single :llm context — direct :llm without chunking is simpler"]

    :summary
    "Briefing generation is the canonical ResearchThenSynthesize instance: chunk → per-chunk :llm extraction → :aggregate → final synthesis :llm that produces a cohesive narrative. The synthesis stage's access to ALL per-chunk material enables cross-chunk reasoning (themes, contradictions). Always extract supporting quotes alongside summaries to support traceability. Watch context overflow — reduce per-chunk granularity if chunk count is high. Wire a grounding judge on the output when the briefing goes to a decision-maker. Use document-analysis (chunked-extraction) for structured triples; use chunked-extraction for terminal aggregation without synthesis."

    :version 1
    :consolidated-from-event-count 0

    ;; R02: child of research-then-synthesize — canonical chunked-research
    ;; + final-narrative variant.
    :parent-tree-id research-then-synth-fp}})

(def comparative-summary-tree-seed
  "Extract from multiple sources + synthesize into a single comparison.
   A research-then-synthesize variant where the 'research' is parallel
   extraction across N sources (each independent) and the synthesis is
   a structured side-by-side comparison."
  {:target-id comparative-summary-task-class-id
   :body
   {:capabilities
    [":sequence of :parallel (or :map-each over sources) per-source :llm extraction → :code aggregator → :llm synthesizer that produces a structured comparison"
     "natural for any 'read N versions/sources and produce a comparison' work — product comparison, contract diff, multi-source fact verification"
     "outputs are typically a structured comparison object (per-source key, common-elements, differences, recommendation)"]

    :strengths
    [{:trait "the synthesis stage's :reads include ALL per-source extractions structured as a per-source map — supports apples-to-apples comparison by source rather than free-form prose"
      :good-when "the sources are roughly comparable AND a structured comparison is the value (vs free-form narrative)"
      :recommended-pattern "[:sequence [:parallel [:llm {:reads [:source-a] :writes [:extract-a]}] [:llm {:reads [:source-b] :writes [:extract-b]}]] [:code {:reads [:extract-a :extract-b] :writes [:by-source] :code (fn [{:keys [extract-a extract-b]}] {:by-source {:a extract-a :b extract-b}})}] [:llm {:reads [:by-source] :writes [:common :differences :recommendation]}] [:final {:keys [:common :differences :recommendation]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "use :parallel (NOT :sequence) for the per-source extraction stages when sources are independent — extraction wall-time becomes max(per-source) not sum(per-source)"
      :good-when "sources are independent (no need to read source B before extracting source A)"
      :recommended-pattern "[:parallel [:llm {:reads [:source-a] :writes [:extract-a]}] [:llm {:reads [:source-b] :writes [:extract-b]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "free-form per-source extraction (different :output-schemas per source or open-ended free text) makes the synthesizer's apples-to-apples comparison hard — comparable fields drift across sources"
      :avoid-when "per-source extractions use different schemas OR free-form text"
      :recommended-alternative "always use IDENTICAL :output-schemas across per-source extraction stages — comparable fields stay aligned, synthesizer can do direct per-field diff"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "synthesizing more than ~5 sources in a single :llm call exceeds context — the comparison loses fidelity on per-source detail"
      :avoid-when "source count >5 AND each source's extraction is non-trivial"
      :recommended-alternative "for >5 sources, hierarchical comparison: cluster sources into 2-3 groups → comparative-summary within each group → final cross-group comparison"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["product-feature comparison across 3-4 vendor docs: per-vendor extraction → side-by-side feature matrix"
     "contract diff across 2 versions: per-version extraction → key-differences + similarities + recommendation"
     "multi-source fact verification: per-source claim extraction → common claims + contradictions + confidence"]

    :avoid-when
    ["only one source — use chunked-extraction or document-analysis instead"
     "the comparison should be a free-form narrative — use briefing-generation instead"
     "the sources are sequentially dependent — use sequential-pipeline instead"]

    :summary
    "Comparative summary is the multi-source research-then-synthesize variant: parallel per-source extraction → :code aggregator structures extractions per source → final :llm synthesizes a structured comparison (common / differences / recommendation). Always use :parallel (not :sequence) for independent per-source stages — wall-time becomes max(per-source). CRITICAL: use IDENTICAL :output-schemas across per-source stages so comparable fields stay aligned. Limit to ~5 sources per synthesis; for more, do hierarchical comparison. Use contract-comparison (parallel-independent task-class) when the structure is exactly diff-two-versions; comparative-summary is for general N-source comparison."

    :version 1
    :consolidated-from-event-count 0

    ;; R02: child of research-then-synthesize — multi-source structured
    ;; comparison variant.
    :parent-tree-id research-then-synth-fp}})

;; =============================================================================
;; R05a — 11 hand-authored top-level behavioral-subtree seeds (C-2e foundation)
;; =============================================================================
;;
;; These are NOT structural patterns — they describe REUSABLE COMPETENCIES
;; (research / extraction / analysis / etc.) that compose INTO structural
;; shells (Sequential / Parallel / etc.) via the behavior:composes-into
;; edges declared in each seed's :composes-into field.
;;
;; Each seed:
;;   :scope            = :behavioral-subtree (routes to R05a's processor)
;;   :parent-behavior  = nil at bootstrap (top-level competencies)
;;   :composes-into    = vector of tree-class IDs (UUIDs for R02 children,
;;                       strings for abstract structural patterns) the
;;                       behavior commonly composes into
;;
;; The corpus is FEW-SHOT EXAMPLE MATERIAL for the recursive RLM researcher,
;; NOT a routing taxonomy. Composition rules are RETRIEVAL HINTS, not
;; enforcement rules — the model is free to compose any behavior into any
;; shell.
;; =============================================================================

(def research-behavior-id
  (stable-uuid-from "seed:behavior:research"))
(def extraction-behavior-id
  (stable-uuid-from "seed:behavior:extraction"))
(def analysis-behavior-id
  (stable-uuid-from "seed:behavior:analysis"))
(def synthesis-behavior-id
  (stable-uuid-from "seed:behavior:synthesis"))
(def ideation-behavior-id
  (stable-uuid-from "seed:behavior:ideation"))
(def design-behavior-id
  (stable-uuid-from "seed:behavior:design"))
(def critique-behavior-id
  (stable-uuid-from "seed:behavior:critique"))
(def validation-behavior-id
  (stable-uuid-from "seed:behavior:validation"))
(def code-building-behavior-id
  (stable-uuid-from "seed:behavior:code-building"))
(def transformation-behavior-id
  (stable-uuid-from "seed:behavior:transformation"))
(def classification-behavior-id
  (stable-uuid-from "seed:behavior:classification"))

(def research-behavior-seed
  "Gathering raw material from external or contextual sources — searching,
   looking up, retrieving documents — to seed downstream work."
  {:target-id research-behavior-id
   :body
   {:capabilities
    ["formulate one or more search/retrieval queries from the task goal, fan out queries, collect raw source material into a named :writes key"
     "natural fit at the start of a tree when the task needs evidence the model doesn't already have in context"
     "outputs are typically a vector of source snippets or a map of source-id → raw content for downstream extraction/synthesis"]

    :strengths
    [{:trait "fan out independent queries with :parallel (or :map-each over queries) so wall-time stays bounded by the slowest single query, not the sum"
      :good-when "queries are independent (no need to read result A before issuing query B) AND each query is cheap relative to the task budget"
      :recommended-pattern "[:parallel [:llm {:reads [:goal] :writes [:result-a]}] [:llm {:reads [:goal] :writes [:result-b]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "declare the source-type and shape in the :reads of downstream nodes so synthesis/analysis can branch on source provenance without re-inferring it from raw text"
      :good-when "downstream stages need to attribute findings to specific sources OR weight sources by trust"
      :recommended-pattern "[:llm {:reads [:goal] :writes [:sources] :output-schemas {:sources [:vector [:map [:source-id :string] [:trust :double] [:content :string]]]}}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "issuing one giant 'find everything relevant' query forces the model to compress in retrieval AND lose source attribution — downstream stages can't backtrack"
      :avoid-when "the task needs traceable evidence per finding"
      :recommended-alternative "decompose the goal into 2-4 narrower queries (each scoped to a sub-aspect), fan them out in :parallel, preserve source-id per snippet for downstream attribution"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "research without a fallback path silently fails open when external sources timeout or refuse — downstream stages see :sources nil and silently produce vacuous output"
      :avoid-when "external retrieval is on the critical path AND the rest of the tree can't degrade gracefully"
      :recommended-alternative "wrap the primary retrieval in :fallback with a secondary source OR a 'use only model's prior knowledge with explicit low-confidence flag' branch"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["pre-analysis fact gathering: 'find recent rulings on X' → top-N case snippets with citations"
     "pre-synthesis briefing: 'collect what's known about company Y' → multi-source dossier"
     "pre-classification grounding: 'lookup definitions of these terms' → controlled-vocabulary anchors"]

    :avoid-when
    ["all needed material is already in :reads — skip research; go directly to extraction or analysis"
     "the task is purely computational/structural with no external grounding need"
     "retrieval would happen via a deterministic tool call (search index, DB lookup) — that's a :code node, not a behavioral research subtree"]

    :summary
    "Research gathers raw external/contextual material to seed downstream behaviors. Best implemented as :parallel fan-out over 2-4 narrower queries (preserving source-id for downstream attribution) rather than one giant retrieve-everything query. ALWAYS provide a fallback path when retrieval is on the critical path — silent failure of external sources produces vacuous downstream output. Naturally composes into research-then-synthesize shells (sequential research → synthesis), briefing-generation (with structured per-source output), and fallback-recovery shells (primary source + backup). Avoid when all material is already in :reads or the task is purely structural."

    :version 1
    :consolidated-from-event-count 0

    :scope :behavioral-subtree
    :composes-into [briefing-generation-task-class-id
                    research-then-synth-fp
                    fallback-recovery-fp]}})

(def extraction-behavior-seed
  "Pulling structured items (entities, dates, claims, fields) from raw
   material. The behavior that turns text into typed data."
  {:target-id extraction-behavior-id
   :body
   {:capabilities
    ["given raw text/documents, identify and emit the named items required by the downstream task as a typed vector or per-item map"
     "natural fit anywhere a tree needs to go from prose to structured fields before reasoning over them"
     "outputs are typically a vector of extraction records with consistent :output-schemas"]

    :strengths
    [{:trait "declare :output-schemas on the extraction :llm so the structure is enforced at parse time — downstream stages can :read with confidence in the shape"
      :good-when "downstream reasoning depends on specific fields (dates, names, amounts) being present and well-typed"
      :recommended-pattern "[:llm {:reads [:document] :writes [:extracts] :output-schemas {:extracts [:vector [:map [:type :string] [:value :string] [:source-span :string]]]}}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "for documents large enough to risk context truncation, use :map-each over chunks with bounded :max-concurrency so per-chunk extraction parallelizes safely under sub-LLM rate limits"
      :good-when "input is a single large document AND extraction is per-chunk independent"
      :recommended-pattern "[:map-each {:items :chunks :as :chunk :max-concurrency 3 :writes [:per-chunk-extracts]} [:llm {:reads [:chunk] :writes [:per-chunk-extracts]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "extraction prompts that ask 'pull out the important parts' without a typed schema produce inconsistent shape across runs — downstream :reads fail silently or get garbage"
      :avoid-when "downstream stages bind specific field names from the extraction output"
      :recommended-alternative "explicit :output-schemas with named fields + a concrete example in the prompt; treat extraction shape as a contract"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "single-pass extraction over a multi-page document misses items in the middle when token budget pressures the model to summarize instead of extract"
      :avoid-when "input is >~3 pages AND extraction needs to be high-recall"
      :recommended-alternative "chunk the document, run :map-each per-chunk extraction with consistent :output-schemas, aggregate after — preserves per-span recall"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["entity extraction from contracts: party names, dates, amounts → typed vector with source spans"
     "claim extraction from articles: claim text + speaker + evidence-class"
     "field extraction from forms: per-field typed value with confidence per field"]

    :avoid-when
    ["the raw material is already structured (JSON, DB rows) — use :code node, not :llm extraction"
     "the task wants reasoning over the items, not extraction of them — that's analysis behavior, not extraction"
     "items are uniform and a regex/parser would do — extraction belongs in :code"]

    :summary
    "Extraction turns raw text into typed structured items. The proven shape uses :llm with explicit :output-schemas as a shape contract, and :map-each (bounded :max-concurrency) over chunks when the document is large enough to risk token-pressure recall loss. NEVER ship extraction without a typed schema — downstream stages bind on field names and silent shape drift produces vacuous output. Composes naturally into etl-pipeline (sequential extract → enrich → emit shells), chunked-extraction (per-chunk parallel extraction), and map-reduce (parallel per-document extraction + aggregate). Avoid when raw material is already structured or when the task wants reasoning over items rather than extraction of them."

    :version 1
    :consolidated-from-event-count 0

    :scope :behavioral-subtree
    :composes-into [etl-pipeline-task-class-id
                    chunked-extraction-fp
                    map-reduce-fp]}})

(def analysis-behavior-seed
  "Reasoning over already-extracted items to identify patterns, problems,
   opportunities, or relationships. The behavior that turns data into
   findings."
  {:target-id analysis-behavior-id
   :body
   {:capabilities
    ["given a vector/map of pre-extracted items, produce a structured set of findings (patterns / problems / opportunities) with per-finding evidence"
     "natural fit as a mid-tree stage between extraction (upstream) and synthesis/critique/code-building (downstream)"
     "outputs are typically a vector of findings, each with :type / :evidence / :confidence / :affected-items"]

    :strengths
    [{:trait "tie each finding to the specific extracted item(s) that triggered it via an :affected-items reference field — downstream stages can audit, drill down, or prioritize findings by evidence weight"
      :good-when "downstream stages need to attribute findings to evidence OR rank findings by support"
      :recommended-pattern "[:llm {:reads [:extracts] :writes [:findings] :output-schemas {:findings [:vector [:map [:type :string] [:evidence :string] [:confidence :double] [:affected-items [:vector :string]]]]}}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "for multi-dimensional analysis (e.g., risk / opportunity / blocker across the same items), use :parallel one-:llm-per-dimension stages — each stage has a tight focus and can produce richer per-dimension reasoning"
      :good-when "the analysis has 2-4 orthogonal dimensions that benefit from separate prompts"
      :recommended-pattern "[:parallel [:llm {:reads [:extracts] :writes [:risks]}] [:llm {:reads [:extracts] :writes [:opportunities]}] [:llm {:reads [:extracts] :writes [:blockers]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "analyzing raw documents (skipping extraction) forces the analysis :llm to do BOTH extraction and reasoning — quality degrades on both since the model can't optimize attention for either"
      :avoid-when "the input is raw documents AND the analysis needs to attribute findings to specific items"
      :recommended-alternative "two-stage: extract structured items first, then analyze over the extracts; preserves analyzability of findings"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "single-pass analysis over a long flat list of items loses signal on per-item nuance — findings cluster around the most prominent items"
      :avoid-when "item count is high AND each item deserves per-item attention"
      :recommended-alternative "use :map-each per-item analysis with bounded :max-concurrency, then aggregate findings — preserves per-item granularity"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["risk analysis over extracted contract clauses: per-clause risk score + reasoning + mitigation"
     "pattern detection across log events: clusters of related events + frequency + suspected cause"
     "gap analysis over a feature list: required-vs-present + missing-items + priority"]

    :avoid-when
    ["the task is just summarizing — that's synthesis behavior, not analysis (analysis produces findings; synthesis produces narrative)"
     "the task is generating new options — that's ideation, not analysis"
     "the items haven't been extracted yet — extraction must run first"]

    :summary
    "Analysis reasons over already-extracted items to produce structured findings (patterns / problems / opportunities) with per-finding evidence. Always tie findings to specific items via :affected-items references for downstream auditability. For multi-dimensional analysis, fan out per-dimension :llm stages via :parallel — each stage gets a tighter focus. NEVER skip the extraction stage: analyzing raw documents conflates extraction and reasoning, degrading both. Composes into comparative-summary (multi-source structured findings), chunked-extraction (per-chunk parallel analysis), and sequential-pipeline (extract → analyze → synthesize). Avoid when the task is summarizing (use synthesis) or generating options (use ideation)."

    :version 1
    :consolidated-from-event-count 0

    :scope :behavioral-subtree
    :composes-into [comparative-summary-task-class-id
                    chunked-extraction-fp
                    sequential-pipeline-fp]}})

(def synthesis-behavior-seed
  "Integrating findings, extractions, or other inputs into a single
   cohesive narrative, document, or recommendation. The behavior that
   collapses many signals into one output."
  {:target-id synthesis-behavior-id
   :body
   {:capabilities
    ["given multiple structured inputs (findings, extracts, source quotes), produce a single integrated artifact — narrative, report, recommendation"
     "natural fit at the tail of a tree where multiple parallel branches converge"
     "outputs are typically a single string (or small structured map) where the structure of the inputs has been collapsed into coherent prose"]

    :strengths
    [{:trait "make the synthesizer's :reads contain the per-source extractions structured by source-id rather than a free-form concat — supports apples-to-apples reference and avoids the model losing track of which finding came from where"
      :good-when "synthesis needs to attribute claims to sources OR balance per-source weight"
      :recommended-pattern "[:llm {:reads [:by-source] :writes [:narrative] :output-schemas {:narrative :string}}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "for long final artifacts, scaffold the synthesis with a :code node that pre-builds a structured outline from the inputs, then have the :llm fill each section — preserves coverage of all inputs"
      :good-when "the final artifact is multi-section AND each section maps to a distinct cluster of inputs"
      :recommended-pattern "[:sequence [:code {:reads [:findings] :writes [:outline] :fn (fn [{:keys [inputs]}] {:outline (mapv :type (:findings inputs))})}] [:llm {:reads [:findings :outline] :writes [:narrative]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "synthesizing >5 distinct inputs in a single :llm call exceeds context — the model drops inputs from the middle of the context window silently"
      :avoid-when "input count >5 AND each input is substantive"
      :recommended-alternative "hierarchical synthesis: cluster inputs into 2-3 groups → per-group :llm synthesis → final :llm integrates the group narratives"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "free-form synthesis output (no :output-schemas) makes downstream stages (critique / validation) work on unstructured prose — they can't easily check coverage or shape"
      :avoid-when "downstream stages need to verify specific sections are present"
      :recommended-alternative "declare a structured :output-schemas on the synthesis (e.g., :map with :summary :recommendations :open-questions) — downstream critique/validation can verify each named field"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["multi-source briefing: extracts from N articles → single executive summary with citations"
     "comparative report: per-vendor extractions → structured comparison + recommendation"
     "investigation conclusion: per-witness extractions → integrated timeline + assessment"]

    :avoid-when
    ["the task is generating new options — that's ideation, not synthesis"
     "the task is checking against criteria — that's critique or validation"
     "there's only one input — no synthesis needed; just transform or format"]

    :summary
    "Synthesis integrates multiple structured inputs into a single cohesive artifact (narrative / report / recommendation). Always provide the synthesizer with inputs structured by source-id (not free-form concat) so attribution stays intact. For long artifacts, scaffold via a :code-built outline that the :llm fills section-by-section — preserves coverage. NEVER synthesize >5 substantive inputs in one call: do hierarchical clustering instead. Declare :output-schemas on the synthesis output when downstream stages (critique / validation) need to verify specific sections. Composes into briefing-generation, comparative-summary, and sequential-pipeline shells. Avoid when the task is ideation (new options) or critique (checking against criteria)."

    :version 1
    :consolidated-from-event-count 0

    :scope :behavioral-subtree
    :composes-into [briefing-generation-task-class-id
                    comparative-summary-task-class-id
                    sequential-pipeline-fp]}})

(def ideation-behavior-seed
  "Generating divergent options, alternatives, or candidates. The behavior
   that produces many possibilities for a downstream selector or
   convergent stage."
  {:target-id ideation-behavior-id
   :body
   {:capabilities
    ["given a goal or constraint set, generate N distinct candidate options/approaches/designs"
      "natural fit at the head of a tree when the work needs exploration before convergence"
     "outputs are typically a vector of N candidate entries, each with rationale + risk + expected-outcome"]

    :strengths
    [{:trait "fan out per-perspective :llm calls in :parallel — different prompt framings (cost-optimized / robustness-optimized / contrarian) produce genuinely distinct candidates rather than minor variations of one anchor"
      :good-when "the task benefits from genuine divergence rather than N close-together suggestions"
      :recommended-pattern "[:parallel [:llm {:reads [:goal] :writes [:cost-opt-options]}] [:llm {:reads [:goal] :writes [:robust-options]}] [:llm {:reads [:goal] :writes [:contrarian-options]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "require each candidate to declare the explicit tradeoff it makes (lower X for higher Y) — the per-candidate tradeoff field is what makes the downstream selection stage actionable"
      :good-when "downstream stage will pick one of the candidates"
      :recommended-pattern "[:llm {:reads [:goal] :writes [:options] :output-schemas {:options [:vector [:map [:option :string] [:rationale :string] [:tradeoff :string] [:risk :string]]]}}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "asking a single :llm for 'N options' produces variations on the model's first guess — the options cluster around one local optimum and miss genuinely different approaches"
      :avoid-when "the value depends on having genuinely different approaches"
      :recommended-alternative "fan out :parallel per-perspective stages with distinct prompts; merge afterward; the parallelism enforces divergence"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "ideation without a downstream convergence stage produces options that never get selected — the tree generates value the next stage doesn't use"
      :avoid-when "no downstream stage exists to pick / score / synthesize the candidates"
      :recommended-alternative "always pair ideation with a downstream convergence (selection :llm / critique-and-pick / synthesis); ideation alone is incomplete"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["product-feature brainstorming: 5 candidate features with rationale + tradeoff + risk"
     "approach exploration for a hard problem: 3 architectural sketches with cost-vs-robustness tradeoff"
     "creative variation generation: N candidate names/taglines/copy variants"]

    :avoid-when
    ["the task has one obvious right answer — ideation just wastes tokens"
     "downstream consumers can't pick from candidates — that's wasted divergence"
     "the task is mechanical transformation — that's transformation, not ideation"]

    :summary
    "Ideation generates divergent candidate options/alternatives. The proven shape uses :parallel per-perspective :llm stages with distinct prompt framings (cost-optimized / robustness-optimized / contrarian) — parallelism enforces genuine divergence rather than variations on one anchor. ALWAYS require per-candidate tradeoff declarations so the downstream selection stage is actionable. NEVER ship ideation without a downstream convergence stage (selection / critique-and-pick / synthesis) — orphan candidates produce no value. Composes into parallel-independent shells (parallel candidate generation) and sequential-pipeline shells (ideation → critique → select). Avoid for tasks with one obvious answer or no downstream selector."

    :version 1
    :consolidated-from-event-count 0

    :scope :behavioral-subtree
    :composes-into [parallel-independent-fp
                    sequential-pipeline-fp]}})

(def design-behavior-seed
  "Producing a convergent plan, spec, or architecture from inputs.
   The behavior that turns inputs into a single committable artifact."
  {:target-id design-behavior-id
   :body
   {:capabilities
    ["given a goal + constraints + (optionally) candidate options, produce a single concrete plan/spec/architecture as the chosen direction"
     "natural fit as the convergence stage after research/extraction/analysis/ideation; the gate before code-building or execution"
     "outputs are typically a single structured spec (sections / decisions / open-questions) with explicit decisions rather than a list of options"]

    :strengths
    [{:trait "structure the design output as :map with named sections (decisions / open-questions / risks) — downstream code-building or execution can :read specific sections without re-parsing prose"
      :good-when "the design will feed automated downstream stages (code-building, validation, execution)"
      :recommended-pattern "[:llm {:reads [:goal :constraints :options] :writes [:spec] :output-schemas {:spec [:map [:decisions [:vector :string]] [:open-questions [:vector :string]] [:risks [:vector :string]]]}}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "iterate design via draft → critique → refined-spec when the design is non-trivial — single-pass design tends to leave decisions implicit"
      :good-when "the design has multiple interacting decisions AND the cost of a wrong call is high"
      :recommended-pattern "[:sequence [:llm {:reads [:goal] :writes [:draft-spec]}] [:llm {:reads [:draft-spec :goal] :writes [:critique]}] [:llm {:reads [:draft-spec :critique] :writes [:refined-spec]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "designs that leave open-questions implicit force the downstream stage to either fail or guess — both produce drift between intended and actual outcome"
      :avoid-when "downstream stages will execute against the design"
      :recommended-alternative "require an explicit :open-questions field in the spec output — empty means 'I'm confident'; non-empty surfaces the gap"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "design without explicit consideration of the constraints (as part of :reads) tends to produce specs that look reasonable but violate the constraints in subtle ways"
      :avoid-when "the task has hard constraints (budget / API limits / shape requirements)"
      :recommended-alternative "always include constraints as a separate :reads input on the design stage AND require the spec output to reference how each constraint was honored"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["technical architecture spec from a problem brief + constraints"
     "experiment design from a research question + dataset constraints"
     "API contract design from a feature spec + backwards-compat requirements"]

    :avoid-when
    ["the task is generating options to choose from — that's ideation, not design"
     "the task is just executing a known plan — that's code-building or transformation"
     "the task is checking an existing design — that's critique or validation"]

    :summary
    "Design produces a single committable plan/spec/architecture from inputs (goal + constraints + optional candidate options). Always structure as :map with named sections (decisions / open-questions / risks) — downstream automated stages bind on these. For non-trivial designs with interacting decisions, iterate via draft → critique → refined-spec. ALWAYS require an :open-questions field — empty means 'I'm confident', non-empty surfaces the gap before downstream stages run against an implicit guess. Composes into iterative-refinement (draft → critique → finalize) and sequential-pipeline (analysis → design → code-building) shells. Avoid when the task is option generation (use ideation) or execution against a known plan (use code-building)."

    :version 1
    :consolidated-from-event-count 0

    :scope :behavioral-subtree
    :composes-into [iterative-refinement-task-class-id
                    sequential-pipeline-fp]}})

(def critique-behavior-seed
  "Evaluating an artifact against quality criteria — producing grades,
   issues, and recommendations. The behavior that scores work."
  {:target-id critique-behavior-id
   :body
   {:capabilities
    ["given an artifact + quality criteria, produce a structured critique — per-criterion grade + list of specific issues + suggested fixes"
     "natural fit downstream of synthesis/design/code-building, upstream of refinement loops or human review"
     "outputs are typically a map with per-criterion scores + a vector of specific issues with locations + a vector of suggested improvements"]

    :strengths
    [{:trait "require per-issue location references (line number / section name / item id) so the downstream refinement stage knows exactly what to address — vague issues produce vague fixes"
      :good-when "the artifact is structured and references are addressable"
      :recommended-pattern "[:llm {:reads [:artifact :criteria] :writes [:issues] :output-schemas {:issues [:vector [:map [:location :string] [:problem :string] [:severity :string] [:suggested-fix :string]]]}}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "score against an explicit per-criterion rubric (not a single overall grade) so the refinement stage knows which dimension needs work — overall-grade-only critiques produce regressions in unscored dimensions"
      :good-when "quality has multiple distinct dimensions (correctness / clarity / completeness / efficiency)"
      :recommended-pattern "[:llm {:reads [:artifact :rubric] :writes [:scores] :output-schemas {:scores [:map-of :string :double]}}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "self-critique on the same model (no prompt-framing change) tends to ratify the original artifact — the model is biased toward consistency with its prior output"
      :avoid-when "the critique stage uses the same :llm with the same role framing as the producer"
      :recommended-alternative "use a different prompt persona (skeptical reviewer / safety auditor) OR a different model OR an explicit checklist that forces specific question forms — adversarial framing improves catch rate"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "critiques that don't produce actionable per-issue fixes leave the refinement stage with nothing to act on — issues phrased as 'unclear' don't tell the writer what to do"
      :avoid-when "the critique feeds a refinement stage"
      :recommended-alternative "every issue must include a :suggested-fix — explicit phrasing of how to address it; not 'consider revising' but 'replace X with Y because Z'"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["draft review: per-section grade + specific issues + suggested rewrites"
     "code review: per-file findings + severity + suggested fix"
     "design review: per-decision check + risks + open questions"]

    :avoid-when
    ["the task is checking against formal rules (pass/fail) — that's validation, not critique"
     "the artifact has no downstream consumer to act on the critique — orphan critique wastes tokens"
     "the task is generating the artifact itself — critique is downstream of production"]

    :summary
    "Critique evaluates an artifact against quality criteria, producing per-criterion grades + located issues + suggested fixes. Always require per-issue location references AND per-issue :suggested-fix — vague critiques produce vague refinements. Score against an explicit per-dimension rubric, NOT a single overall grade, so refinement knows where to focus. NEVER use the same prompt persona for critique as for the original producer — self-critique ratifies. Use adversarial framing (skeptical reviewer / safety auditor) or an explicit checklist. Composes into draft-critique (validation-loop child) and iterative-refinement (sequential-pipeline child) shells; also into validation-loop pattern when paired with a producer. Avoid for formal rule-checking (use validation) or for orphan critiques with no downstream consumer."

    :version 1
    :consolidated-from-event-count 0

    :scope :behavioral-subtree
    :composes-into [draft-critique-task-class-id
                    iterative-refinement-task-class-id
                    validation-loop-fp]}})

(def validation-behavior-seed
  "Checking an artifact against formal rules — producing pass/fail with
   reasons. The behavior that gates progression."
  {:target-id validation-behavior-id
   :body
   {:capabilities
    ["given an artifact + a formal rule set (schema / spec / policy), produce a pass/fail decision per rule + reasons for any failures"
     "natural fit as a gate node before downstream commitment (publish / merge / execute)"
     "outputs are typically {:passed? boolean :violations [...]} or a per-rule pass-fail map with reasons"]

    :strengths
    [{:trait "implement the rule check itself in :code when the rule is mechanical (schema validation / regex match / field presence) — :code is deterministic and cheap; only fall back to :llm for rules that need judgment"
      :good-when "the rules are objective and machine-checkable"
      :recommended-pattern "[:code {:reads [:artifact :schema] :writes [:result] :fn (fn [{:keys [inputs]}] {:result {:passed? true :violations []}})}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "structure the failure output as a vector of {:rule :reason :affected-location} entries — gives the upstream producer (validation-loop) actionable retry signal"
      :good-when "validation feeds a retry/refinement loop where the producer needs to know what to fix"
      :recommended-pattern "[:llm {:reads [:artifact :rules] :writes [:result] :output-schemas {:result [:map [:passed? :boolean] [:violations [:vector [:map [:rule :string] [:reason :string] [:location :string]]]]]}}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "single :llm-based validation on a mechanical rule (schema match / field presence) is non-deterministic AND wastes tokens — the same artifact gets different pass/fail across runs"
      :avoid-when "the rule is fully expressible as code"
      :recommended-alternative "implement mechanical rules in :code; use :llm only for judgment-requiring rules (semantic-fit / policy-intent / tone)"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "validation without a fallback path silently blocks the tree when external rule sources fail — the gate never opens"
      :avoid-when "validation depends on an external service (policy API, schema registry) AND the rest of the tree can't degrade"
      :recommended-alternative "wrap external validation in :fallback with a local cached rule set OR a 'human review queue' branch — gate fails gracefully"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["schema validation against a JSON Schema / Malli spec"
     "policy compliance check against an org's content rules"
     "completeness check: required sections present, required fields populated"]

    :avoid-when
    ["the task is quality assessment with judgment — that's critique, not validation"
     "the task is choosing among options — that's selection, downstream of ideation"
     "there's no commitment downstream that depends on pass/fail — gate is unnecessary"]

    :summary
    "Validation checks an artifact against formal rules, producing pass/fail + per-rule reasons. ALWAYS implement mechanical rules (schema / regex / field presence) in :code — deterministic and cheap. Only use :llm for judgment-requiring rules (semantic-fit / policy-intent). Structure failures as {:rule :reason :location} entries so the upstream producer in a validation-loop gets actionable retry signal. Wrap external rule sources in :fallback when the gate is on the critical path — silent failure of validation blocks the tree. Composes into producer-validator (validation-loop child), validation-loop pattern (with producer), and fallback-recovery shells (primary + backup validators). Avoid for judgment-based quality assessment (use critique) or for option selection (use convergent stage)."

    :version 1
    :consolidated-from-event-count 0

    :scope :behavioral-subtree
    :composes-into [producer-validator-task-class-id
                    validation-loop-fp
                    fallback-recovery-fp]}})

(def code-building-behavior-seed
  "Producing executable code from a spec. The behavior that emits
   committable code, scripts, or configurations."
  {:target-id code-building-behavior-id
   :body
   {:capabilities
    ["given a spec + constraints + (optionally) examples, produce executable code / scripts / config as the artifact"
     "natural fit at the tail of a tree after design has produced the spec, before validation gates the result"
     "outputs are typically code strings with :file-path / :language / :imports / :tests so downstream stages can persist or execute"]

    :strengths
    [{:trait "structure the code output as :map with :file-path / :language / :imports / :code so downstream stages (validation / persistence / execution) can bind on specific fields rather than re-parse a code blob"
      :good-when "the code feeds automated downstream stages (linting, test runs, file writes)"
      :recommended-pattern "[:llm {:reads [:spec] :writes [:artifact] :output-schemas {:artifact [:map [:file-path :string] [:language :string] [:imports [:vector :string]] [:code :string]]}}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "iterate code via draft → critique → refined-code when the code is non-trivial — single-pass code has higher defect rates than draft-critique-refine for the same prompt"
      :good-when "the code involves multiple interacting decisions (error handling / edge cases / type contracts)"
      :recommended-pattern "[:sequence [:llm {:reads [:spec] :writes [:draft-code]}] [:llm {:reads [:draft-code :spec] :writes [:critique]}] [:llm {:reads [:draft-code :critique] :writes [:refined-code]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "code-building without an explicit :spec input (just a goal string) produces code that maps to the model's first guess at what the goal meant — divergence from intent surfaces only at test time"
      :avoid-when "the goal is ambiguous OR the consequences of wrong-interpretation are expensive"
      :recommended-alternative "always pipe code-building's :reads through a design stage first; the design's :spec field is the input to code-building, not the raw goal"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "single-shot generation of a long file (>200 lines) produces code with internal inconsistencies — function signatures mismatch their callers, imports declared but unused"
      :avoid-when "the artifact is a long file with multiple cohesive sections"
      :recommended-alternative "scaffold via :code-built outline (top-of-file imports / function-declarations / glue), then :map-each per-section generation, then :code-stitched final — preserves consistency across sections"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["function implementation from a typed spec + examples"
     "config file generation from a deployment plan"
     "test case generation from a behavior specification"]

    :avoid-when
    ["the artifact is a mechanical transformation (CSV → JSON, format conversion) — use transformation, not code-building"
     "the task is reviewing existing code — that's critique, not code-building"
     "no spec exists yet — run design first; code-building's input is a spec"]

    :summary
    "Code-building produces executable code / scripts / config from a spec. Always structure output as :map with :file-path / :language / :imports / :code — downstream automated stages bind on these. For non-trivial code, iterate via draft → critique → refined-code; single-pass produces higher defect rates. NEVER feed code-building a raw goal — always pipe through a design stage first so the input is a spec, not an open-ended ask. For long files (>200 lines), scaffold then :map-each per-section to preserve cross-section consistency. Composes into iterative-refinement (draft → critique → finalize) and sequential-pipeline (design → code → validate) shells. Avoid for mechanical transformation (use transformation) or code review (use critique)."

    :version 1
    :consolidated-from-event-count 0

    :scope :behavioral-subtree
    :composes-into [iterative-refinement-task-class-id
                    sequential-pipeline-fp]}})

(def transformation-behavior-seed
  "Mechanical reshape of data — ETL, format conversion, restructuring.
   The behavior that converts data between shapes deterministically."
  {:target-id transformation-behavior-id
   :body
   {:capabilities
    ["given input data + a target shape spec, emit the input restructured to the target shape"
     "natural fit anywhere a tree needs to change data shape without semantic interpretation"
     "outputs are typically a single restructured value or a vector of per-item transformations"]

    :strengths
    [{:trait "implement transformations in :code (NOT :llm) when the mapping is deterministic — preserves byte-for-byte fidelity, costs zero tokens, runs in milliseconds"
      :good-when "the transformation rule can be expressed as a pure function over the input"
      :recommended-pattern "[:code {:reads [:input] :writes [:output] :fn (fn [{:keys [inputs]}] {:output (mapv (fn [r] (select-keys r [:a :b])) (:input inputs))})}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "for per-item independent transformations over a vector, use :map-each rather than a single batch :code call when the per-item work is non-trivial — supports per-item failure isolation"
      :good-when "items can fail independently AND partial success has value"
      :recommended-pattern "[:map-each {:items :records :as :record :writes [:transformed]} [:code {:reads [:record] :writes [:transformed] :fn (fn [{:keys [inputs]}] {:transformed (assoc (:record inputs) :stamp \"x\")})}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "using :llm for a deterministic transformation (CSV → JSON, snake_case → camelCase) produces non-deterministic output AND wastes tokens"
      :avoid-when "the transformation rule is fully specifiable"
      :recommended-alternative "always :code for deterministic transformations; reserve :llm for transformations that need judgment (semantic-rephrasing / style-shift / domain-translation)"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "transformations without :output-schemas on the downstream :reads break silently when an upstream change shifts the output shape — the consumer gets garbage instead of an error"
      :avoid-when "the transformation feeds another stage that binds on specific fields"
      :recommended-alternative "declare :output-schemas on the transformation output AND :input-schemas on the downstream :reads — shape mismatch fails loudly at the boundary, not deep in the consumer"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["CSV row → JSON object mapping per-field"
     "extracted entity records → DB-row shape with derived fields"
     "input list → output list with N filters / projections applied"]

    :avoid-when
    ["the transformation needs semantic reasoning (translation / paraphrase / domain-shift) — that's NOT deterministic; use :llm"
     "the task is producing new structure from raw text — that's extraction"
     "the input is unstructured — extract first, then transform"]

    :summary
    "Transformation is mechanical reshape of data between deterministic shapes. ALWAYS use :code (not :llm) for deterministic transformations — preserves fidelity, costs nothing, runs in ms; :llm for transformation produces non-deterministic output. For per-item independent transformations, :map-each isolates per-item failures. ALWAYS declare :output-schemas on transformation output and :input-schemas on downstream :reads so shape mismatch fails at the boundary, not deep in the consumer. Composes into etl-pipeline (sequential transform chain), parallel-sum (map-reduce transformation), sequential-pipeline shells, and map-reduce shells. Avoid for transformations that need judgment (semantic rephrasing / style shift — use :llm) or for unstructured input (extract first)."

    :version 1
    :consolidated-from-event-count 0

    :scope :behavioral-subtree
    :composes-into [etl-pipeline-task-class-id
                    parallel-sum-task-class-id
                    map-reduce-fp]}})

(def classification-behavior-seed
  "Categorizing each item from a set into one or more predefined
   categories. The behavior that assigns labels."
  {:target-id classification-behavior-id
   :body
   {:capabilities
    ["given a set of items + a category vocabulary, emit per-item category assignments (optionally multi-label with confidence)"
     "natural fit when downstream stages branch or aggregate by category"
     "outputs are typically a vector of {:item-id :category :confidence} or a map from item-id to category"]

    :strengths
    [{:trait "fan out per-item classification via :map-each with bounded :max-concurrency — per-item LLM calls give richer per-item reasoning than a single batch classifier prompt"
      :good-when "items are independent AND per-item context matters for accuracy"
      :recommended-pattern "[:map-each {:items :items :as :item :max-concurrency 3 :writes [:classifications]} [:llm {:reads [:item :vocabulary] :writes [:classification] :output-schemas {:classification [:map [:category :string] [:confidence :double]]}}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "include the category vocabulary in :reads of each per-item classifier — anchors the model to the closed-set output and prevents drift to free-form labels"
      :good-when "the category set is closed and stable"
      :recommended-pattern "[:llm {:reads [:item :vocabulary] :writes [:classification] :output-schemas {:classification [:enum :a :b :c]}}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "classifying without the vocabulary in the prompt produces free-form labels that drift across runs — downstream aggregation over categories breaks"
      :avoid-when "downstream stages aggregate / branch on category equality"
      :recommended-alternative "always include the closed-set vocabulary in :reads AND constrain output via :output-schemas with [:enum ...] of the allowed categories"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "single batch classification of N>20 items in one :llm call loses per-item attention — accuracy degrades at the tail of the batch"
      :avoid-when "item count >20 AND per-item accuracy matters"
      :recommended-alternative ":map-each per-item with bounded :max-concurrency — wall-time stays low and per-item attention is preserved"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["per-document topic classification: each document → topic label + confidence"
     "per-issue triage: each support ticket → category from a fixed taxonomy"
     "per-clause risk-class assignment: each contract clause → risk level + reasoning"]

    :avoid-when
    ["categories are open-ended / emergent — that's clustering, not classification"
     "items are uniform and a regex/rule would do — that's :code transformation, not behavioral classification"
     "the task is generating new categories — that's ideation, not classification"]

    :summary
    "Classification assigns each item from a set to one or more predefined categories. ALWAYS include the closed-set vocabulary in :reads AND constrain output via :output-schemas with [:enum ...] of allowed categories — open-ended output drifts across runs and breaks downstream aggregation. Prefer :map-each per-item (with bounded :max-concurrency) over batch classification for >20 items — preserves per-item attention. Composes into parallel-classify-aggregate (map-reduce variant), map-reduce shells, and parallel-independent shells (multi-axis classification with per-axis classifier). Avoid for open-ended labels (clustering), for rule-based assignments (use :code), or for category set generation (use ideation)."

    :version 1
    :consolidated-from-event-count 0

    :scope :behavioral-subtree
    :composes-into [parallel-classify-aggregate-task-class-id
                    map-reduce-fp
                    parallel-independent-fp]}})

;; =============================================================================
;; R07 — Investigation behavioral seed (12th competency)
;; =============================================================================
;;
;; Driven by R05e live verify (2026-05-28): 4 of 24 OOD instructions
;; fresh-minted in a single concentrated semantic cluster (flaky-test
;; investigation, property-test generation, memory-leak debug, postmortem
;; review). The R05c minting affordance fired honestly across all 4; the
;; clustering reveals a corpus gap that's authorable as one new behavioral
;; seed. Investigation explains WHY a system is misbehaving — distinct
;; from Research (gathers external material) and Analysis (reasons over
;; already-extracted items).

(def investigation-behavior-id
  (stable-uuid-from "seed:behavior:investigation"))

(def investigation-behavior-seed
  "Identifying the root cause of an unexpected behavior. Inputs are
   symptoms + ambient context; output is a structured hypothesis with
   supporting evidence and a recommended fix or next step."
  {:target-id investigation-behavior-id
   :body
   {:capabilities
    ["given symptoms + ambient context (logs, code, test artifacts, stack traces), produce a structured hypothesis with supporting evidence and a recommended fix or next step"
     "natural fit when the goal is to explain WHY something is broken — distinct from Research (gather new material) and Analysis (reason over already-extracted items)"
     "outputs are typically a single root-cause statement + supporting evidence references + a recommended-fix or next-step"]

    :strengths
    [{:trait "enumerate candidate hypotheses UPFRONT from the symptom before collecting evidence — separates 'what could be wrong' from 'what is wrong' so evidence rules out rather than confirms the first guess"
      :good-when "the failure mode space is bounded AND the model can enumerate likely causes from the symptom alone"
      :recommended-pattern "[:sequence [:llm {:reads [:symptom :context] :writes [:candidate-hypotheses]}] [:llm {:reads [:candidate-hypotheses :context] :writes [:ruled-out :remaining]}] [:llm {:reads [:remaining :context] :writes [:root-cause :recommended-fix]}] [:final {:keys [:root-cause :recommended-fix]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "for investigations over large evidence sets (multi-file logs, large codebases), chunk the evidence and run per-chunk hypothesis-evidence collection via :map-each so per-chunk reasoning parallelizes and the aggregator sees per-chunk findings tagged with their source"
      :good-when "evidence is too large to fit in a single :llm context AND per-chunk findings can be aggregated without losing per-chunk attribution"
      :recommended-pattern "[:map-each {:items :evidence-chunks :as :chunk :max-concurrency 3 :writes [:per-chunk-findings]} [:llm {:reads [:chunk :symptom] :writes [:per-chunk-findings] :output-schemas {:per-chunk-findings [:vector [:map [:hypothesis :string] [:supporting-evidence :string] [:chunk-id :string]]]}}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :weaknesses
    [{:trait "single-pass investigation on a complex symptom converges on the first plausible hypothesis without ruling out alternatives — produces overconfident root-cause claims that fail when the true cause was the second hypothesis"
      :avoid-when "the symptom has multiple plausible causes AND the cost of acting on the wrong root cause is high"
      :recommended-alternative "explicit multi-hypothesis enumeration upfront; an intermediate :llm stage must rule out at least ONE candidate hypothesis with evidence before convergence to a root cause is allowed"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}
     {:trait "investigation without ambient context (just the symptom + nothing else) produces vacuous 'could be X / could be Y' output the consumer can't act on — the model has no evidence to rule anything in or out"
      :avoid-when "context (logs / code / tests / stack traces) is available in the system but not passed in :reads"
      :recommended-alternative "always pass ambient context explicitly in :reads; if the context is too large for a single :llm node, compose into chunked-extraction via :map-each so per-chunk evidence reaches the hypothesis-evidence stage"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-05-28T00:00:00Z"
      :last-reinforced-at "2026-05-28T00:00:00Z"}]

    :representative-uses
    ["investigate why an integration test is flaky: enumerate timing / shared-state / environment hypotheses; rule out via test logs + run history; recommend the deterministic fix"
     "generate property-based tests that exercise edge cases — property test generation IS investigation, finding inputs that break invariants"
     "debug a memory leak in a long-running service: enumerate allocator / reference-cycle / cache-bloat hypotheses; rule out via heap snapshots + allocation profiles; recommend the targeted fix"
     "review a post-incident report for missing root-cause analysis: enumerate plausible causes the report didn't address; surface the gap with evidence from the timeline"]

    :avoid-when
    ["the task is exploration without a specific failure to explain — that's research, not investigation"
     "the items have already been extracted and the task is reasoning over them — that's analysis"
     "the task is producing a new spec or design from scratch — that's design (no failure to investigate)"
     "the task is checking against known formal rules — that's validation (the rules are known; nothing to investigate)"]

    :summary
    "Investigation explains WHY a system is misbehaving. Best implemented as an explicit hypothesis-then-evidence shape: enumerate candidate causes from the symptom UPFRONT, then collect evidence to rule out alternatives, then converge on the root cause + recommended fix. ALWAYS pass ambient context (logs / code / tests / stack traces) in :reads — context-free investigation produces vacuous output. For large evidence sets (multi-file logs, large codebases), compose into chunked-extraction shells so per-chunk evidence collection parallelizes. Composes into sequential-pipeline (canonical hypothesis chain), fallback-recovery (try-one-path-then-another when initial hypothesis fails), and chunked-extraction (investigating large evidence sets). Distinct from Research (which gathers external material to seed new work, not explain existing breakage) and Analysis (which reasons over already-extracted items, not raw symptoms). Avoid when the task is exploration without a specific failure to explain."

    :version 1
    :consolidated-from-event-count 0

    :scope :behavioral-subtree
    :composes-into [sequential-pipeline-fp
                    fallback-recovery-fp
                    chunked-extraction-fp]}})

(def all-behavioral-subtree-seeds
  "Vector of all top-level behavioral-subtree seeds. R05a's 11 abstract
   competencies + R07's Investigation. Each composes into the structural
   shells declared in its :composes-into field."
  [research-behavior-seed
   extraction-behavior-seed
   analysis-behavior-seed
   synthesis-behavior-seed
   ideation-behavior-seed
   design-behavior-seed
   critique-behavior-seed
   validation-behavior-seed
   code-building-behavior-seed
   transformation-behavior-seed
   classification-behavior-seed
   investigation-behavior-seed])

(def all-tree-fingerprint-seeds
  "Vector of all tree-fingerprint seeds. Grows as we author each one."
  [document-analysis-tree-seed
   risk-analysis-tree-seed
   contract-comparison-tree-seed
   legal-issue-detection-tree-seed
   chunked-extraction-tree-seed
   chunked-extraction-pattern-seed
   sequential-pipeline-pattern-seed
   parallel-independent-pattern-seed
   validation-loop-pattern-seed
   fallback-recovery-pattern-seed
   map-reduce-pattern-seed
   research-then-synthesize-pattern-seed
   ;; R02 — children of the previously-flat top-level patterns
   etl-pipeline-tree-seed
   iterative-refinement-tree-seed
   scheduling-tree-seed
   producer-validator-tree-seed
   draft-critique-tree-seed
   primary-backup-tree-seed
   model-cascade-tree-seed
   parallel-sum-tree-seed
   parallel-classify-aggregate-tree-seed
   briefing-generation-tree-seed
   comparative-summary-tree-seed])

(defn seed-one!
  "Emit a single description seed via the appropriate command."
  [ctx granularity {:keys [target-id body]}]
  (let [cmd-name (case granularity
                   :node-type        :ontology/record-node-type-description
                   :node-instance    :ontology/record-node-instance-description
                   :tree-fingerprint :ontology/record-tree-description
                   :tree-class       :ontology/record-tree-class-description)]
    (cp/process-command
      (assoc ctx :command {:command/name cmd-name
                           :command/id (random-uuid)
                           :command/timestamp (time/now)
                           :target-id target-id
                           :body body}))))

(defn seed-all!
  "Emit every authored seed into the event store. Returns a vec of
   command-result maps. R05a: behavioral-subtree seeds are emitted via
   the same :ontology/record-tree-description command path; their
   :scope :behavioral-subtree body routes them to the R05a reactive
   processor rather than C-2d-1's tree-class processor.

   C-Loop-1: each tree-fingerprint seed is ALSO recorded under
   :tree-class scope (same target-id, same body). This gives the
   Living Description loop's consolidator a non-nil current-description
   to refine on its first cycle, AND lets apply-r05-classifier-context
   read seed content via `get-description :tree-class` from bootstrap
   onward. Tree-fingerprint events stay for the existing classifier
   search index path (the ColBERT corpus partitions by granularity)."
  [ctx]
  (concat
    (mapv #(seed-one! ctx :node-type %) all-node-type-seeds)
    (mapv #(seed-one! ctx :tree-fingerprint %) all-tree-fingerprint-seeds)
    (mapv #(seed-one! ctx :tree-class %) all-tree-fingerprint-seeds)
    (mapv #(seed-one! ctx :tree-fingerprint %) all-behavioral-subtree-seeds)))

(comment
  ;; Run from REPL after starting a context:
  (require '[seed-descriptions :as s] :reload)
  (s/seed-all! ctx))
