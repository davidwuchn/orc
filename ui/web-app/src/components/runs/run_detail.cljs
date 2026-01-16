(ns components.runs.run-detail
  "Run detail component with tabs for flame graph and timeline."
  (:require [uix.core :as uix :refer [defui $]]
            [components.runs.gantt-chart :as gantt-chart]))

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

(defn- ->kw [v]
  (cond (keyword? v) v (string? v) (keyword v) :else v))

(defn status-class [status]
  (case (->kw status)
    :success "bg-green-100 text-green-800 border-green-200"
    :failure "bg-red-100 text-red-800 border-red-200"
    :timeout "bg-yellow-100 text-yellow-800 border-yellow-200"
    "bg-gray-100 text-gray-800 border-gray-200"))

;; =============================================================================
;; Run Detail Component
;; =============================================================================

(defui run-detail [{:keys [trace]}]
  (let [status (:status trace)
        node-traces (:node-traces trace)]
    ($ :div {:class "flex flex-col h-full"}
       ;; Header
       ($ :div {:class "p-4 border-b"}
          ($ :div {:class "flex items-center gap-3"}
             ($ :h2 {:class "text-lg font-semibold"}
                (or (:sheet-name trace) "Unknown Sheet"))
             ($ :span {:class (str "inline-flex items-center px-2.5 py-0.5 rounded text-xs font-medium "
                                   (status-class status))}
                (if (keyword? status) (name status) (str status))))

          ($ :div {:class "flex items-center gap-4 text-sm text-gray-500 mt-2"}
             ($ :span (str "Duration: " (format-duration (:duration-ms trace))))
             ($ :span (str "Nodes: " (count node-traces)))
             ($ :span (format-timestamp (:started-at trace)))
             (when-let [v (:version-number trace)]
               ($ :span (str "Version: v" v)))))

       ;; Error message if present
       (when (:error trace)
         ($ :div {:class "mx-4 mt-2 p-3 bg-red-50 border border-red-200 rounded text-sm text-red-700"}
            (:error trace)))

       ;; Gantt chart
       ($ :div {:class "flex-1 overflow-hidden"}
          ($ gantt-chart/gantt-chart {:trace trace})))))
