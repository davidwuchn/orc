---
name: grain-ui-component
description: Create UIX-based UI components with hooks, re-frame integration, and ShadCN
---

# Grain UI Component Pattern

## Required Imports

```clojure
(ns components.feature.component-name
  (:require [uix.core :as uix :refer [defui $ use-effect]]
            [re-frame.core :as rf]
            [re-frame.uix :refer [use-subscribe]]
            ["/gen/shadcn/components/ui/button" :as button]
            ["/gen/shadcn/components/ui/card" :as card]
            [components.context.interface :as context]
            [store.feature.events :as events]
            [store.feature.subs :as subs]))
```

## Component Template

```clojure
(defui component-name [{:keys [current-match]}]
  (let [;; Route params
        entity-id (get-in current-match [:path-params :entity-id])

        ;; Subscribe to re-frame state
        data (use-subscribe [::subs/data])
        loading? (use-subscribe [::subs/loading?])
        error (use-subscribe [::subs/error])

        ;; Get context (api-client, router)
        ctx (context/use-context)
        navigate! (:router/navigate! ctx)
        api-client (:api/client ctx)

        ;; Local handlers
        handle-action (fn []
                        (rf/dispatch [::events/do-action entity-id api-client]))]

    ;; Load data on mount (fat query pattern)
    (use-effect
     (fn []
       (when entity-id
         (rf/dispatch [::events/load-screen (parse-uuid entity-id) api-client]))
       js/undefined)
     [entity-id api-client])

    ;; Render
    ($ :div {:class "flex flex-col h-screen"}
       ($ :h1 {:class "text-2xl font-semibold"} "Title")
       (if loading?
         ($ :p "Loading...")
         ($ :div {:class "space-y-4"}
            (for [[idx item] (map-indexed vector data)]
              ($ :div {:key idx
                       :class "bg-card rounded-lg border p-4"
                       :on-click #(navigate! :route-name {:id (:id item)})}
                 ($ :p (:name item)))))))))
```

## Key Patterns

### Element Creation with `$`
```clojure
($ :div {:class "..."} children)           ;; HTML elements
($ button/Button {:on-click fn} "Label")   ;; ShadCN components
($ ComponentName {:prop value})            ;; Custom components
```

### Local State with `use-state`
```clojure
(let [[form-state set-form-state!] (uix/use-state {:field "" :loading false})]
  (set-form-state! (assoc form-state :loading true)))
```

### Effect Hook for Data Loading
```clojure
(use-effect
 (fn []
   (rf/dispatch [::events/load-data id api-client])
   js/undefined)  ;; Return undefined (no cleanup)
 [id api-client]) ;; Dependencies array
```

### Navigation
```clojure
(navigate! :route-name {:param-key "value"})
```

### Dispatching Events
```clojure
(rf/dispatch [::events/event-name arg1 arg2 api-client])
```

## Conventions

- Use `defui` macro for all components
- Get `api-client` and `navigate!` from context
- Load screen data in `use-effect` on mount
- Use `use-subscribe` for re-frame subscriptions
- Use Tailwind classes for styling
- Use ShadCN components from `/gen/shadcn/`

## Reference Files

- `ui/web-app/src/components/auth/core.cljs` - Auth forms with local state
- `ui/web-app/src/components/router/core.cljs` - Router with subscriptions
- `ui/web-app/src/components/context/core.cljs` - Context provider pattern
