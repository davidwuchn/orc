(ns ai.obney.orc.evaluation.core.judges
  "Judge implementations for evaluating LLM node executions.

   Judges are functions that take trace data and return evaluation results.
   They can be used directly or wrapped in ORC sheets for full observability.

   Each judge function follows the code executor pattern:
   (fn [{:keys [inputs]}] outputs-map)

   CONFIGURATION:
   Set *use-mock-llm* to true for testing without LLM calls.
   Set *judge-provider* to configure which LLM provider to use.
   Set *judge-model* to configure which model to use."
  (:require [ai.obney.orc.evaluation.core.feedback :as feedback]
            [ai.obney.orc.evaluation.core.rubrics :as rubrics]
            [ai.obney.orc.evaluation.core.scale :as scale]
            [cheshire.core :as json]
            [clojure.string :as str]
            [dscloj.core :as dscloj]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:dynamic *use-mock-llm*
  "Set to true to use mock LLM responses for testing.
   Default: false (use real LLM calls)."
  false)

(def ^:dynamic *judge-provider*
  "Provider keyword for LLM calls (e.g., :openrouter, :anthropic).
   Default: :openrouter"
  :openrouter)

(def ^:dynamic *judge-model*
  "Model to use for judge LLM calls.
   Default: google/gemini-2.5-flash (fast and cost-effective for evaluation)"
  "google/gemini-2.5-flash")

;; =============================================================================
;; Judge Result Schemas (for LLM output)
;; =============================================================================

(def GroundingResultSchema
  "Malli schema for grounding judge output. PA-3: :score is now derived from
   the discrete :level via the Scale (1-5 → [0,1]); :level and :reasoning are
   the richer tier-1 fields (reason-before-score)."
  [:map
   [:score :double]
   [:level {:optional true} [:enum 1 2 3 4 5]]
   [:reasoning {:optional true} :string]
   [:grounded-claims [:vector :string]]
   [:ungrounded-claims [:vector :string]]
   [:feedback :string]])

(def InstructionFollowingResultSchema
  "Malli schema for instruction following judge output"
  [:map
   [:score :double]
   [:requirements-met [:vector :string]]
   [:requirements-missed [:vector :string]]
   [:feedback :string]])

(def ReasoningResultSchema
  "Malli schema for reasoning quality judge output"
  [:map
   [:score :double]
   [:reasoning-strengths [:vector :string]]
   [:reasoning-weaknesses [:vector :string]]
   [:feedback :string]])

(def CompletenessResultSchema
  "Malli schema for completeness judge output"
  [:map
   [:score :double]
   [:aspects-covered [:vector :string]]
   [:aspects-missing [:vector :string]]
   [:feedback :string]])

;; =============================================================================
;; Prompt Rendering
;; =============================================================================

(defn render-rubric-prompt
  "Render a rubric prompt with trace data.

   Args:
     rubric: A rubric map with :prompt template
     trace-data: Map with :inputs, :outputs (or :response), :instruction

   Returns:
     Rendered prompt string"
  [rubric trace-data]
  (let [inputs-json (json/generate-string (or (:inputs trace-data) {}) {:pretty true})
        response (cond
                   (:response trace-data) (:response trace-data)
                   (string? (:outputs trace-data)) (:outputs trace-data)
                   (:outputs trace-data) (json/generate-string (:outputs trace-data) {:pretty true})
                   :else "{}")
        instruction (or (:instruction trace-data) "No instruction provided")]
    (-> (:prompt rubric)
        (str/replace "{inputs}" inputs-json)
        (str/replace "{response}" (str response))
        (str/replace "{instruction}" instruction))))

;; =============================================================================
;; LLM Calling Infrastructure
;; =============================================================================

(defn- build-judge-module
  "Build a DSCloj module for a judge evaluation.

   Args:
     prompt: The rendered rubric prompt
     output-fields: Vector of output field definitions

   Returns:
     DSCloj module map"
  [prompt output-fields]
  {:inputs [{:name :evaluation_request
             :spec :string
             :description "The evaluation request with context and scoring criteria"}]
   :outputs output-fields
   :instructions prompt})

(defn- grounding-output-fields
  "PA-3 tier-1 grounding output fields, ordered to FORCE reason-before-score:
   the model fills :reasoning and the claim lists BEFORE it commits to a
   :level band. Field order in the DSCloj tool schema is the generation
   order. There is NO soft :score field — the score is derived
   deterministically from the discrete :level via the Scale (1-5 → [0,1]),
   never self-reported by the model. Output shape is carried entirely by
   these typed fields (the typed blackboard) — never by json-in-prompt and
   never by a permissive :output-schemas override."
  []
  [{:name :reasoning
    :spec :string
    :description (str "Your adversarial, source-grounded analysis. Hunt for "
                     "claims the source does NOT support. Write this BEFORE "
                     "choosing a level.")}
   {:name :grounded-claims
    :spec [:vector :string]
    :description "Substantive claims in the response that ARE supported by the source."}
   {:name :ungrounded-claims
    :spec [:vector :string]
    :description (str "Substantive claims NOT supported by the source "
                     "(fabrications, unsupported numbers/names/dates, or "
                     "inferences stated as fact). Empty if none.")}
   {:name :level
    :spec [:enum 1 2 3 4 5]
    :description "The grounding band (1-5) chosen AFTER the reasoning, per the bands defined in the instruction."}
   {:name :feedback
    :spec :string
    :description "Specific, actionable feedback the producer can act on to improve grounding."}])

(defn build-grounding-instruction
  "Compose the grounding judge's instruction from the DECOUPLED pieces:
   stance (how to behave) + criteria (what to evaluate) + the Scale's bands
   (how to score). Kept short and field-name-oriented per the structured-
   output rule: it tells the model WHAT to do and names the output fields,
   but contains NO JSON example and NO 'return only JSON' directive — the
   output shape is the typed blackboard's job, not the prompt's.

   The actual source + response + producer-instruction are passed as typed
   INPUT fields (see build-grounding-module), not interpolated here, so the
   instruction stays a stable, decoupled template."
  [{:keys [criteria stance scale]}]
  (str stance "\n\n"
       "WHAT TO EVALUATE:\n" criteria "\n\n"
       "You are given three inputs: `source` (the context the producer had), "
       "`response` (what the producer wrote), and `producer_instruction` "
       "(the task the producer was given). Compare the response against the "
       "source ONLY.\n\n"
       "SCORING BANDS (choose exactly one level for the `level` field):\n"
       (scale/render-bands scale) "\n\n"
       "Fill `reasoning` first (adversarial analysis grounded in the source), "
       "then `grounded-claims` and `ungrounded-claims`, then choose `level`, "
       "then write `feedback`."))

(defn- coerce-source-string
  "Render the producer's inputs/source into a single string for the judge's
   typed `source` input field. Maps/collections are pretty-printed so the
   judge sees structured context; strings pass through."
  [v]
  (cond
    (nil? v) ""
    (string? v) v
    :else (json/generate-string v {:pretty true})))

(defn- build-grounding-module
  "DSCloj module for the tier-1 grounding judge. Typed INPUT fields carry the
   trace data; typed OUTPUT fields carry the verdict. No json-in-prompt, no
   permissive output schema."
  [instruction]
  {:inputs [{:name :source
             :spec :string
             :description "The source context the producer was given (ground truth to check against)."}
            {:name :response
             :spec :string
             :description "The producer's output to evaluate for grounding."}
            {:name :producer_instruction
             :spec :string
             :description "The instruction the producer was given (for context only; do not grade against it)."}]
   :outputs (grounding-output-fields)
   :instructions instruction})

(defn- instruction-following-output-fields []
  [{:name :score
    :spec :double
    :description "Score from 0.0 to 1.0"}
   {:name :requirements-met
    :spec [:vector :string]
    :description "List of instruction requirements that were satisfied"}
   {:name :requirements-missed
    :spec [:vector :string]
    :description "List of instruction requirements that were NOT satisfied"}
   {:name :feedback
    :spec :string
    :description "Specific actionable feedback explaining the score"}])

(defn- reasoning-output-fields []
  [{:name :score
    :spec :double
    :description "Score from 0.0 to 1.0"}
   {:name :reasoning-strengths
    :spec [:vector :string]
    :description "Aspects of reasoning that were good"}
   {:name :reasoning-weaknesses
    :spec [:vector :string]
    :description "Logical gaps or unclear elements"}
   {:name :feedback
    :spec :string
    :description "Specific actionable feedback for improving reasoning"}])

(defn- completeness-output-fields []
  [{:name :score
    :spec :double
    :description "Score from 0.0 to 1.0"}
   {:name :aspects-covered
    :spec [:vector :string]
    :description "Aspects of the task that were addressed"}
   {:name :aspects-missing
    :spec [:vector :string]
    :description "Aspects that should have been included but weren't"}
   {:name :feedback
    :spec :string
    :description "Specific actionable feedback for improving completeness"}])

(defn call-llm-judge
  "Call an LLM to evaluate using the given prompt and parse the response.

   Args:
     prompt: The rendered rubric prompt
     output-fields: Vector of DSCloj output field definitions
     options: Optional map with :provider, :model

   Returns:
     Map with parsed output fields (e.g., {:score 0.8 :feedback \"...\" ...})"
  [prompt output-fields & {:keys [provider model] :or {provider *judge-provider*
                                                        model *judge-model*}}]
  (let [module (build-judge-module prompt output-fields)
        ;; DSCloj needs inputs as a keyword map
        inputs {:evaluation_request "Please evaluate according to the rubric above."}
        ;; Make the LLM call — :model rides through dscloj into the
        ;; litellm router as a per-request override, so the judge model
        ;; actually applies instead of the provider registration's default.
        result (dscloj/predict provider module inputs
                               {:with-metadata? false
                                :validate? false
                                :model model})]
    ;; Result is already a map of keyword -> value
    (or (:outputs result) result)))

(defn call-grounding-judge-llm
  "PA-3 tier-1 grounding LLM call. Sends the trace data as typed INPUT fields
   (source / response / producer_instruction) and the decoupled instruction.
   Returns the raw parsed output map (with :reasoning, :grounded-claims,
   :ungrounded-claims, :level, :feedback) — gating + level→score mapping is
   the caller's job (grounding-judge), so the no-run-through gate sees the
   raw model output."
  [trace-data & {:keys [provider model] :or {provider *judge-provider*
                                             model *judge-model*}}]
  (let [rubric (rubrics/get-tier1-rubric :grounding)
        instruction (build-grounding-instruction rubric)
        module (build-grounding-module instruction)
        response (cond
                   (:response trace-data) (:response trace-data)
                   (string? (:outputs trace-data)) (:outputs trace-data)
                   (:outputs trace-data) (json/generate-string (:outputs trace-data) {:pretty true})
                   :else "")
        inputs {:source (coerce-source-string (:inputs trace-data))
                :response (str response)
                :producer_instruction (or (:instruction trace-data) "No instruction provided")}
        result (dscloj/predict provider module inputs
                               {:with-metadata? false
                                :validate? false
                                :model model})]
    (or (:outputs result) result)))

;; =============================================================================
;; Mock LLM Responses (for testing)
;; =============================================================================

(defn- mock-grounding-result []
  "Mock for the tier-1 grounding shape: a discrete :level + :reasoning, NOT a
   self-reported :score. The judge fn maps :level → :score via the Scale, so
   the mock deliberately omits :score to exercise that path."
  {:level 4
   :reasoning "Mock adversarial review: claims appear traceable to the source; minor extrapolation."
   :grounded-claims ["Mock: Response uses information from provided context"]
   :ungrounded-claims []
   :feedback "Mock evaluation. In production, this analyzes actual grounding against the source."})

(defn- mock-instruction-following-result [prompt]
  {:score 0.85
   :requirements-met ["Mock: Main task addressed"]
   :requirements-missed []
   :feedback (str "Mock evaluation - prompt length: " (count prompt) " chars. "
                  "In production, this would analyze instruction compliance.")})

(defn- mock-reasoning-result [prompt]
  {:score 0.75
   :reasoning-strengths ["Mock: Clear logical structure"]
   :reasoning-weaknesses []
   :feedback (str "Mock evaluation - prompt length: " (count prompt) " chars. "
                  "In production, this would analyze reasoning quality.")})

(defn- mock-completeness-result [prompt]
  {:score 0.9
   :aspects-covered ["Mock: Main aspects addressed"]
   :aspects-missing []
   :feedback (str "Mock evaluation - prompt length: " (count prompt) " chars. "
                  "In production, this would analyze completeness.")})

;; =============================================================================
;; Judge Functions (Code Executor Pattern)
;; =============================================================================

(defn grounding-judge
  "PA-3 tier-1 grounding judge: adversarial, source-grounded, reason-before-
   score, on a decoupled discrete 1-5 Scale mapped deterministically to
   [0,1]. A judge is one capability with two deployment modes (event-
   subscribed and in-pipeline gate); this fn is the shared internal scoring.

   This is a code executor function for use in sheet/code nodes.

   Input keys:
     trace-data: Map with :inputs, :outputs/:response, :instruction

   Output keys:
     grounding-result: Map carrying the BACK-COMPATIBLE shape
       {:score (double 0-1) :grounded-claims :ungrounded-claims :feedback}
       PLUS the richer tier-1 fields {:level :reasoning}. :score is derived
       from the discrete :level via the Scale, never self-reported.

   The no-run-through gate (scale/gate-banded-output) THROWS on empty/garbage
   model output — a judge never silently scores 0 on a structured-output
   regression."
  [{:keys [inputs] :as _executor-context}]
  (let [trace-data (:trace-data inputs)
        the-scale (:scale (rubrics/get-tier1-rubric :grounding))
        raw (if *use-mock-llm*
              (mock-grounding-result)
              (call-grounding-judge-llm trace-data))
        ;; no-run-through gate: empty/missing-level output throws; otherwise
        ;; enriches with a deterministic :score from the discrete :level.
        gated (scale/gate-banded-output the-scale raw)]
    {:grounding-result
     {:score (:score gated)                     ;; deterministic [0,1] from the band
      :level (:level gated)                      ;; the discrete 1-5 band the judge chose
      :grounded-claims (vec (:grounded-claims gated))
      :ungrounded-claims (vec (:ungrounded-claims gated))
      :reasoning (or (:reasoning gated) "")
      :feedback (or (:feedback gated) "")}}))

(defn instruction-following-judge
  "Evaluate if the LLM followed its instruction.

   Input keys:
     trace-data: Map with :inputs, :outputs/:response, :instruction

   Output keys:
     instruction-result: Map with :score, :requirements-met, :requirements-missed, :feedback"
  [{:keys [inputs] :as _executor-context}]
  (let [trace-data (:trace-data inputs)
        rubric (rubrics/get-rubric :instruction-following)
        prompt (render-rubric-prompt rubric trace-data)
        result (if *use-mock-llm*
                 (mock-instruction-following-result prompt)
                 (call-llm-judge prompt (instruction-following-output-fields)))]
    {:instruction-result result}))

(defn reasoning-judge
  "Evaluate the quality of reasoning in the response.

   Input keys:
     trace-data: Map with :inputs, :outputs/:response, :instruction

   Output keys:
     reasoning-result: Map with :score, :reasoning-strengths, :reasoning-weaknesses, :feedback"
  [{:keys [inputs] :as _executor-context}]
  (let [trace-data (:trace-data inputs)
        rubric (rubrics/get-rubric :reasoning)
        prompt (render-rubric-prompt rubric trace-data)
        result (if *use-mock-llm*
                 (mock-reasoning-result prompt)
                 (call-llm-judge prompt (reasoning-output-fields)))]
    {:reasoning-result result}))

(defn completeness-judge
  "Evaluate the completeness of the response.

   Input keys:
     trace-data: Map with :inputs, :outputs/:response, :instruction

   Output keys:
     completeness-result: Map with :score, :aspects-covered, :aspects-missing, :feedback"
  [{:keys [inputs] :as _executor-context}]
  (let [trace-data (:trace-data inputs)
        rubric (rubrics/get-rubric :completeness)
        prompt (render-rubric-prompt rubric trace-data)
        result (if *use-mock-llm*
                 (mock-completeness-result prompt)
                 (call-llm-judge prompt (completeness-output-fields)))]
    {:completeness-result result}))

;; =============================================================================
;; Aggregation Executor
;; =============================================================================

(defn aggregate-dimensions
  "Aggregate multiple dimension results into a single score.

   Input keys:
     grounding-result: Result from grounding judge
     instruction-result: Result from instruction following judge
     reasoning-result: Result from reasoning judge
     completeness-result: Result from completeness judge

   Output keys:
     aggregate-score: Float between 0.0 and 1.0
     feedback-summary: Combined feedback string
     dimensions: Vector of dimension details"
  [{:keys [inputs] :as _executor-context}]
  (let [grounding (:grounding-result inputs)
        instruction (:instruction-result inputs)
        reasoning (:reasoning-result inputs)
        completeness (:completeness-result inputs)

        dimensions [(feedback/->metric-dimension
                     "Source Grounding" 0.35
                     (or (:score grounding) 0.5)
                     (or (:feedback grounding) "No feedback"))
                    (feedback/->metric-dimension
                     "Instruction Following" 0.25
                     (or (:score instruction) 0.5)
                     (or (:feedback instruction) "No feedback"))
                    (feedback/->metric-dimension
                     "Reasoning Quality" 0.20
                     (or (:score reasoning) 0.5)
                     (or (:feedback reasoning) "No feedback"))
                    (feedback/->metric-dimension
                     "Completeness" 0.20
                     (or (:score completeness) 0.5)
                     (or (:feedback completeness) "No feedback"))]

        result (feedback/combine-dimension-scores dimensions)]

    {:aggregate-score (:score result)
     :feedback-summary (feedback/aggregate-feedback-summary result)
     :dimensions (mapv #(select-keys % [:name :weight :score :feedback])
                       dimensions)}))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn evaluate-single
  "Evaluate a trace with a single judge.

   Convenience wrapper that formats inputs correctly.

   Args:
     judge-key: One of :grounding, :instruction-following, :reasoning, :completeness
     trace-data: Map with :inputs, :outputs/:response, :instruction

   Returns:
     The judge result map"
  [judge-key trace-data]
  (let [judge-fn (case judge-key
                   :grounding grounding-judge
                   :instruction-following instruction-following-judge
                   :reasoning reasoning-judge
                   :completeness completeness-judge)]
    (judge-fn {:inputs {:trace-data trace-data}})))

(defn evaluate-all
  "Evaluate a trace with all judges and aggregate.

   Args:
     trace-data: Map with :inputs, :outputs/:response, :instruction

   Returns:
     Map with:
       :aggregate-score - Float 0.0-1.0
       :feedback-summary - Combined feedback
       :dimensions - Per-dimension results
       :raw-results - Individual judge results"
  [trace-data]
  (let [grounding-res (grounding-judge {:inputs {:trace-data trace-data}})
        instruction-res (instruction-following-judge {:inputs {:trace-data trace-data}})
        reasoning-res (reasoning-judge {:inputs {:trace-data trace-data}})
        completeness-res (completeness-judge {:inputs {:trace-data trace-data}})

        ;; Combine all results for aggregation
        combined-inputs (merge grounding-res instruction-res reasoning-res completeness-res)

        agg-result (aggregate-dimensions {:inputs combined-inputs})]

    {:aggregate-score (:aggregate-score agg-result)
     :feedback-summary (:feedback-summary agg-result)
     :dimensions (:dimensions agg-result)
     :raw-results {:grounding (:grounding-result grounding-res)
                   :instruction-following (:instruction-result instruction-res)
                   :reasoning (:reasoning-result reasoning-res)
                   :completeness (:completeness-result completeness-res)}}))

;; =============================================================================
;; Judge Registry
;; =============================================================================

(def judge-registry
  "Registry of available judge executors."
  {:grounding #'grounding-judge
   :instruction-following #'instruction-following-judge
   :reasoning #'reasoning-judge
   :completeness #'completeness-judge
   :aggregate #'aggregate-dimensions})

(defn get-judge
  "Get a judge executor by key."
  [key]
  (get judge-registry key))

;; =============================================================================
;; Configuration Helpers
;; =============================================================================

(defmacro with-mock-llm
  "Execute body with mock LLM responses (for testing)."
  [& body]
  `(binding [*use-mock-llm* true]
     ~@body))

(defmacro with-judge-config
  "Execute body with custom judge configuration.

   Example:
     (with-judge-config {:provider :anthropic :model \"claude-3-haiku-20240307\"}
       (evaluate-all trace-data))"
  [{:keys [provider model mock?]} & body]
  `(binding [*judge-provider* (or ~provider *judge-provider*)
             *judge-model* (or ~model *judge-model*)
             *use-mock-llm* (or ~mock? *use-mock-llm*)]
     ~@body))
