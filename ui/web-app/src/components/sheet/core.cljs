(ns components.sheet.core
  "Behavior Tree Sheet UI components."
  (:require [uix.core :as uix :refer [defui $ use-state use-effect]]
            [re-frame.core :as rf]
            [re-frame.uix :refer [use-subscribe]]
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
            [components.context.interface :as context]
            [store.sheet.events :as sheet-events]
            [store.sheet.subs :as sheet-subs]))

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
    "?"))

(defn node-type-color [node-type]
  (case node-type
    :leaf "bg-blue-100 border-blue-300"
    :sequence "bg-purple-100 border-purple-300"
    :fallback "bg-orange-100 border-orange-300"
    "bg-gray-100"))

;; =============================================================================
;; Node Cell Component
;; =============================================================================

(defui node-cell [{:keys [node layout selected? on-select]}]
  (let [node-type (:type node)
        status (:status node)
        col-start (int (* (:start-col layout) 12))
        col-span (max 1 (int (* (- (:end-col layout) (:start-col layout)) 12)))]
    ($ :div {:class (str "p-2 border-2 rounded-lg cursor-pointer transition-all "
                         (node-type-color node-type) " "
                         (if selected?
                           "ring-2 ring-blue-500 ring-offset-2"
                           "hover:shadow-md"))
             :style {:grid-column (str (inc col-start) " / span " col-span)}
             :on-click #(on-select (:id node))}
       ;; Header row: type icon + name
       ($ :div {:class "flex items-center gap-2 mb-1"}
          ($ :div {:class "w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold border bg-white"}
             (node-type-icon node-type))
          ($ :span {:class "font-medium text-sm truncate flex-1"}
             (:name node))
          ($ :div {:class (str "w-3 h-3 rounded-full " (status-color status))}))

       ;; Instruction preview for leaf nodes
       (when (and (= :leaf node-type) (:instruction node))
         ($ :p {:class "text-xs text-gray-500 truncate mt-1"}
            (:instruction node)))

       ;; Children count for composite nodes
       (when (#{:sequence :fallback} node-type)
         ($ :p {:class "text-xs text-gray-400 mt-1"}
            (str (count (:children-ids node)) " children"))))))

;; =============================================================================
;; Empty Row Cell (for adding nodes)
;; =============================================================================

(defui add-node-cell [{:keys [sheet-id parent-id index on-create-node]}]
  (let [[menu-open? set-menu-open!] (use-state false)]
    ($ :div {:class "p-2 border-2 border-dashed rounded-lg cursor-pointer hover:bg-gray-50 flex items-center justify-center"
             :on-click #(set-menu-open! true)}
       (if menu-open?
         ($ :div {:class "flex gap-2"}
            ($ button/Button {:size "sm" :variant "outline"
                              :on-click #(do (set-menu-open! false)
                                             (on-create-node :leaf parent-id index))}
               "Leaf")
            ($ button/Button {:size "sm" :variant "outline"
                              :on-click #(do (set-menu-open! false)
                                             (on-create-node :sequence parent-id index))}
               "Seq")
            ($ button/Button {:size "sm" :variant "outline"
                              :on-click #(do (set-menu-open! false)
                                             (on-create-node :fallback parent-id index))}
               "Fall"))
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
         ($ :div {:class "space-y-2"}
            (for [row-idx (range num-rows)]
              (let [row-items (get rows-data row-idx [])]
                ($ :div {:key row-idx
                         :class "grid gap-2"
                         :style {:grid-template-columns "repeat(12, 1fr)"}}
                   (for [item row-items]
                     (let [node (get nodes (:node-id item))]
                       ($ node-cell {:key (:node-id item)
                                     :node node
                                     :layout item
                                     :selected? (= (:id node) selected-id)
                                     :on-select handle-select})))))))

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
;; Blackboard Panel Component
;; =============================================================================

(defui blackboard-panel [{:keys [sheet-id]}]
  (let [blackboard-list (use-subscribe [::sheet-subs/blackboard-list])
        field-types (use-subscribe [::sheet-subs/field-types])
        selected-node (use-subscribe [::sheet-subs/selected-node])
        ctx (context/use-context)
        api-client (:api/client ctx)

        [new-key set-new-key!] (use-state "")
        [new-type set-new-type!] (use-state :text)
        [editing-key set-editing-key!] (use-state nil)
        [edit-value set-edit-value!] (use-state "")

        keys-used-by-selected (when selected-node
                                (set (concat (:reads selected-node) (:writes selected-node))))

        handle-declare (fn []
                         (when (seq new-key)
                           (rf/dispatch [::sheet-events/declare-key api-client sheet-id new-key new-type])
                           (set-new-key! "")))

        handle-save-value (fn [key]
                            (rf/dispatch [::sheet-events/set-key-value api-client sheet-id key edit-value])
                            (set-editing-key! nil))

        handle-delete (fn [key]
                        (rf/dispatch [::sheet-events/delete-key api-client sheet-id key]))]

    ($ :div {:class "p-4 border-t"}
       ($ :h3 {:class "font-semibold mb-3"} "Blackboard")

       ;; Existing keys
       (if (empty? blackboard-list)
         ($ :p {:class "text-gray-400 text-sm mb-3"} "No keys declared")
         ($ :div {:class "space-y-2 mb-4"}
            (for [entry blackboard-list]
              (let [k (:key entry)
                    is-used? (and keys-used-by-selected (keys-used-by-selected k))]
                ($ :div {:key k
                         :class (str "flex items-center gap-2 p-2 rounded text-sm "
                                     (if is-used? "bg-blue-50 border border-blue-200" "bg-gray-50"))}
                   ($ :span {:class "font-mono font-medium"} k)
                   ($ badge/Badge {:variant "outline" :class "text-xs"}
                      (name (:type entry)))
                   (if (= editing-key k)
                     ($ :div {:class "flex-1 flex gap-1"}
                        ($ input/Input {:value edit-value
                                        :on-change #(set-edit-value! (.. % -target -value))
                                        :class "h-7 text-xs flex-1"})
                        ($ button/Button {:size "sm" :variant "ghost" :class "h-7 px-2"
                                          :on-click #(handle-save-value k)}
                           "Save"))
                     ($ :div {:class "flex-1 flex items-center gap-2"}
                        ($ :span {:class "text-gray-600 truncate"}
                           (str (or (:value entry) "(nil)")))
                        ($ button/Button {:size "sm" :variant "ghost" :class "h-7 px-2"
                                          :on-click #(do (set-editing-key! k)
                                                         (set-edit-value! (str (or (:value entry) ""))))}
                           "Edit"))))))))

       ;; Add new key
       ($ :div {:class "flex gap-2"}
          ($ input/Input {:placeholder "Key name"
                          :value new-key
                          :on-change #(set-new-key! (.. % -target -value))
                          :class "flex-1"})
          ($ select/Select {:value (name new-type)
                            :onValueChange #(set-new-type! (keyword %))}
             ($ select/SelectTrigger {:class "w-24"}
                ($ select/SelectValue))
             ($ select/SelectContent
                (for [ft field-types]
                  ($ select/SelectItem {:key (name (:value ft)) :value (name (:value ft))}
                     (:label ft)))))
          ($ button/Button {:size "sm" :on-click handle-declare}
             "+")))))

;; =============================================================================
;; Node Editor Panel Component
;; =============================================================================

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

        ;; Initialize values when node changes
        _ (use-effect
            (fn []
              (when node
                (set-name-value! (:name node))
                (set-instruction-value! (or (:instruction node) ""))
                (set-reads-value! (vec (:reads node)))
                (set-writes-value! (vec (:writes node)))))
            [node])

        handle-save-name (fn []
                           (rf/dispatch [::sheet-events/set-node-name api-client sheet-id (:id node) name-value])
                           (set-editing-name! false))

        handle-save-instruction (fn []
                                  (rf/dispatch [::sheet-events/set-node-instruction api-client sheet-id (:id node) instruction-value]))

        handle-save-io (fn []
                         (rf/dispatch [::sheet-events/set-node-io api-client sheet-id (:id node) reads-value writes-value]))

        handle-add-child (fn [child-type]
                           (rf/dispatch [::sheet-events/create-node api-client sheet-id child-type (:id node) nil]))

        handle-delete (fn []
                        (rf/dispatch [::sheet-events/delete-node api-client sheet-id (:id node)]))]

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

            ;; Leaf-specific: Instruction
            (when (= :leaf (:type node))
              ($ :div {:class "space-y-2"}
                 ($ label/Label "Instruction")
                 ($ textarea/Textarea {:value instruction-value
                                       :on-change #(set-instruction-value! (.. % -target -value))
                                       :rows 4
                                       :placeholder "What should this node do?"})
                 ($ button/Button {:size "sm" :on-click handle-save-instruction}
                    "Save Instruction")))

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

            ;; Add child (for composite nodes)
            (when (#{:sequence :fallback} (:type node))
              ($ :div {:class "space-y-2"}
                 ($ label/Label "Add Child")
                 ($ :div {:class "flex gap-2"}
                    ($ button/Button {:size "sm" :variant "outline"
                                      :on-click #(handle-add-child :leaf)}
                       "+ Leaf")
                    ($ button/Button {:size "sm" :variant "outline"
                                      :on-click #(handle-add-child :sequence)}
                       "+ Seq")
                    ($ button/Button {:size "sm" :variant "outline"
                                      :on-click #(handle-add-child :fallback)}
                       "+ Fall"))))

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
;; Sheet Toolbar
;; =============================================================================

(defui sheet-toolbar [{:keys [sheet sheet-id]}]
  (let [ticking? (use-subscribe [::sheet-subs/sheet-ticking?])
        root-node (use-subscribe [::sheet-subs/root-node])
        ctx (context/use-context)
        api-client (:api/client ctx)
        navigate! (:router/navigate! ctx)

        handle-tick (fn []
                      (rf/dispatch [::sheet-events/tick-tree api-client sheet-id]))]

    ($ :div {:class "flex items-center justify-between p-4 border-b bg-white"}
       ($ :div {:class "flex items-center gap-4"}
          ($ button/Button {:variant "ghost" :size "sm" :on-click #(navigate! :sheets)}
             "< Back")
          ($ :h1 {:class "text-xl font-semibold"}
             (:name sheet)))
       ($ :div {:class "flex items-center gap-2"}
          ($ button/Button {:variant "default"
                            :disabled (or ticking? (not root-node))
                            :on-click handle-tick}
             (if ticking?
               ($ :span {:class "flex items-center gap-2"}
                  ($ spinner/Spinner {:class "w-4 h-4"})
                  "Ticking...")
               "Tick Tree"))))))

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
            ($ :p {:class "text-sm text-gray-600"} (str error))))

      sheet
      ($ :div {:class "flex flex-col h-full"}
         ($ sheet-toolbar {:sheet sheet :sheet-id sheet-id})
         ($ :div {:class "flex flex-1 overflow-hidden"}
            ;; Main area: tree + blackboard
            ($ :div {:class "flex-1 flex flex-col overflow-hidden"}
               ($ :div {:class "flex-1 overflow-auto bg-gray-50"}
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
