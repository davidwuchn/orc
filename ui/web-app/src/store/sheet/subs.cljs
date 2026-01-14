(ns store.sheet.subs
  "Behavior Tree Sheet subscriptions - derived state for tree views."
  (:require [re-frame.core :as rf]))

;; =============================================================================
;; Sheet List Subscriptions
;; =============================================================================

(rf/reg-sub
  ::sheets
  (fn [db _]
    (get-in db [:sheets :list] [])))

(rf/reg-sub
  ::sheets-loading?
  (fn [db _]
    (get-in db [:sheets :loading?] false)))

(rf/reg-sub
  ::sheets-error
  (fn [db _]
    (get-in db [:sheets :error])))

(rf/reg-sub
  ::sheets-total
  (fn [db _]
    (get-in db [:sheets :total] 0)))

;; =============================================================================
;; Current Sheet Subscriptions
;; =============================================================================

(rf/reg-sub
  ::sheet
  (fn [db _]
    (get-in db [:sheet :data])))

(rf/reg-sub
  ::sheet-loading?
  (fn [db _]
    (get-in db [:sheet :loading?] false)))

(rf/reg-sub
  ::sheet-error
  (fn [db _]
    (get-in db [:sheet :error])))

(rf/reg-sub
  ::sheet-ticking?
  (fn [db _]
    (get-in db [:sheet :ticking?] false)))

(rf/reg-sub
  ::tick-iteration
  (fn [db _]
    (get-in db [:sheet :tick-iteration] 0)))

(rf/reg-sub
  ::tick-budget
  (fn [db _]
    (get-in db [:sheet :tick-budget] 10)))

(rf/reg-sub
  ::tick-progress
  :<- [::tick-iteration]
  :<- [::tick-budget]
  (fn [[iteration budget] _]
    {:iteration iteration
     :budget budget
     :percent (if (pos? budget)
                (min 100 (* 100 (/ iteration budget)))
                0)}))

;; =============================================================================
;; Nodes Subscriptions
;; =============================================================================

(rf/reg-sub
  ::nodes
  (fn [db _]
    (get-in db [:sheet :nodes] {})))

(rf/reg-sub
  ::nodes-list
  :<- [::nodes]
  (fn [nodes _]
    (vals nodes)))

(rf/reg-sub
  ::node
  :<- [::nodes]
  (fn [nodes [_ node-id]]
    (get nodes node-id)))

(rf/reg-sub
  ::root-node
  :<- [::sheet]
  :<- [::nodes]
  (fn [[sheet nodes] _]
    (when-let [root-id (:root-node-id sheet)]
      (get nodes root-id))))

(rf/reg-sub
  ::selected-node-id
  (fn [db _]
    (get-in db [:sheet :selected-node-id])))

(rf/reg-sub
  ::selected-node
  :<- [::nodes]
  :<- [::selected-node-id]
  (fn [[nodes selected-id] _]
    (when selected-id
      (get nodes selected-id))))

(rf/reg-sub
  ::editing-node-id
  (fn [db _]
    (get-in db [:sheet :editing-node-id])))

;; =============================================================================
;; Tree Layout Subscriptions
;; =============================================================================

(rf/reg-sub
  ::layout
  (fn [db _]
    (get-in db [:sheet :layout] [])))

(rf/reg-sub
  ::layout-by-node-id
  :<- [::layout]
  (fn [layout _]
    (into {} (map (juxt :node-id identity) layout))))

(rf/reg-sub
  ::tree-depth
  :<- [::layout]
  (fn [layout _]
    (if (empty? layout)
      0
      (inc (apply max (map :row layout))))))

(rf/reg-sub
  ::grid-dimensions
  :<- [::tree-depth]
  (fn [depth _]
    {:rows (max 1 depth)
     :cols 12})) ;; Default 12 columns for grid

;; =============================================================================
;; Blackboard Subscriptions
;; =============================================================================

(rf/reg-sub
  ::blackboard
  (fn [db _]
    (get-in db [:sheet :blackboard] {})))

(rf/reg-sub
  ::blackboard-list
  :<- [::blackboard]
  (fn [blackboard _]
    (vals blackboard)))

(rf/reg-sub
  ::blackboard-entry
  :<- [::blackboard]
  (fn [blackboard [_ key]]
    (get blackboard key)))

(rf/reg-sub
  ::blackboard-keys
  :<- [::blackboard]
  (fn [blackboard _]
    (keys blackboard)))

;; =============================================================================
;; Node Status Helpers
;; =============================================================================

(rf/reg-sub
  ::node-is-leaf?
  :<- [::nodes]
  (fn [nodes [_ node-id]]
    (= :leaf (get-in nodes [node-id :type]))))

(rf/reg-sub
  ::node-is-running?
  :<- [::nodes]
  (fn [nodes [_ node-id]]
    (= :running (get-in nodes [node-id :status]))))

(rf/reg-sub
  ::node-can-tick?
  :<- [::nodes]
  (fn [nodes [_ node-id]]
    (let [node (get nodes node-id)]
      (and (= :leaf (:type node))
           (some? (:instruction node))
           (not= :running (:status node))))))

(rf/reg-sub
  ::node-children
  :<- [::nodes]
  (fn [nodes [_ node-id]]
    (let [node (get nodes node-id)]
      (mapv #(get nodes %) (:children-ids node)))))

;; =============================================================================
;; Node IO (reads/writes) Helpers
;; =============================================================================

(rf/reg-sub
  ::node-reads
  :<- [::nodes]
  (fn [nodes [_ node-id]]
    (get-in nodes [node-id :reads] [])))

(rf/reg-sub
  ::node-writes
  :<- [::nodes]
  (fn [nodes [_ node-id]]
    (get-in nodes [node-id :writes] [])))

(rf/reg-sub
  ::keys-used-by-node
  :<- [::nodes]
  (fn [nodes [_ node-id]]
    (let [node (get nodes node-id)]
      (set (concat (:reads node) (:writes node))))))

;; =============================================================================
;; Field Type Options
;; =============================================================================

(rf/reg-sub
  ::field-types
  (fn [_ _]
    [{:value :text :label "Text"}
     {:value :number :label "Number"}
     {:value :yesno :label "Yes/No"}
     {:value :list :label "List"}
     {:value :document :label "Document"}
     {:value :image :label "Image"}
     {:value :table :label "Table"}]))

(rf/reg-sub
  ::node-types
  (fn [_ _]
    [{:value :leaf :label "Leaf" :description "Executes an instruction"}
     {:value :sequence :label "Sequence" :description "Runs children until one fails"}
     {:value :fallback :label "Fallback" :description "Runs children until one succeeds"}
     {:value :condition :label "Condition" :description "Checks a blackboard value"}
     {:value :parallel :label "Parallel" :description "Runs all children concurrently"}
     {:value :map-each :label "Map Each" :description "Iterates over a list"}]))

;; =============================================================================
;; Status Color Mapping
;; =============================================================================

(rf/reg-sub
  ::status-color
  (fn [_ [_ status]]
    (case status
      :idle "gray"
      :running "blue"
      :success "green"
      :failure "red"
      "gray")))

;; =============================================================================
;; Versioning Subscriptions
;; =============================================================================

(rf/reg-sub
  ::version-info
  :<- [::sheet]
  (fn [sheet _]
    {:published-version (:published-version sheet)
     :draft-dirty? (:draft-dirty? sheet)
     :execution-mode (or (:execution-mode sheet) :draft)
     :has-stash? (:has-stash? sheet)}))

(rf/reg-sub
  ::published-version
  :<- [::version-info]
  (fn [version-info _]
    (:published-version version-info)))

(rf/reg-sub
  ::draft-dirty?
  :<- [::version-info]
  (fn [version-info _]
    (:draft-dirty? version-info)))

(rf/reg-sub
  ::execution-mode
  :<- [::version-info]
  (fn [version-info _]
    (:execution-mode version-info)))

(rf/reg-sub
  ::has-stash?
  :<- [::version-info]
  (fn [version-info _]
    (:has-stash? version-info)))

(rf/reg-sub
  ::can-publish?
  :<- [::sheet]
  (fn [sheet _]
    ;; Can publish if there's a root node
    (some? (:root-node-id sheet))))

(rf/reg-sub
  ::can-set-published-mode?
  :<- [::published-version]
  (fn [published-version _]
    ;; Can only set published mode if there's a published version
    (some? published-version)))

(rf/reg-sub
  ::version-history
  (fn [db _]
    (get-in db [:sheet :version-history])))

(rf/reg-sub
  ::version-history-loading?
  (fn [db _]
    (get-in db [:sheet :version-history-loading?] false)))

(rf/reg-sub
  ::versions-list
  :<- [::version-history]
  (fn [history _]
    (:versions history)))

(rf/reg-sub
  ::selected-version
  (fn [db _]
    (get-in db [:sheet :selected-version])))

(rf/reg-sub
  ::version-loading?
  (fn [db _]
    (get-in db [:sheet :version-loading?] false)))

(rf/reg-sub
  ::stash
  (fn [db _]
    (get-in db [:sheet :stash])))

(rf/reg-sub
  ::stash-loading?
  (fn [db _]
    (get-in db [:sheet :stash-loading?] false)))

(rf/reg-sub
  ::publishing?
  (fn [db _]
    (get-in db [:sheet :publishing?] false)))

(rf/reg-sub
  ::reverting?
  (fn [db _]
    (get-in db [:sheet :reverting?] false)))

(rf/reg-sub
  ::restoring-stash?
  (fn [db _]
    (get-in db [:sheet :restoring-stash?] false)))
