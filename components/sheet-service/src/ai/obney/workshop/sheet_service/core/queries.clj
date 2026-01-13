(ns ai.obney.workshop.sheet-service.core.queries
  "Behavior Tree Sheet query handlers.

   Fat Query Model: One query per screen, each returns all data needed.
   All queries return {:query/result ...} on success."
  (:require [ai.obney.workshop.sheet-service.core.read-models :as rm]
            [ai.obney.workshop.sheet-service.core.tree-layout :as layout]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.query-processor.interface :refer [defquery]]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; Sheets List Screen (/sheets)
;; =============================================================================

(defquery :sheet sheets-list-screen
  "Fat query for sheets list screen.
   Returns all sheets with basic metadata."
  [{:keys [event-store]}]
  (let [sheets (rm/get-sheets-all event-store)]
    {:query/result
     {:sheets (vec sheets)
      :total (count sheets)}}))

;; =============================================================================
;; Sheet View Screen (/sheet?sheet-id=...)
;; =============================================================================

(defquery :sheet sheet-view-screen
  "Fat query for sheet view screen.
   Returns sheet metadata, all nodes, blackboard, layout, and current tick."
  [{{:keys [sheet-id]} :query
    :keys [event-store]}]
  (let [sheet (rm/get-sheet event-store sheet-id)]
    (if-not sheet
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}
      (let [nodes (rm/get-nodes-for-sheet event-store sheet-id)
            nodes-by-id (into {} (map (juxt :id identity) nodes))
            blackboard (rm/get-blackboard-for-sheet event-store sheet-id)
            root-node (when-let [root-id (:root-node-id sheet)]
                        (get nodes-by-id root-id))
            tree-layout (if root-node
                          (layout/compute-layout root-node nodes-by-id)
                          [])
            current-tick (rm/get-current-tick event-store sheet-id)]
        {:query/result
         (cond-> {:sheet sheet
                  :nodes (vec nodes)
                  :blackboard (vec blackboard)
                  :layout tree-layout}
           current-tick (assoc :tick current-tick))}))))

;; =============================================================================
;; Export Sheet Query
;; =============================================================================

(defn- build-node-tree
  "Convert flat nodes map to nested tree structure for export."
  [nodes-by-id root-id]
  (when root-id
    (let [node (get nodes-by-id root-id)]
      (when node
        (cond-> {:type (:type node)
                 :name (:name node)}
          ;; Leaf-specific fields
          (= :leaf (:type node))
          (merge (cond-> {}
                   (:executor node) (assoc :executor (:executor node))
                   (:model node) (assoc :model (:model node))
                   (:fn node) (assoc :fn (:fn node))
                   (:instruction node) (assoc :instruction (:instruction node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (seq (:writes node)) (assoc :writes (:writes node))
                   (:retry node) (assoc :retry (:retry node))))
          ;; Condition-specific
          (= :condition (:type node))
          (merge (cond-> {}
                   (:check node) (assoc :check (:check node))
                   (:on-fail node) (assoc :on-fail (:on-fail node))))
          ;; Parallel-specific
          (= :parallel (:type node))
          (merge {:success-policy (or (:success-policy node) :all)
                  :failure-policy (or (:failure-policy node) :any)})
          ;; Map-each-specific
          (= :map-each (:type node))
          (merge (cond-> {}
                   (:source-key node) (assoc :source-key (:source-key node))
                   (:item-key node) (assoc :item-key (:item-key node))
                   (:output-key node) (assoc :output-key (:output-key node))
                   (:max-concurrency node) (assoc :max-concurrency (:max-concurrency node))))
          ;; Children for composite nodes
          (seq (:children-ids node))
          (assoc :children
                 (vec (keep #(build-node-tree nodes-by-id %) (:children-ids node)))))))))

(defquery :sheet export-sheet
  "Export a sheet as a portable EDN structure for download/backup."
  [{{:keys [sheet-id]} :query
    :keys [event-store]}]
  (let [sheet (rm/get-sheet event-store sheet-id)]
    (if-not sheet
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}
      (let [nodes (rm/get-nodes-by-id event-store sheet-id)
            blackboard (rm/get-blackboard-for-sheet event-store sheet-id)]
        {:query/result
         {:version 1
          :exported-at (str (time/now))
          :sheet {:name (:name sheet)
                  :id sheet-id}
          :blackboard-schema (into {}
                                   (map (fn [bb] [(keyword (:key bb)) (:schema bb)])
                                        blackboard))
          :nodes (build-node-tree nodes (:root-node-id sheet))}}))))
