(ns ai.obney.workshop.sheet-service.gepa-primitives-test
  "Tests for GEPA (Goal-directed, Evaluative, Planning Agent) foundational primitives.

   Tests cover:
   - Execution trace storage with path tracking
   - Trace querying (get-trace, get-traces)
   - Version-targeted execution (execute-version)
   - Batch execution (batch-execute)
   - Structural diff (diff-versions)
   - Node statistics aggregation (node-stats)"
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.workshop.sheet-service.test-helpers :as h]
            [ai.obney.workshop.sheet-service.interface :as sheet]))

;; =============================================================================
;; Helper: Create a simple test workflow
;; =============================================================================

(defn- create-simple-workflow!
  "Create a simple workflow with sequence -> condition -> fallback structure.
   Returns {:sheet-id ... :node-ids {:root ... :cond1 ... :fallback ... :cond2 ...}}"
  [ctx]
  (let [;; Create sheet
        sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "GEPA Test"))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)

        ;; Create root sequence
        seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
        seq-id (-> seq-result :command-result/events first :node-id)
        _ (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id seq-id "Root"))

        ;; Declare blackboard keys
        _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id "test-key" :string))
        _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id "other-key" :string))

        ;; Create first condition (child of sequence)
        cond1-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :condition :parent-id seq-id))
        cond1-id (-> cond1-result :command-result/events first :node-id)
        _ (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id cond1-id "check-exists"))
        _ (h/run-and-apply! ctx (h/make-set-node-check-command sheet-id cond1-id {:key "test-key" :op :exists}))

        ;; Create fallback (child of sequence)
        fb-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :fallback :parent-id seq-id))
        fb-id (-> fb-result :command-result/events first :node-id)
        _ (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id fb-id "TryOptions"))

        ;; Create second condition (child of fallback - will fail)
        cond2-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :condition :parent-id fb-id))
        cond2-id (-> cond2-result :command-result/events first :node-id)
        _ (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id cond2-id "check-nonexistent"))
        _ (h/run-and-apply! ctx (h/make-set-node-check-command sheet-id cond2-id {:key "nonexistent" :op :exists}))

        ;; Create third condition (child of fallback - will succeed)
        cond3-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :condition :parent-id fb-id))
        cond3-id (-> cond3-result :command-result/events first :node-id)
        _ (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id cond3-id "check-other"))
        _ (h/run-and-apply! ctx (h/make-set-node-check-command sheet-id cond3-id {:key "other-key" :op :exists}))]

    {:sheet-id sheet-id
     :node-ids {:root seq-id
                :cond1 cond1-id
                :fallback fb-id
                :cond2 cond2-id
                :cond3 cond3-id}}))

;; =============================================================================
;; Phase 0: Node ID Preservation in Snapshots
;; =============================================================================

(deftest snapshot-preserves-node-ids-test
  (testing "published version snapshots preserve original node IDs"
    (h/with-test-context [ctx]
      (let [{:keys [sheet-id node-ids]} (create-simple-workflow! ctx)

            ;; Publish version
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

            ;; Get the version
            version-result (h/run-query ctx (h/make-get-version-query sheet-id 1))
            snapshot (get-in version-result [:query/result :version :snapshot])
            snapshot-root (:nodes snapshot)]

        ;; Verify root ID is preserved
        (is (uuid? (:id snapshot-root)))
        (is (= (:root node-ids) (:id snapshot-root)))

        ;; Verify children IDs are preserved
        (let [children (:children snapshot-root)]
          (is (= 2 (count children)))
          (is (some #(= (:cond1 node-ids) (:id %)) children))
          (is (some #(= (:fallback node-ids) (:id %)) children)))))))

;; =============================================================================
;; Phase 1: Trace Storage with Path Tracking
;; =============================================================================

(deftest execution-stores-trace-test
  (testing "execution always stores a trace with trace-id"
    (h/with-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)

            ;; Execute
            result (sheet/execute ctx sheet-id {"test-key" "hello" "other-key" "world"})]

        ;; Verify trace-id is returned
        (is (uuid? (:trace-id result)))
        (is (= :success (:status result)))))))

(deftest trace-includes-path-tracking-test
  (testing "node traces include path and child-index"
    (h/with-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)

            ;; Execute
            result (sheet/execute ctx sheet-id {"test-key" "hello" "other-key" "world"})
            trace-id (:trace-id result)

            ;; Get trace
            trace-result (h/run-query ctx (h/make-get-trace-query trace-id))
            trace (:query/result trace-result)
            node-traces (:node-traces trace)]

        ;; Verify we have node traces
        (is (pos? (count node-traces)))

        ;; Verify each node trace has required path fields
        (doseq [nt node-traces]
          (is (uuid? (:node-id nt)))
          (is (string? (:node-name nt)))
          (is (keyword? (:node-type nt)))
          (is (vector? (:path nt))))

        ;; Verify root node has empty path
        (let [root-trace (first (filter #(= "Root" (:node-name %)) node-traces))]
          (is (some? root-trace))
          (is (= [] (:path root-trace)))
          (is (nil? (:child-index root-trace))))

        ;; Verify child nodes have proper paths
        (let [cond1-trace (first (filter #(= "check-exists" (:node-name %)) node-traces))]
          (is (some? cond1-trace))
          (is (= ["Root"] (:path cond1-trace)))
          (is (integer? (:child-index cond1-trace)))
          (is (>= (:child-index cond1-trace) 0)))))))

;; =============================================================================
;; Phase 2: Trace Querying
;; =============================================================================

(deftest get-trace-query-test
  (testing "get-trace returns full trace by ID"
    (h/with-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)
            result (sheet/execute ctx sheet-id {"test-key" "a" "other-key" "b"})
            trace-id (:trace-id result)

            trace-result (h/run-query ctx (h/make-get-trace-query trace-id))
            trace (:query/result trace-result)]

        (is (= trace-id (:trace-id trace)))
        (is (= sheet-id (:sheet-id trace)))
        (is (= :success (:status trace)))
        (is (map? (:input-snapshot trace)))
        (is (map? (:output-snapshot trace)))
        (is (vector? (:node-traces trace)))))))

(deftest get-trace-not-found-test
  (testing "get-trace returns not-found for invalid ID"
    (h/with-test-context [ctx]
      (let [result (h/run-query ctx (h/make-get-trace-query (random-uuid)))]
        (is (h/is-anomaly? result))
        (is (= :cognitect.anomalies/not-found (h/anomaly-category result)))))))

(deftest get-traces-query-test
  (testing "get-traces returns traces for sheet"
    (h/with-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)

            ;; Execute multiple times
            _ (sheet/execute ctx sheet-id {"test-key" "a" "other-key" "a"})
            _ (sheet/execute ctx sheet-id {"test-key" "b" "other-key" "b"})
            _ (sheet/execute ctx sheet-id {"test-key" "c" "other-key" "c"})

            result (h/run-query ctx (h/make-get-traces-query sheet-id))
            data (:query/result result)]

        (is (= 3 (:total data)))
        (is (= 3 (count (:traces data))))))))

(deftest get-traces-filters-by-status-test
  (testing "get-traces filters by status"
    (h/with-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)

            ;; Execute with inputs that will succeed
            _ (sheet/execute ctx sheet-id {"test-key" "a" "other-key" "a"})
            ;; Execute without required key - will fail
            _ (sheet/execute ctx sheet-id {})

            success-result (h/run-query ctx (h/make-get-traces-query sheet-id :status :success))
            failure-result (h/run-query ctx (h/make-get-traces-query sheet-id :status :failure))]

        (is (= 1 (get-in success-result [:query/result :total])))
        (is (= 1 (get-in failure-result [:query/result :total])))))))

;; =============================================================================
;; Phase 3: Version-Targeted Execution
;; =============================================================================

(deftest execute-version-test
  (testing "execute-version runs specific published version"
    (h/with-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)

            ;; Publish version 1
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

            ;; Execute version 1
            result (h/run-command ctx (h/make-execute-version-command sheet-id 1
                                        :inputs {"test-key" "v1" "other-key" "v1"}))
            data (:command-result/data result)]

        (is (= :success (:status data)))
        (is (uuid? (:trace-id data)))
        (is (= 1 (:executed-version data)))))))

(deftest execute-version-not-found-test
  (testing "execute-version returns not-found for invalid version"
    (h/with-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)
            result (h/run-command ctx (h/make-execute-version-command sheet-id 99))]

        (is (h/is-anomaly? result))
        (is (= :cognitect.anomalies/not-found (h/anomaly-category result)))))))

;; =============================================================================
;; Phase 4: Batch Execution
;; =============================================================================

(deftest batch-execute-test
  (testing "batch-execute runs multiple inputs in parallel"
    (h/with-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)

            ;; Publish version
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

            ;; Batch execute
            result (h/run-command ctx (h/make-batch-execute-command sheet-id
                                        [{"test-key" "a" "other-key" "a"}
                                         {"test-key" "b" "other-key" "b"}
                                         {"test-key" "c" "other-key" "c"}]
                                        :version-number 1))
            data (:command-result/data result)]

        (is (= 3 (:total-executions data)))
        (is (= 3 (:successful-count data)))
        (is (= 0 (:failed-count data)))
        (is (>= (:duration-ms data) 0))

        ;; Each result should have a trace-id
        (is (every? :trace-id (:results data)))))))

(deftest batch-execute-empty-inputs-fails-test
  (testing "batch-execute fails with empty inputs list"
    (h/with-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)
            result (h/run-command ctx (h/make-batch-execute-command sheet-id []))]

        (is (h/is-anomaly? result))
        (is (= :cognitect.anomalies/incorrect (h/anomaly-category result)))))))

;; =============================================================================
;; Phase 5: Structural Diff
;; =============================================================================

(deftest diff-versions-no-changes-test
  (testing "diff-versions shows no changes for identical versions"
    (h/with-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)

            ;; Publish v1 and v2 without changes
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

            result (h/run-query ctx (h/make-diff-versions-query sheet-id 1 2))
            data (:query/result result)
            node-diff (:node-diff data)]

        (is (= 1 (:from-version data)))
        (is (= 2 (:to-version data)))
        (is (empty? (:added-nodes node-diff)))
        (is (empty? (:removed-nodes node-diff)))
        (is (empty? (:modified-nodes node-diff)))))))

(deftest diff-versions-detects-modifications-test
  (testing "diff-versions detects node modifications"
    (h/with-test-context [ctx]
      (let [{:keys [sheet-id node-ids]} (create-simple-workflow! ctx)

            ;; Publish v1
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

            ;; Modify a node
            _ (h/run-and-apply! ctx (h/make-set-node-check-command sheet-id (:cond1 node-ids)
                                      {:key "test-key" :op :truthy}))

            ;; Publish v2
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

            result (h/run-query ctx (h/make-diff-versions-query sheet-id 1 2))
            node-diff (get-in result [:query/result :node-diff])]

        (is (empty? (:added-nodes node-diff)))
        (is (empty? (:removed-nodes node-diff)))
        (is (= 1 (count (:modified-nodes node-diff))))

        (let [modified (first (:modified-nodes node-diff))]
          (is (= (:cond1 node-ids) (:id modified)))
          (is (= "check-exists" (:name modified)))
          (is (some #(= :check (:field %)) (:changes modified))))))))

(deftest diff-versions-not-found-test
  (testing "diff-versions returns not-found for invalid version"
    (h/with-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

            result (h/run-query ctx (h/make-diff-versions-query sheet-id 1 99))]

        (is (h/is-anomaly? result))
        (is (= :cognitect.anomalies/not-found (h/anomaly-category result)))))))

;; =============================================================================
;; Phase 6: Node Statistics
;; =============================================================================

(deftest node-stats-test
  (testing "node-stats aggregates execution statistics"
    (h/with-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)

            ;; Execute multiple times
            _ (sheet/execute ctx sheet-id {"test-key" "a" "other-key" "a"})
            _ (sheet/execute ctx sheet-id {"test-key" "b" "other-key" "b"})
            _ (sheet/execute ctx sheet-id {"test-key" "c" "other-key" "c"})

            result (h/run-query ctx (h/make-node-stats-query sheet-id))
            data (:query/result result)]

        (is (= 3 (:trace-count data)))
        (is (pos? (count (:stats data))))

        ;; Verify stats structure
        (doseq [stat (:stats data)]
          (is (uuid? (:node-id stat)))
          (is (string? (:node-name stat)))
          (is (keyword? (:node-type stat)))
          (is (number? (:execution-count stat)))
          (is (number? (:success-count stat)))
          (is (number? (:failure-count stat)))
          (is (number? (:success-rate stat)))
          (is (vector? (:common-errors stat))))))))

(deftest node-stats-calculates-success-rate-test
  (testing "node-stats calculates correct success rates"
    (h/with-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)

            ;; Execute with success
            _ (sheet/execute ctx sheet-id {"test-key" "a" "other-key" "a"})
            _ (sheet/execute ctx sheet-id {"test-key" "b" "other-key" "b"})
            ;; Execute with failure (missing keys)
            _ (sheet/execute ctx sheet-id {})

            result (h/run-query ctx (h/make-node-stats-query sheet-id))
            stats (:stats (:query/result result))

            ;; Find the root node stats
            root-stats (first (filter #(= "Root" (:node-name %)) stats))]

        ;; Root should have 2 successes, 1 failure = 66.67% success rate
        (is (some? root-stats))
        (is (= 3 (:execution-count root-stats)))
        (is (= 2 (:success-count root-stats)))
        (is (= 1 (:failure-count root-stats)))
        (is (< 0.6 (:success-rate root-stats) 0.7))))))

(deftest node-stats-tracks-common-errors-test
  (testing "node-stats tracks common errors structure"
    (h/with-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)

            ;; Execute multiple failures
            _ (sheet/execute ctx sheet-id {})
            _ (sheet/execute ctx sheet-id {})
            _ (sheet/execute ctx sheet-id {})

            result (h/run-query ctx (h/make-node-stats-query sheet-id))
            stats (:stats (:query/result result))

            ;; Find a failing node
            failing-stats (first (filter #(pos? (:failure-count %)) stats))]

        (is (some? failing-stats))
        ;; common-errors should be a vector (may be empty if no error messages)
        (is (vector? (:common-errors failing-stats)))))))
