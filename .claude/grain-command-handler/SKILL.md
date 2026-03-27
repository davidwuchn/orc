---
name: grain-command-handler
description: Implement command handlers that validate state, emit events, and return signals using the Grain framework
---

# Grain Command Handler Pattern

## Required Imports

```clojure
(ns my.service.core.commands
  (:require [ai.obney.grain.event-store-v3.interface :refer [->event]]
            [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [my.service.core.read-models :as rm]
            [cognitect.anomalies :as anom]))
```

## Command Template

```clojure
(defcommand :service command-name
  {:authorized? (fn [ctx] (some? (:auth-claims ctx)))}
  "Docstring describing what the command does."
  [{{:keys [entity-id field1 field2]} :command
    {:keys [user-id]} :auth-claims
    :keys [event-store] :as ctx}]

  ;; 1. Read current state from read models (pass ctx, NOT bare event-store)
  (let [entity (rm/get-entity ctx entity-id)]
    (cond
      ;; Return anomaly for validation failures
      (not entity)
      {::anom/category ::anom/not-found
       ::anom/message "Entity not found"}

      (not (:active entity))
      {::anom/category ::anom/conflict
       ::anom/message "Cannot modify inactive entity"}

      ;; Success: return events + optional signals
      :else
      {:command-result/events
       [(->event
         {:type :service/entity-updated
          :tags #{[:entity entity-id]
                  [:user user-id]}
          :body {:entity-id entity-id
                 :field1 field1
                 :field2 field2
                 :modified-by user-id
                 :modified-at (str (time/now))}})]
       :datastar/signals {:__toast "Saved!"
                          :modalOpen false}})))
```

## Authorization Metadata

The `{:authorized? auth-fn}` metadata map goes between the namespace keyword and the docstring:

```clojure
;; Public (no auth required)
(defcommand :service do-thing
  {:authorized? (constantly true)}
  "..." [ctx] ...)

;; Requires login
(defcommand :service do-thing
  {:authorized? (fn [ctx] (some? (:auth-claims ctx)))}
  "..." [ctx] ...)

;; Custom role check
(defcommand :service do-thing
  {:authorized? (fn [ctx] (= :admin (get-in ctx [:auth-claims :role])))}
  "..." [ctx] ...)
```

## Key Variations

### Success with Events Only

```clojure
{:command-result/events
 [(->event {:type :service/thing-created
            :tags #{[:thing thing-id]}
            :body {:thing-id thing-id :name name}})]}
```

### Success with Events + Datastar Signals

The action handler sends these signals back to the client via SSE patch:

```clojure
{:command-result/events [...]
 :datastar/signals {:success true
                    :modalOpen false
                    :name "" :description ""    ;; clear form fields
                    :__toast "Item created!"}}
```

Special signal prefixes:
- `__toast` -- show a brief notification
- `__redirect` -- navigate to a URL
- `__refresh` -- force page refresh

### Anomaly Return (Error)

Just return the anomaly. The action handler auto-converts it to `{:error "msg"}` signal, which `form-error` displays. Schema validation errors also produce `:fieldErrors` automatically.

```clojure
{::anom/category ::anom/conflict
 ::anom/message "Name already taken."}
;; -> auto-converted to signal: {:error "Name already taken."}
```

### CAS (Optimistic Concurrency)

Prevents races by checking state hasn't changed between read and write:

```clojure
{:command-result/events [...]
 :command-result/cas {:types thing-event-types
                      :predicate-fn (fn [_events]
                                      (= :active (:status (rm/get-thing ctx id))))}}
```

### Calling Other Commands

```clojure
(let [result (cp/process-command
              (assoc ctx :command {:command/id (random-uuid)
                                   :command/timestamp (time/now)
                                   :command/name :other/command-name
                                   :field1 value1}))]
  ;; result is either anomaly or {:command-result/events [...]}
  result)
```

## Common Anomaly Categories

- `::anom/not-found` -- resource does not exist
- `::anom/conflict` -- business rule violation (duplicate, invalid state)
- `::anom/incorrect` -- bad input data
- `::anom/forbidden` -- not authorized for this action

## Conventions

- Commands modify state by emitting events; never write to a database directly
- Always validate via read models before emitting events
- Read model helpers take `ctx` (the full context), not bare `event-store`
- Tag events with `#{[:entity entity-id]}` for efficient filtered reads
- Return anomalies for all error cases; the action handler converts them to signals
- Include `modified-by` and `modified-at` in event bodies when relevant
- Use `{:authorized? auth-fn}` metadata between the namespace keyword and the docstring

## Reference Files

- `components/user-service/src/ai/obney/workshop/user_service/core/commands.clj` -- User auth commands (sign-up, login, password reset)
- `components/crm-service/src/ai/obney/workshop/crm_service/core/commands.clj` -- CRM CRUD commands (contacts, relationships)
