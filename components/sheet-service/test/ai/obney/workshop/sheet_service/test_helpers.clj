(ns ai.obney.workshop.sheet-service.test-helpers
  "Test utilities for behavior tree sheet service tests."
  (:require [ai.obney.workshop.sheet-service.core.commands]
            [ai.obney.workshop.sheet-service.core.queries]
            [ai.obney.workshop.sheet-service.interface.schemas]
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
                               :logger nil})]
    {:event-store event-store
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
;; Factory Functions - Sheet Commands
;; =============================================================================

(defn make-create-sheet-command
  "Create a create-sheet command with defaults."
  [& {:keys [name]
      :or {name "Test Sheet"}}]
  {:command/name :sheet/create-sheet
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :name name})

(defn make-rename-sheet-command
  "Create a rename-sheet command."
  [sheet-id name]
  {:command/name :sheet/rename-sheet
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :name name})

(defn make-delete-sheet-command
  "Create a delete-sheet command."
  [sheet-id]
  {:command/name :sheet/delete-sheet
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id})

;; =============================================================================
;; Factory Functions - Node Commands
;; =============================================================================

(defn make-create-node-command
  "Create a create-node command."
  [sheet-id node-type & {:keys [node-id parent-id index]}]
  (cond-> {:command/name :sheet/create-node
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :type node-type}
    node-id (assoc :node-id node-id)
    parent-id (assoc :parent-id parent-id)
    index (assoc :index index)))

(defn make-move-node-command
  "Create a move-node command."
  [sheet-id node-id new-parent-id index]
  {:command/name :sheet/move-node
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :new-parent-id new-parent-id
   :index index})

(defn make-delete-node-command
  "Create a delete-node command."
  [sheet-id node-id]
  {:command/name :sheet/delete-node
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id})

(defn make-set-node-name-command
  "Create a set-node-name command."
  [sheet-id node-id name]
  {:command/name :sheet/set-node-name
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :name name})

(defn make-set-node-instruction-command
  "Create a set-node-instruction command."
  [sheet-id node-id instruction]
  {:command/name :sheet/set-node-instruction
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :instruction instruction})

(defn make-set-node-io-command
  "Create a set-node-io command."
  [sheet-id node-id reads writes]
  {:command/name :sheet/set-node-io
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :reads reads
   :writes writes})

;; =============================================================================
;; Factory Functions - Blackboard Commands
;; =============================================================================

(defn make-declare-key-command
  "Create a declare-key command."
  [sheet-id key type]
  {:command/name :sheet/declare-key
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :key key
   :type type})

(defn make-set-key-value-command
  "Create a set-key-value command."
  [sheet-id key value]
  {:command/name :sheet/set-key-value
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :key key
   :value value})

(defn make-delete-key-command
  "Create a delete-key command."
  [sheet-id key]
  {:command/name :sheet/delete-key
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :key key})

;; =============================================================================
;; Factory Functions - Execution Commands
;; =============================================================================

(defn make-tick-tree-command
  "Create a tick-tree command."
  [sheet-id & {:keys [tick-id]}]
  (cond-> {:command/name :sheet/tick-tree
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id}
    tick-id (assoc :tick-id tick-id)))

(defn make-tick-node-command
  "Create a tick-node command."
  [sheet-id node-id & {:keys [tick-id overrides]}]
  (cond-> {:command/name :sheet/tick-node
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id}
    tick-id (assoc :tick-id tick-id)
    overrides (assoc :overrides overrides)))

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
