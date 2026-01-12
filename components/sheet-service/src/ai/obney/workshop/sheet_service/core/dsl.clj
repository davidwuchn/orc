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
            ))

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
