(ns store.sheet.events
  "Sheet store events - handles state changes for sheet operations.

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
    (let [cells-by-id (into {} (map (juxt :id identity) (:cells result)))]
      (-> db
          (assoc-in [:sheet :loading?] false)
          (assoc-in [:sheet :data] (:sheet result))
          (assoc-in [:sheet :cells] cells-by-id)
          (assoc-in [:sheet :dependency-graph] (:dependency-graph result))))))

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
  (fn [{:keys [db]} [_ api-client name description on-success]]
    {:db (assoc-in db [:sheets :creating?] true)
     ::sheet-fx/create-sheet {:api-client api-client
                              :name name
                              :description description
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
;; Cell Selection Events
;; =============================================================================

(rf/reg-event-db
  ::select-cell
  (fn [db [_ cell-id]]
    (assoc-in db [:sheet :selected-cell-id] cell-id)))

(rf/reg-event-db
  ::clear-selection
  (fn [db _]
    (update db :sheet dissoc :selected-cell-id)))

;; =============================================================================
;; Cell CRUD Events
;; =============================================================================

(rf/reg-event-fx
  ::create-cell
  (fn [_ [_ api-client sheet-id address]]
    {::sheet-fx/create-cell {:api-client api-client
                             :sheet-id sheet-id
                             :address address
                             :on-success [::cell-command-success api-client sheet-id]
                             :on-failure [::cell-operation-failure]}}))

(rf/reg-event-fx
  ::set-cell-literal
  (fn [_ [_ api-client sheet-id cell-id fields]]
    {::sheet-fx/set-cell-literal {:api-client api-client
                                  :sheet-id sheet-id
                                  :cell-id cell-id
                                  :fields fields
                                  :on-success [::cell-command-success api-client sheet-id]
                                  :on-failure [::cell-operation-failure]}}))

(rf/reg-event-fx
  ::set-cell-signature
  (fn [_ [_ api-client sheet-id cell-id signature]]
    {::sheet-fx/set-cell-signature {:api-client api-client
                                    :sheet-id sheet-id
                                    :cell-id cell-id
                                    :signature signature
                                    :on-success [::cell-command-success api-client sheet-id]
                                    :on-failure [::cell-operation-failure]}}))

(rf/reg-event-fx
  ::bind-input
  (fn [_ [_ api-client sheet-id cell-id input-name source-cell-id source-field-name]]
    {::sheet-fx/bind-input {:api-client api-client
                            :sheet-id sheet-id
                            :cell-id cell-id
                            :input-name input-name
                            :source-cell-id source-cell-id
                            :source-field-name source-field-name
                            :on-success [::cell-command-success api-client sheet-id]
                            :on-failure [::cell-operation-failure]}}))

(rf/reg-event-fx
  ::unbind-input
  (fn [_ [_ api-client sheet-id cell-id input-name]]
    {::sheet-fx/unbind-input {:api-client api-client
                              :sheet-id sheet-id
                              :cell-id cell-id
                              :input-name input-name
                              :on-success [::cell-command-success api-client sheet-id]
                              :on-failure [::cell-operation-failure]}}))

(rf/reg-event-fx
  ::delete-cell
  (fn [{:keys [db]} [_ api-client sheet-id cell-id]]
    {:db (update db :sheet dissoc :selected-cell-id)
     ::sheet-fx/delete-cell {:api-client api-client
                             :sheet-id sheet-id
                             :cell-id cell-id
                             :on-success [::cell-command-success api-client sheet-id]
                             :on-failure [::cell-operation-failure]}}))

;; Generic success handler for cell commands - just refetch the screen
(rf/reg-event-fx
  ::cell-command-success
  (fn [_ [_ api-client sheet-id _response]]
    {:dispatch [::load-sheet-view-screen api-client sheet-id]}))

(rf/reg-event-db
  ::cell-operation-failure
  (fn [db [_ error]]
    (assoc-in db [:sheet :error] error)))

;; =============================================================================
;; Execution Events
;; =============================================================================

(rf/reg-event-fx
  ::request-execution
  (fn [{:keys [db]} [_ api-client sheet-id cell-id]]
    {:db (assoc-in db [:sheet :cells cell-id :execution-status] :pending)
     ::sheet-fx/request-execution {:api-client api-client
                                   :sheet-id sheet-id
                                   :cell-id cell-id
                                   :on-success [::cell-command-success api-client sheet-id]
                                   :on-failure [::request-execution-failure cell-id]}}))

(rf/reg-event-db
  ::request-execution-failure
  (fn [db [_ cell-id error]]
    (-> db
        (assoc-in [:sheet :cells cell-id :execution-status] :failed)
        (assoc-in [:sheet :cells cell-id :last-error] (:cognitect.anomalies/message error)))))

;; =============================================================================
;; Real-time Update Events (for WebSocket/SSE updates)
;; =============================================================================

(rf/reg-event-db
  ::cell-execution-completed
  (fn [db [_ cell-id outputs]]
    (-> db
        (assoc-in [:sheet :cells cell-id :fields] outputs)
        (assoc-in [:sheet :cells cell-id :execution-status] :completed)
        (assoc-in [:sheet :cells cell-id :status] :valid)
        (assoc-in [:sheet :cells cell-id :last-error] nil))))

(rf/reg-event-db
  ::cell-execution-failed
  (fn [db [_ cell-id error]]
    (-> db
        (assoc-in [:sheet :cells cell-id :execution-status] :failed)
        (assoc-in [:sheet :cells cell-id :status] :error)
        (assoc-in [:sheet :cells cell-id :last-error] error))))

;; =============================================================================
;; Clear Sheet State
;; =============================================================================

(rf/reg-event-db
  ::clear-sheet
  (fn [db _]
    (dissoc db :sheet)))
