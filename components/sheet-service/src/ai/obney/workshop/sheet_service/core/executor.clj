(ns ai.obney.workshop.sheet-service.core.executor
  "DSCloj-based executor for behavior tree leaf nodes.

   This module bridges the gap between the behavior tree's leaf nodes
   and DSCloj's AI execution capabilities.

   Supports multiple executor types:
   - :ai - DSCloj AI execution with optional model selection
   - :code - Clojure function execution
   - :tool - Direct tool invocation (future)

   Mapping:
   - Node instruction → DSCloj module instructions
   - Node reads + blackboard types → DSCloj module inputs
   - Node writes + blackboard types → DSCloj module outputs
   - Blackboard values → DSCloj input values
   - DSCloj output values → Blackboard writes"
  (:require [dscloj.core :as dscloj]
            [litellm.router :as litellm-router]
            [clojure.string :as str]))

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
        (str "one of: " (clojure.string/join ", " (map str args)))

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
;; Code Executor
;; =============================================================================

(defn resolve-fn
  "Resolve a fully-qualified function symbol string to a function.
   Returns {:fn f} on success or {:error msg} on failure."
  [fn-symbol-str]
  (try
    (let [[ns-str fn-str] (str/split fn-symbol-str #"/")
          ns-sym (symbol ns-str)
          fn-sym (symbol fn-str)]
      ;; Try to find namespace first (may already be loaded)
      (when-not (find-ns ns-sym)
        ;; Only require if namespace not already loaded
        (require ns-sym))
      (if-let [f (ns-resolve (find-ns ns-sym) fn-sym)]
        {:fn (if (var? f) @f f)}
        {:error (str "Function not found: " fn-symbol-str)}))
    (catch Exception e
      {:error (str "Failed to resolve function: " fn-symbol-str " - " (.getMessage e))})))

(defn execute-code
  "Execute a Clojure function as a leaf node.

   The function receives a context map with:
   - :event-store - The event store (if provided)
   - :inputs - Map of blackboard key -> value for node's reads

   The function should return a map of blackboard key -> value for writes.

   Args:
     node - The leaf node map with :fn (fully-qualified symbol string)
     blackboard - Map of key -> {:key, :type, :value, :version}
     context - Additional context (event-store, etc.)

   Returns:
     {:status :success/:failure
      :outputs {string-key value}
      :error string?
      :duration-ms int}"
  [node blackboard context]
  (let [start-time (System/currentTimeMillis)
        fn-symbol (:fn node)
        resolved (resolve-fn fn-symbol)]
    (if (:error resolved)
      {:status :failure
       :error (:error resolved)
       :duration-ms (- (System/currentTimeMillis) start-time)}
      (try
        (let [f (:fn resolved)
              ;; Gather inputs from blackboard
              inputs (reduce (fn [acc key-name]
                               (if-let [entry (get blackboard key-name)]
                                 (assoc acc key-name (:value entry))
                                 acc))
                             {}
                             (:reads node))
              ;; Call the function with context
              result (f (assoc context :inputs inputs))
              duration-ms (- (System/currentTimeMillis) start-time)]
          ;; Result should be a map of key -> value
          (if (map? result)
            {:status :success
             :outputs result
             :duration-ms duration-ms}
            {:status :failure
             :error (str "Code executor function must return a map, got: " (type result))
             :duration-ms duration-ms}))
        (catch Exception e
          {:status :failure
           :error (.getMessage e)
           :duration-ms (- (System/currentTimeMillis) start-time)})))))

;; =============================================================================
;; AI Execution
;; =============================================================================

(defn- get-provider-with-model
  "Get or create a provider config with the specified model.

   litellm-clj's router ignores :model in request options when using a registered
   provider keyword. To work around this, when a model override is specified,
   we dynamically register a model-specific provider if it doesn't exist.

   Returns the provider keyword to use (either original or model-specific)."
  [provider model-override]
  (if (and model-override (keyword? provider))
    ;; Create a model-specific provider name
    (let [model-provider-name (keyword (str (name provider) "/" model-override))
          existing (litellm-router/get-config model-provider-name)]
      (when-not existing
        ;; Register the model-specific provider
        (let [base-config (litellm-router/get-config provider)]
          (when base-config
            (litellm-router/register! model-provider-name
                                      (assoc base-config :model model-override)))))
      model-provider-name)
    ;; No override needed, use provider as-is
    provider))

(defn- outputs-have-nil?
  "Check if any output values are nil."
  [outputs]
  (some nil? (vals outputs)))

(defn execute-ai
  "Execute a leaf node using DSCloj AI.

   Args:
     node - The leaf node map
     blackboard - Map of key -> {:key, :type, :value, :version}
     provider - DSCloj provider keyword (e.g., :openrouter, :anthropic)
     options - Optional DSCloj options map (can include :model, :max-retries, :retry-delay-ms)

   Returns:
     {:status :success/:failure
      :outputs {string-key value} - outputs to write to blackboard
      :error string?             - error message if failed
      :duration-ms int           - execution time
      :usage {:prompt_tokens N :completion_tokens N :total_tokens N} - token usage (when available)
      :model string?}            - model used (when available)"
  [node blackboard provider & {:keys [options] :or {options {}}}]
  (let [start-time (System/currentTimeMillis)
        module (build-module node blackboard)
        inputs (gather-inputs node blackboard)
        output-key-mapping (:output-key-mapping module)
        ;; Remove the mapping from module before passing to DSCloj
        dscloj-module (dissoc module :output-key-mapping)
        ;; Build effective provider config with model override if specified
        effective-provider (get-provider-with-model provider (:model node))
        ;; Request metadata for usage tracking
        dscloj-options (assoc options :with-metadata? true)
        ;; Retry config - defaults to 1 retry with 500ms delay
        max-retries (get options :max-retries 1)
        retry-delay-ms (get options :retry-delay-ms 500)

        ;; Single attempt function
        try-once (fn []
                   (let [result (dscloj/predict effective-provider dscloj-module inputs dscloj-options)
                         raw-outputs (:outputs result)
                         outputs (reduce-kv (fn [acc k v]
                                              (let [sanitized-name (name k)
                                                    original-key (get output-key-mapping sanitized-name sanitized-name)]
                                                (assoc acc original-key v)))
                                            {}
                                            raw-outputs)]
                     {:outputs outputs :usage (:usage result) :model (:model result)}))]
    (try
      (loop [attempt 0]
        (let [{:keys [outputs usage model]} (try-once)]
          (if (and (outputs-have-nil? outputs)
                   (< attempt max-retries))
            (do
              (Thread/sleep retry-delay-ms)
              (recur (inc attempt)))
            ;; Return result (with or without nils)
            {:status :success
             :outputs outputs
             :duration-ms (- (System/currentTimeMillis) start-time)
             :usage usage
             :model model})))
      (catch Exception e
        {:status :failure
         :error (.getMessage e)
         :duration-ms (- (System/currentTimeMillis) start-time)}))))

(defn execute-llm-condition
  "Execute an LLM condition node - uses LLM to evaluate a yes/no question.

   Args:
     node - The llm-condition node map with :instruction, :reads, :model
     blackboard - Map of key -> {:key, :schema, :value, :version}
     provider - DSCloj provider keyword (e.g., :openrouter)
     options - Optional DSCloj options map

   Returns:
     {:status :success/:failure
      :result boolean?          - the LLM's yes/no answer
      :error string?            - error message if failed
      :duration-ms int          - execution time
      :usage {:prompt_tokens N :completion_tokens N :total_tokens N} - token usage (when available)
      :model string?}           - model used (when available)"
  [node blackboard provider & {:keys [options] :or {options {}}}]
  (let [start-time (System/currentTimeMillis)
        ;; Build inputs from reads
        inputs (mapv (fn [key-name]
                       (if-let [entry (get blackboard key-name)]
                         (build-field key-name entry)
                         {:name (keyword (sanitize-field-name key-name))
                          :original-key key-name
                          :spec :string
                          :description (str "Input: " key-name)}))
                     (:reads node))
        ;; Build module with fixed boolean output
        module {:inputs inputs
                :outputs [{:name :result
                           :spec :boolean
                           :description "True if the condition is met, false otherwise"}]
                :instructions (:instruction node)}
        ;; Gather input values
        input-values (into {}
                           (for [key-name (:reads node)
                                 :let [entry (get blackboard key-name)]
                                 :when entry]
                             [(keyword (sanitize-field-name key-name)) (:value entry)]))
        ;; Build effective provider config with model override if specified
        effective-provider (get-provider-with-model provider (:model node))
        ;; Request metadata for usage tracking
        dscloj-options (assoc options :with-metadata? true)]
    (try
      (let [response (dscloj/predict effective-provider module input-values dscloj-options)
            ;; Response now has {:outputs {...} :usage {...} :model "..."}
            bool-result (get-in response [:outputs :result])
            duration-ms (- (System/currentTimeMillis) start-time)]
        {:status :success
         :result (boolean bool-result)
         :duration-ms duration-ms
         :usage (:usage response)
         :model (:model response)})
      (catch Exception e
        {:status :failure
         :error (.getMessage e)
         :duration-ms (- (System/currentTimeMillis) start-time)}))))

;; =============================================================================
;; Retry Logic
;; =============================================================================

(defn get-backoff
  "Get backoff duration for a given attempt (0-indexed)."
  [retry-config attempt]
  (let [backoff-ms (:backoff-ms retry-config)]
    (get backoff-ms (min attempt (dec (count backoff-ms))))))

(defn execute-with-retry
  "Execute a function with retry logic.

   Args:
     execute-fn - Zero-arg function that returns {:status :success/:failure ...}
     retry-config - {:max-attempts n :backoff-ms [100 500 2000]}

   Returns the result of execute-fn, retrying on failure up to max-attempts."
  [execute-fn retry-config]
  (let [max-attempts (or (:max-attempts retry-config) 1)]
    (loop [attempt 0]
      (let [result (execute-fn)]
        (if (or (= :success (:status result))
                (>= (inc attempt) max-attempts))
          result
          (do
            (when-let [backoff (get-backoff retry-config attempt)]
              (Thread/sleep backoff))
            (recur (inc attempt))))))))

;; =============================================================================
;; Main Execution Entry Point
;; =============================================================================

(defn execute-leaf
  "Execute a leaf node based on its executor type.

   Executor types:
   - :ai (default) - DSCloj AI execution
   - :code - Clojure function execution
   - :tool - Direct tool invocation (not yet implemented)

   Args:
     node - The leaf node map
     blackboard - Map of key -> {:key, :type, :value, :version}
     provider - DSCloj provider keyword (for :ai executor)
     context - Additional context map (event-store, etc.)

   Returns:
     {:status :success/:failure
      :outputs {string-key value}
      :error string?
      :duration-ms int}"
  [node blackboard provider & {:keys [context options] :or {context {} options {}}}]
  (let [executor-type (or (:executor node) :ai)
        retry-config (:retry node)
        execute-fn (fn []
                     (case executor-type
                       :ai (execute-ai node blackboard provider :options options)
                       :code (execute-code node blackboard context)
                       :tool {:status :failure
                              :error "Tool executor not yet implemented"
                              :duration-ms 0}
                       ;; Default to AI
                       (execute-ai node blackboard provider :options options)))]
    (if retry-config
      (execute-with-retry execute-fn retry-config)
      (execute-fn))))

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
