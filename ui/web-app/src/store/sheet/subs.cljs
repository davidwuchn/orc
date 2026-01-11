(ns store.sheet.subs
  "Sheet store subscriptions - derived state for sheet views."
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

;; =============================================================================
;; Cells Subscriptions
;; =============================================================================

(rf/reg-sub
  ::cells
  (fn [db _]
    (get-in db [:sheet :cells] {})))

(rf/reg-sub
  ::cells-list
  :<- [::cells]
  (fn [cells _]
    (vals cells)))

(rf/reg-sub
  ::cell
  :<- [::cells]
  (fn [cells [_ cell-id]]
    (get cells cell-id)))

(rf/reg-sub
  ::selected-cell-id
  (fn [db _]
    (get-in db [:sheet :selected-cell-id])))

(rf/reg-sub
  ::selected-cell
  :<- [::cells]
  :<- [::selected-cell-id]
  (fn [[cells selected-id] _]
    (when selected-id
      (get cells selected-id))))

;; =============================================================================
;; Dependency Graph Subscriptions
;; =============================================================================

(rf/reg-sub
  ::dependency-graph
  (fn [db _]
    (get-in db [:sheet :dependency-graph] {:nodes {} :edges #{}})))

(rf/reg-sub
  ::cell-dependents
  :<- [::dependency-graph]
  (fn [graph [_ cell-id]]
    (->> (:edges graph)
         (filter #(= (:from %) cell-id))
         (map :to)
         set)))

(rf/reg-sub
  ::cell-dependencies
  :<- [::dependency-graph]
  (fn [graph [_ cell-id]]
    (->> (:edges graph)
         (filter #(= (:to %) cell-id))
         (map :from)
         set)))

;; =============================================================================
;; Cell Grid Subscriptions (for rendering)
;; =============================================================================

(rf/reg-sub
  ::cells-by-address
  :<- [::cells-list]
  (fn [cells _]
    (into {} (map (juxt :address identity) cells))))

(rf/reg-sub
  ::grid-bounds
  :<- [::cells-list]
  (fn [cells _]
    "Calculate the min/max row and column bounds for the grid."
    (if (empty? cells)
      {:min-col "A" :max-col "E" :min-row 1 :max-row 10}
      (let [addresses (map :address cells)
            parse-address (fn [addr]
                            (let [[_ col row] (re-matches #"([A-Z]+)(\d+)" addr)]
                              {:col col :row (js/parseInt row)}))
            parsed (map parse-address addresses)
            cols (map :col parsed)
            rows (map :row parsed)]
        {:min-col (apply min-key identity cols)
         :max-col (apply max-key identity cols)
         :min-row (apply min rows)
         :max-row (apply max rows)}))))

;; =============================================================================
;; Cell Status Helpers
;; =============================================================================

(rf/reg-sub
  ::cell-is-formula?
  :<- [::cells]
  (fn [cells [_ cell-id]]
    (some? (get-in cells [cell-id :signature]))))

(rf/reg-sub
  ::cell-is-executing?
  :<- [::cells]
  (fn [cells [_ cell-id]]
    (= :running (get-in cells [cell-id :execution-status]))))

(rf/reg-sub
  ::cell-can-execute?
  :<- [::cells]
  (fn [cells [_ cell-id]]
    (let [cell (get cells cell-id)]
      (and (:signature cell)
           (let [required-inputs (set (map :name (get-in cell [:signature :inputs])))
                 bound-inputs (set (keys (:input-bindings cell)))]
             (every? bound-inputs required-inputs))
           (not= :running (:execution-status cell))))))

;; =============================================================================
;; Field Type Options
;; =============================================================================

(rf/reg-sub
  ::field-types
  (fn [_ _]
    [{:value :text :label "Text"}
     {:value :number :label "Number"}
     {:value :list :label "List"}
     {:value :document :label "Document"}
     {:value :image :label "Image"}
     {:value :table :label "Table"}
     {:value :yes-no :label "Yes/No"}]))

;; =============================================================================
;; Available Source Cells (for binding inputs)
;; =============================================================================

(rf/reg-sub
  ::available-source-cells
  :<- [::cells-list]
  :<- [::selected-cell-id]
  (fn [[cells selected-id] _]
    "Get cells that can be used as input sources (excludes selected cell)."
    (->> cells
         (remove #(= (:id %) selected-id))
         (filter #(seq (:fields %)))  ;; Only cells with fields
         (map (fn [cell]
                {:cell-id (:id cell)
                 :address (:address cell)
                 :fields (keys (:fields cell))})))))
