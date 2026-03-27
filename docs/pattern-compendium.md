# Pattern Compendium

A complete guide to the frontend and backend patterns used in this project. Written for AI agents and developers who need to understand how things work and build new features that fit naturally.

---

## Architecture Overview

This is a **Polylith monolith** powered by **Grain** (event-sourced CQRS framework) with **Datastar** for server-rendered reactive UIs. No SPA, no JavaScript framework — the server renders HTML via Hiccup and streams updates over SSE.

**Stack:** Clojure + Grain + Pedestal + Datastar + Tailwind CSS + DaisyUI

**Key directories:**
- `bases/web-api/` — HTTP entry point, Integrant system, interceptors, route wiring
- `components/{service}/` — Domain services (Polylith bricks)
- `components/ui/` — Shared UI component library
- `css/main.css` — Tailwind input with DaisyUI theme

### When you're unsure — read the source

This compendium covers patterns and conventions, but the **Grain framework** and **Datastar component** are the real source of truth for how things work at the protocol level. When something is unclear:

1. **Grain source** — Ask the user where the grain repo lives locally (typically `../grain/`). Key files:
   - `components/command-processor-v2/` — how commands are processed, CAS, event storage
   - `components/read-model-processor-v2/` — how projections, L1/L2 caching, and partitioning work
   - `components/event-store-v3/` — how append/read work, tenant isolation, schema validation
   - `components/datastar/src/.../core.clj` — how SSE streaming, action handlers, signal parsing, POST streams, and route generation work. **This is the single most important file to read when debugging Datastar behavior.**
   - `components/todo-processor-v2/` — how event-driven processors subscribe and dispatch

2. **Datastar JS** — The client-side behavior (signal expressions, `@post`, `@get`, DOM morphing) is documented at [data-star.dev](https://data-star.dev). Read the grain datastar component source to understand how the server side generates the HTML and SSE events that drive it.

3. **nREPL** — Use `/nrepl-connect` to connect to the running system. You can inspect state, evaluate queries, and debug live:
   ```clojure
   ;; Check what's in a read model:
   (rmp/project @(resolve 'ai.obney.orc.web-api.core/app) :service/items)

   ;; Run a command manually:
   (cp/process-command (assoc context :command {...}))
   ```

**Do not guess at framework behavior.** Read the source.

---

## Part 1: Backend Patterns

### 1.1 Command Handlers

Commands are defined with `defcommand` from `grain.command-processor-v2.interface`. Each lives in `{service}/core/commands.clj`.

**Structure:**
```clojure
(defcommand :service-name command-name
  {:authorized? auth-fn}
  "Docstring."
  [{{:keys [field1 field2]} :command
    :keys [event-store] :as ctx}]
  ;; 1. Read current state from read models
  ;; 2. Validate (return anomaly on failure)
  ;; 3. Emit events on success
  )
```

**Authorization:** The `:authorized?` fn receives the full context and returns boolean:
- `(constantly true)` — public
- `(fn [ctx] (some? (:auth-claims ctx)))` — requires login
- Custom role checks on `(:auth-claims ctx)`

**Validation pattern** — use `cond` with early anomaly returns:
```clojure
(cond
  (not entity)
  {::anom/category ::anom/not-found
   ::anom/message "Not found."}

  (not (:active entity))
  {::anom/category ::anom/conflict
   ::anom/message "Entity is not active."}

  :else
  {:command-result/events [...]})
```

**Anomaly categories:** `::anom/not-found`, `::anom/conflict` (business rule), `::anom/forbidden`, `::anom/incorrect` (bad input)

**Event emission:**
```clojure
{:command-result/events
 [(->event {:type :service/thing-happened
            :tags #{[:entity entity-id]}
            :body {:entity-id entity-id
                   :field1 value1}})]}
```

**Datastar signals** — commands can return UI signals:
```clojure
{:command-result/events [...]
 :datastar/signals {:__toast "Saved!"
                    :__redirect "/somewhere"
                    :__refresh true
                    :modalOpen false}}
```

**CAS (optimistic concurrency):**
```clojure
{:command-result/events [...]
 :command-result/cas {:types event-types
                      :predicate-fn (fn [_events]
                                      (= expected-status (:status (rm/get-thing ctx id))))}}
```

### 1.2 Query Handlers

Queries are defined with `defquery` from `grain.query-processor.interface`. Each lives in `{service}/core/queries.clj`.

**Static page query** (renders once, no live updates):
```clojure
(defquery :service-name static-page
  {:authorized? auth-fn
   :datastar/path "/url-path"
   :datastar/title "Page Title"
   :datastar/fps 0}
  "Docstring."
  [{:keys [auth-claims flash query] :as ctx}]
  {:query/result {:some "data"}
   :datastar/hiccup (views/some-page {:claims auth-claims
                                      :error (:error flash)})})
```

**Event-driven page query** (re-renders when domain events arrive):
```clojure
(defquery :service-name live-page
  {:authorized? auth-fn
   :datastar/path "/url-path"
   :datastar/title "Page Title"
   :grain/read-models {:service/read-model-name 1}
   :datastar/debounce-ms 50}
  "Docstring."
  [{:keys [auth-claims] :as ctx}]
  {:query/result {:items (rm/get-all-items ctx)}
   :datastar/hiccup (views/live-page {:claims auth-claims
                                      :items (rm/get-all-items ctx)})})
```

**Polling page query** (re-renders on a timer):
```clojure
(defquery :service-name polling-page
  {:authorized? auth-fn
   :datastar/path "/url-path"
   :datastar/title "Page Title"
   :datastar/fps 2}
  "Re-renders twice per second. Use for data not driven by domain events (e.g. external API status)."
  [ctx]
  {:query/result {:status (fetch-external-status)}
   :datastar/hiccup (views/status-page {:status (fetch-external-status)})})
```

**Three rendering modes** — choose exactly one:

| Mode | Metadata | When to use |
|------|----------|-------------|
| **One-shot** | `:datastar/fps 0` | Static pages, auth forms. Renders once, SSE closes. |
| **Polling** | `:datastar/fps N` (N > 0) | Pages showing external/non-event data. Re-renders N times/sec. |
| **Event-driven** | `:grain/read-models {...}` | Management pages showing domain data. Re-renders when relevant events fire. |

Do NOT combine `:datastar/fps` with `:grain/read-models` — they are mutually exclusive strategies. Event-driven is the default choice for any page that displays domain entities.

**Other metadata fields:**
- `:datastar/path` — URL this query serves (auto-generates GET + SSE routes)
- `:datastar/title` — HTML `<title>`
- `:datastar/debounce-ms` — debounce for rapid event bursts before re-rendering (default 50, use with `:grain/read-models`)

### 1.2.1 POST Streams and Signal-Driven Re-renders

By default, Datastar pages use `@get` to open SSE connections and pass signals as URL query params. This has two problems: (1) sensitive data ends up in URLs/logs, and (2) changing a signal (like a page number) opens a **new** SSE connection instead of updating the existing one.

The **POST stream** pattern solves both by using `@post` for the initial SSE connection and all subsequent signal updates.

**How it works:**

1. The **shim page** boots Datastar with `@post('/path/stream')` instead of `@get(...)`. This is configured via `:stream-method "post"` in the route overrides.

2. The shim emits a `dsNonce` signal (a UUID unique to this page load). Every `@post` includes it in the JSON body automatically.

3. **Initial load**: `@post` opens an SSE connection. The server registers the stream in an atom keyed by `[user-id, query-name, nonce]`, storing a `context-atom` and a `signal-ch`.

4. **Signal change** (e.g. user clicks "Next Page"): Datastar sends a new `@post` with the full signal set as JSON body. The server:
   - Finds the existing stream by `[user-id, query-name, nonce]`
   - Updates the `context-atom` with new signals via `swap!`
   - Puts `:signal-update` on the `signal-ch` to wake the event loop
   - Returns an **empty SSE** that closes immediately (no new persistent connection)

5. The **event loop** (running on the original SSE) wakes up, debounces, re-executes the query with the updated context atom, and patches the DOM if the result changed.

**Configuring POST streams in web-api core:**
```clojure
;; In route overrides, set stream-method "post" for pages with interactive signals:
(reduce (fn [o query-key]
          (update-in o [query-key :datastar/shim-opts]
                     merge {:stream-method "post"}))
        overrides
        [:service/patients-page
         :service/calendar-page
         :service/conversations-page])
```

**Query handler — reading signals from POST body:**
```clojure
(defquery :service patients-page
  {:authorized? auth-fn
   :datastar/path "/patients"
   :datastar/title "Patients"
   :grain/read-models {:service/patients 1}
   :datastar/debounce-ms 50}
  "Patients page with pagination via POST signals."
  [{{:keys [page pageSize]} :query :as ctx}]
  (let [page (max 1 (or page 1))
        page-size (max 1 (or pageSize 25))
        all-patients (rm/get-active-patients ctx)
        total (count all-patients)
        patients (->> all-patients (drop (* (dec page) page-size)) (take page-size) vec)]
    {:query/result {:patients patients :page page :total total}  ;; MUST include page!
     :datastar/hiccup (views/patients-page {:patients patients
                                            :page page
                                            :page-size page-size
                                            :total total})}))
```

**View — declaring signals and pagination controls:**
```clojure
(defn patients-page [{:keys [patients page page-size total]}]
  [:div#app {:data-signals (str "{'page': " page ", 'pageSize': " page-size "}")}
   ;; ... table of patients ...
   [:div {:class "flex gap-2 mt-4"}
    [:button {:data-on-click "$page = Math.max(1, $page - 1)"
              :class "btn btn-sm"
              :disabled (= page 1)}
     "Previous"]
    [:span {:class "text-sm self-center"} (str "Page " page " of " (Math/ceil (/ total page-size)))]
    [:button {:data-on-click (str "$page = Math.min(" (Math/ceil (/ total page-size)) ", $page + 1)")
              :class "btn btn-sm"}
     "Next"]]])
```

When the user clicks "Next", Datastar updates `$page` and immediately fires `@post('/patients/stream')` with `{"page": 2, "pageSize": 25, "dsNonce": "..."}`. The existing SSE re-renders with page 2 data — no new connection, no URL params.

**Critical requirement:** The `:query/result` map must include the signal values (like `:page`). The event loop uses structural comparison of `:query/result` to detect changes — if the result doesn't change, no SSE update is sent.

**When to use POST streams:**
- Pages with pagination, filtering, or search signals
- Any page where signal data shouldn't appear in URLs
- Pages where users interact frequently (avoid SSE connection churn)

**When GET streams are fine:**
- Static pages (`:datastar/fps 0`)
- Pages with no interactive signals
- Fragment queries (typeahead search that renders once)

**Reading from read models:**
```clojure
(let [items (vals (rmp/project ctx :service/items))
      item (get (rmp/project ctx :service/items) item-id)]
  ...)
```

**Partitioned read model access:**
```clojure
(rmp/project ctx :service/items-by-date {:partition-key [location-id date-str]})
```

**Flash context** — data from `flash-redirect` is available as `:flash` in the context:
```clojure
[{:keys [flash] :as ctx}]
(let [error (:error flash)
      success (:success flash)]
  ...)
```

### 1.3 Read Models

Read models are defined with `defreadmodel` from `grain.read-model-processor-v2.interface`. Each lives in `{service}/core/read_models.clj`.

**Pattern:**
```clojure
;; 1. Define event type set
(def thing-event-types
  #{:service/thing-created
    :service/thing-updated
    :service/thing-deleted})

;; 2. Define reducer multimethod
(defmulti things* (fn [_state event] (:event/type event)))

(defmethod things* :service/thing-created
  [state {:keys [thing-id name]}]
  (assoc state thing-id {:id thing-id :name name :active true}))

(defmethod things* :service/thing-updated
  [state {:keys [thing-id changes]}]
  (update state thing-id merge changes))

(defmethod things* :service/thing-deleted
  [state {:keys [thing-id]}]
  (dissoc state thing-id))

(defmethod things* :default [state _] state)

;; 3. Register
(defreadmodel :service things
  {:events thing-event-types :version 1}
  [state event] (things* state event))
```

State is a flat map: `{entity-id entity-data, ...}`. Increment `:version` when the reducer logic changes to bust the cache.

**Partitioned read models** (for large datasets):
```clojure
(defreadmodel :service things-by-category
  {:events thing-event-types
   :version 1
   :partition-fn (fn [entity] (:category-id entity))
   :entity-id-fn :id}
  [state event] (things* state event))
```

**Query helpers** — provide convenience functions for common lookups:
```clojure
(defn get-thing [ctx thing-id]
  (get (rmp/project ctx :service/things) thing-id))

(defn get-active-things [ctx]
  (->> (vals (rmp/project ctx :service/things))
       (filter :active)))
```

### 1.4 Todo Processors

Event-driven side effects. Defined in `{service}/core/todo_processors.clj`.

**Pattern:**
```clojure
(defn send-notification
  [{{:keys [user-id message]} :event :as context}]
  (cp/process-command
   (assoc context :command {:command/id (random-uuid)
                            :command/timestamp (time/now)
                            :command/name :notifications/send
                            :user-id user-id
                            :message message})))

(def todo-processors
  {:service/send-notification
   {:handler-fn #'send-notification
    :topics [:service/thing-happened]}})
```

- Receive the triggering `:event` in context
- Typically dispatch a follow-up command via `cp/process-command`
- Use var references (`#'fn`) for REPL reloading
- `:topics` — event types that trigger this processor

### 1.4.1 Periodic Tasks

Scheduled background jobs. Defined in `{service}/core/periodic_tasks.clj`.

**Pattern:**
```clojure
(defn send-reminders
  "Scan for items due soon and send notifications."
  [context _time]
  (let [items (rm/get-pending-items context)]
    (doseq [item items]
      (cp/process-command
       (assoc context :command {:command/id (random-uuid)
                                :command/timestamp (time/now)
                                :command/name :service/send-reminder
                                :item-id (:id item)})))))

(def periodic-tasks
  {:service/send-reminders
   {:handler-fn #'send-reminders
    :schedule {:cron "0 * * * *" :timezone "America/Chicago"}}})
```

- Function signature: `[context time]` — context is the full system context, time is provided by the scheduler
- `:schedule` accepts either `{:every N :duration :seconds}` or `{:cron "..." :timezone "..."}`
- Use var references (`#'fn`) for REPL reloading
- Wire into web-api via the interface, same as todo-processors

**Multi-tenant iteration** — periodic tasks run globally, so iterate over tenants:
```clojure
(defn my-task [context _time]
  (let [tenant-ids (get-active-tenant-ids context)]
    (doseq [tid tenant-ids]
      (let [ctx (assoc context :tenant-id tid)]
        ;; ... do work scoped to this tenant
        ))))
```

**CAS for idempotency** — when multiple instances run the same task, use CAS to prevent duplicate work:
```clojure
(es/append (:event-store ctx)
           {:tenant-id (:tenant-id ctx)
            :events [(->event {:type :service/reminder-sent :tags #{[:item item-id]} :body {...}})]
            :cas {:types #{:service/reminder-sent}
                  :tags #{[:item item-id]}
                  :predicate-fn (fn [existing] (empty? (into [] existing)))}})
```

### 1.5 Schemas

Defined with `defschemas` from `grain.schema-util.interface` in `{service}/interface/schemas.clj`.

Three sections — `events`, `commands`, `queries`:
```clojure
(defschemas events
  {:service/thing-created
   [:map [:thing-id :uuid] [:name :string]]})

(defschemas commands
  {:service/create-thing
   [:map [:name [:string {:min 1 :error/message "Required"}]]]})

(defschemas queries
  {:service/things-page [:map]})
```

Uses Malli syntax. Common patterns: `:uuid`, `:string`, `[:enum :a :b]`, `[:maybe :string]`, `[:vector ...]`, `[:map-of :uuid ...]`.

### 1.6 Web API Core

`bases/web-api/src/.../core.clj` — the system entry point.

**Integrant system map** wires everything:
```clojure
(def system
  {::logger {}
   ::event-store {:conn {:type :in-memory} ...}
   ::event-pubsub {:type :core-async :topic-fn :event/type}
   ::cache {:storage-dir "storage/cache" :db-name "read-model-cache" ...}
   ::context {:tenant-id system-tenant-id
              :system-tenant-id system-tenant-id
              :event-store (ig/ref ::event-store)
              :cache (ig/ref ::cache)
              :command-registry (cp/global-command-registry)
              :query-registry (query-processor/global-query-registry)
              ...}
   ::routes {:context (ig/ref ::context)}
   ::webserver {::http/routes (ig/ref ::routes) ...}})
```

**Interceptor chain:** cookies -> flash-cookie -> extract-auth-cookie -> set-auth-cookie

**Flash redirect pattern** — for form POST handlers:
```clojure
(flash-redirect "/destination" {:error "Something went wrong"})
(flash-redirect "/destination" {:success true}
  :cookies {"auth-token" {:value jwt ...}})
```

**`live` wrapper** — enables REPL-driven development:
```clojure
["/auth/sign-in" :post [ctx-interceptor (live #'sign-in-handler)] ...]
```

**Route registration** — auto-applies head/theme to all Datastar queries:
```clojure
(ds/routes context overrides)  ;; generates GET + SSE routes for each defquery with :datastar/path
```

### 1.7 Test Patterns

**Test context:**
```clojure
(h/with-test-context [ctx]
  ;; ctx has: event-store, cache, tenant-id, registries, mock email, jwt-secret
  ...)
```

**Running commands in tests:**
```clojure
(let [result (h/process-command! ctx {:command/name :service/do-thing :field "value"})]
  (is (h/event-of-type? result :service/thing-done))
  (let [event (h/find-event result :service/thing-done)]
    (is (= "value" (:field event)))))
```

**Testing failures:**
```clojure
(let [result (h/process-command! ctx {:command/name :service/do-thing ...})]
  (is (= ::anom/conflict (::anom/category result))))
```

**Auth in tests:**
```clojure
(let [{:keys [user-id]} (h/sign-up-and-verify! ctx :email-address "test@example.com")]
  (h/process-command! (h/with-auth ctx {:user-id user-id :email "test@example.com"})
                      {:command/name :service/do-thing}))
```

### 1.8 The Action Handler Pattern

Management pages invoke commands without page navigation via the `/actions` POST route. This is the primary way Datastar pages mutate state.

**Server setup** — a single route handles all management commands:
```clojure
;; In web-api core.clj routes:
["/actions" :post [(ds/action-handler context {})] :route-name ::datastar-actions]
```

**Client side** — `action-form` posts scoped signals to `/actions`:
```clojure
(ui/with-named-scope "add-item"
  (ui/action-form
   {:command "service/create-item"
    :fields {:name "" :description ""}
    :submit-label "Create"}
   (ui/form-field {:label "Name" :data-bind "name"})
   (ui/form-field {:label "Description" :data-bind "description"})))
```

**What happens on submit:**
1. `action-form` remaps scoped signals (`add-item-name`) to unscoped command fields (`name`)
2. Sets `command/name` signal to `"service/create-item"`
3. Fires `@post('/actions')` with all signals as JSON body
4. Server's `ds/action-handler` extracts the command, checks authorization, calls `cp/process-command`

**Command returns signals to the client:**
```clojure
;; On success:
{:command-result/events [...]
 :datastar/signals {:success true
                    :modalOpen false
                    :name "" :description ""    ;; clear form fields
                    :__toast "Item created!"}}  ;; toast notification

;; On failure (anomaly):
{::anom/category ::anom/conflict
 ::anom/message "Name already taken."}
;; → auto-converted to: {:error "Name already taken."}
```

**Anomaly → signal conversion is automatic.** The action handler checks `(anomaly? result)` and converts:
- `::anom/message` → `:error` signal (displayed by `form-error` component)
- `:error/explain` from schema validation → `:fieldErrors` signal (displayed inline per field)

**Special signal prefixes:**
- `__toast` — show a brief notification
- `__redirect` — navigate to a URL
- `__refresh` — force page refresh

**When to use action handler vs form POST:**

| Approach | Use for | Error feedback | Navigation |
|----------|---------|---------------|------------|
| Action handler (`/actions`) | Management pages, modals, in-page forms | Signal-based (`$error`, `$fieldErrors`) | Optional via `__redirect` signal |
| Form POST + `flash-redirect` | Auth pages, onboarding, cookie-setting flows | Flash cookie → query reads `:flash` | Always redirects (302) |

### 1.9 Event Tagging Strategy

Every event carries a `:tags` set — tuples of `[:entity-type entity-id]`. Tags serve two purposes: efficient filtered reads and SSE re-render scoping.

**Tag design rules:**

1. **Always tag with the primary entity:**
```clojure
(->event {:type :service/item-created
          :tags #{[:item item-id]}
          :body {...}})
```

2. **Add cross-cutting tags for related entities:**
```clojure
(->event {:type :scheduling/appointment-booked
          :tags #{[:appointment appointment-id]
                  [:provider provider-id]
                  [:patient patient-id]}
          :body {...}})
```
This allows querying appointments by provider OR by patient efficiently.

3. **Tenant-wide events use empty tags:**
```clojure
(->event {:type :tenant/settings-updated
          :tags #{}
          :body {...}})
```

**Using tags in read model queries:**
```clojure
;; Full projection (all entities):
(rmp/project ctx :service/items)

;; Tag-filtered projection (single entity — much faster):
(rmp/project ctx :service/items {:tags #{[:item item-id]}})

;; Common helper pattern:
(defn get-item [ctx item-id]
  (get (rmp/project ctx :service/items {:tags #{[:item item-id]}})
       item-id))
```

**Tags in SSE re-rendering** — the `:datastar/event-tags` query metadata filters which events trigger a re-render:
```clojure
(defquery :service items-page
  {:datastar/event-tags {:service [:auth-claims :tenant-id]}
   :grain/read-models {:service/items 1}
   ...}
  ...)
```
This means: only re-render when events tagged with the current user's tenant-id arrive. Without this, events from all tenants would trigger re-renders.

### 1.10 The Interface Contract

Every Polylith component has an `interface.clj` that defines its public boundary. The pattern is specific:

```clojure
(ns ai.obney.orc.my-service.interface
  (:require ;; Side-effect requires — loading these registers commands/queries in global registries
            [ai.obney.orc.my-service.core.commands]
            [ai.obney.orc.my-service.core.queries]
            ;; Aliased requires for re-export
            [ai.obney.orc.my-service.interface.read-models :as rm]
            [ai.obney.orc.my-service.core.todo-processors :as tp]))

;; Re-export registries for web-api wiring
(def todo-processors tp/todo-processors)

;; Re-export read model helpers as stable public API
(def get-item rm/get-item)
(def get-active-items rm/get-active-items)
```

**What goes where:**
- **Commands/queries** — NOT re-exported. They're accessed by keyword name (`:service/create-item`) through global registries. The `require` is side-effect-only.
- **Todo processors** — re-exported as `todo-processors` map for web-api to wire into Integrant.
- **Read model helpers** — re-exported as stable functions. These are the public API for cross-service data access.
- **Schemas** — live in `interface/schemas.clj` (separate namespace). Required by web-api for side-effect registration.

**What stays private (in `core/`):**
- Multimethod reducers (`things*`)
- Event type sets (`thing-event-types`)
- Internal helper functions
- View functions (accessed only by queries in the same service)

### 1.11 Cross-Service Data Access

Any query or command can read from another service's read model. There are two ways:

**Via interface helpers** (preferred — stable API):
```clojure
(:require [ai.obney.orc.other-service.interface :as other])

(let [item (other/get-item ctx item-id)]
  ...)
```

**Via direct projection** (when no helper exists):
```clojure
(:require [ai.obney.grain.read-model-processor-v2.interface :as rmp])

(let [users (rmp/project ctx :user/users)]
  ...)
```

**For SSE invalidation**, declare all cross-service read models in query metadata:
```clojure
(defquery :service detail-page
  {:grain/read-models {:service/items 1
                       :other-service/related-things 1   ;; cross-service!
                       :user/users 1}                    ;; another service
   ...}
  ...)
```

**Cross-service events** — a command in one service can emit events with another service's type. The target service's read model will process them:
```clojure
;; In tenant-service command:
(->event {:type :crm/contact-created    ;; CRM event type, emitted by tenant command
          :tags #{[:contact contact-id]}
          :body {...}})
```

### 1.12 Error Surfacing End-to-End

There are exactly two error paths. Which one you use depends on the page type.

**Path A: Flash cookies (auth pages, redirecting flows)**

```
User submits form → POST /auth/sign-in → form handler
  → run-command → anomaly returned
  → (flash-redirect "/auth/sign-in" {:error "Invalid credentials"})
  → 302 redirect with flash cookie
  → Browser loads /auth/sign-in (Datastar shim)
  → SSE stream opens → query runs
  → [{:keys [flash]}] → (:error flash) = "Invalid credentials"
  → (views/sign-in-page {:error "Invalid credentials"})
  → [:div.alert.alert-error "Invalid credentials"]
  → Flash cookie cleared on SSE request
```

View displays the error:
```clojure
(defn sign-in-page [{:keys [error]}]
  [:div#app
   (when error
     [:div {:class "alert alert-error mb-4"} [:span error]])
   [:form ...]])
```

**Path B: Signal patch (management pages, modals, in-page forms)**

```
User submits action-form → @post('/actions') with signals as JSON
  → ds/action-handler → cp/process-command → anomaly returned
  → Auto-converted: {:error "Name taken" :fieldErrors {:name "must be unique"}}
  → patch-signals SSE event sent on existing connection
  → $error = "Name taken", $fieldErrors.name = "must be unique"
  → form-error component shows $error
  → field-level error shows inline under name field
  → User types in field → data-on:input clears: $error = '', $fieldErrors = {}
```

The `form-error` component (included automatically by `action-form`):
```clojure
;; Displays $error when non-empty, auto-included in every action-form
(ui/form-error)
;; Renders: [:div {:data-show "$error" :class "alert alert-error"} [:span {:data-text "$error"}]]
```

Field-level errors display via `$fieldErrors['field-name']`:
```clojure
;; Each form-field automatically shows field errors:
[:div {:data-show (str "$fieldErrors && $fieldErrors['" field-name "']")
       :class "text-error text-sm"}
 [:span {:data-text (str "$fieldErrors['" field-name "']")}]]
```

---

## Part 2: Frontend Patterns

### 2.1 Theme & CSS

**Theme:** DaisyUI with custom `xivara` theme (monochromatic black/white with warm undertones). Set via `data-theme="xivara"` on `<html>`.

**Build:** `npm run css:dev` (Tailwind CLI watching `css/main.css` -> `bases/web-api/resources/web-api/public/css/main.css`)

**Fonts:** Instrument Sans (body) + JetBrains Mono (code), loaded via Google Fonts `<link>`.

**Key DaisyUI classes:**
- Buttons: `btn`, `btn-primary`, `btn-ghost`, `btn-sm`, `btn-outline`
- Cards: `card`, `card-body`
- Forms: `input`, `input-bordered`, `select`, `textarea`, `label`, `label-text`
- Layout: `rounded-box`, `border border-base-300`, `bg-base-100`, `bg-base-200`
- Text: `text-base-content`, `text-base-content/60` (muted)

### 2.2 View Architecture

Views live in `{service}/core/views.clj`. They are pure Hiccup functions — no state, no side effects.

**Page structure:**
```clojure
(defn some-page [{:keys [claims items error]}]
  [:div#app
   ;; Shell with nav
   [:div {:class "min-h-screen bg-base-100"}
    [:div {:class "max-w-4xl mx-auto px-4 py-8"}
     ;; Page header
     [:h1 {:class "text-2xl font-semibold"} "Title"]
     ;; Flash error
     (when error
       [:div {:class "alert alert-error mb-4"} error])
     ;; Content
     ...]]])
```

**The `#app` div** — Datastar patches content within `div#app`. Every page view must wrap in `[:div#app ...]`.

### 2.3 Form Patterns

**Standard HTML form** (POST to action endpoint):
```clojure
[:form {:method "post" :action "/auth/sign-in"}
 [:div.form-control
  [:label.label [:span.label-text "Email"]]
  [:input {:type "email" :name "email-address"
           :class "input input-bordered w-full"
           :required true}]]
 [:div.form-control.mt-4
  [:label.label [:span.label-text "Password"]]
  [:input {:type "password" :name "password"
           :class "input input-bordered w-full"
           :required true}]]
 [:button {:type "submit" :class "btn btn-primary w-full mt-6"} "Sign in"]]
```

Form POSTs go to non-Datastar endpoints (regular Pedestal routes). The handler processes the command, then does a `flash-redirect` back to a Datastar page.

**Datastar action form** (for in-page actions without redirect):
```clojure
[:button {:data-on-click (str "$$post('/action/service/command-name', {body: {id: \"" id "\"}})")
          :class "btn btn-sm"}
 "Do thing"]
```

Uses `ds/action-handler` route on the server.

### 2.4 Flash Messages

Errors and success messages flow through flash cookies:

**Server side (form handler):**
```clojure
(flash-redirect "/page" {:error "Something went wrong"})
(flash-redirect "/page" {:success true :email "user@example.com"})
```

**View side (query):**
```clojure
(defn sign-in-page [{:keys [error]}]
  [:div#app
   (when error
     [:div {:class "alert alert-error mb-4"} [:span error]])
   [:form ...]])
```

**Query receives flash via context:**
```clojure
(defquery :user sign-in-page ...
  [{:keys [flash]}]
  {:datastar/hiccup (views/sign-in-page {:error (:error flash)})})
```

### 2.5 Auth Card Pattern

Centered card for auth pages (sign-in, sign-up, forgot-password):
```clojure
[:div {:class "min-h-screen flex items-center justify-center bg-base-200 px-4"}
 [:div {:class "card w-full max-w-md bg-base-100 shadow-sm"}
  [:div.card-body
   [:h2 {:class "text-2xl font-bold text-center mb-6"} "Title"]
   ;; form content
   ]]]
```

### 2.6 Stat Cards

Dashboard metric display:
```clojure
[:div {:class "border border-base-300 rounded-box p-4"}
 [:div {:class "text-sm text-base-content/60"} "Label"]
 [:div {:class "text-2xl font-semibold mt-1"} "42"]
 [:div {:class "text-xs text-base-content/40 mt-1"} "Description"]]
```

### 2.7 Empty States

When no data exists:
```clojure
[:div {:class "border border-base-300 rounded-box p-8 text-center text-base-content/40"}
 [:p "No items yet."]
 [:p {:class "mt-1 text-sm"} "Create one to get started."]]
```

### 2.8 Table Pattern

```clojure
[:table {:class "table"}
 [:thead
  [:tr
   [:th "Name"] [:th "Status"] [:th "Actions"]]]
 [:tbody
  (for [item items]
    [:tr {:key (:id item)}
     [:td (:name item)]
     [:td [:span {:class "badge badge-sm"} (:status item)]]
     [:td [:button {:class "btn btn-ghost btn-sm"} "Edit"]]])]]
```

### 2.9 Badge Pattern

```clojure
[:span {:class (str "badge badge-sm "
                    (case status
                      :active "badge-success"
                      :inactive "badge-ghost"
                      "badge-neutral"))}
 (name status)]
```

### 2.10 Modal Pattern (Datastar signals)

```clojure
;; Trigger
[:button {:data-on-click "$modalOpen = true" :class "btn"} "Open"]

;; Modal
[:div {:data-show "$modalOpen" :class "modal modal-open"}
 [:div.modal-box
  [:h3.font-bold "Title"]
  [:p "Content"]
  [:div.modal-action
   [:button {:data-on-click "$modalOpen = false" :class "btn"} "Close"]
   [:button {:data-on-click "$$post('/action/...')" :class "btn btn-primary"} "Confirm"]]]]
```

### 2.11 UI Component Library

Use via `(:require [ai.obney.orc.ui.interface :as ui])`. Gallery pages at `/gallery` show live examples.

#### Layout

| Function | Key args | Description |
|----------|----------|-------------|
| `platform-shell` | `{:keys [auth-claims active-page]} & children` | Main app shell with sidebar, header, toast system |
| `page-header` | `{:keys [title description action greeting]}` | Page title bar with optional action button |
| `auth-card` | `{:keys [title subtitle]} & children` | Centered card for auth forms (max-w-md) |
| `form-card` | `{:keys [title class]} & children` | Card wrapper for form content |
| `section-card` | `{:keys [title subtitle action class]} & children` | Section card with optional header and action |
| `field-grid` | `{:keys [cols] :or {cols 2}} & children` | Responsive grid for form fields |
| `breadcrumbs` | `items` (vec of `{:label :href}`) | Breadcrumb navigation |
| `page-form-layout` | `form-content sidebar-content` | Two-column: 2/3 form + 1/3 sidebar |
| `page-detail-layout` | `{:keys [header sections default-section]}` | Detail page with section tabs |

#### Data Display

| Function | Key args | Description |
|----------|----------|-------------|
| `data-table` | `{:keys [columns rows empty-message row-href]}` | Table with clickable rows; `:row-href (fn [row] url)` |
| `text-column` | `label key` or `label key class` | Column spec shorthand for data-table |
| `stat-card` | `{:keys [label value description icon value-color]}` | Metric card with icon |
| `empty-state` | `{:keys [icon title description action]}` | Placeholder when no data |
| `status-badge` | `{:keys [status label]}` | Colored status badge |
| `role-badge` | `role` | Role keyword → display label |
| `pagination-controls` | `{:keys [page page-size total stream-path]}` | Prev/next + page size selector |

#### Forms

| Function | Key args | Description |
|----------|----------|-------------|
| `action-form` | `{:keys [fields command submit-label pre-submit]} & children` | Declarative form: scoped signals → `/actions` POST |
| `form-field` | `{:keys [label name type data-bind placeholder required? error-signal]}` | Text input with label and error display |
| `email-field` | `{:keys [data-bind placeholder]}` | Email input with validation |
| `phone-field` | `{:keys [data-bind placeholder]}` | Phone input, auto-formats (XXX) XXX-XXXX |
| `textarea-field` | `{:keys [data-bind placeholder rows]}` | Multi-line input with morph-ignore |
| `checkbox-field` | `{:keys [label data-bind error-signal]}` | Inline checkbox toggle |
| `select-field` | `{:keys [data-bind options placeholder]}` | Dropdown; options: strings or `{:value :label}` maps |
| `typeahead-field` | `{:keys [data-bind label results result-fn on-input]}` | Autocomplete with server-driven results |
| `form-error` | (no args) | Error banner bound to `$error` signal; auto-included in `action-form` |
| `alert-banner` | `{:keys [success error]}` | Flash-style alert for non-Datastar forms |

#### Modals & Drawers

| Function | Key args | Description |
|----------|----------|-------------|
| `modal` | `{:keys [signal title size]} & children` | Modal controlled by signal; sizes: `:sm` `:md` `:lg` `:xl` `:2xl` |
| `drawer` | `{:keys [signal title width]} & children` | Right-side slide-out panel |
| `confirmation-dialog` | `{:keys [signal title message confirm-label on-confirm variant]}` | Destructive action confirmation |
| `modal-open-click` | `{:keys [assignments modal-signal]}` | Builds `data-on:click` to populate signals + open modal |
| `scoped-open-click` | `{:keys [scope modal-signal field-values]}` | Same but with scoped signal prefixes |

#### Scoping & Expressions

| Function | Key args | Description |
|----------|----------|-------------|
| `with-named-scope` | `scope-id & body` (macro) | Bind scope prefix for nested signals |
| `current-scope` | (none) | Get current scope ID or nil |
| `sig` | `field-name` | Returns `$['scope-field']` for Datastar expressions |
| `scoped-action` | `command-name fields & {:keys [extra]}` | Builds click expression remapping scoped → unscoped |
| `ds-str` | `value` | Wraps value for Datastar expression: `"foo"` → `"'foo'"` |
| `action-click` | `command-name & {:keys [extra]}` | Builds click expression for simple (unscoped) action POST |

#### Formatting & Reference

| Function/Def | Description |
|-------------|-------------|
| `format-price` | Cents → `"$75.00"` |
| `format-phone` | Digits → `"(225) 555-1234"` |
| `normalize-email` | Trim + lowercase |
| `normalize-phone` | Strip non-digits |
| `us-states` | Vector of 51 state abbreviations |
| `us-timezones` | Vector of `{:value :label}` timezone maps |

### 2.12 Signal Scoping

When a page has multiple forms (e.g., a list page with an "Add" modal and an "Edit" modal), their signals would collide. Scoping gives each form its own signal namespace.

**`with-named-scope`** — binds a prefix for all nested signal references:
```clojure
(ui/with-named-scope "add-loc"
  ;; All signals inside here are prefixed: "add-loc-name", "add-loc-phone", etc.
  (ui/action-form
   {:command "service/add-location"
    :fields {:name "" :phone ""}
    :submit-label "Add Location"}
   (ui/form-field {:label "Name" :data-bind "name"})       ;; → data-bind="add-loc-name"
   (ui/phone-field {:label "Phone" :data-bind "phone"})))   ;; → data-bind="add-loc-phone"
```

On submit, `action-form` automatically remaps scoped signals back to unscoped command fields:
`$name = $['add-loc-name']; $phone = $['add-loc-phone']; @post('/actions')`

**`sig`** — reference a scoped signal in Datastar expressions:
```clojure
(ui/with-named-scope "edit"
  [:div {:data-show (ui/sig "modalOpen")}   ;; → data-show="$['edit-modalOpen']"
   ...])
```

**`scoped-action`** — build a click expression that remaps and posts:
```clojure
(ui/with-named-scope "delete"
  [:button {:data-on-click (ui/scoped-action "service/delete-item"
                                             ["item-id"])}
   "Delete"])
;; → $['item-id'] = $['delete-item-id']; $['command/name'] = 'service/delete-item'; @post('/actions')
```

**Scope IDs must be stable strings.** They must survive SSE re-renders (Datastar morphs the DOM, and signal names must match). Never use `(random-uuid)` as a scope — use descriptive strings like `"add-loc"`, `"edit-staff"`.

**The lazy-seq trap:**

`with-named-scope` uses Clojure's `binding` (dynamic vars). Lazy sequences (`for`, `map`) evaluate **after** the binding exits, so `*scope*` will be nil:

```clojure
;; BAD — for is lazy, sig reads *scope* after binding exits:
(ui/with-named-scope "items"
  (for [item items]
    [:div {:data-show (ui/sig "expanded")} ...]))
;; → sig returns $expanded (no prefix!) because *scope* is nil at eval time

;; GOOD — doall forces evaluation inside the binding:
(ui/with-named-scope "items"
  (doall (for [item items]
    [:div {:data-show (ui/sig "expanded")} ...])))
;; → sig returns $['items-expanded'] as expected

;; ALSO GOOD — capture scope in a let:
(let [scope (ui/current-scope)]
  (for [item items]
    (binding [ui/*scope* scope]
      [:div {:data-show (ui/sig "expanded")} ...])))
```

### 2.13 Datastar Attribute Reference

Every Datastar behavior is controlled via `data-*` HTML attributes. When unsure about syntax, read the grain datastar component source (`components/datastar/src/.../core.clj`) and [data-star.dev](https://data-star.dev).

#### Signal Initialization & Binding

| Attribute | Purpose | Example |
|-----------|---------|---------|
| `data-signals` | Declare initial signal values (JSON object) | `{:data-signals "{'page': 1, 'search': ''}"}` |
| `data-bind` | Two-way bind an input to a signal | `{:data-bind "name"}` |
| `data-computed` | Derived signal (reactive expression) | `{:data-computed "$total = $price * $qty"}` |

#### Conditional Rendering & Content

| Attribute | Purpose | Example |
|-----------|---------|---------|
| `data-show` | Show/hide element (JS boolean expression) | `{:data-show "$modalOpen"}` |
| `data-text` | Set element text content from expression | `{:data-text "$userName"}` |
| `data-class` | Conditionally apply CSS classes | `{:data-class "{'hidden': !$show, 'active': $show}"}` |
| `data-attr-disabled` | Conditionally set `disabled` attr | `{"data-attr-disabled" "$__submitting"}` |

#### Event Handlers

| Attribute | Purpose | Example |
|-----------|---------|---------|
| `data-on:click` | Click handler | `{"data-on:click" "$count++"}` |
| `data-on:click__prevent` | Click + preventDefault | `{"data-on:click__prevent" "$modal = false"}` |
| `data-on:click__stop` | Click + stopPropagation | `{"data-on:click__stop" "handleItem()"}` |
| `data-on:submit__prevent` | Form submit + preventDefault | `{"data-on:submit__prevent" "@post('/actions')"}` |
| `data-on:input` | Input value change | `{"data-on:input" "$fieldErrors = {}; $error = ''"}` |
| `data-on:input__debounce.Nms` | Debounced input (N ms) | `{"data-on:input__debounce.300ms" "@post('/search/stream')"}` |
| `data-on:change` | Select/checkbox change | `{"data-on:change" "@post('/calendar/stream')"}` |
| `data-on:keydown` | Keyboard event | `{"data-on:keydown" "evt.key === 'Escape' && ($modal = false)"}` |

**Modifier syntax:** `data-on:EVENT__MODIFIER1__MODIFIER2`. Available modifiers: `prevent` (preventDefault), `stop` (stopPropagation), `debounce.Nms` (delay).

#### Server Requests

| Expression | Purpose | Example |
|------------|---------|---------|
| `@get('path')` | Open GET SSE stream (signals as query params) | `@get('/items/stream')` |
| `@post('path')` | Open POST SSE / send signals as JSON body | `@post('/actions')` |

`@post` sends all current signals in the request body. `@get` appends them as `?datastar={...}` query param.

#### DOM Control

| Attribute | Purpose | Example |
|-----------|---------|---------|
| `data-ignore-morph` | Skip this element during DOM morphing | `{:data-ignore-morph true}` — use on textareas/inputs that hold ephemeral state |
| `data-indicator` | Track fetch state (sets signal during request) | `{:data-indicator "__submitting"}` |

#### Signal Naming Conventions

| Prefix | Meaning | Example |
|--------|---------|---------|
| (none) | Domain signal — sent to server | `$name`, `$page`, `$search` |
| `_` | UI-only signal — local state | `$_pendingId`, `$_showDropdown` |
| `__` | Internal/framework signal | `$__toast`, `$__submitting`, `$__mobileNav` |
| `command/name` | Reserved — set by `action-form` | `$['command/name']` |
| `error` | Reserved — set by action handler on anomaly | `$error` |
| `fieldErrors` | Reserved — set by action handler on validation | `$fieldErrors` |
| `dsNonce` | Reserved — page load nonce for POST streams | `$dsNonce` |

---

## Part 3: Data Flow Summary

```
User Action
  |
  v
[Form POST] -----> [Pedestal Interceptor Chain] ----> [Form Handler]
  or                      |                               |
[Datastar Action]         |                        run-command
  |                       v                               |
  |              extract-auth-cookie               cp/process-command
  |              flash-cookie                             |
  |                                                       v
  |                                              [defcommand handler]
  |                                                       |
  |                                              validation + anomaly check
  |                                                       |
  |                                              emit events (->event)
  |                                                       |
  |                                              command-processor/append
  |                                                       |
  |                                              pubsub broadcast
  |                                               /             \
  |                                              v               v
  |                                    [Todo Processors]   [Read Model Cache]
  |                                    (side effects)      (invalidation)
  |                                                              |
  v                                                              v
[flash-redirect]                                         [Datastar SSE]
  |                                                              |
  v                                                              v
[Browser redirect]                                     [defquery handler]
  |                                                              |
  v                                                     rmp/project (read model)
[Datastar page load]                                             |
  |                                                              v
  v                                                     views/render (Hiccup)
[SSE stream] <---- :datastar/hiccup ---- [Query Result]         |
  |                                                              v
  v                                                     [:div#app ...]
[DOM patch]                                              (HTML fragment)
```

---

## Part 4: Decision Recipes

Quick-reference for "I need to build X — what pattern do I use?"

| I need to... | Reach for... | Key files |
|---|---|---|
| **Show a static page** (auth form, about) | `defquery` with `:datastar/fps 0`, `auth-card` layout | queries.clj, views.clj |
| **Show a live-updating data page** | `defquery` with `:grain/read-models`, `data-table` or custom view | queries.clj, views.clj, read_models.clj |
| **Create/update from a management page** | `action-form` in view + `defcommand` returning `:datastar/signals` | commands.clj, views.clj |
| **Create/update from auth flow** | HTML `<form>` POST to Pedestal route + `flash-redirect` | web-api/core.clj, views.clj |
| **Show an error from auth form** | `flash-redirect` → query reads `:flash` → view shows alert | web-api/core.clj, queries.clj, views.clj |
| **Show an error from management form** | Return anomaly from command → auto-converted to `$error` signal | commands.clj (just return the anomaly) |
| **Read another service's data** | `rmp/project ctx :other/read-model` or interface helper | queries.clj or commands.clj |
| **Filter read model to one entity** | `rmp/project ctx :rm {:tags #{[:entity id]}}` | read_models.clj (helper fn) |
| **Paginate a list** | POST stream + signals (`page`, `pageSize`) + `:stream-method "post"` override | queries.clj, views.clj, web-api/core.clj |
| **Open a modal with pre-filled data** | `data-show` signal + `scoped-open-click` to set field values | views.clj |
| **Confirm a destructive action** | `confirmation-dialog` with `data-show` signal | views.clj |
| **React to an event with a side effect** | Todo processor subscribed to event type, dispatches follow-up command | todo_processors.clj |
| **Run something on a schedule** | Periodic task with `:cron` schedule | periodic_tasks.clj |
| **Add a new domain entity** | Full service: schemas → read model → commands → queries → views → interface → wire in web-api | See checklist below |

### Recipe: CRUD Management Page

The most common pattern. Here's the full loop for a "Widgets" feature:

**1. Schema** (`interface/schemas.clj`):
```clojure
(defschemas events
  {:widgets/widget-created [:map [:widget-id :uuid] [:name :string]]
   :widgets/widget-deleted [:map [:widget-id :uuid]]})
(defschemas commands
  {:widgets/create-widget [:map [:name [:string {:min 1}]]]
   :widgets/delete-widget [:map [:widget-id :uuid]]})
(defschemas queries
  {:widgets/widgets-page [:map]})
```

**2. Read model** (`core/read_models.clj`):
```clojure
(defreadmodel :widgets widgets
  {:events #{:widgets/widget-created :widgets/widget-deleted} :version 1}
  [state event]
  (case (:event/type event)
    :widgets/widget-created (assoc state (:widget-id event) event)
    :widgets/widget-deleted (dissoc state (:widget-id event))
    state))

(defn get-all-widgets [ctx]
  (vals (rmp/project ctx :widgets/widgets)))
```

**3. Commands** (`core/commands.clj`):
```clojure
(defcommand :widgets create-widget
  {:authorized? (fn [ctx] (some? (:auth-claims ctx)))}
  [{{:keys [name]} :command :as ctx}]
  (let [widget-id (random-uuid)]
    {:command-result/events
     [(->event {:type :widgets/widget-created
                :tags #{[:widget widget-id]}
                :body {:widget-id widget-id :name name}})]
     :datastar/signals {:success true :addModal false :name ""
                        :__toast "Widget created!"}}))
```

**4. Query** (`core/queries.clj`):
```clojure
(defquery :widgets widgets-page
  {:authorized? (fn [ctx] (some? (:auth-claims ctx)))
   :datastar/path "/widgets"
   :datastar/title "Widgets"
   :grain/read-models {:widgets/widgets 1}
   :datastar/debounce-ms 50}
  [ctx]
  (let [widgets (rm/get-all-widgets ctx)]
    {:query/result {:widgets widgets}
     :datastar/hiccup (views/widgets-page {:claims (:auth-claims ctx)
                                           :widgets widgets})}))
```

**5. View** (`core/views.clj`):
```clojure
(defn widgets-page [{:keys [claims widgets]}]
  [:div#app
   [:div {:class "max-w-4xl mx-auto px-4 py-8"}
    [:div {:class "flex justify-between items-center mb-6"}
     [:h1 {:class "text-2xl font-semibold"} "Widgets"]
     [:button {:data-on-click "$addModal = true" :class "btn btn-primary btn-sm"}
      "+ Add Widget"]]
    (if (empty? widgets)
      [:div {:class "border border-base-300 rounded-box p-8 text-center text-base-content/40"}
       "No widgets yet."]
      [:table {:class "table"}
       [:thead [:tr [:th "Name"]]]
       [:tbody
        (for [w widgets]
          [:tr {:key (:widget-id w)} [:td (:name w)]])]])
    ;; Add modal
    [:div {:data-show "$addModal" :class "modal modal-open"}
     [:div.modal-box
      (ui/with-named-scope "add"
        (ui/action-form
         {:command "widgets/create-widget"
          :fields {:name ""}
          :submit-label "Create Widget"}
         (ui/form-field {:label "Widget Name" :data-bind "name"})))
      [:div.modal-action
       [:button {:data-on-click "$addModal = false" :class "btn"} "Cancel"]]]]]])
```

---

## Quick Reference: New Feature Checklist

Adding a new feature? Create these files:

1. **`components/{service}/interface/schemas.clj`** — event + command + query schemas
2. **`components/{service}/core/read_models.clj`** — event type sets, reducer multimethods, defreadmodel, query helpers
3. **`components/{service}/core/commands.clj`** — defcommand handlers
4. **`components/{service}/core/queries.clj`** — defquery handlers (with `:datastar/path` for UI pages)
5. **`components/{service}/core/views.clj`** — Hiccup view functions
6. **`components/{service}/core/todo_processors.clj`** — event-driven side effects (if needed)
7. **`components/{service}/interface.clj`** — public API (re-export commands, queries, read models, todo-processors registries)
8. **`components/{service}/test/test_helpers.clj`** — test factories
9. **`components/{service}/test/*_test.clj`** — tests

Then wire into `bases/web-api/core.clj`:
- Add `require` for the interface + schemas
- Add todo-processors to the `::todo-processors` concat
- Datastar routes are auto-registered via query metadata

---

## Debugging

When something doesn't work, use **nREPL** (`/nrepl-connect`) and **the server console logs** (mulog). Do not guess — inspect live state.

### Common problems and how to diagnose them

**Page doesn't update after command succeeds:**
```clojure
;; Check: does the query declare the right read models?
;; The :grain/read-models metadata drives SSE re-rendering.
;; If you added a new read model but the query doesn't list it, it won't re-render.
```
Fix: Add the read model to `:grain/read-models` in the defquery metadata.

**Command returns success but events aren't stored:**
```clojure
;; Via nREPL — read events directly:
(into [] (es/read (:event-store ctx) {:tenant-id tenant-id :types #{:service/thing-created}}))
;; Empty? Check if the command schema is registered (defschemas in interface/schemas.clj loaded?)
;; The v3 event store validates events against schemas — unregistered types silently fail.
```

**Signal changes but view doesn't re-render (POST stream):**
- Check that `:query/result` includes the signal value (e.g., `:page`). The event loop compares results structurally — if the result map doesn't change, no SSE update is sent.
- Check that `stream-method "post"` is set in the route overrides for this query.

**Action form submits but nothing happens:**
```clojure
;; Check the server console for errors. Common causes:
;; 1. Command schema not registered → event validation fails silently
;; 2. Authorization fails → check :authorized? fn matches auth-claims structure
;; 3. Signal scoping mismatch → command receives empty fields

;; Via nREPL — test the command directly:
(cp/process-command (assoc ctx
                      :command {:command/id (random-uuid)
                                :command/timestamp (time/now)
                                :command/name :service/my-command
                                :field "value"}))
```

**Read model returns nil or empty map:**
```clojure
;; Via nREPL — check if events exist:
(into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types #{:service/thing-created}}))
;; Check if the read model is registered:
(get @(ai.obney.grain.read-model-processor-v2.interface/global-read-model-registry) :service/things)
;; nil means the namespace wasn't loaded (require the interface)
```

**Flash message doesn't appear after redirect:**
- Flash is cleared on the SSE request (not the shim HTML request). If the page uses `:datastar/fps 0`, the flash should appear on the one-shot render.
- Check that the query destructures `{:keys [flash]}` and passes it to the view.

### General debugging via nREPL

```clojure
;; Get the running system context:
(def ctx (:ai.obney.orc.web-api.core/context @ai.obney.orc.web-api.core/app))

;; Query a read model:
(require '[ai.obney.grain.read-model-processor-v2.interface :as rmp])
(rmp/project ctx :service/things)

;; Run a command:
(require '[ai.obney.grain.command-processor-v2.interface :as cp])
(require '[ai.obney.grain.time.interface :as time])
(cp/process-command (assoc ctx :command {:command/id (random-uuid)
                                         :command/timestamp (time/now)
                                         :command/name :service/create-thing
                                         :name "Test"}))

;; Read raw events:
(require '[ai.obney.grain.event-store-v3.interface :as es])
(into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)}))

;; Check registered commands:
(keys (cp/global-command-registry))

;; Check registered queries:
(keys @(ai.obney.grain.query-processor.interface/query-registry*))
```
