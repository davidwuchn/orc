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
    :consolidated-from-event-count 0}})

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
    :consolidated-from-event-count 0}})

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
    :consolidated-from-event-count 0}})

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
    :consolidated-from-event-count 0}})

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
    :consolidated-from-event-count 0}})

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
   research-then-synthesize-pattern-seed])

(defn seed-one!
  "Emit a single description seed via the appropriate command."
  [ctx granularity {:keys [target-id body]}]
  (let [cmd-name (case granularity
                   :node-type        :ontology/record-node-type-description
                   :node-instance    :ontology/record-node-instance-description
                   :tree-fingerprint :ontology/record-tree-description)]
    (cp/process-command
      (assoc ctx :command {:command/name cmd-name
                           :command/id (random-uuid)
                           :command/timestamp (time/now)
                           :target-id target-id
                           :body body}))))

(defn seed-all!
  "Emit every authored seed into the event store. Returns a vec of
   command-result maps."
  [ctx]
  (concat
    (mapv #(seed-one! ctx :node-type %) all-node-type-seeds)
    (mapv #(seed-one! ctx :tree-fingerprint %) all-tree-fingerprint-seeds)))

(comment
  ;; Run from REPL after starting a context:
  (require '[seed-descriptions :as s] :reload)
  (s/seed-all! ctx))
