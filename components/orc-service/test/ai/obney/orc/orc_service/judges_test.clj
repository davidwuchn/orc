(ns ai.obney.orc.orc-service.judges-test
  "Tests for evaluation judges DSL and commands."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as rm]))

;; =============================================================================
;; DSL Tests
;; =============================================================================

(deftest judges-dsl-creates-schema-test
  (testing "judges function creates judges-schema map"
    (let [judges-def (sheet/judges
                       {:my-grounding {:type :grounding
                                       :criteria "Test criteria"
                                       :weight 0.5}})]
      (is (contains? judges-def :judges-schema))
      (is (= :grounding (get-in judges-def [:judges-schema :my-grounding :type])))
      (is (= "Test criteria" (get-in judges-def [:judges-schema :my-grounding :criteria])))
      (is (= 0.5 (get-in judges-def [:judges-schema :my-grounding :weight]))))))

(deftest judges-dsl-multiple-judges-test
  (testing "judges function accepts multiple judge definitions"
    (let [judges-def (sheet/judges
                       {:grounding-judge {:type :grounding
                                          :criteria "All claims must cite sources"}
                        :completeness-judge {:type :completeness
                                             :criteria "Must include X, Y, Z"
                                             :weight 0.3}
                        :reasoning-judge {:type :reasoning
                                          :criteria "Logic must be clear"}})]
      (is (= 3 (count (:judges-schema judges-def))))
      (is (contains? (:judges-schema judges-def) :grounding-judge))
      (is (contains? (:judges-schema judges-def) :completeness-judge))
      (is (contains? (:judges-schema judges-def) :reasoning-judge)))))

(deftest workflow-with-judges-test
  (testing "workflow accepts judges definition"
    (let [workflow (sheet/workflow "test-with-judges"
                     (sheet/blackboard {:input :string :output :string})
                     (sheet/judges
                       {:my-judge {:type :completeness
                                   :criteria "Must be complete"}})
                     (sheet/sequence "main"
                       (sheet/code "process"
                         :fn "some.ns/fn"
                         :reads [:input]
                         :writes [:output])))]
      (is (some? (:judges-schema workflow)))
      (is (= :completeness (get-in workflow [:judges-schema :my-judge :type]))))))

(deftest llm-node-with-judges-test
  (testing "llm node accepts judges parameter"
    (let [workflow (sheet/workflow "llm-with-judges"
                     (sheet/blackboard {:input :string :output :string})
                     (sheet/judges
                       {:test-judge {:type :grounding :criteria "Test"}})
                     (sheet/llm "analyze"
                       :model "test/model"
                       :instruction "Analyze"
                       :reads [:input]
                       :writes [:output]
                       :judges ["test-judge"]))
          ;; The llm node is the root node when it's the only node
          llm-node (:root-node workflow)]
      (is (= ["test-judge"] (:judges llm-node))))))

;; =============================================================================
;; Command Tests
;; =============================================================================

(deftest declare-judge-command-test
  (h/with-test-context [ctx]
    (testing "declare-judge emits judge-declared event"
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Judge Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)
            result (h/run-command ctx (h/make-declare-judge-command
                                        sheet-id
                                        "my-grounding"
                                        {:type :grounding
                                         :criteria "All claims must be supported"
                                         :weight 0.35}))]
        (is (= :sheet/judge-declared (h/get-event-type result)))
        (let [event (h/get-event-body result)]
          (is (= sheet-id (:sheet-id event)))
          (is (= "my-grounding" (:judge-name event)))
          (is (= :grounding (get-in event [:judge-config :type]))))))))

(deftest declare-judge-requires-existing-sheet-test
  (h/with-test-context [ctx]
    (testing "declare-judge fails for non-existent sheet"
      (let [result (h/run-command ctx (h/make-declare-judge-command
                                        (random-uuid)
                                        "my-judge"
                                        {:type :grounding}))]
        (is (h/is-anomaly? result))
        (is (= :cognitect.anomalies/not-found (h/anomaly-category result)))))))

(deftest set-node-judges-command-test
  (h/with-test-context [ctx]
    (testing "set-node-judges assigns judges to a leaf node"
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Node Judges Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)
            ;; Declare judge first
            _ (h/run-and-apply! ctx (h/make-declare-judge-command
                                      sheet-id
                                      "my-judge"
                                      {:type :completeness :criteria "Test"}))
            ;; Create a leaf node
            seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
            seq-id (-> seq-result :command-result/events first :node-id)
            leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
            leaf-id (-> leaf-result :command-result/events first :node-id)
            ;; Set judges on node
            result (h/run-command ctx (h/make-set-node-judges-command
                                        sheet-id
                                        leaf-id
                                        ["my-judge"]))]
        (is (= :sheet/node-judges-set (h/get-event-type result)))
        (let [event (h/get-event-body result)]
          (is (= leaf-id (:node-id event)))
          (is (= ["my-judge"] (:judges event))))))))

(deftest set-node-judges-validates-judge-exists-test
  (h/with-test-context [ctx]
    (testing "set-node-judges fails when referencing non-existent judge"
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Validation Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)
            seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
            seq-id (-> seq-result :command-result/events first :node-id)
            leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
            leaf-id (-> leaf-result :command-result/events first :node-id)
            result (h/run-command ctx (h/make-set-node-judges-command
                                        sheet-id
                                        leaf-id
                                        ["non-existent-judge"]))]
        (is (h/is-anomaly? result))
        (is (= :cognitect.anomalies/not-found (h/anomaly-category result)))))))

;; =============================================================================
;; Read Model Tests
;; =============================================================================

(deftest judges-read-model-test
  (h/with-test-context [ctx]
    (testing "judges are queryable from read model after declaration"
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Read Model Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)
            _ (h/run-and-apply! ctx (h/make-declare-judge-command
                                      sheet-id
                                      "grounding-judge"
                                      {:type :grounding
                                       :criteria "Must cite sources"
                                       :weight 0.4}))
            _ (h/run-and-apply! ctx (h/make-declare-judge-command
                                      sheet-id
                                      "completeness-judge"
                                      {:type :completeness
                                       :criteria "Must cover all topics"
                                       :weight 0.3}))
            judges (rm/get-judges ctx sheet-id)]
        (is (= 2 (count judges)))
        (is (contains? judges "grounding-judge"))
        (is (contains? judges "completeness-judge"))
        (is (= :grounding (get-in judges ["grounding-judge" :type])))
        (is (= "Must cite sources" (get-in judges ["grounding-judge" :criteria])))))))

(deftest node-judges-in-read-model-test
  (h/with-test-context [ctx]
    (testing "node judges references stored in node read model"
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Node RM Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)
            _ (h/run-and-apply! ctx (h/make-declare-judge-command
                                      sheet-id
                                      "test-judge"
                                      {:type :reasoning :criteria "Be logical"}))
            seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
            seq-id (-> seq-result :command-result/events first :node-id)
            leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
            leaf-id (-> leaf-result :command-result/events first :node-id)
            _ (h/run-and-apply! ctx (h/make-set-node-judges-command
                                      sheet-id
                                      leaf-id
                                      ["test-judge"]))
            node (rm/get-node ctx sheet-id leaf-id)]
        (is (= ["test-judge"] (:judges node)))))))

;; =============================================================================
;; Build Integration Tests
;; =============================================================================

(deftest build-workflow-declares-judges-test
  (h/with-test-context [ctx]
    (testing "build-workflow! declares all judges from workflow definition"
      (let [workflow (sheet/workflow "build-judges-test"
                       (sheet/blackboard {:input :string :output :string})
                       (sheet/judges
                         {:judge-a {:type :grounding :criteria "Criteria A" :weight 0.5}
                          :judge-b {:type :completeness :criteria "Criteria B" :weight 0.5}})
                       (sheet/code "process"
                         :fn "some.ns/fn"
                         :reads [:input]
                         :writes [:output]))
            sheet-id (sheet/build-workflow! ctx workflow)
            judges (rm/get-judges ctx sheet-id)]
        (is (= 2 (count judges)))
        (is (contains? judges "judge-a"))
        (is (contains? judges "judge-b"))))))

(deftest build-workflow-sets-node-judges-test
  (h/with-test-context [ctx]
    (testing "build-workflow! sets judges on nodes that reference them"
      (let [workflow (sheet/workflow "build-node-judges-test"
                       (sheet/blackboard {:input :string :output :string})
                       (sheet/judges
                         {:my-judge {:type :instruction-following
                                     :criteria "Follow instructions precisely"}})
                       (sheet/sequence "main"
                         (sheet/code "analyze"
                           :fn "some.ns/analyze-fn"
                           :reads [:input]
                           :writes [:output]
                           :judges ["my-judge"])))
            sheet-id (sheet/build-workflow! ctx workflow)
            nodes (rm/get-nodes-for-sheet ctx sheet-id)
            ;; nodes is a seq of node maps, find the leaf node
            leaf-node (first (filter #(= :leaf (:type %)) nodes))]
        (is (some? leaf-node))
        (is (= ["my-judge"] (:judges leaf-node)))))))
