(ns store.sheet.effects
  "Behavior Tree Sheet effects - handles API calls."
  (:require [re-frame.core :as rf]
            [cljs.core.async :refer [go <!]]
            [components.api.interface :as api]
            [anomalies :refer [anomaly?]]))

;; =============================================================================
;; Screen Query Effects (Fat Query Model)
;; =============================================================================

(rf/reg-fx
  ::load-sheets-list-screen
  (fn [{:keys [api-client on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/query api-client
                                      {:query/name :sheet/sheets-list-screen}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::load-sheet-view-screen
  (fn [{:keys [api-client sheet-id on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/query api-client
                                      {:query/name :sheet/sheet-view-screen
                                       :sheet-id sheet-id}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

;; =============================================================================
;; Sheet Effects
;; =============================================================================

(rf/reg-fx
  ::create-sheet
  (fn [{:keys [api-client name on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/create-sheet
                                         :name name}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::rename-sheet
  (fn [{:keys [api-client sheet-id name on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/rename-sheet
                                         :sheet-id sheet-id
                                         :name name}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::delete-sheet
  (fn [{:keys [api-client sheet-id on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/delete-sheet
                                         :sheet-id sheet-id}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

;; =============================================================================
;; Node Effects
;; =============================================================================

(rf/reg-fx
  ::create-node
  (fn [{:keys [api-client sheet-id node-type parent-id index on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        (cond-> {:command/name :sheet/create-node
                                                 :sheet-id sheet-id
                                                 :type node-type}
                                          parent-id (assoc :parent-id parent-id)
                                          index (assoc :index index))))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::move-node
  (fn [{:keys [api-client sheet-id node-id new-parent-id index on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/move-node
                                         :sheet-id sheet-id
                                         :node-id node-id
                                         :new-parent-id new-parent-id
                                         :index index}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::reorder-node
  (fn [{:keys [api-client sheet-id node-id index on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/reorder-node
                                         :sheet-id sheet-id
                                         :node-id node-id
                                         :index index}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::delete-node
  (fn [{:keys [api-client sheet-id node-id on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/delete-node
                                         :sheet-id sheet-id
                                         :node-id node-id}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::set-node-name
  (fn [{:keys [api-client sheet-id node-id name on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/set-node-name
                                         :sheet-id sheet-id
                                         :node-id node-id
                                         :name name}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::set-node-instruction
  (fn [{:keys [api-client sheet-id node-id instruction on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/set-node-instruction
                                         :sheet-id sheet-id
                                         :node-id node-id
                                         :instruction instruction}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::set-node-io
  (fn [{:keys [api-client sheet-id node-id reads writes on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/set-node-io
                                         :sheet-id sheet-id
                                         :node-id node-id
                                         :reads reads
                                         :writes writes}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::set-node-decorators
  (fn [{:keys [api-client sheet-id node-id decorators on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/set-node-decorators
                                         :sheet-id sheet-id
                                         :node-id node-id
                                         :decorators decorators}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::set-node-check
  (fn [{:keys [api-client sheet-id node-id check on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/set-node-check
                                         :sheet-id sheet-id
                                         :node-id node-id
                                         :check check}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

;; =============================================================================
;; Blackboard Effects
;; =============================================================================

(rf/reg-fx
  ::declare-key
  (fn [{:keys [api-client sheet-id key schema on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/declare-key
                                         :sheet-id sheet-id
                                         :key key
                                         :schema schema}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::update-key-schema
  (fn [{:keys [api-client sheet-id key schema on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/update-key-schema
                                         :sheet-id sheet-id
                                         :key key
                                         :schema schema}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::set-key-value
  (fn [{:keys [api-client sheet-id key value on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/set-key-value
                                         :sheet-id sheet-id
                                         :key key
                                         :value value}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::delete-key
  (fn [{:keys [api-client sheet-id key on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/delete-key
                                         :sheet-id sheet-id
                                         :key key}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

;; =============================================================================
;; Execution Effects
;; =============================================================================

(rf/reg-fx
  ::tick-tree
  (fn [{:keys [api-client sheet-id tick-id on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/tick-tree
                                         :sheet-id sheet-id
                                         :tick-id tick-id}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::cancel-tick
  (fn [{:keys [api-client sheet-id tick-id on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/cancel-tick
                                         :sheet-id sheet-id
                                         :tick-id tick-id}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::tick-node
  (fn [{:keys [api-client sheet-id node-id overrides on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        (cond-> {:command/name :sheet/tick-node
                                                 :sheet-id sheet-id
                                                 :node-id node-id}
                                          overrides (assoc :overrides overrides))))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))
