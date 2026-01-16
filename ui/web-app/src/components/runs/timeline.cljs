(ns components.runs.timeline
  "Blackboard timeline visualization showing state evolution."
  (:require [uix.core :as uix :refer [defui $ use-state]]
            [re-frame.uix :refer [use-subscribe]]
            ["/gen/shadcn/components/ui/collapsible" :as collapsible]
            [store.runs.subs :as runs-subs]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn format-duration [ms]
  (when ms
    (cond
      (< ms 1000) (str ms "ms")
      (< ms 60000) (str (.toFixed (/ ms 1000) 1) "s")
      :else (str (.toFixed (/ ms 60000) 1) "m"))))

(defn truncate-value [v max-len]
  (let [s (pr-str v)]
    (if (> (count s) max-len)
      (str (subs s 0 (- max-len 3)) "...")
      s)))

(defn status-color [status]
  (case status
    :success "bg-green-500"
    :failure "bg-red-500"
    :timeout "bg-yellow-500"
    "bg-gray-400"))

;; =============================================================================
;; Timeline Entry Component
;; =============================================================================

(defui timeline-entry [{:keys [entry expanded? on-toggle total-duration]}]
  (let [{:keys [timestamp node-name node-id status changes]} entry
        left-pct (* (/ timestamp total-duration) 100)]
    ($ collapsible/Collapsible {:open expanded?
                                :onOpenChange on-toggle}
       ($ collapsible/CollapsibleTrigger {:asChild true}
          ($ :div {:class "flex items-center gap-3 p-3 hover:bg-gray-50 rounded cursor-pointer transition-colors"}
             ;; Timestamp
             ($ :span {:class "text-xs text-gray-400 font-mono w-16 flex-shrink-0"}
                (format-duration timestamp))

             ;; Status dot
             ($ :div {:class (str "w-2 h-2 rounded-full flex-shrink-0 " (status-color status))})

             ;; Node name
             ($ :span {:class "font-medium text-sm flex-1 truncate"}
                node-name)

             ;; Change count
             ($ :span {:class "text-xs text-gray-500 flex-shrink-0"}
                (str (count changes) " change" (when (not= (count changes) 1) "s")))

             ;; Expand indicator
             ($ :svg {:class (str "w-4 h-4 text-gray-400 transition-transform "
                                  (when expanded? "rotate-180"))
                      :viewBox "0 0 20 20"
                      :fill "currentColor"}
                ($ :path {:fillRule "evenodd"
                          :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z"
                          :clipRule "evenodd"}))))

       ($ collapsible/CollapsibleContent
          ($ :div {:class "pl-20 pr-4 pb-3"}
             ($ :div {:class "bg-gray-50 rounded p-3 space-y-2"}
                (for [{:keys [key old-value new-value]} changes]
                  ($ :div {:key key :class "flex items-start gap-2 text-sm"}
                     ($ :span {:class "font-mono text-blue-600 flex-shrink-0"}
                        key)
                     ($ :span {:class "text-gray-400"} ":")
                     (when (some? old-value)
                       ($ :<>
                          ($ :span {:class "font-mono text-gray-400 line-through"}
                             (truncate-value old-value 40))
                          ($ :span {:class "text-gray-300 mx-1"} "->")))
                     ($ :span {:class "font-mono text-green-600"}
                        (truncate-value new-value 60))))))))))

;; =============================================================================
;; Timeline Header Component
;; =============================================================================

(defui timeline-header [{:keys [total-duration timeline]}]
  ($ :div {:class "relative h-16 border-b bg-gray-50 px-4"}
     ;; Time markers
     ($ :div {:class "absolute inset-x-4 top-2 flex justify-between text-xs text-gray-400"}
        (for [pct [0 25 50 75 100]]
          ($ :span {:key pct}
             (format-duration (int (* (/ pct 100) total-duration))))))

     ;; Timeline bar
     ($ :div {:class "absolute inset-x-4 top-8 h-6 bg-gray-200 rounded"}
        ;; Change markers
        (for [{:keys [timestamp node-id status]} timeline
              :let [left-pct (* (/ timestamp total-duration) 100)]]
          ($ :div {:key (str node-id "-" timestamp)
                   :class "absolute top-0 bottom-0 w-0.5 -ml-px"
                   :style {:left (str left-pct "%")}}
             ($ :div {:class (str "absolute -top-1 -left-1 w-3 h-3 rounded-full border-2 border-white "
                                  (status-color status))}))))))

;; =============================================================================
;; Blackboard Timeline Component
;; =============================================================================

(defui blackboard-timeline [{:keys [trace]}]
  (let [timeline (use-subscribe [::runs-subs/blackboard-timeline])
        [expanded-ids set-expanded-ids!] (use-state #{})
        total-duration (or (:duration-ms trace) 1)

        toggle-entry (fn [id]
                       (set-expanded-ids!
                        (fn [ids]
                          (if (contains? ids id)
                            (disj ids id)
                            (conj ids id)))))]

    (if (empty? timeline)
      ;; Empty state
      ($ :div {:class "flex items-center justify-center h-full text-gray-400"}
         "No blackboard changes recorded")

      ;; Timeline view
      ($ :div {:class "h-full flex flex-col"}
         ;; Visual timeline header
         ($ timeline-header {:total-duration total-duration
                             :timeline timeline})

         ;; Entries list
         ($ :div {:class "flex-1 overflow-auto"}
            ($ :div {:class "divide-y"}
               (for [entry timeline
                     :let [entry-id (str (:node-id entry) "-" (:timestamp entry))
                           expanded? (contains? expanded-ids entry-id)]]
                 ($ timeline-entry {:key entry-id
                                    :entry entry
                                    :expanded? expanded?
                                    :on-toggle #(toggle-entry entry-id)
                                    :total-duration total-duration}))))

         ;; Summary footer
         ($ :div {:class "p-4 border-t bg-gray-50 text-sm text-gray-500"}
            ($ :div {:class "flex items-center gap-4"}
               ($ :span (str (count timeline) " state changes"))
               ($ :span (str (count (distinct (mapcat (fn [e] (map :key (:changes e))) timeline)))
                             " unique keys modified"))))))))
