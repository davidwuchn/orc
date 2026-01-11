(ns ai.obney.workshop.sheet-service.core.todo-processors
  "Sheet Service todo processors (policies).

   These are event-driven side effects that respond to domain events
   and trigger follow-up commands:
   - Cascade execution when upstream cells change
   - Check eligibility when inputs are bound
   - Execute cells when requested"
  (:require [ai.obney.workshop.sheet-service.core.read-models :as rm]
            [ai.obney.workshop.sheet-service.core.dependency-graph :as dg]
            [ai.obney.workshop.sheet-service.core.agent-runtime :as agent]
            [ai.obney.grain.event-store-v2.interface :refer [->event]]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn resolve-inputs
  "Resolve input values from bound source cells.
   Returns a map of input-name -> field-value."
  [event-store sheet-id cell]
  (reduce-kv
   (fn [acc input-name binding]
     (let [source-cell (rm/get-cell event-store sheet-id (:source-cell-id binding))
           field-value (get-in source-cell [:fields (:source-field-name binding)])]
       (assoc acc input-name (or field-value {:type :text :value nil}))))
   {}
   (:input-bindings cell)))

(defn should-continue-cycle?
  "Check if a completed gate cell indicates the cycle should continue.
   Returns true if the yes-no output is true, false otherwise."
  [cell outputs]
  (when (dg/is-gate-cell? cell)
    (let [yes-no-output (some (fn [[name value]]
                                (when (= :yes-no (:type value))
                                  value))
                              outputs)]
      (true? (:value yes-no-output)))))

;; =============================================================================
;; Cascade Trigger Processor
;; =============================================================================

(defn trigger-downstream-cells
  "When a cell's value changes, find and trigger eligible downstream cells.
   Called when:
   - A literal cell's value is set
   - A formula cell completes execution

   For gate cells with yes-no output:
   - If value is true, re-trigger cycle (handled by normal propagation)
   - If value is false, stop cycling"
  [{:keys [event event-store]}]
  (let [sheet-id (:sheet-id event)
        cell-id (:cell-id event)
        dep-graph (rm/get-dependency-graph-for-sheet event-store sheet-id)
        cells-map (into {} (map (juxt :id identity)
                                (rm/get-cells-for-sheet event-store sheet-id)))
        ;; Get direct dependents that are eligible for execution
        eligible-dependents (dg/get-eligible-dependents dep-graph cells-map cell-id)]
    (if (seq eligible-dependents)
      {:result/events
       (mapv (fn [dep-id]
               (let [cell (get cells-map dep-id)
                     exec-id (random-uuid)
                     inputs (resolve-inputs event-store sheet-id cell)]
                 (->event
                  {:type :sheet/cell-execution-requested
                   :tags #{[:sheet sheet-id]
                           [:cell dep-id]
                           [:execution exec-id]}
                   :body {:sheet-id sheet-id
                          :cell-id dep-id
                          :execution-id exec-id
                          :inputs inputs
                          :signature (:signature cell)}})))
             eligible-dependents)}
      {})))

;; =============================================================================
;; Eligibility Check Processor
;; =============================================================================

(defn check-execution-eligibility
  "When inputs are bound or signature is set, check if cell is ready to execute.
   If the cell has a signature, all inputs are bound, and it's idle, trigger execution."
  [{:keys [event event-store]}]
  (let [sheet-id (:sheet-id event)
        cell-id (:cell-id event)
        cell (rm/get-cell event-store sheet-id cell-id)]
    (when (and (:signature cell)
               (dg/all-inputs-bound? cell)
               (= :idle (:execution-status cell)))
      (let [exec-id (random-uuid)
            inputs (resolve-inputs event-store sheet-id cell)]
        {:result/events
         [(->event
           {:type :sheet/cell-execution-requested
            :tags #{[:sheet sheet-id]
                    [:cell cell-id]
                    [:execution exec-id]}
            :body {:sheet-id sheet-id
                   :cell-id cell-id
                   :execution-id exec-id
                   :inputs inputs
                   :signature (:signature cell)}})]}))))

;; =============================================================================
;; Cell Executor Processor
;; =============================================================================

(defn execute-cell-with-agent
  "Execute a formula cell using the agent runtime.
   This processor handles the actual agent invocation."
  [{:keys [event event-store agent-runtime]}]
  (let [sheet-id (:sheet-id event)
        cell-id (:cell-id event)
        execution-id (:execution-id event)
        inputs (:inputs event)
        signature (:signature event)
        ;; Use provided runtime or create a mock one
        runtime (or agent-runtime (agent/create-mock-runtime))
        start-time (System/currentTimeMillis)]
    (try
      (let [outputs (agent/execute runtime signature inputs)
            duration-ms (- (System/currentTimeMillis) start-time)]
        {:result/events
         [(->event
           {:type :sheet/cell-execution-completed
            :tags #{[:sheet sheet-id]
                    [:cell cell-id]
                    [:execution execution-id]
                    [:cell-data cell-id]}
            :body {:sheet-id sheet-id
                   :cell-id cell-id
                   :execution-id execution-id
                   :outputs outputs
                   :duration-ms duration-ms}})]})
      (catch Exception e
        (let [duration-ms (- (System/currentTimeMillis) start-time)]
          {:result/events
           [(->event
             {:type :sheet/cell-execution-failed
              :tags #{[:sheet sheet-id]
                      [:cell cell-id]
                      [:execution execution-id]}
              :body {:sheet-id sheet-id
                     :cell-id cell-id
                     :execution-id execution-id
                     :error (or (.getMessage e) "Unknown error")
                     :duration-ms duration-ms}})]})))))

;; =============================================================================
;; Signature Change Handler
;; =============================================================================

(defn handle-signature-change
  "When a cell's signature changes and it has an in-flight execution,
   the result is no longer relevant. This could trigger a cancellation.
   For now, we just let the execution complete and the result will be
   applied to the new signature (which may produce mismatched outputs)."
  [{:keys [event event-store]}]
  ;; TODO: Consider implementing cancellation when signature changes mid-execution
  ;; For now, we rely on the fact that execution results are validated against
  ;; the current signature when applied
  {})

;; =============================================================================
;; Todo Processor Registry
;; =============================================================================

(def todo-processors
  "Registry of todo processors for the sheet service."
  {;; Trigger downstream cells when values change
   :sheet/trigger-downstream-on-literal
   {:handler-fn #'trigger-downstream-cells
    :topics [:sheet/cell-literal-set]}

   :sheet/trigger-downstream-on-completion
   {:handler-fn #'trigger-downstream-cells
    :topics [:sheet/cell-execution-completed]}

   ;; Check if cell becomes eligible for execution
   :sheet/check-eligibility-on-bind
   {:handler-fn #'check-execution-eligibility
    :topics [:sheet/input-bound]}

   :sheet/check-eligibility-on-signature
   {:handler-fn #'check-execution-eligibility
    :topics [:sheet/cell-signature-defined]}

   ;; Execute cells when requested
   :sheet/execute-cell
   {:handler-fn #'execute-cell-with-agent
    :topics [:sheet/cell-execution-requested]}})
