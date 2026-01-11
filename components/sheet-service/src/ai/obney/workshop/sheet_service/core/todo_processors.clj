(ns ai.obney.workshop.sheet-service.core.todo-processors
  "Behavior Tree Sheet todo processors (policies).

   These are event-driven side effects that respond to domain events:
   - Execute leaf nodes when tree tick starts
   - Handle sequence/fallback composite node logic
   - Update blackboard with node outputs"
  (:require [ai.obney.workshop.sheet-service.core.read-models :as rm]
            [ai.obney.workshop.sheet-service.core.executor :as executor]
            [ai.obney.grain.event-store-v2.interface :refer [->event]]
            [ai.obney.grain.command-processor.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Tick Execution Processor
;; =============================================================================

(defn execute-tree-tick
  "When a tree tick starts, begin executing from the root node.
   This initiates the tick by starting execution on the root."
  [{:keys [event event-store]}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        sheet (rm/get-sheet event-store sheet-id)
        root-id (:root-node-id sheet)
        root-node (when root-id (rm/get-node event-store sheet-id root-id))]
    (when root-node
      (let [blackboard (rm/get-blackboard-by-key event-store sheet-id)
            ;; For leaf nodes, gather inputs from blackboard
            inputs (if (= :leaf (:type root-node))
                     (reduce (fn [acc k]
                               (if-let [entry (get blackboard k)]
                                 (assoc acc k (:value entry))
                                 acc))
                             {}
                             (:reads root-node))
                     {})]
        {:result/events
         [(->event
           {:type :sheet/node-execution-started
            :tags #{[:sheet sheet-id]
                    [:node root-id]
                    [:tick tick-id]}
            :body {:sheet-id sheet-id
                   :tick-id tick-id
                   :node-id root-id
                   :inputs inputs}})]}))))

;; =============================================================================
;; Node Execution Processor
;; =============================================================================

;; Default provider - set to nil to use mock, or :openrouter etc for real execution
(def ^:dynamic *default-dscloj-provider* :openrouter)

(defn execute-leaf-node
  "Execute a leaf node when node-execution-started is emitted.
   Uses DSCloj to invoke an AI agent if a provider is configured,
   otherwise falls back to mock execution.

   Runs AI execution in a future to avoid blocking the pubsub thread.
   Uses cp/process-command to emit completion events."
  [{:keys [event event-store dscloj-provider] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        node (rm/get-node event-store sheet-id node-id)]
    (when (= :leaf (:type node))
      (let [blackboard (rm/get-blackboard-by-key event-store sheet-id)
            ;; Use provider from context, fall back to default, or use mock if nil
            provider (or dscloj-provider *default-dscloj-provider*)]
        ;; Run execution in a future to avoid blocking
        (future
          (try
            (let [result (if provider
                           (executor/execute-leaf node blackboard provider)
                           (executor/execute-leaf-mock node blackboard))
                  {:keys [status outputs error duration-ms]} result]
              ;; Use process-command to emit completion event
              (cp/process-command
                (assoc context :command
                       (cond-> {:command/id (random-uuid)
                                :command/timestamp (time/now)
                                :command/name :sheet/complete-node-execution
                                :sheet-id sheet-id
                                :tick-id tick-id
                                :node-id node-id
                                :status status
                                :writes (or outputs {})}
                         duration-ms (assoc :duration-ms duration-ms)))))
            (catch Exception e
              ;; Use process-command to emit failure event
              (cp/process-command
                (assoc context :command
                       {:command/id (random-uuid)
                        :command/timestamp (time/now)
                        :command/name :sheet/fail-node-execution
                        :sheet-id sheet-id
                        :tick-id tick-id
                        :node-id node-id
                        :error (.getMessage e)})))))
        ;; Return nil - completion will be handled by the future via process-command
        nil))))

;; =============================================================================
;; Condition Node Execution Processor
;; =============================================================================

(defn- normalize-for-comparison
  "Normalize values for comparison, handling yesno/boolean cases.
   Converts 'yes'/'true'/true to true, 'no'/'false'/false to false."
  [v]
  (cond
    (boolean? v) v
    (string? v) (let [lower (clojure.string/lower-case v)]
                  (cond
                    (#{"yes" "true" "1"} lower) true
                    (#{"no" "false" "0"} lower) false
                    ;; Try parsing as number
                    :else (try (Double/parseDouble v) (catch Exception _ v))))
    :else v))

(defn evaluate-condition-check
  "Evaluate a condition check against a blackboard value.
   Returns true if check passes, false otherwise.
   Handles yesno fields by normalizing 'yes'/'no' strings to booleans."
  [check blackboard]
  (let [{:keys [key op value]} check
        entry (get blackboard key)
        bb-value (:value entry)
        ;; Normalize both values for comparison
        norm-bb (normalize-for-comparison bb-value)
        norm-val (normalize-for-comparison value)]
    (case op
      :equals (= norm-bb norm-val)
      :not-equals (not= norm-bb norm-val)
      :gt (and (number? norm-bb) (number? norm-val) (> norm-bb norm-val))
      :lt (and (number? norm-bb) (number? norm-val) (< norm-bb norm-val))
      :gte (and (number? norm-bb) (number? norm-val) (>= norm-bb norm-val))
      :lte (and (number? norm-bb) (number? norm-val) (<= norm-bb norm-val))
      :contains (and (string? bb-value) (string? value) (.contains bb-value value))
      :exists (some? bb-value)
      :truthy (boolean norm-bb)  ;; Use normalized value so "no"/"false" → false
      ;; Default to false for unknown ops
      false)))

(defn execute-condition-node
  "Execute a condition node when node-execution-started is emitted.
   Evaluates the check against blackboard and returns success/failure/running."
  [{:keys [event event-store]}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        node (rm/get-node event-store sheet-id node-id)]
    (when (= :condition (:type node))
      (let [blackboard (rm/get-blackboard-by-key event-store sheet-id)
            check (:check node)
            on-fail (get check :on-fail :failure)
            passed? (if check
                      (evaluate-condition-check check blackboard)
                      false)
            status (if passed?
                     :success
                     on-fail)]
        {:result/events
         [(->event
           {:type :sheet/node-execution-completed
            :tags #{[:sheet sheet-id]
                    [:node node-id]
                    [:tick tick-id]}
            :body {:sheet-id sheet-id
                   :tick-id tick-id
                   :node-id node-id
                   :status status}})]}))))

;; =============================================================================
;; Composite Node Execution Processor
;; =============================================================================

(defn execute-composite-node
  "Handle execution of sequence/fallback nodes.
   When started, begin executing the first child.
   When a child completes, decide whether to continue or finish."
  [{:keys [event event-store]}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        ;; Use get-nodes-by-id to get proper parent-child relationships
        nodes-by-id (rm/get-nodes-by-id event-store sheet-id)
        node (get nodes-by-id node-id)]
    (when (#{:sequence :fallback} (:type node))
      (let [children-ids (:children-ids node)]
        (if (empty? children-ids)
          ;; No children - sequence succeeds, fallback fails
          {:result/events
           [(->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :status (if (= :sequence (:type node)) :success :failure)}})]}
          ;; Start first child
          (let [first-child-id (first children-ids)
                first-child (get nodes-by-id first-child-id)
                blackboard (rm/get-blackboard-by-key event-store sheet-id)
                inputs (if (= :leaf (:type first-child))
                         (reduce (fn [acc k]
                                   (if-let [entry (get blackboard k)]
                                     (assoc acc k (:value entry))
                                     acc))
                                 {}
                                 (:reads first-child))
                         {})]
            {:result/events
             [(->event
               {:type :sheet/node-execution-started
                :tags #{[:sheet sheet-id]
                        [:node first-child-id]
                        [:tick tick-id]}
                :body {:sheet-id sheet-id
                       :tick-id tick-id
                       :node-id first-child-id
                       :inputs inputs}})]}))))))

;; =============================================================================
;; Child Completion Handler
;; =============================================================================

(defn handle-child-completion
  "When a child node completes, handle the parent's logic.
   For sequences: continue on success, fail on failure.
   For fallbacks: succeed on success, continue on failure."
  [{:keys [event event-store]}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        child-id (:node-id event)
        child-status (:status event)
        ;; Use get-nodes-by-id to get proper parent-child relationships
        nodes-by-id (rm/get-nodes-by-id event-store sheet-id)
        child (get nodes-by-id child-id)
        parent-id (:parent-id child)]
    (when parent-id
      (let [parent (get nodes-by-id parent-id)
            siblings (:children-ids parent)
            child-index (.indexOf (vec siblings) child-id)
            next-child-id (get (vec siblings) (inc child-index))
            blackboard (rm/get-blackboard-by-key event-store sheet-id)]
        (case (:type parent)
          :sequence
          (case child-status
            :success
            (if next-child-id
              ;; Continue to next child
              (let [next-child (get nodes-by-id next-child-id)
                    inputs (if (= :leaf (:type next-child))
                             (reduce (fn [acc k]
                                       (if-let [entry (get blackboard k)]
                                         (assoc acc k (:value entry))
                                         acc))
                                     {}
                                     (:reads next-child))
                             {})]
                {:result/events
                 [(->event
                   {:type :sheet/node-execution-started
                    :tags #{[:sheet sheet-id]
                            [:node next-child-id]
                            [:tick tick-id]}
                    :body {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id next-child-id
                           :inputs inputs}})]})
              ;; All children succeeded - sequence succeeds
              {:result/events
               [(->event
                 {:type :sheet/node-execution-completed
                  :tags #{[:sheet sheet-id]
                          [:node parent-id]
                          [:tick tick-id]}
                  :body {:sheet-id sheet-id
                         :tick-id tick-id
                         :node-id parent-id
                         :status :success}})]})
            :failure
            ;; Child failed - sequence fails
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body {:sheet-id sheet-id
                       :tick-id tick-id
                       :node-id parent-id
                       :status :failure}})]}
            :running
            ;; Child returned running - propagate up
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body {:sheet-id sheet-id
                       :tick-id tick-id
                       :node-id parent-id
                       :status :running}})]}
            ;; Unknown status - do nothing
            nil)

          :fallback
          (case child-status
            :success
            ;; Child succeeded - fallback succeeds
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body {:sheet-id sheet-id
                       :tick-id tick-id
                       :node-id parent-id
                       :status :success}})]}
            :failure
            (if next-child-id
              ;; Continue to next child
              (let [next-child (get nodes-by-id next-child-id)
                    inputs (if (= :leaf (:type next-child))
                             (reduce (fn [acc k]
                                       (if-let [entry (get blackboard k)]
                                         (assoc acc k (:value entry))
                                         acc))
                                     {}
                                     (:reads next-child))
                             {})]
                {:result/events
                 [(->event
                   {:type :sheet/node-execution-started
                    :tags #{[:sheet sheet-id]
                            [:node next-child-id]
                            [:tick tick-id]}
                    :body {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id next-child-id
                           :inputs inputs}})]})
              ;; All children failed - fallback fails
              {:result/events
               [(->event
                 {:type :sheet/node-execution-completed
                  :tags #{[:sheet sheet-id]
                          [:node parent-id]
                          [:tick tick-id]}
                  :body {:sheet-id sheet-id
                         :tick-id tick-id
                         :node-id parent-id
                         :status :failure}})]})
            :running
            ;; Child returned running - propagate up
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body {:sheet-id sheet-id
                       :tick-id tick-id
                       :node-id parent-id
                       :status :running}})]}
            ;; Unknown status - do nothing
            nil)

          ;; Unknown parent type
          nil)))))

;; =============================================================================
;; Blackboard Update Processor
;; =============================================================================

(defn update-blackboard-on-completion
  "When a node completes with writes, update the blackboard."
  [{:keys [event event-store]}]
  (let [sheet-id (:sheet-id event)
        writes (:writes event)
        blackboard (rm/get-blackboard-by-key event-store sheet-id)]
    (when (seq writes)
      {:result/events
       (vec
        (for [[k v] writes
              :let [entry (get blackboard k)]
              :when entry]
          (->event
           {:type :sheet/key-value-set
            :tags #{[:sheet sheet-id]}
            :body {:sheet-id sheet-id
                   :key k
                   :value v
                   :version (inc (:version entry))}})))})))

;; =============================================================================
;; Tree Tick Completion
;; =============================================================================

;; Maximum iterations to prevent infinite loops
(def ^:dynamic *max-tick-iterations* 10)

(defn complete-tree-tick
  "When the root node completes, complete the tree tick.
   If status is :running, automatically re-tick (up to max iterations).
   If tick has been cancelled, stop immediately."
  [{:keys [event event-store]}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        status (:status event)
        sheet (rm/get-sheet event-store sheet-id)
        ;; Get current tick to check iteration count and cancellation
        tick (rm/get-tick event-store tick-id)
        current-iteration (or (:iteration tick) 1)
        cancelled? (= :cancelled (:status tick))]
    (when (= node-id (:root-node-id sheet))
      (cond
        ;; Tick was cancelled - don't re-tick
        cancelled?
        {:result/events
         [(->event
           {:type :sheet/tree-tick-completed
            :tags #{[:sheet sheet-id]
                    [:tick tick-id]}
            :body {:sheet-id sheet-id
                   :tick-id tick-id
                   :iteration current-iteration
                   :root-status :failure}})]}

        ;; Status is running and we haven't hit max iterations - re-tick
        (and (= status :running)
             (< current-iteration *max-tick-iterations*))
        {:result/events
         [(->event
           {:type :sheet/tree-tick-completed
            :tags #{[:sheet sheet-id]
                    [:tick tick-id]}
            :body {:sheet-id sheet-id
                   :tick-id tick-id
                   :iteration current-iteration
                   :root-status :running}})
          (->event
           {:type :sheet/tree-tick-started
            :tags #{[:sheet sheet-id]
                    [:tick tick-id]}
            :body {:sheet-id sheet-id
                   :tick-id tick-id
                   :iteration (inc current-iteration)}})]}

        ;; Either success/failure, or hit max iterations
        :else
        {:result/events
         [(->event
           {:type :sheet/tree-tick-completed
            :tags #{[:sheet sheet-id]
                    [:tick tick-id]}
            :body {:sheet-id sheet-id
                   :tick-id tick-id
                   :iteration current-iteration
                   :root-status (if (and (= status :running)
                                         (>= current-iteration *max-tick-iterations*))
                                  :failure  ;; Convert running to failure on max iterations
                                  status)}})]}))))

;; =============================================================================
;; Todo Processor Registry
;; =============================================================================

(def todo-processors
  "Registry of todo processors for the behavior tree sheet service."
  {;; Start tick execution
   :sheet/start-tree-tick
   {:handler-fn #'execute-tree-tick
    :topics [:sheet/tree-tick-started]}

   ;; Execute leaf nodes
   :sheet/execute-leaf-node
   {:handler-fn #'execute-leaf-node
    :topics [:sheet/node-execution-started]}

   ;; Execute condition nodes
   :sheet/execute-condition-node
   {:handler-fn #'execute-condition-node
    :topics [:sheet/node-execution-started]}

   ;; Execute composite nodes
   :sheet/execute-composite-node
   {:handler-fn #'execute-composite-node
    :topics [:sheet/node-execution-started]}

   ;; Handle child completion
   :sheet/handle-child-completion
   {:handler-fn #'handle-child-completion
    :topics [:sheet/node-execution-completed]}

   ;; Update blackboard
   :sheet/update-blackboard
   {:handler-fn #'update-blackboard-on-completion
    :topics [:sheet/node-execution-completed]}

   ;; Complete tree tick
   :sheet/complete-tree-tick
   {:handler-fn #'complete-tree-tick
    :topics [:sheet/node-execution-completed]}})
