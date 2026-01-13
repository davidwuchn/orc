(ns ai.obney.workshop.sheet-service.core.read-models
  "Behavior Tree Sheet read models - projections built from events.

   This namespace provides:
   - Event type sets for querying
   - Multimethod projections for sheets, nodes, blackboard, and ticks
   - Helper functions for common queries"
  (:require [ai.obney.grain.event-store-v2.interface :as event-store]))

;; =============================================================================
;; Event Type Sets
;; =============================================================================

(def sheet-events
  "Events that affect sheet read model"
  #{:sheet/sheet-created
    :sheet/sheet-renamed
    :sheet/sheet-deleted
    :sheet/node-created    ;; first root node sets root-node-id
    :sheet/node-deleted})  ;; deleting root clears root-node-id

(def node-events
  "Events that affect node read model"
  #{:sheet/node-created
    :sheet/node-moved
    :sheet/node-reordered
    :sheet/node-deleted
    :sheet/node-name-set
    :sheet/node-instruction-set
    :sheet/node-io-set
    :sheet/node-decorators-set
    :sheet/node-check-set
    :sheet/node-executor-set
    :sheet/node-retry-set
    :sheet/parallel-config-set
    :sheet/map-each-config-set
    :sheet/node-execution-started
    :sheet/node-execution-completed})

(def blackboard-events
  "Events that affect blackboard read model"
  #{:sheet/key-declared
    :sheet/key-schema-updated
    :sheet/key-value-set
    :sheet/key-deleted})

(def tick-events
  "Events that affect tick/execution read model"
  #{:sheet/tree-tick-started
    :sheet/node-execution-started
    :sheet/node-execution-completed
    :sheet/tree-tick-completed
    :sheet/tick-cancelled})

(def all-sheet-events
  "All events for a complete sheet view"
  (clojure.set/union sheet-events node-events blackboard-events tick-events))

;; =============================================================================
;; Sheets Projection
;; =============================================================================

(defmulti sheets*
  "Apply event to sheets read model"
  (fn [_state event] (:event/type event)))

(defmethod sheets* :sheet/sheet-created
  [state event]
  (assoc state (:sheet-id event)
         {:id (:sheet-id event)
          :name (:name event)
          :root-node-id nil
          :created-at (str (:event/timestamp event))}))

(defmethod sheets* :sheet/sheet-renamed
  [state event]
  (assoc-in state [(:sheet-id event) :name] (:new-name event)))

(defmethod sheets* :sheet/node-created
  [state event]
  ;; When creating a node with no parent, it becomes the root
  (if (nil? (:parent-id event))
    (assoc-in state [(:sheet-id event) :root-node-id] (:node-id event))
    state))

(defmethod sheets* :sheet/node-deleted
  [state event]
  ;; If deleting the root node, clear root-node-id
  (let [sheet-id (:sheet-id event)
        node-id (:node-id event)
        current-root (get-in state [sheet-id :root-node-id])]
    (if (= current-root node-id)
      (assoc-in state [sheet-id :root-node-id] nil)
      state)))

(defmethod sheets* :sheet/sheet-deleted
  [state event]
  (dissoc state (:sheet-id event)))

(defmethod sheets* :default [state _] state)

(defn sheets
  "Build sheets read model from events"
  [initial-state events]
  (reduce sheets* initial-state events))

;; =============================================================================
;; Nodes Projection
;; =============================================================================

(defmulti nodes*
  "Apply event to nodes read model"
  (fn [_state event] (:event/type event)))

(defmethod nodes* :sheet/node-created
  [state event]
  (let [node-id (:node-id event)
        parent-id (:parent-id event)
        index (or (:index event) 0)]
    (-> state
        ;; Create the new node
        (assoc node-id
               {:id node-id
                :sheet-id (:sheet-id event)
                :type (:type event)
                :name (case (:type event)
                        :leaf "New Leaf"
                        :sequence "Sequence"
                        :fallback "Fallback"
                        :condition "Condition"
                        :parallel "Parallel"
                        :map-each "Map Each")
                :parent-id parent-id
                :children-ids []
                :status :idle
                ;; Leaf fields
                :instruction nil
                :reads []
                :writes []
                :decorators []
                :executor nil
                :model nil
                :fn nil
                :tools nil
                :retry nil
                ;; Condition fields
                :check nil
                ;; Parallel fields
                :success-policy nil
                :failure-policy nil
                ;; Map-each fields
                :source-key nil
                :item-key nil
                :output-key nil
                :max-concurrency nil
                ;; Execution tracking
                :last-error nil})
        ;; Add to parent's children if parent exists
        (cond-> parent-id
          (update-in [parent-id :children-ids]
                     (fn [children]
                       (let [children (or children [])]
                         (vec (concat (take index children)
                                      [node-id]
                                      (drop index children))))))))))

(defmethod nodes* :sheet/node-moved
  [state event]
  (let [node-id (:node-id event)
        old-parent-id (:old-parent-id event)
        new-parent-id (:new-parent-id event)
        index (:index event)]
    (-> state
        ;; Update node's parent-id
        (assoc-in [node-id :parent-id] new-parent-id)
        ;; Remove from old parent's children
        (cond-> old-parent-id
          (update-in [old-parent-id :children-ids]
                     (fn [children]
                       (vec (remove #(= % node-id) children)))))
        ;; Add to new parent's children at index
        (update-in [new-parent-id :children-ids]
                   (fn [children]
                     (let [children (or children [])]
                       (vec (concat (take index children)
                                    [node-id]
                                    (drop index children)))))))))

(defmethod nodes* :sheet/node-reordered
  [state event]
  (let [node-id (:node-id event)
        parent-id (get-in state [node-id :parent-id])
        new-index (:new-index event)]
    (if parent-id
      (update-in state [parent-id :children-ids]
                 (fn [children]
                   (let [without-node (vec (remove #(= % node-id) children))]
                     (vec (concat (take new-index without-node)
                                  [node-id]
                                  (drop new-index without-node))))))
      state)))

(defmethod nodes* :sheet/node-deleted
  [state event]
  (let [node-id (:node-id event)
        parent-id (get-in state [node-id :parent-id])]
    (-> state
        ;; Remove from parent's children
        (cond-> parent-id
          (update-in [parent-id :children-ids]
                     (fn [children]
                       (vec (remove #(= % node-id) children)))))
        ;; Remove the node itself
        (dissoc node-id))))

(defmethod nodes* :sheet/node-name-set
  [state event]
  (assoc-in state [(:node-id event) :name] (:name event)))

(defmethod nodes* :sheet/node-instruction-set
  [state event]
  (assoc-in state [(:node-id event) :instruction] (:instruction event)))

(defmethod nodes* :sheet/node-io-set
  [state event]
  (-> state
      (assoc-in [(:node-id event) :reads] (:reads event))
      (assoc-in [(:node-id event) :writes] (:writes event))))

(defmethod nodes* :sheet/node-decorators-set
  [state event]
  (assoc-in state [(:node-id event) :decorators] (:decorators event)))

(defmethod nodes* :sheet/node-check-set
  [state event]
  (assoc-in state [(:node-id event) :check] (:check event)))

(defmethod nodes* :sheet/node-executor-set
  [state event]
  (-> state
      (assoc-in [(:node-id event) :executor] (:executor event))
      (assoc-in [(:node-id event) :model] (:model event))
      (assoc-in [(:node-id event) :fn] (:fn event))
      (assoc-in [(:node-id event) :tools] (:tools event))))

(defmethod nodes* :sheet/node-retry-set
  [state event]
  (assoc-in state [(:node-id event) :retry] (:retry event)))

(defmethod nodes* :sheet/parallel-config-set
  [state event]
  (-> state
      (assoc-in [(:node-id event) :success-policy] (:success-policy event))
      (assoc-in [(:node-id event) :failure-policy] (:failure-policy event))))

(defmethod nodes* :sheet/map-each-config-set
  [state event]
  (-> state
      (assoc-in [(:node-id event) :source-key] (:source-key event))
      (assoc-in [(:node-id event) :item-key] (:item-key event))
      (assoc-in [(:node-id event) :output-key] (:output-key event))
      (assoc-in [(:node-id event) :max-concurrency] (:max-concurrency event))))

(defmethod nodes* :sheet/node-execution-started
  [state event]
  (assoc-in state [(:node-id event) :status] :running))

(defmethod nodes* :sheet/node-execution-completed
  [state event]
  (-> state
      (assoc-in [(:node-id event) :status] (:status event))
      (assoc-in [(:node-id event) :last-error] (:error event))))

(defmethod nodes* :default [state _] state)

(defn nodes
  "Build nodes read model from events"
  [initial-state events]
  (reduce nodes* initial-state events))

;; =============================================================================
;; Blackboard Projection
;; =============================================================================

(defmulti blackboard*
  "Apply event to blackboard read model"
  (fn [_state event] (:event/type event)))

(defmethod blackboard* :sheet/key-declared
  [state event]
  (assoc state (:key event)
         {:key (:key event)
          :schema (:schema event)
          :value nil
          :version 0}))

(defmethod blackboard* :sheet/key-schema-updated
  [state event]
  (assoc-in state [(:key event) :schema] (:schema event)))

(defmethod blackboard* :sheet/key-value-set
  [state event]
  (-> state
      (assoc-in [(:key event) :value] (:value event))
      (assoc-in [(:key event) :version] (:version event))))

(defmethod blackboard* :sheet/key-deleted
  [state event]
  (dissoc state (:key event)))

(defmethod blackboard* :default [state _] state)

(defn blackboard
  "Build blackboard read model from events"
  [initial-state events]
  (reduce blackboard* (or initial-state {}) events))

;; =============================================================================
;; Ticks Projection (for tracking execution state)
;; =============================================================================

(defmulti ticks*
  "Apply event to ticks read model"
  (fn [_state event] (:event/type event)))

(defmethod ticks* :sheet/tree-tick-started
  [state event]
  (let [tick-id (:tick-id event)
        iteration (or (:iteration event) 1)
        existing (get state tick-id)]
    (if existing
      ;; Re-tick - update iteration count
      (-> state
          (assoc-in [tick-id :iteration] iteration)
          (assoc-in [tick-id :status] :running))
      ;; New tick
      (assoc state tick-id
             {:id tick-id
              :sheet-id (:sheet-id event)
              :status :running
              :iteration iteration
              :started-at (str (:event/timestamp event))
              :completed-at nil
              :root-status nil}))))

(defmethod ticks* :sheet/tree-tick-completed
  [state event]
  (-> state
      (assoc-in [(:tick-id event) :status] :completed)
      (assoc-in [(:tick-id event) :root-status] (:root-status event))
      (assoc-in [(:tick-id event) :completed-at] (str (:event/timestamp event)))))

(defmethod ticks* :sheet/tick-cancelled
  [state event]
  (-> state
      (assoc-in [(:tick-id event) :status] :cancelled)
      (assoc-in [(:tick-id event) :cancelled-at] (str (:event/timestamp event)))))

(defmethod ticks* :default [state _] state)

(defn ticks
  "Build ticks read model from events"
  [initial-state events]
  (reduce ticks* (or initial-state {}) events))

;; =============================================================================
;; Query Helper Functions
;; =============================================================================

(defn get-sheet
  "Get a single sheet by ID"
  [event-store sheet-id]
  (let [events (event-store/read event-store
                                 {:types sheet-events
                                  :tags #{[:sheet sheet-id]}})]
    (get (sheets {} events) sheet-id)))

(defn get-sheets-all
  "Get all sheets"
  [event-store]
  (let [events (event-store/read event-store {:types sheet-events})]
    (vals (sheets {} events))))

(defn get-sheet-by-name
  "Find a sheet by name. Returns nil if not found."
  [event-store name]
  (->> (get-sheets-all event-store)
       (filter #(= (:name %) name))
       first))

(defn get-node
  "Get a single node by ID"
  [event-store sheet-id node-id]
  (let [events (event-store/read event-store
                                 {:types node-events
                                  :tags #{[:node node-id]}})]
    (get (nodes {} events) node-id)))

(defn get-nodes-for-sheet
  "Get all nodes in a sheet"
  [event-store sheet-id]
  (let [events (event-store/read event-store
                                 {:types node-events
                                  :tags #{[:sheet sheet-id]}})]
    (vals (nodes {} events))))

(defn get-nodes-by-id
  "Get all nodes in a sheet as a map keyed by node-id"
  [event-store sheet-id]
  (let [events (event-store/read event-store
                                 {:types node-events
                                  :tags #{[:sheet sheet-id]}})]
    (nodes {} events)))

(defn get-blackboard-for-sheet
  "Get the blackboard for a sheet"
  [event-store sheet-id]
  (let [events (event-store/read event-store
                                 {:types blackboard-events
                                  :tags #{[:sheet sheet-id]}})]
    (vals (blackboard nil events))))

(defn get-blackboard-by-key
  "Get the blackboard for a sheet as a map keyed by key name"
  [event-store sheet-id]
  (let [events (event-store/read event-store
                                 {:types blackboard-events
                                  :tags #{[:sheet sheet-id]}})]
    (blackboard nil events)))

(defn get-tick
  "Get a single tick by ID"
  [event-store tick-id]
  (let [events (event-store/read event-store
                                 {:types tick-events
                                  :tags #{[:tick tick-id]}})]
    (get (ticks {} events) tick-id)))

(defn is-tick-cancelled?
  "Check if a tick has been cancelled"
  [event-store tick-id]
  (let [tick (get-tick event-store tick-id)]
    (= :cancelled (:status tick))))

(defn get-ticks-for-sheet
  "Get all ticks for a sheet"
  [event-store sheet-id]
  (let [events (event-store/read event-store
                                 {:types tick-events
                                  :tags #{[:sheet sheet-id]}})]
    (vals (ticks {} events))))

(defn get-current-tick
  "Get the current running tick for a sheet, if any"
  [event-store sheet-id]
  (->> (get-ticks-for-sheet event-store sheet-id)
       (filter #(= :running (:status %)))
       (sort-by :started-at)
       last))

(defn get-root-node
  "Get the root node for a sheet"
  [event-store sheet-id]
  (let [sheet (get-sheet event-store sheet-id)]
    (when-let [root-id (:root-node-id sheet)]
      (get-node event-store sheet-id root-id))))

(defn get-children
  "Get child nodes of a parent node"
  [event-store sheet-id parent-id]
  (let [parent (get-node event-store sheet-id parent-id)]
    (when parent
      (mapv #(get-node event-store sheet-id %)
            (:children-ids parent)))))

(defn get-descendants
  "Get all descendant nodes of a node (recursive)"
  [event-store sheet-id node-id]
  (let [nodes-by-id (get-nodes-by-id event-store sheet-id)]
    (letfn [(descendants [nid]
              (let [node (get nodes-by-id nid)]
                (cons node
                      (mapcat descendants (:children-ids node)))))]
      (rest (descendants node-id))))) ;; rest to exclude the node itself

(defn is-descendant?
  "Check if potential-descendant is a descendant of ancestor-id"
  [nodes-by-id ancestor-id potential-descendant-id]
  (loop [to-check #{potential-descendant-id}
         visited #{}]
    (if (empty? to-check)
      false
      (let [current (first to-check)
            remaining (disj to-check current)]
        (if (visited current)
          (recur remaining visited)
          (let [node (get nodes-by-id current)
                parent-id (:parent-id node)]
            (cond
              (= parent-id ancestor-id) true
              (nil? parent-id) (recur remaining (conj visited current))
              :else (recur (conj remaining parent-id) (conj visited current)))))))))
