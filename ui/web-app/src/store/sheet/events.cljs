(ns store.sheet.events
  "Behavior Tree Sheet events - handles state changes.

   Pattern: Commands don't return data. On success, re-fetch the screen query."
  (:require [re-frame.core :as rf]
            [store.sheet.effects :as sheet-fx]))

;; =============================================================================
;; Sheets List Screen Events
;; =============================================================================

(rf/reg-event-fx
  ::load-sheets-list-screen
  (fn [_ctx [_ api-client]]
    {:db (-> (:db _ctx)
             (assoc-in [:sheets :loading?] true)
             (assoc-in [:sheets :error] nil))
     ::sheet-fx/load-sheets-list-screen {:api-client api-client
                                         :on-success [::load-sheets-list-screen-success]
                                         :on-failure [::load-sheets-list-screen-failure]}}))

(rf/reg-event-db
  ::load-sheets-list-screen-success
  (fn [db [_ result]]
    (-> db
        (assoc-in [:sheets :loading?] false)
        (assoc-in [:sheets :list] (:sheets result))
        (assoc-in [:sheets :total] (:total result)))))

(rf/reg-event-db
  ::load-sheets-list-screen-failure
  (fn [db [_ error]]
    (-> db
        (assoc-in [:sheets :loading?] false)
        (assoc-in [:sheets :error] error))))

;; =============================================================================
;; Sheet View Screen Events
;; =============================================================================

(rf/reg-event-fx
  ::load-sheet-view-screen
  (fn [{:keys [db]} [_ api-client sheet-id]]
    {:db (-> db
             (assoc-in [:sheet :loading?] true)
             (assoc-in [:sheet :error] nil))
     ::sheet-fx/load-sheet-view-screen {:api-client api-client
                                        :sheet-id sheet-id
                                        :on-success [::load-sheet-view-screen-success]
                                        :on-failure [::load-sheet-view-screen-failure]}}))

(rf/reg-event-db
  ::load-sheet-view-screen-success
  (fn [db [_ result]]
    (let [nodes-by-id (into {} (map (juxt :id identity) (:nodes result)))
          blackboard-by-key (into {} (map (juxt :key identity) (:blackboard result)))]
      (-> db
          (assoc-in [:sheet :loading?] false)
          (assoc-in [:sheet :data] (:sheet result))
          (assoc-in [:sheet :nodes] nodes-by-id)
          (assoc-in [:sheet :blackboard] blackboard-by-key)
          (assoc-in [:sheet :layout] (:layout result))))))

(rf/reg-event-db
  ::load-sheet-view-screen-failure
  (fn [db [_ error]]
    (-> db
        (assoc-in [:sheet :loading?] false)
        (assoc-in [:sheet :error] error))))

;; =============================================================================
;; Sheet CRUD Events
;; =============================================================================

(rf/reg-event-fx
  ::create-sheet
  (fn [{:keys [db]} [_ api-client name on-success]]
    {:db (assoc-in db [:sheets :creating?] true)
     ::sheet-fx/create-sheet {:api-client api-client
                              :name name
                              :on-success [::create-sheet-success api-client on-success]
                              :on-failure [::create-sheet-failure]}}))

(rf/reg-event-fx
  ::create-sheet-success
  (fn [{:keys [db]} [_ api-client on-success _response]]
    (when on-success
      (on-success))
    {:db (assoc-in db [:sheets :creating?] false)
     :dispatch [::load-sheets-list-screen api-client]}))

(rf/reg-event-db
  ::create-sheet-failure
  (fn [db [_ error]]
    (-> db
        (assoc-in [:sheets :creating?] false)
        (assoc-in [:sheets :error] error))))

(rf/reg-event-fx
  ::delete-sheet
  (fn [{:keys [db]} [_ api-client sheet-id on-success]]
    {:db (assoc-in db [:sheets :deleting?] true)
     ::sheet-fx/delete-sheet {:api-client api-client
                              :sheet-id sheet-id
                              :on-success [::delete-sheet-success api-client on-success]
                              :on-failure [::delete-sheet-failure]}}))

(rf/reg-event-fx
  ::delete-sheet-success
  (fn [{:keys [db]} [_ api-client on-success _response]]
    (when on-success
      (on-success))
    {:db (assoc-in db [:sheets :deleting?] false)
     :dispatch [::load-sheets-list-screen api-client]}))

(rf/reg-event-db
  ::delete-sheet-failure
  (fn [db [_ error]]
    (-> db
        (assoc-in [:sheets :deleting?] false)
        (assoc-in [:sheets :error] error))))

;; =============================================================================
;; Node Selection Events
;; =============================================================================

(rf/reg-event-db
  ::select-node
  (fn [db [_ node-id]]
    (assoc-in db [:sheet :selected-node-id] node-id)))

(rf/reg-event-db
  ::clear-selection
  (fn [db _]
    (update db :sheet dissoc :selected-node-id)))

(rf/reg-event-db
  ::set-editing-node
  (fn [db [_ node-id]]
    (assoc-in db [:sheet :editing-node-id] node-id)))

(rf/reg-event-db
  ::clear-editing
  (fn [db _]
    (update db :sheet dissoc :editing-node-id)))

;; =============================================================================
;; Node CRUD Events
;; =============================================================================

;; Generic success handler - just refetch the screen
(rf/reg-event-fx
  ::node-command-success
  (fn [_ [_ api-client sheet-id _response]]
    {:dispatch [::load-sheet-view-screen api-client sheet-id]}))

(rf/reg-event-db
  ::node-operation-failure
  (fn [db [_ error]]
    (assoc-in db [:sheet :error] error)))

(rf/reg-event-fx
  ::create-node
  (fn [_ [_ api-client sheet-id node-type parent-id index]]
    {::sheet-fx/create-node {:api-client api-client
                             :sheet-id sheet-id
                             :node-type node-type
                             :parent-id parent-id
                             :index index
                             :on-success [::node-command-success api-client sheet-id]
                             :on-failure [::node-operation-failure]}}))

(rf/reg-event-fx
  ::move-node
  (fn [_ [_ api-client sheet-id node-id new-parent-id index]]
    {::sheet-fx/move-node {:api-client api-client
                           :sheet-id sheet-id
                           :node-id node-id
                           :new-parent-id new-parent-id
                           :index index
                           :on-success [::node-command-success api-client sheet-id]
                           :on-failure [::node-operation-failure]}}))

(rf/reg-event-fx
  ::reorder-node
  (fn [_ [_ api-client sheet-id node-id index]]
    {::sheet-fx/reorder-node {:api-client api-client
                              :sheet-id sheet-id
                              :node-id node-id
                              :index index
                              :on-success [::node-command-success api-client sheet-id]
                              :on-failure [::node-operation-failure]}}))

(rf/reg-event-fx
  ::delete-node
  (fn [{:keys [db]} [_ api-client sheet-id node-id]]
    {:db (update db :sheet dissoc :selected-node-id)
     ::sheet-fx/delete-node {:api-client api-client
                             :sheet-id sheet-id
                             :node-id node-id
                             :on-success [::node-command-success api-client sheet-id]
                             :on-failure [::node-operation-failure]}}))

(rf/reg-event-fx
  ::set-node-name
  (fn [_ [_ api-client sheet-id node-id name]]
    {::sheet-fx/set-node-name {:api-client api-client
                               :sheet-id sheet-id
                               :node-id node-id
                               :name name
                               :on-success [::node-command-success api-client sheet-id]
                               :on-failure [::node-operation-failure]}}))

(rf/reg-event-fx
  ::set-node-instruction
  (fn [_ [_ api-client sheet-id node-id instruction]]
    {::sheet-fx/set-node-instruction {:api-client api-client
                                      :sheet-id sheet-id
                                      :node-id node-id
                                      :instruction instruction
                                      :on-success [::node-command-success api-client sheet-id]
                                      :on-failure [::node-operation-failure]}}))

(rf/reg-event-fx
  ::set-node-io
  (fn [_ [_ api-client sheet-id node-id reads writes]]
    {::sheet-fx/set-node-io {:api-client api-client
                             :sheet-id sheet-id
                             :node-id node-id
                             :reads reads
                             :writes writes
                             :on-success [::node-command-success api-client sheet-id]
                             :on-failure [::node-operation-failure]}}))

(rf/reg-event-fx
  ::set-node-decorators
  (fn [_ [_ api-client sheet-id node-id decorators]]
    {::sheet-fx/set-node-decorators {:api-client api-client
                                     :sheet-id sheet-id
                                     :node-id node-id
                                     :decorators decorators
                                     :on-success [::node-command-success api-client sheet-id]
                                     :on-failure [::node-operation-failure]}}))

;; =============================================================================
;; Blackboard Events
;; =============================================================================

(rf/reg-event-fx
  ::declare-key
  (fn [_ [_ api-client sheet-id key type]]
    {::sheet-fx/declare-key {:api-client api-client
                             :sheet-id sheet-id
                             :key key
                             :type type
                             :on-success [::node-command-success api-client sheet-id]
                             :on-failure [::node-operation-failure]}}))

(rf/reg-event-fx
  ::set-key-value
  (fn [_ [_ api-client sheet-id key value]]
    {::sheet-fx/set-key-value {:api-client api-client
                               :sheet-id sheet-id
                               :key key
                               :value value
                               :on-success [::node-command-success api-client sheet-id]
                               :on-failure [::node-operation-failure]}}))

(rf/reg-event-fx
  ::delete-key
  (fn [_ [_ api-client sheet-id key]]
    {::sheet-fx/delete-key {:api-client api-client
                            :sheet-id sheet-id
                            :key key
                            :on-success [::node-command-success api-client sheet-id]
                            :on-failure [::node-operation-failure]}}))

;; =============================================================================
;; Execution Events
;; =============================================================================

(rf/reg-event-fx
  ::tick-tree
  (fn [{:keys [db]} [_ api-client sheet-id]]
    {:db (assoc-in db [:sheet :ticking?] true)
     ::sheet-fx/tick-tree {:api-client api-client
                           :sheet-id sheet-id
                           :on-success [::tick-tree-success api-client sheet-id]
                           :on-failure [::tick-tree-failure]}}))

(rf/reg-event-fx
  ::tick-tree-success
  (fn [{:keys [db]} [_ api-client sheet-id _response]]
    {:db (assoc-in db [:sheet :ticking?] false)
     :dispatch [::load-sheet-view-screen api-client sheet-id]}))

(rf/reg-event-db
  ::tick-tree-failure
  (fn [db [_ error]]
    (-> db
        (assoc-in [:sheet :ticking?] false)
        (assoc-in [:sheet :error] error))))

(rf/reg-event-fx
  ::tick-node
  (fn [{:keys [db]} [_ api-client sheet-id node-id overrides]]
    {:db (assoc-in db [:sheet :nodes node-id :status] :running)
     ::sheet-fx/tick-node {:api-client api-client
                           :sheet-id sheet-id
                           :node-id node-id
                           :overrides overrides
                           :on-success [::node-command-success api-client sheet-id]
                           :on-failure [::tick-node-failure node-id]}}))

(rf/reg-event-db
  ::tick-node-failure
  (fn [db [_ node-id error]]
    (-> db
        (assoc-in [:sheet :nodes node-id :status] :failure)
        (assoc-in [:sheet :nodes node-id :last-error] (:cognitect.anomalies/message error)))))

;; =============================================================================
;; Clear Sheet State
;; =============================================================================

(rf/reg-event-db
  ::clear-sheet
  (fn [db _]
    (dissoc db :sheet)))
