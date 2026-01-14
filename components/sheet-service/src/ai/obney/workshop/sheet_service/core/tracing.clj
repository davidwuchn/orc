(ns ai.obney.workshop.sheet-service.core.tracing
  "Langfuse tracing for behavior tree execution.

   Provides observability into workflow execution by sending:
   - Trace events for overall workflow execution
   - Span events for each node execution
   - Generation events for AI node executions"
  (:require [ai.obney.workshop.langfuse.interface :as langfuse]
            [ai.obney.grain.time.interface :as time])
  (:import [java.time Instant ZoneId]
           [java.time.format DateTimeFormatter]))

;; =============================================================================
;; Trace Event Builders
;; =============================================================================

(defn- timestamp-str []
  (str (time/now)))

(defn- millis->iso
  "Convert epoch milliseconds to ISO 8601 timestamp string."
  [epoch-ms]
  (-> (Instant/ofEpochMilli epoch-ms)
      (.atZone (ZoneId/systemDefault))
      (.format DateTimeFormatter/ISO_OFFSET_DATE_TIME)))

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

(defn- make-observation-id
  "Create a stable observation ID for a node (used for parent references)."
  [node-id]
  (str "obs-" node-id))

(defn- make-unique-observation-id
  "Create a unique observation ID for leaf nodes that may execute multiple times."
  [node-id]
  (str "obs-" node-id "-" (random-uuid)))

(def ^:private composite-node-types
  "Node types that can have children and need stable observation IDs."
  #{:sequence :fallback :parallel :map-each})

(defn span-event
  "Create a span-create event for a node execution.
   Composite nodes get stable IDs (for parent refs), leaf nodes get unique IDs."
  [trace-id node-id node-name node-type start-time end-time inputs outputs status error
   & {:keys [parent-observation-id]}]
  (let [;; Composite nodes need stable IDs so children can reference them
        ;; Leaf nodes (condition, code) can have unique IDs since they have no children
        obs-id (if (composite-node-types node-type)
                 (make-observation-id node-id)
                 (make-unique-observation-id node-id))]
    {:id (random-uuid)
     :timestamp (timestamp-str)
     :type "span-create"
     :body (cond-> {:id obs-id
                    :traceId (str trace-id)
                    :name (or node-name (str node-type " node"))
                    :startTime (millis->iso start-time)
                    :endTime (millis->iso end-time)
                    :input inputs
                    :output outputs
                    :metadata {:node-id (str node-id)
                               :node-type (name node-type)
                               :status (name status)}}
             parent-observation-id (assoc :parentObservationId parent-observation-id)
             error (assoc-in [:metadata :error] error))}))

(defn generation-event
  "Create a generation-create event for an AI node execution.
   Always uses unique IDs since AI nodes are leaf nodes with no children."
  [trace-id node-id node-name model start-time end-time inputs outputs status error
   & {:keys [parent-observation-id]}]
  {:id (random-uuid)
   :timestamp (timestamp-str)
   :type "generation-create"
   :body (cond-> {:id (make-unique-observation-id node-id)
                  :traceId (str trace-id)
                  :name (or node-name "AI Generation")
                  :startTime (millis->iso start-time)
                  :endTime (millis->iso end-time)
                  :input inputs
                  :output outputs
                  :model model
                  :metadata {:node-id (str node-id)
                             :status (name status)}}
           parent-observation-id (assoc :parentObservationId parent-observation-id)
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

(defn- reorder-events-for-langfuse
  "Reorder events so parents are sent before children.
   Langfuse requires parent observations to exist before children reference them."
  [events]
  (let [;; Separate trace events from observation events
        trace-start (filter #(and (= "trace-create" (:type %))
                                  (not (get-in % [:body :endTime]))) events)
        trace-end (filter #(and (= "trace-create" (:type %))
                                (get-in % [:body :endTime])) events)
        observations (filter #(#{"span-create" "generation-create"} (:type %)) events)

        ;; Build a map of id -> event for observations
        obs-by-id (into {} (map (fn [e] [(get-in e [:body :id]) e]) observations))

        ;; Topological sort: events with no parent first, then children
        sorted-obs (loop [remaining (set (keys obs-by-id))
                          result []
                          processed #{}]
                     (if (empty? remaining)
                       result
                       ;; Find events whose parent is either nil or already processed
                       (let [ready (filter (fn [id]
                                             (let [parent (get-in obs-by-id [id :body :parentObservationId])]
                                               (or (nil? parent)
                                                   (contains? processed parent))))
                                           remaining)]
                         (if (empty? ready)
                           ;; Circular dependency or missing parent - just add remaining
                           (concat result (map obs-by-id remaining))
                           (recur (apply disj remaining ready)
                                  (concat result (map obs-by-id ready))
                                  (into processed ready))))))]
    (concat trace-start sorted-obs trace-end)))

(defn flush-events!
  "Send all recorded events to Langfuse."
  [trace-ctx]
  (when (:enabled? trace-ctx)
    (let [events @(:events trace-ctx)
          ordered-events (reorder-events-for-langfuse events)]
      (when (seq ordered-events)
        (langfuse/ingestion (:langfuse-client trace-ctx) ordered-events)))))

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
                     start-time end-time inputs outputs status error
                     parent-observation-id]}]
  (let [event (if (= :ai executor)
                (generation-event (:trace-id trace-ctx) node-id node-name model
                                  start-time end-time inputs outputs status error
                                  :parent-observation-id parent-observation-id)
                (span-event (:trace-id trace-ctx) node-id node-name node-type
                            start-time end-time inputs outputs status error
                            :parent-observation-id parent-observation-id))]
    (record-event! trace-ctx event)))

(defn node-observation-id
  "Get the observation ID for a node (for use as parent reference)."
  [node-id]
  (make-observation-id node-id))

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

;; =============================================================================
;; Internal Trace Collection (Always-On for Event Storage)
;; =============================================================================

(defn create-internal-trace
  "Create an internal trace collector for event storage.
   This always collects traces for storing in the event store,
   independent of Langfuse tracing."
  []
  {:trace-id (random-uuid)
   :node-traces (atom [])
   :started-at nil
   :completed-at nil})

(defn start-internal-trace!
  "Mark the start time for internal trace collection."
  [internal-trace]
  (assoc internal-trace :started-at (time/now)))

(defn record-node-trace!
  "Record a node trace entry for internal storage.

   Args:
     internal-trace - The internal trace context
     node-trace - Map with:
       :node-id, :node-name, :node-type
       :parent-id, :path, :child-index
       :status, :started-at, :completed-at, :duration-ms
       :inputs, :outputs, :error"
  [internal-trace node-trace]
  (swap! (:node-traces internal-trace) conj node-trace))

(defn complete-internal-trace!
  "Mark the completion time and return the finalized trace."
  [internal-trace status error]
  (assoc internal-trace
         :completed-at (time/now)
         :status status
         :error error))

(defn get-node-traces
  "Get all collected node traces."
  [internal-trace]
  @(:node-traces internal-trace))
