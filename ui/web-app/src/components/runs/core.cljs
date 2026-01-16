(ns components.runs.core
  "BT Runs visualization pages - list and detail views."
  (:require [uix.core :as uix :refer [defui $ use-effect]]
            [re-frame.core :as rf]
            [re-frame.uix :refer [use-subscribe]]
            ["/gen/shadcn/components/ui/spinner" :as spinner]
            [components.context.interface :as context]
            [components.runs.runs-list :as runs-list]
            [components.runs.run-detail :as run-detail]
            [store.runs.events :as runs-events]
            [store.runs.subs :as runs-subs]))

;; =============================================================================
;; Runs List Page
;; =============================================================================

(defui runs-list-page []
  (let [ctx (context/use-context)
        api-client (:api/client ctx)
        navigate! (:router/navigate! ctx)
        loading? (use-subscribe [::runs-subs/runs-loading?])
        runs (use-subscribe [::runs-subs/runs-list])]

    ;; Load runs on mount
    (use-effect
      (fn []
        (rf/dispatch [::runs-events/load-runs-screen api-client])
        js/undefined)
      [api-client])

    ($ :div {:class "flex flex-col h-screen"}
       ;; Header
       ($ :div {:class "border-b p-4"}
          ($ :h1 {:class "text-2xl font-bold"} "BT Runs"))

       ;; Main content
       ($ :div {:class "flex-1 overflow-hidden"}
          (if (and loading? (empty? runs))
            ($ :div {:class "flex items-center justify-center h-full"}
               ($ spinner/Spinner {:size "lg"}))
            ($ runs-list/runs-list
               {:on-select (fn [trace-id]
                             (navigate! :run-detail {:trace-id (str trace-id)}))}))))))

;; =============================================================================
;; Run Detail Page
;; =============================================================================

(defui run-detail-page [{:keys [query-params]}]
  (let [ctx (context/use-context)
        api-client (:api/client ctx)
        navigate! (:router/navigate! ctx)
        trace-id-str (:trace-id query-params)
        trace-id (when trace-id-str (uuid trace-id-str))
        loading? (use-subscribe [::runs-subs/selected-trace-loading?])
        trace (use-subscribe [::runs-subs/selected-trace])]

    ;; Load trace on mount or when trace-id changes
    (use-effect
      (fn []
        (when trace-id
          (rf/dispatch [::runs-events/load-trace api-client trace-id]))
        js/undefined)
      [api-client trace-id])

    ($ :div {:class "flex flex-col h-screen"}
       ;; Header with back navigation
       ($ :div {:class "border-b p-4"}
          ($ :div {:class "flex items-center gap-4"}
             ($ :button {:class "text-gray-500 hover:text-gray-700"
                         :onClick #(navigate! :runs)}
                "\u2190 Back to Runs")
             ($ :h1 {:class "text-2xl font-bold"} "Run Detail")))

       ;; Main content
       ($ :div {:class "flex-1 overflow-hidden"}
          (cond
            (not trace-id)
            ($ :div {:class "flex items-center justify-center h-full text-gray-400"}
               "No trace ID provided")

            loading?
            ($ :div {:class "flex items-center justify-center h-full"}
               ($ spinner/Spinner {:size "lg"}))

            (not trace)
            ($ :div {:class "flex items-center justify-center h-full text-gray-400"}
               "Trace not found")

            :else
            ($ run-detail/run-detail {:trace trace}))))))
