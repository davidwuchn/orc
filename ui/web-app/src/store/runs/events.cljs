(ns store.runs.events
  "Runs screen events - handles state changes for BT execution traces."
  (:require [re-frame.core :as rf]
            [store.runs.effects :as runs-fx]))

;; =============================================================================
;; Runs Screen Events
;; =============================================================================

(rf/reg-event-fx
  ::load-runs-screen
  (fn [{:keys [db]} [_ api-client]]
    (let [has-data? (seq (get-in db [:runs :list]))
          trace-id (get-in db [:runs :selected-trace-id])
          status (get-in db [:runs :filters :status])]
      {:db (cond-> db
             (not has-data?) (assoc-in [:runs :loading?] true)
             true (assoc-in [:runs :error] nil))
       ::runs-fx/load-runs-screen {:api-client api-client
                                   :trace-id trace-id
                                   :status status
                                   :on-success [::load-runs-screen-success]
                                   :on-failure [::load-runs-screen-failure]}})))

(rf/reg-event-db
  ::load-runs-screen-success
  (fn [db [_ result]]
    (-> db
        (assoc-in [:runs :loading?] false)
        (assoc-in [:runs :list] (:traces result))
        (assoc-in [:runs :total] (:total result))
        (assoc-in [:runs :selected-trace] (:selected-trace result)))))

(rf/reg-event-db
  ::load-runs-screen-failure
  (fn [db [_ error]]
    (-> db
        (assoc-in [:runs :loading?] false)
        (assoc-in [:runs :error] error))))

;; =============================================================================
;; Selection Events
;; =============================================================================

(rf/reg-event-fx
  ::select-trace
  (fn [{:keys [db]} [_ api-client trace-id]]
    (let [status (get-in db [:runs :filters :status])]
      {:db (assoc-in db [:runs :selected-trace-id] trace-id)
       ::runs-fx/load-runs-screen {:api-client api-client
                                   :trace-id trace-id
                                   :status status
                                   :on-success [::load-runs-screen-success]
                                   :on-failure [::load-runs-screen-failure]}})))

(rf/reg-event-fx
  ::clear-selected-trace
  (fn [{:keys [db]} [_ api-client]]
    (let [status (get-in db [:runs :filters :status])]
      {:db (-> db
               (assoc-in [:runs :selected-trace-id] nil)
               (assoc-in [:runs :selected-trace] nil))
       ::runs-fx/load-runs-screen {:api-client api-client
                                   :status status
                                   :on-success [::load-runs-screen-success]
                                   :on-failure [::load-runs-screen-failure]}})))

;; =============================================================================
;; Filter Events
;; =============================================================================

(rf/reg-event-fx
  ::set-status-filter
  (fn [{:keys [db]} [_ api-client status]]
    (let [trace-id (get-in db [:runs :selected-trace-id])]
      {:db (assoc-in db [:runs :filters :status] status)
       ::runs-fx/load-runs-screen {:api-client api-client
                                   :trace-id trace-id
                                   :status status
                                   :on-success [::load-runs-screen-success]
                                   :on-failure [::load-runs-screen-failure]}})))

;; =============================================================================
;; Node Trace Detail Events (On-demand loading)
;; =============================================================================

(rf/reg-event-fx
  ::load-node-trace-detail
  (fn [{:keys [db]} [_ api-client trace-id node-id]]
    {:db (-> db
             (assoc-in [:runs :node-detail :loading?] true)
             (assoc-in [:runs :node-detail :node-id] node-id)
             (assoc-in [:runs :node-detail :error] nil)
             (assoc-in [:runs :node-detail :data] nil))
     ::runs-fx/load-node-trace-detail {:api-client api-client
                                       :trace-id trace-id
                                       :node-id node-id
                                       :on-success [::load-node-trace-detail-success]
                                       :on-failure [::load-node-trace-detail-failure]}}))

(rf/reg-event-db
  ::load-node-trace-detail-success
  (fn [db [_ result]]
    (-> db
        (assoc-in [:runs :node-detail :loading?] false)
        (assoc-in [:runs :node-detail :data] result))))

(rf/reg-event-db
  ::load-node-trace-detail-failure
  (fn [db [_ error]]
    (-> db
        (assoc-in [:runs :node-detail :loading?] false)
        (assoc-in [:runs :node-detail :error] error))))

(rf/reg-event-db
  ::clear-node-detail
  (fn [db _]
    (assoc-in db [:runs :node-detail] nil)))

;; =============================================================================
;; Single Trace Load Events (for detail page)
;; =============================================================================

(rf/reg-event-fx
  ::load-trace
  (fn [{:keys [db]} [_ api-client trace-id]]
    {:db (-> db
             (assoc-in [:runs :selected-trace-id] trace-id)
             (assoc-in [:runs :selected-trace-loading?] true)
             (assoc-in [:runs :selected-trace] nil))
     ::runs-fx/load-run-detail-screen {:api-client api-client
                                       :trace-id trace-id
                                       :on-success [::load-trace-success]
                                       :on-failure [::load-trace-failure]}}))

(rf/reg-event-db
  ::load-trace-success
  (fn [db [_ result]]
    (-> db
        (assoc-in [:runs :selected-trace] (:trace result))
        (assoc-in [:runs :selected-trace-loading?] false))))

(rf/reg-event-db
  ::load-trace-failure
  (fn [db [_ error]]
    (-> db
        (assoc-in [:runs :selected-trace-loading?] false)
        (assoc-in [:runs :error] error))))
