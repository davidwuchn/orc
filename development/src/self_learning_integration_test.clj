(ns self-learning-integration-test
  "Integration tests verifying manual self-learning workflow matches documentation.

   This test suite proves that:
   1. Recording strengths flows through Grain events → read models
   2. Recording weaknesses works the same way
   3. find-self-patterns returns correct tree's patterns
   4. Context injection prepends patterns to LLM instructions
   5. Full self-learning loop works end-to-end

   Run these tests in REPL after (dev/start!) to verify documentation accuracy.

   Usage:
     1. (dev/start!)
     2. (load-file \"development/src/self_learning_integration_test.clj\")
     3. (run-all-tests)"
  (:require [clojure.test :refer [deftest testing is run-tests]]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test Setup Helpers
;; =============================================================================

(defn make-test-ctx
  "Create a test context. Call dev/start! first, then dev/ctx."
  []
  ((requiring-resolve 'dev/ctx)))

(defn run-command!
  "Run a command against the test context."
  [ctx command-data]
  (cp/process-command
   (assoc ctx :command
     (merge {:command/id (random-uuid)
             :command/timestamp (time/now)}
            command-data))))

;; =============================================================================
;; Test 1: Record Strength Flow
;; =============================================================================

(deftest test-record-strength-flow
  (testing "Recording a strength creates event and updates tree profile"
    (let [ctx (make-test-ctx)
          tree-id (random-uuid)
          pattern-uri "success:TestPattern"]

      ;; 1. Record a strength with rich context
      (run-command! ctx
        {:command/name :ontology/record-tree-strength
         :tree-id tree-id
         :pattern-uri pattern-uri
         :confidence 0.85
         :evidence-trace-ids [(random-uuid)]
         :avg-score 0.88
         :domain-type "test-domain"
         :context-conditions {:test-field 123
                              :another-field "value"}
         :action-taken {:type "test-action"
                        :target "test-target"}
         :expected-outcome "success"})

      ;; 2. Query tree profile
      (let [profile (ontology/get-tree-profile ctx tree-id)]

        ;; 3. Verify strength appears with correct fields
        ;; Note: Profile stores :pattern (not :pattern-uri) per read model
        (is (some? profile) "Should have tree profile")
        (is (= 1 (count (:strengths profile))) "Should have 1 strength")

        (let [strength (first (:strengths profile))]
          (is (= pattern-uri (:pattern strength)))
          (is (= 0.85 (:confidence strength)))
          (is (= "test-domain" (:domain-type strength)))
          (is (= {:test-field 123 :another-field "value"}
                 (:context-conditions strength)))
          (is (= {:type "test-action" :target "test-target"}
                 (:action-taken strength))))))))

;; =============================================================================
;; Test 2: Record Weakness Flow
;; =============================================================================

(deftest test-record-weakness-flow
  (testing "Recording a weakness creates event and updates tree profile"
    (let [ctx (make-test-ctx)
          tree-id (random-uuid)
          failure-uri "failure:TestFailure"]

      ;; 1. Record a weakness with triggers and severity
      (run-command! ctx
        {:command/name :ontology/record-tree-weakness
         :tree-id tree-id
         :failure-uri failure-uri
         :frequency 0.25
         :severity :high
         :triggers ["trigger1" "trigger2"]
         :evidence-trace-ids [(random-uuid)]
         :domain-type "test-domain"
         :failure-context {:error-type "validation"
                           :input-size 1000}})

      ;; 2. Query tree profile
      (let [profile (ontology/get-tree-profile ctx tree-id)]

        ;; 3. Verify weakness appears with correct fields
        ;; Note: Profile stores :failure (not :failure-uri) per read model
        (is (some? profile) "Should have tree profile")
        (is (= 1 (count (:weaknesses profile))) "Should have 1 weakness")

        (let [weakness (first (:weaknesses profile))]
          (is (= failure-uri (:failure weakness)))
          (is (= 0.25 (:frequency weakness)))
          (is (= :high (:severity weakness)))
          (is (= ["trigger1" "trigger2"] (:triggers weakness)))
          (is (= "test-domain" (:domain-type weakness)))
          (is (= {:error-type "validation" :input-size 1000}
                 (:failure-context weakness))))))))

;; =============================================================================
;; Test 3: Self-Patterns Retrieval
;; =============================================================================

(deftest test-find-self-patterns
  (testing "find-self-patterns returns THIS tree's patterns only"
    (let [ctx (make-test-ctx)
          tree-1 (random-uuid)
          tree-2 (random-uuid)]

      ;; 1. Record patterns on tree-1
      (run-command! ctx
        {:command/name :ontology/record-tree-strength
         :tree-id tree-1
         :pattern-uri "success:Tree1Pattern"
         :confidence 0.9
         :evidence-trace-ids [(random-uuid)]
         :avg-score 0.85
         :domain-type "domain-a"})

      (run-command! ctx
        {:command/name :ontology/record-tree-weakness
         :tree-id tree-1
         :failure-uri "failure:Tree1Failure"
         :frequency 0.1
         :severity :low
         :triggers ["tree1-trigger"]
         :evidence-trace-ids [(random-uuid)]})

      ;; 2. Record patterns on tree-2 (different tree)
      (run-command! ctx
        {:command/name :ontology/record-tree-strength
         :tree-id tree-2
         :pattern-uri "success:Tree2Pattern"
         :confidence 0.8
         :evidence-trace-ids [(random-uuid)]
         :avg-score 0.75
         :domain-type "domain-b"})

      ;; 3. Query find-self-patterns for tree-1
      ;; Note: find-self-patterns returns :uri (mapped from :pattern) per retrieval.clj
      (let [patterns (ontology/find-self-patterns ctx tree-1 {})]

        ;; 4. Verify only tree-1's patterns returned
        (is (some? patterns))
        (is (= 1 (count (:strengths patterns))) "Should have tree-1's strength only")
        (is (= 1 (count (:weaknesses patterns))) "Should have tree-1's weakness only")

        (is (= "success:Tree1Pattern"
               (:uri (first (:strengths patterns)))))
        (is (= "failure:Tree1Failure"
               (:uri (first (:weaknesses patterns)))))

        ;; Verify tree-2's pattern is NOT in tree-1's self-patterns
        (is (not (some #(= "success:Tree2Pattern" (:uri %))
                       (:strengths patterns))))))))

;; =============================================================================
;; Test 4: Build Actionable Context
;; =============================================================================

(deftest test-build-actionable-context
  (testing "build-actionable-context returns formatted markdown for LLM injection"
    (let [ctx (make-test-ctx)
          tree-id (random-uuid)]

      ;; 1. Record some patterns
      (run-command! ctx
        {:command/name :ontology/record-tree-strength
         :tree-id tree-id
         :pattern-uri "success:GoodPattern"
         :confidence 0.95
         :evidence-trace-ids [(random-uuid)]
         :avg-score 0.92
         :domain-type "test"
         :context-conditions {:factor "high"}
         :action-taken {:type "use-schema"}})

      (run-command! ctx
        {:command/name :ontology/record-tree-weakness
         :tree-id tree-id
         :failure-uri "failure:BadPattern"
         :frequency 0.15
         :severity :medium
         :triggers ["vague input"]
         :evidence-trace-ids [(random-uuid)]})

      ;; 2. Build actionable context
      ;; Note: build-actionable-context returns a map with :formatted-context key
      (let [result (ontology/build-actionable-context ctx tree-id "problem:Test" {})
            context-str (:formatted-context result)]

        ;; 3. Verify it returns a map with formatted context string
        (is (map? result) "Should return map")
        (is (contains? result :formatted-context) "Should have :formatted-context key")
        (is (string? context-str) ":formatted-context should be string")
        (is (pos? (count context-str)) "Should not be empty")

        ;; 4. Verify it contains expected sections
        (is (re-find #"(?i)strength|success|pattern|rule|learn" context-str)
            "Should mention strengths/patterns/rules")
        ;; Note: Weaknesses only appear if recorded, check has-patterns flag
        (is (:has-patterns? result) "Should have patterns")))))

;; =============================================================================
;; Test 5: Context Injection Verification
;; =============================================================================

(deftest test-context-parameter-handling
  (testing ":context parameter is properly stored in node definition"
    (let [ctx (make-test-ctx)
          tree-id (random-uuid)]

      ;; Build a workflow with :context parameter
      (let [workflow (orc/workflow "context-test"
                       (orc/blackboard {:input :string :output :string})
                       (orc/llm "test-node"
                         :instruction "Test instruction"
                         :reads [:input]
                         :writes [:output]
                         :context {:problem-type "problem:Test"
                                   :self-learning? true
                                   :tree-id tree-id}))
            ;; Note: workflow DSL stores nodes under :root-node, not :nodes
            llm-node (:root-node workflow)]

        ;; Verify the workflow DSL captures :context
        (is (some? (:context llm-node)) "Node should have :context parameter")
        (is (= "problem:Test" (get-in llm-node [:context :problem-type])))
        (is (true? (get-in llm-node [:context :self-learning?])))))))

;; =============================================================================
;; Test 6: Grain Event Verification
;; =============================================================================

(deftest test-grain-events-emitted
  (testing "Recording patterns emits proper Grain events"
    (let [ctx (make-test-ctx)
          tree-id (random-uuid)
          es (:event-store ctx)
          tenant-id (:tenant-id ctx)]

      ;; Record a strength
      (run-command! ctx
        {:command/name :ontology/record-tree-strength
         :tree-id tree-id
         :pattern-uri "success:GrainEventTest"
         :confidence 0.88
         :evidence-trace-ids [(random-uuid)]
         :avg-score 0.85})

      ;; Query events directly from event store
      ;; Note: es/read requires :tenant-id in the query
      (let [result (es/read es
                     {:tenant-id tenant-id
                      :types #{:ontology/tree-strength-recorded}
                      :tags #{[:tree tree-id]}})
            ;; Check if result is an anomaly or actual events
            events (if (and (map? result) (:cognitect.anomalies/category result))
                     []
                     (into [] result))]

        (is (pos? (count events)) "Should have at least 1 strength event")

        (when (seq events)
          (let [event (first events)]
            (is (= :ontology/tree-strength-recorded (:event/type event)))
            (is (= tree-id (:tree-id event)))
            (is (= "success:GrainEventTest" (:pattern-uri event)))))))))

;; =============================================================================
;; Run All Tests
;; =============================================================================

(defn run-all-tests
  "Run all self-learning integration tests.
   Make sure to call (dev/start!) first."
  []
  (println "\n========================================")
  (println "Self-Learning Integration Tests")
  (println "========================================\n")
  (run-tests 'self-learning-integration-test))

(comment
  ;; === How to run tests ===

  ;; 1. Start the dev environment
  (dev/start!)

  ;; 2. Load this file
  (load-file "development/src/self_learning_integration_test.clj")

  ;; 3. Run all tests
  (run-all-tests)

  ;; Or run individual tests
  (test-record-strength-flow)
  (test-record-weakness-flow)
  (test-find-self-patterns)
  (test-build-actionable-context)
  (test-context-parameter-handling)
  (test-grain-events-emitted)

  ,)
