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
;; Type Mapping
;; =============================================================================

(def field-type->malli-spec
  "Maps blackboard field types to Malli specs for DSCloj.
   Using :any for numbers due to Malli validation quirks with composite specs.
   Note: yesno uses :string because DSCloj's boolean parsing is unreliable."
  {:text     :string
   :number   :any  ;; Could be int, double, or string representation
   :yesno    :string  ;; Use string "yes"/"no" - DSCloj boolean parsing broken
   :list     [:vector :any]
   :document :string
   :image    :string
   :table    [:vector [:map-of :string :any]]})

(defn- sanitize-field-name
  "Sanitize field name for DSCloj - remove ? and other problematic chars"
  [key-name]
  (-> key-name
      (clojure.string/replace "?" "")
      (clojure.string/replace "!" "")
      (clojure.string/replace #"[^a-zA-Z0-9_-]" "_")))

(defn- build-field
  "Build a DSCloj field definition from a blackboard key and its entry"
  [key-name blackboard-entry]
  (let [field-type (:type blackboard-entry)
        safe-name (sanitize-field-name key-name)]
    {:name (keyword safe-name)
     :original-key key-name  ;; Keep original for mapping back
     :spec (get field-type->malli-spec field-type :string)
     :description (if (= :yesno field-type)
                    (str "Blackboard key: " key-name " - output 'yes' or 'no'")
                    (str "Blackboard key: " key-name " (type: " (name field-type) ")"))}))

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
    {"question" {:key "question" :type :text :value "What is 2+2?" :version 1}
     "answer" {:key "answer" :type :text :value nil :version 0}})

  ;; 3. Execute
  (execute-leaf example-node example-blackboard :openrouter)
  ;; => {:status :success, :outputs {"answer" "4"}, :duration-ms 1234}

  ;; 4. Or use mock for testing
  (execute-leaf-mock example-node example-blackboard)
  ;; => {:status :success, :outputs {"answer" "mock-value-for-answer"}, :duration-ms 0}
  )
