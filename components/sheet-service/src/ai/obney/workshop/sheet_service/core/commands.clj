(ns ai.obney.workshop.sheet-service.core.commands
  "Behavior Tree Sheet command handlers.

   All commands follow the Grain pattern:
   - Take context including event-store and command data
   - Return {:command-result/events [...]} on success
   - Return cognitect anomaly on failure
   - Last write wins (no optimistic concurrency)"
  (:require [ai.obney.workshop.sheet-service.core.read-models :as rm]
            [ai.obney.workshop.sheet-service.core.runtime :as runtime]
            [ai.obney.grain.event-store-v2.interface :refer [->event]]
            [ai.obney.grain.command-processor.interface :refer [defcommand]]
            [cognitect.anomalies :as anom]
            [malli.core :as m]
            [malli.error :as me]))

;; =============================================================================
;; Sheet Commands
;; =============================================================================

(defcommand :sheet create-sheet
  "Create a new sheet."
  [{{:keys [name]} :command
    :keys [event-store]}]
  (let [sheet-id (random-uuid)]
    {:command-result/events
     [(->event
       {:type :sheet/sheet-created
        :tags #{[:sheet sheet-id]}
        :body {:sheet-id sheet-id
               :name name}})]}))

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
  "Delete a sheet and all its nodes."
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
;; Node Commands
;; =============================================================================

(defcommand :sheet create-node
  "Create a new node in a sheet.
   If parent-id is nil, creates a root node.
   Index specifies position among siblings (default 0)."
  [{{:keys [sheet-id node-id type parent-id index]} :command
    :keys [event-store]}]
  (let [sheet (rm/get-sheet event-store sheet-id)
        has-root? (some? (:root-node-id sheet))
        parent (when parent-id (rm/get-node event-store sheet-id parent-id))]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      ;; If no parent, this would be a root node - check if one exists
      (and (nil? parent-id) has-root?)
      {::anom/category ::anom/conflict
       ::anom/message "Sheet already has a root node"}

      ;; If parent specified, it must exist
      (and parent-id (not parent))
      {::anom/category ::anom/not-found
       ::anom/message "Parent node not found"}

      :else
      (let [new-node-id (or node-id (random-uuid))]
        {:command-result/events
         [(->event
           {:type :sheet/node-created
            :tags #{[:sheet sheet-id]
                    [:node new-node-id]}
            :body (cond-> {:sheet-id sheet-id
                           :node-id new-node-id
                           :type type}
                    parent-id (assoc :parent-id parent-id)
                    index (assoc :index index))})]}))))

(defcommand :sheet move-node
  "Move a node to a new parent at specified index."
  [{{:keys [sheet-id node-id new-parent-id index]} :command
    :keys [event-store]}]
  (let [node (rm/get-node event-store sheet-id node-id)
        new-parent (rm/get-node event-store sheet-id new-parent-id)
        nodes-by-id (rm/get-nodes-by-id event-store sheet-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not new-parent)
      {::anom/category ::anom/not-found
       ::anom/message "New parent node not found"}

      ;; Check for cycles - can't move node under its own descendant
      (rm/is-descendant? nodes-by-id node-id new-parent-id)
      {::anom/category ::anom/conflict
       ::anom/message "Cannot move node under its own descendant"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-moved
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :new-parent-id new-parent-id
                         :index index}
                  (:parent-id node) (assoc :old-parent-id (:parent-id node)))})]})))

(defcommand :sheet reorder-node
  "Reorder a node among its siblings."
  [{{:keys [sheet-id node-id index]} :command
    :keys [event-store]}]
  (let [node (rm/get-node event-store sheet-id node-id)
        parent-id (:parent-id node)
        parent (when parent-id (rm/get-node event-store sheet-id parent-id))
        current-children (when parent (:children-ids parent))
        current-index (when current-children
                        (.indexOf (vec current-children) node-id))]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (nil? parent-id)
      {::anom/category ::anom/incorrect
       ::anom/message "Cannot reorder root node"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-reordered
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body {:sheet-id sheet-id
                 :node-id node-id
                 :old-index (or current-index 0)
                 :new-index index}})]})))

(defcommand :sheet delete-node
  "Delete a node from a sheet.
   Node must have no children (delete children first)."
  [{{:keys [sheet-id node-id]} :command
    :keys [event-store]}]
  (let [node (rm/get-node event-store sheet-id node-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (seq (:children-ids node))
      {::anom/category ::anom/conflict
       ::anom/message "Cannot delete node with children. Delete children first."}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-deleted
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body {:sheet-id sheet-id
                 :node-id node-id}})]})))

(defcommand :sheet set-node-name
  "Set the name of a node."
  [{{:keys [sheet-id node-id name]} :command
    :keys [event-store]}]
  (let [node (rm/get-node event-store sheet-id node-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-name-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :name name}
                  (:name node) (assoc :previous-name (:name node)))})]})))

(defcommand :sheet set-node-instruction
  "Set the instruction for a leaf node."
  [{{:keys [sheet-id node-id instruction]} :command
    :keys [event-store]}]
  (let [node (rm/get-node event-store sheet-id node-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :leaf (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only leaf nodes can have instructions"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-instruction-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :instruction instruction}
                  (:instruction node) (assoc :previous-instruction (:instruction node)))})]})))

(defcommand :sheet set-node-io
  "Set the reads/writes (blackboard keys) for a leaf node."
  [{{:keys [sheet-id node-id reads writes]} :command
    :keys [event-store]}]
  (let [node (rm/get-node event-store sheet-id node-id)
        blackboard (rm/get-blackboard-by-key event-store sheet-id)
        all-keys (set (keys blackboard))
        unknown-reads (remove all-keys reads)
        unknown-writes (remove all-keys writes)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :leaf (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only leaf nodes can have reads/writes"}

      (seq unknown-reads)
      {::anom/category ::anom/incorrect
       ::anom/message (str "Unknown blackboard keys in reads: " (vec unknown-reads))}

      (seq unknown-writes)
      {::anom/category ::anom/incorrect
       ::anom/message (str "Unknown blackboard keys in writes: " (vec unknown-writes))}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-io-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :reads (vec reads)
                         :writes (vec writes)}
                  (seq (:reads node)) (assoc :previous-reads (:reads node))
                  (seq (:writes node)) (assoc :previous-writes (:writes node)))})]})))

(defcommand :sheet set-node-decorators
  "Set decorators for a node."
  [{{:keys [sheet-id node-id decorators]} :command
    :keys [event-store]}]
  (let [node (rm/get-node event-store sheet-id node-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-decorators-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :decorators (vec decorators)}
                  (seq (:decorators node)) (assoc :previous-decorators (:decorators node)))})]})))

(defcommand :sheet set-node-check
  "Set the condition check for a condition node."
  [{{:keys [sheet-id node-id check]} :command
    :keys [event-store]}]
  (let [node (rm/get-node event-store sheet-id node-id)
        blackboard (rm/get-blackboard-by-key event-store sheet-id)
        check-key (:key check)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :condition (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only condition nodes can have checks"}

      (not (contains? blackboard check-key))
      {::anom/category ::anom/not-found
       ::anom/message (str "Blackboard key '" check-key "' not declared")}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-check-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :check check}
                  (:check node) (assoc :previous-check (:check node)))})]})))

(defcommand :sheet set-node-executor
  "Set the executor configuration for a leaf node.
   - :ai executor uses DSCloj with optional model selection
   - :code executor runs a Clojure function
   - :tool executor directly invokes a tool"
  [{{:keys [sheet-id node-id executor model fn tools]} :command
    :keys [event-store]}]
  (let [node (rm/get-node event-store sheet-id node-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :leaf (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only leaf nodes can have executors"}

      (and (= executor :code) (not fn))
      {::anom/category ::anom/incorrect
       ::anom/message "Code executor requires :fn (fully-qualified function symbol)"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-executor-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :executor executor}
                  model (assoc :model model)
                  fn (assoc :fn fn)
                  tools (assoc :tools (vec tools))
                  (:executor node) (assoc :previous-executor (:executor node))
                  (:model node) (assoc :previous-model (:model node))
                  (:fn node) (assoc :previous-fn (:fn node))
                  (:tools node) (assoc :previous-tools (:tools node)))})]})))

(defcommand :sheet set-node-retry
  "Set retry configuration for a leaf node."
  [{{:keys [sheet-id node-id retry]} :command
    :keys [event-store]}]
  (let [node (rm/get-node event-store sheet-id node-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :leaf (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only leaf nodes can have retry configuration"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-retry-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :retry retry}
                  (:retry node) (assoc :previous-retry (:retry node)))})]})))

(defcommand :sheet set-parallel-config
  "Set configuration for a parallel node."
  [{{:keys [sheet-id node-id success-policy failure-policy]} :command
    :keys [event-store]}]
  (let [node (rm/get-node event-store sheet-id node-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :parallel (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only parallel nodes can have parallel configuration"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/parallel-config-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id}
                  success-policy (assoc :success-policy success-policy)
                  failure-policy (assoc :failure-policy failure-policy)
                  (:success-policy node) (assoc :previous-success-policy (:success-policy node))
                  (:failure-policy node) (assoc :previous-failure-policy (:failure-policy node)))})]})))

(defcommand :sheet set-map-each-config
  "Set configuration for a map-each node."
  [{{:keys [sheet-id node-id source-key item-key output-key max-concurrency]} :command
    :keys [event-store]}]
  (let [node (rm/get-node event-store sheet-id node-id)
        blackboard (rm/get-blackboard-by-key event-store sheet-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :map-each (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only map-each nodes can have map-each configuration"}

      (not (contains? blackboard source-key))
      {::anom/category ::anom/not-found
       ::anom/message (str "Source key '" source-key "' not declared in blackboard")}

      (not (contains? blackboard item-key))
      {::anom/category ::anom/not-found
       ::anom/message (str "Item key '" item-key "' not declared in blackboard")}

      (not (contains? blackboard output-key))
      {::anom/category ::anom/not-found
       ::anom/message (str "Output key '" output-key "' not declared in blackboard")}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/map-each-config-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :source-key source-key
                         :item-key item-key
                         :output-key output-key}
                  max-concurrency (assoc :max-concurrency max-concurrency)
                  (:source-key node) (assoc :previous-source-key (:source-key node))
                  (:item-key node) (assoc :previous-item-key (:item-key node))
                  (:output-key node) (assoc :previous-output-key (:output-key node))
                  (:max-concurrency node) (assoc :previous-max-concurrency (:max-concurrency node)))})]})))

(defcommand :sheet set-llm-condition-config
  "Set configuration for an llm-condition node."
  [{{:keys [sheet-id node-id instruction reads model]} :command
    :keys [event-store]}]
  (let [node (rm/get-node event-store sheet-id node-id)
        blackboard (rm/get-blackboard-by-key event-store sheet-id)
        all-keys (set (keys blackboard))
        unknown-reads (remove all-keys reads)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :llm-condition (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only llm-condition nodes can have llm-condition configuration"}

      (seq unknown-reads)
      {::anom/category ::anom/incorrect
       ::anom/message (str "Unknown blackboard keys in reads: " (vec unknown-reads))}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/llm-condition-config-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :instruction instruction
                         :reads (vec reads)}
                  model (assoc :model model)
                  (:instruction node) (assoc :previous-instruction (:instruction node))
                  (seq (:reads node)) (assoc :previous-reads (:reads node))
                  (:model node) (assoc :previous-model (:model node)))})]})))

;; =============================================================================
;; Blackboard Commands
;; =============================================================================

(defn- valid-malli-schema?
  "Check if a value is a valid Malli schema."
  [schema]
  (try
    (m/schema schema)
    true
    (catch Exception _
      false)))

(defn- validate-against-schema
  "Validate a value against a Malli schema.
   Returns nil if valid, or an error map if invalid."
  [schema value]
  (try
    (let [malli-schema (m/schema schema)]
      (if (m/validate malli-schema value)
        nil
        {:error :validation-failed
         :message (me/humanize (m/explain malli-schema value))}))
    (catch Exception e
      {:error :invalid-schema
       :message (.getMessage e)})))

(defcommand :sheet declare-key
  "Declare a new key in the blackboard with a Malli schema."
  [{{:keys [sheet-id key schema]} :command
    :keys [event-store]}]
  (let [sheet (rm/get-sheet event-store sheet-id)
        blackboard (rm/get-blackboard-by-key event-store sheet-id)]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      (contains? blackboard key)
      {::anom/category ::anom/conflict
       ::anom/message (str "Key '" key "' already declared")}

      (not (valid-malli-schema? schema))
      {::anom/category ::anom/incorrect
       ::anom/message "Invalid Malli schema"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/key-declared
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :key key
                 :schema schema}})]})))

(defcommand :sheet update-key-schema
  "Update the schema for an existing blackboard key."
  [{{:keys [sheet-id key schema]} :command
    :keys [event-store]}]
  (let [blackboard (rm/get-blackboard-by-key event-store sheet-id)
        entry (get blackboard key)]
    (cond
      (not entry)
      {::anom/category ::anom/not-found
       ::anom/message (str "Key '" key "' not declared")}

      (not (valid-malli-schema? schema))
      {::anom/category ::anom/incorrect
       ::anom/message "Invalid Malli schema"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/key-schema-updated
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :key key
                 :schema schema
                 :previous-schema (:schema entry)}})]})))

(defcommand :sheet set-key-value
  "Set a value for a blackboard key. Value is validated against the key's Malli schema."
  [{{:keys [sheet-id key value]} :command
    :keys [event-store]}]
  (let [blackboard (rm/get-blackboard-by-key event-store sheet-id)
        entry (get blackboard key)
        schema (:schema entry)
        validation-error (when schema (validate-against-schema schema value))]
    (cond
      (not entry)
      {::anom/category ::anom/not-found
       ::anom/message (str "Key '" key "' not declared")}

      validation-error
      {::anom/category ::anom/incorrect
       ::anom/message (str "Value doesn't match schema: " (:message validation-error))}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/key-value-set
          :tags #{[:sheet sheet-id]}
          :body (cond-> {:sheet-id sheet-id
                         :key key
                         :value value
                         :version (inc (:version entry))}
                  (some? (:value entry)) (assoc :previous-value (:value entry)))})]})))

(defcommand :sheet delete-key
  "Delete a key from the blackboard."
  [{{:keys [sheet-id key]} :command
    :keys [event-store]}]
  (let [blackboard (rm/get-blackboard-by-key event-store sheet-id)
        entry (get blackboard key)
        ;; Check if any nodes reference this key
        nodes (rm/get-nodes-for-sheet event-store sheet-id)
        nodes-using-key (filter (fn [node]
                                  (or (some #{key} (:reads node))
                                      (some #{key} (:writes node))))
                                nodes)]
    (cond
      (not entry)
      {::anom/category ::anom/not-found
       ::anom/message (str "Key '" key "' not declared")}

      (seq nodes-using-key)
      {::anom/category ::anom/conflict
       ::anom/message (str "Cannot delete key '" key "': still in use by " (count nodes-using-key) " node(s)")}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/key-deleted
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :key key}})]})))

;; =============================================================================
;; Execution Commands
;; =============================================================================

(defcommand :sheet tick-tree
  "Start a tree tick (execute from root)."
  [{{:keys [sheet-id tick-id]} :command
    :keys [event-store]}]
  (let [sheet (rm/get-sheet event-store sheet-id)
        root-node-id (:root-node-id sheet)]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      (not root-node-id)
      {::anom/category ::anom/incorrect
       ::anom/message "Sheet has no root node"}

      :else
      (let [new-tick-id (or tick-id (random-uuid))]
        {:command-result/events
         [(->event
           {:type :sheet/tree-tick-started
            :tags #{[:sheet sheet-id]
                    [:tick new-tick-id]}
            :body {:sheet-id sheet-id
                   :tick-id new-tick-id}})]}))))

(defcommand :sheet tick-node
  "Start a single node tick (for testing or manual execution)."
  [{{:keys [sheet-id node-id tick-id overrides]} :command
    :keys [event-store]}]
  (let [node (rm/get-node event-store sheet-id node-id)
        blackboard (rm/get-blackboard-by-key event-store sheet-id)
        ;; Get input values from blackboard (for reads)
        inputs (reduce (fn [acc k]
                         (if-let [entry (get blackboard k)]
                           (assoc acc k (:value entry))
                           acc))
                       {}
                       (:reads node))
        ;; Apply overrides
        inputs-with-overrides (merge inputs (or overrides {}))]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :leaf (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only leaf nodes can be ticked directly"}

      (not (:instruction node))
      {::anom/category ::anom/incorrect
       ::anom/message "Node has no instruction"}

      :else
      (let [new-tick-id (or tick-id (random-uuid))]
        {:command-result/events
         [(->event
           {:type :sheet/node-execution-started
            :tags #{[:sheet sheet-id]
                    [:node node-id]
                    [:tick new-tick-id]}
            :body {:sheet-id sheet-id
                   :tick-id new-tick-id
                   :node-id node-id
                   :inputs inputs-with-overrides}})]}))))

(defcommand :sheet complete-node-execution
  "Complete a node execution (internal command from todo processor)."
  [{{:keys [sheet-id tick-id node-id status writes duration-ms error]} :command
    :keys [event-store]}]
  {:command-result/events
   [(->event
     {:type :sheet/node-execution-completed
      :tags #{[:sheet sheet-id]
              [:node node-id]
              [:tick tick-id]}
      :body (cond-> {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :status status}
              (seq writes) (assoc :writes writes)
              duration-ms (assoc :duration-ms duration-ms)
              error (assoc :error error))})]})

(defcommand :sheet fail-node-execution
  "Mark a node execution as failed (internal command from todo processor)."
  [{{:keys [sheet-id tick-id node-id error duration-ms]} :command
    :keys [event-store]}]
  {:command-result/events
   [(->event
     {:type :sheet/node-execution-completed
      :tags #{[:sheet sheet-id]
              [:node node-id]
              [:tick tick-id]}
      :body (cond-> {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :status :failure}
              error (assoc :error error)
              duration-ms (assoc :duration-ms duration-ms))})]})

(defcommand :sheet cancel-tick
  "Cancel a running tick. Prevents further re-ticks."
  [{{:keys [sheet-id tick-id]} :command
    :keys [event-store]}]
  {:command-result/events
   [(->event
     {:type :sheet/tick-cancelled
      :tags #{[:sheet sheet-id]
              [:tick tick-id]}
      :body {:sheet-id sheet-id
             :tick-id tick-id}})]})

;; =============================================================================
;; Versioning Commands
;; =============================================================================

(defn- build-node-tree
  "Convert flat nodes map to nested tree structure for snapshots.
   Preserves original node IDs for accurate structural diffing."
  [nodes-by-id root-id]
  (when root-id
    (let [node (get nodes-by-id root-id)]
      (when node
        (cond-> {:id (:id node)
                 :type (:type node)
                 :name (:name node)}
          ;; Leaf-specific fields
          (= :leaf (:type node))
          (merge (cond-> {}
                   (:executor node) (assoc :executor (:executor node))
                   (:model node) (assoc :model (:model node))
                   (:fn node) (assoc :fn (:fn node))
                   (:instruction node) (assoc :instruction (:instruction node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (seq (:writes node)) (assoc :writes (:writes node))
                   (:tools node) (assoc :tools (:tools node))
                   (:retry node) (assoc :retry (:retry node))))
          ;; Condition-specific
          (= :condition (:type node))
          (merge (cond-> {}
                   (:check node) (assoc :check (:check node))))
          ;; LLM-Condition-specific
          (= :llm-condition (:type node))
          (merge (cond-> {}
                   (:instruction node) (assoc :instruction (:instruction node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (:model node) (assoc :model (:model node))))
          ;; Parallel-specific
          (= :parallel (:type node))
          (merge {:success-policy (or (:success-policy node) :all)
                  :failure-policy (or (:failure-policy node) :any)})
          ;; Map-each-specific
          (= :map-each (:type node))
          (merge (cond-> {}
                   (:source-key node) (assoc :source-key (:source-key node))
                   (:item-key node) (assoc :item-key (:item-key node))
                   (:output-key node) (assoc :output-key (:output-key node))
                   (:max-concurrency node) (assoc :max-concurrency (:max-concurrency node))))
          ;; Children for composite nodes
          (seq (:children-ids node))
          (assoc :children
                 (vec (keep #(build-node-tree nodes-by-id %) (:children-ids node)))))))))

(defn- create-snapshot
  "Create a snapshot of the current sheet state."
  [event-store sheet-id]
  (let [sheet (rm/get-sheet event-store sheet-id)
        nodes-by-id (rm/get-nodes-by-id event-store sheet-id)
        blackboard (rm/get-blackboard-for-sheet event-store sheet-id)]
    {:sheet {:name (:name sheet) :id sheet-id}
     :blackboard-schema (into {}
                              (map (fn [bb] [(keyword (:key bb)) (:schema bb)])
                                   blackboard))
     :nodes (build-node-tree nodes-by-id (:root-node-id sheet))}))

(defcommand :sheet publish-version
  "Create a new published version from the current draft state.
   Captures all nodes and blackboard schema as an immutable snapshot."
  [{{:keys [sheet-id description]} :command
    :keys [event-store]}]
  (let [sheet (rm/get-sheet event-store sheet-id)]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      (not (:root-node-id sheet))
      {::anom/category ::anom/incorrect
       ::anom/message "Cannot publish sheet without root node"}

      :else
      (let [snapshot (create-snapshot event-store sheet-id)
            current-version (or (:published-version sheet) 0)
            new-version (inc current-version)
            snapshot-id (random-uuid)]
        {:command-result/events
         [(->event
           {:type :sheet/version-published
            :tags #{[:sheet sheet-id]
                    [:version snapshot-id]}
            :body (cond-> {:sheet-id sheet-id
                           :snapshot-id snapshot-id
                           :version-number new-version
                           :snapshot snapshot}
                    description (assoc :description description))})]}))))

(defcommand :sheet revert-to-version
  "Discard current draft and restore state from a published version.
   If the draft has changes, it will be stashed first for recovery."
  [{{:keys [sheet-id version-number]} :command
    :keys [event-store]}]
  (let [sheet (rm/get-sheet event-store sheet-id)
        version (rm/get-version event-store sheet-id version-number)]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      (not version)
      {::anom/category ::anom/not-found
       ::anom/message (str "Version " version-number " not found")}

      :else
      (let [;; If draft is dirty, stash it first
            draft-dirty? (:draft-dirty? sheet)
            stash-events (when draft-dirty?
                           (let [snapshot (create-snapshot event-store sheet-id)
                                 stash-id (random-uuid)]
                             [(->event
                               {:type :sheet/draft-stashed
                                :tags #{[:sheet sheet-id]}
                                :body {:sheet-id sheet-id
                                       :stash-id stash-id
                                       :snapshot snapshot}})]))
            revert-event (->event
                          {:type :sheet/draft-reverted
                           :tags #{[:sheet sheet-id]}
                           :body {:sheet-id sheet-id
                                  :target-version version-number
                                  :snapshot-id (:snapshot-id version)
                                  :snapshot (:snapshot version)}})]
        {:command-result/events
         (if stash-events
           (conj (vec stash-events) revert-event)
           [revert-event])}))))

(defcommand :sheet restore-stash
  "Restore the stashed draft. The stash is consumed after restoration."
  [{{:keys [sheet-id]} :command
    :keys [event-store]}]
  (let [sheet (rm/get-sheet event-store sheet-id)
        stash (rm/get-stash event-store sheet-id)]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      (not stash)
      {::anom/category ::anom/not-found
       ::anom/message "No stash found"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/stash-restored
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :stash-id (:stash-id stash)
                 :snapshot (:snapshot stash)}})]})))

(defcommand :sheet set-execution-mode
  "Toggle between executing draft or published version."
  [{{:keys [sheet-id mode]} :command
    :keys [event-store]}]
  (let [sheet (rm/get-sheet event-store sheet-id)]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      (and (= mode :published) (nil? (:published-version sheet)))
      {::anom/category ::anom/incorrect
       ::anom/message "No published version exists. Publish first."}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/execution-mode-set
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :mode mode
                 :previous-mode (or (:execution-mode sheet) :draft)}})]})))

;; =============================================================================
;; Execution Commands
;; =============================================================================

(defcommand :sheet execute-version
  "Execute a specific published version of a sheet.

   This command executes a sheet workflow using a specific version snapshot,
   not the current draft state. Useful for comparing behavior across versions.

   Traces are always stored and the trace-id is returned in the result."
  [{{:keys [sheet-id version-number inputs]} :command
    :keys [event-store] :as context}]
  (let [sheet (rm/get-sheet event-store sheet-id)
        version (when sheet (rm/get-version event-store sheet-id version-number))]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      (not version)
      {::anom/category ::anom/not-found
       ::anom/message (str "Version " version-number " not found")}

      :else
      (let [result (runtime/execute context sheet-id (or inputs {})
                                    :use-version version-number)]
        {:command-result/data result}))))

(defcommand :sheet batch-execute
  "Execute a sheet with multiple input sets in parallel.

   This command runs the same sheet multiple times with different inputs,
   executing them concurrently for efficiency. Each execution stores its own trace.

   Returns results for all executions along with total wall-clock time."
  [{{:keys [sheet-id version-number inputs-list]} :command
    :keys [event-store] :as context}]
  (let [sheet (rm/get-sheet event-store sheet-id)
        version (when (and sheet version-number)
                  (rm/get-version event-store sheet-id version-number))]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      (and version-number (not version))
      {::anom/category ::anom/not-found
       ::anom/message (str "Version " version-number " not found")}

      (empty? inputs-list)
      {::anom/category ::anom/incorrect
       ::anom/message "inputs-list cannot be empty"}

      :else
      (let [start-time (System/currentTimeMillis)
            ;; Execute all inputs in parallel using futures
            futures (mapv (fn [input-set]
                            (future
                              (try
                                (runtime/execute context sheet-id (or input-set {})
                                                 :use-version version-number)
                                (catch Exception e
                                  {:status :failure
                                   :error (.getMessage e)}))))
                          inputs-list)
            ;; Collect all results (blocks until all complete)
            results (mapv deref futures)
            end-time (System/currentTimeMillis)]
        {:command-result/data
         {:results results
          :total-executions (count results)
          :successful-count (count (filter #(= :success (:status %)) results))
          :failed-count (count (filter #(not= :success (:status %)) results))
          :duration-ms (- end-time start-time)}}))))
