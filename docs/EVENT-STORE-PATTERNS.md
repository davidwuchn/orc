# Event Store Patterns

A guide to working with Grain's event store for querying workflow executions, building training data, and debugging.

## Table of Contents

1. [Overview](#overview)
2. [Event Structure](#event-structure)
3. [Reading Events](#reading-events)
4. [Sheet-Service Events](#orc-service-events)
5. [Read Models](#read-models)
6. [Query Patterns for GEPA](#query-patterns-for-gepa)
7. [Testing with In-Memory Event Store](#testing-with-in-memory-event-store)

---

## Overview

The Grain event store provides:

- **Immutable Event Log** - Every state change is recorded as an event
- **Tag-Based Filtering** - Efficient queries using semantic tags
- **Read Model Projections** - Derive queryable state from events
- **In-Memory Testing** - Fast tests without database dependencies

```
┌─────────────────────────────────────────────────────────────────┐
│                    Event Store Architecture                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   Commands ──► Events ──► Event Store ──► Read Models           │
│                              │                │                 │
│                              │                ▼                 │
│                              │          Queryable State         │
│                              │                                  │
│                              ▼                                  │
│                      Todo Processors                            │
│                      (Side Effects)                             │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Event Structure

Every event has a standard structure:

```clojure
{:event/id #uuid "abc123..."         ;; Unique event ID
 :event/type :sheet/node-execution-completed  ;; Event type keyword
 :event/created-at #inst "2025-01-18T..."     ;; Timestamp
 :event/tags #{[:sheet sheet-id]              ;; Semantic tags for filtering
               [:node node-id]}
 :body {:sheet-id sheet-id                    ;; Event-specific payload
        :node-id node-id
        :status :success
        :duration-ms 423}}
```

### Tags

Tags enable efficient filtering. Common patterns:

| Tag Pattern | Usage |
|-------------|-------|
| `[:sheet sheet-id]` | All events for a workflow |
| `[:node node-id]` | All events for a specific node |
| `[:trace trace-id]` | All events for an execution trace |
| `[:user user-id]` | All events by a user |
| `[:entity entity-id]` | Generic entity reference |

### Creating Events (in Commands)

```clojure
(require '[ai.obney.grain.commands.interface :refer [->event]])

(->event {:type :sheet/node-execution-completed
          :tags #{[:sheet sheet-id] [:node node-id]}
          :body {:sheet-id sheet-id
                 :node-id node-id
                 :status :success
                 :duration-ms 423}})
```

---

## Reading State: Use Read Models (Not Direct Event Reads)

### Preferred: `rmp/project` via Read Models

Most queries should go through registered read models (`defreadmodel` + `rmp/project`), which provide L1/L2 caching and partitioned projections:

```clojure
(require '[ai.obney.grain.read-model-processor-v2.interface :as rmp])

;; Get all nodes for a sheet (uses cached, partitioned projection)
(rmp/project ctx :sheet/nodes {:partition-key sheet-id})

;; Get all traces for a sheet
(rmp/project ctx :sheet/traces {:partition-key sheet-id})

;; Full projection (all sheets)
(rmp/project ctx :sheet/sheets)
```

Each service exposes helper functions that wrap `rmp/project`:
```clojure
(sheet/get-nodes-for-sheet ctx sheet-id)
(sheet/get-traces-for-sheet ctx sheet-id)
(ontology/get-concepts ctx)
```

### Direct Event Store Access (Rare)

Use `es/read` only for audit trails, cross-aggregate queries, or custom event analysis. **`es/read` returns a reducible collection, NOT a sequence** — you must materialize with `(into [] ...)`:

```clojure
(require '[ai.obney.grain.event-store-v3.interface :as es])

;; WRONG - will throw UnsupportedOperationException
(count (es/read event-store {:types #{:sheet/execution-traced}}))

;; CORRECT - materialize first
(count (into [] (es/read event-store {:types #{:sheet/execution-traced}})))
```

### Query Options

```clojure
(into [] (es/read event-store
           {:types #{:type1 :type2}     ;; Filter by event types
            :tags #{[:sheet sheet-id]}  ;; Filter by tags (AND logic)
            :limit 100                   ;; Max events to return
            :order :desc                 ;; :asc (default) or :desc
            :since #inst "2025-01-01"   ;; Events after this time
            :until #inst "2025-01-31"})) ;; Events before this time
```

### Query Examples

#### Get All Events for a Sheet

```clojure
(into [] (es/read event-store
           {:tags #{[:sheet sheet-id]}}))
```

#### Get Failed Executions

```clojure
(->> (es/read event-store
       {:types #{:sheet/tree-tick-completed}
        :tags #{[:sheet sheet-id]}})
     (into [])
     (filter #(= :failure (get-in % [:body :root-status]))))
```

#### Get Recent Node Executions

```clojure
(into [] (es/read event-store
           {:types #{:sheet/node-execution-completed}
            :tags #{[:sheet sheet-id] [:node node-id]}
            :limit 50
            :order :desc}))
```

#### Get Traces in Time Range

```clojure
(into [] (es/read event-store
           {:types #{:sheet/trace-assembled}
            :tags #{[:sheet sheet-id]}
            :since #inst "2025-01-18T00:00:00Z"
            :until #inst "2025-01-19T00:00:00Z"}))
```

---

## Sheet-Service Events

Complete reference of all `:sheet/*` event types.

### Workflow Definition Events

| Event Type | When | Body Fields |
|------------|------|-------------|
| `:sheet/sheet-created` | Workflow created | `:sheet-id`, `:name`, `:created-at` |
| `:sheet/node-created` | Node added | `:sheet-id`, `:node-id`, `:type`, `:parent-id` |
| `:sheet/node-name-set` | Node named | `:sheet-id`, `:node-id`, `:name` |
| `:sheet/node-io-set` | I/O configured | `:sheet-id`, `:node-id`, `:reads`, `:writes` |
| `:sheet/node-executor-set` | Executor configured | `:sheet-id`, `:node-id`, `:executor`, `:params` |
| `:sheet/key-declared` | Blackboard key added | `:sheet-id`, `:key-name`, `:schema` |

### Execution Events

| Event Type | When | Body Fields |
|------------|------|-------------|
| `:sheet/tree-tick-started` | Execution begins | `:sheet-id`, `:tick-id`, `:started-at` |
| `:sheet/node-execution-started` | Node begins | `:sheet-id`, `:node-id`, `:tick-id`, `:started-at` |
| `:sheet/node-execution-completed` | Node ends | `:sheet-id`, `:node-id`, `:tick-id`, `:status`, `:duration-ms`, `:completed-at` |
| `:sheet/tree-tick-completed` | Execution ends | `:sheet-id`, `:tick-id`, `:root-status`, `:duration-ms`, `:completed-at` |

### Trace Events

| Event Type | When | Body Fields |
|------------|------|-------------|
| `:sheet/trace-assembled` | Trace ready | `:trace-id`, `:sheet-id`, `:status`, `:input-snapshot`, `:output-snapshot`, `:node-traces`, `:duration-ms` |

### Example: Node Execution Completed Event

```clojure
{:event/id #uuid "..."
 :event/type :sheet/node-execution-completed
 :event/created-at #inst "2025-01-18T12:00:00Z"
 :event/tags #{[:sheet #uuid "sheet-123"]
               [:node #uuid "node-456"]
               [:tick #uuid "tick-789"]}
 :body {:sheet-id #uuid "sheet-123"
        :node-id #uuid "node-456"
        :tick-id #uuid "tick-789"
        :status :success
        :duration-ms 423
        :started-at #inst "2025-01-18T11:59:59.577Z"
        :completed-at #inst "2025-01-18T12:00:00Z"}}
```

### Example: Trace Assembled Event

```clojure
{:event/id #uuid "..."
 :event/type :sheet/trace-assembled
 :event/created-at #inst "2025-01-18T12:00:01Z"
 :event/tags #{[:sheet #uuid "sheet-123"]
               [:trace #uuid "trace-abc"]}
 :body {:trace-id #uuid "trace-abc"
        :sheet-id #uuid "sheet-123"
        :status :success
        :duration-ms 2500
        :input-snapshot {"question" "What is 2+2?"}
        :output-snapshot {"answer" "4"}
        :node-traces [{:node-id #uuid "node-456"
                       :node-name "answer"
                       :node-type :leaf
                       :status :success
                       :duration-ms 423
                       :inputs {"question" "What is 2+2?"}
                       :outputs {"answer" "4"}}]}}
```

---

## Read Models

Read models project events into queryable state.

### Rolling Metrics Pattern

Track node performance over a sliding window:

```clojure
(defn rolling-metrics
  "Reduces node-execution-completed events into rolling window stats."
  [state events]
  (reduce
    (fn [acc {:keys [body]}]
      (let [{:keys [sheet-id node-id status duration-ms]} body
            key [sheet-id node-id]]
        (update acc key
          (fn [metrics]
            (let [metrics (or metrics {:executions []})]
              (update metrics :executions
                #(take-last 100 (conj % {:status status
                                          :duration-ms duration-ms}))))))))
    state
    (filter #(= (:event/type %) :sheet/node-execution-completed) events)))
```

### Using Read Models

```clojure
(require '[ai.obney.orc.orc-service.interface :as sheet])

;; Get metrics for a specific node
(sheet/get-node-rolling-metrics event-store sheet-id node-id)
;; => {:execution-count 150
;;     :success-rate 0.967
;;     :avg-duration-ms 423.5
;;     :recent-trend :stable}

;; Get metrics for all nodes in a sheet
(sheet/get-tree-rolling-metrics event-store sheet-id)
;; => {:sheet-id ...
;;     :nodes [{:node-id ... :success-rate ...}]
;;     :total-executions 500}
```

---

## Query Patterns for GEPA

### Building Training Data from Traces

```clojure
(defn traces-to-trainset
  "Convert stored traces to GEPA trainset format."
  [event-store sheet-id & {:keys [limit] :or {limit 100}}]
  (let [trace-events (into [] (es/read event-store
                                {:types #{:sheet/trace-assembled}
                                 :tags #{[:sheet sheet-id]}
                                 :limit limit
                                 :order :desc}))]
    (mapv (fn [{:keys [body]}]
            {:inputs (:input-snapshot body)
             :outputs (:output-snapshot body)
             :status (:status body)})
          trace-events)))
```

### Finding Low-Scoring Executions

```clojure
(defn low-scoring-traces
  "Get traces with evaluation scores below threshold."
  [event-store sheet-id threshold]
  (let [eval-events (into [] (es/read event-store
                               {:types #{:evaluation/completed}
                                :tags #{[:sheet sheet-id]}}))]
    (->> eval-events
         (filter #(< (get-in % [:body :score]) threshold))
         (mapv :body))))
```

### Aggregating Node Performance

```clojure
(defn node-failure-analysis
  "Analyze which nodes fail most frequently."
  [event-store sheet-id]
  (let [events (into [] (es/read event-store
                          {:types #{:sheet/node-execution-completed}
                           :tags #{[:sheet sheet-id]}}))]
    (->> events
         (group-by #(get-in % [:body :node-id]))
         (map (fn [[node-id evts]]
                {:node-id node-id
                 :total (count evts)
                 :failures (count (filter #(= :failure (get-in % [:body :status])) evts))
                 :failure-rate (double (/ (count (filter #(= :failure (get-in % [:body :status])) evts))
                                          (count evts)))}))
         (sort-by :failure-rate >))))
```

### Getting Execution History for a Node

```clojure
(defn node-execution-history
  "Get detailed execution history for a specific node."
  [event-store sheet-id node-id & {:keys [limit] :or {limit 50}}]
  (let [events (into [] (es/read event-store
                          {:types #{:sheet/node-execution-completed}
                           :tags #{[:sheet sheet-id] [:node node-id]}
                           :limit limit
                           :order :desc}))]
    (mapv (fn [{:keys [body event/created-at]}]
            {:timestamp created-at
             :status (:status body)
             :duration-ms (:duration-ms body)})
          events)))
```

---

## Testing with In-Memory Event Store

### Test Context Setup

```clojure
(ns my-app.test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.event-store-v2.interface :as es]))

(deftest my-test
  (testing "event store integration"
    (h/with-async-test-context [ctx]
      (let [event-store (:event-store ctx)]
        ;; Your test code here
        ))))
```

### Verifying Event Emission

```clojure
(deftest events-emitted-test
  (h/with-async-test-context [ctx]
    (let [event-store (:event-store ctx)
          sheet-id (create-and-execute-workflow! ctx)]

      ;; IMPORTANT: Materialize with into []
      (let [tick-events (into [] (es/read event-store
                                   {:types #{:sheet/tree-tick-started
                                            :sheet/tree-tick-completed}
                                    :tags #{[:sheet sheet-id]}}))]

        (is (>= (count tick-events) 2))

        (let [event-types (set (map :event/type tick-events))]
          (is (contains? event-types :sheet/tree-tick-started))
          (is (contains? event-types :sheet/tree-tick-completed)))))))
```

### Test Helpers

The `test-helpers` namespace provides factory functions:

```clojure
;; Create sheet
(h/run-and-apply! ctx (h/make-create-sheet-command :name "Test"))

;; Create node
(h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf))

;; Set node I/O
(h/run-and-apply! ctx (h/make-set-node-io-command sheet-id node-id ["input"] ["output"]))

;; Set executor
(h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id node-id :code
                        :fn "my-app.test/mock-executor"))

;; Declare blackboard key
(h/run-and-apply! ctx (h/make-declare-key-command sheet-id "key-name" :string))

;; Query
(h/run-query ctx (h/make-get-trace-query trace-id))
```

### Full Integration Test Example

```clojure
(deftest full-integration-test
  (h/with-async-test-context [ctx]
    (let [event-store (:event-store ctx)

          ;; Create workflow
          sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Test"))
          sheet-id (-> sheet-result :command-result/events first :sheet-id)

          ;; Add node
          node-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf))
          node-id (-> node-result :command-result/events first :node-id)

          ;; Configure
          _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id "input" :string))
          _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id "output" :string))
          _ (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id node-id ["input"] ["output"]))
          _ (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id node-id :code
                                    :fn "my-app.test/echo-executor"))

          ;; Execute
          result (sheet/execute ctx sheet-id {"input" "test-value"})]

      ;; Verify execution
      (is (= :success (:status result)))
      (is (= "test-value" (get-in result [:outputs "output" "input"])))

      ;; Verify events
      (let [events (into [] (es/read event-store
                              {:types #{:sheet/node-execution-completed}
                               :tags #{[:sheet sheet-id]}}))]
        (is (= 1 (count events)))
        (is (= :success (get-in (first events) [:body :status])))))))
```

---

## Related Documentation

- [SHEET-SERVICE-GUIDE.md](./SHEET-SERVICE-GUIDE.md) - Sheet service overview
- [ARCHITECTURE.md](./ARCHITECTURE.md) - System architecture
- [GEPA-GUIDE.md](./GEPA-GUIDE.md) - GEPA prompt optimization
