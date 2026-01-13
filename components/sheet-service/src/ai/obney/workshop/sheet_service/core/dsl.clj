(ns ai.obney.workshop.sheet-service.core.dsl
  "REPL-friendly DSL for building behavior tree workflows.

   This provides a declarative way to define and build workflows:

   ```clojure
   (def my-workflow
     (workflow \"recommendations\"
       (blackboard
         {:student-profile :map
          :programs [:vector :map]
          :recommendations [:vector :map]})

       (sequence
         (code-node \"fetch-programs\"
           :fn \"myapp.fns/fetch-programs\"
           :reads [\"student-profile\"]
           :writes [\"programs\"])

         (map-each \"score-programs\"
           :source \"programs\"
           :item \"current-program\"
           :output \"scored-programs\"
           :concurrency 5
           (parallel
             (ai-node \"academic\"
               :model \"google/gemini-2.5-flash\"
               :instruction \"Score academic fit 0-100\"
               :reads [\"current-program\" \"student-profile\"]
               :writes [\"academic-score\"])
             (ai-node \"career\"
               :model \"google/gemini-2.5-flash\"
               :instruction \"Score career alignment 0-100\"
               :reads [\"current-program\" \"student-profile\"]
               :writes [\"career-score\"]))))))

   ;; Build and execute
   (def sheet-id (build-workflow! ctx my-workflow))
   (sheet/execute ctx sheet-id {\"student-profile\" {...}})
   ```"
  (:require [ai.obney.workshop.sheet-service.test-helpers :as h]
            [ai.obney.workshop.sheet-service.core.read-models :as rm]
            [ai.obney.grain.time.interface :as time]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string]))

;; =============================================================================
;; Node Builders (Data Structures)
;; =============================================================================

(defn ai-node
  "Define an AI executor leaf node.

   Options:
     :model - OpenRouter model ID (e.g., \"google/gemini-2.5-flash\")
     :instruction - Prompt for the AI
     :reads - Vector of blackboard keys to read
     :writes - Vector of blackboard keys to write
     :retry - {:max-attempts n :backoff-ms [100 500]}"
  [name & {:keys [model instruction reads writes retry]}]
  {:node-type :leaf
   :name name
   :executor :ai
   :model model
   :instruction instruction
   :reads (vec reads)
   :writes (vec writes)
   :retry retry})

(defn code-node
  "Define a code executor leaf node.

   Options:
     :fn - Fully-qualified function symbol string
     :reads - Vector of blackboard keys to read
     :writes - Vector of blackboard keys to write
     :retry - {:max-attempts n :backoff-ms [100 500]}"
  [name & {:keys [fn reads writes retry]}]
  {:node-type :leaf
   :name name
   :executor :code
   :fn fn
   :reads (vec reads)
   :writes (vec writes)
   :retry retry})

(defn condition
  "Define a condition node.

   Options:
     :check - {:key \"bb-key\" :op :equals/:gt/:lt/etc :value expected}
     :on-fail - :failure (default) or :success"
  [name & {:keys [check on-fail]}]
  {:node-type :condition
   :name name
   :check check
   :on-fail (or on-fail :failure)})

(defn sequence
  "Define a sequence node (runs children in order, fails on first failure)."
  [& children]
  {:node-type :sequence
   :children (vec children)})

(defn fallback
  "Define a fallback node (runs children in order, succeeds on first success)."
  [& children]
  {:node-type :fallback
   :children (vec children)})

(defn parallel
  "Define a parallel node (runs all children concurrently).

   Options (as first arg if map):
     :success-policy - :all (default), :any, :majority
     :failure-policy - :any (default), :all"
  [& args]
  (let [[opts children] (if (and (map? (first args))
                                 (contains? (first args) :success-policy))
                          [(first args) (rest args)]
                          [{} args])]
    {:node-type :parallel
     :success-policy (or (:success-policy opts) :all)
     :failure-policy (or (:failure-policy opts) :any)
     :children (vec children)}))

(defn map-each
  "Define a map-each node (iterates over a list).

   Options:
     :source - Blackboard key containing the source list
     :item - Blackboard key for the current item
     :output - Blackboard key for the results list
     :concurrency - Max parallel executions (default 1 = sequential)"
  [name & args]
  (let [[opts children] (if (keyword? (first args))
                          ;; Parse keyword args
                          (let [kw-args (take-while #(or (keyword? %) (not (map? %))) args)
                                rest-args (drop (count kw-args) args)
                                opts-map (apply hash-map kw-args)]
                            [opts-map rest-args])
                          [{} args])]
    {:node-type :map-each
     :name name
     :source-key (:source opts)
     :item-key (:item opts)
     :output-key (:output opts)
     :max-concurrency (:concurrency opts)
     :children (vec children)}))

;; =============================================================================
;; Blackboard Schema Builder
;; =============================================================================

(defn blackboard
  "Define the blackboard schema.

   Takes a map of key-name -> malli-schema:
   ```
   (blackboard
     {:input :string
      :items [:vector :map]
      :result :int})
   ```"
  [schema-map]
  {:blackboard-schema schema-map})

;; =============================================================================
;; Workflow Definition
;; =============================================================================

(defn workflow
  "Define a complete workflow.

   Args:
     name - Name of the workflow
     & parts - Blackboard definition and root node"
  [name & parts]
  (let [bb-def (first (filter :blackboard-schema parts))
        root-node (first (filter :node-type parts))]
    {:workflow-name name
     :blackboard-schema (:blackboard-schema bb-def)
     :root-node root-node}))

;; =============================================================================
;; Build Functions
;; =============================================================================

(defn- build-node!
  "Recursively build a node and its children. Returns the node-id."
  [ctx sheet-id node parent-id index]
  (let [node-type (:node-type node)
        ;; Create the node
        create-result (h/run-and-apply! ctx
                        (h/make-create-node-command sheet-id node-type
                          :parent-id parent-id
                          :index index))
        node-id (-> create-result :command-result/events first :node-id)]

    ;; Set name if provided
    (when (:name node)
      (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id node-id (:name node))))

    ;; Configure based on node type
    (case node-type
      :leaf
      (do
        ;; Set executor
        (h/run-and-apply! ctx
          (h/make-set-node-executor-command sheet-id node-id (:executor node)
            :model (:model node)
            :fn (:fn node)))
        ;; Set instruction if AI node
        (when (:instruction node)
          (h/run-and-apply! ctx
            (h/make-set-node-instruction-command sheet-id node-id (:instruction node))))
        ;; Set IO
        (h/run-and-apply! ctx
          (h/make-set-node-io-command sheet-id node-id (:reads node) (:writes node)))
        ;; Set retry if configured
        (when-let [retry (:retry node)]
          (h/run-and-apply! ctx
            (h/make-set-node-retry-command sheet-id node-id
              (:max-attempts retry) (:backoff-ms retry)))))

      :condition
      (do
        ;; Condition nodes store check in node data - would need a command for this
        ;; For now, skip condition configuration
        nil)

      :parallel
      (do
        (h/run-and-apply! ctx
          (h/make-set-parallel-config-command sheet-id node-id
            :success-policy (:success-policy node)
            :failure-policy (:failure-policy node)))
        ;; Build children
        (doseq [[idx child] (map-indexed vector (:children node))]
          (build-node! ctx sheet-id child node-id idx)))

      :map-each
      (do
        (h/run-and-apply! ctx
          (h/make-set-map-each-config-command sheet-id node-id
            (:source-key node) (:item-key node) (:output-key node)
            :max-concurrency (:max-concurrency node)))
        ;; Build children
        (doseq [[idx child] (map-indexed vector (:children node))]
          (build-node! ctx sheet-id child node-id idx)))

      ;; sequence, fallback - just build children
      (doseq [[idx child] (map-indexed vector (:children node))]
        (build-node! ctx sheet-id child node-id idx)))

    node-id))

(defn build-workflow!
  "Build a workflow definition into an actual sheet.

   Args:
     ctx - Test context with :event-store
     workflow-def - Workflow definition from (workflow ...)

   Returns the sheet-id."
  [ctx workflow-def]
  (let [{:keys [workflow-name blackboard-schema root-node]} workflow-def

        ;; Create sheet
        sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name workflow-name))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]

    ;; Declare blackboard keys
    (doseq [[key-name schema] blackboard-schema]
      (h/run-and-apply! ctx
        (h/make-declare-key-command sheet-id (name key-name) schema)))

    ;; Build the tree
    (build-node! ctx sheet-id root-node nil 0)

    sheet-id))

(defn build-workflow!!
  "Idempotent workflow builder. Creates or replaces a workflow by name.

   If a sheet with the same name exists, it is deleted first.
   Returns the (possibly new) sheet-id.

   Use double-bang (!!) to indicate destructive idempotent operation."
  [ctx workflow-def]
  (let [workflow-name (:workflow-name workflow-def)
        existing (rm/get-sheet-by-name (:event-store ctx) workflow-name)]
    (when existing
      (h/run-and-apply! ctx (h/make-delete-sheet-command (:id existing))))
    (build-workflow! ctx workflow-def)))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn print-tree
  "Print a visual representation of a workflow definition."
  ([workflow-def]
   (print-tree (:root-node workflow-def) 0))
  ([node depth]
   (let [indent (apply str (repeat depth "  "))
         node-type (:node-type node)
         name-str (or (:name node) (name node-type))]
     (println (str indent "├── [" (name node-type) "] " name-str
                   (when (:executor node) (str " (" (name (:executor node)) ")"))
                   (when (:model node) (str " model=" (:model node)))))
     (doseq [child (:children node)]
       (print-tree child (inc depth))))))

(defn describe-workflow
  "Print a description of a workflow."
  [workflow-def]
  (println "Workflow:" (:workflow-name workflow-def))
  (println "\nBlackboard Schema:")
  (doseq [[k v] (:blackboard-schema workflow-def)]
    (println "  " (name k) "->" v))
  (println "\nTree Structure:")
  (print-tree workflow-def))

;; =============================================================================
;; Export/Import Functions
;; =============================================================================

(defn- build-node-tree
  "Convert flat nodes map to nested tree structure for export."
  [nodes-by-id root-id]
  (when root-id
    (let [node (get nodes-by-id root-id)]
      (when node
        (cond-> {:type (:type node)
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
                   (:retry node) (assoc :retry (:retry node))))
          ;; Condition-specific
          (= :condition (:type node))
          (merge (cond-> {}
                   (:check node) (assoc :check (:check node))
                   (:on-fail node) (assoc :on-fail (:on-fail node))))
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

(defn export-sheet
  "Export a sheet from the event store as an EDN structure.

   Returns a map that can be saved to file and later imported.

   Example:
     (export-sheet ctx sheet-id)
     ;; => {:version 1 :sheet {...} :blackboard-schema {...} :nodes {...}}"
  [ctx sheet-id]
  (let [es (:event-store ctx)
        sheet (rm/get-sheet es sheet-id)
        nodes (rm/get-nodes-by-id es sheet-id)
        blackboard (rm/get-blackboard-for-sheet es sheet-id)]
    {:version 1
     :exported-at (time/now)
     :sheet {:name (:name sheet)
             :id sheet-id}
     :blackboard-schema (into {}
                              (map (fn [bb] [(keyword (:key bb)) (:schema bb)])
                                   blackboard))
     :nodes (build-node-tree nodes (:root-node-id sheet))}))

(defn- import-node!
  "Recursively import a node and its children from exported structure."
  [ctx sheet-id node parent-id index]
  (let [node-type (:type node)
        ;; Create the node
        create-result (h/run-and-apply! ctx
                        (h/make-create-node-command sheet-id node-type
                          :parent-id parent-id
                          :index index))
        node-id (-> create-result :command-result/events first :node-id)]

    ;; Set name if provided
    (when (:name node)
      (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id node-id (:name node))))

    ;; Configure based on node type
    (case node-type
      :leaf
      (do
        ;; Set executor
        (when (:executor node)
          (h/run-and-apply! ctx
            (h/make-set-node-executor-command sheet-id node-id (:executor node)
              :model (:model node)
              :fn (:fn node))))
        ;; Set instruction if AI node
        (when (:instruction node)
          (h/run-and-apply! ctx
            (h/make-set-node-instruction-command sheet-id node-id (:instruction node))))
        ;; Set IO
        (when (or (seq (:reads node)) (seq (:writes node)))
          (h/run-and-apply! ctx
            (h/make-set-node-io-command sheet-id node-id
              (vec (:reads node)) (vec (:writes node)))))
        ;; Set retry if configured
        (when-let [retry (:retry node)]
          (h/run-and-apply! ctx
            (h/make-set-node-retry-command sheet-id node-id
              (:max-attempts retry) (:backoff-ms retry)))))

      :condition
      nil ;; TODO: add set-check command when implemented

      :parallel
      (do
        (h/run-and-apply! ctx
          (h/make-set-parallel-config-command sheet-id node-id
            :success-policy (:success-policy node)
            :failure-policy (:failure-policy node)))
        ;; Build children
        (doseq [[idx child] (map-indexed vector (:children node))]
          (import-node! ctx sheet-id child node-id idx)))

      :map-each
      (do
        (h/run-and-apply! ctx
          (h/make-set-map-each-config-command sheet-id node-id
            (:source-key node) (:item-key node) (:output-key node)
            :max-concurrency (:max-concurrency node)))
        ;; Build children
        (doseq [[idx child] (map-indexed vector (:children node))]
          (import-node! ctx sheet-id child node-id idx)))

      ;; sequence, fallback - just build children
      (doseq [[idx child] (map-indexed vector (:children node))]
        (import-node! ctx sheet-id child node-id idx)))

    node-id))

(defn import-sheet
  "Import a sheet from an exported EDN structure.

   Creates a new sheet with the same structure. Returns the new sheet-id.

   Example:
     (import-sheet ctx exported-data)"
  [ctx exported]
  (let [sheet-name (get-in exported [:sheet :name])
        blackboard-schema (:blackboard-schema exported)
        root-node (:nodes exported)

        ;; Create new sheet
        sheet-result (h/run-and-apply! ctx
                       (h/make-create-sheet-command :name sheet-name))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]

    ;; Declare blackboard keys
    (doseq [[key-name schema] blackboard-schema]
      (h/run-and-apply! ctx
        (h/make-declare-key-command sheet-id (name key-name) schema)))

    ;; Build the node tree
    (when root-node
      (import-node! ctx sheet-id root-node nil 0))

    sheet-id))

;; =============================================================================
;; File I/O Helpers
;; =============================================================================

(defn save-sheet!
  "Export a sheet and save to file.

   Example:
     (save-sheet! ctx sheet-id \"development/sheets/my-workflow.edn\")"
  [ctx sheet-id filepath]
  (let [exported (export-sheet ctx sheet-id)]
    (io/make-parents filepath)
    (spit filepath (with-out-str (pprint/pprint exported)))
    (println "Saved sheet to:" filepath)
    filepath))

(defn load-sheet!
  "Load a sheet from file and import it. Returns the new sheet-id.

   Example:
     (load-sheet! ctx \"development/sheets/my-workflow.edn\")"
  [ctx filepath]
  (let [exported (read-string (slurp filepath))
        sheet-id (import-sheet ctx exported)]
    (println "Loaded sheet from:" filepath "-> sheet-id:" sheet-id)
    sheet-id))

(defn save-all-sheets!
  "Export all sheets to a directory.

   Example:
     (save-all-sheets! ctx \"development/sheets\")"
  [ctx dir-path]
  (let [sheets (rm/get-sheets-all (:event-store ctx))]
    (doseq [sheet sheets]
      (let [safe-name (-> (:name sheet)
                          (clojure.string/replace #"[^a-zA-Z0-9-_]" "_"))
            filename (str safe-name ".edn")
            filepath (str dir-path "/" filename)]
        (save-sheet! ctx (:id sheet) filepath)))
    (println "Saved" (count sheets) "sheets to:" dir-path)))

(defn load-all-sheets!
  "Load all sheets from a directory.

   Example:
     (load-all-sheets! ctx \"development/sheets\")"
  [ctx dir-path]
  (let [dir (io/file dir-path)
        files (when (.exists dir)
                (->> (.listFiles dir)
                     (filter #(.endsWith (.getName %) ".edn"))
                     (sort-by #(.getName %))))]
    (doseq [file files]
      (load-sheet! ctx (.getPath file)))
    (println "Loaded" (count files) "sheets from:" dir-path)))
