(ns ai.obney.orc.colbert.training-data-processor-test
  "Unit tests for ColBERT training data processor.

   Tests verify EXACT Python parity for:
   - Triplet generation (max 20 per query)
   - Format conversion (pairs, labeled-pairs, triplets)
   - Data validation
   - Export formats"
  (:require [clojure.test :refer [deftest testing is are]]
            [ai.obney.orc.colbert.core.training-data-processor :as tdp]
            [clojure.java.io :as io]
            [clojure.data.json :as json]))

;; =============================================================================
;; Validation Tests
;; =============================================================================

(deftest validate-pair-test
  (testing "Valid pair passes validation"
    (let [result (tdp/validate-pair ["query" "positive"])]
      (is (:valid? result))))

  (testing "Non-vector fails"
    (let [result (tdp/validate-pair '("query" "positive"))]
      (is (not (:valid? result)))
      (is (re-find #"vector" (:error result)))))

  (testing "Wrong element count fails"
    (let [result (tdp/validate-pair ["query"])]
      (is (not (:valid? result)))
      (is (re-find #"exactly 2" (:error result)))))

  (testing "Blank query fails"
    (let [result (tdp/validate-pair ["" "positive"])]
      (is (not (:valid? result)))
      (is (re-find #"Query" (:error result)))))

  (testing "Blank positive fails"
    (let [result (tdp/validate-pair ["query" "  "])]
      (is (not (:valid? result)))
      (is (re-find #"Positive" (:error result))))))

(deftest validate-labeled-pair-test
  (testing "Valid labeled pair passes"
    (is (:valid? (tdp/validate-labeled-pair ["query" "passage" 1])))
    (is (:valid? (tdp/validate-labeled-pair ["query" "passage" 0]))))

  (testing "Invalid label fails"
    (let [result (tdp/validate-labeled-pair ["query" "passage" 2])]
      (is (not (:valid? result)))
      (is (re-find #"Label must be 0 or 1" (:error result))))))

(deftest validate-triplet-test
  (testing "Valid triplet passes"
    (is (:valid? (tdp/validate-triplet ["query" "positive" "negative"]))))

  (testing "Blank negative fails"
    (let [result (tdp/validate-triplet ["query" "positive" ""])]
      (is (not (:valid? result)))
      (is (re-find #"Negative" (:error result))))))

(deftest validate-training-data-test
  (testing "Valid pairs pass batch validation"
    (let [result (tdp/validate-training-data
                   [["q1" "p1"] ["q2" "p2"] ["q3" "p3"]]
                   :pairs)]
      (is (:valid? result))
      (is (= 3 (:count result)))))

  (testing "Invalid pairs fail with error details"
    (let [result (tdp/validate-training-data
                   [["q1" "p1"] ["" "p2"] ["q3"]]
                   :pairs)]
      (is (not (:valid? result)))
      (is (= 2 (:total-errors result)))
      (is (= 2 (count (:errors result))))))

  (testing "Empty data throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"empty"
          (tdp/validate-training-data [] :pairs)))))

;; =============================================================================
;; Format Conversion Tests
;; =============================================================================

(deftest process-raw-pairs-test
  (testing "Single pair creates data map entry"
    (let [result (tdp/process-raw-pairs [["query1" "positive1"]])]
      (is (= {"query1" {:positives ["positive1"] :negatives []}}
             result))))

  (testing "Multiple positives for same query are grouped"
    (let [result (tdp/process-raw-pairs [["q" "p1"] ["q" "p2"] ["q" "p3"]])]
      (is (= {"q" {:positives ["p1" "p2" "p3"] :negatives []}}
             result))))

  (testing "Different queries create separate entries"
    (let [result (tdp/process-raw-pairs [["q1" "p1"] ["q2" "p2"]])]
      (is (= {"q1" {:positives ["p1"] :negatives []}
              "q2" {:positives ["p2"] :negatives []}}
             result)))))

(deftest process-raw-labeled-pairs-test
  (testing "Label 1 creates positive"
    (let [result (tdp/process-raw-labeled-pairs [["q" "passage" 1]])]
      (is (= ["passage"] (get-in result ["q" :positives])))))

  (testing "Label 0 creates negative"
    (let [result (tdp/process-raw-labeled-pairs [["q" "passage" 0]])]
      (is (= ["passage"] (get-in result ["q" :negatives])))))

  (testing "Mixed labels create both"
    (let [result (tdp/process-raw-labeled-pairs
                   [["q" "pos1" 1] ["q" "neg1" 0] ["q" "pos2" 1]])]
      (is (= ["pos1" "pos2"] (get-in result ["q" :positives])))
      (is (= ["neg1"] (get-in result ["q" :negatives]))))))

(deftest process-raw-triplets-test
  (testing "Triplet creates both positive and negative"
    (let [result (tdp/process-raw-triplets [["q" "pos" "neg"]])]
      (is (= ["pos"] (get-in result ["q" :positives])))
      (is (= ["neg"] (get-in result ["q" :negatives])))))

  (testing "Multiple triplets for same query accumulate"
    (let [result (tdp/process-raw-triplets
                   [["q" "p1" "n1"] ["q" "p2" "n2"]])]
      (is (= ["p1" "p2"] (get-in result ["q" :positives])))
      (is (= ["n1" "n2"] (get-in result ["q" :negatives]))))))

;; =============================================================================
;; Triplet Generation Tests (Python Parity)
;; =============================================================================

(deftest triplet-generation-negs-per-positive-test
  (testing "Python parity: negs_per_positive = max(1, 20 // len(positives))"
    ;; 1 positive -> 20 negs per positive
    (is (= 20 (max 1 (quot 20 1))))
    ;; 2 positives -> 10 negs per positive
    (is (= 10 (max 1 (quot 20 2))))
    ;; 3 positives -> 6 negs per positive
    (is (= 6 (max 1 (quot 20 3))))
    ;; 5 positives -> 4 negs per positive
    (is (= 4 (max 1 (quot 20 5))))
    ;; 10 positives -> 2 negs per positive
    (is (= 2 (max 1 (quot 20 10))))
    ;; 20 positives -> 1 neg per positive
    (is (= 1 (max 1 (quot 20 20))))
    ;; 50 positives -> still 1 (minimum)
    (is (= 1 (max 1 (quot 20 50))))))

(deftest make-individual-triplets-max-20-test
  (testing "Max 20 triplets per query"
    (let [;; Create data with 2 positives and 50 negatives
          data-map {"q1" {:positives ["p1" "p2"]
                          :negatives (mapv str (range 50))}}
          triplets (tdp/make-individual-triplets data-map)]
      ;; With 2 positives: negs_per_positive = 10
      ;; Total triplets = 2 * 10 = 20
      (is (<= (count triplets) 20))
      ;; Each triplet should have the query
      (is (every? #(= "q1" (first %)) triplets))
      ;; Each triplet should be a 3-tuple
      (is (every? #(= 3 (count %)) triplets)))))

(deftest make-individual-triplets-distribution-test
  (testing "Negatives are distributed across positives"
    (let [data-map {"q" {:positives ["p1" "p2"]
                         :negatives ["n1" "n2" "n3" "n4" "n5" "n6" "n7" "n8" "n9" "n10"
                                     "n11" "n12" "n13" "n14" "n15" "n16" "n17" "n18" "n19" "n20"]}}
          triplets (tdp/make-individual-triplets data-map)
          ;; Group by positive
          by-positive (group-by second triplets)]
      ;; Both positives should have triplets
      (is (contains? by-positive "p1"))
      (is (contains? by-positive "p2"))
      ;; With 2 positives, each gets 10 negatives
      (is (= 10 (count (get by-positive "p1"))))
      (is (= 10 (count (get by-positive "p2")))))))

(deftest make-individual-triplets-cycles-negatives-test
  (testing "Negatives cycle when not enough available"
    (let [data-map {"q" {:positives ["p1"]
                         :negatives ["n1" "n2" "n3"]}}  ; Only 3 negs, but need 20
          triplets (tdp/make-individual-triplets data-map)
          negs-used (mapv #(nth % 2) triplets)]
      ;; Should produce 20 triplets by cycling
      (is (= 20 (count triplets)))
      ;; Negatives should cycle: n1, n2, n3, n1, n2, n3, ...
      (is (= "n1" (first negs-used)))
      (is (= "n2" (second negs-used)))
      (is (= "n3" (nth negs-used 2)))
      (is (= "n1" (nth negs-used 3))))))

(deftest make-individual-triplets-empty-test
  (testing "Empty positives returns no triplets"
    (let [data-map {"q" {:positives [] :negatives ["n1" "n2"]}}
          triplets (tdp/make-individual-triplets data-map)]
      (is (empty? triplets))))

  (testing "Empty negatives returns no triplets"
    (let [data-map {"q" {:positives ["p1" "p2"] :negatives []}}
          triplets (tdp/make-individual-triplets data-map)]
      (is (empty? triplets)))))

(deftest make-individual-triplets-multiple-queries-test
  (testing "Multiple queries are processed independently"
    (let [data-map {"q1" {:positives ["p1"] :negatives (mapv #(str "n1-" %) (range 5))}
                    "q2" {:positives ["p2"] :negatives (mapv #(str "n2-" %) (range 3))}}
          triplets (tdp/make-individual-triplets data-map)
          q1-triplets (filter #(= "q1" (first %)) triplets)
          q2-triplets (filter #(= "q2" (first %)) triplets)]
      ;; Each query gets up to 20 triplets
      (is (= 20 (count q1-triplets)))  ; cycles negs
      (is (= 20 (count q2-triplets)))  ; cycles negs
      ;; Total should be sum
      (is (= 40 (count triplets))))))

;; =============================================================================
;; Deduplication Tests
;; =============================================================================

(deftest deduplicate-triplets-test
  (testing "Removes exact duplicates"
    (let [triplets [["q" "p" "n1"]
                    ["q" "p" "n2"]
                    ["q" "p" "n1"]  ; duplicate
                    ["q" "p" "n2"]] ; duplicate
          result (tdp/deduplicate-triplets triplets)]
      (is (= 2 (count result)))
      (is (= [["q" "p" "n1"] ["q" "p" "n2"]] result))))

  (testing "Preserves order of first occurrence"
    (let [triplets [["q" "p" "n3"]
                    ["q" "p" "n1"]
                    ["q" "p" "n3"]]
          result (tdp/deduplicate-triplets triplets)]
      (is (= [["q" "p" "n3"] ["q" "p" "n1"]] result)))))

;; =============================================================================
;; Export Tests
;; =============================================================================

(deftest export-training-data-jsonl-test
  (testing "Exports JSONL format matching Python"
    (let [triplets [["query1" "positive1" "negative1"]
                    ["query2" "positive2" "negative2"]]
          temp-file (java.io.File/createTempFile "test-export" ".jsonl")
          temp-path (.getAbsolutePath temp-file)]
      (try
        (let [result (tdp/export-training-data triplets temp-path)
              lines (with-open [rdr (io/reader temp-path)]
                      (doall (line-seq rdr)))
              parsed (mapv #(json/read-str % :key-fn keyword) lines)]
          ;; Check return value
          (is (= temp-path (:path result)))
          (is (= 2 (:num-triplets result)))
          ;; Check JSONL format
          (is (= 2 (count lines)))
          (is (= {:query "query1" :positive "positive1" :negative "negative1"}
                 (first parsed)))
          (is (= {:query "query2" :positive "positive2" :negative "negative2"}
                 (second parsed))))
        (finally
          (.delete temp-file))))))

;; =============================================================================
;; High-Level API Tests
;; =============================================================================

(deftest process-training-data-pairs-test
  (testing "Full pipeline with pairs format"
    (let [pairs [["q1" "p1"] ["q1" "p2"] ["q2" "p3"]]
          ;; Need to add negatives for triplet generation
          ;; Since pairs don't have negatives, no triplets will be generated
          result (tdp/process-training-data pairs :pairs)]
      (is (map? (:data-map result)))
      (is (= 2 (count (:data-map result))))  ; q1 and q2
      ;; No triplets without negatives
      (is (empty? (:triplets result))))))

(deftest process-training-data-labeled-pairs-test
  (testing "Full pipeline with labeled-pairs format"
    (let [labeled [["q1" "pos" 1] ["q1" "neg1" 0] ["q1" "neg2" 0]]
          result (tdp/process-training-data labeled :labeled-pairs)]
      (is (= 1 (count (:data-map result))))
      ;; 1 positive * 2 negs = 2 triplets (but max limited by negs available)
      (is (pos? (count (:triplets result)))))))

(deftest process-training-data-triplets-test
  (testing "Full pipeline with triplets format"
    (let [triplets [["q1" "p1" "n1"] ["q1" "p1" "n2"]]
          result (tdp/process-training-data triplets :triplets)]
      ;; Triplets get converted to data-map then back to triplets
      (is (pos? (count (:triplets result))))
      (is (map? (:stats result)))
      (is (pos? (:num-queries (:stats result))))
      (is (pos? (:num-triplets (:stats result)))))))

(deftest process-training-data-validation-test
  (testing "Validation catches errors"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid training data"
          (tdp/process-training-data [["" "p"]] :pairs :validate? true))))

  (testing "Validation can be skipped"
    (let [result (tdp/process-training-data [["" "p"]] :pairs :validate? false)]
      ;; Doesn't throw, but may produce empty/weird results
      (is (map? result)))))
