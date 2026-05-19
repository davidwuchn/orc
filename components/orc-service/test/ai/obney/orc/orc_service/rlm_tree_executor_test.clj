(ns ai.obney.orc.orc-service.rlm-tree-executor-test
  "Tests for RLM tree executor - Phase 2 execution of generated trees.

   The tree executor takes a canonical ORC DSL tree, spawns a child tick,
   and returns results via promise. This enables two-phase RLM execution
   where Phase 1 generates the tree and Phase 2 executes it.

   NOTE: Tests use dscloj-provider nil to use the mock executor,
   which proves the execution flow without requiring LLM API keys."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [dscloj.core :as dscloj]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.rlm-tree-executor :as tree-executor]
            [ai.obney.orc.orc-service.core.rlm-dsl :as rlm-dsl]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.todo-processors :as tp-core]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]))

;; =============================================================================
;; Test Context
;; =============================================================================

(defn- create-test-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/tree-executor-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        ;; Use dscloj-provider nil to use mock executor (no LLM API required)
        ;; IMPORTANT: Include event-pubsub in base-ctx so processors can publish returned events
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :dscloj-provider nil
                  :event-pubsub ps  ;; Must be in context for processors to publish result events
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv
                    (fn [acc proc-name {:keys [handler-fn topics]}]
                      (assoc acc proc-name
                             (tp/start {:event-pubsub ps
                                        :topics topics
                                        :handler-fn handler-fn
                                        :context base-ctx})))
                    {}
                    @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-test-context [ctx]
  (doseq [[_ processor] (:processors ctx)] (tp/stop processor))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [cache (:cache ctx)] (kv/stop cache))
  (when-let [event-store (:event-store ctx)] (es/stop event-store))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [child (.listFiles f)] (.delete child))
        (.delete f)))))

(defmacro with-test-context [[ctx-sym] & body]
  `(binding [tp-core/*default-dscloj-provider* nil]
     (let [~ctx-sym (create-test-context)]
       (try
         ~@body
         (finally
           (stop-test-context ~ctx-sym))))))

;; =============================================================================
;; Issue 001: Tracer Bullet - Simple tree executes via child tick
;; =============================================================================

(deftest simple-tree-executes-and-returns-outputs
  (testing "Simple final-only tree returns outputs"
    (with-test-context [ctx]
      ;; Create a minimal tree that just returns a final value
      ;; This is the canonical ORC DSL form (what rlm-dsl->orc-dsl produces)
      (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                   [:sequence
                    [:final {:keys [:summary]}]])
            ;; Provide the required output value via sandbox-vars
            sandbox-vars {:summary "Test summary from Phase 1"}
            ;; Execute the tree
            result (tree-executor/execute-tree tree ctx
                     {:sandbox-vars sandbox-vars
                      :timeout-ms 10000})]
        ;; Should return success (proves child tick mechanism works)
        (is (= :success (:status result))
            (str "Expected :success, got: " (:status result) " error: " (:error result)))
        ;; Outputs should contain the expected keys
        ;; NOTE: Mock executor produces mock values; real executor would produce actual values
        (is (contains? (:outputs result) :summary)
            "Should return outputs containing :summary key")))))

(deftest tree-with-blackboard-inputs-executes
  (testing "Tree can access blackboard inputs passed to child tick"
    (with-test-context [ctx]
      (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                   [:sequence
                    [:final {:keys [:result]}]])
            ;; Blackboard inputs for the child tick
            blackboard {:input-data "Hello from blackboard"}
            sandbox-vars {:result "Processed input"}
            result (tree-executor/execute-tree tree ctx
                     {:blackboard blackboard
                      :sandbox-vars sandbox-vars
                      :timeout-ms 10000})]
        ;; Should return success (proves child tick mechanism works with blackboard inputs)
        (is (= :success (:status result))
            (str "Expected :success, got: " (:status result) " error: " (:error result)))
        ;; Outputs should contain both sandbox-vars keys and blackboard keys
        (is (contains? (:outputs result) :result)
            "Should return outputs containing :result key")
        (is (contains? (:outputs result) :input-data)
            "Should return outputs containing blackboard input :input-data key")))))

;; =============================================================================
;; Issue 002: Phase 2 Integration - emit-tree! triggers automatic execution
;; =============================================================================

(deftest emit-tree-triggers-phase2-execution
  (testing "RLM node with emit-tree! returns :success with outputs (not :tree-generated)"
    (with-test-context [ctx]
      ;; Mock dscloj/predict to return code that calls emit-tree!
      (with-redefs [dscloj/predict
                    (fn [provider module inputs opts]
                      {:outputs {:code "(emit-tree!
                                          [:sequence
                                            [:llm {:instruction \"Extract summary\"
                                                   :reads [:document]
                                                   :writes [:summary]}]
                                            [:final {:keys [:summary]}]])"}
                       :usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}})]
        (let [node {:type :repl-researcher
                    :instruction "Analyze the document and extract a summary"
                    :reads [:document]
                    :writes [:summary]
                    :rlm true
                    :max-iterations 5}
              blackboard {:document {:key :document
                                     :schema :string
                                     :value "This is a test document about AI."
                                     :version 1}}
              ;; Execute the RLM node - should trigger Phase 2 automatically
              result (executor/execute-repl-researcher-rlm
                       node blackboard :openrouter ctx)]
          ;; Should return :success, NOT :tree-generated
          (is (= :success (:status result))
              (str "Expected :success, got: " (:status result)
                   " - Phase 2 should execute automatically after emit-tree!"))
          ;; Should have outputs containing the expected key
          (is (contains? (:outputs result) :summary)
              "Should return outputs containing :summary key from Phase 2 execution"))))))

;; =============================================================================
;; Issue 003: Tree Compilation - LLM nodes actually execute
;; =============================================================================

(defn- create-test-context-with-provider
  "Create test context with a non-nil dscloj-provider so LLM nodes actually execute."
  []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/tree-executor-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        ;; Use a provider keyword so the executor actually calls dscloj/predict
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :dscloj-provider :openrouter  ;; Non-nil provider triggers real execution
                  :event-pubsub ps
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv
                    (fn [acc proc-name {:keys [handler-fn topics]}]
                      (assoc acc proc-name
                             (tp/start {:event-pubsub ps
                                        :topics topics
                                        :handler-fn handler-fn
                                        :context base-ctx})))
                    {}
                    @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defmacro with-provider-context [[ctx-sym] & body]
  `(let [~ctx-sym (create-test-context-with-provider)]
     (try
       ~@body
       (finally
         (stop-test-context ~ctx-sym)))))

(deftest tree-with-llm-node-executes-sub-llm-call
  (testing "Generated tree with [:llm ...] node executes the LLM call during Phase 2"
    (with-provider-context [ctx]
      ;; Track sub-LLM calls to verify they actually happen
      (let [sub-llm-calls (atom [])]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        ;; Record the call for verification
                        (swap! sub-llm-calls conj {:provider provider
                                                   :inputs inputs})
                        ;; Return mock summary
                        {:outputs {:summary "AI document analyzed successfully"}
                         :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}})]
          ;; Create a tree that has an :llm node (not just :final)
          ;; This tree should cause a sub-LLM call during Phase 2
          (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                       [:sequence
                        [:llm {:instruction "Summarize the document"
                               :reads [:document]
                               :writes [:summary]}]
                        [:final {:keys [:summary]}]])
                blackboard {:document "This is a test document about artificial intelligence."}
                result (tree-executor/execute-tree tree ctx
                         {:blackboard blackboard
                          :timeout-ms 10000})]
            ;; Phase 2 should succeed
            (is (= :success (:status result))
                (str "Expected :success, got: " (:status result) " error: " (:error result)))
            ;; The :llm node should have triggered a sub-LLM call
            (is (pos? (count @sub-llm-calls))
                "Should have made at least one sub-LLM call during Phase 2")
            ;; The output should contain the LLM-generated summary
            (is (= "AI document analyzed successfully" (:summary (:outputs result)))
                "Should return the summary from the sub-LLM call")))))))

;; =============================================================================
;; Issue 004: Map-Each Decomposition in Generated Trees
;; =============================================================================

(deftest tree-with-map-each-processes-collection
  (testing "Generated tree with map-each iterates over collection and aggregates results"
    (with-provider-context [ctx]
      ;; Track sub-LLM calls
      (let [sub-llm-calls (atom [])]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (let [chunk-text (get-in inputs [:chunk :value])]
                          ;; Record the call
                          (swap! sub-llm-calls conj {:chunk chunk-text})
                          ;; Return a summary for this chunk
                          {:outputs {:chunk-summary (str "Summary of: " chunk-text)}
                           :usage {:prompt_tokens 20 :completion_tokens 10 :total_tokens 30}}))]
          ;; Create a tree that:
          ;; 1. Has chunks in blackboard
          ;; 2. Uses map-each to process each chunk
          ;; 3. Collects results
          (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                       [:sequence
                        [:map-each {:from :chunks :as :chunk :into :summaries}
                         [:llm {:instruction "Summarize the chunk"
                                :reads [:chunk]
                                :writes [:chunk-summary]}]]
                        [:final {:keys [:summaries]}]])
                ;; Provide 3 chunks to process
                blackboard {:chunks ["chunk1 content" "chunk2 content" "chunk3 content"]}
                result (tree-executor/execute-tree tree ctx
                         {:blackboard blackboard
                          :timeout-ms 15000})]
            ;; Phase 2 should succeed
            (is (= :success (:status result))
                (str "Expected :success, got: " (:status result) " error: " (:error result)))
            ;; Should have made 3 sub-LLM calls (one per chunk)
            (is (= 3 (count @sub-llm-calls))
                (str "Expected 3 sub-LLM calls, got: " (count @sub-llm-calls)))
            ;; Results should be aggregated
            (is (= 3 (count (:summaries (:outputs result))))
                "Should have 3 summaries in output")))))))

;; =============================================================================
;; P01: max-concurrency parallel execution in map-each
;; =============================================================================

(deftest map-each-max-concurrency-runs-iterations-in-parallel
  (testing "When :max-concurrency N is set on map-each over M items (M > N),
            multiple child iterations are in flight simultaneously"
    (with-provider-context [ctx]
      ;; Instrument the mock LLM to track in-flight concurrency.
      ;; Each call: increment counter on entry, sleep ~100ms, decrement on exit.
      ;; We track the peak in-flight count (high-water mark).
      (let [in-flight (atom 0)
            peak (atom 0)
            call-count (atom 0)]
        (with-redefs [dscloj/predict
                      (fn [_provider _module inputs _opts]
                        (swap! call-count inc)
                        (let [now-in-flight (swap! in-flight inc)]
                          (swap! peak max now-in-flight))
                        ;; Hold the call open so concurrency can be observed
                        (Thread/sleep 200)
                        (swap! in-flight dec)
                        {:outputs {:chunk-summary
                                   (str "Summary of: " (get-in inputs [:chunk :value]))}
                         :usage {:prompt_tokens 20
                                 :completion_tokens 10
                                 :total_tokens 30}})]
          (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                       [:sequence
                        [:map-each {:from :chunks
                                    :as :chunk
                                    :into :summaries
                                    :max-concurrency 3}
                         [:llm {:instruction "Summarize the chunk"
                                :reads [:chunk]
                                :writes [:chunk-summary]}]]
                        [:final {:keys [:summaries]}]])
                ;; 6 items so we'd expect 2 batches of 3 if parallelism works
                blackboard {:chunks ["c1" "c2" "c3" "c4" "c5" "c6"]}
                result (tree-executor/execute-tree tree ctx
                         {:blackboard blackboard
                          :timeout-ms 30000})]
            (is (= :success (:status result))
                (str "Expected :success, got: " (:status result) " error: " (:error result)))
            (is (= 6 @call-count)
                (str "Should have made 6 sub-LLM calls, got: " @call-count))
            ;; THE behavior we care about: peak in-flight > 1.
            ;; Ideally peak >= 3 (matching max-concurrency).
            ;; If the bug exists, peak will be 1.
            (is (> @peak 1)
                (str "Expected >1 sub-LLM call in flight at peak (max-concurrency=3), "
                     "got peak=" @peak " - map-each is running serially!"))
            (is (>= @peak 3)
                (str "Expected peak in-flight to equal max-concurrency (3), got: " @peak))))))))

;; =============================================================================
;; Issue 005: Code Nodes in Generated Trees
;; =============================================================================

(deftest tree-with-code-node-executes-computation
  (testing "Generated tree with code node executes pure computation"
    (with-provider-context [ctx]
      ;; Create a tree that uses a code node to chunk a document
      ;; This is the :chunk-document pattern from the DSL
      (let [;; The chunk-document DSL generates a code node with an inline function
            tree (rlm-dsl/rlm-dsl->orc-dsl
                   [:sequence
                    [:chunk-document {:from :document :size 10 :into :chunks}]
                    [:final {:keys [:chunks]}]])
            blackboard {:document "Hello world, this is a test document for chunking."}
            result (tree-executor/execute-tree tree ctx
                     {:blackboard blackboard
                      :timeout-ms 10000})]
        ;; Should succeed
        (is (= :success (:status result))
            (str "Expected :success, got: " (:status result) " error: " (:error result)))
        ;; Should have chunks in output
        (is (some? (:chunks (:outputs result)))
            "Should have :chunks in output")
        ;; Chunks should be a vector of strings (partitioned by size 10)
        (let [chunks (:chunks (:outputs result))]
          (is (vector? chunks) "Chunks should be a vector")
          (is (pos? (count chunks)) "Should have at least one chunk"))))))

(deftest tree-with-failing-llm-node-propagates-error
  (testing "When LLM node fails in Phase 2, error propagates back correctly"
    (with-provider-context [ctx]
      (with-redefs [dscloj/predict
                    (fn [provider module inputs opts]
                      ;; Simulate LLM failure
                      (throw (ex-info "Rate limit exceeded" {:status 429})))]
        (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                     [:sequence
                      [:llm {:instruction "Analyze document"
                             :reads [:document]
                             :writes [:analysis]}]
                      [:final {:keys [:analysis]}]])
              blackboard {:document "Test document"}
              result (tree-executor/execute-tree tree ctx
                       {:blackboard blackboard
                        :timeout-ms 10000})]
          ;; Should return failure status
          (is (= :failure (:status result))
              (str "Expected :failure status, got: " (:status result)))
          ;; Should have an error message
          (is (some? (:error result))
              "Should have an error message"))))))

;; =============================================================================
;; Issue 006: Ephemeral Function Cleanup
;; =============================================================================

(deftest ephemeral-functions-cleaned-up-after-execution
  (testing "Ephemeral functions are removed from registry after tree execution"
    (with-provider-context [ctx]
      ;; Get initial registry state (deref the var, then deref the atom)
      (let [initial-count (count @@#'tree-executor/ephemeral-fn-registry)]
        ;; Execute a tree with a code node (chunk-document creates one)
        (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                     [:sequence
                      [:chunk-document {:from :document :size 10 :into :chunks}]
                      [:final {:keys [:chunks]}]])
              blackboard {:document "Hello world, this is a test document."}
              result (tree-executor/execute-tree tree ctx
                       {:blackboard blackboard
                        :timeout-ms 10000})]
          ;; Execution should succeed
          (is (= :success (:status result))
              (str "Expected :success, got: " (:status result) " error: " (:error result)))
          ;; Registry should be back to initial count (functions cleaned up)
          (is (= initial-count (count @@#'tree-executor/ephemeral-fn-registry))
              (str "Expected ephemeral registry to be cleaned up. "
                   "Initial: " initial-count ", Current: "
                   (count @@#'tree-executor/ephemeral-fn-registry))))))))

;; =============================================================================
;; Issue 007: Sub-LLM Usage Tracking
;; =============================================================================

(deftest sub-llm-usage-is-tracked-and-aggregated
  (testing "Usage from sub-LLM calls is included in final result"
    (with-provider-context [ctx]
      ;; Track sub-LLM calls with mock usage
      (with-redefs [dscloj/predict
                    (fn [provider module inputs opts]
                      ;; Return mock output with usage
                      {:outputs {:summary "Test summary"}
                       :usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}})]
        (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                     [:sequence
                      [:llm {:instruction "Summarize"
                             :reads [:document]
                             :writes [:summary]}]
                      [:final {:keys [:summary]}]])
              blackboard {:document "Test document content"}
              result (tree-executor/execute-tree tree ctx
                       {:blackboard blackboard
                        :timeout-ms 10000})]
          ;; Execution should succeed
          (is (= :success (:status result))
              (str "Expected :success, got: " (:status result) " error: " (:error result)))
          ;; Should have usage data in result
          (is (some? (:usage result))
              "Result should include :usage from sub-LLM calls")
          ;; Usage should have expected structure
          (when (:usage result)
            (is (pos? (get-in result [:usage :total-tokens] 0))
                "Usage should have positive total-tokens")))))))

(deftest multiple-sub-llm-calls-aggregate-usage
  (testing "Usage from multiple sub-LLM calls is aggregated"
    (with-provider-context [ctx]
      (let [call-count (atom 0)]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (swap! call-count inc)
                        ;; Each call returns 100 tokens
                        {:outputs {:chunk-summary "Summary of chunk"}
                         :usage {:prompt_tokens 50 :completion_tokens 50 :total_tokens 100}})]
          (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                       [:sequence
                        [:map-each {:from :chunks :as :chunk :into :summaries}
                         [:llm {:instruction "Summarize chunk"
                                :reads [:chunk]
                                :writes [:chunk-summary]}]]
                        [:final {:keys [:summaries]}]])
                ;; 3 chunks = 3 sub-LLM calls = 300 total tokens
                blackboard {:chunks ["chunk1" "chunk2" "chunk3"]}
                result (tree-executor/execute-tree tree ctx
                         {:blackboard blackboard
                          :timeout-ms 15000})]
            ;; Execution should succeed
            (is (= :success (:status result))
                (str "Expected :success, got: " (:status result) " error: " (:error result)))
            ;; Should have made 3 calls
            (is (= 3 @call-count)
                (str "Expected 3 sub-LLM calls, got: " @call-count))
            ;; Should have aggregated usage
            (is (some? (:usage result))
                "Result should include :usage")
            (when (:usage result)
              ;; 3 calls x 100 tokens each = 300 total
              (is (= 300 (get-in result [:usage :total-tokens] 0))
                  (str "Expected 300 total tokens, got: " (get-in result [:usage :total-tokens]))))))))))

(comment
  ;; Run individual test
  (clojure.test/run-tests 'ai.obney.orc.orc-service.rlm-tree-executor-test))
