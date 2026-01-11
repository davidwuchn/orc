(ns ai.obney.workshop.sheet-service.core.commands
  "Sheet Service command handlers.

   All commands follow the Grain pattern:
   - Take context including event-store and command data
   - Return {:command-result/events [...]} on success
   - Return cognitect anomaly on failure
   - Last write wins (no optimistic concurrency)"
  (:require [ai.obney.workshop.sheet-service.core.read-models :as rm]
            [ai.obney.workshop.sheet-service.core.dependency-graph :as dg]
            [ai.obney.grain.event-store-v2.interface :refer [->event]]
            [ai.obney.grain.command-processor.interface :refer [defcommand]]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn valid-signature?
  "Check if a signature is valid (has instruction and at least one output)."
  [signature]
  (and (string? (:instruction signature))
       (seq (:instruction signature))
       (seq (:outputs signature))))

(defn input-defined?
  "Check if an input name is defined in a signature."
  [signature input-name]
  (some #(= input-name (:name %)) (:inputs signature)))

(defn resolve-inputs
  "Resolve input values from bound source cells.
   Returns a map of input-name -> field-value."
  [event-store sheet-id cell]
  (reduce-kv
   (fn [acc input-name binding]
     (let [source-cell (rm/get-cell event-store sheet-id (:source-cell-id binding))
           field-value (get-in source-cell [:fields (:source-field-name binding)])]
       (assoc acc input-name (or field-value {:type :text :value nil}))))
   {}
   (:input-bindings cell)))

;; =============================================================================
;; Sheet Commands
;; =============================================================================

(defcommand :sheet create-sheet
  "Create a new sheet."
  [{{:keys [name description]} :command
    :keys [event-store]}]
  (let [sheet-id (random-uuid)]
    {:command-result/events
     [(->event
       {:type :sheet/sheet-created
        :tags #{[:sheet sheet-id]}
        :body (cond-> {:sheet-id sheet-id
                       :name name}
                description (assoc :description description))})]}))

(defcommand :sheet rename-sheet
  "Rename an existing sheet."
  [{{:keys [sheet-id name]} :command
    :keys [event-store]}]
  (let [sheet (rm/get-sheet event-store sheet-id)]
    (if-not sheet
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}
      {:command-result/events
       [(->event
         {:type :sheet/sheet-renamed
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :old-name (:name sheet)
                 :new-name name}})]})))

(defcommand :sheet delete-sheet
  "Delete a sheet and all its cells."
  [{{:keys [sheet-id]} :command
    :keys [event-store]}]
  (let [sheet (rm/get-sheet event-store sheet-id)]
    (if-not sheet
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}
      {:command-result/events
       [(->event
         {:type :sheet/sheet-deleted
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id}})]})))

;; =============================================================================
;; Cell Commands
;; =============================================================================

(defcommand :sheet create-cell
  "Create a new empty cell in a sheet."
  [{{:keys [sheet-id address cell-id]} :command
    :keys [event-store]}]
  (let [sheet (rm/get-sheet event-store sheet-id)
        existing-cells (rm/get-cells-for-sheet event-store sheet-id)
        address-taken? (some #(= address (:address %)) existing-cells)]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      address-taken?
      {::anom/category ::anom/conflict
       ::anom/message (str "Cell address " address " is already taken")}

      :else
      (let [new-cell-id (or cell-id (random-uuid))]
        {:command-result/events
         [(->event
           {:type :sheet/cell-created
            :tags #{[:sheet sheet-id]
                    [:cell new-cell-id]}
            :body {:sheet-id sheet-id
                   :cell-id new-cell-id
                   :address address}})]}))))

(defcommand :sheet set-cell-literal
  "Set literal field values on a cell (clears any signature)."
  [{{:keys [sheet-id cell-id fields]} :command
    :keys [event-store]}]
  (let [cell (rm/get-cell event-store sheet-id cell-id)]
    (cond
      (not cell)
      {::anom/category ::anom/not-found
       ::anom/message "Cell not found"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/cell-literal-set
          :tags #{[:sheet sheet-id]
                  [:cell cell-id]
                  [:cell-data cell-id]}
          :body (cond-> {:sheet-id sheet-id
                         :cell-id cell-id
                         :fields fields}
                  (seq (:fields cell)) (assoc :previous-fields (:fields cell)))})]}))

(defcommand :sheet set-cell-signature
  "Define a formula signature for a cell (makes it a formula cell)."
  [{{:keys [sheet-id cell-id signature]} :command
    :keys [event-store]}]
  (let [cell (rm/get-cell event-store sheet-id cell-id)]
    (cond
      (not cell)
      {::anom/category ::anom/not-found
       ::anom/message "Cell not found"}

      (not (valid-signature? signature))
      {::anom/category ::anom/incorrect
       ::anom/message "Invalid signature: must have instruction and at least one output"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/cell-signature-defined
          :tags #{[:sheet sheet-id]
                  [:cell cell-id]}
          :body (cond-> {:sheet-id sheet-id
                         :cell-id cell-id
                         :signature signature}
                  (:signature cell) (assoc :previous-signature (:signature cell)))})]}))))

(defcommand :sheet bind-input
  "Bind an input in a formula cell to a field from another cell."
  [{{:keys [sheet-id cell-id input-name source-cell-id source-field-name]} :command
    :keys [event-store]}]
  (let [cell (rm/get-cell event-store sheet-id cell-id)
        source-cell (rm/get-cell event-store sheet-id source-cell-id)
        dep-graph (rm/get-dependency-graph-for-sheet event-store sheet-id)]
    (cond
      (not cell)
      {::anom/category ::anom/not-found
       ::anom/message "Target cell not found"}

      (not (:signature cell))
      {::anom/category ::anom/incorrect
       ::anom/message "Cannot bind input on a literal cell - cell must have a signature"}

      (not (input-defined? (:signature cell) input-name))
      {::anom/category ::anom/incorrect
       ::anom/message (str "Input '" input-name "' not defined in cell signature")}

      (not source-cell)
      {::anom/category ::anom/not-found
       ::anom/message "Source cell not found"}

      ;; Cycle detection - check if adding this binding would create a cycle
      (dg/would-create-cycle? dep-graph source-cell-id cell-id)
      {::anom/category ::anom/conflict
       ::anom/message "Binding would create a dependency cycle"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/input-bound
          :tags #{[:sheet sheet-id]
                  [:cell cell-id]
                  [:cell-dependency cell-id]}
          :body {:sheet-id sheet-id
                 :cell-id cell-id
                 :input-name input-name
                 :source-cell-id source-cell-id
                 :source-field-name source-field-name}})]})))

(defcommand :sheet unbind-input
  "Remove an input binding from a formula cell."
  [{{:keys [sheet-id cell-id input-name]} :command
    :keys [event-store]}]
  (let [cell (rm/get-cell event-store sheet-id cell-id)]
    (cond
      (not cell)
      {::anom/category ::anom/not-found
       ::anom/message "Cell not found"}

      :else
      (let [previous-binding (get-in cell [:input-bindings input-name])]
        {:command-result/events
         [(->event
           {:type :sheet/input-unbound
            :tags #{[:sheet sheet-id]
                    [:cell cell-id]
                    [:cell-dependency cell-id]}
            :body (cond-> {:sheet-id sheet-id
                           :cell-id cell-id
                           :input-name input-name}
                    previous-binding (assoc :previous-binding previous-binding))})]}))))

(defcommand :sheet delete-cell
  "Delete a cell from a sheet."
  [{{:keys [sheet-id cell-id]} :command
    :keys [event-store]}]
  (let [cell (rm/get-cell event-store sheet-id cell-id)
        dependents (rm/get-dependent-cells event-store sheet-id cell-id)]
    (cond
      (not cell)
      {::anom/category ::anom/not-found
       ::anom/message "Cell not found"}

      (seq dependents)
      {::anom/category ::anom/conflict
       ::anom/message (str "Cannot delete: cell has " (count dependents) " dependent cells. Remove their bindings first.")}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/cell-deleted
          :tags #{[:sheet sheet-id]
                  [:cell cell-id]}
          :body {:sheet-id sheet-id
                 :cell-id cell-id}})]})))

;; =============================================================================
;; Execution Commands
;; =============================================================================

(defcommand :sheet request-cell-execution
  "Request execution of a formula cell."
  [{{:keys [sheet-id cell-id execution-id]} :command
    :keys [event-store]}]
  (let [cell (rm/get-cell event-store sheet-id cell-id)]
    (cond
      (not cell)
      {::anom/category ::anom/not-found
       ::anom/message "Cell not found"}

      (not (:signature cell))
      {::anom/category ::anom/incorrect
       ::anom/message "Cannot execute a literal cell - cell must have a signature"}

      (not (dg/all-inputs-bound? cell))
      {::anom/category ::anom/incorrect
       ::anom/message "Not all inputs are bound"}

      :else
      (let [exec-id (or execution-id (random-uuid))
            inputs (resolve-inputs event-store sheet-id cell)]
        {:command-result/events
         [(->event
           {:type :sheet/cell-execution-requested
            :tags #{[:sheet sheet-id]
                    [:cell cell-id]
                    [:execution exec-id]}
            :body {:sheet-id sheet-id
                   :cell-id cell-id
                   :execution-id exec-id
                   :inputs inputs
                   :signature (:signature cell)}})]}))))

(defcommand :sheet complete-cell-execution
  "Mark a cell execution as completed with outputs (internal command)."
  [{{:keys [sheet-id cell-id execution-id outputs duration-ms]} :command
    :keys [event-store]}]
  {:command-result/events
   [(->event
     {:type :sheet/cell-execution-completed
      :tags #{[:sheet sheet-id]
              [:cell cell-id]
              [:execution execution-id]
              [:cell-data cell-id]}
      :body {:sheet-id sheet-id
             :cell-id cell-id
             :execution-id execution-id
             :outputs outputs
             :duration-ms duration-ms}})]})

(defcommand :sheet fail-cell-execution
  "Mark a cell execution as failed (internal command)."
  [{{:keys [sheet-id cell-id execution-id error duration-ms]} :command
    :keys [event-store]}]
  {:command-result/events
   [(->event
     {:type :sheet/cell-execution-failed
      :tags #{[:sheet sheet-id]
              [:cell cell-id]
              [:execution execution-id]}
      :body {:sheet-id sheet-id
             :cell-id cell-id
             :execution-id execution-id
             :error error
             :duration-ms duration-ms}})]})

(defcommand :sheet cancel-cell-execution
  "Cancel a running cell execution."
  [{{:keys [sheet-id execution-id]} :command
    :keys [event-store]}]
  (let [execution (rm/get-execution event-store execution-id)]
    (cond
      (not execution)
      {::anom/category ::anom/not-found
       ::anom/message "Execution not found"}

      (not= :running (:status execution))
      {::anom/category ::anom/conflict
       ::anom/message "Execution is not running"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/cell-execution-cancelled
          :tags #{[:sheet sheet-id]
                  [:execution execution-id]}
          :body {:sheet-id sheet-id
                 :execution-id execution-id}})]})))
