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
            [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.orc.colbert.interface.schemas]
            [ai.obney.grain.command-processor-v2.interface :as command-processor]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
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

;; =============================================================================
;; C-2b-1 — Re-index processor
;;
;; Subscribes to ALL THREE :ontology/*-description-updated events. After each
;; event, checks the reindex-state read-model against the reindex-config:
;;   - if events-since-last-rebuild >= configured threshold, OR
;;   - if (now - last-rebuild-timestamp) >= configured timer-minutes, OR
;;   - if no index has been built yet (cold-start),
;; → call colbert/create-index! with the current ontology-descriptions corpus.
;;
;; The :colbert/index-created event emitted by create-index! is what resets
;; reindex-state — handled by the read-model projection in read_models.clj.
;; =============================================================================

(def ^:private ontology-descriptions-index-name
  "Stable :index-name used for every rebuild of the ontology descriptions
   corpus. Per the C-2b sub-grill (Decision 1): one giant index across all
   3 granularities, distinguished only by metadata."
  "ontology-descriptions")

(defn- average-confidence
  "Average :confidence across a description body's :strengths vector.
   Returns 0.0 for empty/missing strengths."
  [body]
  (let [strengths (:strengths body)
        confidences (keep :confidence strengths)]
    (if (seq confidences)
      (double (/ (reduce + confidences) (count confidences)))
      0.0)))

(defn- collect-current-descriptions
  "Project the descriptions read-model and flatten into a vector of
   document records. Each record: {:granularity :target-id :body
   :recorded-at}. Used by the rebuild path to construct the ColBERT
   document collection + per-document metadata."
  [ctx]
  (let [state (rmp/project ctx :ontology/descriptions)]
    (vec
      (for [[granularity by-target] state
            [target-id {:keys [current history]}] by-target
            :when current]
        {:granularity granularity
         :target-id target-id
         :body current
         :recorded-at (some-> history last :recorded-at)}))))

(defn- build-document-collection
  "Convert the flattened description records into the
   (:collection, :document-ids, :document-metadatas) tuple required by
   colbert/create-index!.

   Document content = the description's :summary (the field designed for
   retrieval embedding). Metadata carries granularity + target-id +
   average confidence + last-update so post-retrieval filtering and the
   downstream LLM reranker (C-2b-2) can reason about each match."
  [descriptions]
  (reduce
    (fn [{:keys [collection document-ids document-metadatas] :as acc} d]
      (let [content (or (-> d :body :summary) "")
            doc-id (str (:granularity d) ":" (pr-str (:target-id d)))
            metadata {:granularity (:granularity d)
                      :target-id (:target-id d)
                      :confidence (average-confidence (:body d))
                      :last-update (str (:recorded-at d))}]
        (-> acc
            (assoc :collection (conj collection content))
            (assoc :document-ids (conj document-ids doc-id))
            (assoc :document-metadatas (conj document-metadatas metadata)))))
    {:collection [] :document-ids [] :document-metadatas []}
    descriptions))

(defn minutes-since
  "Whole minutes elapsed between an ISO-string timestamp and now. Returns
   Long/MAX_VALUE if ts is nil (so the timer-condition always fires when
   no rebuild has happened).

   Non-private so tests can with-redefs it to simulate elapsed time
   without sleeping."
  [iso-str]
  (if (nil? iso-str)
    Long/MAX_VALUE
    (try
      (let [past (java.time.OffsetDateTime/parse iso-str)
            now (java.time.OffsetDateTime/now)]
        (.toMinutes (java.time.Duration/between past now)))
      (catch Exception _ Long/MAX_VALUE))))

(defn- should-rebuild?
  "Decide whether the current reindex-state crosses either the threshold
   or timer trigger from the reindex-config. Cold-start (no index built
   yet) always rebuilds when there are any descriptions to index."
  [reindex-state reindex-config descriptions-count]
  (let [{:keys [events-since-last-rebuild last-rebuild-timestamp index-built?]} reindex-state
        {:keys [reindex-threshold-events reindex-timer-minutes]} reindex-config]
    (cond
      ;; Cold-start: any descriptions exist but no index built yet
      (and (pos? descriptions-count) (not index-built?))
      true

      ;; Event-count threshold crossed
      (>= events-since-last-rebuild reindex-threshold-events)
      true

      ;; Timer threshold crossed AND at least one event since last rebuild
      (and (pos? events-since-last-rebuild)
           (>= (minutes-since last-rebuild-timestamp) reindex-timer-minutes))
      true

      :else false)))

(defn maybe-rebuild!
  "Read reindex-state + config; if the trigger conditions are met, build
   the document collection from the current descriptions read-model and
   dispatch the :colbert/create-index command. The
   :colbert/index-created event emitted by the command handler is what
   resets the counter via the reindex-state projection.

   We dispatch via the command processor (NOT colbert/create-index!
   directly) because the interface fn bypasses event emission — it
   returns the bridge result but never emits :colbert/index-created.
   The defcommand is the only path that emits the event."
  [context]
  (let [reindex-state (rm/get-reindex-state context)
        reindex-config (rm/get-reindex-config context)
        descriptions (collect-current-descriptions context)]
    (when (should-rebuild? reindex-state reindex-config (count descriptions))
      (let [{:keys [collection document-ids document-metadatas]}
            (build-document-collection descriptions)]
        (u/log ::reindex-triggered
               :event-count (:events-since-last-rebuild reindex-state)
               :threshold (:reindex-threshold-events reindex-config)
               :document-count (count collection)
               :cold-start? (not (:index-built? reindex-state)))
        (try
          ;; NOTE: pass :split-documents? + :max-document-length explicitly.
          ;; The :colbert/create-index defcommand forwards these to
          ;; operations/create-index! unconditionally; operations' :or
          ;; defaults only apply when the key is absent — passing nil
          ;; overrides the default and produces a malformed
          ;; :colbert/index-created event (config keys would be nil).
          (command-processor/process-command
            (assoc context :command
                   {:command/name :colbert/create-index
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :collection collection
                    :index-name ontology-descriptions-index-name
                    :document-ids document-ids
                    :document-metadatas document-metadatas
                    :model-name "colbert-ir/colbertv2.0"
                    :split-documents? true
                    :max-document-length 256}))
          (catch Exception e
            (u/log ::reindex-failed
                   :error (.getMessage e))))))))

(defprocessor :ontology on-description-updated-maybe-reindex
  {:topics #{:ontology/node-type-description-updated
             :ontology/node-instance-description-updated
             :ontology/tree-description-updated}}
  "C-2b-1: after each description-updated event, check whether the
   reindex-state has crossed the threshold OR timer trigger; if so,
   rebuild the ColBERT ontology-descriptions index via create-index!."
  [context]
  (maybe-rebuild! context))
