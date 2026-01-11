(ns ai.obney.workshop.sheet-service.core.queries
  "Sheet Service query handlers.

   Fat Query Model: One query per screen, each returns all data needed.
   All queries return {:query/result ...} on success."
  (:require [ai.obney.workshop.sheet-service.core.read-models :as rm]
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
;; Sheet View Screen (/sheet/:sheet-id)
;; =============================================================================

(defquery :sheet sheet-view-screen
  "Fat query for sheet view screen.
   Returns sheet metadata, all cells, and dependency graph."
  [{{:keys [sheet-id]} :query
    :keys [event-store]}]
  (let [sheet (rm/get-sheet event-store sheet-id)]
    (if-not sheet
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}
      (let [cells (rm/get-cells-for-sheet event-store sheet-id)
            dep-graph (rm/get-dependency-graph-for-sheet event-store sheet-id)]
        {:query/result
         {:sheet sheet
          :cells (vec cells)
          :dependency-graph dep-graph}}))))
