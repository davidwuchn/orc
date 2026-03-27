---
name: grain-ui-component
description: Build views using the shared Hiccup UI component library with DaisyUI and Datastar
---

# Grain UI Component Library

Server-rendered Hiccup views using the shared UI component library. No SPA, no JavaScript framework -- the server renders HTML via Hiccup and Datastar patches the DOM over SSE.

## Required Import

```clojure
(:require [ai.obney.workshop.ui.interface :as ui])
```

See the **Pattern Compendium section 2.11** (`docs/pattern-compendium.md`) for the full component catalog with every function signature and key args.

## Management Page Template

A standard management page with platform shell, page header, and data table:

```clojure
(ns ai.obney.workshop.my-service.core.views
  (:require [ai.obney.workshop.ui.interface :as ui]))

(defn items-page [{:keys [claims items error]}]
  [:div#app
   (ui/platform-shell {:auth-claims claims :active-page :items}
     (ui/page-header {:title "Items"
                      :description "Manage your items."
                      :action {:label "+ Add Item"
                               :data-on-click "$addModal = true"}})

     ;; Flash error (for pages using flash-redirect)
     (when error
       [:div.alert.alert-error.mb-4 [:span error]])

     ;; Content
     (if (seq items)
       (ui/data-table
        {:columns [(ui/text-column "Name" :name)
                   (ui/text-column "Status" :status "w-32")
                   (ui/text-column "Created" :created-at "w-40")]
         :rows items
         :row-href (fn [row] (str "/items/" (:item-id row)))})

       (ui/empty-state {:icon "inbox"
                        :title "No items yet"
                        :description "Create your first item to get started."
                        :action {:label "+ Add Item"
                                 :data-on-click "$addModal = true"}}))

     ;; Add modal
     (ui/modal {:signal "addModal" :title "Add Item"}
       (ui/with-named-scope "add-item"
         (ui/action-form
          {:command "my-service/create-item"
           :fields {:name "" :description ""}
           :submit-label "Create Item"}
          (ui/form-field {:label "Name" :data-bind "name" :required? true})
          (ui/form-field {:label "Description" :data-bind "description"})))))
   ])
```

## Auth Page Template

Auth pages use `auth-card` with a standard HTML form (method="post") and `alert-banner` for flash errors:

```clojure
(defn sign-in-page [{:keys [error success]}]
  [:div#app
   (ui/auth-card {:title "Sign In"}
     (ui/alert-banner {:success success :error error})
     [:form {:method "post" :action "/auth/sign-in"}
      (ui/form-field {:label "Email" :name "email-address" :type "email"
                      :required? true :placeholder "name@example.com"})
      (ui/form-field {:label "Password" :name "password" :type "password" :required? true})
      [:button.btn.btn-primary.w-full.mt-4 {:type "submit"} "Sign In"]]
     [:div.text-sm.mt-4
      [:a.link.link-primary {:href "/auth/sign-up"} "Create an account"]])])
```

Key difference from management pages: auth forms use `method="post"` to a Pedestal route, not `action-form`. Errors flow through flash cookies, not Datastar signals.

## Action Form Pattern with `with-named-scope`

`action-form` posts scoped signals to `/actions`. Use `with-named-scope` to namespace signals when multiple forms exist on the same page:

```clojure
(ui/with-named-scope "add-loc"
  (ui/action-form
   {:command "service/add-location"
    :fields {:name "" :phone ""}
    :submit-label "Add Location"}
   (ui/form-field {:label "Name" :data-bind "name"})       ;; bound to "add-loc-name"
   (ui/phone-field {:label "Phone" :data-bind "phone"})))   ;; bound to "add-loc-phone"
```

On submit, `action-form` automatically remaps scoped signals back to unscoped command fields:
`$name = $['add-loc-name']; $phone = $['add-loc-phone']; @post('/actions')`

## Modal Pattern with `data-show` Signal

```clojure
;; Trigger button
[:button {:data-on-click "$editModal = true" :class "btn btn-sm"} "Edit"]

;; Modal using the ui/modal component
(ui/modal {:signal "editModal" :title "Edit Item" :size :lg}
  (ui/with-named-scope "edit-item"
    (ui/action-form
     {:command "service/update-item"
      :fields {:item-id "" :name ""}
      :submit-label "Save Changes"}
     (ui/form-field {:label "Name" :data-bind "name"}))))
```

To open a modal with pre-filled data from a table row, use `scoped-open-click`:

```clojure
(ui/data-table
 {:columns [(ui/text-column "Name" :name)
            {:label "Actions" :class "w-24"
             :render (fn [row]
                       [:button
                        (ui/scoped-open-click
                         {:scope "edit-item"
                          :modal-signal "editModal"
                          :field-values {"name" (:name row)
                                         "item-id" (str (:item-id row))}})
                        "Edit"])}]
  :rows items})
```

## Table with `row-href`

Make entire rows clickable links to detail pages:

```clojure
(ui/data-table
 {:columns [(ui/text-column "Name" :name)
            (ui/text-column "Email" :email "w-64")
            {:label "Status" :class "w-32"
             :render (fn [row] (ui/status-badge {:status (:status row)}))}]
  :rows items
  :row-href (fn [row] (str "/items/" (:item-id row)))
  :empty-message "No items found."})
```

## Empty State Pattern

```clojure
(ui/empty-state {:icon "inbox"
                 :title "No items yet"
                 :description "Create your first item to get started."
                 :action {:label "+ Add Item"
                          :data-on-click "$addModal = true"}})
```

## Datastar Attribute Reference

See **Pattern Compendium section 2.13** for the full Datastar attribute reference. Key attributes:

| Attribute | Purpose | Example |
|-----------|---------|---------|
| `data-signals` | Declare initial signal values | `{:data-signals "{'page': 1}"}` |
| `data-bind` | Two-way bind input to signal | `{:data-bind "name"}` |
| `data-show` | Conditionally show/hide element | `{:data-show "$modalOpen"}` |
| `data-text` | Set text content from expression | `{:data-text "$userName"}` |
| `data-on:click` | Click handler | `{"data-on:click" "$count++"}` |
| `data-on:click__prevent` | Click + preventDefault | |
| `data-on:submit__prevent` | Form submit + preventDefault | |
| `data-indicator` | Track fetch state | `{:data-indicator "__submitting"}` |
| `data-ignore-morph` | Skip during DOM morphing | Use on textareas with ephemeral state |

Signal naming conventions:
- `$name` -- domain signal, sent to server
- `$_pending` -- UI-only signal (underscore prefix)
- `$__toast` -- internal/framework signal (double underscore)

## The Lazy-Seq Scoping Trap

`with-named-scope` uses Clojure `binding` (dynamic vars). Lazy sequences (`for`, `map`) evaluate **after** the binding exits, so `*scope*` will be nil:

```clojure
;; BAD -- for is lazy, sig reads *scope* after binding exits:
(ui/with-named-scope "items"
  (for [item items]
    [:div {:data-show (ui/sig "expanded")} ...]))
;; sig returns $expanded (no prefix!) because *scope* is nil

;; GOOD -- doall forces evaluation inside the binding:
(ui/with-named-scope "items"
  (doall (for [item items]
    [:div {:data-show (ui/sig "expanded")} ...])))
;; sig returns $['items-expanded'] as expected
```

## Column Width Conventions

Use the third argument to `text-column` for consistent column widths:

| Width class | Use for |
|-------------|---------|
| `"w-24"` | Short labels, actions |
| `"w-32"` | Status badges, short fields |
| `"w-40"` | Dates, timestamps |
| `"w-48"` | Emails, phone numbers |
| `"w-64"` | Longer text fields |
| (no class) | Primary column, takes remaining space |

## Reference Files

- `components/user-service/src/ai/obney/workshop/user_service/core/views.clj` -- Auth pages, dashboard (real examples)
- `components/ui/src/ai/obney/workshop/ui/interface.clj` -- Full component library source (read for exact signatures)
- `docs/pattern-compendium.md` section 2.11 -- Component catalog table
- `docs/pattern-compendium.md` section 2.13 -- Datastar attribute reference
