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
   Using :any for numbers due to Malli validation quirks with composite specs."
  {:text     :string
   :number   :any  ;; Could be int, double, or string representation
   :yesno    :boolean
   :list     [:vector :any]
   :document :string
   :image    :string
   :table    [:vector [:map-of :string :any]]})

(defn- build-field
  "Build a DSCloj field definition from a blackboard key and its entry"
  [key-name blackboard-entry]
  {:name (keyword key-name)
   :spec (get field-type->malli-spec (:type blackboard-entry) :string)
   :description (str "Blackboard key: " key-name " (type: " (name (:type blackboard-entry)) ")")})

;; =============================================================================
;; Module Builder
;; =============================================================================

(defn build-module
  "Build a DSCloj module from a leaf node and blackboard metadata.

   Args:
     node - The leaf node map with :instruction, :reads, :writes
     blackboard - Map of key -> {:key, :type, :value, :version}

   Returns a DSCloj module map with :inputs, :outputs, :instructions"
  [node blackboard]
  (let [inputs (mapv (fn [key-name]
                       (if-let [entry (get blackboard key-name)]
                         (build-field key-name entry)
                         {:name (keyword key-name)
                          :spec :string
                          :description (str "Input: " key-name)}))
                     (:reads node))
        outputs (mapv (fn [key-name]
                        (if-let [entry (get blackboard key-name)]
                          (build-field key-name entry)
                          {:name (keyword key-name)
                           :spec :string
                           :description (str "Output: " key-name)}))
                      (:writes node))]
    {:inputs inputs
     :outputs outputs
     :instructions (or (:instruction node) "Execute this task.")}))

(defn gather-inputs
  "Gather input values from the blackboard for the node's reads.

   Args:
     node - The leaf node with :reads
     blackboard - Map of key -> {:key, :type, :value, :version}

   Returns a map of keyword -> value for DSCloj"
  [node blackboard]
  (reduce (fn [acc key-name]
            (if-let [entry (get blackboard key-name)]
              (assoc acc (keyword key-name) (:value entry))
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
        inputs (gather-inputs node blackboard)]
    (try
      (let [result (dscloj/predict provider module inputs options)
            ;; Convert keyword keys back to strings for blackboard
            outputs (reduce-kv (fn [acc k v]
                                 (assoc acc (name k) v))
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
