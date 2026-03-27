(ns ai.obney.orc.ontology.core.discovery
  "Pattern discovery workflow and helpers.

   Analyzes low-scoring evaluation feedback from the evaluation component
   to discover new failure subtypes not covered by the current ontology.

   Integration points:
   - Uses :evaluation/trace-evaluated events for judge feedback
   - Uses ontology static concepts for existing failure patterns
   - Emits :ontology/failure-subtype-discovered events for new patterns"
  (:require [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.ontology.core.static-ontology :as static]
            [ai.obney.grain.event-store-v3.interface :as es]))

;; =============================================================================
;; Evaluation Event Extraction
;; =============================================================================

(defn get-low-scoring-evaluations
  "Get evaluation events with low aggregate scores.

   Reads :evaluation/trace-evaluated events from the event store and
   filters to those below the score threshold.

   Args:
   - event-store: Grain event store
   - sheet-id: UUID of the sheet to analyze
   - threshold: Score threshold (default 0.6)
   - limit: Max evaluations to return (default 100)

   Returns vector of maps with:
   - :trace-id, :sheet-id, :node-id, :node-name
   - :aggregate-score
   - :dimensions - vector of {:name :score :feedback}
   - :feedback-summary"
  [{:keys [event-store tenant-id] :as ctx} sheet-id {:keys [threshold limit]
                                                     :or {threshold 0.6 limit 100}}]
  (let [;; Read evaluation events for this sheet
        ;; Note: es/read returns an IReduce, so we convert to vector first
        ;; Note: Event body fields are flattened into the event (no :body key)
        eval-events (into [] (es/read event-store
                                      {:types #{:evaluation/trace-evaluated}
                                       :tags #{[:sheet sheet-id]}
                                       :tenant-id tenant-id}))

        ;; Filter to low-scoring and extract relevant data
        ;; Fields are at the top level of the event, not under :body
        low-scoring (->> eval-events
                         (filter #(< (:aggregate-score %) threshold))
                         (take limit)
                         vec)]
    low-scoring))

(defn format-evaluation-for-discovery
  "Format an evaluation event for pattern discovery analysis.

   Extracts the dimension feedback into a format suitable for LLM analysis.

   Returns map with:
   - :trace-id
   - :node-name
   - :aggregate-score
   - :dimension-feedback - vector of strings like 'Grounding (0.3): feedback text'"
  [{:keys [trace-id node-name aggregate-score dimensions feedback-summary]}]
  {:trace-id trace-id
   :node-name node-name
   :aggregate-score aggregate-score
   :dimension-feedback (mapv (fn [{:keys [name score feedback]}]
                               (format "%s (%.2f): %s" name score feedback))
                             dimensions)
   :feedback-summary feedback-summary})

;; =============================================================================
;; Code Node Executors
;; =============================================================================

(defn load-failure-concepts
  "Code node executor: Load existing failure concepts from static ontology.

   Used in the discovery workflow to provide the LLM with current concepts
   so it can identify patterns NOT already covered."
  [{:keys [_inputs]}]
  {"existing-concepts"
   (->> (static/get-concepts-by-scope :failure)
        (map #(select-keys % [:uri :label :description :indicators]))
        vec)})

(defn format-traces-for-analysis
  "Code node executor: Format raw evaluation data for LLM analysis.

   Takes failure-traces from the blackboard and formats them into
   a structured summary for the pattern analysis LLM."
  [{:keys [inputs]}]
  (let [traces (get inputs "failure-traces")
        formatted (mapv format-evaluation-for-discovery traces)]
    {"formatted-traces"
     (mapv (fn [{:keys [trace-id node-name aggregate-score dimension-feedback]}]
             {:trace-id (str trace-id)
              :node node-name
              :score aggregate-score
              :feedback dimension-feedback})
           formatted)}))

;; =============================================================================
;; Pattern Discovery Workflow
;; =============================================================================

(def pattern-discovery-workflow
  "ORC workflow that analyzes low-scoring evaluation feedback to discover
   new failure subtypes.

   Blackboard:
   - failure-traces: Raw evaluation events (input)
   - existing-concepts: Current failure ontology concepts
   - formatted-traces: Traces formatted for LLM analysis
   - discovered-subtypes: New failure subtypes found (output)

   Flow:
   1. Load current failure concepts from static ontology
   2. Format traces for LLM analysis
   3. Analyze patterns with LLM to find new subtypes"
  (sheet/workflow "ontology-pattern-discovery"
    (sheet/blackboard
      {;; Input: evaluation events with low scores
       :failure-traces [:vector [:map
                                 [:trace-id :uuid]
                                 [:sheet-id :uuid]
                                 [:node-id :uuid]
                                 [:node-name :string]
                                 [:aggregate-score :double]
                                 [:dimensions [:vector [:map
                                                        [:name :string]
                                                        [:score :double]
                                                        [:feedback :string]]]]
                                 [:feedback-summary :string]]]

       ;; Intermediate: existing failure concepts
       :existing-concepts [:vector [:map
                                    [:uri :string]
                                    [:label :string]
                                    [:description :string]
                                    [:indicators [:vector :string]]]]

       ;; Intermediate: formatted traces for LLM
       :formatted-traces [:vector [:map
                                   [:trace-id :string]
                                   [:node :string]
                                   [:score :double]
                                   [:feedback [:vector :string]]]]

       ;; Output: discovered failure subtypes
       :discovered-subtypes [:vector [:map
                                      [:parent-uri :string]
                                      [:proposed-uri :string]
                                      [:label :string]
                                      [:description :string]
                                      [:indicators [:vector :string]]
                                      [:evidence-count :int]]]})

    (sheet/sequence "discover"
      ;; Step 1: Load current failure ontology
      (sheet/code "load-ontology"
        :fn "ai.obney.orc.ontology.core.discovery/load-failure-concepts"
        :reads []
        :writes ["existing-concepts"])

      ;; Step 2: Format traces for analysis
      (sheet/code "format-traces"
        :fn "ai.obney.orc.ontology.core.discovery/format-traces-for-analysis"
        :reads ["failure-traces"]
        :writes ["formatted-traces"])

      ;; Step 3: Analyze patterns with LLM
      (sheet/llm "analyze-patterns"
        :model "anthropic/claude-sonnet-4"
        :instruction "You are analyzing evaluation feedback from LLM judge assessments.
Your task is to identify RECURRING failure patterns that are NOT already covered
by the existing ontology concepts.

Review the formatted-traces which contain:
- Node name and aggregate score
- Dimension feedback from judges (Grounding, Instruction Following, Reasoning, Completeness)

Compare against existing-concepts which are the current failure taxonomy.

For each NEW pattern you identify (must appear in 3+ traces):
1. Find the closest existing parent concept URI (e.g., failure:Hallucination, failure:Grounding)
2. Propose a URI like failure:Parent.NewSubtype (e.g., failure:Hallucination.NumericInvention)
3. Provide a clear, concise label
4. Write a 1-2 sentence description
5. List 3-5 indicator phrases that signal this failure type
6. Count how many traces exhibit this pattern

If no new patterns are found with 3+ occurrences, return an empty array.

Focus on actionable, specific subtypes - not vague generalizations."
        :reads ["formatted-traces" "existing-concepts"]
        :writes ["discovered-subtypes"]))))

(defn build-discovery-workflow!
  "Build the pattern discovery workflow. Returns sheet-id.

   This is idempotent - calling multiple times with the same definition
   will return the same sheet-id."
  [ctx]
  (sheet/build-workflow! ctx pattern-discovery-workflow))

;; =============================================================================
;; Public API
;; =============================================================================

(defn discover-patterns
  "High-level API to run pattern discovery on a sheet's evaluations.

   Args:
   - ctx: Context with event-store
   - sheet-id: Sheet to analyze
   - options:
     - :min-traces - Minimum traces required to run (default 20)
     - :score-threshold - Analyze traces below this score (default 0.6)

   Returns map with:
   - :discovered - count of new subtypes
   - :analyzed-traces - count of traces analyzed
   - :subtypes - vector of discovered subtype maps
   - :skipped - true if insufficient traces"
  [ctx sheet-id {:keys [min-traces score-threshold]
                 :or {min-traces 20 score-threshold 0.6}}]
  (let [;; Get low-scoring evaluations
        low-scoring (get-low-scoring-evaluations
                     ctx sheet-id
                     {:threshold score-threshold :limit 100})

        trace-count (count low-scoring)]

    (if (< trace-count min-traces)
      {:skipped true
       :reason "insufficient-traces"
       :found trace-count
       :required min-traces}

      (let [;; Build workflow if needed
            discovery-sheet-id (build-discovery-workflow! ctx)

            ;; Execute discovery workflow
            result (sheet/execute ctx discovery-sheet-id
                                  {"failure-traces" low-scoring})

            subtypes (get-in result [:outputs "discovered-subtypes"] [])]

        {:discovered (count subtypes)
         :analyzed-traces trace-count
         :subtypes subtypes}))))
