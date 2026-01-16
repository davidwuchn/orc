(ns components.runs.gantt-chart
  "Gantt chart visualization for BT node execution."
  (:require [uix.core :as uix :refer [defui $ use-state]]
            [re-frame.core :as rf]
            [re-frame.uix :refer [use-subscribe]]
            [cljs.pprint :as pprint]
            ["highlight.js/lib/core" :as hljs]
            ["highlight.js/lib/languages/clojure" :as clojure-lang]
            [components.context.interface :as context]
            [store.runs.subs :as runs-subs]
            [store.runs.events :as runs-events]))

;; =============================================================================
;; Helpers (must come before components that use them)
;; =============================================================================

(defn- ->kw [v]
  (cond (keyword? v) v (string? v) (keyword v) :else v))

(defn status-color [status]
  (case (->kw status)
    :success "#22c55e"
    :failure "#ef4444"
    :timeout "#eab308"
    :running "#3b82f6"
    :skipped "#9ca3af"
    "#d1d5db"))

(defn status-bg [status]
  (case (->kw status)
    :success "bg-green-500"
    :failure "bg-red-500"
    :timeout "bg-yellow-500"
    :running "bg-blue-500"
    :skipped "bg-gray-400"
    "bg-gray-300"))

(defn format-duration [ms]
  (when ms
    (cond
      (< ms 1) "<1ms"
      (< ms 1000) (str (int ms) "ms")
      (< ms 60000) (str (.toFixed (/ ms 1000) 1) "s")
      :else (str (.toFixed (/ ms 60000) 1) "m"))))

(defn format-time-axis [ms]
  "Format time for axis labels - use appropriate units based on total duration."
  (cond
    (< ms 1000) (str (int ms) "ms")
    (< ms 60000) (str (.toFixed (/ ms 1000) 1) "s")
    :else (str (.toFixed (/ ms 60000) 1) "m")))

;; =============================================================================
;; Tooltip Component
;; =============================================================================

;; Register Clojure language with highlight.js
(.registerLanguage hljs "clojure" clojure-lang)

(defn pprint-str [v]
  "Pretty print value to string."
  (with-out-str (pprint/pprint v)))

(defui syntax-highlighted-value [{:keys [value]}]
  (let [pretty (pprint-str value)
        highlighted (.-value (.highlight hljs pretty #js {:language "clojure"}))]
    ($ :pre {:class "text-xs whitespace-pre-wrap bg-gray-50 p-2 rounded hljs"}
       ($ :code {:class "language-clojure"
                 :dangerouslySetInnerHTML {:__html highlighted}}))))

(defui node-detail-panel [{:keys [node on-close]}]
  (let [{:keys [name type status duration error]} node
        ;; Subscribe to on-demand loaded data
        loading? (use-subscribe [::runs-subs/node-detail-loading?])
        detail-data (use-subscribe [::runs-subs/node-detail-data])
        inputs (:inputs detail-data)
        outputs (:outputs detail-data)]
    ($ :div {:class "h-full flex flex-col border-l bg-white"}
       ;; Header
       ($ :div {:class "p-3 border-b flex items-start justify-between gap-2"}
          ($ :div
             ($ :div {:class "font-semibold"} name)
             ($ :div {:class "text-xs text-gray-500 mt-1"}
                (str (if (keyword? type) (cljs.core/name type) type)
                     " | " (if (keyword? status) (cljs.core/name status) status)
                     " | " (format-duration duration))))
          ($ :button {:class "text-gray-400 hover:text-gray-600"
                      :onClick on-close}
             "\u2715"))
       ;; Content
       ($ :div {:class "flex-1 overflow-auto p-3"}
          ;; Error if present
          (when error
            ($ :div {:class "text-red-600 text-xs mb-3 p-2 bg-red-50 rounded"} error))
          ;; Loading state
          (if loading?
            ($ :div {:class "text-gray-400 text-sm"} "Loading...")
            ($ :<>
               ;; Inputs
               (when (seq inputs)
                 ($ :div {:class "mb-4"}
                    ($ :div {:class "text-xs font-medium text-gray-500 mb-2"} "Inputs")
                    ($ syntax-highlighted-value {:value inputs})))
               ;; Outputs
               (when (seq outputs)
                 ($ :div
                    ($ :div {:class "text-xs font-medium text-gray-500 mb-2"} "Outputs")
                    ($ syntax-highlighted-value {:value outputs})))
               ;; No data message
               (when (and (not loading?) (nil? detail-data))
                 ($ :div {:class "text-gray-400 text-sm"} "Click to load details"))))))))


;; =============================================================================
;; Gantt Chart Component
;; =============================================================================

(defui gantt-chart [{:keys [trace]}]
  (let [ctx (context/use-context)
        api-client (:api/client ctx)
        data (use-subscribe [::runs-subs/flame-graph-data])
        [selected-node set-selected-node!] (use-state nil)

        handle-node-click (fn [node e]
                            (.stopPropagation e)
                            (set-selected-node! node)
                            ;; Dispatch fetch for node detail
                            (rf/dispatch [::runs-events/load-node-trace-detail
                                          api-client
                                          (:trace-id trace)
                                          (:id node)]))

        handle-close (fn []
                       (set-selected-node! nil)
                       (rf/dispatch [::runs-events/clear-node-detail]))

        total-duration (or (:duration-ms trace) 1)
        row-height 28
        label-width 200

        ;; Sort by start time
        sorted-data (sort-by :start-ms data)

        ;; Calculate chart dimensions
        num-rows (count sorted-data)
        chart-height (* num-rows row-height)]

    ($ :div {:class "h-full flex"}
       ;; Main chart area
       ($ :div {:class "flex-1 flex flex-col p-4 min-w-0"}
          ;; Header
          ($ :div {:class "flex items-center justify-between mb-4"}
             ($ :div {:class "text-sm text-gray-500"}
                (str (count data) " nodes | " (format-duration total-duration) " total")))

          ;; Chart container
          ($ :div {:class "flex-1 overflow-auto border rounded bg-white"}
             ($ :div {:class "flex min-w-0"
                      :style {:min-height chart-height}}

                ;; Left: Node labels
                ($ :div {:class "shrink-0 border-r bg-gray-50"
                         :style {:width label-width}}
                   ;; Header spacer to match time axis height
                   ($ :div {:class "sticky top-0 h-6 bg-gray-50 border-b z-10"})
                   (for [[idx node] (map-indexed vector sorted-data)]
                     ($ :div {:key (str (:id node) "-" idx)
                              :class (str "flex items-center px-2 text-xs truncate border-b cursor-pointer hover:bg-blue-50 "
                                          (when (= selected-node node) "bg-blue-100"))
                              :style {:height row-height}
                              :onClick #(handle-node-click node %)}
                        ;; Indent based on depth
                        ($ :span {:style {:width (* (:depth node) 12)}})
                        ($ :span {:class "truncate"} (:name node)))))

                ;; Right: Timeline bars
                ($ :div {:class "flex-1 relative"}
                   ;; Time axis at top
                   ($ :div {:class "sticky top-0 h-6 bg-white border-b z-10 flex"}
                      (for [pct [0 25 50 75 100]]
                        ($ :div {:key pct
                                 :class "absolute text-[10px] text-gray-400"
                                 :style {:left (str pct "%")
                                         :transform "translateX(-50%)"}}
                           (format-time-axis (* (/ pct 100) total-duration)))))

                   ;; Bars
                   ($ :div {:class "relative"
                            :style {:height chart-height}}
                      ;; Grid lines
                      (for [pct [0 25 50 75 100]]
                        ($ :div {:key (str "grid-" pct)
                                 :class "absolute top-0 bottom-0 border-l border-gray-100"
                                 :style {:left (str pct "%")}}))

                      ;; Node bars
                      (for [[idx node] (map-indexed vector sorted-data)]
                        (let [{:keys [id status start-ms end-ms]} node
                              left-pct (* (/ start-ms total-duration) 100)
                              width-pct (* (/ (- end-ms start-ms) total-duration) 100)
                              top (* idx row-height)]
                          ($ :div {:key (str id "-" idx)
                                   :class (str "absolute flex items-center cursor-pointer "
                                               (when (= selected-node node) "z-10"))
                                   :style {:left (str left-pct "%")
                                           :width (str (max width-pct 0.5) "%")
                                           :top top
                                           :height row-height
                                           :padding "2px 0"}
                                   :onClick #(handle-node-click node %)}
                             ($ :div {:class (str "h-5 rounded-sm w-full " (status-bg status)
                                                  (when (= selected-node node) " ring-2 ring-blue-400"))})))))))))

       ;; Side panel for node details
       (when selected-node
         ($ :div {:class "w-[550px] shrink-0"}
            ($ node-detail-panel {:node selected-node
                                  :on-close handle-close}))))))
