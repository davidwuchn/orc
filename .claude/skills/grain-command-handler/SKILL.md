---
name: grain-command-handler
description: Implement command handlers that emit events using the Grain framework
---

# Grain Command Handler Pattern

## Required Imports

```clojure
(ns my.service.core.commands
  (:require [ai.obney.grain.event-store-v2.interface :as es :refer [->event]]
            [ai.obney.grain.command-processor.interface :refer [defcommand]]
            [ai.obney.grain.time.interface :as time]
            [my.service.interface.read-models :as rm]
            [cognitect.anomalies :as anom]
            [clj-uuid :as uuid]))
```

## Command Template

```clojure
(defcommand :namespace command-name
  "Docstring describing what the command does."
  [{{:keys [entity-id field1 field2]} :command
    {:keys [user-id]} :auth-claims
    :keys [event-store other-dependency]}]

  ;; 1. Validate using read models
  (let [existing (rm/get-entity event-store entity-id)]
    (cond
      ;; Return anomaly for validation failures
      (not existing)
      {::anom/category ::anom/not-found
       ::anom/message "Entity not found"}

      (not (:active existing))
      {::anom/category ::anom/conflict
       ::anom/message "Cannot modify inactive entity"}

      ;; Success: return events
      :else
      {:command-result/events
       [(->event
         {:type :namespace/action-completed
          :tags #{[:entity entity-id]
                  [:user user-id]}
          :body {:entity-id entity-id
                 :field1 field1
                 :field2 field2
                 :modified-by user-id
                 :modified-at (str (time/now))}})]})))
```

## Event Creation

```clojure
(->event
  {:type :namespace/event-type        ;; Keyword, matches schema
   :tags #{[:entity entity-id]        ;; Set of [category id] tuples for filtering
           [:related related-id]}
   :body {:field1 value1              ;; Event payload
          :field2 value2}})
```

## Return Patterns

### Success with Events
```clojure
{:command-result/events [event1 event2]}
```

### Success with Events and Result
```clojure
{:command-result/events [event1]
 :some-key result-value}  ;; Returned to caller in :command/result
```

### Error (Cognitect Anomaly)
```clojure
{::anom/category ::anom/not-found    ;; or ::conflict, ::incorrect, ::forbidden
 ::anom/message "Human readable message"}
```

## Common Anomaly Categories

- `::anom/not-found` - Resource doesn't exist
- `::anom/conflict` - State conflict (duplicate, already exists)
- `::anom/incorrect` - Invalid input data
- `::anom/forbidden` - Not authorized

## Calling Other Commands

```clojure
(require '[ai.obney.grain.command-processor.interface :as command-processor])

(let [result (command-processor/process-command
              (assoc context
                     :command
                     {:command/id (random-uuid)
                      :command/timestamp (time/now)
                      :command/name :other/command-name
                      :field1 value1}))]
  ;; Handle result...
  )
```

## Validation Pattern

```clojure
(defcommand :crm create-contact
  [{{:keys [type-slug field-values]} :command
    :keys [event-store]}]
  (let [contact-type (rm/get-contact-type event-store {:type-slug type-slug})]
    (cond
      (not contact-type)
      {::anom/category ::anom/not-found
       ::anom/message "Contact type not found"}

      (not (:active contact-type))
      {::anom/category ::anom/conflict
       ::anom/message "Cannot create contact of inactive type"}

      (not (validate-required-fields contact-type field-values))
      {::anom/category ::anom/incorrect
       ::anom/message "Required fields missing"}

      :else
      {:command-result/events
       [(->event {...})]})))
```

## Conventions

- Commands modify state by emitting events
- Always validate before emitting events
- Use read models to check current state
- Tag events for efficient future queries
- Return anomalies for all error cases
- Include `modified-by` and `modified-at` in event bodies when relevant

## Reference Files

- `components/user-service/src/ai/obney/workshop/user_service/core/commands.clj` - User auth commands
- `components/crm-service/src/ai/obney/workshop/crm_service/core/commands.clj` - CRM CRUD commands
