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
  "Create a create-sheet command with defaults.
   If sheet-id is provided, uses that ID (for deterministic UUIDs)."
  [& {:keys [name sheet-id]
      :or {name "Test Sheet"}}]
  (cond-> {:command/name :sheet/create-sheet
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :name name}
    sheet-id (assoc :sheet-id sheet-id)))

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

(defn make-set-node-executor-command
  "Create a set-node-executor command.
   executor-type: :ai, :code, or :tool
   opts: {:model \"...\", :fn \"...\", :tools [...]}"
  [sheet-id node-id executor-type & {:keys [model fn tools]}]
  (cond-> {:command/name :sheet/set-node-executor
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id
           :executor executor-type}
    model (assoc :model model)
    fn (assoc :fn fn)
    tools (assoc :tools tools)))

(defn make-set-node-retry-command
  "Create a set-node-retry command."
  [sheet-id node-id max-attempts backoff-ms]
  {:command/name :sheet/set-node-retry
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :retry {:max-attempts max-attempts
           :backoff-ms backoff-ms}})

(defn make-set-node-check-command
  "Create a set-node-check command."
  [sheet-id node-id check]
  {:command/name :sheet/set-node-check
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :check check})

(defn make-set-parallel-config-command
  "Create a set-parallel-config command."
  [sheet-id node-id & {:keys [success-policy failure-policy]}]
  (cond-> {:command/name :sheet/set-parallel-config
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id}
    success-policy (assoc :success-policy success-policy)
    failure-policy (assoc :failure-policy failure-policy)))

(defn make-set-map-each-config-command
  "Create a set-map-each-config command."
  [sheet-id node-id source-key item-key output-key & {:keys [max-concurrency]}]
  (cond-> {:command/name :sheet/set-map-each-config
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id
           :source-key source-key
           :item-key item-key
           :output-key output-key}
    max-concurrency (assoc :max-concurrency max-concurrency)))

(defn make-set-llm-condition-config-command
  "Create a set-llm-condition-config command."
  [sheet-id node-id instruction reads & {:keys [model]}]
  (cond-> {:command/name :sheet/set-llm-condition-config
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id
           :instruction instruction
           :reads (vec reads)}
    model (assoc :model model)))

;; =============================================================================
;; Factory Functions - Blackboard Commands
;; =============================================================================

(defn make-declare-key-command
  "Create a declare-key command with a Malli schema."
  [sheet-id key schema]
  {:command/name :sheet/declare-key
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :key key
   :schema schema})

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
;; Factory Functions - Versioning Commands
;; =============================================================================

(defn make-publish-version-command
  "Create a publish-version command."
  [sheet-id & {:keys [description]}]
  (cond-> {:command/name :sheet/publish-version
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id}
    description (assoc :description description)))

(defn make-revert-to-version-command
  "Create a revert-to-version command."
  [sheet-id version-number]
  {:command/name :sheet/revert-to-version
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :version-number version-number})

(defn make-restore-stash-command
  "Create a restore-stash command."
  [sheet-id]
  {:command/name :sheet/restore-stash
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id})

(defn make-set-execution-mode-command
  "Create a set-execution-mode command."
  [sheet-id mode]
  {:command/name :sheet/set-execution-mode
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :mode mode})

;; =============================================================================
;; Factory Functions - Versioning Queries
;; =============================================================================

(defn make-version-history-query
  "Create a version-history query."
  [sheet-id]
  {:query/name :sheet/version-history
   :sheet-id sheet-id})

(defn make-get-version-query
  "Create a get-version query."
  [sheet-id version-number]
  {:query/name :sheet/get-version
   :sheet-id sheet-id
   :version-number version-number})

(defn make-get-stash-query
  "Create a get-stash query."
  [sheet-id]
  {:query/name :sheet/get-stash
   :sheet-id sheet-id})

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

;; =============================================================================
;; Factory Functions - Execution Commands
;; =============================================================================

(defn make-execute-version-command
  "Create an execute-version command."
  [sheet-id version-number & {:keys [inputs]}]
  {:command/name :sheet/execute-version
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :version-number version-number
   :inputs (or inputs {})})

(defn make-batch-execute-command
  "Create a batch-execute command."
  [sheet-id inputs-list & {:keys [version-number]}]
  (cond-> {:command/name :sheet/batch-execute
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :inputs-list inputs-list}
    version-number (assoc :version-number version-number)))

;; =============================================================================
;; Factory Functions - Trace Queries
;; =============================================================================

(defn make-get-trace-query
  "Create a get-trace query."
  [trace-id]
  {:query/name :sheet/get-trace
   :trace-id trace-id})

(defn make-get-traces-query
  "Create a get-traces query."
  [sheet-id & {:keys [version-number status node-id since limit]}]
  (cond-> {:query/name :sheet/get-traces
           :sheet-id sheet-id}
    (some? version-number) (assoc :version-number version-number)
    status (assoc :status status)
    node-id (assoc :node-id node-id)
    since (assoc :since since)
    limit (assoc :limit limit)))

(defn make-diff-versions-query
  "Create a diff-versions query."
  [sheet-id from-version to-version]
  {:query/name :sheet/diff-versions
   :sheet-id sheet-id
   :from-version from-version
   :to-version to-version})

(defn make-node-stats-query
  "Create a node-stats query."
  [sheet-id & {:keys [version-number since node-ids]}]
  (cond-> {:query/name :sheet/node-stats
           :sheet-id sheet-id}
    (some? version-number) (assoc :version-number version-number)
    since (assoc :since since)
    (seq node-ids) (assoc :node-ids node-ids)))
