(ns rlm-gen-bench
  "ORC RLM Generalization Benchmark

   Tests ORC RLM across fundamentally different task patterns to prove
   it generalizes beyond a single use case.

   Tasks:
   1. Risk & Obligation Analysis - Analytical reasoning on single large doc
   2. Contract Comparison - Cross-document diff analysis
   3. Multi-Document Reconciliation - Cross-reference validation

   Run from REPL:
     (require '[rlm-gen-bench :as bench])
     (bench/start!)
     (bench/run-all-tasks!)
     (bench/generate-summary-table!)
     (bench/stop!)
  "
  (:require [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]
            [litellm.router :as litellm-router]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def config
  {:model "google/gemini-3-flash-preview"
   :timeout-ms 600000  ;; 10 minutes for complex tasks
   :documents-dir "development/bench/documents"
   :results-dir "development/bench/generalization-results"})

;; =============================================================================
;; Task Definitions
;; =============================================================================

(def tasks
  {:document-analysis
   {:name "Document Analysis (Extraction)"
    :pattern "Extraction (single doc -> summary/dates/entities)"
    :documents [:yyj_rfp]
    :description "Extract summary, key dates, and entities from a large RFP document."
    :instruction "Document available: :yyj_rfp (large RFP, ~280K chars)

Task: Produce a structured extraction of this RFP with three outputs:
- :summary - Executive summary of the RFP
- :key-dates - All important dates with their context (issue date, deadlines, milestones)
- :entities - Important entities mentioned (people with their roles, organizations, contact info)

Quality requirements: Information must be accurate and traceable to the source. Do not invent dates, people, or organizations not present in the document."
    :writes [:summary :key-dates :entities]
    :evaluation-criteria
    ["Identifies RFP issue/response dates"
     "Extracts contact information (Procurement Manager, CFO, etc.)"
     "Identifies key organizations (VAA, LDC, etc.)"
     "Captures monetary thresholds (revenue figures, MAG, etc.)"
     "Identifies key facility specifications (parking spaces, occupancy)"
     "Does NOT hallucinate dates or entities not in the document"]}

   :risk-analysis
   {:name "Risk & Obligation Analysis"
    :pattern "Analytical reasoning (single doc -> classified output)"
    :documents [:yyj_rfp]
    :description "Analyze a large RFP document to identify and classify all contractual obligations, penalties, and risks."
    :instruction "Document available: :yyj_rfp (large RFP, ~280K chars)

Task: Perform a comprehensive RISK AND OBLIGATION ANALYSIS from the bidder's perspective.

This is analytical work, not extraction. Reason about risks and consequences, do not just list clauses.

Produce four outputs:
- :obligations - What the bidder must do, classified by type (Financial / Operational / Compliance / Reporting / Timeline)
- :penalties - Consequences for non-compliance (termination triggers, financial penalties, disqualification criteria) with specific clause references
- :risk-matrix - Each major obligation mapped to a risk level (HIGH/MEDIUM/LOW) with reasoning about difficulty and consequence severity
- :executive-summary - Top strategic concerns and recommendations for the bidder

Quality requirements: Risk classifications must be justified by document content. Do not invent obligations or penalties not in the document."
    :writes [:obligations :penalties :risk-matrix :executive-summary]
    :evaluation-criteria
    ["Identifies mandatory pre-bid meeting requirement (disqualification if missed)"
     "Identifies Letter of Credit requirement (5% of management fee)"
     "Identifies insurance requirements (specific coverage amounts)"
     "Identifies WorkSafeBC registration requirement"
     "Identifies proposal validity period (60 days)"
     "Classifies financial vs operational vs compliance obligations"
     "Identifies contract termination triggers"
     "Provides risk assessment with HIGH/MEDIUM/LOW ratings"
     "Does NOT hallucinate obligations not in the document"]}

   :contract-comparison
   {:name "Contract Comparison"
    :pattern "Cross-document diff (two docs -> delta report)"
    :documents [:contract_v2 :contract_v3]
    :description "Compare two versions of a legal contract and identify all differences with impact analysis."
    :instruction "Documents available:
- :contract_v2 - Ontario microFIT Contract Version 2.0 (56K chars)
- :contract_v3 - Ontario microFIT Contract Version 3.1.1 (49K chars)

Task: Compare these two contract versions and identify what changed between them.

Produce five outputs:
- :document-survey - Structure overview of both documents (sections, appendices)
- :section-diffs - Specific additions, removals, and modifications with exact section/clause references
- :major-changes - Significant changes classified as MAJOR (legal/financial impact) vs MINOR (clarification) vs COSMETIC (formatting)
- :impact-analysis - Who is affected by each change (Supplier vs OPA) and how risk has shifted
- :executive-summary - High-level overview of how the contract evolved between versions

Quality requirements:
- Every claimed difference must cite a specific section number or clause text
- If a section is essentially identical between versions, say so explicitly rather than inventing differences
- Do not assume changes based on general knowledge about microFIT contracts - rely only on what is actually present in the documents"
    :writes [:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]
    :evaluation-criteria
    ["Correctly identifies both documents as microFIT contracts"
     "Notes version numbers (2.0 vs 3.1.1)"
     "Identifies page count difference (23 vs 22 pages)"
     "Finds Ministerial Direction reference addition in v3.1.1"
     "Finds anti-splitting provisions added in v3.1.1"
     "Finds domestic content changes between versions"
     "Finds battery backup restriction changes"
     "Classifies changes as MAJOR/MINOR appropriately"
     "Identifies which party benefits from changes"
     "Does NOT hallucinate differences that don't exist"]}

   :legal-issue-detection
   {:name "Legal Issue Detection"
    :pattern "Analytical reasoning on SMALL document (7K chars)"
    :documents [:employment_agreement]
    :description "Analyze a small employment agreement for potential issues, ambiguities, and missing protections."
    :instruction "Document available: :employment_agreement (small employment contract, ~7K chars)

Task: Review this employment agreement from the EMPLOYEE'S perspective.

Produce four outputs:
- :issues - Potential problems or concerns for the employee (broad scope clauses, unfavorable terms, etc.)
- :ambiguities - Unclear or ambiguous language that could be interpreted unfavorably
- :missing - Standard protections or clauses that are NOT included in this agreement
- :recommendations - Suggested negotiation points before signing

Quality requirements: All issues must be traceable to specific clause text. Do not invent problems not in the document."
    :writes [:issues :ambiguities :missing :recommendations]
    :evaluation-criteria
    ["Identifies non-compete clause scope concerns"
     "Notes confidentiality obligations extent"
     "Identifies termination notice period adequacy"
     "Notes bonus/incentive discretionary language"
     "Identifies intellectual property assignment scope"
     "Notes any missing standard protections"
     "Provides actionable negotiation recommendations"
     "Does NOT hallucinate issues not in the document"]}

   :contract-comparison-validated
   {:name "Contract Comparison (with Adversarial Validation Requirement)"
    :pattern "Cross-document diff with quality requirement: every claim must be adversarially validated"
    :documents [:contract_v2 :contract_v3]
    :description "Compare two contract versions. Quality bar: every claim must survive adversarial validation against source text."
    :instruction "Documents available:
- :contract_v2 - Ontario microFIT Contract Version 2.0 (56K chars)
- :contract_v3 - Ontario microFIT Contract Version 3.1.1 (49K chars)

Task: Compare these two contract versions and identify what changed between them.

Produce five outputs:
- :document-survey - Structure overview of both documents (sections, appendices)
- :section-diffs - Specific additions, removals, and modifications with exact section/clause references
- :major-changes - Significant changes classified as MAJOR (legal/financial impact) vs MINOR (clarification) vs COSMETIC (formatting)
- :impact-analysis - Who is affected by each change (Supplier vs OPA) and how risk has shifted
- :executive-summary - High-level overview of how the contract evolved between versions

QUALITY REQUIREMENT — ADVERSARIAL VALIDATION:
Every claim in your final outputs must survive adversarial validation. An adversarial validator is an antagonistic reviewer whose job is to disprove each claim by examining the source text. A claim that cannot be defended with exact text from the source documents must NOT appear in your final outputs.

In other words: if an antagonistic reviewer cannot find textual evidence for a claim in :contract_v2 and :contract_v3, that claim is invalid and should be excluded. Your final outputs should be the SURVIVING claims after adversarial scrutiny.

Other quality requirements:
- Every claimed difference must cite a specific section number or clause text
- If a section is essentially identical between versions, say so explicitly rather than inventing differences
- Do not rely on general knowledge about microFIT contracts - only on what is actually present in the documents
- Prefer fewer verified claims over many unverified ones"
    :writes [:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]
    :evaluation-criteria
    ["Tree designed by model includes some form of validation/verification stage (observe what shape it takes)"
     "Final claims include citations or text quotes from source"
     "No fabricated 'Deemed Single Property' addition"
     "No fabricated 'Anti-splitting Warranty' addition"
     "No fabricated 'Section 6.6 new in v3' claim"
     "Domestic Content removal correctly identified"
     "Section 6.5 emergency planning correctly identified as v3 addition"
     "Tree pattern is observably different from G08 baseline (does the model add structure for validation?)"]}})

;; =============================================================================
;; System State
;; =============================================================================

(defonce ^:private system-state (atom nil))

(defn- create-benchmark-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/rlm-gen-bench-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "bench"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :dscloj-provider :openrouter
                  ::cache-dir cache-dir}
        processors (reduce-kv
                    (fn [acc proc-name {:keys [handler-fn topics]}]
                      (assoc acc proc-name
                             (tp/start {:event-pubsub ps
                                        :topics topics
                                        :handler-fn handler-fn
                                        :context base-ctx})))
                    {}
                    @tp/processor-registry*)]
    (assoc base-ctx :event-pubsub ps :processors processors)))

(defn- stop-benchmark-context [ctx]
  (doseq [[_ processor] (:processors ctx)] (tp/stop processor))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [cache (:cache ctx)] (kv/stop cache))
  (when-let [event-store (:event-store ctx)] (es/stop event-store))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [child (.listFiles f)] (.delete child))
        (.delete f)))))

;; =============================================================================
;; Document Loading
;; =============================================================================

(defn- load-document [doc-key]
  (let [path (str (:documents-dir config) "/" (name doc-key) ".txt")]
    (when (.exists (io/file path))
      (slurp path))))

(defn- load-task-documents [task-key]
  (let [task (get tasks task-key)
        doc-keys (:documents task)]
    (reduce (fn [acc k]
              (if-let [content (load-document k)]
                (assoc acc k content)
                acc))
            {}
            doc-keys)))

;; =============================================================================
;; Task Execution
;; =============================================================================

(defn- setup-task-sheet! [ctx task-key documents]
  "Create a sheet for the task with appropriate inputs."
  (let [task (get tasks task-key)
        sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command
                                            :name (str "Task: " (:name task))))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]

    ;; Declare document keys based on task
    (doseq [[doc-key _] documents]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id doc-key :string)))

    ;; Declare output keys
    (doseq [write-key (:writes task)]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id write-key :string)))

    ;; Create repl-researcher node
    (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-result :command-result/events first :node-id)
          node-result (h/run-and-apply! ctx (h/make-create-node-command
                                             sheet-id :repl-researcher :parent-id seq-id))
          node-id (-> node-result :command-result/events first :node-id)]

      ;; Configure RLM mode
      (h/run-and-apply! ctx (h/make-set-repl-researcher-config-command
                              sheet-id node-id
                              (:instruction task)
                              (vec (keys documents))
                              (:writes task)
                              []
                              :model (:model config)
                              :max-iterations 5
                              :rlm {:debug? true}))
      {:sheet-id sheet-id :node-id node-id})))

(defn- execute-task! [ctx sheet-id documents timeout-ms]
  (let [tick-id (random-uuid)
        p (runtime/register-completion! tick-id)
        start-time (System/currentTimeMillis)
        _ (cp/process-command
            (assoc ctx :command
                   {:command/id (random-uuid)
                    :command/timestamp (time/now)
                    :command/name :sheet/tick-tree
                    :sheet-id sheet-id
                    :tick-id tick-id
                    :inputs documents
                    :options {:timeout-ms timeout-ms}}))
        result (deref p timeout-ms {:status :timeout :error "Execution timed out"})
        duration-ms (- (System/currentTimeMillis) start-time)]
    (assoc result :duration-ms duration-ms)))

;; =============================================================================
;; Result Management
;; =============================================================================

(defn- ensure-results-dir! []
  (let [dir (io/file (:results-dir config))]
    (when-not (.exists dir)
      (.mkdirs dir))
    (.getPath dir)))

(defn- timestamp-now []
  (.format (java.time.ZonedDateTime/now)
           (java.time.format.DateTimeFormatter/ISO_INSTANT)))

(defn- save-result! [task-key result]
  (let [dir (ensure-results-dir!)
        filename (str (name task-key) "_"
                     (.format (java.time.LocalDateTime/now)
                              (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HHmmss"))
                     ".edn")
        path (str dir "/" filename)]
    (spit path (pr-str result))
    (println "  Saved:" path)
    path))

;; =============================================================================
;; Public API
;; =============================================================================

(defn start!
  "Initialize the benchmark system."
  []
  (when @system-state
    (stop-benchmark-context @system-state))
  ;; Register OpenRouter
  (let [api-key (or (System/getenv "OPENROUTER_API_KEY")
                    (throw (ex-info "OPENROUTER_API_KEY not set" {})))
        base-config {:provider :openrouter
                     :model (:model config)
                     :config {:api-base "https://openrouter.ai/api/v1"
                              :api-key api-key}}]
    (litellm-router/register! :openrouter base-config)
    (litellm-router/register! (keyword (str "openrouter/" (:model config)))
                              (assoc base-config :model (:model config))))
  (reset! system-state (create-benchmark-context))
  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println "  ORC RLM Generalization Benchmark")
  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println "Available tasks:")
  (doseq [[k task] tasks]
    (println (str "  " (name k) " - " (:name task))))
  (println "\nCommands:")
  (println "  (run-task! :task-key)   - Run single task")
  (println "  (run-all-tasks!)        - Run all tasks")
  (println "  (generate-summary-table!) - Generate comparison table")
  (println "  (stop!)                 - Clean up")
  :started)

(defn stop!
  "Stop the benchmark system."
  []
  (when @system-state
    (stop-benchmark-context @system-state)
    (reset! system-state nil))
  (println "Benchmark system stopped.")
  :stopped)

(defn run-task!
  "Run a single task and save results."
  [task-key]
  (let [task (get tasks task-key)
        _ (when-not task (throw (ex-info "Unknown task" {:task task-key})))
        ctx @system-state
        _ (when-not ctx (throw (ex-info "System not started" {})))]

    (println "\n" (apply str (repeat 70 "=")) "\n")
    (println "  TASK:" (:name task))
    (println "  Pattern:" (:pattern task))
    (println "\n" (apply str (repeat 70 "=")) "\n")

    ;; Load documents
    (println "Loading documents...")
    (let [documents (load-task-documents task-key)
          _ (doseq [[k v] documents]
              (println (str "  " (name k) ": " (count v) " chars")))

          ;; Setup and execute
          _ (println "\nSetting up task sheet...")
          {:keys [sheet-id]} (setup-task-sheet! ctx task-key documents)

          _ (println "Executing ORC RLM...")
          _ (println "(Watch stdout for [DEBUG RLM] messages)\n")
          start-time (System/currentTimeMillis)
          result (execute-task! ctx sheet-id documents (:timeout-ms config))
          duration-ms (- (System/currentTimeMillis) start-time)

          ;; Extract usage
          usage (or (:usage result) {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})

          ;; Build result record
          record (cond-> {:task (name task-key)
                          :task-name (:name task)
                          :pattern (:pattern task)
                          :timestamp (timestamp-now)
                          :documents (into {} (map (fn [[k v]] [k {:chars (count v)}]) documents))
                          :total-chars (reduce + (map (fn [[_ v]] (count v)) documents))
                          :duration-ms duration-ms
                          :status (:status result)
                          :usage usage
                          :outputs (:outputs result)
                          :evaluation-criteria (:evaluation-criteria task)}
                   (:error result) (assoc :error (:error result))
                   (:phase2-error result) (assoc :phase2-error (:phase2-error result))
                   (:generated-tree-raw result) (assoc :generated-tree-raw (:generated-tree-raw result)))]

      ;; Print summary
      (println "\n" (apply str (repeat 70 "-")))
      (println "RESULT SUMMARY:")
      (println "  Status:" (:status result))
      (println "  Duration:" (format "%.1f" (/ duration-ms 1000.0)) "seconds")
      (println "  Tokens:" (:total-tokens usage)
               (str "(prompt: " (:prompt-tokens usage)
                    ", completion: " (:completion-tokens usage) ")"))
      (when (:error result)
        (println "  ERROR:" (:error result)))
      (when (:phase2-error result)
        (println "  PHASE 2 ERROR:" (:phase2-error result)))

      ;; Print generated tree if present
      (when-let [tree-raw (get-in result [:outputs :generated-tree-raw])]
        (println "\nGENERATED TREE (raw S-expr):")
        (pprint/pprint tree-raw))

      ;; Print outputs preview
      (println "\nOUTPUTS PREVIEW:")
      (doseq [[k v] (:outputs result)]
        (when (and (string? v) (not= k :generated-tree-raw))
          (println (str "  " (name k) ": "
                       (subs v 0 (min 150 (count v)))
                       (when (> (count v) 150) "...")))))

      ;; Print evaluation criteria
      (println "\nEVALUATION CRITERIA (manual check):")
      (doseq [criterion (:evaluation-criteria task)]
        (println (str "  [ ] " criterion)))

      ;; Save result
      (println)
      (save-result! task-key record)
      (println (apply str (repeat 70 "-")))

      record)))

(defn run-all-tasks!
  "Run all tasks sequentially."
  []
  (println "\n" (apply str (repeat 70 "=")) "\n")
  (println "  RUNNING ALL GENERALIZATION TASKS")
  (println "\n" (apply str (repeat 70 "=")) "\n")

  (let [results (doall
                 (for [task-key (keys tasks)]
                   (try
                     (run-task! task-key)
                     (catch Exception e
                       (println "ERROR running" task-key ":" (.getMessage e))
                       {:task (name task-key) :status :error :error (.getMessage e)}))))]

    (println "\n\n" (apply str (repeat 70 "=")) "\n")
    (println "  ALL TASKS COMPLETE")
    (println "\n" (apply str (repeat 70 "=")) "\n")

    results))

(defn generate-summary-table!
  "Generate a markdown summary table from saved results."
  []
  (let [dir (io/file (:results-dir config))
        files (when (.exists dir)
                (filter #(.endsWith (.getName %) ".edn") (.listFiles dir)))
        results (map #(edn/read-string (slurp %)) files)
        ;; Group by task and take latest
        by-task (group-by :task results)
        latest (into {} (map (fn [[k vs]]
                              [k (last (sort-by :timestamp vs))])
                            by-task))]

    (println "\n# ORC RLM Generalization Benchmark Results\n")
    (println (str "**Generated:** " (timestamp-now) "\n"))

    (println "## Summary Table\n")
    (println "| Task | Pattern | Doc Size | Duration | Tokens | Status |")
    (println "|------|---------|----------|----------|--------|--------|")

    (doseq [[task-name result] (sort-by first latest)]
      (println (format "| %s | %s | %,d chars | %.1fs | %,d | %s |"
                      (:task-name result)
                      (:pattern result)
                      (:total-chars result)
                      (/ (:duration-ms result) 1000.0)
                      (get-in result [:usage :total-tokens] 0)
                      (name (:status result)))))

    (println "\n## Task Details\n")

    (doseq [[task-name result] (sort-by first latest)]
      (println (str "### " (:task-name result) "\n"))
      (println (str "**Pattern:** " (:pattern result) "\n"))
      (println (str "**Documents:** "
                   (str/join ", " (map (fn [[k v]] (str (name k) " (" (:chars v) " chars)"))
                                      (:documents result))) "\n"))
      (println (str "**Status:** " (name (:status result)) "\n"))
      (println (str "**Duration:** " (format "%.1f seconds" (/ (:duration-ms result) 1000.0)) "\n"))
      (println (str "**Tokens:** " (get-in result [:usage :total-tokens] 0) "\n"))

      (println "**Outputs:**\n")
      (doseq [[k v] (:outputs result)]
        (when (string? v)
          (println (str "- **" (name k) "**: " (subs v 0 (min 200 (count v)))
                       (when (> (count v) 200) "...") "\n"))))

      (println "**Evaluation Criteria:**\n")
      (doseq [criterion (:evaluation-criteria result)]
        (println (str "- [ ] " criterion)))
      (println))

    ;; Save as markdown
    (let [md-path (str (:results-dir config) "/SUMMARY.md")]
      (println (str "\nSummary saved to: " md-path))
      md-path)))

;; =============================================================================
;; REPL
;; =============================================================================

(comment
  ;; Quick start
  (start!)

  ;; Run individual tasks
  (run-task! :risk-analysis)
  (run-task! :contract-comparison)
  (run-task! :legal-issue-detection)

  ;; Run all tasks
  (run-all-tasks!)

  ;; Generate summary
  (generate-summary-table!)

  (stop!)
  )
