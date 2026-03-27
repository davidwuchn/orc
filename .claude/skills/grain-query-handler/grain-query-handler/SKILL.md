---
name: grain-query-handler
description: Implement query handlers that read from event-sourced read models
---

# Grain Query Handler Pattern

## Required Imports

```clojure
(ns my.service.core.queries
  (:require [ai.obney.grain.event-store-v2.interface :as es]
            [ai.obney.grain.query-processor.interface :refer [defquery]]
            [my.service.interface.read-models :as rm]
            [cognitect.anomalies :as anom]))
```

## Query Template

```clojure
(defquery :namespace query-name
  "Docstring describing what the query returns."
  [{{:keys [entity-id filter-param]} :query
    :keys [event-store other-dependency]}]

  (let [entity (rm/get-entity event-store entity-id)]
    (if entity
      {:query/result {:entity entity}}
      {::anom/category ::anom/not-found
       ::anom/message "Entity not found"})))
```

## Return Patterns

### Success
```clojure
{:query/result {:entity data
                :related-data more-data}}
```

### Error
```clojure
{::anom/category ::anom/not-found
 ::anom/message "Human readable message"}
```

## Fat Query Pattern (Screen Queries)

Load all data needed for a UI screen in one query:

```clojure
(defquery :advisor meetings-screen
  "Fat query for meetings screen - returns all data needed."
  [{{:keys [student-id]} :query
    :keys [event-store]}]

  (let [;; Get primary entity
        student (rm/get-student event-store student-id)
        ;; Get related data
        meetings (rm/get-meetings-for-student event-store student-id)
        ;; Get metadata/options
        meeting-types (rm/get-meeting-types event-store)]

    (if student
      {:query/result {:student student
                      :meetings meetings
                      :meeting-types meeting-types}}
      {::anom/category ::anom/not-found
       ::anom/message "Student not found"})))
```

## List Query with Pagination

```clojure
(defquery :crm list-contacts
  "List contacts with optional filters and pagination."
  [{{:keys [type-slug status limit offset]} :query
    :keys [event-store]}]

  (let [;; Get all matching contacts
        all-contacts (cond->> (rm/get-contacts-all event-store)
                       type-slug (filter #(= type-slug (:type-slug %)))
                       status (filter #(= status (:status %))))
        total (count all-contacts)
        ;; Apply pagination
        paginated (cond->> all-contacts
                    offset (drop offset)
                    limit (take limit))]

    {:query/result {:contacts (vec paginated)
                    :total total}}))
```

## Batch Loading Pattern

Pre-load related data to avoid N+1 queries:

```clojure
(defquery :crm list-contacts-with-types
  [{{:keys [type-slug]} :query
    :keys [event-store]}]

  (let [;; Pre-load all contact types (1 query instead of N)
        types-by-id (rm/get-contact-types-by-id event-store)
        ;; Get contacts
        contacts (rm/get-contacts-by-type event-store type-slug)
        ;; Enrich with type data
        enriched (mapv #(assoc % :contact-type (get types-by-id (:type-id %)))
                       contacts)]

    {:query/result {:contacts enriched}}))
```

## Composing Read Models

```clojure
(defquery :crm get-contact
  "Get contact with attribution in single query."
  [{{:keys [contact-id]} :query
    :keys [event-store]}]

  (let [contact (rm/get-contact event-store contact-id)]
    (if contact
      (let [;; Get related data
            relationships (rm/get-relationships-for-contact event-store contact-id)
            attribution (rm/get-attribution event-store contact-id)]
        {:query/result {:contact (cond-> contact
                                   attribution (assoc :attribution attribution)
                                   (seq relationships) (assoc :relationships relationships))}})
      {::anom/category ::anom/not-found
       ::anom/message "Contact not found"})))
```

## Conventions

- Queries are read-only (never emit events)
- Use read models to get current state
- Return `{:query/result {...}}` for success
- Return anomalies for not-found and errors
- Use fat queries for screen data (reduces round trips)
- Batch load related data to avoid N+1 queries
- Include pagination metadata (`:total`) for list queries

## Datastar Integration

Queries serve as the rendering engine for datastar pages. The query metadata configures routing, SSE streaming, and event-driven re-rendering.

### `:datastar/hiccup` and `:datastar/path`

Every page query returns `:datastar/hiccup` containing the rendered Hiccup vector:

```clojure
(defquery :tenant locations-page
  {:authorized? owner?
   :datastar/path "/locations"        ;; URL path — auto-generates HTML shim + SSE route
   :datastar/title "Locations"        ;; Browser tab title
   ...}
  "Renders the locations management page."
  [context]
  {:query/result {:locations locations}
   :datastar/hiccup (views/locations-page {:claims claims :locations locations})})
```

`:datastar/path` auto-generates two routes:
1. `GET /locations` — serves an HTML shim that bootstraps datastar
2. `GET /locations` (SSE) — streams the Hiccup as a datastar fragment via SSE

### Render Modes

| Mode | Metadata | Behavior |
|------|----------|----------|
| One-shot | `:datastar/fps 0` | Renders once, no polling. Used for static pages (auth forms). |
| Polling | `:datastar/fps N` | Re-renders every N frames per second. Rarely used. |
| Event-driven | `:grain/read-models` + `:datastar/event-tags` | Re-renders when relevant events occur. Used for management pages. |

### Event-Driven Queries

For live-updating management pages, set these metadata keys:

```clojure
(defquery :tenant locations-page
  {:authorized? owner?
   :datastar/path "/locations"
   :datastar/title "Locations"
   :grain/read-models {:crm/contacts 1 :crm/contact-types 1}   ;; Read model cache tags
   :datastar/event-tags {:tenant [:auth-claims :tenant-id]}     ;; Events filtered by tenant
   :datastar/debounce-ms 50}                                    ;; Debounce rapid updates
  ...)
```

| Key | Purpose |
|-----|---------|
| `:grain/read-models` | Map of read-model keys to version numbers. When any listed read model is updated, the query re-executes. |
| `:datastar/event-tags` | Map of event-store namespace to context path for tag filtering. `{:tenant [:auth-claims :tenant-id]}` means re-render when events tagged with the current user's tenant-id arrive. |
| `:datastar/debounce-ms` | Milliseconds to debounce rapid event bursts before re-rendering. |

### Flash Context

Auth pages read one-time messages from the flash cookie:

```clojure
(defquery :user sign-in-page
  {:authorized? (constantly true)
   :datastar/path "/auth/sign-in"
   :datastar/title "Sign In"
   :datastar/fps 0}
  "Renders the sign-in form page."
  [{:keys [flash]}]                         ;; flash from cookie interceptor
  {:query/result {:page :sign-in}
   :datastar/hiccup (views/sign-in-page {:error (:error flash)
                                          :success (:success flash)})})
```

Flash data comes from `flash-redirect` in POST handlers:
```clojure
(flash-redirect "/auth/sign-in" {:error "Login failed"})
```

### Auth Context

Authenticated queries access claims from the context:

```clojure
[{:keys [auth-claims] :as context}]
;; auth-claims: {:user-id UUID :email str :tenant-id UUID :role keyword :location-ids [UUID]}
```

### Query Params

Queries can access URL query parameters:

```clojure
[{:keys [query]}]
;; query: {:jwt "..." :other-param "..."}
```

### Query -> View Composition

The query assembles the view-model, the view function renders it:

```clojure
;; In queries.clj:
(defquery :tenant staff-page
  {...metadata...}
  [context]
  (let [claims (:auth-claims context)
        staff-members (rm/get-staff-by-tenant context (:tenant-id claims))
        locations (get-locations context (:tenant-id claims))]
    {:query/result {:staff staff-members}
     :datastar/hiccup (views/staff-page {:claims claims
                                          :staff-members staff-members
                                          :locations locations})}))

;; In views.clj — pure function, no side effects:
(defn staff-page [{:keys [claims staff-members locations]}]
  [:div#app
   (ui/platform-shell {:auth-claims claims :active-page "/staff"}
     ...)])
```

### Route Overrides

In `web-api/core.clj`, overrides add interceptors and HTML shim options per query:

```clojure
(ds/routes context
  {:user/dashboard          {:datastar/interceptors [require-auth]
                             :datastar/shim-opts {:head head}}
   :tenant/locations-page   {:datastar/interceptors [require-auth]
                             :datastar/shim-opts {:head head}}})
```

- `:datastar/interceptors` — Pedestal interceptors prepended to the route (e.g. `require-auth`)
- `:datastar/shim-opts` — HTML shim configuration (`:head` for CSS/font links, `:html-attrs` for data-theme)

### Full Example: Event-Driven Management Page

```clojure
(defquery :tenant locations-page
  {:authorized? owner?
   :datastar/path "/locations"
   :datastar/title "Locations"
   :grain/read-models {:crm/contacts 1 :crm/contact-types 1}
   :datastar/event-tags {:tenant [:auth-claims :tenant-id]}
   :datastar/debounce-ms 50}
  "Renders the locations management page."
  [context]
  (let [claims (:auth-claims context)
        tenant-id (:tenant-id claims)
        contacts (crm/get-contacts-by-type context "location")
        locations (->> contacts
                       (filter #(= :active (:status %)))
                       (map (fn [c] (merge {:location-id (:id c)} (:field-values c)))))]
    {:query/result {:locations locations}
     :datastar/hiccup (views/locations-page {:claims claims :locations locations})}))
```

## Cross-Service Query Pattern

Queries often need data from multiple services. The Grain architecture supports this without tight coupling via shared LMDB cache.

### Declaring Read Model Dependencies

`:grain/read-models` metadata declares which read models the query reads from. This drives SSE re-rendering — when any listed read model updates, the query re-executes.

```clojure
(defquery :tenant staff-page
  {:grain/read-models {:tenant/staff 1 :tenant/invitations 1
                       :crm/contacts 1 :crm/contact-types 1}  ;; Cross-service!
   ...}
  [context]
  ;; Can read from tenant AND crm read models
  ...)
```

### Reading Cross-Service Data

Interface functions (e.g., `crm/get-contacts-by-type`) read from the shared LMDB cache. The query handler calls them like local functions:

```clojure
(let [locations (crm/get-contacts-by-type context "location")
      staff (rm/get-all-staff context)]
  ...)
```

### Direct Read Model Projection

For lower-level access, `rmp/project` reads another service's read model directly from cache:

```clojure
(:require [ai.obney.grain.read-model-processor.interface :as rmp])

(let [users (rmp/project context :user/users)]  ;; Read user-service's read model
  ...)
```

### Cross-Service Events

Commands can emit events owned by other services. The CRM read model will process them regardless of which service emitted them:

```clojure
;; In tenant-service command:
(->event {:type :crm/contact-created    ;; CRM event emitted by tenant command
          :tags #{[:contact user-id]}
          :body {...}})
```

### Reference: Staff Page (4-Service Composition)

`tenant-service/core/queries.clj` `staff-page` composes data from tenant, user, crm, and invitation read models in a single query.

## Reference Files

- `components/crm-service/src/ai/obney/workshop/crm_service/core/queries.clj` - CRM queries with batch loading
- `components/tenant-service/src/ai/obney/workshop/tenant_service/core/queries.clj` - Event-driven management page queries
- `components/user-service/src/ai/obney/workshop/user_service/core/queries.clj` - Auth page queries with flash context
- `bases/web-api/src/ai/obney/workshop/web_api/core.clj` - Route overrides and interceptor setup
