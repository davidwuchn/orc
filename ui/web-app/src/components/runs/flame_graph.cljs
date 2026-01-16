(ns components.runs.flame-graph
  "Flame graph visualization for BT node execution."
  (:require [uix.core :as uix :refer [defui $ use-state use-ref use-effect]]
            [re-frame.uix :refer [use-subscribe]]
            ["/gen/shadcn/components/ui/button" :as button]
            [store.runs.subs :as runs-subs]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- ->kw
  "Coerce value to keyword if it's a string."
  [v]
  (cond
    (keyword? v) v
    (string? v) (keyword v)
    :else v))

(defn status-color [status]
  (case (->kw status)
    :success "#22c55e"   ;; green-500
    :failure "#ef4444"   ;; red-500
    :timeout "#eab308"   ;; yellow-500
    :running "#3b82f6"   ;; blue-500
    :skipped "#9ca3af"   ;; gray-400
    "#d1d5db"))          ;; gray-300

(defn type-color [node-type]
  (case (->kw node-type)
    :leaf "#dbeafe"      ;; blue-100
    :sequence "#f3e8ff"  ;; purple-100
    :fallback "#ffedd5"  ;; orange-100
    :condition "#fef9c3" ;; yellow-100
    :llm-condition "#fef3c7" ;; amber-100
    :parallel "#dcfce7"  ;; green-100
    :map-each "#ccfbf1"  ;; teal-100
    "#f3f4f6"))          ;; gray-100

(defn truncate-name [name max-chars]
  (if (> (count name) max-chars)
    (str (subs name 0 (- max-chars 2)) "..")
    name))

(defn format-duration [ms]
  (when ms
    (cond
      (< ms 1) "<1ms"
      (< ms 1000) (str (int ms) "ms")
      :else (str (.toFixed (/ ms 1000) 2) "s"))))

;; =============================================================================
;; Tooltip Component
;; =============================================================================

(defn- ->name
  "Get name of keyword or return string as-is."
  [v]
  (if (keyword? v) (name v) (str v)))

(defui tooltip [{:keys [node x y]}]
  (when node
    ($ :div {:class "fixed z-50 bg-white border rounded-lg shadow-lg p-3 text-sm pointer-events-none"
             :style {:left (+ x 10) :top (+ y 10) :max-width 300}}
       ($ :div {:class "font-semibold mb-1"} (:name node))
       ($ :div {:class "text-gray-500 text-xs mb-2"}
          (str (->name (:type node)) " | " (->name (:status node))))
       ($ :div {:class "grid grid-cols-2 gap-x-4 gap-y-1 text-xs"}
          ($ :span {:class "text-gray-500"} "Duration:")
          ($ :span {:class "font-mono"} (format-duration (:duration node)))
          ($ :span {:class "text-gray-500"} "Start:")
          ($ :span {:class "font-mono"} (str (:start-ms node) "ms"))
          ($ :span {:class "text-gray-500"} "End:")
          ($ :span {:class "font-mono"} (str (:end-ms node) "ms")))
       (when (:error node)
         ($ :div {:class "mt-2 text-red-600 text-xs"}
            (truncate-name (:error node) 100))))))

;; =============================================================================
;; Flame Graph Component
;; =============================================================================

(defui flame-graph [{:keys [trace]}]
  (let [data (use-subscribe [::runs-subs/flame-graph-data])
        max-depth (use-subscribe [::runs-subs/max-depth])
        [hover-node set-hover-node!] (use-state nil)
        [mouse-pos set-mouse-pos!] (use-state {:x 0 :y 0})
        [zoom set-zoom!] (use-state {:start 0 :end 1})
        container-ref (use-ref nil)
        [dimensions set-dimensions!] (use-state {:width 800 :height 400})

        total-duration (or (:duration-ms trace) 1)
        row-height 28
        padding 4
        time-axis-height 20
        content-height (* (+ max-depth 1) (+ row-height padding))
        height (+ time-axis-height (max 180 content-height))

        ;; Calculate visible range based on zoom
        zoom-start (* total-duration (:start zoom))
        zoom-end (* total-duration (:end zoom))
        zoom-duration (- zoom-end zoom-start)

        handle-mouse-move (fn [e]
                            (set-mouse-pos! {:x (.-clientX e)
                                             :y (.-clientY e)}))

        handle-reset-zoom (fn []
                            (set-zoom! {:start 0 :end 1}))]

    ;; Update dimensions on mount and resize
    (use-effect
      (fn []
        (when-let [el @container-ref]
          (let [update-dims (fn []
                              (let [rect (.getBoundingClientRect el)]
                                (set-dimensions! {:width (.-width rect)
                                                  :height (.-height rect)})))]
            (update-dims)
            (.addEventListener js/window "resize" update-dims)
            #(.removeEventListener js/window "resize" update-dims))))
      [])

    ($ :div {:class "p-4"}
       ;; Controls
       ($ :div {:class "flex items-center justify-between mb-4"}
          ($ :div {:class "text-sm text-gray-500"}
             (str (count data) " nodes | " (format-duration total-duration) " total"))
          ($ :div {:class "flex items-center gap-2"}
             (when (or (not= (:start zoom) 0) (not= (:end zoom) 1))
               ($ button/Button {:variant "outline"
                                 :size "sm"
                                 :onClick handle-reset-zoom}
                  "Reset Zoom"))
             ($ :span {:class "text-xs text-gray-400"}
                "Click and drag to zoom")))

       ;; SVG Container
       (let [svg-width (or (:width dimensions) 800)]
         ($ :div {:ref container-ref
                  :class "border rounded bg-gray-50"
                  :onMouseMove handle-mouse-move}
            ($ :svg {:width "100%"
                     :height height
                     :style {:display "block"}
                     :viewBox (str "0 0 " svg-width " " height)
                     :preserveAspectRatio "none"}

             ;; Time axis
             ($ :g {:class "time-axis"}
                (for [pct [0 25 50 75 100]]
                  (let [x (* (/ pct 100) (:width dimensions))
                        time-ms (+ zoom-start (* (/ pct 100) zoom-duration))]
                    ($ :g {:key pct}
                       ($ :line {:x1 x :y1 0 :x2 x :y2 height
                                 :stroke "#e5e7eb" :strokeWidth 1})
                       ($ :text {:x (+ x 4) :y 12
                                 :class "text-[10px] fill-gray-400"}
                          (str (int time-ms) "ms"))))))

             ;; Render each node as a rect
             (for [[idx node] (map-indexed vector data)
                   :let [{:keys [id name type status start-ms end-ms duration depth]} node
                         ;; Skip if outside visible range
                         visible? (and (< start-ms zoom-end) (> end-ms zoom-start))
                         ;; Calculate position within zoom window
                         rel-start (max 0 (- start-ms zoom-start))
                         rel-end (min zoom-duration (- end-ms zoom-start))
                         w-pixels (:width dimensions)
                         x (* (/ rel-start zoom-duration) w-pixels)
                         w (* (/ (- rel-end rel-start) zoom-duration) w-pixels)
                         y (+ 20 (* depth (+ row-height padding)))]
                   :when (and visible? (> w 0.5))]
               ($ :g {:key (str id "-" idx)
                      :onMouseEnter #(set-hover-node! node)
                      :onMouseLeave #(set-hover-node! nil)
                      :class "cursor-pointer"}
                  ;; Background rect
                  ($ :rect {:x x :y y
                            :width (max w 2) :height row-height
                            :fill (type-color type)
                            :stroke (status-color status)
                            :strokeWidth 2
                            :rx 3
                            :class "hover:opacity-80 transition-opacity"})
                  ;; Status indicator
                  ($ :rect {:x (+ x 2) :y (+ y 2)
                            :width 4 :height (- row-height 4)
                            :fill (status-color status)
                            :rx 1})
                  ;; Label (if there's enough space)
                  (when (> w 50)
                    ($ :text {:x (+ x 10) :y (+ y 18)
                              :class "text-xs fill-gray-700 pointer-events-none"
                              :clipPath (str "url(#clip-" id ")")}
                       (truncate-name name (int (/ w 7))))))))))

       ;; Tooltip
       ($ tooltip {:node hover-node
                   :x (:x mouse-pos)
                   :y (:y mouse-pos)}))))
