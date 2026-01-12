(ns ai.obney.workshop.sheet-service.core.executor
  "DSCloj-based executor for behavior tree leaf nodes.

   This module bridges the gap between the behavior tree's leaf nodes
   and DSCloj's AI execution capabilities.

   Mapping:
   - Node instruction → DSCloj module instructions
   - Node reads + blackboard types → DSCloj module inputs
   - Node writes + blackboard types → DSCloj module outputs
   - Blackboard values → DSCloj input values
   - DSCloj output values → Blackboard writes"
  (:require [dscloj.core :as dscloj]))

;; =============================================================================
;; Schema Description Generation
;; =============================================================================

(defn malli-schema->description
  "Generate a human-readable description from a Malli schema for AI context.
   This helps the AI understand what structure to produce."
  [schema]
  (cond
    ;; Simple keyword types
    (keyword? schema)
    (case schema
      :string "string"
      :int "integer"
      :double "number"
      :number "number"
      :boolean "boolean (true/false)"
      :any "any value"
      :uuid "UUID string"
      (name schema))

    ;; Vector schemas like [:map ...], [:vector ...], [:enum ...]
    (vector? schema)
    (let [[schema-type & args] schema]
      (case schema-type
        :map
        (let [fields (filter vector? args)  ;; Skip property maps
              field-descs (for [field fields]
                            (let [[field-key & rest] field
                                  ;; Handle optional {:optional true} map
                                  opts (when (map? (first rest)) (first rest))
                                  field-schema (if opts (second rest) (first rest))
                                  optional? (:optional opts)]
                              (str (name field-key)
                                   (when optional? "?")
                                   ": " (malli-schema->description field-schema))))]
          (str "object with {" (clojure.string/join ", " field-descs) "}"))

        :vector
        (str "list of " (malli-schema->description (first args)))

        :enum
        (str "one of: " (clojure.string/join ", " (map pr-str args)))

        :maybe
        (str (malli-schema->description (first args)) " (optional)")

        :or
        (str "either " (clojure.string/join " or " (map malli-schema->description args)))

        :map-of
        (let [[key-schema val-schema] args]
          (str "map of " (malli-schema->description key-schema)
               " -> " (malli-schema->description val-schema)))

        ;; Default for unknown vector schemas
        (str schema-type " " (clojure.string/join " " (map malli-schema->description args)))))

    ;; Fallback
    :else (pr-str schema)))

(defn- sanitize-field-name
  "Sanitize field name for DSCloj - remove ? and other problematic chars"
  [key-name]
  (-> key-name
      (clojure.string/replace "?" "")
      (clojure.string/replace "!" "")
      (clojure.string/replace #"[^a-zA-Z0-9_-]" "_")))

(defn- build-field
  "Build a DSCloj field definition from a blackboard key and its entry.
   Now uses Malli schemas directly instead of legacy field types."
  [key-name blackboard-entry]
  (let [schema (:schema blackboard-entry)
        safe-name (sanitize-field-name key-name)
        schema-desc (when schema (malli-schema->description schema))]
    {:name (keyword safe-name)
     :original-key key-name  ;; Keep original for mapping back
     :spec (or schema :any)  ;; Use the Malli schema directly
     :description (str "Blackboard key: " key-name
                       (when schema-desc (str " - " schema-desc)))}))

;; =============================================================================
;; Module Builder
;; =============================================================================

(defn build-module
  "Build a DSCloj module from a leaf node and blackboard metadata.

   Args:
     node - The leaf node map with :instruction, :reads, :writes
     blackboard - Map of key -> {:key, :type, :value, :version}

   Returns a DSCloj module map with :inputs, :outputs, :instructions
   and :output-key-mapping for converting sanitized names back to originals"
  [node blackboard]
  (let [inputs (mapv (fn [key-name]
                       (if-let [entry (get blackboard key-name)]
                         (build-field key-name entry)
                         {:name (keyword (sanitize-field-name key-name))
                          :original-key key-name
                          :spec :string
                          :description (str "Input: " key-name)}))
                     (:reads node))
        outputs (mapv (fn [key-name]
                        (if-let [entry (get blackboard key-name)]
                          (build-field key-name entry)
                          {:name (keyword (sanitize-field-name key-name))
                           :original-key key-name
                           :spec :string
                           :description (str "Output: " key-name)}))
                      (:writes node))
        ;; Build mapping from sanitized name -> original key
        output-key-mapping (into {} (map (fn [o] [(name (:name o)) (:original-key o)]) outputs))]
    {:inputs inputs
     :outputs outputs
     :instructions (or (:instruction node) "Execute this task.")
     :output-key-mapping output-key-mapping}))

(defn gather-inputs
  "Gather input values from the blackboard for the node's reads.

   Args:
     node - The leaf node with :reads
     blackboard - Map of key -> {:key, :type, :value, :version}

   Returns a map of keyword -> value for DSCloj (using sanitized names)"
  [node blackboard]
  (reduce (fn [acc key-name]
            (if-let [entry (get blackboard key-name)]
              (assoc acc (keyword (sanitize-field-name key-name)) (:value entry))
              acc))
          {}
          (:reads node)))

;; =============================================================================
;; Execution
;; =============================================================================

(defn execute-leaf
  "Execute a leaf node using DSCloj.

   Args:
     node - The leaf node map
     blackboard - Map of key -> {:key, :type, :value, :version}
     provider - DSCloj provider keyword (e.g., :openrouter, :anthropic)
     options - Optional DSCloj options map

   Returns:
     {:status :success/:failure
      :outputs {string-key value} - outputs to write to blackboard
      :error string?             - error message if failed
      :duration-ms int}          - execution time"
  [node blackboard provider & {:keys [options] :or {options {}}}]
  (let [start-time (System/currentTimeMillis)
        module (build-module node blackboard)
        inputs (gather-inputs node blackboard)
        output-key-mapping (:output-key-mapping module)
        ;; Remove the mapping from module before passing to DSCloj
        dscloj-module (dissoc module :output-key-mapping)]
    (try
      (let [result (dscloj/predict provider dscloj-module inputs options)
            ;; Convert sanitized keys back to original blackboard keys
            outputs (reduce-kv (fn [acc k v]
                                 (let [sanitized-name (name k)
                                       original-key (get output-key-mapping sanitized-name sanitized-name)]
                                   (assoc acc original-key v)))
                               {}
                               result)
            duration-ms (- (System/currentTimeMillis) start-time)]
        {:status :success
         :outputs outputs
         :duration-ms duration-ms})
      (catch Exception e
        {:status :failure
         :error (.getMessage e)
         :duration-ms (- (System/currentTimeMillis) start-time)}))))

;; =============================================================================
;; Mock Executor (for testing without AI)
;; =============================================================================

(defn execute-leaf-mock
  "Mock executor that returns success with placeholder outputs.
   Useful for testing the behavior tree flow without AI calls."
  [node _blackboard]
  (let [start-time (System/currentTimeMillis)
        outputs (into {}
                      (map (fn [k] [k (str "mock-value-for-" k)])
                           (:writes node)))
        duration-ms (- (System/currentTimeMillis) start-time)]
    {:status :success
     :outputs outputs
     :duration-ms duration-ms}))

;; =============================================================================
;; Provider Setup
;; =============================================================================

(defn setup-providers!
  "Set up DSCloj providers from environment variables.
   Call this at application startup."
  []
  (dscloj/quick-setup!))

(defn list-available-providers
  "List all registered DSCloj providers."
  []
  (dscloj/list-providers))

(comment
  ;; Example usage:

  ;; 1. Setup providers (do once at app startup)
  (setup-providers!)

  ;; 2. Define a node and blackboard
  (def example-node
    {:instruction "Given the question, provide a clear and concise answer."
     :reads ["question"]
     :writes ["answer"]})

  (def example-blackboard
    {"question" {:key "question" :schema :string :value "What is 2+2?" :version 1}
     "answer" {:key "answer" :schema :string :value nil :version 0}})

  ;; 3. Execute
  (execute-leaf example-node example-blackboard :openrouter)
  ;; => {:status :success, :outputs {"answer" "4"}, :duration-ms 1234}

  ;; 4. Or use mock for testing
  (execute-leaf-mock example-node example-blackboard)
  ;; => {:status :success, :outputs {"answer" "mock-value-for-answer"}, :duration-ms 0}
  )
