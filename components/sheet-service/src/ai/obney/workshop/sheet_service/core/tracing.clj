(ns ai.obney.workshop.sheet-service.core.tracing
  "Langfuse tracing for behavior tree execution.

   Provides observability into workflow execution by sending:
   - Trace events for overall workflow execution
   - Span events for each node execution
   - Generation events for AI node executions"
  (:require [ai.obney.workshop.langfuse.interface :as langfuse]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Trace Event Builders
;; =============================================================================

(defn- timestamp-str []
  (str (time/now)))

(defn trace-start-event
  "Create a trace-create event for starting a workflow execution."
  [trace-id sheet-name inputs]
  {:id (random-uuid)
   :timestamp (timestamp-str)
   :type "trace-create"
   :body {:id (str trace-id)
          :name (or sheet-name "Workflow Execution")
          :startTime (timestamp-str)
          :input inputs
          :metadata {:type "behavior-tree"}}})

(defn trace-end-event
  "Create a trace-create event for completing a workflow execution."
  [trace-id status outputs duration-ms error]
  {:id (random-uuid)
   :timestamp (timestamp-str)
   :type "trace-create"
   :body (cond-> {:id (str trace-id)
                  :endTime (timestamp-str)
                  :output outputs
                  :metadata {:status (name status)
                             :duration-ms duration-ms}}
           error (assoc-in [:metadata :error] error))})

(defn span-event
  "Create a span-create event for a node execution."
  [trace-id node-id node-name node-type start-time end-time inputs outputs status error]
  {:id (random-uuid)
   :timestamp (timestamp-str)
   :type "span-create"
   :body (cond-> {:id (str node-id "-" (random-uuid))
                  :traceId (str trace-id)
                  :name (or node-name (str node-type " node"))
                  :startTime (str start-time)
                  :endTime (str end-time)
                  :input inputs
                  :output outputs
                  :metadata {:node-id (str node-id)
                             :node-type (name node-type)
                             :status (name status)}}
           error (assoc-in [:metadata :error] error))})

(defn generation-event
  "Create a generation-create event for an AI node execution."
  [trace-id node-id node-name model start-time end-time inputs outputs status error]
  {:id (random-uuid)
   :timestamp (timestamp-str)
   :type "generation-create"
   :body (cond-> {:id (str node-id "-" (random-uuid))
                  :traceId (str trace-id)
                  :name (or node-name "AI Generation")
                  :startTime (str start-time)
                  :endTime (str end-time)
                  :input inputs
                  :output outputs
                  :model model
                  :metadata {:node-id (str node-id)
                             :status (name status)}}
           error (assoc-in [:metadata :error] error))})

;; =============================================================================
;; Trace Context
;; =============================================================================

(defn create-trace-context
  "Create a new trace context for a workflow execution.

   Returns a map with:
   - :trace-id - UUID for the trace
   - :langfuse-client - Client for sending events (may be nil)
   - :events - Atom collecting events to send
   - :enabled? - Whether tracing is enabled"
  [langfuse-client]
  {:trace-id (random-uuid)
   :langfuse-client langfuse-client
   :events (atom [])
   :enabled? (and langfuse-client
                  (:public-key langfuse-client)
                  (:secret-key langfuse-client))})

(defn record-event!
  "Record an event to the trace context."
  [trace-ctx event]
  (when (:enabled? trace-ctx)
    (swap! (:events trace-ctx) conj event)))

(defn flush-events!
  "Send all recorded events to Langfuse."
  [trace-ctx]
  (when (:enabled? trace-ctx)
    (let [events @(:events trace-ctx)]
      (when (seq events)
        (langfuse/ingestion (:langfuse-client trace-ctx) events)))))

;; =============================================================================
;; High-Level Tracing Functions
;; =============================================================================

(defn start-trace!
  "Start a new trace for a workflow execution."
  [trace-ctx sheet-name inputs]
  (record-event! trace-ctx
                 (trace-start-event (:trace-id trace-ctx) sheet-name inputs)))

(defn end-trace!
  "End the current trace with results."
  [trace-ctx status outputs duration-ms error]
  (record-event! trace-ctx
                 (trace-end-event (:trace-id trace-ctx) status outputs duration-ms error))
  (flush-events! trace-ctx))

(defn trace-node!
  "Record a node execution span."
  [trace-ctx {:keys [node-id node-name node-type executor model
                     start-time end-time inputs outputs status error]}]
  (let [event (if (= :ai executor)
                (generation-event (:trace-id trace-ctx) node-id node-name model
                                  start-time end-time inputs outputs status error)
                (span-event (:trace-id trace-ctx) node-id node-name node-type
                            start-time end-time inputs outputs status error))]
    (record-event! trace-ctx event)))

;; =============================================================================
;; Local Trace Collector (for REPL / non-Langfuse use)
;; =============================================================================

(defn create-local-trace
  "Create a local trace collector that doesn't send to Langfuse.
   Useful for REPL testing and debugging."
  []
  {:trace-id (random-uuid)
   :langfuse-client nil
   :events (atom [])
   :enabled? true
   :local? true})

(defn get-trace-summary
  "Get a summary of the local trace for display."
  [trace-ctx]
  (when (:local? trace-ctx)
    (let [events @(:events trace-ctx)
          nodes (->> events
                     (filter #(#{"span-create" "generation-create"} (:type %)))
                     (map (fn [e]
                            (let [body (:body e)]
                              {:name (:name body)
                               :type (:type e)
                               :status (get-in body [:metadata :status])
                               :duration-ms (when-let [start (:startTime body)]
                                              (when-let [end (:endTime body)]
                                                ;; Approximate - would need proper time parsing
                                                nil))}))))]
      {:trace-id (:trace-id trace-ctx)
       :node-count (count nodes)
       :nodes nodes})))
