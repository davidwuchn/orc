(ns ai.obney.workshop.sheet-service.core.todo-processors
  "Behavior Tree Sheet todo processors (policies).

   These are event-driven side effects that respond to domain events:
   - Execute leaf nodes when tree tick starts
   - Handle sequence/fallback/parallel composite node logic
   - Handle map-each iteration
   - Update blackboard with node outputs"
  (:require [ai.obney.workshop.sheet-service.core.read-models :as rm]
            [ai.obney.workshop.sheet-service.core.executor :as executor]
            [ai.obney.grain.event-store-v2.interface :as event-store :refer [->event]]
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
   Supports multiple executor types:
   - :ai - DSCloj AI execution (default)
   - :code - Clojure function execution
   - :tool - Direct tool invocation

   Runs execution in a future to avoid blocking the pubsub thread.
   Uses cp/process-command to emit completion events."
  [{:keys [event event-store dscloj-provider] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        node (rm/get-node event-store sheet-id node-id)]
    (when (= :leaf (:type node))
      (let [blackboard (rm/get-blackboard-by-key event-store sheet-id)
            ;; Use provider from context, fall back to default, or use mock if nil
            provider (or dscloj-provider *default-dscloj-provider*)
            executor-type (or (:executor node) :ai)]
        ;; Run execution in a future to avoid blocking
        (future
          (try
            (let [result (cond
                           ;; Code executor doesn't need provider
                           (= :code executor-type)
                           (executor/execute-leaf node blackboard nil
                                                  :context {:event-store event-store})
                           ;; AI executor with provider
                           provider
                           (executor/execute-leaf node blackboard provider
                                                  :context {:event-store event-store})
                           ;; No provider - use mock
                           :else
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
                         duration-ms (assoc :duration-ms duration-ms)
                         error (assoc :error error)))))
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
;; Parallel Node Execution Processor
;; =============================================================================

(defn execute-parallel-node
  "Handle execution of parallel nodes.
   When started, execute ALL children concurrently.
   Completion is handled by handle-parallel-child-completion."
  [{:keys [event event-store]}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        nodes-by-id (rm/get-nodes-by-id event-store sheet-id)
        node (get nodes-by-id node-id)]
    (when (= :parallel (:type node))
      (let [children-ids (:children-ids node)]
        (if (empty? children-ids)
          ;; No children - parallel succeeds
          {:result/events
           [(->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :status :success}})]}
          ;; Start ALL children concurrently
          (let [blackboard (rm/get-blackboard-by-key event-store sheet-id)]
            {:result/events
             (vec
              (for [child-id children-ids]
                (let [child (get nodes-by-id child-id)
                      inputs (if (= :leaf (:type child))
                               (reduce (fn [acc k]
                                         (if-let [entry (get blackboard k)]
                                           (assoc acc k (:value entry))
                                           acc))
                                       {}
                                       (:reads child))
                               {})]
                  (->event
                   {:type :sheet/node-execution-started
                    :tags #{[:sheet sheet-id]
                            [:node child-id]
                            [:tick tick-id]}
                    :body {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id child-id
                           :inputs inputs}}))))}))))))

;; =============================================================================
;; Child Completion Handler
;; =============================================================================

(defn- count-child-statuses
  "Count how many children of a node have completed with each status.
   Returns {:success n :failure n :total-children n :completed n}"
  [event-store sheet-id tick-id parent-node]
  (let [children-ids (:children-ids parent-node)
        total (count children-ids)
        ;; Read all execution completed events for this tick and these children
        events (event-store/read event-store
                                 {:types #{:sheet/node-execution-completed}
                                  :tags #{[:tick tick-id]}})
        ;; Filter to only children of this parent
        child-set (set children-ids)
        child-completions (filter #(child-set (:node-id %)) events)
        ;; Count by status
        success-count (count (filter #(= :success (:status %)) child-completions))
        failure-count (count (filter #(= :failure (:status %)) child-completions))]
    {:success success-count
     :failure failure-count
     :total-children total
     :completed (count child-completions)}))

(defn- evaluate-parallel-completion
  "Evaluate if a parallel node should complete based on its policies.
   Returns nil if not ready, or {:status :success/:failure} if ready."
  [child-counts success-policy failure-policy]
  (let [{:keys [success failure total-children completed]} child-counts
        ;; Default policies
        success-policy (or success-policy :all)
        failure-policy (or failure-policy :any)]
    (cond
      ;; Check failure policy first
      (and (= failure-policy :any) (> failure 0))
      {:status :failure}

      (and (= failure-policy :all) (= failure total-children))
      {:status :failure}

      ;; Check success policy
      (and (= success-policy :all) (= success total-children))
      {:status :success}

      (and (= success-policy :any) (> success 0))
      {:status :success}

      (and (= success-policy :majority) (> success (/ total-children 2)))
      {:status :success}

      ;; All children completed but didn't meet success criteria
      (= completed total-children)
      {:status :failure}

      ;; Not all children completed yet
      :else nil)))

(defn handle-child-completion
  "When a child node completes, handle the parent's logic.
   For sequences: continue on success, fail on failure.
   For fallbacks: succeed on success, continue on failure.
   For parallel: check policies to determine completion."
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

          :parallel
          ;; For parallel nodes, check if all children completed based on policies
          (let [child-counts (count-child-statuses event-store sheet-id tick-id parent)
                completion (evaluate-parallel-completion
                            child-counts
                            (:success-policy parent)
                            (:failure-policy parent))]
            (when completion
              {:result/events
               [(->event
                 {:type :sheet/node-execution-completed
                  :tags #{[:sheet sheet-id]
                          [:node parent-id]
                          [:tick tick-id]}
                  :body {:sheet-id sheet-id
                         :tick-id tick-id
                         :node-id parent-id
                         :status (:status completion)}})]}))

          ;; Map-each has special handling via execute-map-each-node
          :map-each
          nil

          ;; Unknown parent type
          nil)))))

;; =============================================================================
;; Map-Each Node Execution Processor
;; =============================================================================

;; Track map-each iteration state (in-memory, keyed by tick-id + node-id)
(defonce ^:private map-each-state (atom {}))

(defn- map-each-key [tick-id node-id]
  (str tick-id "-" node-id))

(defn execute-map-each-node
  "Handle execution of map-each nodes.
   Iterates over a list in the blackboard, executing the child subtree for each item.
   Supports optional concurrency via :max-concurrency."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        nodes-by-id (rm/get-nodes-by-id event-store sheet-id)
        node (get nodes-by-id node-id)]
    (when (= :map-each (:type node))
      (let [source-key (:source-key node)
            item-key (:item-key node)
            output-key (:output-key node)
            max-concurrency (or (:max-concurrency node) 1)
            children-ids (:children-ids node)
            child-id (first children-ids) ;; map-each has exactly one child subtree
            blackboard (rm/get-blackboard-by-key event-store sheet-id)
            source-list (get-in blackboard [source-key :value])]
        (cond
          (not (sequential? source-list))
          ;; Source is not a list - fail
          {:result/events
           [(->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :status :failure
                     :error (str "Source key '" source-key "' is not a list")}})]}

          (empty? source-list)
          ;; Empty list - succeed with empty results
          {:result/events
           [(->event
             {:type :sheet/key-value-set
              :tags #{[:sheet sheet-id]}
              :body {:sheet-id sheet-id
                     :key output-key
                     :value []
                     :version (inc (get-in blackboard [output-key :version] 0))}})
            (->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :status :success}})]}

          (not child-id)
          ;; No child subtree - succeed with original list
          {:result/events
           [(->event
             {:type :sheet/key-value-set
              :tags #{[:sheet sheet-id]}
              :body {:sheet-id sheet-id
                     :key output-key
                     :value (vec source-list)
                     :version (inc (get-in blackboard [output-key :version] 0))}})
            (->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :status :success}})]}

          :else
          ;; Initialize iteration state and start first batch
          (let [state-key (map-each-key tick-id node-id)
                items (vec source-list)
                initial-state {:items items
                               :current-index 0
                               :results []
                               :in-flight #{}
                               :max-concurrency max-concurrency
                               :child-id child-id
                               :item-key item-key
                               :output-key output-key}
                ;; Determine how many to start
                batch-size (min max-concurrency (count items))
                batch-indices (range batch-size)]
            ;; Store state
            (swap! map-each-state assoc state-key initial-state)
            ;; Start first batch - set item value and start child for each
            {:result/events
             (vec
              (for [idx batch-indices]
                (let [item (nth items idx)
                      child (get nodes-by-id child-id)]
                  ;; We need to set the item-key value before starting child
                  ;; This is done via a key-value-set event followed by node-execution-started
                  ;; For simplicity, we'll set it in the inputs map
                  (->event
                   {:type :sheet/node-execution-started
                    :tags #{[:sheet sheet-id]
                            [:node child-id]
                            [:tick tick-id]}
                    :body {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id child-id
                           ;; Pass item as an input override
                           :inputs {item-key item
                                    :__map-each-index idx
                                    :__map-each-parent node-id}}}))))}))))))

(defn handle-map-each-child-completion
  "Handle completion of a map-each child iteration.
   Collects results and starts next items or completes the map-each node."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        child-id (:node-id event)
        child-status (:status event)
        inputs (:inputs event)
        writes (:writes event)
        ;; Check if this is a map-each child
        map-each-parent-id (get inputs :__map-each-parent)
        item-index (get inputs :__map-each-index)]
    (when (and map-each-parent-id item-index)
      (let [state-key (map-each-key tick-id map-each-parent-id)
            state (get @map-each-state state-key)]
        (when state
          (let [{:keys [items current-index results in-flight max-concurrency
                        child-id item-key output-key]} state
                ;; Get the original item
                item (nth items item-index)
                ;; Create result (merge item with writes, or just item if failed)
                result (if (= :success child-status)
                         (merge (when (map? item) item) writes)
                         (assoc (if (map? item) item {:__original item})
                                :__status :failure
                                :__error (:error event)))
                ;; Update state
                new-results (assoc results item-index result)
                completed-count (count (filter some? new-results))
                total-items (count items)
                nodes-by-id (rm/get-nodes-by-id event-store sheet-id)
                blackboard (rm/get-blackboard-by-key event-store sheet-id)]
            ;; Update state with result
            (swap! map-each-state update state-key
                   (fn [s] (assoc s :results new-results)))

            (if (= completed-count total-items)
              ;; All items processed - complete map-each node
              (do
                ;; Clean up state
                (swap! map-each-state dissoc state-key)
                ;; Emit results and completion
                {:result/events
                 [(->event
                   {:type :sheet/key-value-set
                    :tags #{[:sheet sheet-id]}
                    :body {:sheet-id sheet-id
                           :key output-key
                           :value (vec new-results)
                           :version (inc (get-in blackboard [output-key :version] 0))}})
                  (->event
                   {:type :sheet/node-execution-completed
                    :tags #{[:sheet sheet-id]
                            [:node map-each-parent-id]
                            [:tick tick-id]}
                    :body {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id map-each-parent-id
                           :status :success}})]})
              ;; Check if we should start more items
              (let [next-index (+ max-concurrency item-index)
                    ;; Find next unprocessed index
                    next-to-start (first (filter #(and (>= % max-concurrency)
                                                       (nil? (get new-results %)))
                                                 (range total-items)))]
                (when (and next-to-start (< next-to-start total-items))
                  (let [next-item (nth items next-to-start)
                        child (get nodes-by-id child-id)]
                    {:result/events
                     [(->event
                       {:type :sheet/node-execution-started
                        :tags #{[:sheet sheet-id]
                                [:node child-id]
                                [:tick tick-id]}
                        :body {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id child-id
                               :inputs {item-key next-item
                                        :__map-each-index next-to-start
                                        :__map-each-parent map-each-parent-id}}})]}))))))))))

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

;; =============================================================================
;; Snapshot Restore Processor
;; =============================================================================

(defn- flatten-snapshot-nodes
  "Flatten a nested snapshot tree into a list of [node parent-id] pairs,
   in creation order (parent before children)."
  [snapshot-node parent-id]
  (when snapshot-node
    (let [node-id (random-uuid)
          node-record [node-id parent-id snapshot-node]
          children (or (:children snapshot-node) [])]
      (cons node-record
            (mapcat #(flatten-snapshot-nodes % node-id) children)))))

(defn- collect-deletion-order
  "Collect nodes in deletion order (children before parents, leaf-first).
   Returns vector of node-ids to delete."
  [nodes-by-id root-id]
  (letfn [(collect [node-id]
            (let [node (get nodes-by-id node-id)
                  children-ids (:children-ids node)]
              (concat (mapcat collect children-ids)
                      [node-id])))]
    (when root-id
      (vec (collect root-id)))))

(defn restore-from-snapshot
  "Restore sheet state from a version or stash snapshot.
   Handles both :sheet/draft-reverted and :sheet/stash-restored events.

   This generates all events needed to:
   1. Delete all existing nodes (leaf-first)
   2. Delete all existing blackboard keys
   3. Recreate nodes from snapshot
   4. Recreate blackboard schema from snapshot"
  [{:keys [event event-store]}]
  (let [event-type (:event/type event)
        sheet-id (:sheet-id event)
        snapshot (:snapshot event)]
    (when (and (#{:sheet/draft-reverted :sheet/stash-restored} event-type)
               snapshot)
      (let [;; Get current state to delete
            current-nodes-by-id (rm/get-nodes-by-id event-store sheet-id)
            current-sheet (rm/get-sheet event-store sheet-id)
            current-root-id (:root-node-id current-sheet)
            current-blackboard (rm/get-blackboard-by-key event-store sheet-id)

            ;; Generate deletion events for nodes (leaf-first order)
            node-deletion-ids (collect-deletion-order current-nodes-by-id current-root-id)
            node-deletion-events (mapv (fn [node-id]
                                         (->event
                                          {:type :sheet/node-deleted
                                           :tags #{[:sheet sheet-id]
                                                   [:node node-id]}
                                           :body {:sheet-id sheet-id
                                                  :node-id node-id}}))
                                       node-deletion-ids)

            ;; Generate deletion events for blackboard keys
            key-deletion-events (mapv (fn [[k _]]
                                        (->event
                                         {:type :sheet/key-deleted
                                          :tags #{[:sheet sheet-id]}
                                          :body {:sheet-id sheet-id
                                                 :key k}}))
                                      current-blackboard)

            ;; Extract snapshot data
            snapshot-nodes (:nodes snapshot)
            blackboard-schema (:blackboard-schema snapshot)

            ;; Generate key declaration events
            key-declaration-events (mapv (fn [[k schema]]
                                           (->event
                                            {:type :sheet/key-declared
                                             :tags #{[:sheet sheet-id]}
                                             :body {:sheet-id sheet-id
                                                    :key (name k)
                                                    :schema schema}}))
                                         blackboard-schema)

            ;; Flatten snapshot tree to get node creation order
            node-records (flatten-snapshot-nodes snapshot-nodes nil)

            ;; Generate node creation and configuration events
            node-events (mapcat
                         (fn [[node-id parent-id snapshot-node]]
                           (let [node-type (:type snapshot-node)
                                 create-event (->event
                                               {:type :sheet/node-created
                                                :tags #{[:sheet sheet-id]
                                                        [:node node-id]}
                                                :body (cond-> {:sheet-id sheet-id
                                                               :node-id node-id
                                                               :type node-type}
                                                        parent-id (assoc :parent-id parent-id))})
                                 ;; Name event
                                 name-event (when (:name snapshot-node)
                                              (->event
                                               {:type :sheet/node-name-set
                                                :tags #{[:sheet sheet-id]
                                                        [:node node-id]}
                                                :body {:sheet-id sheet-id
                                                       :node-id node-id
                                                       :name (:name snapshot-node)}}))
                                 ;; Configuration events based on node type
                                 config-events
                                 (case node-type
                                   :leaf
                                   (filterv some?
                                            [(when (:instruction snapshot-node)
                                               (->event
                                                {:type :sheet/node-instruction-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body {:sheet-id sheet-id
                                                        :node-id node-id
                                                        :instruction (:instruction snapshot-node)}}))
                                             (when (or (seq (:reads snapshot-node))
                                                       (seq (:writes snapshot-node)))
                                               (->event
                                                {:type :sheet/node-io-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body {:sheet-id sheet-id
                                                        :node-id node-id
                                                        :reads (or (:reads snapshot-node) [])
                                                        :writes (or (:writes snapshot-node) [])}}))
                                             (when (:executor snapshot-node)
                                               (->event
                                                {:type :sheet/node-executor-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body (cond-> {:sheet-id sheet-id
                                                                :node-id node-id
                                                                :executor (:executor snapshot-node)}
                                                         (:model snapshot-node) (assoc :model (:model snapshot-node))
                                                         (:fn snapshot-node) (assoc :fn (:fn snapshot-node))
                                                         (:tools snapshot-node) (assoc :tools (:tools snapshot-node)))}))
                                             (when (:retry snapshot-node)
                                               (->event
                                                {:type :sheet/node-retry-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body {:sheet-id sheet-id
                                                        :node-id node-id
                                                        :retry (:retry snapshot-node)}}))])

                                   :condition
                                   (filterv some?
                                            [(when (:check snapshot-node)
                                               (->event
                                                {:type :sheet/node-check-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body {:sheet-id sheet-id
                                                        :node-id node-id
                                                        :check (:check snapshot-node)}}))])

                                   :llm-condition
                                   (filterv some?
                                            [(when (or (:instruction snapshot-node)
                                                       (seq (:reads snapshot-node)))
                                               (->event
                                                {:type :sheet/llm-condition-config-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body (cond-> {:sheet-id sheet-id
                                                                :node-id node-id
                                                                :instruction (or (:instruction snapshot-node) "")
                                                                :reads (or (:reads snapshot-node) [])}
                                                         (:model snapshot-node) (assoc :model (:model snapshot-node)))}))])

                                   :parallel
                                   [(->event
                                     {:type :sheet/parallel-config-set
                                      :tags #{[:sheet sheet-id] [:node node-id]}
                                      :body {:sheet-id sheet-id
                                             :node-id node-id
                                             :success-policy (or (:success-policy snapshot-node) :all)
                                             :failure-policy (or (:failure-policy snapshot-node) :any)}})]

                                   :map-each
                                   (filterv some?
                                            [(when (and (:source-key snapshot-node)
                                                        (:item-key snapshot-node)
                                                        (:output-key snapshot-node))
                                               (->event
                                                {:type :sheet/map-each-config-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body (cond-> {:sheet-id sheet-id
                                                                :node-id node-id
                                                                :source-key (:source-key snapshot-node)
                                                                :item-key (:item-key snapshot-node)
                                                                :output-key (:output-key snapshot-node)}
                                                         (:max-concurrency snapshot-node)
                                                         (assoc :max-concurrency (:max-concurrency snapshot-node)))}))])

                                   ;; sequence, fallback - no extra config needed
                                   [])]
                             (filterv some? (into [create-event name-event] config-events))))
                         node-records)]

        {:result/events
         (vec (concat node-deletion-events
                      key-deletion-events
                      key-declaration-events
                      node-events))}))))

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

   ;; Execute composite nodes (sequence/fallback)
   :sheet/execute-composite-node
   {:handler-fn #'execute-composite-node
    :topics [:sheet/node-execution-started]}

   ;; Execute parallel nodes
   :sheet/execute-parallel-node
   {:handler-fn #'execute-parallel-node
    :topics [:sheet/node-execution-started]}

   ;; Execute map-each nodes
   :sheet/execute-map-each-node
   {:handler-fn #'execute-map-each-node
    :topics [:sheet/node-execution-started]}

   ;; Handle child completion
   :sheet/handle-child-completion
   {:handler-fn #'handle-child-completion
    :topics [:sheet/node-execution-completed]}

   ;; Handle map-each child completion
   :sheet/handle-map-each-child-completion
   {:handler-fn #'handle-map-each-child-completion
    :topics [:sheet/node-execution-completed]}

   ;; Update blackboard
   :sheet/update-blackboard
   {:handler-fn #'update-blackboard-on-completion
    :topics [:sheet/node-execution-completed]}

   ;; Complete tree tick
   :sheet/complete-tree-tick
   {:handler-fn #'complete-tree-tick
    :topics [:sheet/node-execution-completed]}

   ;; Restore from snapshot (revert/stash restore)
   :sheet/restore-from-snapshot
   {:handler-fn #'restore-from-snapshot
    :topics [:sheet/draft-reverted :sheet/stash-restored]}})
