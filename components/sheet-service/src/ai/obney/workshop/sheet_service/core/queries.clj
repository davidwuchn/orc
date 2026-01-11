(ns ai.obney.workshop.sheet-service.core.queries
  "Behavior Tree Sheet query handlers.

   Fat Query Model: One query per screen, each returns all data needed.
   All queries return {:query/result ...} on success."
  (:require [ai.obney.workshop.sheet-service.core.read-models :as rm]
            [ai.obney.workshop.sheet-service.core.tree-layout :as layout]
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
   Returns sheet metadata, all nodes, blackboard, and layout."
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
                          [])]
        {:query/result
         {:sheet sheet
          :nodes (vec nodes)
          :blackboard (vec blackboard)
          :layout tree-layout}}))))
