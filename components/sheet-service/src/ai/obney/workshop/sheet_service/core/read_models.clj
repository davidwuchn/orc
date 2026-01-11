(ns ai.obney.workshop.sheet-service.core.read-models
  "Sheet Service read models - projections built from events.

   This namespace provides:
   - Event type sets for querying
   - Multimethod projections for sheets, cells, executions, and dependency graph
   - Helper functions for common queries"
  (:require [ai.obney.grain.event-store-v2.interface :as event-store]))

;; =============================================================================
;; Event Type Sets
;; =============================================================================

(def sheet-events
  "Events that affect sheet read model"
  #{:sheet/sheet-created
    :sheet/sheet-renamed
    :sheet/sheet-deleted})

(def cell-events
  "Events that affect cell read model"
  #{:sheet/cell-created
    :sheet/cell-literal-set
    :sheet/cell-signature-defined
    :sheet/input-bound
    :sheet/input-unbound
    :sheet/cell-execution-requested
    :sheet/cell-execution-completed
    :sheet/cell-execution-failed
    :sheet/cell-execution-cancelled
    :sheet/cell-deleted})

(def execution-events
  "Events that affect execution history read model"
  #{:sheet/cell-execution-requested
    :sheet/cell-execution-completed
    :sheet/cell-execution-failed
    :sheet/cell-execution-cancelled})

(def dependency-events
  "Events that affect dependency graph read model"
  #{:sheet/cell-created
    :sheet/input-bound
    :sheet/input-unbound
    :sheet/cell-deleted})

(def all-sheet-events
  "All events for a complete sheet view"
  (into sheet-events cell-events))

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
          :description (:description event)
          :created-at (str (:event/timestamp event))
          :updated-at (str (:event/timestamp event))}))

(defmethod sheets* :sheet/sheet-renamed
  [state event]
  (-> state
      (assoc-in [(:sheet-id event) :name] (:new-name event))
      (assoc-in [(:sheet-id event) :updated-at] (str (:event/timestamp event)))))

(defmethod sheets* :sheet/sheet-deleted
  [state event]
  (dissoc state (:sheet-id event)))

(defmethod sheets* :default [state _] state)

(defn sheets
  "Build sheets read model from events"
  [initial-state events]
  (reduce sheets* initial-state events))

;; =============================================================================
;; Cells Projection
;; =============================================================================

(defmulti cells*
  "Apply event to cells read model"
  (fn [_state event] (:event/type event)))

(defmethod cells* :sheet/cell-created
  [state event]
  (assoc state (:cell-id event)
         {:id (:cell-id event)
          :sheet-id (:sheet-id event)
          :address (:address event)
          :fields {}
          :signature nil
          :input-bindings {}
          :status :valid
          :execution-status :idle
          :last-execution-id nil
          :last-error nil
          :created-at (str (:event/timestamp event))
          :updated-at (str (:event/timestamp event))}))

(defmethod cells* :sheet/cell-literal-set
  [state event]
  (let [cell-id (:cell-id event)]
    (-> state
        (assoc-in [cell-id :fields] (:fields event))
        (assoc-in [cell-id :signature] nil)
        (assoc-in [cell-id :input-bindings] {})
        (assoc-in [cell-id :status] :valid)
        (assoc-in [cell-id :updated-at] (str (:event/timestamp event))))))

(defmethod cells* :sheet/cell-signature-defined
  [state event]
  (let [cell-id (:cell-id event)]
    (-> state
        (assoc-in [cell-id :signature] (:signature event))
        (assoc-in [cell-id :fields] {})
        (assoc-in [cell-id :status] :stale)
        (assoc-in [cell-id :updated-at] (str (:event/timestamp event))))))

(defmethod cells* :sheet/input-bound
  [state event]
  (let [cell-id (:cell-id event)]
    (-> state
        (assoc-in [cell-id :input-bindings (:input-name event)]
                  {:source-cell-id (:source-cell-id event)
                   :source-field-name (:source-field-name event)})
        (assoc-in [cell-id :status] :stale)
        (assoc-in [cell-id :updated-at] (str (:event/timestamp event))))))

(defmethod cells* :sheet/input-unbound
  [state event]
  (let [cell-id (:cell-id event)]
    (-> state
        (update-in [cell-id :input-bindings] dissoc (:input-name event))
        (assoc-in [cell-id :status] :stale)
        (assoc-in [cell-id :updated-at] (str (:event/timestamp event))))))

(defmethod cells* :sheet/cell-execution-requested
  [state event]
  (let [cell-id (:cell-id event)]
    (-> state
        (assoc-in [cell-id :execution-status] :running)
        (assoc-in [cell-id :last-execution-id] (:execution-id event))
        (assoc-in [cell-id :updated-at] (str (:event/timestamp event))))))

(defmethod cells* :sheet/cell-execution-completed
  [state event]
  (let [cell-id (:cell-id event)]
    (-> state
        (assoc-in [cell-id :fields] (:outputs event))
        (assoc-in [cell-id :execution-status] :completed)
        (assoc-in [cell-id :status] :valid)
        (assoc-in [cell-id :last-error] nil)
        (assoc-in [cell-id :updated-at] (str (:event/timestamp event))))))

(defmethod cells* :sheet/cell-execution-failed
  [state event]
  (let [cell-id (:cell-id event)]
    (-> state
        (assoc-in [cell-id :execution-status] :failed)
        (assoc-in [cell-id :status] :error)
        (assoc-in [cell-id :last-error] (:error event))
        (assoc-in [cell-id :updated-at] (str (:event/timestamp event))))))

(defmethod cells* :sheet/cell-execution-cancelled
  [state event]
  (when-let [cell-id (some (fn [[id cell]]
                             (when (= (:execution-id event) (:last-execution-id cell))
                               id))
                           state)]
    (-> state
        (assoc-in [cell-id :execution-status] :idle)
        (assoc-in [cell-id :updated-at] (str (:event/timestamp event))))))

(defmethod cells* :sheet/cell-deleted
  [state event]
  (dissoc state (:cell-id event)))

(defmethod cells* :default [state _] state)

(defn cells
  "Build cells read model from events"
  [initial-state events]
  (reduce cells* initial-state events))

;; =============================================================================
;; Executions Projection
;; =============================================================================

(defmulti executions*
  "Apply event to executions read model"
  (fn [_state event] (:event/type event)))

(defmethod executions* :sheet/cell-execution-requested
  [state event]
  (assoc state (:execution-id event)
         {:id (:execution-id event)
          :sheet-id (:sheet-id event)
          :cell-id (:cell-id event)
          :inputs (:inputs event)
          :signature (:signature event)
          :status :running
          :outputs nil
          :error nil
          :duration-ms nil
          :started-at (str (:event/timestamp event))
          :completed-at nil}))

(defmethod executions* :sheet/cell-execution-completed
  [state event]
  (let [exec-id (:execution-id event)]
    (-> state
        (assoc-in [exec-id :status] :completed)
        (assoc-in [exec-id :outputs] (:outputs event))
        (assoc-in [exec-id :duration-ms] (:duration-ms event))
        (assoc-in [exec-id :completed-at] (str (:event/timestamp event))))))

(defmethod executions* :sheet/cell-execution-failed
  [state event]
  (let [exec-id (:execution-id event)]
    (-> state
        (assoc-in [exec-id :status] :failed)
        (assoc-in [exec-id :error] (:error event))
        (assoc-in [exec-id :duration-ms] (:duration-ms event))
        (assoc-in [exec-id :completed-at] (str (:event/timestamp event))))))

(defmethod executions* :sheet/cell-execution-cancelled
  [state event]
  (let [exec-id (:execution-id event)]
    (-> state
        (assoc-in [exec-id :status] :cancelled)
        (assoc-in [exec-id :completed-at] (str (:event/timestamp event))))))

(defmethod executions* :default [state _] state)

(defn executions
  "Build executions read model from events"
  [initial-state events]
  (reduce executions* initial-state events))

;; =============================================================================
;; Dependency Graph Projection
;; =============================================================================

(defmulti dependency-graph*
  "Apply event to dependency graph read model"
  (fn [_state event] (:event/type event)))

(defmethod dependency-graph* :sheet/cell-created
  [state event]
  (assoc-in state [:nodes (:cell-id event)] #{}))

(defmethod dependency-graph* :sheet/input-bound
  [state event]
  (update-in state [:edges]
             (fnil conj #{})
             {:from (:source-cell-id event)
              :to (:cell-id event)
              :input-name (:input-name event)}))

(defmethod dependency-graph* :sheet/input-unbound
  [state event]
  (update state :edges
          (fn [edges]
            (set (remove #(and (= (:to %) (:cell-id event))
                               (= (:input-name %) (:input-name event)))
                         edges)))))

(defmethod dependency-graph* :sheet/cell-deleted
  [state event]
  (let [cell-id (:cell-id event)]
    (-> state
        (update :nodes dissoc cell-id)
        (update :edges (fn [edges]
                         (set (remove #(or (= (:from %) cell-id)
                                           (= (:to %) cell-id))
                                      edges)))))))

(defmethod dependency-graph* :default [state _] state)

(defn dependency-graph
  "Build dependency graph read model from events"
  [initial-state events]
  (reduce dependency-graph* (or initial-state {:nodes {} :edges #{}}) events))

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

(defn get-cell
  "Get a single cell by ID"
  [event-store sheet-id cell-id]
  (let [events (event-store/read event-store
                                 {:types cell-events
                                  :tags #{[:cell cell-id]}})]
    (get (cells {} events) cell-id)))

(defn get-cells-for-sheet
  "Get all cells in a sheet"
  [event-store sheet-id]
  (let [events (event-store/read event-store
                                 {:types cell-events
                                  :tags #{[:sheet sheet-id]}})]
    (vals (cells {} events))))

(defn get-dependency-graph-for-sheet
  "Get the dependency graph for a sheet"
  [event-store sheet-id]
  (let [events (event-store/read event-store
                                 {:types dependency-events
                                  :tags #{[:sheet sheet-id]}})]
    (dependency-graph nil events)))

(defn get-execution
  "Get a single execution by ID"
  [event-store execution-id]
  (let [events (event-store/read event-store
                                 {:types execution-events
                                  :tags #{[:execution execution-id]}})]
    (get (executions {} events) execution-id)))

(defn get-executions-for-cell
  "Get all executions for a cell"
  [event-store cell-id]
  (let [events (event-store/read event-store
                                 {:types execution-events
                                  :tags #{[:cell cell-id]}})]
    (->> (vals (executions {} events))
         (sort-by :started-at)
         reverse)))

(defn get-executions-for-sheet
  "Get all executions in a sheet"
  [event-store sheet-id]
  (let [events (event-store/read event-store
                                 {:types execution-events
                                  :tags #{[:sheet sheet-id]}})]
    (->> (vals (executions {} events))
         (sort-by :started-at)
         reverse)))

(defn get-dependent-cells
  "Get cells that depend on the given cell"
  [event-store sheet-id cell-id]
  (let [graph (get-dependency-graph-for-sheet event-store sheet-id)]
    (->> (:edges graph)
         (filter #(= (:from %) cell-id))
         (map :to)
         set)))

(defn get-sheet-view
  "Get complete sheet view with all cells and dependency graph"
  [event-store sheet-id]
  (let [sheet-evts (event-store/read event-store
                                     {:types sheet-events
                                      :tags #{[:sheet sheet-id]}})
        cell-evts (event-store/read event-store
                                    {:types cell-events
                                     :tags #{[:sheet sheet-id]}})
        dep-evts (event-store/read event-store
                                   {:types dependency-events
                                    :tags #{[:sheet sheet-id]}})
        sheet (get (sheets {} sheet-evts) sheet-id)]
    (when sheet
      {:sheet sheet
       :cells (vals (cells {} cell-evts))
       :dependency-graph (dependency-graph nil dep-evts)})))
