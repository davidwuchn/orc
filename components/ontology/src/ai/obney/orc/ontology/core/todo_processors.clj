(ns ai.obney.orc.ontology.core.todo-processors
  "Todo processors for ontology self-learning.

   These event handlers implement the automatic feedback loop between
   evaluation and ontology:

   1. On evaluation/trace-evaluated: Classify failures and auto-record to tree profile
   2. On tree-profile-updated (future): Trigger re-embedding for hybrid search

   This closes the integration gap where judges evaluate outputs but
   results don't flow to the ontology for learning."
  (:require [ai.obney.orc.ontology.core.classifier :as classifier]
            [ai.obney.grain.command-processor-v2.interface :as command-processor]
            [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog :as u]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn run-command!
  "Execute a command through the command processor."
  [context command]
  (command-processor/process-command
    (assoc context :command command)))

(defn- transform-evaluation-result
  "Transform evaluation event format to classify-evaluation command format.

   Evaluation event dimensions: [{:name :weight :score :feedback}]
   Command expects: {:score :dimensions [{:name :score :feedback}]}"
  [{:keys [dimensions aggregate-score]}]
  {:score aggregate-score
   :dimensions (mapv (fn [{:keys [name score feedback]}]
                       {:name name
                        :score score
                        :feedback (or feedback "")})
                     dimensions)})

;; =============================================================================
;; On Trace Evaluated
;; =============================================================================

(defn on-trace-evaluated
  "Handle evaluation/trace-evaluated event.

   When a trace is evaluated by the judges:
   1. Transform the evaluation result to ontology format
   2. Call :ontology/classify-evaluation with auto-record? true
   3. This automatically records weaknesses to the tree profile

   This closes the feedback loop: evaluate → classify → learn."
  [{:keys [event] :as context}]
  (let [{:keys [trace-id sheet-id node-id dimensions aggregate-score]} event]

    (u/log ::on-trace-evaluated
           :trace-id trace-id
           :sheet-id sheet-id
           :aggregate-score aggregate-score)

    ;; Only classify if we have dimensions and a low score (potential failure)
    ;; Skip classification for high-scoring traces to reduce noise
    (when (and (seq dimensions)
               (< aggregate-score 0.8))  ;; Threshold for "potential failure"

      (let [evaluation-result (transform-evaluation-result event)]
        (u/log ::classifying-evaluation
               :trace-id trace-id
               :num-dimensions (count dimensions)
               :aggregate-score aggregate-score)

        (try
          (run-command! context
            {:command/id (random-uuid)
             :command/timestamp (time/now)
             :command/name :ontology/classify-evaluation
             :trace-id trace-id
             :sheet-id sheet-id
             :node-id node-id
             :evaluation-result evaluation-result
             :auto-record? true})  ;; Key: automatically record weaknesses

          (catch Exception e
            (u/log ::classify-evaluation-error
                   :error (.getMessage e)
                   :trace-id trace-id)))))))

;; =============================================================================
;; On High-Scoring Trace (Record Strengths)
;; =============================================================================

(defn on-high-scoring-trace
  "Handle evaluation/trace-evaluated event for successful traces.

   When a trace scores high (>= 0.85), record as a strength:
   1. Identify which success patterns might apply
   2. Record the strength to the tree profile

   This complements on-trace-evaluated which handles failures."
  [{:keys [event] :as context}]
  (let [{:keys [trace-id sheet-id aggregate-score]} event]

    ;; Only record strengths for high-scoring traces
    (when (>= aggregate-score 0.85)
      (u/log ::recording-high-score-strength
             :trace-id trace-id
             :sheet-id sheet-id
             :aggregate-score aggregate-score)

      ;; For now, record as a generic success pattern
      ;; In the future, use pattern detection to identify specific patterns
      (try
        (run-command! context
          {:command/id (random-uuid)
           :command/timestamp (time/now)
           :command/name :ontology/record-tree-strength
           :tree-id sheet-id
           :pattern-uri "success:HighQualityOutput"
           :confidence aggregate-score
           :evidence-trace-ids [trace-id]
           :avg-score aggregate-score})

        (catch Exception e
          (u/log ::record-strength-error
                 :error (.getMessage e)
                 :trace-id trace-id))))))

;; =============================================================================
;; Processor Registration
;; =============================================================================

(defprocessor :ontology on-trace-evaluated
  {:topics #{:evaluation/trace-evaluated}}
  "Handle evaluation/trace-evaluated: classify failures and auto-record to ontology."
  [context]
  (on-trace-evaluated context)
  ;; Also check for high-scoring traces to record strengths
  (on-high-scoring-trace context))
