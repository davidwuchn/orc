(ns components.sheet.core
  "Sheet UI components for the agent orchestration spreadsheet."
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
;; Utility Functions
;; =============================================================================

(defn col-letter->index
  "Convert column letter(s) to zero-based index. A=0, B=1, Z=25, AA=26"
  [col]
  (reduce (fn [acc c]
            (+ (* acc 26) (- (.charCodeAt c 0) 64)))
          0
          col))

(defn index->col-letter
  "Convert zero-based index to column letter(s)."
  [idx]
  (loop [n (inc idx)
         result ""]
    (if (<= n 0)
      result
      (let [remainder (mod (dec n) 26)]
        (recur (quot (dec n) 26)
               (str (char (+ 65 remainder)) result))))))

(defn parse-address
  "Parse a cell address like 'A1' into {:col 'A' :row 1}"
  [addr]
  (when addr
    (let [[_ col row] (re-matches #"([A-Z]+)(\d+)" addr)]
      {:col col :row (js/parseInt row)})))

;; =============================================================================
;; Status Badge Component
;; =============================================================================

(defui cell-status-badge [{:keys [status execution-status]}]
  (let [[variant extra-class label] (case execution-status
                                      :running ["secondary" "animate-pulse" "Running"]
                                      :completed ["default" "bg-green-500" "Done"]
                                      :failed ["destructive" "" "Failed"]
                                      :pending ["secondary" "" "Pending"]
                                      (case status
                                        :stale ["outline" "border-yellow-500 text-yellow-600" "Stale"]
                                        :error ["destructive" "" "Error"]
                                        :valid ["outline" "" "Ready"]
                                        ["outline" "" ""]))]
    (when (and label (not= label ""))
      ($ badge/Badge {:variant variant :class (str "text-xs " extra-class)}
         label))))

;; =============================================================================
;; Cell Component
;; =============================================================================

(defui grid-cell [{:keys [cell selected? on-select]}]
  (let [is-formula? (some? (:signature cell))
        has-fields? (seq (:fields cell))
        first-field-value (when has-fields?
                            (-> (:fields cell) vals first :value))]
    ($ :div {:class (str "border-r border-b p-2 min-h-[60px] cursor-pointer transition-colors "
                         (if selected?
                           "bg-blue-50 ring-2 ring-blue-500 ring-inset"
                           "hover:bg-gray-50"))
             :on-click #(on-select (:id cell))}
       ;; Cell address badge
       ($ :div {:class "flex items-center justify-between mb-1"}
          ($ :span {:class "text-xs text-gray-400 font-mono"}
             (:address cell))
          (when is-formula?
            ($ badge/Badge {:variant "outline" :class "text-xs h-4 px-1"}
               "fx")))

       ;; Cell content
       (if has-fields?
         ($ :div {:class "text-sm truncate"}
            (str first-field-value))
         (when is-formula?
           ($ :div {:class "text-xs text-gray-400 italic truncate"}
              (get-in cell [:signature :instruction]))))

       ;; Status indicator
       (when (or (not= :idle (:execution-status cell))
                 (not= :valid (:status cell)))
         ($ :div {:class "mt-1"}
            ($ cell-status-badge {:status (:status cell)
                                  :execution-status (:execution-status cell)}))))))

(defui empty-cell [{:keys [address on-create]}]
  ($ :div {:class "border-r border-b p-2 min-h-[60px] cursor-pointer hover:bg-gray-50 flex items-center justify-center group"
           :on-click #(on-create address)}
     ($ :span {:class "text-gray-300 group-hover:text-gray-500 text-xl"}
        "+")))

;; =============================================================================
;; Sheet Grid Component
;; =============================================================================

(defui sheet-grid [{:keys [sheet-id]}]
  (let [cells-by-addr (use-subscribe [::sheet-subs/cells-by-address])
        selected-id (use-subscribe [::sheet-subs/selected-cell-id])
        ctx (context/use-context)
        api-client (:api/client ctx)

        ;; Generate column headers A-J (10 columns)
        columns (mapv index->col-letter (range 10))
        ;; Generate row numbers 1-20
        rows (range 1 21)

        handle-select (fn [cell-id]
                        (rf/dispatch [::sheet-events/select-cell cell-id]))

        handle-create (fn [address]
                        (rf/dispatch [::sheet-events/create-cell api-client sheet-id address]))]

    ($ :div {:class "overflow-auto"}
       ($ :table {:class "border-collapse"}
          ;; Column headers
          ($ :thead
             ($ :tr {:class "bg-gray-100"}
                ($ :th {:class "border p-2 w-12"} "")
                (for [col columns]
                  ($ :th {:key col :class "border p-2 min-w-[120px] text-center font-medium text-gray-600"}
                     col))))

          ;; Grid rows
          ($ :tbody
             (for [row rows]
               ($ :tr {:key row}
                  ;; Row header
                  ($ :td {:class "border p-2 bg-gray-100 text-center font-medium text-gray-600"}
                     row)
                  ;; Cells
                  (for [col columns]
                    (let [address (str col row)
                          cell (get cells-by-addr address)]
                      ($ :td {:key address :class "p-0"}
                         (if cell
                           ($ grid-cell {:cell cell
                                         :selected? (= (:id cell) selected-id)
                                         :on-select handle-select})
                           ($ empty-cell {:address address
                                          :on-create handle-create}))))))))))))

;; =============================================================================
;; Field Editor Component
;; =============================================================================

(defui field-editor [{:keys [field-name field-value on-change]}]
  (let [field-type (:type field-value)
        value (:value field-value)]
    ($ :div {:class "space-y-1"}
       ($ label/Label {:class "text-sm font-medium"} field-name)
       (case field-type
         :text ($ input/Input {:value (or value "")
                               :on-change #(on-change field-name
                                                      {:type :text
                                                       :value (.. % -target -value)})})
         :number ($ input/Input {:type "number"
                                 :value (or value "")
                                 :on-change #(on-change field-name
                                                        {:type :number
                                                         :value (js/parseFloat (.. % -target -value))})})
         :yes-no ($ :div {:class "flex items-center gap-2"}
                    ($ input/Input {:type "checkbox"
                                    :checked (boolean value)
                                    :on-change #(on-change field-name
                                                          {:type :yes-no
                                                           :value (.. % -target -checked)})
                                    :class "w-4 h-4"})
                    ($ :span {:class "text-sm"}
                       (if value "Yes" "No")))
         :document ($ textarea/Textarea {:value (or value "")
                                         :rows 4
                                         :on-change #(on-change field-name
                                                                {:type :document
                                                                 :value (.. % -target -value)})})
         ;; Default: text input
         ($ input/Input {:value (str value)
                         :on-change #(on-change field-name
                                                {:type field-type
                                                 :value (.. % -target -value)})})))))

;; =============================================================================
;; Cell Editor Panel
;; =============================================================================

(defui literal-cell-editor [{:keys [cell sheet-id api-client]}]
  (let [[fields set-fields!] (use-state (or (:fields cell) {}))
        [new-field-name set-new-field-name!] (use-state "")
        [new-field-type set-new-field-type!] (use-state :text)

        handle-field-change (fn [name value]
                              (set-fields! (assoc fields name value)))

        handle-add-field (fn []
                           (when (and (seq new-field-name)
                                      (not (contains? fields new-field-name)))
                             (set-fields! (assoc fields new-field-name
                                                 {:type new-field-type :value nil}))
                             (set-new-field-name! "")))

        handle-save (fn []
                      (rf/dispatch [::sheet-events/set-cell-literal
                                    api-client sheet-id (:id cell) fields]))]

    ($ :div {:class "space-y-4"}
       ($ :h3 {:class "text-lg font-semibold"} "Literal Cell")
       ($ :p {:class "text-sm text-gray-500"} (:address cell))

       ($ separator/Separator)

       ;; Existing fields
       (when (seq fields)
         ($ :div {:class "space-y-3"}
            (for [[name value] fields]
              ($ :div {:key name}
                 ($ field-editor {:field-name name
                                  :field-value value
                                  :on-change handle-field-change})))))

       ;; Add new field
       ($ :div {:class "flex gap-2"}
          ($ input/Input {:placeholder "Field name"
                          :value new-field-name
                          :on-change #(set-new-field-name! (.. % -target -value))
                          :class "flex-1"})
          ($ select/Select {:value (name new-field-type)
                            :onValueChange #(set-new-field-type! (keyword %))}
             ($ select/SelectTrigger {:class "w-28"}
                ($ select/SelectValue {:placeholder "Type"}))
             ($ select/SelectContent
                ($ select/SelectItem {:value "text"} "Text")
                ($ select/SelectItem {:value "number"} "Number")
                ($ select/SelectItem {:value "yes-no"} "Yes/No")
                ($ select/SelectItem {:value "document"} "Document")))
          ($ button/Button {:variant "outline" :on-click handle-add-field}
             "Add"))

       ;; Save button
       ($ button/Button {:on-click handle-save :class "w-full"}
          "Save"))))

(defui formula-cell-editor [{:keys [cell sheet-id api-client]}]
  (let [signature (:signature cell)
        bindings (:input-bindings cell)
        available-sources (use-subscribe [::sheet-subs/available-source-cells])
        can-execute? (use-subscribe [::sheet-subs/cell-can-execute? (:id cell)])
        is-executing? (use-subscribe [::sheet-subs/cell-is-executing? (:id cell)])

        handle-execute (fn []
                         (rf/dispatch [::sheet-events/request-execution
                                       api-client sheet-id (:id cell)]))]

    ($ :div {:class "space-y-4"}
       ($ :h3 {:class "text-lg font-semibold"} "Formula Cell")
       ($ :p {:class "text-sm text-gray-500"} (:address cell))

       ($ separator/Separator)

       ;; Instruction
       ($ :div {:class "space-y-1"}
          ($ label/Label "Instruction")
          ($ :p {:class "text-sm bg-gray-50 p-2 rounded"}
             (:instruction signature)))

       ;; Input bindings
       (when (seq (:inputs signature))
         ($ :div {:class "space-y-2"}
            ($ label/Label "Inputs")
            (for [input-def (:inputs signature)]
              (let [input-name (:name input-def)
                    binding (get bindings input-name)]
                ($ :div {:key input-name :class "flex items-center gap-2 text-sm"}
                   ($ :span {:class "w-24 font-medium"} input-name)
                   ($ :span {:class "text-gray-400"} (str "(" (name (:type input-def)) ")"))
                   (if binding
                     ($ :span {:class "text-green-600"}
                        (str "← " (:source-cell-id binding) "." (:source-field-name binding)))
                     ($ :span {:class "text-yellow-600"} "Unbound")))))))

       ;; Outputs
       (when (seq (:outputs signature))
         ($ :div {:class "space-y-2"}
            ($ label/Label "Outputs")
            (for [output-def (:outputs signature)]
              (let [output-name (:name output-def)
                    value (get-in cell [:fields output-name])]
                ($ :div {:key output-name :class "text-sm"}
                   ($ :div {:class "flex items-center gap-2"}
                      ($ :span {:class "font-medium"} output-name)
                      ($ :span {:class "text-gray-400"} (str "(" (name (:type output-def)) ")")))
                   (if value
                     ($ :p {:class "mt-1 bg-gray-50 p-2 rounded truncate"}
                        (str (:value value)))
                     ($ :p {:class "mt-1 text-gray-400 italic"} "(pending)")))))))

       ;; Status
       ($ :div {:class "flex items-center gap-2"}
          ($ label/Label "Status")
          ($ cell-status-badge {:status (:status cell)
                                :execution-status (:execution-status cell)}))

       ;; Error message
       (when (:last-error cell)
         ($ :div {:class "text-sm text-red-600 bg-red-50 p-2 rounded"}
            (:last-error cell)))

       ;; Execute button
       ($ button/Button {:on-click handle-execute
                         :disabled (or (not can-execute?) is-executing?)
                         :class "w-full"}
          (if is-executing?
            ($ :span {:class "flex items-center gap-2"}
               ($ spinner/Spinner {:class "w-4 h-4"})
               "Executing...")
            "Execute")))))

;; =============================================================================
;; Signature Editor (for creating new formula cells)
;; =============================================================================

(defui signature-editor [{:keys [cell sheet-id api-client]}]
  (let [[instruction set-instruction!] (use-state "")
        [inputs set-inputs!] (use-state [])
        [outputs set-outputs!] (use-state [])
        [new-input set-new-input!] (use-state {:name "" :type :text})
        [new-output set-new-output!] (use-state {:name "" :type :text})

        handle-add-input (fn []
                           (when (seq (:name new-input))
                             (set-inputs! (conj inputs new-input))
                             (set-new-input! {:name "" :type :text})))

        handle-add-output (fn []
                            (when (seq (:name new-output))
                              (set-outputs! (conj outputs new-output))
                              (set-new-output! {:name "" :type :text})))

        ;; Include any pending output that hasn't been added yet
        all-outputs (if (seq (:name new-output))
                      (conj outputs new-output)
                      outputs)
        all-inputs (if (seq (:name new-input))
                     (conj inputs new-input)
                     inputs)
        can-save? (and (seq instruction) (seq all-outputs))

        handle-save (fn []
                      (when can-save?
                        (rf/dispatch [::sheet-events/set-cell-signature
                                      api-client sheet-id (:id cell)
                                      {:instruction instruction
                                       :inputs all-inputs
                                       :outputs all-outputs}])))]

    ($ :div {:class "space-y-4"}
       ($ :h3 {:class "text-lg font-semibold"} "Create Formula")
       ($ :p {:class "text-sm text-gray-500"} (:address cell))

       ($ separator/Separator)

       ;; Instruction
       ($ :div {:class "space-y-1"}
          ($ label/Label "Instruction")
          ($ textarea/Textarea {:value instruction
                                :on-change #(set-instruction! (.. % -target -value))
                                :placeholder "Describe what this cell should do..."
                                :rows 3}))

       ;; Inputs
       ($ :div {:class "space-y-2"}
          ($ label/Label "Inputs")
          (when (seq inputs)
            ($ :div {:class "space-y-1"}
               (for [[idx input] (map-indexed vector inputs)]
                 ($ :div {:key idx :class "flex items-center gap-2 text-sm"}
                    ($ :span (:name input))
                    ($ :span {:class "text-gray-400"} (str "(" (name (:type input)) ")"))))))
          ($ :div {:class "flex gap-2"}
             ($ input/Input {:placeholder "Input name"
                             :value (:name new-input)
                             :on-change #(set-new-input! (assoc new-input :name (.. % -target -value)))
                             :class "flex-1"})
             ($ select/Select {:value (name (:type new-input))
                               :onValueChange #(set-new-input! (assoc new-input :type (keyword %)))}
                ($ select/SelectTrigger {:class "w-24"}
                   ($ select/SelectValue))
                ($ select/SelectContent
                   ($ select/SelectItem {:value "text"} "Text")
                   ($ select/SelectItem {:value "number"} "Number")
                   ($ select/SelectItem {:value "document"} "Doc")))
             ($ button/Button {:variant "outline" :size "sm" :on-click handle-add-input}
                "+")))

       ;; Outputs
       ($ :div {:class "space-y-2"}
          ($ label/Label "Outputs")
          (when (seq outputs)
            ($ :div {:class "space-y-1"}
               (for [[idx output] (map-indexed vector outputs)]
                 ($ :div {:key idx :class "flex items-center gap-2 text-sm"}
                    ($ :span (:name output))
                    ($ :span {:class "text-gray-400"} (str "(" (name (:type output)) ")"))))))
          ($ :div {:class "flex gap-2"}
             ($ input/Input {:placeholder "Output name"
                             :value (:name new-output)
                             :on-change #(set-new-output! (assoc new-output :name (.. % -target -value)))
                             :class "flex-1"})
             ($ select/Select {:value (name (:type new-output))
                               :onValueChange #(set-new-output! (assoc new-output :type (keyword %)))}
                ($ select/SelectTrigger {:class "w-24"}
                   ($ select/SelectValue))
                ($ select/SelectContent
                   ($ select/SelectItem {:value "text"} "Text")
                   ($ select/SelectItem {:value "number"} "Number")
                   ($ select/SelectItem {:value "yes-no"} "Yes/No")
                   ($ select/SelectItem {:value "document"} "Doc")))
             ($ button/Button {:variant "outline" :size "sm" :on-click handle-add-output}
                "+")))

       ;; Save button
       ($ button/Button {:on-click handle-save
                         :disabled (not can-save?)
                         :class "w-full"}
          "Create Formula"))))

;; =============================================================================
;; Cell Editor Panel
;; =============================================================================

(defui cell-editor-panel []
  (let [cell (use-subscribe [::sheet-subs/selected-cell])
        sheet (use-subscribe [::sheet-subs/sheet])
        ctx (context/use-context)
        api-client (:api/client ctx)

        [mode set-mode!] (use-state nil) ;; :literal or :formula

        handle-convert-to-literal (fn []
                                    (set-mode! :literal))

        handle-convert-to-formula (fn []
                                    (set-mode! :formula))]

    (when cell
      (let [sheet-id (:id sheet)
            is-formula? (some? (:signature cell))
            effective-mode (or mode (if is-formula? :formula :literal))]

        ($ :div {:class "w-96 border-l bg-white h-full overflow-auto"}
           ($ :div {:class "p-4 space-y-4"}
              ;; Mode toggle
              (when-not (:signature cell)
                ($ :div {:class "flex gap-2"}
                   ($ button/Button {:variant (if (= effective-mode :literal) "default" "outline")
                                     :size "sm"
                                     :on-click handle-convert-to-literal}
                      "Literal")
                   ($ button/Button {:variant (if (= effective-mode :formula) "default" "outline")
                                     :size "sm"
                                     :on-click handle-convert-to-formula}
                      "Formula")))

              ;; Editor based on mode
              (if (or (= effective-mode :formula) is-formula?)
                (if is-formula?
                  ($ formula-cell-editor {:cell cell
                                          :sheet-id sheet-id
                                          :api-client api-client})
                  ;; Show signature editor for new formula cells
                  ($ signature-editor {:cell cell
                                       :sheet-id sheet-id
                                       :api-client api-client}))
                ($ literal-cell-editor {:cell cell
                                        :sheet-id sheet-id
                                        :api-client api-client}))))))))

;; =============================================================================
;; Sheet Toolbar
;; =============================================================================

(defui sheet-toolbar [{:keys [sheet]}]
  (let [ctx (context/use-context)
        navigate! (:router/navigate! ctx)]
    ($ :div {:class "flex items-center justify-between p-4 border-b bg-white"}
       ($ :div {:class "flex items-center gap-4"}
          ($ button/Button {:variant "ghost" :size "sm" :on-click #(navigate! :sheets)}
             "← Back")
          ($ :h1 {:class "text-xl font-semibold"}
             (:name sheet)))
       ($ :div {:class "flex items-center gap-2"}
          ($ button/Button {:variant "outline" :size "sm"}
             "Settings")))))

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
         ($ sheet-toolbar {:sheet sheet})
         ($ :div {:class "flex flex-1 overflow-hidden"}
            ($ :div {:class "flex-1 overflow-auto bg-gray-50 p-4"}
               ($ sheet-grid {:sheet-id sheet-id}))
            ($ cell-editor-panel)))

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
        navigate! (:router/navigate! ctx)

        handle-create (fn []
                        (set-creating! true)
                        (rf/dispatch [::sheet-events/create-sheet
                                      api-client name nil
                                      (fn [_]
                                        (set-creating! false)
                                        (on-close)
                                        ;; Reload sheets list
                                        (rf/dispatch [::sheet-events/load-sheets-list-screen api-client]))]))]
    ($ dialog/Dialog {:open open? :onOpenChange #(when-not creating? (on-close))}
       ($ dialog/DialogContent
          ($ dialog/DialogHeader
             ($ dialog/DialogTitle "Create New Sheet"))
          ($ :div {:class "space-y-4 py-4"}
             ($ :div {:class "space-y-2"}
                ($ label/Label "Name")
                ($ input/Input {:value name
                                :on-change #(set-name! (.. % -target -value))
                                :placeholder "My Agent Sheet"
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
          ($ :h1 {:class "text-2xl font-bold"} "Agent Sheets")
          ($ button/Button {:on-click #(set-dialog-open! true)}
             "+ New Sheet"))

       (if loading?
         ($ :div {:class "flex items-center justify-center py-12"}
            ($ spinner/Spinner {:class "w-8 h-8"}))
         (if (empty? sheets)
           ($ :div {:class "text-center py-12"}
              ($ :p {:class "text-gray-500 mb-4"} "No sheets yet")
              ($ button/Button {:on-click #(set-dialog-open! true)}
                 "Create your first sheet"))
           ($ :div {:class "grid gap-4 md:grid-cols-2 lg:grid-cols-3"}
              (for [sheet sheets]
                ($ card/Card {:key (:id sheet)
                              :class "cursor-pointer hover:shadow-md transition-shadow"
                              :on-click #(navigate! :sheet {:sheet-id (str (:id sheet))})}
                   ($ card/CardHeader
                      ($ card/CardTitle (:name sheet))
                      (when (:description sheet)
                        ($ card/CardDescription (:description sheet))))
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
