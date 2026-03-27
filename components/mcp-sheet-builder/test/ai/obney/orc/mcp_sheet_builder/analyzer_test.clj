(ns ai.obney.orc.mcp-sheet-builder.analyzer-test
  "Tests for MCP tool analysis."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.mcp-sheet-builder.core.analyzer :as analyzer]))

;; ============================================================================
;; Sample Test Data
;; ============================================================================

(def sample-search-tool
  {:name "search"
   :description "Search the web for information"
   :inputSchema {"type" "object"
                 "properties" {"query" {"type" "string"}}}})

(def sample-extract-tool
  {:name "extract"
   :description "Extract content from a URL"
   :inputSchema {"type" "object"
                 "properties" {"url" {"type" "string"}}}})

(def sample-create-tool
  {:name "createDocument"
   :description "Create a new document"
   :inputSchema {"type" "object"
                 "properties" {"title" {"type" "string"}
                               "content" {"type" "string"}}}})

(def sample-delete-tool
  {:name "deleteFile"
   :description "Delete a file from storage"
   :inputSchema {"type" "object"
                 "properties" {"path" {"type" "string"}}}})

(def sample-transform-tool
  {:name "convert"
   :description "Transform data from one format to another"
   :inputSchema {"type" "object"
                 "properties" {"input" {"type" "string"}
                               "format" {"type" "string"}}}})

;; ============================================================================
;; Capability Detection Tests
;; ============================================================================

(deftest analyze-tools-returns-vector
  (testing "returns vector of analyzed tools"
    (let [analyzed (analyzer/analyze-tools [sample-search-tool] {})]
      (is (vector? analyzed))
      (is (= 1 (count analyzed))))))

(deftest analyze-tools-tags-capabilities
  (testing "tags tools with capability keywords"
    (let [analyzed (analyzer/analyze-tools [sample-search-tool sample-extract-tool] {})]
      (is (seq analyzed))
      (is (every? :capabilities analyzed)))))

(deftest analyze-tools-detects-search-capability
  (testing "detects search capability from name/description"
    (let [analyzed (analyzer/analyze-tools [sample-search-tool] {})
          search-tool (first analyzed)]
      (is (contains? (:capabilities search-tool) :search)))))

(deftest analyze-tools-detects-create-capability
  (testing "detects create capability"
    (let [analyzed (analyzer/analyze-tools [sample-create-tool] {})
          create-tool (first analyzed)]
      (is (contains? (:capabilities create-tool) :create)))))

(deftest analyze-tools-detects-delete-capability
  (testing "detects delete capability"
    (let [analyzed (analyzer/analyze-tools [sample-delete-tool] {})
          delete-tool (first analyzed)]
      (is (contains? (:capabilities delete-tool) :delete)))))

(deftest analyze-tools-detects-transform-capability
  (testing "detects transform capability"
    (let [analyzed (analyzer/analyze-tools [sample-transform-tool] {})
          transform-tool (first analyzed)]
      (is (contains? (:capabilities transform-tool) :transform)))))

;; ============================================================================
;; Category Classification Tests
;; ============================================================================

(deftest analyze-tool-classifies-category
  (testing "classifies tools into categories"
    (let [analyzed (analyzer/analyze-tools [sample-search-tool] {})
          tool (first analyzed)]
      (is (contains? tool :category))
      (is (#{:data-access :data-mutation :transformation :side-effect} (:category tool))))))

(deftest analyze-tool-search-is-data-access
  (testing "search tools are data-access"
    (let [analyzed (analyzer/analyze-tools [sample-search-tool] {})
          tool (first analyzed)]
      (is (= :data-access (:category tool))))))

(deftest analyze-tool-create-is-mutation
  (testing "create tools are data-mutation"
    (let [analyzed (analyzer/analyze-tools [sample-create-tool] {})
          tool (first analyzed)]
      (is (= :data-mutation (:category tool))))))

;; ============================================================================
;; Idempotency Tests
;; ============================================================================

(deftest analyze-tool-idempotency
  (testing "marks idempotent tools correctly"
    (let [analyzed (analyzer/analyze-tools [sample-search-tool] {})
          tool (first analyzed)]
      (is (contains? tool :idempotent?))
      (is (true? (:idempotent? tool))))))

(deftest analyze-tool-create-not-idempotent
  (testing "create tools are not idempotent"
    (let [analyzed (analyzer/analyze-tools [sample-create-tool] {})
          tool (first analyzed)]
      (is (false? (:idempotent? tool))))))

(deftest analyze-tool-delete-not-idempotent
  (testing "delete tools are not idempotent"
    (let [analyzed (analyzer/analyze-tools [sample-delete-tool] {})
          tool (first analyzed)]
      (is (false? (:idempotent? tool))))))

;; ============================================================================
;; Schema Conversion Tests
;; ============================================================================

(deftest analyze-tool-converts-input-schema
  (testing "converts input schema to Malli"
    (let [analyzed (analyzer/analyze-tools [sample-search-tool] {})
          tool (first analyzed)]
      (is (contains? tool :malli-input))
      (is (vector? (:malli-input tool)))
      (is (= :map (first (:malli-input tool)))))))

;; ============================================================================
;; Exploration Budget Tests
;; ============================================================================

(deftest exploration-budget-small-set
  (testing "returns appropriate budget for small tool sets"
    (let [budget (analyzer/exploration-budget 2)]
      (is (map? budget))
      (is (= 3 (:patterns budget))))))

(deftest exploration-budget-medium-set
  (testing "returns appropriate budget for medium tool sets"
    (let [budget (analyzer/exploration-budget 5)]
      (is (= 6 (:patterns budget))))))

(deftest exploration-budget-large-set
  (testing "returns appropriate budget for large tool sets"
    (let [budget (analyzer/exploration-budget 15)]
      (is (= 10 (:patterns budget))))))
