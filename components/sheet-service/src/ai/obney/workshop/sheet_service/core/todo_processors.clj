(ns ai.obney.workshop.sheet-service.core.todo-processors
  "Behavior Tree Sheet todo processors (policies).

   These are event-driven side effects that respond to domain events:
   - Execute leaf nodes when tree tick starts
   - Handle sequence/fallback composite node logic
   - Update blackboard with node outputs"
  (:require [ai.obney.workshop.sheet-service.core.read-models :as rm]
            [ai.obney.workshop.sheet-service.core.executor :as executor]
            [ai.obney.grain.event-store-v2.interface :refer [->event]]))

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
   otherwise falls back to mock execution."
  [{:keys [event event-store dscloj-provider]}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        node (rm/get-node event-store sheet-id node-id)]
    (when (= :leaf (:type node))
      (let [blackboard (rm/get-blackboard-by-key event-store sheet-id)
            ;; Use provider from context, fall back to default, or use mock if nil
            provider (or dscloj-provider *default-dscloj-provider*)
            result (if provider
                     (executor/execute-leaf node blackboard provider)
                     (executor/execute-leaf-mock node blackboard))
            {:keys [status outputs error duration-ms]} result]
        {:result/events
         [(->event
           {:type :sheet/node-execution-completed
            :tags #{[:sheet sheet-id]
                    [:node node-id]
                    [:tick tick-id]}
            :body (cond-> {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id node-id
                           :status status}
                    (seq outputs) (assoc :writes outputs)
                    error (assoc :error error)
                    duration-ms (assoc :duration-ms duration-ms))})]}))))

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
            ;; :running - do nothing, wait
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
            ;; :running - do nothing, wait
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

(defn complete-tree-tick
  "When the root node completes, complete the tree tick."
  [{:keys [event event-store]}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        status (:status event)
        sheet (rm/get-sheet event-store sheet-id)]
    (when (= node-id (:root-node-id sheet))
      {:result/events
       [(->event
         {:type :sheet/tree-tick-completed
          :tags #{[:sheet sheet-id]
                  [:tick tick-id]}
          :body {:sheet-id sheet-id
                 :tick-id tick-id
                 :root-status status}})]})))

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
