(ns runner
  "Shared infrastructure for running ORC RLM generalization benchmarks.

   This is a *generic* runner — it takes a task map directly and executes it.
   Per-task definitions live in their own files (e.g. risk-analysis.clj).

   Public API:
     (runner/start!)            — bring up the grain system
     (runner/run! task-map)     — execute one task, save result EDN, return result record
     (runner/run-all! task-maps) — sequentially run a list of task maps
     (runner/generate-summary! results-dir) — produce a markdown summary table
     (runner/stop!)             — tear down the system

   Run from REPL:
     (require '[runner])
     (require '[risk-analysis :as t])
     (runner/start!)
     (runner/run! t/task)
     (runner/stop!)
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
;; System State
;; =============================================================================

(defonce ^:private system-state (atom nil))

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/orc-bench-" (random-uuid))
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

(defn- stop-context [ctx]
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

(defn- load-task-documents [task]
  (reduce (fn [acc k]
            (if-let [content (load-document k)]
              (assoc acc k content)
              acc))
          {}
          (:documents task)))

;; =============================================================================
;; Task Execution
;; =============================================================================

(defn- setup-task-sheet! [ctx task documents]
  "Create a sheet for the task with appropriate inputs."
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command
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

(defn- task-slug [task]
  ;; Derive a filename slug from the task's :name field
  ;; e.g. "Risk & Obligation Analysis" -> "risk-obligation-analysis"
  ;; For consistency with prior runs, prefer explicit :slug if present
  (or (:slug task)
      (-> (:name task)
          str/lower-case
          (str/replace #"[^a-z0-9]+" "-")
          (str/replace #"^-+|-+$" ""))))

(defn- save-result! [task result]
  (let [dir (ensure-results-dir!)
        slug (task-slug task)
        filename (str slug "_"
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
    (stop-context @system-state))
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
  (reset! system-state (create-context))
  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println "  ORC RLM Benchmark Runner started")
  (println "\n" (apply str (repeat 60 "=")) "\n")
  :started)

(defn stop!
  "Stop the benchmark system."
  []
  (when @system-state
    (stop-context @system-state)
    (reset! system-state nil))
  (println "Benchmark system stopped.")
  :stopped)

(defn run!
  "Run a single task given a task map.

   The task map must contain:
     :name                 — human-readable name
     :pattern              — short description of the pattern
     :documents            — list of document keys (each maps to a file in documents/)
     :instruction          — instruction string for the RLM
     :writes               — list of output keys the task produces
     :evaluation-criteria  — list of manual evaluation criteria strings (optional)
     :slug                 — explicit filename slug (optional; derived from :name if absent)

   Returns a result record map and saves an EDN to generalization-results/."
  [task]
  (let [ctx @system-state
        _ (when-not ctx (throw (ex-info "System not started — call (runner/start!) first" {})))
        slug (task-slug task)]

    (println "\n" (apply str (repeat 70 "=")) "\n")
    (println "  TASK:" (:name task))
    (println "  Pattern:" (:pattern task))
    (println "\n" (apply str (repeat 70 "=")) "\n")

    ;; Load documents
    (println "Loading documents...")
    (let [documents (load-task-documents task)
          _ (doseq [[k v] documents]
              (println (str "  " (name k) ": " (count v) " chars")))

          ;; Setup and execute
          _ (println "\nSetting up task sheet...")
          {:keys [sheet-id]} (setup-task-sheet! ctx task documents)

          _ (println "Executing ORC RLM...")
          _ (println "(Watch stdout for [DEBUG RLM] messages)\n")
          start-time (System/currentTimeMillis)
          result (execute-task! ctx sheet-id documents (:timeout-ms config))
          duration-ms (- (System/currentTimeMillis) start-time)

          ;; Extract usage
          usage (or (:usage result) {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})

          ;; Build result record
          record (cond-> {:task slug
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
      (save-result! task record)
      (println (apply str (repeat 70 "-")))

      record)))

(defn run-all!
  "Run a list of task maps sequentially."
  [tasks]
  (println "\n" (apply str (repeat 70 "=")) "\n")
  (println "  RUNNING" (count tasks) "TASKS")
  (println "\n" (apply str (repeat 70 "=")) "\n")

  (let [results (doall
                 (for [task tasks]
                   (try
                     (run! task)
                     (catch Exception e
                       (println "ERROR running" (:name task) ":" (.getMessage e))
                       {:task-name (:name task) :status :error :error (.getMessage e)}))))]

    (println "\n\n" (apply str (repeat 70 "=")) "\n")
    (println "  ALL TASKS COMPLETE")
    (println "\n" (apply str (repeat 70 "=")) "\n")

    results))

(defn generate-summary!
  "Generate a markdown summary table from saved EDN results in the given directory."
  [results-dir]
  (let [dir (io/file results-dir)
        files (when (.exists dir)
                (filter #(.endsWith (.getName %) ".edn") (.listFiles dir)))
        results (map #(edn/read-string (slurp %)) files)
        ;; Group by task and take latest
        by-task (group-by :task results)
        latest (into {} (map (fn [[k vs]]
                              [k (last (sort-by :timestamp vs))])
                            by-task))]

    (println "\n# ORC RLM Benchmark Results\n")
    (println (str "**Generated:** " (timestamp-now) "\n"))

    (println "## Summary Table\n")
    (println "| Task | Pattern | Doc Size | Duration | Tokens | Status |")
    (println "|------|---------|----------|----------|--------|--------|")

    (doseq [[_ result] (sort-by first latest)]
      (println (format "| %s | %s | %,d chars | %.1fs | %,d | %s |"
                      (:task-name result)
                      (:pattern result)
                      (:total-chars result)
                      (/ (:duration-ms result) 1000.0)
                      (get-in result [:usage :total-tokens] 0)
                      (name (:status result)))))

    ;; Save as markdown
    (let [md-path (str results-dir "/SUMMARY.md")]
      (println (str "\nSummary saved to: " md-path))
      md-path)))
