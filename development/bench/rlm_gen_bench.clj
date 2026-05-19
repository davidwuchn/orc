(ns rlm-gen-bench
  "ORC RLM Generalization Benchmark — compatibility wrapper.

   This namespace preserves the original public API while delegating
   actual execution to `runner` and pulling task definitions from the
   individual per-task namespaces. New code should require `runner` and
   the per-task namespaces directly.

   Tasks (each in its own file):
   - document-analysis
   - risk-analysis
   - contract-comparison
   - contract-comparison-validated
   - legal-issue-detection

   Run from REPL:
     (require '[rlm-gen-bench :as bench])
     (bench/start!)
     (bench/run-all-tasks!)
     (bench/generate-summary-table!)
     (bench/stop!)
  "
  (:require [runner]
            [document-analysis]
            [risk-analysis]
            [contract-comparison]
            [contract-comparison-validated]
            [legal-issue-detection]))

;; =============================================================================
;; Configuration — re-exported from runner for backwards compatibility
;; =============================================================================

(def config runner/config)

;; =============================================================================
;; Task Definitions
;; =============================================================================

(def tasks
  "Map of task keyword to task definition. Task definitions are imported
   from their per-task namespaces (e.g. risk-analysis/task)."
  {:document-analysis              document-analysis/task
   :risk-analysis                  risk-analysis/task
   :contract-comparison            contract-comparison/task
   :contract-comparison-validated  contract-comparison-validated/task
   :legal-issue-detection          legal-issue-detection/task})

;; =============================================================================
;; Public API — thin wrappers delegating to `runner`
;; =============================================================================

(defn start!
  "Initialize the benchmark system. Delegates to `runner/start!`."
  []
  (let [result (runner/start!)]
    (println "Available tasks:")
    (doseq [[k task] tasks]
      (println (str "  " (name k) " - " (:name task))))
    (println "\nCommands:")
    (println "  (run-task! :task-key)   - Run single task")
    (println "  (run-all-tasks!)        - Run all tasks")
    (println "  (generate-summary-table!) - Generate comparison table")
    (println "  (stop!)                 - Clean up")
    result))

(defn stop!
  "Stop the benchmark system. Delegates to `runner/stop!`."
  []
  (runner/stop!))

(defn run-task!
  "Run a single task by keyword. Looks up the task in the `tasks` map and
   delegates to `runner/run!`."
  [task-key]
  (let [task (get tasks task-key)]
    (when-not task
      (throw (ex-info "Unknown task" {:task task-key
                                       :available (keys tasks)})))
    (runner/run! task)))

(defn run-all-tasks!
  "Run all tasks sequentially. Delegates to `runner/run-all!`."
  []
  (runner/run-all! (vals tasks)))

(defn generate-summary-table!
  "Generate a markdown summary table from saved EDN results.
   Delegates to `runner/generate-summary!`."
  []
  (runner/generate-summary! (:results-dir config)))

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

  (stop!))
