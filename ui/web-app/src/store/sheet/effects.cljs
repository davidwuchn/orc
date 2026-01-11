(ns store.sheet.effects
  "Sheet store effects - handles API calls for sheet operations."
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

(rf/reg-fx
  ::create-sheet
  (fn [{:keys [api-client name description on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        (cond-> {:command/name :sheet/create-sheet
                                                 :name name}
                                          description (assoc :description description))))]
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
;; Cell Effects
;; =============================================================================

(rf/reg-fx
  ::create-cell
  (fn [{:keys [api-client sheet-id address on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/create-cell
                                         :sheet-id sheet-id
                                         :address address}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::set-cell-literal
  (fn [{:keys [api-client sheet-id cell-id fields on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/set-cell-literal
                                         :sheet-id sheet-id
                                         :cell-id cell-id
                                         :fields fields}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::set-cell-signature
  (fn [{:keys [api-client sheet-id cell-id signature on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/set-cell-signature
                                         :sheet-id sheet-id
                                         :cell-id cell-id
                                         :signature signature}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::bind-input
  (fn [{:keys [api-client sheet-id cell-id input-name source-cell-id source-field-name on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/bind-input
                                         :sheet-id sheet-id
                                         :cell-id cell-id
                                         :input-name input-name
                                         :source-cell-id source-cell-id
                                         :source-field-name source-field-name}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::unbind-input
  (fn [{:keys [api-client sheet-id cell-id input-name on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/unbind-input
                                         :sheet-id sheet-id
                                         :cell-id cell-id
                                         :input-name input-name}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::delete-cell
  (fn [{:keys [api-client sheet-id cell-id on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/delete-cell
                                         :sheet-id sheet-id
                                         :cell-id cell-id}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

;; =============================================================================
;; Execution Effects
;; =============================================================================

(rf/reg-fx
  ::request-execution
  (fn [{:keys [api-client sheet-id cell-id on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/request-cell-execution
                                         :sheet-id sheet-id
                                         :cell-id cell-id}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

(rf/reg-fx
  ::cancel-execution
  (fn [{:keys [api-client sheet-id execution-id on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/command api-client
                                        {:command/name :sheet/cancel-cell-execution
                                         :sheet-id sheet-id
                                         :execution-id execution-id}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

