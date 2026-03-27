---
name: grain-service
description: Create a complete Grain domain service with schemas, read models, commands, queries, views, and tests
---

# Create a Complete Grain Domain Service

This skill walks through creating a full CRUD domain service from scratch. Follow each step mechanically. The example builds a "Widgets" service -- replace `widgets`/`widget` with your actual domain entity throughout.

**Naming conventions used below:**
- Service name: `widgets` (kebab-case for ns, used as event/command prefix)
- Entity name: `widget` (singular)
- Component dir: `components/widget-service/`

---

## Step 1: Create Component Structure

Create the Polylith component directory tree:

```
components/widget-service/
  deps.edn
  src/ai/obney/workshop/widget_service/
    interface.clj
    interface/schemas.clj
    core/
      read_models.clj
      commands.clj
      queries.clj
      views.clj
      todo_processors.clj
  test/ai/obney/workshop/widget_service/
    test_helpers.clj
    commands_test.clj
```

**`deps.edn`:**
```clojure
{:paths ["src" "resources"]
 :deps {}
 :aliases {:test {:extra-paths ["test"]
                  :extra-deps {}}}}
```

---

## Step 2: Define Schemas (`interface/schemas.clj`)

```clojure
(ns ai.obney.orc.widget-service.interface.schemas
  "Malli schemas for widget service commands, events, queries, and read models."
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

(defschemas events
  {:widgets/widget-created [:map [:widget-id :uuid] [:name :string] [:description [:maybe :string]]]
   :widgets/widget-updated [:map [:widget-id :uuid] [:name :string] [:description [:maybe :string]]]
   :widgets/widget-deleted [:map [:widget-id :uuid]]})

(defschemas commands
  {:widgets/create-widget [:map
                           [:name [:string {:min 1 :error/message "Name is required"}]]
                           [:description {:optional true} [:maybe :string]]]
   :widgets/update-widget [:map
                           [:widget-id :uuid]
                           [:name [:string {:min 1 :error/message "Name is required"}]]
                           [:description {:optional true} [:maybe :string]]]
   :widgets/delete-widget [:map
                           [:widget-id :uuid]]})

(defschemas queries
  {:widgets/widgets-page [:map]
   :widgets/widget-detail-page [:map [:widget-id :uuid]]})
```

---

## Step 3: Build Read Model (`core/read_models.clj`)

```clojure
(ns ai.obney.orc.widget-service.core.read-models
  "Widget read models -- projections built from events."
  (:require [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]))

;; Event type set
(def widget-event-types
  #{:widgets/widget-created
    :widgets/widget-updated
    :widgets/widget-deleted})

;; Reducer multimethod
(defmulti widgets* (fn [_state event] (:event/type event)))

(defmethod widgets* :widgets/widget-created
  [state {:keys [widget-id name description]}]
  (assoc state widget-id {:widget-id widget-id
                          :name name
                          :description description
                          :active true}))

(defmethod widgets* :widgets/widget-updated
  [state {:keys [widget-id name description]}]
  (update state widget-id merge {:name name :description description}))

(defmethod widgets* :widgets/widget-deleted
  [state {:keys [widget-id]}]
  (dissoc state widget-id))

(defmethod widgets* :default [state _] state)

;; Register read model
(defreadmodel :widgets widgets
  {:events widget-event-types :version 1}
  [state event]
  (widgets* state event))

;; Query helper functions (public API via interface)
(defn get-widget [ctx widget-id]
  (get (rmp/project ctx :widgets/widgets {:tags #{[:widget widget-id]}})
       widget-id))

(defn get-all-widgets [ctx]
  (->> (vals (rmp/project ctx :widgets/widgets))
       (filter :active)
       (sort-by :name)))
```

---

## Step 4: Implement Commands (`core/commands.clj`)

```clojure
(ns ai.obney.orc.widget-service.core.commands
  "Command handlers for widget service."
  (:require [ai.obney.orc.widget-service.core.read-models :as rm]
            [ai.obney.grain.event-store-v3.interface :refer [->event]]
            [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [cognitect.anomalies :as anom]))

(defcommand :widgets create-widget
  {:authorized? (fn [ctx] (some? (:auth-claims ctx)))}
  "Create a new widget."
  [{{:keys [name description]} :command :as ctx}]
  (let [widget-id (random-uuid)]
    {:command-result/events
     [(->event {:type :widgets/widget-created
                :tags #{[:widget widget-id]}
                :body {:widget-id widget-id
                       :name name
                       :description description}})]
     :datastar/signals {:success true
                        :addModal false
                        :name "" :description ""
                        :__toast "Widget created!"}}))

(defcommand :widgets update-widget
  {:authorized? (fn [ctx] (some? (:auth-claims ctx)))}
  "Update an existing widget."
  [{{:keys [widget-id name description]} :command :as ctx}]
  (let [widget (rm/get-widget ctx widget-id)]
    (cond
      (not widget)
      {::anom/category ::anom/not-found
       ::anom/message "Widget not found."}

      :else
      {:command-result/events
       [(->event {:type :widgets/widget-updated
                  :tags #{[:widget widget-id]}
                  :body {:widget-id widget-id
                         :name name
                         :description description}})]
       :datastar/signals {:success true
                          :editModal false
                          :__toast "Widget updated!"}})))

(defcommand :widgets delete-widget
  {:authorized? (fn [ctx] (some? (:auth-claims ctx)))}
  "Delete a widget."
  [{{:keys [widget-id]} :command :as ctx}]
  (let [widget (rm/get-widget ctx widget-id)]
    (cond
      (not widget)
      {::anom/category ::anom/not-found
       ::anom/message "Widget not found."}

      :else
      {:command-result/events
       [(->event {:type :widgets/widget-deleted
                  :tags #{[:widget widget-id]}
                  :body {:widget-id widget-id}})]
       :datastar/signals {:success true
                          :deleteConfirm false
                          :__toast "Widget deleted."}})))
```

---

## Step 5: Implement Queries with Datastar (`core/queries.clj`)

```clojure
(ns ai.obney.orc.widget-service.core.queries
  "Query handlers for widget service pages."
  (:require [ai.obney.orc.widget-service.core.views :as views]
            [ai.obney.orc.widget-service.core.read-models :as rm]
            [ai.obney.grain.query-processor.interface :refer [defquery]]))

;; Event-driven page -- re-renders when widget events fire
(defquery :widgets widgets-page
  {:authorized? (fn [ctx] (some? (:auth-claims ctx)))
   :datastar/path "/widgets"
   :datastar/title "Widgets"
   :grain/read-models {:widgets/widgets 1}
   :datastar/debounce-ms 50}
  "Widgets management page."
  [{:keys [auth-claims] :as ctx}]
  (let [widgets (rm/get-all-widgets ctx)]
    {:query/result {:widgets widgets}
     :datastar/hiccup (views/widgets-page {:claims auth-claims
                                           :widgets widgets})}))

;; Static detail page -- renders once (fps 0)
(defquery :widgets widget-detail-page
  {:authorized? (fn [ctx] (some? (:auth-claims ctx)))
   :datastar/path "/widgets/:widget-id"
   :datastar/title "Widget Detail"
   :grain/read-models {:widgets/widgets 1}
   :datastar/debounce-ms 50}
  "Widget detail page."
  [{:keys [auth-claims query] :as ctx}]
  (let [widget-id (parse-uuid (:widget-id query))
        widget (rm/get-widget ctx widget-id)]
    {:query/result {:widget widget}
     :datastar/hiccup (views/widget-detail-page {:claims auth-claims
                                                  :widget widget})}))
```

**Three rendering modes** (choose exactly one per query):

| Mode | Metadata | When to use |
|------|----------|-------------|
| One-shot | `:datastar/fps 0` | Static pages, auth forms |
| Polling | `:datastar/fps N` (N > 0) | External/non-event data |
| Event-driven | `:grain/read-models {...}` | Management pages showing domain data |

Do NOT combine `:datastar/fps` with `:grain/read-models`.

---

## Step 6: Build Views (`core/views.clj`)

```clojure
(ns ai.obney.orc.widget-service.core.views
  "Pure Hiccup rendering functions for widget service pages."
  (:require [ai.obney.orc.ui.interface :as ui]))

(defn widgets-page [{:keys [claims widgets]}]
  [:div#app
   (ui/platform-shell {:auth-claims claims :active-page :widgets}
     (ui/page-header {:title "Widgets"
                      :description "Manage your widgets."
                      :action {:label "+ Add Widget"
                               :data-on-click "$addModal = true"}})

     (if (seq widgets)
       (ui/data-table
        {:columns [(ui/text-column "Name" :name)
                   (ui/text-column "Description" :description "w-64")]
         :rows widgets
         :row-href (fn [row] (str "/widgets/" (:widget-id row)))})

       (ui/empty-state {:icon "inbox"
                        :title "No widgets yet"
                        :description "Create your first widget to get started."
                        :action {:label "+ Add Widget"
                                 :data-on-click "$addModal = true"}}))

     ;; Add modal
     (ui/modal {:signal "addModal" :title "Add Widget"}
       (ui/with-named-scope "add-widget"
         (ui/action-form
          {:command "widgets/create-widget"
           :fields {:name "" :description ""}
           :submit-label "Create Widget"}
          (ui/form-field {:label "Name" :data-bind "name" :required? true})
          (ui/form-field {:label "Description" :data-bind "description"})))))])

(defn widget-detail-page [{:keys [claims widget]}]
  [:div#app
   (ui/platform-shell {:auth-claims claims :active-page :widgets}
     (if widget
       [:div
        (ui/breadcrumbs [{:label "Widgets" :href "/widgets"}
                         {:label (:name widget)}])
        (ui/page-header {:title (:name widget)
                         :description (:description widget)})
        ;; Detail content sections here
        ]
       [:div.p-8.text-center.text-base-content/40 "Widget not found."]))])
```

---

## Step 7: Create Interface (`interface.clj`)

The interface has side-effect requires (to register commands/queries in global registries) and re-exports for cross-service access:

```clojure
(ns ai.obney.orc.widget-service.interface
  (:require ;; Side-effect requires -- registers commands/queries in global registries
            [ai.obney.orc.widget-service.core.commands]
            [ai.obney.orc.widget-service.core.queries]
            ;; Aliased requires for re-export
            [ai.obney.orc.widget-service.core.read-models :as rm]
            [ai.obney.orc.widget-service.core.todo-processors :as tp]))

;; Re-export todo processors for web-api wiring
(def todo-processors tp/todo-processors)

;; Re-export read model helpers as stable public API
(def get-widget rm/get-widget)
(def get-all-widgets rm/get-all-widgets)
```

**What goes where:**
- Commands/queries are NOT re-exported. They're accessed by keyword name through global registries. The `require` is side-effect-only.
- Todo processors are re-exported as a map for web-api to wire into Integrant.
- Read model helpers are re-exported as stable functions for cross-service access.
- Schemas live in `interface/schemas.clj` (separate namespace).

If the service has periodic tasks, also add:

```clojure
;; In the ns require:
[ai.obney.orc.widget-service.core.periodic-tasks :as tasks]

;; In the body:
(def periodic-tasks tasks/periodic-tasks)
```

---

## Step 8: Write Tests

### Test Helpers (`test/test_helpers.clj`)

```clojure
(ns ai.obney.orc.widget-service.test-helpers
  "Test utilities for widget service tests."
  (:require [ai.obney.orc.grain-test-utils.interface :as t]
            [ai.obney.orc.widget-service.core.commands]
            [ai.obney.orc.widget-service.core.queries]
            [ai.obney.orc.widget-service.interface.schemas]))

;; Re-exports from grain-test-utils
(def create-test-context #(t/create-test-context "widget-test"))
(def stop-context t/stop-context)

(defmacro with-test-context
  [[ctx-sym] & body]
  `(t/with-test-context [~ctx-sym "widget-test"] ~@body))

(def process-command! t/process-command!)
(def get-result-events t/get-result-events)
(def event-of-type? t/event-of-type?)
(def find-event t/find-event)
(def command-authorized? t/command-authorized?)
(def with-auth t/with-auth)
(def apply-events! t/apply-events!)
(def sign-up-and-verify! t/sign-up-and-verify!)
(def sent-emails t/sent-emails)
```

### Command Tests (`test/commands_test.clj`)

```clojure
(ns ai.obney.orc.widget-service.commands-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.widget-service.test-helpers :as h]
            [ai.obney.orc.widget-service.core.commands :as cmd]
            [cognitect.anomalies :as anom]))

(deftest create-widget-test
  (testing "creates a widget successfully"
    (h/with-test-context [ctx]
      (let [{:keys [user-id]} (h/sign-up-and-verify! ctx)
            ctx (h/with-auth ctx {:user-id user-id :email "test@example.com"})
            result (cmd/widgets-create-widget
                     (assoc ctx :command {:name "Test Widget"
                                          :description "A test widget"}))]
        (is (contains? result :command-result/events))
        (is (h/event-of-type? result :widgets/widget-created))
        (let [event (h/find-event result :widgets/widget-created)]
          (is (some? (:widget-id event)))
          (is (= "Test Widget" (:name event))))))))

(deftest delete-widget-test
  (testing "deletes an existing widget"
    (h/with-test-context [ctx]
      (let [{:keys [user-id]} (h/sign-up-and-verify! ctx)
            ctx (h/with-auth ctx {:user-id user-id :email "test@example.com"})
            create-result (cmd/widgets-create-widget
                            (assoc ctx :command {:name "To Delete"}))
            _ (h/apply-events! ctx create-result)
            widget-id (:widget-id (h/find-event create-result :widgets/widget-created))
            result (cmd/widgets-delete-widget
                     (assoc ctx :command {:widget-id widget-id}))]
        (is (h/event-of-type? result :widgets/widget-deleted)))))

  (testing "returns not-found for nonexistent widget"
    (h/with-test-context [ctx]
      (let [{:keys [user-id]} (h/sign-up-and-verify! ctx)
            ctx (h/with-auth ctx {:user-id user-id :email "test@example.com"})
            result (cmd/widgets-delete-widget
                     (assoc ctx :command {:widget-id (random-uuid)}))]
        (is (= ::anom/not-found (::anom/category result)))))))
```

**Command function naming:** `defcommand :widgets create-widget` generates a function named `widgets-create-widget`.

---

## Step 9: Wire into Web API (`bases/web-api/src/.../core.clj`)

### 9a. Add requires

```clojure
;; In the ns :require block, add:
[ai.obney.orc.widget-service.interface :as widget-service]
[ai.obney.orc.widget-service.interface.schemas]
```

### 9b. Add todo processors

In the `::todo-processors` init-key, add to the `concat`:

```clojure
(concat
 (vals user-service/todo-processors)
 (vals widget-service/todo-processors)   ;; <-- add this
 ...)
```

### 9c. Route overrides (if needed)

Datastar routes are auto-registered from query metadata. But if your pages need auth guards or POST streams, add overrides:

```clojure
;; In the overrides map within the ::routes init-key:
{:widgets/widgets-page [require-auth]
 :widgets/widget-detail-page [require-auth]}
```

For POST stream pages (pagination, search):

```clojure
(reduce (fn [o query-key]
          (update-in o [query-key :datastar/shim-opts]
                     merge {:stream-method "post"}))
        overrides
        [:widgets/widgets-page])
```

---

## Step 10: Register in `deps.edn`

### Root `deps.edn`

Add to `:aliases > :dev > :extra-deps`:

```clojure
ai.obney.orc/widget-service {:local/root "components/widget-service"}
```

### `projects/web-api/deps.edn`

Add to `:deps`:

```clojure
ai.obney.orc/widget-service {:local/root "../../components/widget-service"}
```

---

## Todo Processors (`core/todo_processors.clj`)

If the service needs event-driven side effects:

```clojure
(ns ai.obney.orc.widget-service.core.todo-processors
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

(defn notify-on-widget-created
  [{{:keys [widget-id name]} :event :as context}]
  (cp/process-command
   (assoc context :command {:command/id (random-uuid)
                            :command/timestamp (time/now)
                            :command/name :notifications/send
                            :widget-id widget-id
                            :message (str "Widget created: " name)})))

(def todo-processors
  {:widgets/notify-on-create
   {:handler-fn #'notify-on-widget-created
    :topics [:widgets/widget-created]}})
```

If no side effects are needed, create a minimal file:

```clojure
(ns ai.obney.orc.widget-service.core.todo-processors)

(def todo-processors {})
```

---

## Reference Files

- `components/user-service/` -- Complete working service (auth domain)
- `components/user-service/src/.../interface.clj` -- Interface pattern
- `components/user-service/src/.../interface/schemas.clj` -- Schema definitions
- `components/user-service/src/.../core/read_models.clj` -- Read model with helpers
- `components/user-service/src/.../core/commands.clj` -- Command handlers
- `components/user-service/src/.../core/queries.clj` -- Query handlers
- `components/user-service/src/.../core/views.clj` -- View functions
- `components/user-service/test/.../test_helpers.clj` -- Test helper setup
- `components/user-service/test/.../commands_test.clj` -- Command test patterns
- `bases/web-api/src/.../core.clj` -- Web API wiring
- `docs/pattern-compendium.md` Part 4 -- CRUD recipe and decision table
