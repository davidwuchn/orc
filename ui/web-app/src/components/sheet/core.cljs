(ns components.sheet.core
  "Behavior Tree Sheet UI components."
  (:require [uix.core :as uix :refer [defui $ use-state use-effect use-callback]]
            [re-frame.core :as rf]
            [re-frame.uix :refer [use-subscribe]]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [malli.core :as m]
            ["/gen/shadcn/components/ui/button" :as button]
            ["/gen/shadcn/components/ui/input" :as input]
            ["/gen/shadcn/components/ui/card" :as card]
            ["/gen/shadcn/components/ui/badge" :as badge]
            ["/gen/shadcn/components/ui/select" :as select]
            ["/gen/shadcn/components/ui/label" :as label]
            ["/gen/shadcn/components/ui/textarea" :as textarea]
            ["/gen/shadcn/components/ui/dialog" :as dialog]
            ["/gen/shadcn/components/ui/separator" :as separator]
            ["/gen/shadcn/components/ui/spinner" :as spinner]
            ["/gen/shadcn/components/ui/checkbox" :as checkbox]
            [components.context.interface :as context]
            [store.sheet.events :as sheet-events]
            [store.sheet.subs :as sheet-subs]))

;; =============================================================================
;; Error Helpers
;; =============================================================================

(defn error-message
  "Extract user-friendly message from an anomaly/error."
  [error]
  (or (:cognitect.anomalies/message error)
      (str error)))

;; =============================================================================
;; Status Helpers
;; =============================================================================

(defn status-color [status]
  (case status
    :idle "bg-gray-200"
    :running "bg-blue-400 animate-pulse"
    :success "bg-green-400"
    :failure "bg-red-400"
    "bg-gray-200"))

(defn node-type-icon [node-type]
  (case node-type
    :leaf "L"
    :sequence "S"
    :fallback "F"
    :condition "?"
    :llm-condition "?"
    :parallel "P"
    :map-each "M"
    "?"))

(defn node-type-color [node-type]
  (case node-type
    :leaf "bg-blue-100 border-blue-300"
    :sequence "bg-purple-100 border-purple-300"
    :fallback "bg-orange-100 border-orange-300"
    :condition "bg-yellow-100 border-yellow-300"
    :llm-condition "bg-amber-100 border-amber-300"
    :parallel "bg-green-100 border-green-300"
    :map-each "bg-teal-100 border-teal-300"
    "bg-gray-100"))

;; =============================================================================
;; Node Cell Component
;; =============================================================================

(defui node-cell [{:keys [node layout selected? on-select]}]
  (let [node-type (:type node)
        status (:status node)
        ;; Use percentage-based width for true equal distribution
        width-pct (* (- (:end-col layout) (:start-col layout)) 100)
        ;; Progressive disclosure based on available width
        minimal? (< width-pct 6)      ;; Just colored block with status
        compact? (< width-pct 12)     ;; Icon + status only
        narrow? (< width-pct 20)]     ;; Icon + truncated name + status
    ($ :div {:class "px-0.5 min-w-0"
             :style {:flex (str "0 1 " width-pct "%")}}
       ($ :div {:class (str (cond minimal? "p-0.5" compact? "p-1" :else "p-2")
                            " border rounded cursor-pointer transition-all h-full overflow-hidden "
                            (node-type-color node-type) " "
                            (if selected?
                              "ring-2 ring-blue-500 ring-offset-1"
                              "hover:shadow-md"))
                :on-click #(on-select (:id node))}

          (cond
            ;; Minimal: just a colored block with status dot
            minimal?
            ($ :div {:class "flex items-center justify-center h-4"}
               ($ :div {:class (str "w-2 h-2 rounded-full " (status-color status))}))

            ;; Compact: icon + status
            compact?
            ($ :div {:class "flex items-center justify-between gap-0.5"}
               ($ :div {:class "w-4 h-4 text-[9px] rounded-full flex items-center justify-center font-bold border bg-white"}
                  (node-type-icon node-type))
               ($ :div {:class (str "w-2 h-2 rounded-full flex-shrink-0 " (status-color status))}))

            ;; Narrow: icon + name + status
            narrow?
            ($ :div {:class "flex items-center gap-1 min-w-0"}
               ($ :div {:class "w-5 h-5 text-[10px] rounded-full flex items-center justify-center font-bold border bg-white flex-shrink-0"}
                  (node-type-icon node-type))
               ($ :span {:class "text-xs font-medium truncate flex-1 min-w-0"}
                  (:name node))
               ($ :div {:class (str "w-2 h-2 rounded-full flex-shrink-0 " (status-color status))}))

            ;; Full: everything
            :else
            ($ :<>
               ($ :div {:class "flex items-center gap-1 min-w-0"}
                  ($ :div {:class "w-6 h-6 text-xs rounded-full flex items-center justify-center font-bold border bg-white flex-shrink-0"}
                     (node-type-icon node-type))
                  ($ :span {:class "text-sm font-medium truncate flex-1 min-w-0"}
                     (:name node))
                  ($ :div {:class (str "w-3 h-3 rounded-full flex-shrink-0 " (status-color status))}))

               ;; Instruction preview for leaf nodes
               (when (and (= :leaf node-type) (:instruction node))
                 ($ :p {:class "text-xs text-gray-500 truncate mt-1"}
                    (:instruction node)))

               ;; Check preview for condition nodes
               (when (= :condition node-type)
                 (if-let [check (:check node)]
                   ($ :p {:class "text-xs text-gray-500 truncate mt-1"}
                      (str (:key check) " " (name (:op check)) " " (:value check)))
                   ($ :p {:class "text-xs text-gray-400 italic mt-1"} "No check")))

               ;; Children count for sequence/fallback nodes
               (when (#{:sequence :fallback} node-type)
                 ($ :p {:class "text-xs text-gray-400 mt-1"}
                    (str (count (:children-ids node)) " children")))

               ;; Parallel node preview
               (when (= :parallel node-type)
                 ($ :p {:class "text-xs text-gray-400 mt-1"}
                    (str (count (:children-ids node)) " children")))

               ;; Map-each node preview
               (when (= :map-each node-type)
                 ($ :p {:class "text-xs text-gray-500 truncate mt-1"}
                    (when (:source-key node)
                      (str (:source-key node) " -> " (:output-key node)))))))))))

;; =============================================================================
;; Empty Row Cell (for adding nodes)
;; =============================================================================

(defui add-node-cell [{:keys [sheet-id parent-id index on-create-node]}]
  (let [[menu-open? set-menu-open!] (use-state false)]
    ($ :div {:class "p-2 border-2 border-dashed rounded-lg cursor-pointer hover:bg-gray-50 flex items-center justify-center"
             :on-click #(set-menu-open! true)}
       (if menu-open?
         ($ :div {:class "flex gap-2 flex-wrap justify-center"}
            ($ button/Button {:size "sm" :variant "outline"
                              :on-click #(do (set-menu-open! false)
                                             (on-create-node :leaf parent-id index))}
               "Leaf")
            ($ button/Button {:size "sm" :variant "outline"
                              :on-click #(do (set-menu-open! false)
                                             (on-create-node :condition parent-id index))}
               "Cond")
            ($ button/Button {:size "sm" :variant "outline"
                              :on-click #(do (set-menu-open! false)
                                             (on-create-node :sequence parent-id index))}
               "Seq")
            ($ button/Button {:size "sm" :variant "outline"
                              :on-click #(do (set-menu-open! false)
                                             (on-create-node :fallback parent-id index))}
               "Fall")
            ($ button/Button {:size "sm" :variant "outline"
                              :on-click #(do (set-menu-open! false)
                                             (on-create-node :parallel parent-id index))}
               "Para")
            ($ button/Button {:size "sm" :variant "outline"
                              :on-click #(do (set-menu-open! false)
                                             (on-create-node :map-each parent-id index))}
               "Map"))
         ($ :span {:class "text-gray-400 text-2xl"} "+")))))

;; =============================================================================
;; Tree Grid Component
;; =============================================================================

(defui tree-grid [{:keys [sheet-id]}]
  (let [layout (use-subscribe [::sheet-subs/layout])
        layout-by-id (use-subscribe [::sheet-subs/layout-by-node-id])
        nodes (use-subscribe [::sheet-subs/nodes])
        root-node (use-subscribe [::sheet-subs/root-node])
        selected-id (use-subscribe [::sheet-subs/selected-node-id])
        tree-depth (use-subscribe [::sheet-subs/tree-depth])
        ctx (context/use-context)
        api-client (:api/client ctx)

        handle-select (fn [node-id]
                        (rf/dispatch [::sheet-events/select-node node-id]))

        handle-create-node (fn [node-type parent-id index]
                             (rf/dispatch [::sheet-events/create-node
                                           api-client sheet-id node-type parent-id index]))

        ;; Group layout items by row
        rows-data (group-by :row layout)
        num-rows (if (empty? layout) 1 (inc (apply max (map :row layout))))]

    ($ :div {:class "p-4"}
       (if root-node
         ;; Tree exists - render grid
         ($ :div {:class "space-y-1"}
            (for [row-idx (range num-rows)]
              (let [row-items (get rows-data row-idx [])
                    ;; Sort items by start-col to ensure correct order
                    sorted-items (sort-by :start-col row-items)
                    ;; Build items with spacers for gaps
                    items-with-spacers
                    (loop [items sorted-items
                           current-pos 0.0
                           result []]
                      (if (empty? items)
                        ;; No trailing spacer needed
                        result
                        (let [item (first items)
                              gap (- (:start-col item) current-pos)
                              with-spacer (if (> gap 0.001)
                                            (conj result {:type :spacer
                                                          :key (str "spacer-" row-idx "-" current-pos)
                                                          :width gap})
                                            result)]
                          (recur (rest items)
                                 (:end-col item)
                                 (conj with-spacer {:type :node
                                                    :item item})))))]
                ($ :div {:key row-idx
                         :class "flex"}
                   (for [entry items-with-spacers]
                     (if (= :spacer (:type entry))
                       ;; Render spacer
                       ($ :div {:key (:key entry)
                                :class "min-w-0"
                                :style {:flex (str "0 1 " (* (:width entry) 100) "%")}})
                       ;; Render node
                       (let [item (:item entry)
                             node (get nodes (:node-id item))]
                         ($ node-cell {:key (:node-id item)
                                       :node node
                                       :layout item
                                       :selected? (= (:id node) selected-id)
                                       :on-select handle-select}))))))))

         ;; No tree yet - show create root button
         ($ :div {:class "flex flex-col items-center justify-center py-12"}
            ($ :p {:class "text-gray-500 mb-4"} "No tree yet. Create a root node.")
            ($ :div {:class "flex gap-2"}
               ($ button/Button {:on-click #(handle-create-node :sequence nil nil)}
                  "Create Sequence Root")
               ($ button/Button {:variant "outline"
                                 :on-click #(handle-create-node :fallback nil nil)}
                  "Create Fallback Root")))))))

;; =============================================================================
;; Schema Helpers
;; =============================================================================

(defn valid-malli-schema?
  "Check if a value is a valid Malli schema."
  [schema]
  (try
    (m/schema schema)
    true
    (catch :default _
      false)))

(defn schema-summary
  "Generate a short summary of a Malli schema for display."
  [schema]
  (cond
    (keyword? schema) (name schema)
    (vector? schema)
    (let [[schema-type & _] schema]
      (case schema-type
        :map "object"
        :vector "list"
        :enum "enum"
        :maybe "optional"
        (name schema-type)))
    :else "unknown"))

(defn parse-map-fields
  "Parse fields from a Malli :map schema.
   Returns seq of {:key, :optional?, :schema}."
  [fields]
  (for [field fields
        :when (vector? field)]
    (let [[field-key & rest] field
          ;; Check if second element is options map
          opts (when (map? (first rest)) (first rest))
          field-schema (if opts (second rest) (first rest))]
      {:key field-key
       :optional? (:optional opts false)
       :schema field-schema})))

;; =============================================================================
;; Schema Editor Component (EDN text input)
;; =============================================================================

(defui schema-editor [{:keys [schema on-save on-cancel]}]
  (let [[text set-text!] (use-state (pr-str schema))
        [error set-error!] (use-state nil)

        handle-save (fn []
                      (try
                        (let [parsed (reader/read-string text)]
                          (if (valid-malli-schema? parsed)
                            (do
                              (set-error! nil)
                              (on-save parsed))
                            (set-error! "Invalid Malli schema")))
                        (catch :default e
                          (set-error! (str "Invalid EDN: " (.-message e))))))]
    ($ :div {:class "space-y-2"}
       ($ textarea/Textarea
          {:value text
           :on-change #(set-text! (.. % -target -value))
           :class (str "font-mono text-sm min-h-[80px] "
                       (when error "border-red-500"))
           :placeholder ":string, [:vector :int], [:map [:name :string]]"})
       (when error
         ($ :p {:class "text-red-500 text-xs"} error))
       ($ :div {:class "flex gap-2"}
          ($ button/Button {:size "sm" :on-click handle-save}
             "Save Schema")
          (when on-cancel
            ($ button/Button {:size "sm" :variant "ghost" :on-click on-cancel}
               "Cancel"))))))

;; =============================================================================
;; Malli Value Editor Component (recursive tree editor)
;; =============================================================================

(declare malli-value-editor)

(defui malli-value-editor [{:keys [schema value on-change path inline?]}]
  (let [path (or path [])]
    (cond
      ;; String type
      (= :string schema)
      ($ :input
         {:value (or value "")
          :on-change #(on-change (.. % -target -value))
          :class (str "w-full rounded border border-gray-200 px-2 py-1.5 text-sm font-mono "
                      "focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent "
                      "placeholder:text-gray-400")
          :placeholder "Enter text..."})

      ;; Integer type
      (or (= :int schema) (= :integer schema))
      ($ :input
         {:type "number"
          :value (or value 0)
          :on-change #(on-change (js/parseInt (.. % -target -value) 10))
          :class (str "w-20 rounded border border-gray-200 px-2 py-1.5 text-sm font-mono text-right "
                      "focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent")})

      ;; Double/number type
      (or (= :double schema) (= :number schema))
      ($ :input
         {:type "number"
          :step "any"
          :value (or value 0)
          :on-change #(on-change (js/parseFloat (.. % -target -value)))
          :class (str "w-24 rounded border border-gray-200 px-2 py-1.5 text-sm font-mono text-right "
                      "focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent")})

      ;; Boolean type
      (= :boolean schema)
      ($ :button
         {:class (str "px-3 py-1.5 rounded text-sm font-medium transition-colors "
                      (if value
                        "bg-green-100 text-green-700 hover:bg-green-200"
                        "bg-gray-100 text-gray-600 hover:bg-gray-200"))
          :on-click #(on-change (not value))}
         (if value "Yes" "No"))

      ;; Map type - render fields recursively
      (and (vector? schema) (= :map (first schema)))
      (let [fields (parse-map-fields (rest schema))]
        ($ :div {:class "space-y-2 pl-3 border-l-2 border-gray-200"}
           (for [{:keys [key optional? schema]} fields]
             ($ :div {:key (name key) :class "space-y-1"}
                ($ :label {:class "text-xs font-medium text-gray-600"}
                   (str (name key)
                        (when optional? " (optional)")))
                ($ malli-value-editor
                   {:schema schema
                    :value (get value key)
                    :on-change #(on-change (assoc (or value {}) key %))
                    :path (conj path key)})))))

      ;; Vector type - render items with add/remove
      (and (vector? schema) (= :vector (first schema)))
      (let [item-schema (second schema)
            items (or value [])]
        ($ :div {:class "space-y-1.5"}
           (for [[idx item] (map-indexed vector items)]
             ($ :div {:key idx :class "flex gap-2 items-center group"}
                ($ :span {:class "text-xs text-gray-400 w-4 text-right"} idx)
                ($ :div {:class "flex-1"}
                   ($ malli-value-editor
                      {:schema item-schema
                       :value item
                       :on-change #(on-change (assoc items idx %))
                       :path (conj path idx)}))
                ($ :button
                   {:class "opacity-0 group-hover:opacity-100 text-gray-400 hover:text-red-500 transition-opacity px-1"
                    :on-click #(on-change (vec (concat (take idx items)
                                                       (drop (inc idx) items))))}
                   "x")))
           ($ :button
              {:class "w-full py-1.5 text-xs text-gray-500 hover:text-gray-700 hover:bg-gray-100 rounded transition-colors"
               :on-click #(on-change (conj items nil))}
              "+ add item")))

      ;; Enum type
      (and (vector? schema) (= :enum (first schema)))
      (let [options (rest schema)]
        ($ :div {:class "flex flex-wrap gap-1"}
           (for [opt options]
             ($ :button
                {:key (pr-str opt)
                 :class (str "px-2.5 py-1 rounded text-sm transition-colors "
                             (if (= value opt)
                               "bg-blue-100 text-blue-700 font-medium"
                               "bg-gray-100 text-gray-600 hover:bg-gray-200"))
                 :on-click #(on-change opt)}
                (str opt)))))

      ;; Maybe type (optional)
      (and (vector? schema) (= :maybe (first schema)))
      ($ malli-value-editor
         {:schema (second schema)
          :value value
          :on-change on-change
          :path path})

      ;; Fallback - use textarea for raw EDN
      :else
      ($ :textarea
         {:value (if (nil? value) "" (pr-str value))
          :on-change #(try
                        (let [text (.. % -target -value)]
                          (on-change (if (str/blank? text)
                                       nil
                                       (reader/read-string text))))
                        (catch :default _ nil))
          :class (str "w-full rounded border border-gray-200 px-2 py-1.5 text-sm font-mono "
                      "focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent "
                      "placeholder:text-gray-400 min-h-[60px] resize-y")
          :placeholder "EDN value..."}))))

;; =============================================================================
;; Blackboard Entry Editor Component
;; =============================================================================

(defui blackboard-entry-editor [{:keys [entry sheet-id on-delete is-used?]}]
  (let [ctx (context/use-context)
        api-client (:api/client ctx)
        [editing-schema? set-editing-schema!] (use-state false)
        [local-value set-local-value!] (use-state (:value entry))
        [dirty? set-dirty!] (use-state false)
        [saving? set-saving!] (use-state false)

        k (:key entry)
        schema (:schema entry)

        handle-value-change (fn [new-value]
                              (set-local-value! new-value)
                              (set-dirty! true))

        handle-save-value (fn []
                            (set-saving! true)
                            (rf/dispatch [::sheet-events/set-key-value
                                          api-client sheet-id k local-value])
                            (set-dirty! false)
                            (js/setTimeout #(set-saving! false) 300))

        handle-save-schema (fn [new-schema]
                             (rf/dispatch [::sheet-events/update-key-schema
                                           api-client sheet-id k new-schema])
                             (set-editing-schema! false))]

    ;; Sync local value when entry changes from server
    (use-effect
      (fn []
        (set-local-value! (:value entry))
        (set-dirty! false))
      [entry])

    ($ :div {:class (str "group rounded-lg border bg-white transition-all "
                         (if is-used?
                           "border-blue-300 bg-blue-50/30"
                           "border-gray-200 hover:border-gray-300"))}

       ;; Header row - key name, schema, actions
       ($ :div {:class "flex items-center gap-2 px-3 py-2"}
          ;; Key name
          ($ :span {:class "font-mono text-sm font-medium text-gray-700"} k)

          ;; Schema badge (clickable to edit)
          ($ :button {:class "text-xs px-1.5 py-0.5 rounded bg-gray-100 text-gray-500 hover:bg-gray-200 hover:text-gray-700 transition-colors"
                      :on-click #(set-editing-schema! true)}
             (schema-summary schema))

          ($ :div {:class "flex-1"})

          ;; Status indicator
          (when dirty?
            ($ :span {:class "text-xs text-amber-500 mr-1"} "unsaved"))

          ;; Delete button (visible on hover)
          ($ :button
             {:class "opacity-0 group-hover:opacity-100 text-gray-400 hover:text-red-500 transition-opacity"
              :on-click on-delete}
             "x"))

       ;; Value editor - full width
       (when-not editing-schema?
         ($ :div {:class "px-3 pb-2"}
            ($ :div {:class "flex items-start gap-2"}
               ($ :div {:class "flex-1"}
                  ($ malli-value-editor
                     {:schema schema
                      :value local-value
                      :on-change handle-value-change
                      :path []}))
               (when dirty?
                 ($ :button
                    {:class "px-2 py-1 text-xs font-medium text-blue-600 hover:text-blue-700 hover:bg-blue-50 rounded transition-colors"
                     :on-click handle-save-value}
                    (if saving? "..." "Save"))))))

       ;; Schema editor
       (when editing-schema?
         ($ :div {:class "px-3 pb-3"}
            ($ schema-editor
               {:schema schema
                :on-save handle-save-schema
                :on-cancel #(set-editing-schema! false)}))))))

;; =============================================================================
;; Inline Quick-Add Component
;; =============================================================================

(def quick-types
  "Quick type buttons for inline add."
  [{:key "T" :schema :string :tip "Text"}
   {:key "N" :schema :int :tip "Number"}
   {:key "?" :schema :boolean :tip "Yes/No"}
   {:key "[]" :schema [:vector :string] :tip "List"}
   {:key "{}" :schema [:map] :tip "Object"}])

(defui inline-add-variable [{:keys [sheet-id]}]
  (let [ctx (context/use-context)
        api-client (:api/client ctx)
        [name-value set-name-value!] (use-state "")
        [focused? set-focused!] (use-state false)
        input-ref (uix/use-ref nil)

        handle-quick-add (fn [schema]
                           (when (seq name-value)
                             (rf/dispatch [::sheet-events/declare-key
                                           api-client sheet-id name-value schema])
                             (set-name-value! "")
                             ;; Keep focus for rapid entry
                             (when-let [input @input-ref]
                               (.focus input))))

        handle-key-down (fn [e]
                          (when (and (= (.-key e) "Enter") (seq name-value))
                            ;; Enter = add as text
                            (handle-quick-add :string)))]

    ($ :div {:class (str "rounded-lg border-2 border-dashed transition-colors "
                         (if (or focused? (seq name-value))
                           "border-blue-300 bg-blue-50/30"
                           "border-gray-200 hover:border-gray-300"))}
       ($ :div {:class "flex items-center gap-2 p-2"}
          ($ :input
             {:ref input-ref
              :value name-value
              :on-change #(set-name-value! (.. % -target -value))
              :on-focus #(set-focused! true)
              :on-blur #(js/setTimeout (fn [] (set-focused! false)) 150)
              :on-key-down handle-key-down
              :placeholder "Add variable..."
              :class "flex-1 bg-transparent outline-none text-sm font-mono placeholder:text-gray-400"})

          ;; Quick type buttons
          ($ :div {:class (str "flex gap-1 transition-opacity "
                               (if (seq name-value) "opacity-100" "opacity-40"))}
             (for [{:keys [key schema tip]} quick-types]
               ($ :button
                  {:key key
                   :class (str "h-7 px-2 rounded text-xs font-mono transition-colors "
                               (if (seq name-value)
                                 "text-gray-600 hover:bg-gray-200 hover:text-gray-900"
                                 "text-gray-400 cursor-default"))
                   :title tip
                   :disabled (empty? name-value)
                   :on-click #(handle-quick-add schema)}
                  key)))))))

;; =============================================================================
;; Blackboard Panel Component
;; =============================================================================

(defui blackboard-panel [{:keys [sheet-id]}]
  (let [blackboard-list (use-subscribe [::sheet-subs/blackboard-list])
        selected-node (use-subscribe [::sheet-subs/selected-node])
        ctx (context/use-context)
        api-client (:api/client ctx)

        keys-used-by-selected (when selected-node
                                (set (concat (:reads selected-node) (:writes selected-node))))

        handle-delete (fn [key]
                        (rf/dispatch [::sheet-events/delete-key api-client sheet-id key]))]

    ($ :div {:class "border-t bg-gray-50/50"}
       ;; Header
       ($ :div {:class "px-4 pt-3 pb-1"}
          ($ :h3 {:class "text-xs font-medium text-gray-500 uppercase tracking-wide"} "Variables"))

       ;; Variables list
       ($ :div {:class "px-4 space-y-1.5"}
          (for [entry blackboard-list]
            (let [k (:key entry)
                  is-used? (and keys-used-by-selected (keys-used-by-selected k))]
              ($ blackboard-entry-editor
                 {:key k
                  :entry entry
                  :sheet-id sheet-id
                  :is-used? is-used?
                  :on-delete #(handle-delete k)}))))

       ;; Inline add
       ($ :div {:class "px-4 pb-3 pt-1"}
          ($ inline-add-variable {:sheet-id sheet-id})))))

;; =============================================================================
;; Node Editor Panel Component
;; =============================================================================

(def condition-operators
  "Available operators for condition checks"
  [{:value :equals :label "="}
   {:value :not-equals :label "!="}
   {:value :gt :label ">"}
   {:value :lt :label "<"}
   {:value :gte :label ">="}
   {:value :lte :label "<="}
   {:value :contains :label "contains"}
   {:value :exists :label "exists"}
   {:value :truthy :label "truthy"}])

(defui node-editor-panel [{:keys [sheet-id]}]
  (let [node (use-subscribe [::sheet-subs/selected-node])
        blackboard-keys (use-subscribe [::sheet-subs/blackboard-keys])
        ctx (context/use-context)
        api-client (:api/client ctx)

        [editing-name? set-editing-name!] (use-state false)
        [name-value set-name-value!] (use-state "")
        [instruction-value set-instruction-value!] (use-state "")
        [reads-value set-reads-value!] (use-state [])
        [writes-value set-writes-value!] (use-state [])
        ;; Condition check state
        [check-key set-check-key!] (use-state "")
        [check-op set-check-op!] (use-state :equals)
        [check-value set-check-value!] (use-state "")
        [check-on-fail set-check-on-fail!] (use-state :failure)

        ;; Executor state (for leaf nodes)
        [executor-type set-executor-type!] (use-state :ai)
        [model-value set-model-value!] (use-state "")
        [fn-value set-fn-value!] (use-state "")

        ;; Retry state
        [retry-enabled? set-retry-enabled!] (use-state false)
        [max-attempts set-max-attempts!] (use-state 3)
        [backoff-ms set-backoff-ms!] (use-state "100,500,2000")

        ;; Parallel config state
        [success-policy set-success-policy!] (use-state :all)
        [failure-policy set-failure-policy!] (use-state :any)

        ;; Map-each config state
        [source-key set-source-key!] (use-state "")
        [item-key set-item-key!] (use-state "")
        [output-key set-output-key!] (use-state "")
        [max-concurrency set-max-concurrency!] (use-state "")

        ;; Initialize values when node changes
        _ (use-effect
            (fn []
              (when node
                (set-name-value! (:name node))
                (set-instruction-value! (or (:instruction node) ""))
                (set-reads-value! (vec (:reads node)))
                (set-writes-value! (vec (:writes node)))
                ;; Initialize condition check state
                (when-let [check (:check node)]
                  (set-check-key! (or (:key check) ""))
                  (set-check-op! (or (:op check) :equals))
                  (set-check-value! (str (or (:value check) "")))
                  (set-check-on-fail! (or (:on-fail check) :failure)))
                ;; Initialize executor state
                (set-executor-type! (or (:executor node) :ai))
                (set-model-value! (or (:model node) ""))
                (set-fn-value! (or (:fn node) ""))
                ;; Initialize retry state
                (set-retry-enabled! (some? (:retry node)))
                (when-let [retry (:retry node)]
                  (set-max-attempts! (or (:max-attempts retry) 3))
                  (set-backoff-ms! (str/join "," (or (:backoff-ms retry) [100 500 2000]))))
                ;; Initialize parallel config state
                (set-success-policy! (or (:success-policy node) :all))
                (set-failure-policy! (or (:failure-policy node) :any))
                ;; Initialize map-each config state
                (set-source-key! (or (:source-key node) ""))
                (set-item-key! (or (:item-key node) ""))
                (set-output-key! (or (:output-key node) ""))
                (set-max-concurrency! (str (or (:max-concurrency node) "")))))
            [node])

        handle-save-name (fn []
                           (rf/dispatch [::sheet-events/set-node-name api-client sheet-id (:id node) name-value])
                           (set-editing-name! false))

        handle-save-instruction (fn []
                                  (rf/dispatch [::sheet-events/set-node-instruction api-client sheet-id (:id node) instruction-value]))

        handle-save-io (fn []
                         (rf/dispatch [::sheet-events/set-node-io api-client sheet-id (:id node) reads-value writes-value]))

        handle-save-check (fn []
                            (rf/dispatch [::sheet-events/set-node-check
                                          api-client sheet-id (:id node)
                                          {:key check-key
                                           :op check-op
                                           :value check-value
                                           :on-fail check-on-fail}]))

        handle-add-child (fn [child-type]
                           (let [num-children (count (:children-ids node))]
                             (rf/dispatch [::sheet-events/create-node api-client sheet-id child-type (:id node) num-children])))

        handle-add-leaf-with-executor (fn [executor]
                                        (let [num-children (count (:children-ids node))]
                                          (rf/dispatch [::sheet-events/create-leaf-with-executor
                                                        api-client sheet-id (:id node) num-children executor])))

        handle-delete (fn []
                        (rf/dispatch [::sheet-events/delete-node api-client sheet-id (:id node)]))

        handle-save-executor (fn []
                               (rf/dispatch [::sheet-events/set-node-executor
                                             api-client sheet-id (:id node)
                                             executor-type model-value fn-value])
                               (when (= :ai executor-type)
                                 (rf/dispatch [::sheet-events/set-node-instruction
                                               api-client sheet-id (:id node) instruction-value])))

        handle-save-retry (fn []
                            (rf/dispatch [::sheet-events/set-node-retry
                                          api-client sheet-id (:id node)
                                          max-attempts
                                          (mapv js/parseInt (str/split backoff-ms #","))]))

        handle-save-parallel-config (fn []
                                      (rf/dispatch [::sheet-events/set-parallel-config
                                                    api-client sheet-id (:id node)
                                                    success-policy failure-policy]))

        handle-save-map-each-config (fn []
                                      (rf/dispatch [::sheet-events/set-map-each-config
                                                    api-client sheet-id (:id node)
                                                    source-key item-key output-key
                                                    (when (seq max-concurrency)
                                                      (js/parseInt max-concurrency))]))

        handle-save-llm-condition-config (fn []
                                           (rf/dispatch [::sheet-events/set-llm-condition-config
                                                         api-client sheet-id (:id node)
                                                         instruction-value reads-value model-value]))]

    (if node
      ($ :div {:class "w-80 border-l bg-white h-full overflow-auto"}
         ($ :div {:class "p-4 space-y-4"}
            ;; Node header
            ($ :div {:class "flex items-center gap-2"}
               ($ :div {:class (str "w-8 h-8 rounded-full flex items-center justify-center font-bold border "
                                    (node-type-color (:type node)))}
                  (node-type-icon (:type node)))
               (if editing-name?
                 ($ :div {:class "flex-1 flex gap-1"}
                    ($ input/Input {:value name-value
                                    :on-change #(set-name-value! (.. % -target -value))
                                    :class "h-8"})
                    ($ button/Button {:size "sm" :on-click handle-save-name}
                       "Save"))
                 ($ :div {:class "flex-1 flex items-center gap-2"}
                    ($ :h3 {:class "font-semibold"} (:name node))
                    ($ button/Button {:size "sm" :variant "ghost"
                                      :on-click #(set-editing-name! true)}
                       "Edit"))))

            ($ separator/Separator)

            ;; Status
            ($ :div {:class "flex items-center gap-2"}
               ($ label/Label "Status")
               ($ badge/Badge {:variant "outline"
                               :class (case (:status node)
                                        :running "border-blue-500 text-blue-600"
                                        :success "border-green-500 text-green-600"
                                        :failure "border-red-500 text-red-600"
                                        "")}
                  (name (:status node))))

            ;; Error if any
            (when (:last-error node)
              ($ :div {:class "text-sm text-red-600 bg-red-50 p-2 rounded"}
                 (:last-error node)))

            ;; Leaf-specific: Executor configuration
            (when (= :leaf (:type node))
              ($ :div {:class "space-y-4"}
                 ;; Executor type selector
                 ($ :div {:class "space-y-2"}
                    ($ label/Label "Executor")
                    ($ select/Select {:value (name executor-type)
                                      :onValueChange #(set-executor-type! (keyword %))}
                       ($ select/SelectTrigger {:class "w-full"}
                          ($ select/SelectValue))
                       ($ select/SelectContent
                          ($ select/SelectItem {:value "ai"} "AI (LLM)")
                          ($ select/SelectItem {:value "code"} "Code (Clojure fn)"))))

                 ;; AI-specific: Model selector
                 (when (= :ai executor-type)
                   ($ :div {:class "space-y-2"}
                      ($ label/Label "Model (OpenRouter)")
                      ($ input/Input {:value model-value
                                      :on-change #(set-model-value! (.. % -target -value))
                                      :placeholder "e.g., google/gemini-2.5-flash"})))

                 ;; Code-specific: Function symbol
                 (when (= :code executor-type)
                   ($ :div {:class "space-y-2"}
                      ($ label/Label "Function (fully qualified)")
                      ($ input/Input {:value fn-value
                                      :on-change #(set-fn-value! (.. % -target -value))
                                      :placeholder "e.g., myapp.fns/process"})))

                 ;; Instruction (for AI executor)
                 (when (= :ai executor-type)
                   ($ :div {:class "space-y-2"}
                      ($ label/Label "Instruction")
                      ($ textarea/Textarea {:value instruction-value
                                            :on-change #(set-instruction-value! (.. % -target -value))
                                            :rows 4
                                            :placeholder "What should this node do?"})))

                 ($ button/Button {:size "sm" :on-click handle-save-executor}
                    "Save Executor")))

            ;; Leaf-specific: Reads/Writes
            (when (= :leaf (:type node))
              ($ :div {:class "space-y-3"}
                 ;; Reads
                 ($ :div {:class "space-y-1"}
                    ($ label/Label "Reads (inputs)")
                    ($ :div {:class "flex flex-wrap gap-1"}
                       (for [k reads-value]
                         ($ badge/Badge {:key k :variant "secondary" :class "cursor-pointer"
                                         :on-click #(set-reads-value! (vec (remove #{k} reads-value)))}
                            (str k " x")))
                       ($ select/Select {:value ""
                                         :onValueChange #(when (seq %)
                                                           (set-reads-value! (conj reads-value %)))}
                          ($ select/SelectTrigger {:class "w-20 h-6 text-xs"}
                             ($ select/SelectValue {:placeholder "+"}))
                          ($ select/SelectContent
                             (for [k blackboard-keys]
                               (when-not (some #{k} reads-value)
                                 ($ select/SelectItem {:key k :value k} k)))))))

                 ;; Writes
                 ($ :div {:class "space-y-1"}
                    ($ label/Label "Writes (outputs)")
                    ($ :div {:class "flex flex-wrap gap-1"}
                       (for [k writes-value]
                         ($ badge/Badge {:key k :variant "secondary" :class "cursor-pointer"
                                         :on-click #(set-writes-value! (vec (remove #{k} writes-value)))}
                            (str k " x")))
                       ($ select/Select {:value ""
                                         :onValueChange #(when (seq %)
                                                           (set-writes-value! (conj writes-value %)))}
                          ($ select/SelectTrigger {:class "w-20 h-6 text-xs"}
                             ($ select/SelectValue {:placeholder "+"}))
                          ($ select/SelectContent
                             (for [k blackboard-keys]
                               (when-not (some #{k} writes-value)
                                 ($ select/SelectItem {:key k :value k} k)))))))

                 ($ button/Button {:size "sm" :variant "outline" :on-click handle-save-io}
                    "Save I/O")))

            ;; Leaf-specific: Retry configuration
            (when (= :leaf (:type node))
              ($ :div {:class "space-y-2"}
                 ($ :div {:class "flex items-center gap-2"}
                    ($ checkbox/Checkbox {:id "retry-enabled"
                                          :checked retry-enabled?
                                          :onCheckedChange set-retry-enabled!})
                    ($ label/Label {:htmlFor "retry-enabled"} "Enable Retry"))

                 (when retry-enabled?
                   ($ :div {:class "space-y-2 pl-6"}
                      ($ :div {:class "space-y-1"}
                         ($ :span {:class "text-xs text-gray-500"} "Max Attempts")
                         ($ input/Input {:type "number"
                                         :value max-attempts
                                         :on-change #(set-max-attempts! (js/parseInt (.. % -target -value)))
                                         :class "w-20"}))
                      ($ :div {:class "space-y-1"}
                         ($ :span {:class "text-xs text-gray-500"} "Backoff (ms, comma-sep)")
                         ($ input/Input {:value backoff-ms
                                         :on-change #(set-backoff-ms! (.. % -target -value))
                                         :placeholder "100,500,2000"}))
                      ($ button/Button {:size "sm" :variant "outline" :on-click handle-save-retry}
                         "Save Retry")))))

            ;; Condition-specific: Check configuration
            (when (= :condition (:type node))
              ($ :div {:class "space-y-3"}
                 ($ label/Label "Condition Check")
                 ;; Key selector
                 ($ :div {:class "space-y-1"}
                    ($ :span {:class "text-xs text-gray-500"} "Blackboard Key")
                    ($ select/Select {:value check-key
                                      :onValueChange #(set-check-key! %)}
                       ($ select/SelectTrigger {:class "w-full"}
                          ($ select/SelectValue {:placeholder "Select key..."}))
                       ($ select/SelectContent
                          (for [k blackboard-keys]
                            ($ select/SelectItem {:key k :value k} k)))))
                 ;; Operator selector
                 ($ :div {:class "space-y-1"}
                    ($ :span {:class "text-xs text-gray-500"} "Operator")
                    ($ select/Select {:value (name check-op)
                                      :onValueChange #(set-check-op! (keyword %))}
                       ($ select/SelectTrigger {:class "w-full"}
                          ($ select/SelectValue))
                       ($ select/SelectContent
                          (for [{:keys [value label]} condition-operators]
                            ($ select/SelectItem {:key (name value) :value (name value)} label)))))
                 ;; Value input (not needed for exists/truthy)
                 (when-not (#{:exists :truthy} check-op)
                   ($ :div {:class "space-y-1"}
                      ($ :span {:class "text-xs text-gray-500"} "Value")
                      ($ input/Input {:value check-value
                                      :on-change #(set-check-value! (.. % -target -value))
                                      :placeholder "Value to compare"})))
                 ;; On-fail behavior
                 ($ :div {:class "space-y-1"}
                    ($ :span {:class "text-xs text-gray-500"} "On Fail")
                    ($ select/Select {:value (name check-on-fail)
                                      :onValueChange #(set-check-on-fail! (keyword %))}
                       ($ select/SelectTrigger {:class "w-full"}
                          ($ select/SelectValue))
                       ($ select/SelectContent
                          ($ select/SelectItem {:value "failure"} "Failure (stop)")
                          ($ select/SelectItem {:value "running"} "Running (retry)"))))
                 ($ button/Button {:size "sm" :on-click handle-save-check
                                   :disabled (empty? check-key)}
                    "Save Check")))

            ;; LLM Condition-specific: Configuration
            (when (= :llm-condition (:type node))
              ($ :div {:class "space-y-3"}
                 ($ label/Label "LLM Condition Configuration")

                 ;; Model selector
                 ($ :div {:class "space-y-1"}
                    ($ :span {:class "text-xs text-gray-500"} "Model (OpenRouter)")
                    ($ input/Input {:value model-value
                                    :on-change #(set-model-value! (.. % -target -value))
                                    :placeholder "e.g., google/gemini-2.5-flash"}))

                 ;; Instruction
                 ($ :div {:class "space-y-1"}
                    ($ :span {:class "text-xs text-gray-500"} "Question (yes/no)")
                    ($ textarea/Textarea {:value instruction-value
                                          :on-change #(set-instruction-value! (.. % -target -value))
                                          :rows 3
                                          :placeholder "Is the user's message urgent?"}))

                 ;; Reads
                 ($ :div {:class "space-y-1"}
                    ($ :span {:class "text-xs text-gray-500"} "Reads (context)")
                    ($ :div {:class "flex flex-wrap gap-1"}
                       (for [k reads-value]
                         ($ badge/Badge {:key k :variant "secondary" :class "cursor-pointer"
                                         :on-click #(set-reads-value! (vec (remove #{k} reads-value)))}
                            (str k " x")))
                       ($ select/Select {:value ""
                                         :onValueChange #(when (seq %)
                                                           (set-reads-value! (conj reads-value %)))}
                          ($ select/SelectTrigger {:class "w-20 h-6 text-xs"}
                             ($ select/SelectValue {:placeholder "+"}))
                          ($ select/SelectContent
                             (for [k blackboard-keys]
                               (when-not (some #{k} reads-value)
                                 ($ select/SelectItem {:key k :value k} k)))))))

                 ($ button/Button {:size "sm" :on-click handle-save-llm-condition-config
                                   :disabled (empty? instruction-value)}
                    "Save Configuration")))

            ;; Parallel-specific: Policy configuration
            (when (= :parallel (:type node))
              ($ :div {:class "space-y-3"}
                 ($ label/Label "Parallel Policies")

                 ;; Success policy
                 ($ :div {:class "space-y-1"}
                    ($ :span {:class "text-xs text-gray-500"} "Success Policy")
                    ($ select/Select {:value (name success-policy)
                                      :onValueChange #(set-success-policy! (keyword %))}
                       ($ select/SelectTrigger {:class "w-full"}
                          ($ select/SelectValue))
                       ($ select/SelectContent
                          ($ select/SelectItem {:value "all"} "All (all must succeed)")
                          ($ select/SelectItem {:value "any"} "Any (one succeeds)")
                          ($ select/SelectItem {:value "majority"} "Majority (>50% succeed)"))))

                 ;; Failure policy
                 ($ :div {:class "space-y-1"}
                    ($ :span {:class "text-xs text-gray-500"} "Failure Policy")
                    ($ select/Select {:value (name failure-policy)
                                      :onValueChange #(set-failure-policy! (keyword %))}
                       ($ select/SelectTrigger {:class "w-full"}
                          ($ select/SelectValue))
                       ($ select/SelectContent
                          ($ select/SelectItem {:value "any"} "Any (one fails)")
                          ($ select/SelectItem {:value "all"} "All (all must fail)"))))

                 ($ button/Button {:size "sm" :on-click handle-save-parallel-config}
                    "Save Policies")))

            ;; Map-each-specific: Configuration
            (when (= :map-each (:type node))
              ($ :div {:class "space-y-3"}
                 ($ label/Label "Map-Each Configuration")

                 ;; Source key
                 ($ :div {:class "space-y-1"}
                    ($ :span {:class "text-xs text-gray-500"} "Source Key (list to iterate)")
                    ($ select/Select {:value source-key
                                      :onValueChange set-source-key!}
                       ($ select/SelectTrigger {:class "w-full"}
                          ($ select/SelectValue {:placeholder "Select key..."}))
                       ($ select/SelectContent
                          (for [k blackboard-keys]
                            ($ select/SelectItem {:key k :value k} k)))))

                 ;; Item key
                 ($ :div {:class "space-y-1"}
                    ($ :span {:class "text-xs text-gray-500"} "Item Key (current item var)")
                    ($ input/Input {:value item-key
                                    :on-change #(set-item-key! (.. % -target -value))
                                    :placeholder "e.g., current-item"}))

                 ;; Output key
                 ($ :div {:class "space-y-1"}
                    ($ :span {:class "text-xs text-gray-500"} "Output Key (results list)")
                    ($ input/Input {:value output-key
                                    :on-change #(set-output-key! (.. % -target -value))
                                    :placeholder "e.g., processed-items"}))

                 ;; Max concurrency
                 ($ :div {:class "space-y-1"}
                    ($ :span {:class "text-xs text-gray-500"} "Max Concurrency (blank = sequential)")
                    ($ input/Input {:type "number"
                                    :value max-concurrency
                                    :on-change #(set-max-concurrency! (.. % -target -value))
                                    :placeholder "e.g., 10"}))

                 ($ button/Button {:size "sm" :on-click handle-save-map-each-config}
                    "Save Config")))

            ;; Add child (for composite nodes)
            (when (#{:sequence :fallback :parallel :map-each} (:type node))
              ($ :div {:class "space-y-3"}
                 ($ label/Label "Add Child")

                 ;; Actions
                 ($ :div {:class "space-y-1"}
                    ($ :p {:class "text-xs text-gray-500 font-medium uppercase tracking-wide"} "Actions")
                    ($ :div {:class "grid grid-cols-2 gap-1"}
                       ($ button/Button {:size "sm" :variant "outline"
                                         :on-click #(handle-add-leaf-with-executor :ai)}
                          "LLM")
                       ($ button/Button {:size "sm" :variant "outline"
                                         :on-click #(handle-add-leaf-with-executor :code)}
                          "Code")))

                 ;; Control Flow
                 ($ :div {:class "space-y-1"}
                    ($ :p {:class "text-xs text-gray-500 font-medium uppercase tracking-wide"} "Control Flow")
                    ($ :div {:class "grid grid-cols-2 gap-1"}
                       ($ button/Button {:size "sm" :variant "outline"
                                         :on-click #(handle-add-child :sequence)}
                          "Sequence")
                       ($ button/Button {:size "sm" :variant "outline"
                                         :on-click #(handle-add-child :parallel)}
                          "Parallel")
                       ($ button/Button {:size "sm" :variant "outline"
                                         :on-click #(handle-add-child :fallback)}
                          "Fallback")
                       ($ button/Button {:size "sm" :variant "outline"
                                         :on-click #(handle-add-child :map-each)}
                          "Map Each")))

                 ;; Conditions
                 ($ :div {:class "space-y-1"}
                    ($ :p {:class "text-xs text-gray-500 font-medium uppercase tracking-wide"} "Conditions")
                    ($ :div {:class "grid grid-cols-2 gap-1"}
                       ($ button/Button {:size "sm" :variant "outline"
                                         :on-click #(handle-add-child :condition)}
                          "Condition")
                       ($ button/Button {:size "sm" :variant "outline"
                                         :on-click #(handle-add-child :llm-condition)}
                          "LLM Condition")))))

            ($ separator/Separator)

            ;; Delete button
            ($ button/Button {:variant "destructive" :size "sm" :class "w-full"
                              :disabled (seq (:children-ids node))
                              :on-click handle-delete}
               (if (seq (:children-ids node))
                 "Delete children first"
                 "Delete Node"))))

      ;; No node selected
      ($ :div {:class "w-80 border-l bg-gray-50 h-full flex items-center justify-center"}
         ($ :p {:class "text-gray-400"} "Select a node to edit")))))

;; =============================================================================
;; Version Status Badge
;; =============================================================================

(defui version-status-badge []
  (let [version-info (use-subscribe [::sheet-subs/version-info])
        {:keys [published-version draft-dirty? execution-mode has-stash?]} version-info]
    ($ :div {:class "flex items-center gap-2"}
       ;; Published version indicator
       (if published-version
         ($ :div {:class "flex items-center gap-1"}
            ($ badge/Badge {:variant "outline" :class "text-xs"}
               (str "v" published-version))
            (when draft-dirty?
              ($ badge/Badge {:variant "secondary" :class "text-xs bg-amber-100 text-amber-700"}
                 "Draft modified")))
         ($ badge/Badge {:variant "secondary" :class "text-xs"}
            "Unpublished"))
       ;; Execution mode indicator
       (when published-version
         ($ badge/Badge {:class (str "text-xs "
                                     (if (= :published execution-mode)
                                       "bg-green-100 text-green-700"
                                       "bg-blue-100 text-blue-700"))}
            (if (= :published execution-mode) "Published mode" "Draft mode")))
       ;; Stash indicator
       (when has-stash?
         ($ badge/Badge {:variant "outline" :class "text-xs border-purple-300 text-purple-600"}
            "Stash available")))))

;; =============================================================================
;; Publish Dialog
;; =============================================================================

(defui publish-dialog [{:keys [open? on-close sheet-id]}]
  (let [[description set-description!] (use-state "")
        publishing? (use-subscribe [::sheet-subs/publishing?])
        ctx (context/use-context)
        api-client (:api/client ctx)

        handle-publish (fn []
                         (rf/dispatch [::sheet-events/publish-version
                                       api-client sheet-id
                                       (when (seq description) description)])
                         (on-close)
                         (set-description! ""))]

    ($ dialog/Dialog {:open open? :onOpenChange #(when-not publishing? (on-close))}
       ($ dialog/DialogContent
          ($ dialog/DialogHeader
             ($ dialog/DialogTitle "Publish Version"))
          ($ :div {:class "space-y-4 py-4"}
             ($ :p {:class "text-sm text-gray-600"}
                "Publishing will create a snapshot of the current tree that can be used for execution and reverted to later.")
             ($ :div {:class "space-y-2"}
                ($ label/Label "Description (optional)")
                ($ input/Input {:value description
                                :on-change #(set-description! (.. % -target -value))
                                :placeholder "e.g., Added new validation step"
                                :disabled publishing?})))
          ($ dialog/DialogFooter
             ($ button/Button {:variant "outline" :on-click on-close :disabled publishing?}
                "Cancel")
             ($ button/Button {:on-click handle-publish :disabled publishing?}
                (if publishing? "Publishing..." "Publish")))))))

;; =============================================================================
;; Version History Dialog
;; =============================================================================

(defui version-history-dialog [{:keys [open? on-close sheet-id]}]
  (let [versions (use-subscribe [::sheet-subs/versions-list])
        loading? (use-subscribe [::sheet-subs/version-history-loading?])
        reverting? (use-subscribe [::sheet-subs/reverting?])
        has-stash? (use-subscribe [::sheet-subs/has-stash?])
        restoring-stash? (use-subscribe [::sheet-subs/restoring-stash?])
        ctx (context/use-context)
        api-client (:api/client ctx)

        handle-revert (fn [version-number]
                        (rf/dispatch [::sheet-events/revert-to-version
                                      api-client sheet-id version-number])
                        (on-close))

        handle-restore-stash (fn []
                               (rf/dispatch [::sheet-events/restore-stash api-client sheet-id])
                               (on-close))]

    ;; Load history when dialog opens
    (use-effect
      (fn []
        (when open?
          (rf/dispatch [::sheet-events/load-version-history api-client sheet-id]))
        js/undefined)
      [open? api-client sheet-id])

    ($ dialog/Dialog {:open open? :onOpenChange on-close}
       ($ dialog/DialogContent {:class "max-w-lg"}
          ($ dialog/DialogHeader
             ($ dialog/DialogTitle "Version History"))
          ($ :div {:class "py-4 max-h-96 overflow-y-auto"}
             (if loading?
               ($ :div {:class "flex justify-center py-8"}
                  ($ spinner/Spinner {:class "w-6 h-6"}))
               (if (empty? versions)
                 ($ :p {:class "text-center text-gray-500 py-8"}
                    "No versions published yet")
                 ($ :div {:class "space-y-2"}
                    ;; Stash option
                    (when has-stash?
                      ($ :div {:class "p-3 border rounded-lg bg-purple-50 border-purple-200"}
                         ($ :div {:class "flex items-center justify-between"}
                            ($ :div
                               ($ :p {:class "font-medium text-purple-700"} "Stashed Draft")
                               ($ :p {:class "text-xs text-purple-600"}
                                  "Your draft changes before last revert"))
                            ($ button/Button {:size "sm"
                                              :variant "outline"
                                              :disabled restoring-stash?
                                              :on-click handle-restore-stash}
                               (if restoring-stash? "Restoring..." "Restore")))))
                    ;; Version list
                    (for [version (reverse versions)]
                      ($ :div {:key (:version-number version)
                               :class "p-3 border rounded-lg hover:bg-gray-50"}
                         ($ :div {:class "flex items-center justify-between"}
                            ($ :div
                               ($ :p {:class "font-medium"}
                                  (str "Version " (:version-number version)))
                               (when (:description version)
                                 ($ :p {:class "text-sm text-gray-600"}
                                    (:description version)))
                               ($ :p {:class "text-xs text-gray-400"}
                                  (:published-at version)))
                            ($ button/Button {:size "sm"
                                              :variant "outline"
                                              :disabled reverting?
                                              :on-click #(handle-revert (:version-number version))}
                               "Revert"))))))))
          ($ dialog/DialogFooter
             ($ button/Button {:variant "outline" :on-click on-close}
                "Close"))))))

;; =============================================================================
;; Sheet Toolbar
;; =============================================================================

(defui sheet-toolbar [{:keys [sheet sheet-id]}]
  (let [ticking? (use-subscribe [::sheet-subs/sheet-ticking?])
        tick-progress (use-subscribe [::sheet-subs/tick-progress])
        root-node (use-subscribe [::sheet-subs/root-node])
        can-publish? (use-subscribe [::sheet-subs/can-publish?])
        published-version (use-subscribe [::sheet-subs/published-version])
        execution-mode (use-subscribe [::sheet-subs/execution-mode])
        ctx (context/use-context)
        api-client (:api/client ctx)
        navigate! (:router/navigate! ctx)

        [publish-dialog-open? set-publish-dialog-open!] (use-state false)
        [history-dialog-open? set-history-dialog-open!] (use-state false)

        handle-tick (fn []
                      (rf/dispatch [::sheet-events/tick-tree api-client sheet-id]))
        handle-stop (fn []
                      (rf/dispatch [::sheet-events/stop-ticking api-client sheet-id]))
        handle-export (fn []
                        (rf/dispatch [::sheet-events/export-sheet api-client sheet-id]))
        handle-toggle-mode (fn []
                             (let [new-mode (if (= :published execution-mode) :draft :published)]
                               (rf/dispatch [::sheet-events/set-execution-mode
                                             api-client sheet-id new-mode])))]

    ($ :<>
       ($ :div {:class "flex items-center justify-between p-4 border-b bg-white"}
          ($ :div {:class "flex items-center gap-4"}
             ($ button/Button {:variant "ghost" :size "sm" :on-click #(navigate! :sheets)}
                "< Back")
             ($ :h1 {:class "text-xl font-semibold"}
                (:name sheet))
             ($ version-status-badge))
          ($ :div {:class "flex items-center gap-2"}
             ;; Tick progress
             (when ticking?
               ($ :div {:class "flex items-center gap-2 text-sm text-gray-600 mr-2"}
                  ($ spinner/Spinner {:class "w-4 h-4"})
                  ($ :span (str "Iteration " (:iteration tick-progress) "/" (:budget tick-progress)))))

             ;; Execution mode toggle (only if published)
             (when published-version
               ($ button/Button {:variant "outline"
                                 :size "sm"
                                 :on-click handle-toggle-mode}
                  (if (= :published execution-mode) "Use Draft" "Use Published")))

             ;; Version history
             ($ button/Button {:variant "outline"
                               :size "sm"
                               :on-click #(set-history-dialog-open! true)}
                "History")

             ;; Publish
             ($ button/Button {:variant "outline"
                               :size "sm"
                               :disabled (not can-publish?)
                               :on-click #(set-publish-dialog-open! true)}
                "Publish")

             ($ separator/Separator {:orientation "vertical" :class "h-6"})

             ;; Export
             ($ button/Button {:variant "outline" :size "sm" :on-click handle-export}
                "Download")

             ;; Tick/Stop
             (if ticking?
               ($ button/Button {:variant "outline" :size "sm" :on-click handle-stop}
                  "Stop")
               ($ button/Button {:variant "default"
                                 :size "sm"
                                 :disabled (not root-node)
                                 :on-click handle-tick}
                  "Tick Tree"))))

       ;; Dialogs
       ($ publish-dialog {:open? publish-dialog-open?
                          :on-close #(set-publish-dialog-open! false)
                          :sheet-id sheet-id})
       ($ version-history-dialog {:open? history-dialog-open?
                                  :on-close #(set-history-dialog-open! false)
                                  :sheet-id sheet-id}))))

;; =============================================================================
;; Main Sheet Page
;; =============================================================================

(defui sheet-page [{:keys [sheet-id]}]
  (let [sheet (use-subscribe [::sheet-subs/sheet])
        loading? (use-subscribe [::sheet-subs/sheet-loading?])
        error (use-subscribe [::sheet-subs/sheet-error])
        ctx (context/use-context)
        api-client (:api/client ctx)]

    ;; Load sheet on mount
    (use-effect
      (fn []
        (rf/dispatch [::sheet-events/load-sheet-view-screen api-client sheet-id])
        ;; Cleanup on unmount
        (fn []
          (rf/dispatch [::sheet-events/clear-sheet])))
      [api-client sheet-id])

    (cond
      loading?
      ($ :div {:class "flex items-center justify-center h-full"}
         ($ spinner/Spinner {:class "w-8 h-8"}))

      error
      ($ :div {:class "flex items-center justify-center h-full"}
         ($ card/Card {:class "p-6"}
            ($ :h2 {:class "text-lg font-semibold text-red-600"} "Error loading sheet")
            ($ :p {:class "text-sm text-gray-600"} (error-message error))))

      sheet
      ($ :div {:class "flex flex-col h-full"}
         ($ sheet-toolbar {:sheet sheet :sheet-id sheet-id})
         ($ :div {:class "flex flex-1 overflow-hidden"}
            ;; Main area: tree + blackboard
            ($ :div {:class "flex-1 flex flex-col overflow-hidden"}
               ($ :div {:class "flex-1 overflow-y-auto overflow-x-hidden bg-gray-50"}
                  ($ tree-grid {:sheet-id sheet-id}))
               ($ blackboard-panel {:sheet-id sheet-id}))
            ;; Editor panel
            ($ node-editor-panel {:sheet-id sheet-id})))

      :else
      ($ :div {:class "flex items-center justify-center h-full"}
         ($ :p "Sheet not found")))))

;; =============================================================================
;; Sheets List Page
;; =============================================================================

(defui create-sheet-dialog [{:keys [open? on-close]}]
  (let [[name set-name!] (use-state "")
        [creating? set-creating!] (use-state false)
        ctx (context/use-context)
        api-client (:api/client ctx)

        handle-create (fn []
                        (set-creating! true)
                        (rf/dispatch [::sheet-events/create-sheet
                                      api-client name
                                      (fn [_]
                                        (set-creating! false)
                                        (on-close))]))]
    ($ dialog/Dialog {:open open? :onOpenChange #(when-not creating? (on-close))}
       ($ dialog/DialogContent
          ($ dialog/DialogHeader
             ($ dialog/DialogTitle "Create New Behavior Tree"))
          ($ :div {:class "space-y-4 py-4"}
             ($ :div {:class "space-y-2"}
                ($ label/Label "Name")
                ($ input/Input {:value name
                                :on-change #(set-name! (.. % -target -value))
                                :placeholder "My Behavior Tree"
                                :disabled creating?})))
          ($ dialog/DialogFooter
             ($ button/Button {:variant "outline" :on-click on-close :disabled creating?}
                "Cancel")
             ($ button/Button {:on-click handle-create
                               :disabled (or creating? (empty? name))}
                (if creating? "Creating..." "Create")))))))

(defui sheets-list-page []
  (let [sheets (use-subscribe [::sheet-subs/sheets])
        loading? (use-subscribe [::sheet-subs/sheets-loading?])
        error (use-subscribe [::sheet-subs/sheets-error])
        ctx (context/use-context)
        api-client (:api/client ctx)
        navigate! (:router/navigate! ctx)
        [dialog-open? set-dialog-open!] (use-state false)]

    ;; Load sheets on mount
    (use-effect
      (fn []
        (rf/dispatch [::sheet-events/load-sheets-list-screen api-client]))
      [api-client])

    ($ :div {:class "container mx-auto py-8 px-4"}
       ;; Show error banner if there's an error
       (when error
         ($ :div {:class "mb-4 p-4 bg-red-50 border border-red-200 rounded-lg text-red-700"}
            (error-message error)))
       ($ :div {:class "flex items-center justify-between mb-8"}
          ($ :h1 {:class "text-2xl font-bold"} "Behavior Trees")
          ($ button/Button {:on-click #(set-dialog-open! true)}
             "+ New Tree"))

       (if loading?
         ($ :div {:class "flex items-center justify-center py-12"}
            ($ spinner/Spinner {:class "w-8 h-8"}))
         (if (empty? sheets)
           ($ :div {:class "text-center py-12"}
              ($ :p {:class "text-gray-500 mb-4"} "No behavior trees yet")
              ($ button/Button {:on-click #(set-dialog-open! true)}
                 "Create your first tree"))
           ($ :div {:class "grid gap-4 md:grid-cols-2 lg:grid-cols-3"}
              (for [sheet sheets]
                ($ card/Card {:key (:id sheet)
                              :class "cursor-pointer hover:shadow-md transition-shadow"
                              :on-click #(navigate! :sheet {:sheet-id (str (:id sheet))})}
                   ($ card/CardHeader
                      ($ card/CardTitle (:name sheet)))
                   ($ card/CardFooter {:class "text-sm text-gray-500"}
                      (str "Created: " (:created-at sheet))))))))

       ($ create-sheet-dialog {:open? dialog-open?
                               :on-close #(set-dialog-open! false)}))))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defui main [{:keys [query-params]}]
  (let [sheet-id (:sheet-id query-params)]
    (if sheet-id
      ($ sheet-page {:sheet-id (uuid sheet-id)})
      ($ sheets-list-page))))
