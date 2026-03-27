---
name: grain-view
description: Build Hiccup view functions for Datastar pages using the UI component library
---

# Grain View Functions

Views are **pure Hiccup functions** that take a view-model map and return `[:div#app ...]`. They live in `{service}/core/views.clj`. No state, no side effects -- just data in, HTML out.

## Core Rule

Every view function returns `[:div#app ...]`. Datastar patches content within this div. If you forget the `#app` id, nothing will render.

```clojure
(defn page-name [{:keys [claims items error]}]
  [:div#app
   ;; ... page content ...
   ])
```

## How Queries Call Views

Query handlers build a view-model from read model data and pass it to the view:

```clojure
(defquery :service items-page
  {:authorized? auth-fn
   :datastar/path "/items"
   :datastar/title "Items"
   :grain/read-models {:service/items 1}
   :datastar/debounce-ms 50}
  [{:keys [auth-claims] :as ctx}]
  (let [items (rm/get-all-items ctx)]
    {:query/result {:items items}
     :datastar/hiccup (views/items-page {:claims auth-claims
                                         :items items})}))
```

The view never reads from the database or context directly. The query builds the view-model, the view renders it.

## Management Page Template

Standard management page: `platform-shell` > `page-header` > content (table or cards):

```clojure
(ns ai.obney.orc.my-service.core.views
  "Pure Hiccup rendering functions for my-service pages."
  (:require [ai.obney.orc.ui.interface :as ui]))

(defn items-page [{:keys [claims items]}]
  [:div#app
   (ui/platform-shell {:auth-claims claims :active-page :items}
     (ui/page-header {:title "Items"
                      :description "Manage your items."
                      :action {:label "+ Add Item"
                               :data-on-click "$addModal = true"}})

     ;; List content
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
          {:command "service/create-item"
           :fields {:name "" :description ""}
           :submit-label "Create Item"}
          (ui/form-field {:label "Name" :data-bind "name" :required? true})
          (ui/form-field {:label "Description" :data-bind "description"})))))])
```

## Auth Page Template

Auth pages use `auth-card` with standard HTML forms (`method="post"`) and `alert-banner` for flash messages:

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

Key differences from management pages:
- Uses `auth-card` instead of `platform-shell`
- Standard HTML `<form>` with `method="post"`, NOT `action-form`
- Errors via `alert-banner` (flash cookies), NOT Datastar signals
- No `with-named-scope` needed

## List Page with Data Table, Empty State, and Add Modal

```clojure
(defn locations-page [{:keys [claims locations]}]
  [:div#app
   (ui/platform-shell {:auth-claims claims :active-page :locations}
     (ui/page-header {:title "Locations"
                      :action {:label "+ Add Location"
                               :data-on-click "$addModal = true"}})

     (if (seq locations)
       (ui/data-table
        {:columns [(ui/text-column "Name" :name)
                   (ui/text-column "City" :city "w-40")
                   (ui/text-column "Phone" :phone "w-48")
                   {:label "Status" :class "w-32"
                    :render (fn [row] (ui/status-badge {:status (:status row)}))}]
         :rows locations
         :row-href (fn [row] (str "/locations/" (:location-id row)))
         :empty-message "No locations found."})

       (ui/empty-state {:icon "map-pin"
                        :title "No locations yet"
                        :description "Add your first location."}))

     ;; Add modal with scoped signals
     (ui/modal {:signal "addModal" :title "Add Location" :size :lg}
       (ui/with-named-scope "add-loc"
         (ui/action-form
          {:command "service/create-location"
           :fields {:name "" :city "" :phone ""}
           :submit-label "Add Location"}
          (ui/field-grid {}
            (ui/form-field {:label "Name" :data-bind "name" :required? true})
            (ui/form-field {:label "City" :data-bind "city"})
            (ui/phone-field {:label "Phone" :data-bind "phone"}))))))])
```

## Detail Page with Sections

```clojure
(defn item-detail-page [{:keys [claims item]}]
  [:div#app
   (ui/platform-shell {:auth-claims claims :active-page :items}
     (if item
       [:div
        (ui/breadcrumbs [{:label "Items" :href "/items"}
                         {:label (:name item)}])
        (ui/page-detail-layout
         {:header (ui/page-header {:title (:name item)
                                   :description (:description item)})
          :sections [{:id "overview" :label "Overview"
                      :content (ui/section-card {:title "Details"}
                                 (ui/field-grid {}
                                   [:div
                                    [:div.text-sm.text-base-content/60 "Status"]
                                    [:div (ui/status-badge {:status (:status item)})]]
                                   [:div
                                    [:div.text-sm.text-base-content/60 "Created"]
                                    [:div (:created-at item)]]))}
                     {:id "settings" :label "Settings"
                      :content (ui/section-card {:title "Settings"}
                                 [:p "Settings content here"])}]
          :default-section "overview"})]
       [:div.p-8.text-center.text-base-content/40 "Item not found."]))])
```

## Modal with `with-named-scope` and `action-form`

Every modal that submits data should use `with-named-scope` to isolate its signals:

```clojure
;; Edit modal with pre-populated fields
(ui/modal {:signal "editModal" :title "Edit Item" :size :lg}
  (ui/with-named-scope "edit-item"
    (ui/action-form
     {:command "service/update-item"
      :fields {:item-id "" :name "" :description ""}
      :submit-label "Save Changes"}
     (ui/form-field {:label "Name" :data-bind "name" :required? true})
     (ui/form-field {:label "Description" :data-bind "description"}))))

;; Open the edit modal with pre-filled data from a row:
(ui/scoped-open-click
 {:scope "edit-item"
  :modal-signal "editModal"
  :field-values {"item-id" (str (:item-id row))
                 "name" (:name row)
                 "description" (:description row)}})
```

## Confirmation Dialog for Destructive Actions

```clojure
(ui/confirmation-dialog
 {:signal "deleteConfirm"
  :title "Delete Item"
  :message "This action cannot be undone. Are you sure?"
  :confirm-label "Delete"
  :on-confirm (ui/scoped-action "service/delete-item" ["item-id"])
  :variant :destructive})
```

## Flash Error Display

For pages that receive errors via flash cookies (auth pages, redirecting flows):

```clojure
(defn my-page [{:keys [error success]}]
  [:div#app
   ;; Option A: Using alert-banner (preferred for auth pages)
   (ui/alert-banner {:success success :error error})

   ;; Option B: Manual (when you need custom styling)
   (when error
     [:div.alert.alert-error.mb-4 [:span error]])
   (when success
     [:div.alert.alert-success.mb-4 [:span success]])

   ;; ... rest of page
   ])
```

For management pages using `action-form`, error display is automatic -- the `form-error` component is included in every `action-form` and reacts to the `$error` signal.

## Important: The Lazy-Seq Scoping Trap

When using `with-named-scope` with lazy sequences (`for`, `map`), wrap in `doall`:

```clojure
;; BAD -- scope is nil by the time for evaluates:
(ui/with-named-scope "items"
  (for [item items]
    [:div {:data-show (ui/sig "expanded")} ...]))

;; GOOD -- doall forces evaluation inside the binding:
(ui/with-named-scope "items"
  (doall (for [item items]
    [:div {:data-show (ui/sig "expanded")} ...])))
```

## Reference Files

- `components/user-service/src/ai/obney/workshop/user_service/core/views.clj` -- Auth pages, dashboard (working examples)
- `components/ui/src/ai/obney/workshop/ui/interface.clj` -- Full component library (read for exact signatures)
- `docs/pattern-compendium.md` section 2.2 -- View architecture
- `docs/pattern-compendium.md` section 2.11 -- Component catalog table
- `docs/pattern-compendium.md` section 2.12 -- Signal scoping
- `docs/pattern-compendium.md` section 2.13 -- Datastar attribute reference
