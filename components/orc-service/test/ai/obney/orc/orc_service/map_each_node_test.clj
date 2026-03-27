(ns ai.obney.orc.orc-service.map-each-node-test
  "Tests for map-each node execution."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]))

;; =============================================================================
;; Test Functions
;; =============================================================================

(defn double-value [{:keys [inputs]}]
  (let [item (get inputs :current-item)]
    {:current-item (update item :value * 2)}))

(defn add-score [{:keys [inputs]}]
  (let [item (get inputs :current-item)]
    {:score (* (:id item) 10)}))

(def concurrency-tracker (atom {:max 0 :current 0}))

(defn tracked-process [{:keys [inputs]}]
  (swap! concurrency-tracker update :current inc)
  (swap! concurrency-tracker (fn [t] (assoc t :max (max (:max t) (:current t)))))
  (Thread/sleep 50)
  (swap! concurrency-tracker update :current dec)
  (let [item (get inputs :current-item)]
    {:current-item (assoc item :processed true)}))

;; =============================================================================
;; Map-Each Tests
;; =============================================================================

(deftest map-each-basic-test
  (testing "processes each item in the list"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Map Each Basic"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        ;; Declare blackboard
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :items [:vector :map]))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :current-item :map))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :results [:vector :map]))

        ;; Create map-each node
        (let [map-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :map-each))
              map-id (-> map-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-map-each-config-command sheet-id map-id
                                  :items :current-item :results))

          ;; Create child that doubles value
          (let [leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id map-id))
                leaf-id (-> leaf-result :command-result/events first :node-id)]

            (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                    :fn "ai.obney.orc.orc-service.map-each-node-test/double-value"))
            (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:current-item] [:current-item]))

            ;; Execute
            (let [result (sheet/execute ctx sheet-id {:items [{:id 1 :value 5}
                                                               {:id 2 :value 10}
                                                               {:id 3 :value 15}]})]
              (is (= :success (:status result)))
              (let [results (get-in result [:outputs :results])]
                (is (= 3 (count results)))
                (is (= 10 (:value (first results))))
                (is (= 20 (:value (second results))))
                (is (= 30 (:value (nth results 2))))))))))))

(deftest map-each-empty-list-test
  (testing "handles empty source list"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Map Each Empty"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :items [:vector :map]))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :current-item :map))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :results [:vector :map]))

        (let [map-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :map-each))
              map-id (-> map-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-map-each-config-command sheet-id map-id
                                  :items :current-item :results))

          (let [leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id map-id))
                leaf-id (-> leaf-result :command-result/events first :node-id)]

            (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                    :fn "ai.obney.orc.orc-service.map-each-node-test/double-value"))
            (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:current-item] [:current-item]))

            (let [result (sheet/execute ctx sheet-id {:items []})]
              (is (= :success (:status result)))
              (is (= [] (get-in result [:outputs :results]))))))))))

(deftest map-each-concurrency-test
  (testing "respects max-concurrency limit"
    (h/with-async-test-context [ctx]
      (reset! concurrency-tracker {:max 0 :current 0})

      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Map Each Concurrency"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :items [:vector :map]))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :current-item :map))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :results [:vector :map]))

        (let [map-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :map-each))
              map-id (-> map-result :command-result/events first :node-id)]

          ;; Set max-concurrency to 2
          (h/run-and-apply! ctx (h/make-set-map-each-config-command sheet-id map-id
                                  :items :current-item :results :max-concurrency 2))

          (let [leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id map-id))
                leaf-id (-> leaf-result :command-result/events first :node-id)]

            (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                    :fn "ai.obney.orc.orc-service.map-each-node-test/tracked-process"))
            (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:current-item] [:current-item]))

            ;; Execute with 6 items
            (let [result (sheet/execute ctx sheet-id {:items (mapv #(hash-map :id %) (range 6))})]
              (is (= :success (:status result)))
              (is (= 6 (count (get-in result [:outputs :results]))))
              ;; Max concurrent should not exceed 2
              (is (<= (:max @concurrency-tracker) 2)))))))))

(deftest map-each-with-parallel-child-test
  (testing "map-each with parallel child merges outputs into items"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Map Each + Parallel"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :items [:vector :map]))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :current-item :map))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :score :int))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :results [:vector :map]))

        ;; Create map-each
        (let [map-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :map-each))
              map-id (-> map-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-map-each-config-command sheet-id map-id
                                  :items :current-item :results))

          ;; Create child leaf that writes to separate key
          (let [leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id map-id))
                leaf-id (-> leaf-result :command-result/events first :node-id)]

            (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                    :fn "ai.obney.orc.orc-service.map-each-node-test/add-score"))
            (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:current-item] [:score]))

            ;; Execute
            (let [result (sheet/execute ctx sheet-id {:items [{:id 1 :name "A"}
                                                               {:id 2 :name "B"}
                                                               {:id 3 :name "C"}]})]
              (is (= :success (:status result)))
              (let [results (get-in result [:outputs :results])]
                (is (= 3 (count results)))
                ;; Each item should have the score merged in
                (is (= 10 (:score (first results))))
                (is (= 20 (:score (second results))))
                (is (= 30 (:score (nth results 2))))))))))))
