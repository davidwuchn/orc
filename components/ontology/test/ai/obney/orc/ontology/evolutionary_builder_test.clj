(ns ai.obney.orc.ontology.evolutionary-builder-test
  "Tests for evolutionary builder Grain schema compliance.

   Issue 001: Verify ontology-id normalization produces valid UUID tags."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.evolutionary-builder :as builder]))

;; =============================================================================
;; Issue 001: UUID Normalization Tests
;; =============================================================================

;; Access the private normalize-ontology-id function for unit testing
(def normalize-ontology-id #'builder/normalize-ontology-id)

(deftest normalize-ontology-id-converts-strings-to-uuids
  (testing "string ontology-id is converted to UUID"
    (let [result (normalize-ontology-id "my-test-ontology")]
      (is (uuid? result)
          "String should be converted to UUID")))

  (testing "conversion is deterministic - same string produces same UUID"
    (let [result1 (normalize-ontology-id "transcript-corrections")
          result2 (normalize-ontology-id "transcript-corrections")]
      (is (= result1 result2)
          "Same string should always produce same UUID")))

  (testing "different strings produce different UUIDs"
    (let [result1 (normalize-ontology-id "ontology-a")
          result2 (normalize-ontology-id "ontology-b")]
      (is (not= result1 result2)
          "Different strings should produce different UUIDs"))))

(deftest normalize-ontology-id-passes-through-uuids
  (testing "UUID ontology-id passes through unchanged"
    (let [original-uuid (random-uuid)
          result (normalize-ontology-id original-uuid)]
      (is (= original-uuid result)
          "UUID should pass through unchanged"))))
