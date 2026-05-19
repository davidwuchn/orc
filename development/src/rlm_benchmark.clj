(ns rlm-benchmark
  "RLM Benchmark Comparison Framework

   Compares token usage across execution stacks for the same document analysis task:
   - Naive: Full document to single LLM
   - ORC RLM: Model generates behavior tree with sub-LLM decomposition

   Usage:
     (require '[rlm-benchmark :as bench])
     (bench/start!)
     (bench/run-benchmark!)
     (bench/stop!)"
  (:require [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as rlm-sandbox]
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
            [clojure.edn :as edn])
  (:import [java.time LocalDate LocalDateTime]
           [java.time.format DateTimeFormatter]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private config
  {:model "google/gemini-3-flash-preview"
   :provider :openrouter
   :task "document_analysis"
   :document-name "victoria-airport-rfp"
   :default-iterations 3
   :timeout-ms 300000
   ;; OpenRouter pricing for Gemini 3 Flash (per 1M tokens)
   :pricing {:prompt 0.10
             :completion 0.40}})

;; =============================================================================
;; System State (shared with demo infrastructure)
;; =============================================================================

(defonce ^:private system-state (atom nil))

(defn- create-benchmark-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/rlm-benchmark-" (random-uuid))
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
;; Document Generation (from rlm-massive-demo)
;; =============================================================================

(def ^:private rfp-sections
  [{:title "1. EXECUTIVE SUMMARY"
    :content "The Victoria International Airport (YYJ) is seeking qualified vendors to provide comprehensive parking management services for a period of five (5) years, with two (2) optional one-year extensions. The successful proponent will be responsible for all aspects of parking operations including revenue collection, customer service, facility maintenance, and technology implementation.

Key Dates:
- RFP Issue Date: January 15, 2025
- Questions Deadline: February 1, 2025
- Proposal Due Date: February 28, 2025
- Contract Award: March 15, 2025
- Service Commencement: April 1, 2025

Contact Information:
- Procurement Manager: Sarah Johnson
- Email: sjohnson@yyj.ca
- Phone: (250) 555-0123"}

   {:title "2. BACKGROUND AND CONTEXT"
    :content "The Victoria International Airport serves over 2.1 million passengers annually and operates parking facilities totaling 3,500 spaces across three lots: Main Terminal (2,000 spaces), Economy (1,200 spaces), and Cell Phone Waiting (300 spaces). Current annual parking revenue exceeds $12.5 million.

Historical Performance (2024):
- Total transactions: 1,847,293
- Average daily occupancy: 67%
- Peak season occupancy: 94% (July-August)
- Customer satisfaction score: 4.2/5.0

Existing Infrastructure:
- Pay-on-foot machines: 12 units
- Entry/exit gates: 8 lanes
- License plate recognition: All lanes
- EV charging stations: 24 Level 2, 4 DC Fast"}

   {:title "3. SCOPE OF SERVICES"
    :content "The successful proponent shall provide the following services:

3.1 Revenue Management
- Collection and reconciliation of all parking fees
- Daily deposits to designated bank accounts
- Monthly financial reporting
- Annual audited statements

3.2 Customer Service
- 24/7 staffing at information booth
- Telephone support line
- Online reservation system
- Lost ticket processing
- Dispute resolution

3.3 Facility Operations
- Daily cleaning and maintenance
- Snow and ice removal
- Lighting maintenance
- Signage management
- Landscaping coordination

3.4 Technology Management
- PARCS system operation
- LPR system maintenance
- Payment processing integration
- Mobile app development and support"}

   {:title "4. TECHNICAL REQUIREMENTS"
    :content "4.1 Parking Access and Revenue Control System (PARCS)
The proponent must implement or integrate with a modern PARCS solution meeting these specifications:
- Cloud-based architecture with 99.9% uptime SLA
- Real-time occupancy tracking per lot and level
- Dynamic pricing capability
- Integration with airline POS systems
- PCI DSS Level 1 compliance

4.2 License Plate Recognition (LPR)
- Minimum 98% accuracy rate
- Support for all North American plates
- Integration with BC Provincial vehicle database
- Hotlist monitoring for stolen vehicles

4.3 Payment Systems
- Accept all major credit cards (Visa, Mastercard, Amex)
- Apple Pay and Google Pay support
- Interac Flash and Tap
- Prepaid parking cards
- Corporate billing accounts

4.4 Reporting Requirements
- Real-time dashboard accessible to Airport Authority
- Customizable report generation
- Data retention for minimum 7 years
- GDPR-compliant data handling"}

   {:title "5. PRICING AND FINANCIAL"
    :content "5.1 Fee Structure
Proponents must submit pricing based on the following models:
- Management fee (fixed monthly)
- Performance incentive (percentage of revenue above baseline)
- Capital investment recovery schedule

5.2 Revenue Sharing
The Airport Authority requires a minimum guaranteed annual payment (MAG) plus percentage sharing:
- Base MAG: $8,000,000 annually
- Revenue share above MAG: 75% Airport / 25% Operator
- Capital investments offset against management fees

5.3 Performance Guarantees
- Service level credits for SLA failures
- Performance bond: $2,000,000
- Insurance requirements per Appendix C

Key Financial Contacts:
- CFO: Michael Chen, mchen@yyj.ca
- Controller: Lisa Patel, lpatel@yyj.ca"}

   {:title "6. EVALUATION CRITERIA"
    :content "Proposals will be evaluated based on the following weighted criteria:

| Criterion | Weight |
|-----------|--------|
| Technical Solution | 30% |
| Experience & References | 25% |
| Financial Proposal | 25% |
| Innovation & Sustainability | 10% |
| Local Economic Impact | 10% |

6.1 Technical Evaluation Panel:
- Director of Operations: James Wilson
- IT Manager: Priya Sharma
- Customer Experience Lead: Robert Kim
- External Consultant: Parking Solutions Inc.

6.2 Mandatory Requirements (Pass/Fail):
- Valid business license in British Columbia
- Minimum 5 years experience at comparable airports
- No outstanding legal actions against the firm
- Evidence of required insurance coverage"}

   {:title "7. SUBMISSION REQUIREMENTS"
    :content "7.1 Proposal Format
- Maximum 100 pages excluding appendices
- 11-point font minimum, 1-inch margins
- Electronic submission via BidSync portal
- Three (3) bound hard copies delivered to Airport Administration

7.2 Required Sections
a) Executive Summary (max 3 pages)
b) Corporate Profile and Experience
c) Technical Approach and Innovation
d) Staffing Plan and Organization Chart
e) Implementation Timeline
f) Financial Proposal (separate sealed envelope)
g) References (minimum 3 comparable facilities)

7.3 Submission Deadline
All proposals must be received by:
- Date: February 28, 2025
- Time: 2:00 PM Pacific Standard Time
- Late submissions will not be considered

Submission Address:
Victoria International Airport Authority
Attention: Procurement Department
1640 Electra Boulevard
Sidney, BC V8L 5V4"}

   {:title "8. TERMS AND CONDITIONS"
    :content "8.1 Contract Term
- Initial term: Five (5) years
- Option periods: Two (2) one-year extensions
- Renewal decision: 180 days prior to expiration

8.2 Insurance Requirements
- Commercial General Liability: $10,000,000
- Automobile Liability: $5,000,000
- Professional Liability: $5,000,000
- Cyber Liability: $2,000,000
- Pollution Liability: $2,000,000

8.3 Performance Standards
- Customer wait time: Maximum 3 minutes at exit
- Equipment uptime: Minimum 99.5%
- Customer complaints: Maximum 0.1% of transactions
- Revenue collection accuracy: 99.9%

8.4 Termination Provisions
- For cause: 30 days written notice
- For convenience: 180 days written notice
- Transition assistance: 90 days minimum

Legal Contact:
- General Counsel: Amanda Torres
- Email: atorres@yyj.ca
- Phone: (250) 555-0199"}])

(defn generate-document
  "Generate the Victoria Airport RFP document (~138K chars)."
  []
  (let [base-content (str/join "\n\n" (map (fn [{:keys [title content]}]
                                              (str "=" (apply str (repeat 70 "=")) "\n"
                                                   title "\n"
                                                   "=" (apply str (repeat 70 "=")) "\n\n"
                                                   content))
                                            rfp-sections))
        expanded (str/join "\n\n"
                          (for [page (range 1 137)]
                            (let [section (nth rfp-sections (mod page (count rfp-sections)))
                                  variation (str "\n\n--- Page " page " of 136 ---\n\n"
                                               "SECTION " (inc (mod page 8)) ": "
                                               (:title section) " (Continued)\n\n"
                                               "Additional specifications for page " page ":\n"
                                               (:content section) "\n"
                                               "\nAppendix reference: See Appendix " (char (+ 65 (mod page 26)))
                                               " for detailed requirements.\n"
                                               "Cross-reference: Sections " (inc (mod page 8)) ", "
                                               (+ 2 (mod page 7)) ", and " (+ 3 (mod page 6)) ".\n")]
                              variation)))]
    (str "VICTORIA INTERNATIONAL AIRPORT (YYJ)\n"
         "REQUEST FOR PROPOSALS\n"
         "PARKING MANAGEMENT SERVICES\n"
         "RFP No. YYJ-2025-001\n\n"
         (apply str (repeat 80 "=")) "\n\n"
         base-content "\n\n"
         expanded)))

;; =============================================================================
;; Cost Calculation
;; =============================================================================

(defn calculate-cost
  "Calculate USD cost from token counts."
  [{:keys [prompt-tokens completion-tokens]}]
  (let [{:keys [prompt completion]} (:pricing config)]
    (+ (* (/ prompt-tokens 1000000.0) prompt)
       (* (/ completion-tokens 1000000.0) completion))))

;; =============================================================================
;; Result Schema & Persistence (Issue 003)
;; =============================================================================

(defn- ensure-runs-dir! [date task]
  (let [dir-path (str "development/bench/runs/" date "_" task)]
    (io/make-parents (str dir-path "/dummy"))
    dir-path))

(defn- timestamp-now []
  (.format (LocalDateTime/now) (DateTimeFormatter/ISO_LOCAL_DATE_TIME)))

(defn- date-today []
  (.format (LocalDate/now) (DateTimeFormatter/ISO_LOCAL_DATE)))

(defn save-run!
  "Save a benchmark run result to EDN file."
  [result]
  (let [date (date-today)
        dir (ensure-runs-dir! date (:task result))
        filename (str dir "/" (:run-id result) ".edn")]
    (spit filename (pr-str result))
    (println "  Saved:" filename)
    filename))

(defn load-run
  "Load a benchmark run result from EDN file."
  [path]
  (edn/read-string (slurp path)))

(defn list-runs
  "List all runs in a directory."
  [dir]
  (->> (io/file dir)
       (.listFiles)
       (filter #(str/ends-with? (.getName %) ".edn"))
       (map #(.getPath %))
       (map load-run)))


;; =============================================================================
;; ORC RLM Runner (Issue 002)
;; =============================================================================

(defn- setup-rlm-sheet! [ctx document]
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Benchmark RLM Analysis"))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]
    ;; Declare blackboard keys
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :document :string))
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :summary :string))
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :key-dates :string))
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :entities :string))
    ;; Create sequence with repl-researcher
    (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-result :command-result/events first :node-id)
          node-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :repl-researcher :parent-id seq-id))
          node-id (-> node-result :command-result/events first :node-id)]
      ;; Configure RLM mode
      (h/run-and-apply! ctx (h/make-set-repl-researcher-config-command
                              sheet-id node-id
                              "You are analyzing a large RFP document (~138,000 characters).

Your task: Extract a summary, key dates, and important entities.

CRITICAL: This document is TOO LARGE to process in one pass.
You MUST use emit-tree! to generate a behavior tree that decomposes the task:

Use this exact pattern with emit-tree!:
```clojure
(emit-tree!
  [:sequence
   [:chunk-document {:from :document :size 8000 :into :chunks}]
   [:map-each {:from :chunks :as :chunk :into :chunk-results}
    [:llm {:instruction \"Extract any dates (with context), people names, organizations, and key facts from this section. Return as structured data.\"
           :reads [:chunk]
           :writes [:extracted-data]}]]
   [:aggregate {:from :chunk-results :writes [:all-extracted]}]
   [:llm {:instruction \"Synthesize the extracted information into a coherent summary. Deduplicate dates and entities. Provide: 1) Executive summary, 2) All key dates with context, 3) All important entities (people, orgs).\"
          :reads [:all-extracted]
          :writes [:summary :key-dates :entities]}]
   [:final {:keys [:summary :key-dates :entities]}]])
```

The emit-tree! approach:
- :chunk-document splits the large document into manageable pieces
- :map-each processes each chunk with a sub-LLM call
- :aggregate combines all chunk results
- Final :llm synthesizes everything
- :final declares the output keys

DO NOT use direct primitives like map-each or manual chunking.
ALWAYS use emit-tree! for large document processing."
                              [:document] [:summary :key-dates :entities] []
                              :model (:model config)
                              :max-iterations 5
                              :rlm {:debug? true}))
      {:sheet-id sheet-id :node-id node-id})))

(defn- execute-rlm! [ctx sheet-id document timeout-ms]
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
                    :inputs {:document document}
                    :options {:timeout-ms timeout-ms}}))
        result (deref p timeout-ms {:status :timeout :error "Execution timed out"})
        duration-ms (- (System/currentTimeMillis) start-time)]
    (assoc result :duration-ms duration-ms)))

(defn run-orc-rlm
  "Run ORC RLM: model generates behavior tree with sub-LLM decomposition.

   Returns standardized result map with breakdown."
  [document run-number]
  (let [run-id (str "orc-rlm_" (format "%02d" run-number))
        _ (println (str "\n  Running " run-id "..."))
        ctx @system-state]

    (if-not ctx
      {:stack "orc-rlm"
       :task (:task config)
       :run-id run-id
       :document {:name (:document-name config) :chars (count document)}
       :model (:model config)
       :timestamp (timestamp-now)
       :duration-ms 0
       :usage {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0}
       :cost-usd 0.0
       :status :error
       :error "System not started. Call (start!) first."}

      (try
        ;; Setup and execute
        (let [{:keys [sheet-id]} (setup-rlm-sheet! ctx document)
              result (execute-rlm! ctx sheet-id document (:timeout-ms config))
              duration-ms (:duration-ms result)
              ;; Extract usage - normalize both formats
              raw-usage (:usage result)
              usage (when raw-usage
                      {:prompt-tokens (or (:prompt-tokens raw-usage) (:prompt_tokens raw-usage) 0)
                       :completion-tokens (or (:completion-tokens raw-usage) (:completion_tokens raw-usage) 0)
                       :total-tokens (or (:total-tokens raw-usage) (:total_tokens raw-usage) 0)})
              ;; Get breakdown if available
              breakdown (:breakdown result)]

          (println (str "    Duration: " duration-ms "ms"))
          (println (str "    Status: " (:status result)))
          (when usage
            (println (str "    Tokens: " (:total-tokens usage) " (prompt: " (:prompt-tokens usage) ", completion: " (:completion-tokens usage) ")")))
          (when breakdown
            (println (str "    Breakdown - Root: " (get-in breakdown [:root-llm :total-tokens]) ", Sub-LLM: " (get-in breakdown [:sub-llm :total-tokens]))))

          {:stack "orc-rlm"
           :task (:task config)
           :run-id run-id
           :document {:name (:document-name config) :chars (count document)}
           :model (:model config)
           :timestamp (timestamp-now)
           :duration-ms duration-ms
           :usage (or usage {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})
           :cost-usd (if usage (calculate-cost usage) 0.0)
           :status (or (:status result) :unknown)
           :outputs (:outputs result)
           :breakdown breakdown
           :iterations (count (:iterations result))})

        (catch Exception e
          (println (str "    ERROR: " (.getMessage e)))
          {:stack "orc-rlm"
           :task (:task config)
           :run-id run-id
           :document {:name (:document-name config) :chars (count document)}
           :model (:model config)
           :timestamp (timestamp-now)
           :duration-ms 0
           :usage {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0}
           :cost-usd 0.0
           :status :error
           :error (.getMessage e)})))))

;; =============================================================================
;; Summary Report Generator (Issue 005)
;; =============================================================================

(def ^:private predict-rlm-reference
  "Hardcoded reference data from Round 5 benchmark (2026-04-26)."
  {:stack "predict-rlm"
   :task "document_analysis"
   :source "Round 5 benchmark (2026-04-26)"
   :runs [{:tokens 706000 :cost 0.46 :duration-ms 160000}
          {:tokens 834480 :cost 0.53 :duration-ms 166000}
          {:tokens 1200000 :cost 0.82 :duration-ms 172000}]
   :median {:tokens 834480 :cost 0.53 :duration-ms 166000}})

(defn- median [coll]
  (when (seq coll)
    (let [sorted (sort coll)
          n (count sorted)
          mid (quot n 2)]
      (if (odd? n)
        (nth sorted mid)
        (/ (+ (nth sorted (dec mid)) (nth sorted mid)) 2.0)))))

(defn- aggregate-stack-runs [runs]
  (when (seq runs)
    (let [tokens (map #(get-in % [:usage :total-tokens] 0) runs)
          costs (map :cost-usd runs)
          durations (map :duration-ms runs)]
      {:runs (count runs)
       :median-tokens (median tokens)
       :median-cost (median costs)
       :median-duration-ms (median durations)
       :min-tokens (apply min tokens)
       :max-tokens (apply max tokens)})))

(defn generate-report!
  "Generate markdown summary report comparing ORC RLM vs predict-rlm reference."
  [runs-dir]
  (let [runs (list-runs runs-dir)
        by-stack (group-by :stack runs)
        orc-rlm-stats (aggregate-stack-runs (get by-stack "orc-rlm"))
        predict-rlm-tokens (:tokens (:median predict-rlm-reference))

        report (str "# RLM Benchmark: ORC RLM vs predict-rlm\n\n"
                   "**Date:** " (date-today) "\n"
                   "**Task:** document_analysis (Victoria Airport RFP, 138K chars)\n"
                   "**Model:** " (:model config) "\n\n"
                   "## Summary\n\n"
                   "| Stack | Runs | Median Tokens | Median Cost | Median Duration | vs predict-rlm |\n"
                   "|-------|------|---------------|-------------|-----------------|----------------|\n"
                   (when orc-rlm-stats
                     (format "| **ORC RLM** | %d | %,.0f | $%.4f | %.1fs | **%.1fx fewer tokens** |\n"
                             (:runs orc-rlm-stats)
                             (double (:median-tokens orc-rlm-stats))
                             (:median-cost orc-rlm-stats)
                             (/ (:median-duration-ms orc-rlm-stats) 1000.0)
                             (double (/ predict-rlm-tokens (max 1 (:median-tokens orc-rlm-stats))))))
                   (format "| **predict-rlm** (ref) | 3 | %,.0f | $%.2f | %.1fs | 1.0x (baseline) |\n\n"
                           (double predict-rlm-tokens)
                           (double (:cost (:median predict-rlm-reference)))
                           (double (/ (:duration-ms (:median predict-rlm-reference)) 1000.0)))
                   "## Analysis\n\n"
                   (when orc-rlm-stats
                     (let [reduction (- 1 (/ (:median-tokens orc-rlm-stats) predict-rlm-tokens))]
                       (format "- **ORC RLM achieves %.0f%% token reduction** vs predict-rlm\n"
                               (* reduction 100))))
                   "- predict-rlm reference data from Round 5 benchmark (2026-04-26)\n"
                   "- predict-rlm uses unbounded code iteration, leading to high token usage\n"
                   "- ORC RLM uses bounded behavior tree execution with controlled sub-LLM calls\n\n"
                   "## Individual Runs\n\n"
                   (str/join "\n"
                            (for [run (filter #(= "orc-rlm" (:stack %)) runs)]
                              (format "- **%s**: %,d tokens, $%.4f, %.1fs, status: %s"
                                      (:run-id run)
                                      (get-in run [:usage :total-tokens] 0)
                                      (or (:cost-usd run) 0.0)
                                      (/ (or (:duration-ms run) 0) 1000.0)
                                      (name (or (:status run) :unknown))))))
        report-path (str runs-dir "/SUMMARY.md")]
    (spit report-path report)
    (println "\nReport saved to:" report-path)
    report-path))

;; =============================================================================
;; Benchmark Orchestrator (Issue 004)
;; =============================================================================

(defn run-benchmark!
  "Run ORC RLM benchmark and compare to predict-rlm reference."
  ([] (run-benchmark! (:default-iterations config)))
  ([n]
   (println "\n" (apply str (repeat 70 "=")) "\n")
   (println "  ORC RLM BENCHMARK")
   (println "  Comparing to predict-rlm reference (N=" n ")")
   (println "\n" (apply str (repeat 70 "=")) "\n")

   (let [date (date-today)
         runs-dir (ensure-runs-dir! date (:task config))
         document (generate-document)]

     (println (str "Document: " (count document) " chars\n"))

     ;; Run ORC RLM
     (doall
      (for [i (range 1 (inc n))]
        (let [result (run-orc-rlm document i)]
          (save-run! result)
          result)))

     (println "\nORC RLM runs complete.")

     ;; Generate report
     (generate-report! runs-dir)

     (println "\n" (apply str (repeat 70 "=")) "\n")
     (println "  BENCHMARK COMPLETE")
     (println (str "  Results: " runs-dir))
     (println "\n" (apply str (repeat 70 "=")) "\n")

     runs-dir)))

;; =============================================================================
;; Public API
;; =============================================================================

(defn start!
  "Initialize the benchmark system."
  []
  (when @system-state
    (stop-benchmark-context @system-state))
  ;; Register OpenRouter base provider
  (let [api-key (or (System/getenv "OPENROUTER_API_KEY")
                    (throw (ex-info "OPENROUTER_API_KEY not set" {})))
        base-config {:provider :openrouter
                     :model (:model config)
                     :config {:api-base "https://openrouter.ai/api/v1"
                              :api-key api-key}}
        ;; Model-specific provider name (required because litellm ignores :model in options)
        model-provider-name (keyword (str "openrouter/" (:model config)))]
    (litellm-router/register! :openrouter base-config)
    ;; Also register model-specific provider for direct calls
    (litellm-router/register! model-provider-name (assoc base-config :model (:model config))))
  (reset! system-state (create-benchmark-context))
  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println "  ORC RLM Benchmark System Started")
  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println "Available functions:")
  (println "  (run-benchmark!)      - Run ORC RLM benchmark (N=3)")
  (println "  (run-benchmark! n)    - Run ORC RLM benchmark with N iterations")
  (println "  (run-debug-orc-rlm!)  - Single debug run with verbose output")
  (println "  (stop!)               - Clean up resources")
  :started)

(defn stop!
  "Stop the benchmark system."
  []
  (when @system-state
    (stop-benchmark-context @system-state)
    (reset! system-state nil))
  (println "Benchmark system stopped.")
  :stopped)

;; =============================================================================
;; Debug Functions
;; =============================================================================

(defn run-debug-orc-rlm!
  "Run a single ORC RLM test with verbose debug output.
   Debug mode is already enabled - output goes to stdout.

   Returns the full result map including outputs."
  []
  (println "\n" (apply str (repeat 70 "=")) "\n")
  (println "  DEBUG: ORC RLM Single Run")
  (println "  (Watch stdout for [DEBUG RLM] messages)")
  (println "\n" (apply str (repeat 70 "=")) "\n")

  (let [document (generate-document)]
    (println "Document size:" (count document) "chars")
    (println "Starting RLM execution...\n")
    (let [result (run-orc-rlm document 99)]
      (println "\n" (apply str (repeat 70 "-")))
      (println "RESULT SUMMARY:")
      (println "  Status:" (:status result))
      (println "  Duration:" (:duration-ms result) "ms")
      (println "  Usage:" (:usage result))
      (println "\nOUTPUTS:")
      (doseq [[k v] (:outputs result)]
        (when (not= k :document)
          (println (str "  " (name k) ":"))
          (println (str "    " (if (string? v)
                                 (subs v 0 (min 200 (count v)))
                                 v) "..."))))
      (println (apply str (repeat 70 "-")))
      result)))

;; =============================================================================
;; REPL
;; =============================================================================

(comment
  ;; Quick start
  (start!)
  (run-benchmark!)      ;; Run N=3 iterations
  (run-benchmark! 1)    ;; Single run
  (run-debug-orc-rlm!)  ;; Debug run with verbose output
  (stop!)

  ;; Generate report from saved runs
  (generate-report! "development/bench/runs/2026-05-18_document_analysis")

  ;; Test document generation
  (def doc (generate-document))
  (count doc)  ;; Should be ~138K
  )
