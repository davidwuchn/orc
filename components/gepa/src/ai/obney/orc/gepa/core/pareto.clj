(ns ai.obney.orc.gepa.core.pareto
  "Pareto Selection and Frontier Management - EXACT 1:1 Python GEPA Parity.

   This module implements GEPA's diversity-preserving selection strategy
   with EXACT parity to Python's gepa_utils.py algorithms:
   - is_dominated
   - remove_dominated_programs
   - find_dominator_programs
   - select_program_candidate_from_pareto_front

   Key insight: A candidate that scores best on even ONE validation
   instance is Pareto-optimal. Dominator filtering removes candidates
   whose best-at instances are all also best-at by other candidates."
  (:require [clojure.set :as set]
            [com.brunobonacci.mulog :as u]))

;; =============================================================================
;; Python GEPA Algorithm: is_dominated
;; =============================================================================
;; From: src/gepa/gepa_utils.py

(defn is-dominated?
  "Check if program y is dominated by other programs.

   EXACT match to Python's is_dominated function.

   A program y is dominated if for EVERY front that y appears in,
   at least one program from the 'programs' set also appears in that front.

   This means y has no 'unique' contribution - all instances where y is best
   are also covered by other programs.

   Arguments:
   - y: The program index to check
   - programs: Set of other program indices to compare against
   - pareto-front-mapping: Map of {instance-id -> #{program-ids that are best}}

   Returns true if y is dominated."
  [y programs pareto-front-mapping]
  (let [;; Find all fronts where y appears
        y-fronts (->> (vals pareto-front-mapping)
                      (filter #(contains? % y)))]
    (if (empty? y-fronts)
      ;; If y isn't in any front, it's vacuously dominated
      true
      ;; For each front y appears in, check if any program from 'programs' is also there
      ;; y is dominated only if EVERY front has a dominator
      (every?
        (fn [front]
          (some #(contains? programs %) front))
        y-fronts))))

;; =============================================================================
;; Python GEPA Algorithm: remove_dominated_programs
;; =============================================================================
;; From: src/gepa/gepa_utils.py

(defn remove-dominated-programs
  "Remove dominated programs from the Pareto front.

   EXACT match to Python's remove_dominated_programs function.

   Iteratively removes programs whose best-at instances are all
   also best-at by other programs. The order of removal is determined
   by scores (lowest first) to preserve higher-scoring programs.

   Arguments:
   - pareto-front-mapping: Map of {instance-id -> #{program-ids that are best}}
   - scores: Optional sequence of scores indexed by program-id (nil uses uniform)

   Returns:
   New pareto-front-mapping with dominated programs removed."
  ([pareto-front-mapping]
   (remove-dominated-programs pareto-front-mapping nil))
  ([pareto-front-mapping scores]
   (u/log ::remove-dominated-programs-start
          :num-instances (count pareto-front-mapping))

   (let [;; Count frequency of each program across all fronts
         freq (reduce
                (fn [f front]
                  (reduce
                    (fn [f' p]
                      (update f' p (fnil inc 0)))
                    f
                    front))
                {}
                (vals pareto-front-mapping))

         ;; Get all programs sorted by score (ascending - lowest first)
         programs (keys freq)
         score-map (if scores
                     (into {} (map-indexed (fn [i s] [i s]) scores))
                     (zipmap programs (repeat 1)))
         sorted-programs (sort-by #(get score-map % 0) programs)]

     (loop [dominated #{}
            remaining (vec sorted-programs)
            found-to-remove true]
       (if-not found-to-remove
         ;; No more dominated programs - filter the mapping
         (let [dominators (set/difference (set sorted-programs) dominated)]
           (u/log ::remove-dominated-programs-done
                  :num-dominators (count dominators)
                  :num-dominated (count dominated))
           (into {}
             (map (fn [[instance-id front]]
                    [instance-id (set/intersection front dominators)])
                  pareto-front-mapping)))

         ;; Try to find a dominated program
         (let [result
               (reduce
                 (fn [_ y]
                   (when-not (dominated y)
                     (let [other-progs (set/difference
                                         (set remaining)
                                         #{y}
                                         dominated)]
                       (when (is-dominated? y other-progs pareto-front-mapping)
                         (reduced {:found y})))))
                 nil
                 remaining)]
           (if result
             (recur (conj dominated (:found result))
                    remaining
                    true)
             (recur dominated remaining false))))))))

;; =============================================================================
;; Python GEPA Algorithm: find_dominator_programs
;; =============================================================================
;; From: src/gepa/gepa_utils.py

(defn find-dominator-programs
  "Find the non-dominated programs in the Pareto front.

   EXACT match to Python's find_dominator_programs function.

   Arguments:
   - pareto-front-mapping: Map of {instance-id -> #{program-ids}}
   - agg-scores: Sequence of aggregate scores indexed by program-id

   Returns:
   Vector of program-ids that are dominators (non-dominated)."
  [pareto-front-mapping agg-scores]
  (let [filtered-mapping (remove-dominated-programs pareto-front-mapping agg-scores)
        uniq-progs (->> (vals filtered-mapping)
                        (mapcat identity)
                        set)]
    (vec uniq-progs)))

;; =============================================================================
;; Python GEPA Algorithm: select_program_candidate_from_pareto_front
;; =============================================================================
;; From: src/gepa/gepa_utils.py

(defn select-program-candidate-from-pareto-front
  "Select a candidate from the Pareto front for mutation.

   EXACT match to Python's select_program_candidate_from_pareto_front function.

   1. Remove dominated programs from the front
   2. Count frequency of each program in the filtered front
   3. Sample weighted by frequency (more instances = more likely)

   Arguments:
   - pareto-front-mapping: Map of {instance-id -> #{program-ids}}
   - agg-scores: Sequence of aggregate scores indexed by program-id
   - rng: Random number generator (java.util.Random or function)

   Returns:
   Selected program index."
  [pareto-front-mapping agg-scores rng]
  (let [;; Remove dominated programs
        filtered-mapping (remove-dominated-programs pareto-front-mapping agg-scores)

        ;; Count frequency in filtered front
        freq (reduce
               (fn [f front]
                 (reduce
                   (fn [f' prog-idx]
                     (update f' prog-idx (fnil inc 0)))
                   f
                   front))
               {}
               (vals filtered-mapping))

        ;; Build sampling list where each program appears 'freq' times
        sampling-list (mapcat
                        (fn [[prog-idx frequency]]
                          (repeat frequency prog-idx))
                        freq)]

    (u/log ::select-from-pareto-front
           :num-candidates (count freq)
           :sampling-list-size (count sampling-list))

    (when (empty? sampling-list)
      (throw (ex-info "No Pareto programs survived filtering"
                      {:filtered-mapping filtered-mapping})))

    ;; Random choice from sampling list
    (let [idx (if (fn? rng)
                (rng (count sampling-list))
                (.nextInt ^java.util.Random rng (count sampling-list)))]
      (nth (vec sampling-list) idx))))

;; =============================================================================
;; Python GEPA Algorithm: idxmax
;; =============================================================================
;; From: src/gepa/gepa_utils.py

(defn idxmax
  "Return the index of the maximum value in a sequence.

   EXACT match to Python's idxmax function."
  [scores]
  (let [indexed (map-indexed vector scores)
        max-val (apply max scores)]
    (first (first (filter #(= (second %) max-val) indexed)))))

;; =============================================================================
;; Per-Instance Best Tracking (builds pareto-front-mapping)
;; =============================================================================

(defn compute-pareto-front-mapping
  "Compute the Pareto front mapping from candidates.

   This creates the data structure used by selection:
   {instance-idx -> #{candidate-ids that are best at this instance}}

   Arguments:
   - candidates: Sequence of {:candidate-id id, :scores [s1 s2 ...]}

   Returns:
   Map of {instance-idx -> #{candidate-ids}}"
  [candidates]
  (when (seq candidates)
    (let [num-instances (count (:scores (first candidates)))]
      (reduce
        (fn [mapping idx]
          (let [best-score (->> candidates
                                (map #(nth (:scores %) idx nil))
                                (remove nil?)
                                (apply max Double/NEGATIVE_INFINITY))
                best-ids (->> candidates
                              (filter #(= (nth (:scores %) idx nil) best-score))
                              (map :candidate-id)
                              set)]
            (assoc mapping idx best-ids)))
        {}
        (range num-instances)))))

(defn update-pareto-front-mapping
  "Update the Pareto front mapping with a new candidate's scores.

   Arguments:
   - mapping: Current {instance-idx -> #{candidate-ids}}
   - max-scores: Current {instance-idx -> max-score}
   - candidate-id: ID of new candidate
   - scores: Vector of scores for new candidate

   Returns:
   {:mapping updated-mapping, :max-scores updated-max-scores}"
  [mapping max-scores candidate-id scores]
  (reduce
    (fn [{:keys [mapping max-scores]} idx]
      (let [score (nth scores idx)
            current-max (get max-scores idx Double/NEGATIVE_INFINITY)]
        (cond
          (> score current-max)
          ;; New best - replace the front
          {:mapping (assoc mapping idx #{candidate-id})
           :max-scores (assoc max-scores idx score)}

          (= score current-max)
          ;; Tied - add to front
          {:mapping (update mapping idx (fnil conj #{}) candidate-id)
           :max-scores max-scores}

          :else
          ;; Not best - no change
          {:mapping mapping :max-scores max-scores})))
    {:mapping mapping :max-scores max-scores}
    (range (count scores))))

;; =============================================================================
;; Component Selection - Round Robin (Matches Python)
;; =============================================================================

(defn select-component-round-robin
  "Select which component to mutate using round-robin.

   EXACT match to Python's RoundRobinReflectionComponentSelector.

   Each candidate maintains its own 'next predictor to update' index.
   This ensures all components get mutated over time.

   Arguments:
   - candidate-idx: Index of the candidate being mutated
   - component-names: Vector of component names
   - round-robin-state: Map of {candidate-idx -> next-predictor-idx}

   Returns:
   {:component selected-component-name
    :new-state updated-round-robin-state}"
  [candidate-idx component-names round-robin-state]
  (let [pid (get round-robin-state candidate-idx 0)
        num-components (count component-names)
        next-pid (mod (inc pid) num-components)
        selected (nth component-names pid)]
    {:component selected
     :new-state (assoc round-robin-state candidate-idx next-pid)}))

(defn select-all-components
  "Select all components for mutation.

   EXACT match to Python's AllReflectionComponentSelector."
  [candidate]
  (vec (keys candidate)))

;; =============================================================================
;; Candidate Selection Strategies (Matches Python)
;; =============================================================================

(defn select-candidate-pareto
  "Select a candidate using Pareto-based selection.

   EXACT match to Python's ParetoCandidateSelector."
  [pareto-front-mapping agg-scores rng]
  (select-program-candidate-from-pareto-front
    pareto-front-mapping agg-scores rng))

(defn select-candidate-best
  "Select the current best candidate.

   EXACT match to Python's CurrentBestCandidateSelector."
  [agg-scores]
  (idxmax agg-scores))

(defn select-candidate-epsilon-greedy
  "Select candidate using epsilon-greedy strategy.

   EXACT match to Python's EpsilonGreedyCandidateSelector.

   With probability epsilon, select randomly.
   Otherwise, select the best."
  [agg-scores epsilon rng]
  (let [r (if (fn? rng)
            (rng)
            (.nextDouble ^java.util.Random rng))]
    (if (< r epsilon)
      ;; Explore: random selection
      (let [n (count agg-scores)
            idx (if (fn? rng)
                  (rng n)
                  (.nextInt ^java.util.Random rng n))]
        idx)
      ;; Exploit: select best
      (idxmax agg-scores))))

;; =============================================================================
;; Legacy Compatibility Functions
;; =============================================================================

(defn dominates?
  "Check if candidate A dominates candidate B.

   A dominates B if:
   - A is at least as good as B on ALL instances
   - A is strictly better than B on AT LEAST ONE instance

   Returns true if A dominates B."
  [scores-a scores-b]
  (let [pairs (map vector scores-a scores-b)
        at-least-as-good? (every? (fn [[a b]] (>= a b)) pairs)
        strictly-better? (some (fn [[a b]] (> a b)) pairs)]
    (and at-least-as-good? strictly-better?)))

(defn instances-best-at
  "Get which instances a candidate is best at.

   Returns vector of instance indices where this candidate
   achieves the maximum score."
  [candidate-id pareto-front-mapping]
  (->> pareto-front-mapping
       (filter (fn [[_idx best-ids]]
                 (contains? best-ids candidate-id)))
       (map first)
       vec))

;; =============================================================================
;; Diversity Metrics
;; =============================================================================

(defn frontier-diversity
  "Compute diversity metric for the Pareto frontier.

   Returns the number of unique instance winners - higher is better."
  [pareto-front-mapping]
  (->> (vals pareto-front-mapping)
       (mapcat identity)
       set
       count))

(defn instruction-similarity
  "Compute similarity between two instruction sets using Jaccard similarity."
  [instructions-a instructions-b]
  (let [tokens-a (set (mapcat #(clojure.string/split % #"\s+")
                              (vals instructions-a)))
        tokens-b (set (mapcat #(clojure.string/split % #"\s+")
                              (vals instructions-b)))
        intersection (count (set/intersection tokens-a tokens-b))
        union (count (set/union tokens-a tokens-b))]
    (if (zero? union)
      1.0
      (/ intersection union))))

;; =============================================================================
;; Selection Statistics
;; =============================================================================

(defn selection-statistics
  "Compute statistics about the current selection state."
  [candidates pareto-front-mapping agg-scores]
  (let [dominator-ids (set (find-dominator-programs pareto-front-mapping agg-scores))
        dominator-scores (map #(nth agg-scores %) dominator-ids)]
    {:frontier-size (count dominator-ids)
     :diversity-score (frontier-diversity pareto-front-mapping)
     :num-instances (count pareto-front-mapping)
     :best-aggregate-score (when (seq dominator-scores) (apply max dominator-scores))
     :avg-aggregate-score (when (seq dominator-scores)
                            (/ (reduce + dominator-scores) (count dominator-scores)))}))
