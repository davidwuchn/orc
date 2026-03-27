(ns ai.obney.orc.orc-service.core.agent-runtime
  "Agent runtime for executing cell formulas.

   This namespace provides:
   - AgentRuntime protocol for pluggable agent implementations
   - MockAgentRuntime for testing (generates mock outputs)
   - Helper functions for creating runtimes")

;; =============================================================================
;; Agent Runtime Protocol
;; =============================================================================

(defprotocol AgentRuntime
  "Protocol for agent execution implementations."
  (execute [this signature inputs]
    "Execute a signature with inputs, return outputs map.
     signature: {:instruction string, :inputs [...], :outputs [...]}
     inputs: {input-name {:type field-type, :value any}}
     returns: {output-name {:type field-type, :value any}}"))

;; =============================================================================
;; Mock Value Generation
;; =============================================================================

(defn mock-value-for-type
  "Generate a mock value for a field type."
  [field-type field-name]
  (case field-type
    :text (str "Mock " field-name " output")
    :number 42
    :list ["item1" "item2" "item3"]
    :document (str "# Mock Document\n\nThis is mock content for " field-name ".")
    :image "https://example.com/mock-image.png"
    :table [{"column1" "row1-value1" "column2" "row1-value2"}
            {"column1" "row2-value1" "column2" "row2-value2"}]
    :yes-no true
    (str "unknown-type-" field-name)))

(defn generate-mock-outputs
  "Generate mock outputs based on signature output definitions."
  [output-definitions]
  (into {}
        (map (fn [output-def]
               [(:name output-def)
                {:type (:type output-def)
                 :value (mock-value-for-type (:type output-def) (:name output-def))}])
             output-definitions)))

;; =============================================================================
;; Mock Agent Runtime
;; =============================================================================

(defrecord MockAgentRuntime [delay-ms responses]
  AgentRuntime
  (execute [_ signature inputs]
    ;; Simulate processing delay
    (when delay-ms
      (Thread/sleep delay-ms))

    ;; Check for pre-configured response for this instruction
    (if-let [response (get responses (:instruction signature))]
      (if (fn? response)
        (response signature inputs)
        response)
      ;; Generate mock outputs based on signature
      (generate-mock-outputs (:outputs signature)))))

(defn create-mock-runtime
  "Create a mock agent runtime.

   Options:
   - :delay-ms - Simulated processing delay in milliseconds (default: 100)
   - :responses - Map of instruction -> outputs (or instruction -> fn)
                  If a function, called with (signature inputs)

   Example:
   (create-mock-runtime
     :delay-ms 50
     :responses {\"Write a summary\" {\"summary\" {:type :text :value \"Custom summary\"}}
                 \"Evaluate quality\" (fn [sig inputs]
                                        {\"score\" {:type :number :value (rand-int 100)}})})"
  [& {:keys [delay-ms responses] :or {delay-ms 100 responses {}}}]
  (->MockAgentRuntime delay-ms responses))

;; =============================================================================
;; Configurable Mock Runtime
;; =============================================================================

(defrecord ConfigurableMockRuntime [delay-ms error-rate responses]
  AgentRuntime
  (execute [_ signature inputs]
    ;; Simulate processing delay
    (when delay-ms
      (Thread/sleep delay-ms))

    ;; Simulate random failures
    (when (and error-rate (< (rand) error-rate))
      (throw (ex-info "Simulated agent failure" {:type :agent-error})))

    ;; Check for pre-configured response
    (if-let [response (get responses (:instruction signature))]
      (if (fn? response)
        (response signature inputs)
        response)
      (generate-mock-outputs (:outputs signature)))))

(defn create-configurable-runtime
  "Create a configurable mock runtime for testing error scenarios.

   Options:
   - :delay-ms - Simulated processing delay (default: 100)
   - :error-rate - Probability of failure, 0.0-1.0 (default: 0)
   - :responses - Map of instruction -> outputs or functions"
  [& {:keys [delay-ms error-rate responses]
      :or {delay-ms 100 error-rate 0 responses {}}}]
  (->ConfigurableMockRuntime delay-ms error-rate responses))

;; =============================================================================
;; Echo Runtime (for debugging)
;; =============================================================================

(defrecord EchoRuntime []
  AgentRuntime
  (execute [_ signature inputs]
    ;; Return outputs that echo the inputs for debugging
    (into {}
          (map (fn [output-def]
                 [(:name output-def)
                  {:type (:type output-def)
                   :value (str "Echo: instruction='" (:instruction signature)
                               "' inputs=" (pr-str inputs))}])
               (:outputs signature)))))

(defn create-echo-runtime
  "Create an echo runtime that returns debug info in outputs."
  []
  (->EchoRuntime))
