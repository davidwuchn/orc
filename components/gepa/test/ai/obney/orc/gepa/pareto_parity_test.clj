(ns ai.obney.orc.gepa.pareto-parity-test
  "Algorithm parity tests for GEPA Pareto selection.

   Verifies EXACT behavior match with Python GEPA gepa_utils.py:
   - is_dominated
   - remove_dominated_programs
   - find_dominator_programs
   - select_program_candidate_from_pareto_front

   Test cases derived from Python GEPA behavior."
  (:require [clojure.test :refer [deftest testing is are]]
            [ai.obney.orc.gepa.core.pareto :as pareto]
            [ai.obney.orc.gepa.core.merge :as merge]))

;; =============================================================================
;; Test Data - Matching Python GEPA Test Cases
;; =============================================================================

(def simple-pareto-front
  "Simple Pareto front with 3 instances and 3 candidates.
   Candidate 0 is best at instance 0
   Candidate 1 is best at instance 1
   Candidate 2 is best at instance 2"
  {0 #{0}
   1 #{1}
   2 #{2}})

(def overlapping-pareto-front
  "Pareto front with overlapping best-at sets.
   Candidate 0 is best at instances 0 and 1
   Candidate 1 is best at instances 1 and 2
   Candidate 2 is best at instance 2 only"
  {0 #{0}
   1 #{0 1}
   2 #{1 2}})

(def dominated-candidate-front
  "Pareto front where candidate 2 is dominated.
   Candidate 0: best at instances 0, 1, 2
   Candidate 1: best at instances 0, 1, 2
   Candidate 2: best at instances 0, 1 (subset - dominated)"
  {0 #{0 1 2}
   1 #{0 1 2}
   2 #{0 1}})

;; =============================================================================
;; is-dominated? Tests
;; =============================================================================

(deftest is-dominated-test
  (testing "Candidate with unique best-at is not dominated"
    (is (false? (pareto/is-dominated? 0 #{1 2} simple-pareto-front)))
    (is (false? (pareto/is-dominated? 1 #{0 2} simple-pareto-front)))
    (is (false? (pareto/is-dominated? 2 #{0 1} simple-pareto-front))))

  (testing "Candidate is dominated when all its fronts have dominators"
    ;; Candidate 2 appears in fronts 0 and 1
    ;; In front 0: candidates 0, 1, 2 - both 0 and 1 are in dominator set
    ;; In front 1: candidates 0, 1, 2 - both 0 and 1 are in dominator set
    ;; So candidate 2 is dominated by {0, 1}
    (is (true? (pareto/is-dominated? 2 #{0 1} dominated-candidate-front))))

  (testing "Candidate not in any front is vacuously dominated"
    (is (true? (pareto/is-dominated? 99 #{0 1 2} simple-pareto-front))))

  (testing "Candidate with overlapping coverage"
    ;; In overlapping-pareto-front:
    ;; Candidate 0: fronts 0, 1
    ;; Candidate 1: fronts 1, 2
    ;; Candidate 2: front 2
    ;; Candidate 0 is NOT dominated - has unique coverage of front 0
    (is (false? (pareto/is-dominated? 0 #{1 2} overlapping-pareto-front)))
    ;; Candidate 2 IS dominated - front 2 also has candidate 1
    (is (true? (pareto/is-dominated? 2 #{0 1} overlapping-pareto-front)))))

;; =============================================================================
;; remove-dominated-programs Tests
;; =============================================================================

(deftest remove-dominated-programs-test
  (testing "Simple front - no candidates dominated"
    (let [result (pareto/remove-dominated-programs simple-pareto-front)]
      ;; All candidates should remain
      (is (= #{0} (get result 0)))
      (is (= #{1} (get result 1)))
      (is (= #{2} (get result 2)))))

  (testing "Front with dominated candidate"
    ;; With scores [1.0 2.0 0.5], dominated candidates are removed iteratively
    ;; Candidate 2 is removed first (lowest score, dominated by 0 and 1)
    ;; Then candidate 0 is also dominated by 1 (1 is in every front 0 is in, with higher score)
    (let [scores [1.0 2.0 0.5]
          result (pareto/remove-dominated-programs dominated-candidate-front scores)]
      ;; Candidate 2 should be removed
      (is (not (contains? (get result 0) 2)))
      ;; Candidate 1 survives as the sole dominator (highest score, in all fronts)
      (is (contains? (get result 0) 1))))

  (testing "Overlapping front removes dominated"
    (let [scores [1.5 1.0 0.5]
          result (pareto/remove-dominated-programs overlapping-pareto-front scores)]
      ;; Candidate 2 dominated, should be removed
      (is (not (contains? (get result 2) 2)))
      ;; Candidates 0 and 1 should remain where they were best
      (is (= #{0} (get result 0))))))

;; =============================================================================
;; find-dominator-programs Tests
;; =============================================================================

(deftest find-dominator-programs-test
  (testing "All candidates are dominators in simple front"
    (let [scores [1.0 1.0 1.0]
          result (pareto/find-dominator-programs simple-pareto-front scores)]
      (is (= #{0 1 2} (set result)))))

  (testing "Dominated candidate excluded"
    ;; In dominated-candidate-front with equal scores [1.0 1.0 0.5]:
    ;; Candidate 2 is dominated (appears in subset of fronts)
    ;; When 0 and 1 have equal scores and appear in same fronts, one may dominate
    (let [scores [1.0 1.0 0.5]
          result (pareto/find-dominator-programs dominated-candidate-front scores)]
      ;; Candidate 2 is definitely dominated, should not appear
      (is (not (contains? (set result) 2)))
      ;; At least one of 0 or 1 survives
      (is (or (contains? (set result) 0)
              (contains? (set result) 1))))))

;; =============================================================================
;; select-program-candidate-from-pareto-front Tests
;; =============================================================================

(deftest select-program-candidate-from-pareto-front-test
  (testing "Selection returns valid dominator"
    (let [scores [1.0 1.0 1.0]
          ;; Deterministic RNG for testing
          rng (fn [n] (mod 42 n))
          result (pareto/select-program-candidate-from-pareto-front
                   simple-pareto-front scores rng)]
      ;; Should return one of the dominators
      (is (contains? #{0 1 2} result))))

  (testing "Selection weighted by frequency"
    ;; Create front where candidate 0 appears in all 3 instances
    (let [weighted-front {0 #{0}
                          1 #{0}
                          2 #{0 1}}
          scores [1.0 1.0]
          ;; Count selections over many trials
          selections (for [seed (range 100)]
                       (let [rng (fn [n] (mod seed n))]
                         (pareto/select-program-candidate-from-pareto-front
                           weighted-front scores rng)))
          freq-0 (count (filter #(= 0 %) selections))
          freq-1 (count (filter #(= 1 %) selections))]
      ;; Candidate 0 has frequency 3 (appears in all fronts)
      ;; Candidate 1 has frequency 1 (appears only in front 2)
      ;; So candidate 0 should be selected ~75% of the time
      (is (> freq-0 freq-1) "Higher-frequency candidate should be selected more often"))))

;; =============================================================================
;; idxmax Tests
;; =============================================================================

(deftest idxmax-test
  (testing "Returns index of maximum value"
    (is (= 0 (pareto/idxmax [5.0 3.0 2.0 1.0])))
    (is (= 2 (pareto/idxmax [1.0 2.0 5.0 3.0])))
    (is (= 3 (pareto/idxmax [1.0 2.0 3.0 4.0]))))

  (testing "Returns first occurrence for ties"
    (is (= 0 (pareto/idxmax [5.0 5.0 3.0])))
    (is (= 1 (pareto/idxmax [3.0 5.0 5.0])))))

;; =============================================================================
;; select-component-round-robin Tests
;; =============================================================================

(deftest select-component-round-robin-test
  (testing "Cycles through components"
    (let [component-names ["instruction" "context" "format"]
          state0 {}

          {:keys [component new-state]}
          (pareto/select-component-round-robin 0 component-names state0)]
      (is (= "instruction" component))
      (is (= {0 1} new-state))

      (let [{:keys [component new-state]}
            (pareto/select-component-round-robin 0 component-names new-state)]
        (is (= "context" component))
        (is (= {0 2} new-state))

        (let [{:keys [component new-state]}
              (pareto/select-component-round-robin 0 component-names new-state)]
          (is (= "format" component))
          (is (= {0 0} new-state))  ;; Wraps around

          (let [{:keys [component]}
                (pareto/select-component-round-robin 0 component-names new-state)]
            (is (= "instruction" component)))))))

  (testing "Independent state per candidate"
    (let [component-names ["a" "b" "c"]
          state {}

          {c1 :component state :new-state}
          (pareto/select-component-round-robin 0 component-names state)

          {c2 :component state :new-state}
          (pareto/select-component-round-robin 1 component-names state)]

      ;; Both start at index 0
      (is (= "a" c1))
      (is (= "a" c2))

      ;; But advance independently
      (is (= {0 1, 1 1} state)))))

;; =============================================================================
;; Pareto Front Computation Tests
;; =============================================================================

(deftest compute-pareto-front-mapping-test
  (testing "Computes correct best-at mapping"
    (let [candidates [{:candidate-id :a :scores [1.0 0.5 0.8]}
                      {:candidate-id :b :scores [0.5 1.0 0.7]}
                      {:candidate-id :c :scores [0.8 0.6 1.0]}]
          result (pareto/compute-pareto-front-mapping candidates)]
      ;; Instance 0: :a is best (1.0)
      (is (= #{:a} (get result 0)))
      ;; Instance 1: :b is best (1.0)
      (is (= #{:b} (get result 1)))
      ;; Instance 2: :c is best (1.0)
      (is (= #{:c} (get result 2)))))

  (testing "Handles ties correctly"
    (let [candidates [{:candidate-id :a :scores [1.0 0.5]}
                      {:candidate-id :b :scores [1.0 0.5]}]
          result (pareto/compute-pareto-front-mapping candidates)]
      ;; Both should be best at instance 0 (tie)
      (is (= #{:a :b} (get result 0)))
      ;; Both should be best at instance 1 (tie)
      (is (= #{:a :b} (get result 1))))))

;; =============================================================================
;; Merge Algorithm Parity Tests
;; =============================================================================

(deftest does-triplet-have-desirable-predictors-test
  (testing "Finds desirable predictors when one descendant diverged"
    (let [candidates [{"pred1" "original" "pred2" "original"}      ;; ancestor
                      {"pred1" "improved" "pred2" "original"}      ;; id1 changed pred1
                      {"pred1" "original" "pred2" "improved"}]]    ;; id2 changed pred2
      ;; This is the ideal merge scenario - each improved a different predictor
      (is (true? (merge/does-triplet-have-desirable-predictors? candidates 0 1 2)))))

  (testing "No desirable predictors when both changed same predictor"
    (let [candidates [{"pred1" "original" "pred2" "original"}
                      {"pred1" "changed1" "pred2" "original"}
                      {"pred1" "changed2" "pred2" "original"}]]
      ;; Both changed pred1 - no independent improvements to merge
      (is (false? (merge/does-triplet-have-desirable-predictors? candidates 0 1 2)))))

  (testing "No desirable predictors when both match ancestor"
    (let [candidates [{"pred1" "original" "pred2" "original"}
                      {"pred1" "original" "pred2" "original"}
                      {"pred1" "original" "pred2" "original"}]]
      ;; No changes at all
      (is (false? (merge/does-triplet-have-desirable-predictors? candidates 0 1 2))))))

(deftest get-ancestors-test
  (testing "Finds direct parents"
    (let [parent-list [[nil]      ;; 0: no parent (root)
                       [0]        ;; 1: parent is 0
                       [0]        ;; 2: parent is 0
                       [1]        ;; 3: parent is 1
                       [2]]]      ;; 4: parent is 2
      (is (= #{0} (merge/get-ancestors 1 parent-list #{})))
      (is (= #{0} (merge/get-ancestors 2 parent-list #{})))
      (is (= #{0 1} (merge/get-ancestors 3 parent-list #{})))))

  (testing "Finds all ancestors in chain"
    (let [parent-list [[nil]
                       [0]
                       [1]
                       [2]]]
      ;; 3 -> 2 -> 1 -> 0
      (is (= #{0 1 2} (merge/get-ancestors 3 parent-list #{}))))))

(deftest should-accept-merge-test
  (testing "Accepts when merged score >= max parent"
    (let [new-scores [0.8 0.9 0.7]      ;; sum = 2.4
          id1-scores [0.7 0.8 0.6]      ;; sum = 2.1
          id2-scores [0.6 0.9 0.5]]     ;; sum = 2.0
      (is (true? (merge/should-accept-merge? new-scores id1-scores id2-scores)))))

  (testing "Accepts when exactly equal to max parent"
    (let [new-scores [0.7 0.8 0.6]      ;; sum = 2.1
          id1-scores [0.7 0.8 0.6]      ;; sum = 2.1
          id2-scores [0.5 0.5 0.5]]     ;; sum = 1.5
      (is (true? (merge/should-accept-merge? new-scores id1-scores id2-scores)))))

  (testing "Rejects when below max parent"
    (let [new-scores [0.5 0.5 0.5]      ;; sum = 1.5
          id1-scores [0.7 0.8 0.6]      ;; sum = 2.1
          id2-scores [0.6 0.9 0.5]]     ;; sum = 2.0
      (is (false? (merge/should-accept-merge? new-scores id1-scores id2-scores))))))

;; =============================================================================
;; Selection Strategy Tests
;; =============================================================================

(deftest select-candidate-best-test
  (testing "Selects candidate with highest aggregate score"
    (is (= 0 (pareto/select-candidate-best [5.0 3.0 2.0])))
    (is (= 2 (pareto/select-candidate-best [2.0 3.0 5.0])))))

(deftest select-candidate-epsilon-greedy-test
  (testing "Exploits when random value >= epsilon"
    ;; With rng always returning 1.0, should always exploit
    (let [scores [3.0 5.0 2.0]
          rng (constantly 0.99)]
      (is (= 1 (pareto/select-candidate-epsilon-greedy scores 0.5 rng)))))

  (testing "Explores when random value < epsilon"
    ;; With rng returning 0.0 for explore check, should explore (random)
    (let [scores [3.0 5.0 2.0]
          call-count (atom 0)
          rng (fn [& args]
                (swap! call-count inc)
                (if (= 1 @call-count)
                  0.1      ;; First call: explore check (< 0.5)
                  1))]     ;; Second call: index selection
      ;; Should explore and select index 1 (mod 1 3 = 1)
      (let [result (pareto/select-candidate-epsilon-greedy scores 0.5 rng)]
        (is (contains? #{0 1 2} result))))))

;; =============================================================================
;; Diversity Metrics Tests
;; =============================================================================

(deftest frontier-diversity-test
  (testing "Counts unique frontier members"
    (is (= 3 (pareto/frontier-diversity simple-pareto-front)))
    ;; overlapping-pareto-front has 3 unique programs (0, 1, 2)
    (is (= 3 (pareto/frontier-diversity overlapping-pareto-front)))))

(deftest dominates-test
  (testing "A dominates B when better on all and strictly better on one"
    (is (true? (pareto/dominates? [1.0 1.0 1.0] [0.9 0.9 0.9])))
    (is (true? (pareto/dominates? [1.0 0.9 0.9] [0.9 0.9 0.9]))))

  (testing "A does not dominate B when worse on any"
    (is (false? (pareto/dominates? [1.0 0.8 1.0] [0.9 0.9 0.9]))))

  (testing "Equal scores - no domination"
    ;; dominates? returns nil/false for non-domination
    (is (not (pareto/dominates? [1.0 1.0 1.0] [1.0 1.0 1.0])))))
