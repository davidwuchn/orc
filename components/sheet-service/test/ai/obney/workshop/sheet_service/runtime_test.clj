(ns ai.obney.workshop.sheet-service.runtime-test
  "Tests for the synchronous execute API."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.workshop.sheet-service.test-helpers :as h]
            [ai.obney.workshop.sheet-service.interface :as sheet]))

;; =============================================================================
;; Test Functions for Code Executor
;; =============================================================================

(defn identity-fn
  "Returns the input as output."
  [{:keys [inputs]}]
  {"output" (get inputs "input")})

(defn transform-fn
  "Transforms input by uppercasing."
  [{:keys [inputs]}]
  {"output" (clojure.string/upper-case (get inputs "input"))})

(defn multi-output-fn
  "Returns multiple outputs."
  [{:keys [inputs]}]
  (let [x (get inputs "x")]
    {"doubled" (* x 2)
     "squared" (* x x)}))

(defn slow-fn
  "Simulates slow operation."
  [{:keys [inputs]}]
  (Thread/sleep 200)
  {"output" "done"})

;; =============================================================================
;; Execute API Tests
;; =============================================================================

(deftest execute-simple-sequence-test
  (testing "executes a sequence of code nodes"
    (h/with-test-context [ctx]
      ;; Create sheet
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Simple Sequence"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        ;; Declare blackboard
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id "input" :string))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id "output" :string))

        ;; Create sequence with one leaf
        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)
              leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
              leaf-id (-> leaf-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                  :fn "ai.obney.workshop.sheet-service.runtime-test/transform-fn"))
          (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id ["input"] ["output"]))

          ;; Execute
          (let [result (sheet/execute ctx sheet-id {"input" "hello"})]
            (is (= :success (:status result)))
            (is (= "HELLO" (get-in result [:outputs "output"])))))))))

(deftest execute-isolated-blackboard-test
  (testing "execution doesn't mutate sheet's stored blackboard"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Isolation Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        ;; Declare blackboard with initial value
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id "input" :string))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id "output" :string))
        (h/run-and-apply! ctx (h/make-set-key-value-command sheet-id "input" "original"))

        ;; Create leaf
        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)
              leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
              leaf-id (-> leaf-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                  :fn "ai.obney.workshop.sheet-service.runtime-test/identity-fn"))
          (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id ["input"] ["output"]))

          ;; Execute with different input
          (let [result (sheet/execute ctx sheet-id {"input" "overridden"})]
            (is (= :success (:status result)))
            (is (= "overridden" (get-in result [:outputs "output"]))))

          ;; Verify original blackboard unchanged
          (let [bb (sheet/get-blackboard-by-key (:event-store ctx) sheet-id)]
            (is (= "original" (get-in bb ["input" :value])))))))))

(deftest execute-timeout-test
  (testing "returns timeout status when execution exceeds limit"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Timeout Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id "output" :string))

        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)
              leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
              leaf-id (-> leaf-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                  :fn "ai.obney.workshop.sheet-service.runtime-test/slow-fn"))
          (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [] ["output"]))

          ;; Execute with very short timeout
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 50)]
            (is (= :timeout (:status result)))
            (is (some? (:error result)))))))))

(deftest execute-multi-output-test
  (testing "captures multiple outputs from a node"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Multi Output"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id "x" :int))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id "doubled" :int))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id "squared" :int))

        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)
              leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
              leaf-id (-> leaf-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                  :fn "ai.obney.workshop.sheet-service.runtime-test/multi-output-fn"))
          (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id ["x"] ["doubled" "squared"]))

          (let [result (sheet/execute ctx sheet-id {"x" 5})]
            (is (= :success (:status result)))
            (is (= 10 (get-in result [:outputs "doubled"])))
            (is (= 25 (get-in result [:outputs "squared"])))))))))
