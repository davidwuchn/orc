(ns ai.obney.orc.ontology.core.todo-processors
  "Todo processors for ontology self-learning.

   These event handlers implement the automatic feedback loop between
   evaluation and ontology:

   1. On evaluation/trace-evaluated: Classify failures and auto-record to tree profile
   2. On tree-profile-updated (future): Trigger re-embedding for hybrid search
   3. C-2a-3a — threshold-tracking trigger: when a target's delta-counter
      crosses its configured threshold, emit :ontology/request-consolidation
      so the consolidator processor (C-2a-3b) can run reflection.

   This closes the integration gap where judges evaluate outputs but
   results don't flow to the ontology for learning."
  (:require [ai.obney.orc.ontology.core.classifier :as classifier]
            [ai.obney.orc.ontology.core.read-models :as rm]
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

;; =============================================================================
;; C-2a-3a — Threshold-tracking trigger
;; =============================================================================
;;
;; Listens to the execution-completion events that feed the delta-counter
;; read-model. For each event, derives the affected (target-type, target-id)
;; tuples, reads the current counter, compares to the configured threshold,
;; and emits :ontology/request-consolidation when the counter has crossed.
;;
;; Counter reset semantics are handled by the read-model's reduction on
;; :ontology/consolidation-requested — so re-firing requires the counter
;; to climb back to threshold from 0.

(defn- request-consolidation!
  "Issue a non-on-demand :ontology/request-consolidation command.
   The command uses CAS on the crossing-uuid tag to enforce exactly-once
   semantics across concurrent processor handlers. A
   :cognitect.anomalies/conflict result is EXPECTED and benign — it
   means another handler in the same threshold window won the race and
   already emitted the consolidation-requested event. We treat it as
   silent success, not an error."
  [context target-type target-id]
  (try
    (let [result (run-command! context
                   {:command/id (random-uuid)
                    :command/timestamp (time/now)
                    :command/name :ontology/request-consolidation
                    :target-type target-type
                    :target-id target-id
                    :on-demand? false})]
      (when (and (map? result)
                 (= :cognitect.anomalies/conflict
                    (:cognitect.anomalies/category result)))
        (u/log ::cas-conflict-benign
               :target-type target-type
               :target-id target-id
               :note "Another handler won the race; this is expected"))
      result)
    (catch Exception e
      (u/log ::request-consolidation-error
             :error (.getMessage e)
             :target-type target-type
             :target-id target-id))))

(defn- maybe-fire-consolidation!
  "If the current delta-counter for the target meets or exceeds its
   configured threshold, emit :ontology/request-consolidation."
  [context target-type target-id]
  (let [delta (rm/get-consolidation-delta context target-type target-id)
        threshold (rm/get-consolidation-threshold context target-type)]
    (when (>= delta threshold)
      (u/log ::threshold-crossed
             :target-type target-type
             :target-id target-id
             :delta delta
             :threshold threshold)
      (request-consolidation! context target-type target-id))))

(defn on-node-execution-completed
  "Threshold-trigger logic for :sheet/node-execution-completed.
   Ticks the per-node-type AND per-node-instance counters via the
   read-model projection, then checks both for threshold crossing."
  [{:keys [event] :as context}]
  (let [{:keys [node-type sheet-id node-id]} event]
    (when (some? node-type)
      (maybe-fire-consolidation! context :node-type node-type))
    (when (and (some? sheet-id) (some? node-id))
      (maybe-fire-consolidation! context :node-instance [sheet-id node-id]))))

(defn on-rlm-tree-execution-completed
  "Threshold-trigger logic for :sheet/rlm-tree-execution-completed."
  [{:keys [event] :as context}]
  (when-let [fp (:tree-fingerprint event)]
    (maybe-fire-consolidation! context :tree-fingerprint fp)))

(defprocessor :ontology on-execution-completed-check-threshold
  {:topics #{:sheet/node-execution-completed
             :sheet/rlm-tree-execution-completed}}
  "C-2a-3a: after each execution-completion event, check whether the
   affected target's delta-counter has crossed its configured threshold;
   emit :ontology/request-consolidation if so."
  [{:keys [event] :as context}]
  (case (:event/type event)
    :sheet/node-execution-completed     (on-node-execution-completed context)
    :sheet/rlm-tree-execution-completed (on-rlm-tree-execution-completed context)
    nil))
