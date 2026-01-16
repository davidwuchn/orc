(ns store.runs.effects
  "Runs screen effects - handles API calls for BT execution traces."
  (:require [re-frame.core :as rf]
            [cljs.core.async :refer [go <!]]
            [components.api.interface :as api]
            [anomalies :refer [anomaly?]]))

;; =============================================================================
;; Screen Query Effect (Fat Query Model)
;; =============================================================================

(rf/reg-fx
  ::load-runs-screen
  (fn [{:keys [api-client trace-id status limit on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/query api-client
                                      (cond-> {:query/name :sheet/runs-screen}
                                        trace-id (assoc :trace-id trace-id)
                                        status (assoc :status status)
                                        limit (assoc :limit limit))))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

;; =============================================================================
;; Run Detail Screen Effect (Single trace for detail page)
;; =============================================================================

(rf/reg-fx
  ::load-run-detail-screen
  (fn [{:keys [api-client trace-id on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/query api-client
                                      {:query/name :sheet/run-detail-screen
                                       :trace-id trace-id}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))

;; =============================================================================
;; Node Trace Detail Effect (On-demand loading)
;; =============================================================================

(rf/reg-fx
  ::load-node-trace-detail
  (fn [{:keys [api-client trace-id node-id on-success on-failure]}]
    (when api-client
      (go
        (let [response (<! (api/query api-client
                                      {:query/name :sheet/node-trace-detail
                                       :trace-id trace-id
                                       :node-id node-id}))]
          (if (anomaly? response)
            (rf/dispatch (conj on-failure response))
            (rf/dispatch (conj on-success response))))))))
