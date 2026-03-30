(ns ai.obney.orc.gepa.budget-test
  "Tests that subsample evaluations are counted against the metric call budget."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.orc.gepa.core.commands]
            [ai.obney.orc.gepa.core.read-models :as rm]
            [ai.obney.orc.gepa.interface.schemas]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]))

(defn- seed-events! [ctx events]
  (es/append (:event-store ctx) {:tenant-id (:tenant-id ctx) :events (vec events)}))

(defn- start-optimization! [ctx sheet-id]
  (let [result (tu/process-command! ctx
                 {:command/name :gepa/start-optimization
                  :sheet-id sheet-id
                  :trainset [{"question" "What is 2+2?" "instruction" "Answer"}]
                  :valset [{"question" "What is 3+3?" "instruction" "Answer"}]
                  :config {:max-metric-calls 10}})
        _ (assert (nil? (:cognitect.anomalies/category result))
                  (str "start-optimization failed: " (:cognitect.anomalies/message result)
                       " " (:error/explain result)))]
    (tu/apply-events! ctx result)
    (get-in result [:command/result :optimization-id])))

(deftest subsample-metric-calls-counted-in-budget-test
  (testing "subsample-evaluated events increment total-metric-calls in optimization-state"
    (tu/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            opt-id (start-optimization! ctx sheet-id)]
        ;; Initially 0 metric calls
        (is (= 0 (:total-metric-calls (rm/get-optimization-summary ctx opt-id))))

        ;; Seed a subsample-evaluated event with 4 metric calls
        (seed-events! ctx
          [(->event {:type :gepa/subsample-evaluated
                     :tags #{[:optimization opt-id]}
                     :body {:optimization-id opt-id
                            :parent-id (random-uuid)
                            :proposed-instructions {"instruction" "Be better"}
                            :component-updated "instruction"
                            :subsample-indices [0]
                            :parent-scores [0.5]
                            :proposed-scores [0.6]
                            :parent-sum 0.5
                            :proposed-sum 0.6
                            :accepted? true
                            :metric-calls 4
                            :evaluated-at (str (java.time.Instant/now))}})])

        ;; Now should have 4 metric calls
        (is (= 4 (:total-metric-calls (rm/get-optimization-summary ctx opt-id)))))))

  (testing "budget-exhausted? returns true when subsample calls exceed budget"
    (tu/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            opt-id (start-optimization! ctx sheet-id)]
        ;; Not exhausted initially
        (is (false? (rm/budget-exhausted? ctx opt-id)))

        ;; Seed 3 subsample evaluations with 4 metric calls each = 12 total
        (seed-events! ctx
          (for [_ (range 3)]
            (->event {:type :gepa/subsample-evaluated
                      :tags #{[:optimization opt-id]}
                      :body {:optimization-id opt-id
                             :parent-id (random-uuid)
                             :proposed-instructions {"instruction" "test"}
                             :component-updated "instruction"
                             :subsample-indices [0]
                             :parent-scores [0.5]
                             :proposed-scores [0.6]
                             :parent-sum 0.5
                             :proposed-sum 0.6
                             :accepted? false
                             :metric-calls 4
                             :evaluated-at (str (java.time.Instant/now))}})))

        ;; 12 > 10 (max-metric-calls) → budget exhausted
        (is (true? (rm/budget-exhausted? ctx opt-id))))))

  (testing "get-optimization-summary returns non-nil after start"
    (tu/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            opt-id (start-optimization! ctx sheet-id)
            summary (rm/get-optimization-summary ctx opt-id)]
        (is (some? summary) "summary should not be nil")
        (println "SUMMARY:" (pr-str summary))
        (when summary
          (is (= 0 (:total-metric-calls summary)))
          (is (= :running (:status summary)))))))

  (testing "candidate-evaluated metric calls still counted"
    (tu/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            opt-id (start-optimization! ctx sheet-id)
            cand-id (let [r (tu/process-command! ctx
                             {:command/name :gepa/create-candidate
                              :optimization-id opt-id
                              :instructions {"instruction" "Be helpful"}
                              :parent-ids [nil]
                              :mutation-reason nil})]
                      (tu/apply-events! ctx r)
                      (get-in r [:command/result :candidate-id]))
            _ (let [r (tu/process-command! ctx
                        {:command/name :gepa/record-evaluation-result
                         :optimization-id opt-id
                         :candidate-id cand-id
                         :scores [0.8]
                         :trace-ids [(random-uuid)]
                         :metric-calls 2})]
                (tu/apply-events! ctx r))]
        ;; Should have metric calls from the candidate evaluation
        (let [calls (:total-metric-calls (rm/get-optimization-summary ctx opt-id))]
          (is (pos? calls) "metric calls should be > 0 after evaluation")
          (is (>= calls 2) "should include at least the 2 metric calls from record-evaluation-result"))))))
