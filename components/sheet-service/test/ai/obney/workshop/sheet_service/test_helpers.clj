(ns ai.obney.workshop.sheet-service.test-helpers
  "Test utilities for sheet service tests."
  (:require [ai.obney.workshop.sheet-service.core.commands]
            [ai.obney.workshop.sheet-service.core.queries]
            [ai.obney.workshop.sheet-service.interface.schemas]
            [ai.obney.workshop.sheet-service.core.agent-runtime :as agent]
            [ai.obney.grain.event-store-v2.interface :as es]
            [ai.obney.grain.command-processor.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test Context
;; =============================================================================

(defn create-test-context
  "Create a fresh test context with in-memory event store."
  []
  (let [event-store (es/start {:conn {:type :in-memory}
                               :event-pubsub nil
                               :logger nil})
        agent-runtime (agent/create-mock-runtime :delay-ms 10)]
    {:event-store event-store
     :agent-runtime agent-runtime
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)}))

(defn stop-context
  "Stop and clean up test context."
  [ctx]
  (when-let [event-store (:event-store ctx)]
    (es/stop event-store)))

(defmacro with-test-context
  "Execute body with a fresh test context, cleaning up afterward."
  [[ctx-sym] & body]
  `(let [~ctx-sym (create-test-context)]
     (try
       ~@body
       (finally
         (stop-context ~ctx-sym)))))

;; =============================================================================
;; Command/Query Execution
;; =============================================================================

(defn run-command
  "Run a command with the given context and command data."
  [ctx command-data]
  (let [command-name (:command/name command-data)
        handler-fn (get-in ctx [:command-registry command-name :handler-fn])]
    (if handler-fn
      (handler-fn (assoc ctx :command command-data))
      (throw (ex-info "Unknown command" {:command command-name})))))

(defn apply-events!
  "Apply events from a command result to the event store."
  [ctx result]
  (when-let [events (seq (:command-result/events result))]
    (es/append (:event-store ctx) {:events (vec events)}))
  result)

(defn run-and-apply!
  "Run a command and apply its events to the store."
  [ctx command-data]
  (let [result (run-command ctx command-data)]
    (apply-events! ctx result)
    result))

(defn run-query
  "Run a query with the given context and query data."
  [ctx query-data]
  (let [query-name (:query/name query-data)
        handler-fn (get-in ctx [:query-registry query-name :handler-fn])]
    (if handler-fn
      (handler-fn (assoc ctx :query query-data))
      (throw (ex-info "Unknown query" {:query query-name})))))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn make-create-sheet-command
  "Create a create-sheet command with defaults."
  [& {:keys [name description]
      :or {name "Test Sheet"
           description "A test sheet for unit tests"}}]
  {:command/name :sheet/create-sheet
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :name name
   :description description})

(defn make-create-cell-command
  "Create a create-cell command."
  [sheet-id address & {:keys [cell-id]}]
  (cond-> {:command/name :sheet/create-cell
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :address address}
    cell-id (assoc :cell-id cell-id)))

(defn make-set-literal-command
  "Create a set-cell-literal command."
  [sheet-id cell-id fields]
  {:command/name :sheet/set-cell-literal
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :cell-id cell-id
   :fields fields})

(defn make-set-signature-command
  "Create a set-cell-signature command."
  [sheet-id cell-id signature]
  {:command/name :sheet/set-cell-signature
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :cell-id cell-id
   :signature signature})

(defn make-bind-input-command
  "Create a bind-input command."
  [sheet-id cell-id input-name source-cell-id source-field-name]
  {:command/name :sheet/bind-input
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :cell-id cell-id
   :input-name input-name
   :source-cell-id source-cell-id
   :source-field-name source-field-name})

(defn make-request-execution-command
  "Create a request-cell-execution command."
  [sheet-id cell-id]
  {:command/name :sheet/request-cell-execution
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :cell-id cell-id})

;; =============================================================================
;; Test Data Builders
;; =============================================================================

(defn text-field
  "Create a text field value."
  [value]
  {:type :text :value value})

(defn number-field
  "Create a number field value."
  [value]
  {:type :number :value value})

(defn yes-no-field
  "Create a yes-no field value."
  [value]
  {:type :yes-no :value value})

(defn simple-signature
  "Create a simple signature with one text input and one text output."
  [instruction]
  {:instruction instruction
   :inputs [{:name "input" :type :text}]
   :outputs [{:name "output" :type :text}]})

(defn gate-signature
  "Create a gate signature with a yes-no output for cycle control."
  [instruction]
  {:instruction instruction
   :inputs [{:name "input" :type :text}]
   :outputs [{:name "result" :type :text}
             {:name "continue" :type :yes-no}]})

;; =============================================================================
;; Assertion Helpers
;; =============================================================================

(defn get-event-type
  "Get the event type from a command result."
  [result]
  (-> result :command-result/events first :event/type))

(defn get-event-body
  "Get the event body from a command result."
  [result]
  (-> result :command-result/events first))

(defn is-anomaly?
  "Check if a result is an anomaly."
  [result]
  (contains? result :cognitect.anomalies/category))

(defn anomaly-category
  "Get the anomaly category from a result."
  [result]
  (:cognitect.anomalies/category result))
