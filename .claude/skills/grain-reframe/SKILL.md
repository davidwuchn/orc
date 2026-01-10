---
name: grain-reframe
description: Implement re-frame events, subscriptions, and effects for Grain UI
---

# Grain Re-frame Pattern

## File Structure

```
store/feature/
├── events.cljs   ;; Event handlers
├── subs.cljs     ;; Subscriptions
└── effects.cljs  ;; Side effects (API calls)
```

## Events (events.cljs)

```clojure
(ns store.feature.events
  (:require [re-frame.core :as rf]
            [store.feature.effects :as fx]))

;; Simple DB event (pure state update)
(rf/reg-event-db
  ::set-value
  (fn [db [_ value]]
    (assoc-in db [:feature :value] value)))

;; Effect event (triggers side effects)
(rf/reg-event-fx
  ::load-screen
  (fn [{:keys [db]} [_ entity-id api-client]]
    {:db (-> db
             (assoc-in [:feature :loading?] true)
             (assoc-in [:feature :error] nil))
     ::fx/fetch-screen {:entity-id entity-id
                        :api-client api-client
                        :on-success [::load-success]
                        :on-failure [::load-failure]}}))

;; Success handler with normalized cache
(rf/reg-event-db
  ::load-success
  (fn [db [_ {:keys [entity items]}]]
    (let [items-map (into {} (map (fn [i] [(:id i) i]) items))
          item-ids (mapv :id items)]
      (-> db
          (assoc-in [:feature :entity] entity)
          (update-in [:feature :items-by-id] merge items-map)
          (assoc-in [:feature :item-ids] item-ids)
          (assoc-in [:feature :loading?] false)))))

;; Failure handler
(rf/reg-event-db
  ::load-failure
  (fn [db [_ error]]
    (-> db
        (assoc-in [:feature :loading?] false)
        (assoc-in [:feature :error] error))))
```

## Subscriptions (subs.cljs)

```clojure
(ns store.feature.subs
  (:require [re-frame.core :as rf]))

;; Basic subscription
(rf/reg-sub
  ::loading?
  (fn [db _]
    (get-in db [:feature :loading?])))

;; Hydrating from normalized cache
(rf/reg-sub
  ::items
  (fn [db _]
    (let [item-ids (get-in db [:feature :item-ids] [])
          items-by-id (get-in db [:feature :items-by-id] {})]
      (mapv #(get items-by-id %) item-ids))))

;; Derived subscription (composed)
(rf/reg-sub
  ::has-items?
  :<- [::items]
  (fn [items _]
    (seq items)))

;; Multi-source derived subscription
(rf/reg-sub
  ::has-changes?
  :<- [::current-value]
  :<- [::saved-value]
  (fn [[current saved] _]
    (not= current saved)))
```

## Effects (effects.cljs)

```clojure
(ns store.feature.effects
  (:require [re-frame.core :as rf]
            [cljs.core.async :refer [go <!]]
            [components.api.interface :as api]
            [anomalies :refer [anomaly?]]))

;; Query effect
(rf/reg-fx
  ::fetch-screen
  (fn [{:keys [entity-id api-client on-success on-failure]}]
    (when (and entity-id api-client)
      (go
        (let [response (<! (api/query api-client {:query/name :feature/screen
                                                   :entity-id entity-id}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

;; Command effect
(rf/reg-fx
  ::do-action
  (fn [{:keys [entity-id data api-client on-success on-failure]}]
    (when (and entity-id api-client)
      (go
        (let [response (<! (api/command api-client {:command/name :feature/do-action
                                                     :entity-id entity-id
                                                     :data data}))]
          (if (anomaly? response)
            (when on-failure (rf/dispatch (conj on-failure response)))
            (when on-success (rf/dispatch (conj on-success (:result response))))))))))
```

## DB Structure (Normalized Cache)

```clojure
{:feature {:loading? false
           :error nil
           :entity {:id uuid :name "..."}
           :item-ids [uuid1 uuid2 uuid3]  ;; Ordered list of IDs
           :items-by-id {uuid1 {...}      ;; Normalized cache
                         uuid2 {...}
                         uuid3 {...}}}}
```

## Key Patterns

- **Fat Query**: Load all screen data in one query
- **Normalized Cache**: Store `*-by-id` maps + id lists
- **Optimistic Updates**: Update cache before API confirms
- **Event Chaining**: Pass `on-success`/`on-failure` event vectors
- **core.async**: Use `go`/`<!` for async in effects
- **Anomaly Handling**: Check `(anomaly? response)` for errors

## Reference Files

- `ui/web-app/src/store/auth/events.cljs` - Event handlers
- `ui/web-app/src/store/auth/subs.cljs` - Subscriptions with derived subs
- `ui/web-app/src/store/auth/effects.cljs` - Async effects with core.async
