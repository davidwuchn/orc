---
name: grain-todo-processor
description: Implement event-driven side effects that react to domain events
---

# Grain Todo Processor Pattern

Todo processors are policies that react to domain events and emit follow-up events.

## Required Imports

```clojure
(ns my.service.core.todo-processors
  (:require [my.service.interface.read-models :as rm]
            [ai.obney.grain.event-store-v2.interface :refer [->event]]
            [ai.obney.grain.time.interface :as time]))
```

## Processor Template

```clojure
(defn processor-name
  "Docstring explaining what this processor does and when."
  [{:keys [event event-store]}]
  (let [entity-id (:entity-id event)
        ;; Check current state using read models
        entity (rm/get-entity event-store entity-id)]
    (if (should-take-action? entity)
      ;; Emit follow-up events
      {:result/events
       [(->event
         {:type :namespace/follow-up-action
          :tags #{[:entity entity-id]}
          :body {:entity-id entity-id
                 :triggered-by (:event/type event)
                 :processed-at (str (time/now))}})]}
      ;; No action needed
      {})))
```

## Return Patterns

### Emit Events
```clojure
{:result/events [event1 event2]}
```

### No Action
```clojure
{}
```

## Registry Structure

```clojure
(def todo-processors
  {:namespace/processor-name
   {:handler-fn #'processor-function
    :topics [:triggering/event-type]}

   :namespace/another-processor
   {:handler-fn #'another-function
    :topics [:first/event-type :second/event-type]}})
```

## Common Use Cases

### Ensure Default Value

```clojure
(defn ensure-attribution
  "Record default attribution if none exists."
  [{:keys [event event-store]}]
  (let [contact-id (:contact-id event)
        existing (rm/get-contact event-store contact-id)]
    (if (:attribution existing)
      {}  ;; Already has attribution
      {:result/events
       [(->event
         {:type :crm/attribution-recorded
          :tags #{[:contact contact-id]}
          :body {:contact-id contact-id
                 :attribution {:source :manual_entry
                               :recorded-at (str (time/now))}}})]})))
```

### Duplicate Detection

```clojure
(defn check-for-duplicates
  "Check for duplicates when contact is created."
  [{:keys [event event-store]}]
  (let [contact-id (:contact-id event)
        email (normalize-email (get-in event [:field-values :email]))
        existing (rm/get-contacts-by-email event-store email)]
    (if (seq (remove #(= contact-id (:id %)) existing))
      {:result/events
       [(->event
         {:type :crm/duplicate-detected
          :tags #{[:contact contact-id]}
          :body {:contact-id contact-id
                 :match-type :email
                 :match-value email}})]}
      {})))
```

### Cascade Operations

```clojure
(defn end-relationships-on-archive
  "End all relationships when contact is archived."
  [{:keys [event event-store]}]
  (let [contact-id (:contact-id event)
        rels (rm/get-relationships-for-contact event-store contact-id)
        active-rels (filter #(nil? (:end-date %)) rels)]
    (if (seq active-rels)
      {:result/events
       (mapv (fn [rel]
               (->event
                {:type :crm/relationship-ended
                 :tags #{[:relationship (:id rel)]}
                 :body {:relationship-id (:id rel)
                        :end-date (str (java.time.LocalDate/now))
                        :reason "Contact archived"}}))
             active-rels)}
      {})))
```

### Transform Source Data

```clojure
(defn record-lead-attribution
  "Map lead form type to attribution source."
  [{:keys [event]}]
  (let [contact-id (:contact-id event)
        source (case (:form-type event)
                 :application :application_form
                 :intake :intake_form
                 :interest :interest_form
                 :manual_entry)]
    {:result/events
     [(->event
       {:type :crm/attribution-recorded
        :tags #{[:contact contact-id]}
        :body {:contact-id contact-id
               :attribution {:source source
                             :form-id (:form-id event)}}})]}))
```

## Complete Registry Example

```clojure
(def todo-processors
  {:crm/ensure-attribution
   {:handler-fn #'ensure-attribution
    :topics [:crm/contact-created]}

   :crm/check-for-duplicates
   {:handler-fn #'check-for-duplicates
    :topics [:crm/contact-created]}

   :crm/record-lead-attribution
   {:handler-fn #'record-lead-attribution
    :topics [:crm/lead-captured]}

   :crm/end-relationships-on-archive
   {:handler-fn #'end-relationships-on-archive
    :topics [:crm/contact-archived]}

   :crm/transfer-on-merge
   {:handler-fn #'transfer-relationships-on-merge
    :topics [:crm/contact-merged]}})
```

## Conventions

- Processors receive `{:keys [event event-store]}`
- Use read models to check current state before acting
- Return `{:result/events [...]}` or `{}` (empty map)
- One processor can listen to multiple topics
- Multiple processors can listen to the same topic
- Normalize data before comparison (email lowercase, phone digits only)
- Include reason/context in generated events for audit trail

## Reference Files

- `components/user-service/src/ai/obney/workshop/user_service/core/todo_processors.clj` - Email notifications
- `components/crm-service/src/ai/obney/workshop/crm_service/core/todo_processors.clj` - Attribution & duplicates
