---
name: grain-periodic-task
description: Create scheduled background jobs that run on cron schedules
---

# Grain Periodic Task Pattern

Periodic tasks are scheduled background jobs that run on cron or interval schedules. They operate outside the request/response cycle and are used for maintenance, cleanup, reminders, and other time-based operations.

See **Pattern Compendium section 1.4.1** for the authoritative reference.

## Function Signature

```clojure
(defn task-name
  "Description of what this task does."
  [context _time]
  ;; context is the full system context (event-store, cache, tenant-id, etc.)
  ;; _time is provided by the scheduler
  ...)
```

## Task File (`core/periodic_tasks.clj`)

```clojure
(ns ai.obney.workshop.my-service.core.periodic-tasks
  "Scheduled background jobs for my-service."
  (:require [ai.obney.workshop.my-service.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog :as u]))

(defn send-reminders
  "Scan for items due soon and send reminder notifications."
  [context _time]
  (let [items (rm/get-pending-items context)]
    (u/log ::send-reminders :count (count items))
    (doseq [item items]
      (cp/process-command
       (assoc context :command {:command/id (random-uuid)
                                :command/timestamp (time/now)
                                :command/name :my-service/send-reminder
                                :item-id (:id item)})))))

(defn cleanup-expired
  "Remove items that have been expired for more than 30 days."
  [context _time]
  (let [expired (rm/get-expired-items context)]
    (u/log ::cleanup-expired :count (count expired))
    (doseq [item expired]
      (cp/process-command
       (assoc context :command {:command/id (random-uuid)
                                :command/timestamp (time/now)
                                :command/name :my-service/archive-item
                                :item-id (:id item)})))))
```

## Task Registry

Define the registry map that maps task keys to handler functions and schedules:

```clojure
(def periodic-tasks
  {:my-service/send-reminders
   {:handler-fn #'send-reminders
    :schedule {:cron "0 * * * *" :timezone "America/Chicago"}}

   :my-service/cleanup-expired
   {:handler-fn #'cleanup-expired
    :schedule {:cron "0 3 * * *" :timezone "America/Chicago"}}})
```

**Schedule formats:**

| Format | Example | Description |
|--------|---------|-------------|
| Cron | `{:cron "0 * * * *" :timezone "America/Chicago"}` | Standard cron (minute hour day month weekday) |
| Interval | `{:every 30 :duration :seconds}` | Fixed interval |

Common cron patterns:
- `"0 * * * *"` -- every hour on the hour
- `"*/15 * * * *"` -- every 15 minutes
- `"0 3 * * *"` -- daily at 3 AM
- `"0 0 * * 1"` -- weekly on Monday at midnight

**Always use var references (`#'fn`)** for REPL reloading support.

## Multi-Tenant Iteration

Periodic tasks run globally (not scoped to a tenant). When your task needs to process data per-tenant, iterate over active tenants:

```clojure
(defn tenant-maintenance
  "Run maintenance for each active tenant."
  [context _time]
  (let [tenant-ids (get-active-tenant-ids context)]
    (doseq [tid tenant-ids]
      (let [ctx (assoc context :tenant-id tid)]
        ;; Now ctx is scoped to this tenant
        ;; Read models will return tenant-specific data
        (let [items (rm/get-stale-items ctx)]
          (doseq [item items]
            (cp/process-command
             (assoc ctx :command {:command/id (random-uuid)
                                  :command/timestamp (time/now)
                                  :command/name :my-service/refresh-item
                                  :item-id (:id item)}))))))))
```

## CAS for Idempotency

When multiple instances might run the same task simultaneously, use CAS (Compare-And-Swap) to prevent duplicate work:

```clojure
(require '[ai.obney.grain.event-store-v3.interface :as es :refer [->event]])

(defn send-daily-digest
  "Send daily digest -- at most once per user per day."
  [context _time]
  (let [users (rm/get-users-needing-digest context)]
    (doseq [user users]
      (es/append (:event-store context)
                 {:tenant-id (:tenant-id context)
                  :events [(->event {:type :my-service/digest-sent
                                     :tags #{[:user (:user-id user)]}
                                     :body {:user-id (:user-id user)
                                            :date (str (java.time.LocalDate/now))}})]
                  :cas {:types #{:my-service/digest-sent}
                        :tags #{[:user (:user-id user)]}
                        :predicate-fn (fn [existing]
                                        (empty? (into [] existing)))}}))))
```

The CAS predicate checks that no `:my-service/digest-sent` event exists for this user with these tags. If another instance already emitted one, the append silently fails.

## Wiring

### 1. Re-export in interface.clj

```clojure
(ns ai.obney.workshop.my-service.interface
  (:require ...
            [ai.obney.workshop.my-service.core.periodic-tasks :as tasks]))

(def periodic-tasks tasks/periodic-tasks)
```

### 2. Initialize in web-api core

Periodic tasks are typically started alongside the system. In `bases/web-api/src/.../core.clj`, import the interface and start the tasks as part of the Integrant system.

The exact wiring depends on the periodic task infrastructure component. At minimum, you need the interface require (which loads the periodic-tasks namespace) and the task map available to the system initialization.

```clojure
;; In ns require:
[ai.obney.workshop.my-service.interface :as my-service]

;; In system initialization -- the periodic task component reads from service interfaces:
;; my-service/periodic-tasks is available for the scheduler to pick up
```

## Reference Files

- `components/user-service/src/.../core/periodic_tasks.clj` -- Working example (example-periodic-task)
- `components/user-service/src/.../interface.clj` -- How periodic tasks are re-exported
- `docs/pattern-compendium.md` section 1.4.1 -- Authoritative periodic task reference
