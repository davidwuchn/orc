(ns ai.obney.orc.gepa.core.merge
  "Merge Engine for GEPA - EXACT 1:1 Python GEPA Parity.

   This module implements GEPA's sophisticated crossover/merge logic
   with EXACT parity to Python's proposer/merge.py algorithms:
   - does_triplet_have_desirable_predictors
   - filter_ancestors
   - find_common_ancestor_pair
   - sample_and_attempt_merge_programs_by_common_predictors
   - MergeProposer

   Key insight: GEPA doesn't do random crossover. It finds candidates
   that share a common ancestor and combines the independent improvements
   each descendant made to different predictors."
  (:require [ai.obney.orc.gepa.core.pareto :as pareto]
            [clojure.set :as set]
            [com.brunobonacci.mulog :as u]))

;; =============================================================================
;; Type Aliases (matching Python)
;; =============================================================================
;; AncestorLog = tuple[int, int, int]           ;; (id1, id2, ancestor)
;; MergeDescription = tuple[int, int, tuple[int, ...]]  ;; (id1, id2, source-per-predictor)
;; MergeAttempt = tuple[Candidate, ProgramIdx, ProgramIdx, ProgramIdx] | None

;; =============================================================================
;; Python GEPA Algorithm: does_triplet_have_desirable_predictors
;; =============================================================================
;; From: src/gepa/proposer/merge.py

(defn does-triplet-have-desirable-predictors?
  "Check if a triplet (ancestor, id1, id2) has predictors worth merging.

   EXACT match to Python's does_triplet_have_desirable_predictors.

   Returns true if there exists at least one predictor where:
   - One descendant has the same instruction as ancestor
   - The other descendant has a different instruction
   - This indicates independent improvements on different predictors

   Arguments:
   - program-candidates: Sequence of candidate maps {predictor-name -> instruction}
   - ancestor: Index of the ancestor candidate
   - id1: Index of first descendant
   - id2: Index of second descendant

   Returns true if the triplet is desirable for merging."
  [program-candidates ancestor id1 id2]
  (let [pred-names (keys (nth program-candidates ancestor))
        found-predictors
        (for [pred-name pred-names
              :let [pred-anc (get (nth program-candidates ancestor) pred-name)
                    pred-id1 (get (nth program-candidates id1) pred-name)
                    pred-id2 (get (nth program-candidates id2) pred-name)]
              ;; Condition: one matches ancestor, the other doesn't, and they differ
              :when (and (or (= pred-anc pred-id1)
                             (= pred-anc pred-id2))
                         (not= pred-id1 pred-id2))]
          pred-name)]
    (pos? (count found-predictors))))

;; =============================================================================
;; Python GEPA Algorithm: filter_ancestors
;; =============================================================================
;; From: src/gepa/proposer/merge.py

(defn filter-ancestors
  "Filter common ancestors to find valid merge candidates.

   EXACT match to Python's filter_ancestors.

   Filters out ancestors that:
   - Have already been used in a merge with this pair
   - Have higher score than either descendant (ancestor should be worse)
   - Don't have desirable predictor patterns

   Arguments:
   - i, j: The descendant candidate indices
   - common-ancestors: Set of potential ancestor indices
   - merges-performed: {:ancestor-logs #{[i j ancestor] ...}}
   - agg-scores: Sequence of aggregate scores
   - program-candidates: Sequence of candidate instruction maps

   Returns sequence of valid ancestor indices."
  [i j common-ancestors merges-performed agg-scores program-candidates]
  (->> common-ancestors
       (filter
         (fn [ancestor]
           (and
             ;; Not already merged with this pair
             (not (contains? (:ancestor-logs merges-performed) [i j ancestor]))

             ;; Ancestor score <= both descendants (ancestor should be worse)
             (<= (nth agg-scores ancestor) (nth agg-scores i))
             (<= (nth agg-scores ancestor) (nth agg-scores j))

             ;; Has desirable predictors
             (does-triplet-have-desirable-predictors?
               program-candidates ancestor i j))))
       vec))

;; =============================================================================
;; Python GEPA Algorithm: get_ancestors (helper)
;; =============================================================================

(defn get-ancestors
  "Get all ancestors of a node by traversing parent links.

   Arguments:
   - node: Index of the node
   - parent-list: Sequence of [parent-idx ...] for each node
   - ancestors-found: Accumulated set of ancestors

   Returns set of all ancestor indices."
  [node parent-list ancestors-found]
  (reduce
    (fn [found parent]
      (if (or (nil? parent)
              (contains? found parent))
        found
        (get-ancestors parent parent-list (conj found parent))))
    ancestors-found
    (nth parent-list node [])))

;; =============================================================================
;; Python GEPA Algorithm: find_common_ancestor_pair
;; =============================================================================
;; From: src/gepa/proposer/merge.py

(defn find-common-ancestor-pair
  "Find a pair of candidates with a common ancestor suitable for merging.

   EXACT match to Python's find_common_ancestor_pair.

   Arguments:
   - rng: Random number generator
   - parent-list: Sequence of parent indices for each candidate
   - program-indexes: Candidates to consider
   - merges-performed: Previously performed merges
   - agg-scores: Aggregate scores for weighting
   - program-candidates: Candidate instruction maps
   - max-attempts: Maximum sampling attempts

   Returns [id1 id2 ancestor] or nil."
  [rng parent-list program-indexes merges-performed agg-scores program-candidates
   & {:keys [max-attempts] :or {max-attempts 10}}]

  (loop [attempt 0]
    (when (< attempt max-attempts)
      (if (< (count program-indexes) 2)
        nil

        (let [;; Sample two different candidates
              indexes (vec program-indexes)
              sample-idx (fn [] (nth indexes (rng (count indexes))))
              i (sample-idx)
              j (loop [j' (sample-idx)]
                  (if (= j' i)
                    (recur (sample-idx))
                    j'))

              ;; Ensure i < j for consistent ordering
              [i j] (if (< j i) [j i] [i j])

              ;; Get ancestors
              ancestors-i (get-ancestors i parent-list #{})
              ancestors-j (get-ancestors j parent-list #{})]

          ;; Check if one is ancestor of other (can't merge)
          (if (or (contains? ancestors-i j)
                  (contains? ancestors-j i))
            (recur (inc attempt))

            ;; Find common ancestors
            (let [common-ancestors (set/intersection ancestors-i ancestors-j)
                  filtered (filter-ancestors i j common-ancestors
                                             merges-performed agg-scores
                                             program-candidates)]
              (if (empty? filtered)
                (recur (inc attempt))

                ;; Select ancestor weighted by score
                (let [weights (map #(nth agg-scores %) filtered)
                      total-weight (reduce + weights)
                      r (* (rng) total-weight)]
                  (loop [remaining (map vector filtered weights)
                         cumulative 0.0]
                    (if-let [[anc w] (first remaining)]
                      (let [new-cum (+ cumulative w)]
                        (if (< r new-cum)
                          [i j anc]
                          (recur (rest remaining) new-cum)))
                      ;; Fallback to first
                      [i j (first filtered)])))))))))))

;; =============================================================================
;; Python GEPA Algorithm: sample_and_attempt_merge_programs_by_common_predictors
;; =============================================================================
;; From: src/gepa/proposer/merge.py

(defn sample-and-attempt-merge
  "Sample candidates and attempt to merge them using common ancestor logic.

   EXACT match to Python's sample_and_attempt_merge_programs_by_common_predictors.

   Arguments:
   - agg-scores: Aggregate scores for all candidates
   - rng: Random number generator (fn that returns double in [0,1) or int in [0,n))
   - merge-candidates: Indices of candidates to consider
   - merges-performed: {:ancestor-logs #{...} :merge-descs #{...}}
   - program-candidates: Sequence of candidate instruction maps
   - parent-list: Parent indices for each candidate
   - has-val-support-overlap?: Optional fn (id1 id2) -> bool
   - max-attempts: Max attempts to find mergeable pair

   Returns {:candidate new-instructions, :id1 i, :id2 j, :ancestor anc} or nil."
  [agg-scores rng merge-candidates merges-performed program-candidates parent-list
   & {:keys [has-val-support-overlap? max-attempts]
      :or {max-attempts 10}}]

  (when (and (>= (count merge-candidates) 2)
             (>= (count parent-list) 3))

    (loop [attempt 0]
      (when (< attempt max-attempts)
        (if-let [[id1 id2 ancestor]
                 (find-common-ancestor-pair
                   rng parent-list merge-candidates
                   merges-performed agg-scores program-candidates
                   :max-attempts max-attempts)]

          ;; Check if already merged
          (if (contains? (:ancestor-logs merges-performed) [id1 id2 ancestor])
            (recur (inc attempt))

            ;; Verify score ordering
            (do
              (assert (<= (nth agg-scores ancestor) (nth agg-scores id1))
                      "Ancestor should not be better than descendant 1")
              (assert (<= (nth agg-scores ancestor) (nth agg-scores id2))
                      "Ancestor should not be better than descendant 2")
              (assert (not= id1 id2) "Cannot merge same program")

              ;; Build merged program
              (let [pred-names (keys (nth program-candidates ancestor))
                    {:keys [new-program merge-desc]}
                    (reduce
                      (fn [{:keys [new-program merge-desc]} pred-name]
                        (let [pred-anc (get (nth program-candidates ancestor) pred-name)
                              pred-id1 (get (nth program-candidates id1) pred-name)
                              pred-id2 (get (nth program-candidates id2) pred-name)]
                          (cond
                            ;; Case 1: One matches ancestor, other doesn't
                            (and (or (= pred-anc pred-id1)
                                     (= pred-anc pred-id2))
                                 (not= pred-id1 pred-id2))
                            (let [same-as-ancestor? (= pred-anc pred-id1)
                                  new-value-idx (if same-as-ancestor? id2 id1)]
                              {:new-program (assoc new-program pred-name
                                              (get (nth program-candidates new-value-idx) pred-name))
                               :merge-desc (conj merge-desc new-value-idx)})

                            ;; Case 2: Both differ from ancestor
                            (and (not= pred-anc pred-id1)
                                 (not= pred-anc pred-id2))
                            (let [;; Pick the better scoring one (or random if tied)
                                  source (cond
                                           (> (nth agg-scores id1) (nth agg-scores id2)) id1
                                           (> (nth agg-scores id2) (nth agg-scores id1)) id2
                                           :else (if (< (rng) 0.5) id1 id2))]
                              {:new-program (assoc new-program pred-name
                                              (get (nth program-candidates source) pred-name))
                               :merge-desc (conj merge-desc source)})

                            ;; Case 3: Both are same (either both match ancestor or match each other)
                            :else
                            {:new-program (assoc new-program pred-name
                                            (get (nth program-candidates id1) pred-name))
                             :merge-desc (conj merge-desc id1)})))
                      {:new-program {}
                       :merge-desc []}
                      pred-names)]

                ;; Check if this merge description already exists
                (if (contains? (:merge-descs merges-performed) [id1 id2 merge-desc])
                  (recur (inc attempt))

                  ;; Check validation support overlap if required
                  (if (and has-val-support-overlap?
                           (not (has-val-support-overlap? id1 id2)))
                    (recur (inc attempt))

                    ;; Success!
                    {:candidate new-program
                     :id1 id1
                     :id2 id2
                     :ancestor ancestor
                     :merge-desc merge-desc})))))

          ;; find-common-ancestor-pair returned nil
          (recur (inc attempt)))))))

;; =============================================================================
;; Merge State Management
;; =============================================================================

(defn empty-merge-state
  "Create empty merge tracking state."
  []
  {:ancestor-logs #{}    ;; #{[id1 id2 ancestor] ...}
   :merge-descs #{}})    ;; #{[id1 id2 [source-per-pred]] ...}

(defn record-merge-attempt
  "Record a merge attempt to prevent duplicates."
  [state id1 id2 ancestor merge-desc]
  (-> state
      (update :ancestor-logs conj [id1 id2 ancestor])
      (update :merge-descs conj [id1 id2 merge-desc])))

;; =============================================================================
;; Subsample Selection for Merged Candidates
;; =============================================================================
;; From: src/gepa/proposer/merge.py - MergeProposer.select_eval_subsample_for_merged_program

(defn select-merge-eval-subsample
  "Select evaluation subsample that tests both parent's strengths.

   EXACT match to Python's select_eval_subsample_for_merged_program.

   Selects instances where:
   - id1 beats id2 (p1)
   - id2 beats id1 (p2)
   - They tie (p3)

   Arguments:
   - scores1: Map of {instance-id -> score} for candidate 1
   - scores2: Map of {instance-id -> score} for candidate 2
   - num-samples: Target number of samples (default 5)
   - rng: Random function

   Returns vector of instance-ids."
  [scores1 scores2 rng & {:keys [num-samples] :or {num-samples 5}}]
  (let [common-ids (set/intersection (set (keys scores1)) (set (keys scores2)))
        common-ids-vec (vec common-ids)

        ;; Partition by who's better
        p1 (filterv #(> (get scores1 %) (get scores2 %)) common-ids-vec)
        p2 (filterv #(> (get scores2 %) (get scores1 %)) common-ids-vec)
        p3 (filterv #(and (not (contains? (set p1) %))
                          (not (contains? (set p2) %)))
                    common-ids-vec)

        n-each (max 1 (int (Math/ceil (/ num-samples 3))))

        sample-from-bucket
        (fn [bucket n already-selected]
          (let [available (remove #(contains? already-selected %) bucket)
                take-n (min (count available) n)]
            (if (zero? take-n)
              []
              ;; Random sample
              (loop [selected []
                     remaining (vec available)]
                (if (or (>= (count selected) take-n)
                        (empty? remaining))
                  selected
                  (let [idx (rng (count remaining))
                        item (nth remaining idx)]
                    (recur (conj selected item)
                           (into (subvec remaining 0 idx)
                                 (subvec remaining (inc idx))))))))))

        selected1 (sample-from-bucket p1 n-each #{})
        selected2 (sample-from-bucket p2 n-each (set selected1))
        selected3 (sample-from-bucket p3 n-each (set/union (set selected1) (set selected2)))
        selected (into [] cat [selected1 selected2 selected3])

        ;; Fill remaining if needed
        remaining-count (- num-samples (count selected))
        final-selected
        (if (pos? remaining-count)
          (let [unused (remove #(contains? (set selected) %) common-ids-vec)]
            (if (>= (count unused) remaining-count)
              (into selected (take remaining-count (shuffle unused)))
              (into selected (take remaining-count (cycle common-ids-vec)))))
          selected)]

    (vec (take num-samples final-selected))))

;; =============================================================================
;; Merge Acceptance Check
;; =============================================================================

(defn should-accept-merge?
  "Check if merged candidate should be accepted.

   EXACT match to Python's acceptance criterion: >= max(parents).

   Arguments:
   - new-scores: Vector of scores for merged candidate
   - id1-scores: Vector of scores for parent 1
   - id2-scores: Vector of scores for parent 2

   Returns true if merge should be accepted."
  [new-scores id1-scores id2-scores]
  (let [new-sum (reduce + new-scores)
        parent-max (max (reduce + id1-scores)
                        (reduce + id2-scores))]
    (>= new-sum parent-max)))

;; =============================================================================
;; MergeProposer-style Interface
;; =============================================================================

(defn propose-merge
  "Propose a merge between candidates.

   This is the main entry point matching Python's MergeProposer.propose.

   Arguments:
   - state: GEPA optimization state with:
     - :program-candidates - Sequence of candidate instruction maps
     - :agg-scores - Aggregate scores
     - :parent-list - Parent indices for each candidate
     - :pareto-front-mapping - {instance-id -> #{candidate-ids}}
     - :val-subscores - {candidate-id -> {instance-id -> score}}
   - merge-state: Current merge tracking state
   - rng: Random function
   - config: {:val-overlap-floor min-overlap-instances}

   Returns:
   {:candidate merged-instructions
    :parent-ids [id1 id2]
    :ancestor ancestor
    :merge-state updated-merge-state
    :subsample-ids ids-to-evaluate}
   or nil if no valid merge found."
  [state merge-state rng config]
  (u/log ::propose-merge
         :num-candidates (count (:program-candidates state)))

  (let [{:keys [program-candidates agg-scores parent-list
                pareto-front-mapping val-subscores]} state
        val-overlap-floor (get config :val-overlap-floor 5)

        ;; Find dominator programs to merge
        dominators (pareto/find-dominator-programs pareto-front-mapping agg-scores)

        ;; Validation support overlap check
        has-val-support-overlap?
        (fn [id1 id2]
          (let [common (set/intersection
                         (set (keys (get val-subscores id1 {})))
                         (set (keys (get val-subscores id2 {}))))]
            (>= (count common) val-overlap-floor)))]

    (when-let [result (sample-and-attempt-merge
                        agg-scores rng dominators
                        merge-state program-candidates parent-list
                        :has-val-support-overlap? has-val-support-overlap?)]
      (let [{:keys [candidate id1 id2 ancestor merge-desc]} result

            ;; Get scores for parents
            scores1 (get val-subscores id1 {})
            scores2 (get val-subscores id2 {})

            ;; Select subsample
            subsample-ids (when (and (seq scores1) (seq scores2))
                            (select-merge-eval-subsample scores1 scores2 rng))]

        (when (seq subsample-ids)
          {:candidate candidate
           :parent-ids [id1 id2]
           :ancestor ancestor
           :merge-state (record-merge-attempt merge-state id1 id2 ancestor merge-desc)
           :subsample-ids subsample-ids})))))

;; =============================================================================
;; Legacy Simple Crossover (kept for compatibility)
;; =============================================================================

(defn uniform-crossover
  "Perform simple uniform crossover (not the Python GEPA algorithm)."
  [instructions1 instructions2]
  (let [all-components (set (concat (keys instructions1) (keys instructions2)))]
    (reduce
      (fn [merged component]
        (let [inst1 (get instructions1 component)
              inst2 (get instructions2 component)
              selected (cond
                         (and inst1 inst2) (if (< (rand) 0.5) inst1 inst2)
                         inst1 inst1
                         inst2 inst2
                         :else "")]
          (assoc merged component selected)))
      {}
      all-components)))

(defn weighted-crossover
  "Perform weighted crossover based on scores (not the Python GEPA algorithm)."
  [instructions1 score1 instructions2 score2]
  (let [total-score (+ score1 score2)
        prob1 (if (pos? total-score) (/ score1 total-score) 0.5)
        all-components (set (concat (keys instructions1) (keys instructions2)))]
    (reduce
      (fn [merged component]
        (let [inst1 (get instructions1 component)
              inst2 (get instructions2 component)
              selected (cond
                         (and inst1 inst2) (if (< (rand) prob1) inst1 inst2)
                         inst1 inst1
                         inst2 inst2
                         :else "")]
          (assoc merged component selected)))
      {}
      all-components)))
