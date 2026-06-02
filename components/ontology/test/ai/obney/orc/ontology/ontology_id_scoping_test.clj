(ns ai.obney.orc.ontology.ontology-id-scoping-test
  "TDD tests for ontology-id scoping.

   Tests that multiple ontologies can coexist in the same event store
   and be queried independently.

   Uses proper Grain patterns:
   - Commands emit events
   - Events stored via event-store (in-memory, same interface as SQLite)
   - Read models project from events"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.test-helpers :as h]))

;; =============================================================================
;; Test Data
;; =============================================================================

(def ontology-a-id #uuid "aaaa0000-0000-0000-0000-000000000001")
(def ontology-b-id #uuid "bbbb0000-0000-0000-0000-000000000002")
(def ontology-c-id #uuid "cccc0000-0000-0000-0000-000000000003")

;; =============================================================================
;; Test Fixture - Proper Grain Context
;; =============================================================================

(def ^:dynamic *test-ctx* nil)

(defn test-context-fixture [f]
  (binding [*test-ctx* (h/create-test-context)]
    (try
      (f)
      (finally
        (h/stop-context *test-ctx*)))))

(use-fixtures :each test-context-fixture)

;; =============================================================================
;; Helper - Create concept via command
;; =============================================================================

(defn create-concept!
  "Create a concept using the proper Grain command pattern."
  [ctx {:keys [ontology-id uri label scope description]
        :or {scope :custom description ""}}]
  (h/run-and-apply! ctx
    (fn [c]
      (cmd/ontology-create-concept
        (assoc c :command
          {:ontology-id ontology-id
           :uri uri
           :label label
           :scope scope
           :description description})))))

;; =============================================================================
;; Slice 1: Tracer Bullet - Single ontology concept creation and retrieval
;; =============================================================================

(deftest single-ontology-concept-round-trip
  (testing "create concept via command, retrieve via get-concepts"
    ;; Create a concept using command
    (create-concept! *test-ctx*
      {:ontology-id ontology-a-id
       :uri "person:tavidee"
       :label "Tavidee Hoskins"})

    ;; Retrieve all concepts
    (let [all-concepts (rm/get-concepts *test-ctx*)]
      (is (= 1 (count all-concepts))
          "should have 1 concept")
      (is (= "Tavidee Hoskins" (:label (first all-concepts)))
          "should have correct label")
      (is (= ontology-a-id (:ontology-id (first all-concepts)))
          "should have correct ontology-id"))))

;; =============================================================================
;; Slice 2: Two ontologies - filter by single ontology-id
;; =============================================================================

(deftest two-ontologies-filter-by-single-id
  (testing "filter concepts by single ontology-id"
    ;; Create concepts in ontology A
    (create-concept! *test-ctx*
      {:ontology-id ontology-a-id
       :uri "person:tavidee"
       :label "Tavidee Hoskins"})
    (create-concept! *test-ctx*
      {:ontology-id ontology-a-id
       :uri "person:justin"
       :label "Justin"})

    ;; Create concept in ontology B
    (create-concept! *test-ctx*
      {:ontology-id ontology-b-id
       :uri "failure:hallucination"
       :label "Hallucination"
       :scope :failure})

    ;; Query with ontology-id filter
    (let [ontology-a-concepts (rm/get-concepts *test-ctx* {:ontology-id ontology-a-id})
          ontology-b-concepts (rm/get-concepts *test-ctx* {:ontology-id ontology-b-id})
          all-concepts (rm/get-concepts *test-ctx*)]

      (is (= 2 (count ontology-a-concepts))
          "ontology A should have 2 concepts")
      (is (= 1 (count ontology-b-concepts))
          "ontology B should have 1 concept")
      (is (= 3 (count all-concepts))
          "without filter, should return all 3 concepts")

      ;; Verify correct concepts returned
      (is (= #{"Tavidee Hoskins" "Justin"}
             (set (map :label ontology-a-concepts)))
          "ontology A should have Tavidee and Justin")
      (is (= #{"Hallucination"}
             (set (map :label ontology-b-concepts)))
          "ontology B should have Hallucination"))))

;; =============================================================================
;; Slice 3: Filter by multiple ontology-ids
;; =============================================================================

(deftest filter-by-multiple-ontology-ids
  (testing "filter concepts by multiple ontology-ids returns union"
    ;; Create concepts across three ontologies
    (create-concept! *test-ctx*
      {:ontology-id ontology-a-id
       :uri "person:alice"
       :label "Alice"})
    (create-concept! *test-ctx*
      {:ontology-id ontology-b-id
       :uri "org:acme"
       :label "Acme Corp"})
    (create-concept! *test-ctx*
      {:ontology-id ontology-c-id
       :uri "product:widget"
       :label "Widget"})

    ;; Query multiple ontology-ids
    (let [ab-concepts (rm/get-concepts *test-ctx*
                        {:ontology-ids [ontology-a-id ontology-b-id]})]

      (is (= 2 (count ab-concepts))
          "should return concepts from ontology A and B only")
      (is (= #{"Alice" "Acme Corp"}
             (set (map :label ab-concepts)))
          "should have Alice and Acme Corp, not Widget"))))

;; =============================================================================
;; Slice 4: Backward compatibility - no filter returns all
;; =============================================================================

(deftest no-filter-returns-all-backward-compat
  (testing "get-concepts without ontology-id returns all (backward compatible)"
    (create-concept! *test-ctx*
      {:ontology-id ontology-a-id
       :uri "person:alice"
       :label "Alice"})
    (create-concept! *test-ctx*
      {:ontology-id ontology-b-id
       :uri "org:acme"
       :label "Acme Corp"})

    (let [all-concepts (rm/get-concepts *test-ctx*)]
      (is (= 2 (count all-concepts))
          "without filter, should return all concepts"))))

;; =============================================================================
;; Slice 5: Combine ontology-id with scope filter
;; =============================================================================

(deftest ontology-id-combined-with-scope-filter
  (testing "ontology-id filter works with scope filter"
    ;; Create concepts with different scopes in same ontology
    (create-concept! *test-ctx*
      {:ontology-id ontology-a-id
       :uri "person:alice"
       :label "Alice"
       :scope :person})
    (create-concept! *test-ctx*
      {:ontology-id ontology-a-id
       :uri "failure:timeout"
       :label "Timeout"
       :scope :failure})
    (create-concept! *test-ctx*
      {:ontology-id ontology-b-id
       :uri "failure:crash"
       :label "Crash"
       :scope :failure})

    ;; Query ontology A failures only
    (let [a-failures (rm/get-concepts *test-ctx*
                       {:ontology-id ontology-a-id :scope :failure})]

      (is (= 1 (count a-failures))
          "should return only failures from ontology A")
      (is (= "Timeout" (:label (first a-failures)))
          "should be the Timeout failure"))))

;; =============================================================================
;; Slice 6: Concept embeddings preserve ontology-id
;; =============================================================================

(deftest concept-embeddings-preserve-ontology-id
  (testing "concept embeddings read model preserves ontology-id"
    ;; Directly test the embedding projection (embeddings come from different command)
    (let [events [{:event/type :ontology/concept-embedded
                   :ontology-id ontology-a-id
                   :uri "person:alice"
                   :concept-id #uuid "11111111-1111-1111-1111-111111111111"
                   :embedding [0.1 0.2 0.3]
                   :text-embedded "Alice"
                   :field-source :label
                   :model-id "text-embedding-3-small"
                   :embedded-at "2026-06-01T00:00:00Z"}]
          state (rm/concept-embeddings {} events)
          embedding (get state "person:alice")]
      (is (some? embedding))
      (is (= ontology-a-id (:ontology-id embedding))
          "ontology-id should be preserved in embeddings"))))
