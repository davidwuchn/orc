(ns components.runs.runs-list
  "Runs list component with DataTable."
  (:require [uix.core :as uix :refer [defui $]]
            [re-frame.core :as rf]
            [re-frame.uix :refer [use-subscribe]]
            ["/gen/shadcn/components/ui/data-table" :as data-table]
            ["/gen/shadcn/components/ui/select" :as select]
            [components.context.interface :as context]
            [store.runs.events :as runs-events]
            [store.runs.subs :as runs-subs]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn format-timestamp [ts]
  (when ts
    (let [date (if (inst? ts) ts (js/Date. ts))]
      (.toLocaleString date))))

(defn format-duration [ms]
  (when ms
    (cond
      (< ms 1000) (str ms "ms")
      (< ms 60000) (str (.toFixed (/ ms 1000) 1) "s")
      :else (str (.toFixed (/ ms 60000) 1) "m"))))

(defn status-variant [status]
  (case status
    :success "default"
    :failure "destructive"
    :timeout "secondary"
    "outline"))

(defn status-class [status]
  (case status
    :success "bg-green-100 text-green-800 border-green-200"
    :failure "bg-red-100 text-red-800 border-red-200"
    :timeout "bg-yellow-100 text-yellow-800 border-yellow-200"
    "bg-gray-100 text-gray-800 border-gray-200"))

;; =============================================================================
;; Table Columns
;; =============================================================================

(def columns
  #js [#js {:accessorKey "sheet-name"
            :header "Sheet"
            :size 150
            :cell (fn [info]
                    (let [value (.getValue info)]
                      ($ :span {:class "font-medium truncate"} value)))}

       #js {:accessorKey "status"
            :header "Status"
            :size 100
            :cell (fn [info]
                    (let [status (keyword (.getValue info))]
                      ($ :span {:class (str "inline-flex items-center px-2 py-0.5 rounded text-xs font-medium "
                                            (status-class status))}
                         (name status))))}

       #js {:accessorKey "started-at"
            :header "Started"
            :size 160
            :cell (fn [info]
                    (let [value (.getValue info)]
                      ($ :span {:class "text-sm text-gray-500"}
                         (format-timestamp value))))}

       #js {:accessorKey "duration-ms"
            :header "Duration"
            :size 80
            :cell (fn [info]
                    (let [value (.getValue info)]
                      ($ :span {:class "text-sm font-mono"}
                         (format-duration value))))}

       #js {:accessorKey "node-count"
            :header "Nodes"
            :size 60
            :cell (fn [info]
                    (let [value (.getValue info)]
                      ($ :span {:class "text-sm text-gray-500"} value)))}

       #js {:accessorKey "version-number"
            :header "Version"
            :size 80
            :cell (fn [info]
                    (let [value (.getValue info)]
                      ($ :span {:class "text-sm text-gray-500"}
                         (if value (str "v" value) "draft"))))}])

;; =============================================================================
;; Status Filter Component
;; =============================================================================

(defui status-filter []
  (let [ctx (context/use-context)
        api-client (:api/client ctx)
        current-status (use-subscribe [::runs-subs/status-filter])
        options (use-subscribe [::runs-subs/status-options])]
    ($ :div {:class "flex items-center gap-2"}
       ($ :span {:class "text-sm text-gray-500"} "Status:")
       ($ select/Select {:value (or (some-> current-status name) "all")
                         :onValueChange (fn [value]
                                          (let [status (if (= value "all") nil (keyword value))]
                                            (rf/dispatch [::runs-events/set-status-filter api-client status])))}
          ($ select/SelectTrigger {:class "w-32"}
             ($ select/SelectValue {:placeholder "All"}))
          ($ select/SelectContent
             (for [{:keys [value label]} options]
               ($ select/SelectItem {:key (or (some-> value name) "all")
                                     :value (or (some-> value name) "all")}
                  label)))))))

;; =============================================================================
;; Runs List Component
;; =============================================================================

(defui runs-list [{:keys [on-select]}]
  (let [runs (use-subscribe [::runs-subs/runs-list])
        total (use-subscribe [::runs-subs/runs-total])
        ;; Convert runs to JS objects for the table
        runs-data (clj->js (mapv (fn [r]
                                   {"trace-id" (str (:trace-id r))
                                    "sheet-id" (str (:sheet-id r))
                                    "sheet-name" (:sheet-name r)
                                    "status" (name (:status r))
                                    "started-at" (:started-at r)
                                    "duration-ms" (:duration-ms r)
                                    "node-count" (:node-count r)
                                    "version-number" (:version-number r)})
                                 runs))]
    ($ :div {:class "h-full flex flex-col p-4"}
       ;; Toolbar
       ($ :div {:class "flex items-center justify-between mb-4"}
          ($ :div {:class "text-sm text-gray-500"}
             (str total " run" (when (not= total 1) "s")))
          ($ status-filter))

       ;; Table
       ($ :div {:class "flex-1 overflow-auto"}
          ($ data-table/DataTable
             {:columns columns
              :data runs-data
              :searchKey "sheet-name"
              :searchPlaceholder "Search by sheet name..."
              :pageSize 20
              :emptyMessage "No runs found"
              :onRowClick (fn [row]
                            (let [trace-id (uuid (aget row "trace-id"))]
                              (when on-select
                                (on-select trace-id))))})))))
