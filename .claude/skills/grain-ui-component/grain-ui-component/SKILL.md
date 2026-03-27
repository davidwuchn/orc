---
name: grain-ui-component
description: Build views using the shared Hiccup UI component library with DaisyUI and Datastar
---

# UI Component Library

**Design principles live in the gallery** (`/dev/gallery`, source: `gallery/core.clj`). Read it before building UI. This file documents the component API only.

## Required Imports

```clojure
(ns my.service.core.views
  "Pure Hiccup rendering functions for <service> pages.
   Each function takes a view-model map and returns a Hiccup vector."
  (:require [ai.obney.orc.ui.interface :as ui]
            [clojure.string :as string]))
```

## Component Catalogue

### Layout

| Component | Signature | Description |
|-----------|-----------|-------------|
| `platform-shell` | `[{:keys [auth-claims active-page]} & children]` | Main app layout with sidebar, nav, and user profile |
| `page-header` | `[{:keys [title description action greeting]}]` | Page title bar with optional greeting, description, and action button |
| `auth-card` | `[{:keys [title subtitle]} & children]` | Centered auth form container (max-w-md) |
| `form-card` | `[{:keys [title class]} & children]` | General container card with optional title |

### Data Display

| Component | Signature | Description |
|-----------|-----------|-------------|
| `data-table` | `[{:keys [columns rows empty-message row-href]}]` | Table with column configs and optional clickable rows. `:row-href` is `(fn [row] url-string)`. Columns: `{:label :key :render :class}` |
| `stat-card` | `[{:keys [label value description icon value-color]}]` | Metric card with icon |
| `empty-state` | `[{:keys [icon title description action]}]` | Centered placeholder when no data |
| `text-column` | `[label key]` or `[label key class]` | Data-table column spec shorthand; optional `class` for width control |
| `status-badge` | `[{:keys [status label]}]` | Inline badge for detail pages (NOT for table rows — tables don't show status columns) |
| `role-badge` | `[role]` | Maps role keyword to display label |

### Forms

| Component | Signature | Description |
|-----------|-----------|-------------|
| `form-field` | `[{:keys [label name type placeholder required? data-bind value error-signal]}]` | Text input with label and error display |
| `email-field` | `[{:keys [label name data-bind placeholder error-signal]}]` | Email input with validation class binding |
| `phone-field` | `[{:keys [label name data-bind placeholder error-signal]}]` | Phone input, auto-formats to (XXX) XXX-XXXX |
| `select-field` | `[{:keys [label name data-bind options placeholder error-signal]}]` | Dropdown; options are strings or `{:value :label}` maps |
| `form-error` | `[]` | Error banner bound to `$error` signal; auto-clears after 0.5s |
| `action-form` | `[{:keys [fields command data-signals submit-label]} & children]` | Declarative form that POSTs to `/actions` |

### Modal

| Component | Signature | Description |
|-----------|-----------|-------------|
| `modal` | `[{:keys [signal title size max-w]} & children]` | Fixed-position modal; sizes: `:sm` `:md` `:lg` (default) `:xl` `:2xl` |
| `modal-trigger` | `[{:keys [signal label class]}]` | Button that sets signal true to open modal |
| `confirmation-dialog` | `[{:keys [signal title message confirm-label on-confirm variant]} & children]` | Destructive action confirmation modal |
| `modal-section` | `[{:keys [title action]} & children]` | Titled section inside modal with optional action button |
| `field-grid` | `[{:keys [cols] :or {cols 2}} & children]` | Responsive grid for form fields |
| `section-action` | `[{:keys [label on-click variant] :or {variant :primary}}]` | Compact button for modal sections; variants: `:primary` `:danger` `:ghost` `:default` |

### Expression Helpers

| Function | Signature | Description |
|----------|-----------|-------------|
| `ds-str` | `[value]` | Wraps value for Datastar expression: `"Denver"` -> `"'Denver'"` |
| `ds-assign` | `[signal-name value-expr]` | Builds assignment expression; auto-uses bracket notation for dashes |
| `modal-open-click` | `[{:keys [assignments modal-signal]}]` | Builds `data-on:click` expression that populates signals and opens modal |

### Scoping

| Function | Signature | Description |
|----------|-----------|-------------|
| `with-named-scope` | `[scope-id & body]` (macro) | Wraps body with a deterministic scope prefix; survives SSE re-renders. **Preferred for all management pages.** |
| `with-scope` | `[& body]` (macro) | Wraps body with auto-generated random scope prefix. Use only for one-shot pages that don't receive SSE updates. |
| `current-scope` | `[]` | Returns current scope ID or nil |
| `sig` | `[field-name]` | Returns Datastar signal reference, auto-scoped; use in `data-text`/`data-show` |
| `scoped-action` | `[command-name fields & {:keys [extra]}]` | Builds click expression remapping scoped signals to command fields |
| `scoped-open-click` | `[{:keys [scope modal-signal field-values]}]` | Builds click expression setting scoped signals and opening modal |
| `scoped-signals-str` | `[fields]` | Builds `data-signals` JSON with scope prefixes applied |

**CRITICAL — Lazy sequences inside scope bindings:**

`with-scope` and `with-named-scope` use Clojure dynamic `binding`. Lazy sequences (`for`, `map`, `mapcat`, etc.) evaluate their body **after** the binding exits, so `*scope*` will be nil. This causes scoped signal names in `data-bind`, `data-show`, and `ui/sig` to silently lose their prefix.

**Rule:** If a lazy sequence body calls `ui/sig`, `scoped-bind`, `scoped-action`, or any function that reads `*scope*`, you MUST either:
1. Wrap with `doall` to force eager evaluation inside the binding, OR
2. Capture the scope into a local `let` binding before the lazy seq and pass it explicitly

```clojure
;; BAD — for is lazy, ui/sig reads *scope* after binding exits:
(ui/with-named-scope "my-scope"
  (for [item items]
    [:div {:data-show (ui/sig "field")} ...]))

;; GOOD — doall forces evaluation inside the binding:
(ui/with-named-scope "my-scope"
  (doall (for [item items]
    [:div {:data-show (ui/sig "field")} ...])))
```

### Reference Data

| Def | Description |
|-----|-------------|
| `us-states` | Vector of US state abbreviations (50 states + DC) |
| `us-timezones` | Vector of `{:value :label}` maps for IANA timezones |

## View File Template (Management Page)

Management pages use `platform-shell` with clean, clickable tables. See `/dev/gallery` for design principles.

```clojure
(defn my-page [{:keys [claims items]}]
  [:div#app
   (ui/platform-shell {:auth-claims claims :active-page "/my-page"}
     (ui/page-header
      {:title "My Page"
       :description "Manage your items."
       :action [:a.btn.btn-outline {:href "/my-page/new"} "+ Add Item"]})

     ;; Data
     (if (empty? items)
       (ui/empty-state {:title "No items yet"
                        :description "Add your first item."})
       (ui/data-table
        {:columns [{:label "Name" :key :name}
                   {:label "Price" :key :price :class "w-32"}]
         :rows items
         :row-href (fn [row] (str "/my-page/edit?id=" (:item-id row)))})))])
```

**Column width conventions** — use `:class` on column specs to control width:

| Column type | Class | Rationale |
|---|---|---|
| Short data (Price, Duration, Role) | `"w-32"` | Fixed modest width |
| Name / primary text | _(no class)_ | Auto-expands to fill remaining space |

## Auth Page Template

Auth pages use `auth-card`, plain HTML forms, and flash messages. No datastar.

```clojure
(defn sign-in-page [{:keys [error success]}]
  [:div#app
   (ui/auth-card {:title "Sign In"}
     (when success
       [:div.alert.alert-success [:span success]])
     (when error
       [:div.alert.alert-error [:span error]])
     [:form {:method "post" :action "/auth/sign-in"}
      [:fieldset.fieldset
       [:label.fieldset-label "Email"]
       [:input.input.input-bordered.w-full
        {:type "email" :name "email-address" :required true
         :placeholder "name@example.com"}]
       [:label.fieldset-label "Password"]
       [:input.input.input-bordered.w-full
        {:type "password" :name "password" :required true}]
       [:button.btn.btn-primary.w-full.mt-4 {:type "submit"} "Sign In"]]]
     [:div.text-sm.mt-4
      [:a.link.link-primary {:href "/auth/sign-up"} "Create an account"]])])
```

## Action Form Pattern

The declarative `action-form` handles signal setup, scoping, and POST submission automatically.

```clojure
;; Inside a modal or page section:
(ui/with-named-scope "add-loc"
  (ui/action-form
   {:command "tenant/add-location"                ;; command name for /actions
    :fields {:name "" :address-street ""           ;; initial signal values
             :phone "" :email "" :timezone ""}
    :submit-label "Add Location"}
   ;; Children are the form body:
   (ui/field-grid {}
    (ui/form-field {:label "Location Name" :data-bind "name"})
    (ui/phone-field {:label "Phone" :data-bind "phone"})
    (ui/email-field {:label "Email" :data-bind "email"})
    (ui/select-field {:label "Timezone" :data-bind "timezone"
                      :placeholder "Select..." :options ui/us-timezones}))))
```

How it works:
1. `with-named-scope` binds a deterministic scope prefix (e.g. `"add-loc"`)
2. `action-form` with `:fields` generates `data-signals` with scoped keys + error/fieldErrors
3. All nested `data-bind` references are auto-scoped
4. On submit, scoped signals are remapped to unscoped field names and POSTed to `/actions`
5. On SSE re-render, signal names are identical → Idiomorph preserves user input

## List Page + Clickable Table Pattern

```clojure
(defn locations-page [{:keys [claims locations]}]
  [:div#app
   (ui/platform-shell {:auth-claims claims :active-page "/locations"}
     (ui/page-header
      {:title "Locations"
       :description "Manage your business locations."
       :action [:a.btn.btn-outline {:href "/locations/new"} "+ Add Location"]})

     (if (empty? locations)
       (ui/empty-state {:title "No locations yet"
                        :description "Add your first location."})
       (ui/data-table
        {:columns [(ui/text-column "Name" :name)
                   (ui/text-column "Phone" :phone)]
         :rows locations
         :row-href (fn [row] (str "/locations/edit?id=" (:location-id row)))})))])
```

`:row-href` takes a function of row → URL string. See `/dev/gallery` for the full table design philosophy.

## Conventions

- View functions are **pure**: take a view-model map, return `[:div#app ...]`
- View files live at `components/<service>/src/.../core/views.clj`
- The query handler assembles the view-model, the view function renders it
- Use `platform-shell` for all authenticated management pages
- Use `auth-card` for all unauthenticated auth pages
- DaisyUI utility classes: `.btn`, `.btn-primary`, `.btn-outline`, `.btn-ghost`, `.btn-sm`, `.input`, `.input-bordered`, `.alert`, `.alert-error`, `.alert-success`, `.fieldset`, `.fieldset-label`, `.link`, `.link-primary`, `.divider`, `.badge`
- Tailwind classes for layout: `.grid`, `.gap-4`, `.flex`, `.items-center`, `.justify-between`, `.mb-4`, `.mt-4`, `.w-full`, `.text-sm`, `.font-medium`
- Field `data-bind` values must exactly match command schema field names
- `action-form` with `:fields` is preferred over raw `:data-signals` strings

## Reference Files

- `components/ui/src/ai/obney/workshop/ui/core.clj` — All component implementations
- `components/ui/src/ai/obney/workshop/ui/interface.clj` — Public exports
- `components/tenant-service/src/ai/obney/workshop/tenant_service/core/views.clj` — Management page views (locations, staff, settings)
- `components/user-service/src/ai/obney/workshop/user_service/core/views.clj` — Auth page views (sign-in, sign-up, dashboard)
