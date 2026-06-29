(ns ai.obney.orc.ontology.core.domain-penalty
  "EL-5 (ADR 0016, emergence loop): the deterministic CONTRASTIVE domain penalty.

   EL-2 made the LLM reranker READ each candidate's judge-grounded :avoid-when,
   but an orchestrator rate probe measured the refactor->rename-move-symbol
   force-fit persists 9/10 — the LLM ignores the domain veto even when shown it
   (single-scalar shape-override, NOT a recall gap). EL-5 makes the SAME
   :avoid-when evidence BITE DETERMINISTICALLY: after the LLM rerank, apply a
   graded penalty multiplier to each candidate's fitness.

   THE EQUATION (implement exactly):

     domain_penalty(candidate, task) =
       clamp( penalty_scale * max(0, cos_avoid - cos_good - margin),
              0, penalty_cap)
     final_fitness = llm_fitness * (1 - domain_penalty)

   CONTRASTIVE is the load-bearing idea: the penalty fires ONLY when the task is
   MORE like the candidate's :avoid-when than its positive use-case description.
   A naive cos(:avoid-when, task) is REJECTED — it would penalize a web-search
   behavior FOR a web-search task (topic overlap on 'web search', not the
   avoid-condition). The contrast asks 'is this task more the AVOID-condition
   than the USE-case?', not 'does the guard mention a topic in the task?'.

   ----------------------------------------------------------------------------
   AMENDMENT (ADR 0016, post-prototype): the SCORER is a PLUGGABLE injected
   capability; ColBERT is the default.
   ----------------------------------------------------------------------------
   The contrastive equation is unchanged, but the BACKEND that produces
   cos_avoid / cos_good is now config-selected:

     :colbert  (DEFAULT) — colbert/rerank in-memory MaxSim (late-interaction,
                NO index). One rerank call scores ALL guard strings against the
                ONE task query, so avoid + good are on the SAME scale: pass
                (concat avoid-strings positive-strings) once, split the results
                back BY CONTENT, take MAX over each group, NORMALIZE each via
                colbert/normalize-colbert-score -> [0,1]. ColBERT separated the
                refactor force-fit (avoid 20.7 - good 15.3 = +5.46) from the
                web-search zero-FP (7.1 - 10.4 = -3.29); all-MiniLM single-vector
                cosine did NOT (refactor contrast -0.112). So ColBERT is default.

     :embedding — embed + cosine (all-MiniLM today; the MODEL is a config value
                so a stronger embedder can be dropped in). Retained as the
                cheaper/offline + upgrade path (it's the original (b) code).

   The penalty arithmetic (domain-penalty, apply-penalty) and the
   penalize-candidates PASS (score -> multiply fitness -> re-sort) are unchanged
   and already pure-given-a-scorer: this is a STRATEGY SEAM, not a rewrite. The
   scorer is INJECTED — default to the real backend, fake it in tests.

   This namespace holds the PURE arithmetic, the two scorer adapters, and the
   penalize pass. The avoid/good SOURCE strings come from EL-2's enrichment."
  (:require [clojure.string :as str]
            [ai.obney.orc.ontology.core.embedding :as embedding]
            [ai.obney.orc.colbert.interface :as colbert]
            [com.brunobonacci.mulog :as mu]))

;; =============================================================================
;; Knobs (ADR 0016) — all tunable, started CONSERVATIVE.
;;
;; The knobs are scorer-relative: cos_avoid / cos_good are always in [0,1]
;; (cosine for :embedding; colbert/normalize-colbert-score for :colbert), so
;; one (margin, scale, cap) set is scale-stable across backends.
;;
;; The DEFAULT set is RECALIBRATED for the NORMALIZED ColBERT scale against the
;; REAL enriched candidate signals (NOT the hand-written separability probe
;; strings). Calibration measured by development/bench/el5_domain_penalty_proto
;; on the live refactor case (real grain + real ColBERT MaxSim, /40 linear norm):
;;
;;   child/rename-move-symbol (force-fit) cos_avoid 0.518 - cos_good 0.451 = +0.068  <- MUST fire
;;   Validation                            0.345 - 0.382 = -0.038                     <- clean
;;   Research                              0.335 - 0.380 = -0.045                     <- clean
;;   Critique                              0.322 - 0.356 = -0.034                     <- clean
;;   Code-building (correct parent)        0.269 - 0.357 = -0.088                     <- clean
;;
;; So the live separability band is (-0.034, +0.068): every clean case has a
;; NEGATIVE contrast and only the rename force-fit is positive. (The contrast is
;; tighter than the probe's +0.137 because the candidate's POSITIVE strings
;; include the verbose :summary, which itself carries 'refactor'/'extract'
;; tokens that lift cos_good — exactly the content-quality dependency ADR 0016
;; flags; C-3 sharpening the guard widens this over runs.)
;;
;;   :margin 0.03  — sits inside the band: ABOVE every clean case (all <= -0.034,
;;                   so case (3) web-search-on-own-domain + case (2) deepening
;;                   stay penalty 0 — the zero-FP guard) and BELOW the rename
;;                   force-fit +0.068 (so case (1) fires). Conservative: a clean
;;                   case would have to flip from -0.034 to >+0.03 (a 0.064 swing)
;;                   before a false positive — far beyond ColBERT noise here.
;;   :penalty-scale 10.0 — turns rename's net contrast (0.068 - 0.03 = 0.038)
;;                   into a ~0.38 penalty: enough to flip the LLM's shape-favored
;;                   fitness below the correct candidate AND, at threshold 0.6,
;;                   push a borderline force-fit under the gate. Clean cases stay
;;                   at penalty 0 regardless of scale (their contrast is < margin).
;;   :penalty-cap 0.6 — graded, never a hard zero (demoted, not annihilated).
;; =============================================================================

(def default-penalty-config
  "CONSERVATIVE defaults (ADR 0016 — recalibrated for the NORMALIZED ColBERT
   scale, the default backend; see the four-case calibration in the proto):

     :scorer          — :colbert (DEFAULT) | :embedding. Selects the backend.
     :embedding-model — embedding model id when :scorer is :embedding (the model
                        is swappable; nil => the embedding component default,
                        all-MiniLM-L6-v2 today).
     :penalty-scale   — multiplier on the (avoid - good - margin) contrast.
     :margin          — embedding-noise floor: avoid must beat good by MORE than
                        this before any penalty fires (the zero-false-positive
                        guard for case (3) web-search-on-own-domain + case (2)
                        deepening-on-own-task).
     :penalty-cap     — caps the penalty so it stays GRADED, never a hard zero
                        (the candidate is demoted, not annihilated — reversible).
     :colbert-norm    — colbert/normalize-colbert-score opts ({:max-score
                        :method}); applied to both avoid + good so margin/cap are
                        scale-stable. Only used by the :colbert scorer."
  {:scorer :colbert
   :embedding-model nil
   :penalty-scale 10.0
   :margin 0.03
   :penalty-cap 0.6
   :colbert-norm {:max-score 40.0 :method :linear}})

;; =============================================================================
;; The pure penalty arithmetic (DETERMINISTIC — unit-tested hard). UNCHANGED.
;; =============================================================================

(defn clamp
  "Clamp x to [lo, hi]."
  [x lo hi]
  (-> x (max lo) (min hi)))

(defn domain-penalty
  "The CONTRASTIVE penalty for one candidate, given its two scores.

     cos-avoid — score(:avoid-when, task)  (MAX over the candidate's avoid guards)
     cos-good  — score(:good-when/:summary, task) (MAX over the positive signals)

   Returns a penalty in [0, penalty-cap]. Fires (> 0) ONLY when
   cos-avoid - cos-good > margin: the task is more the avoid-condition than the
   use-case, beyond scorer noise. good >= avoid (or within margin) => 0."
  ([cos-avoid cos-good]
   (domain-penalty cos-avoid cos-good default-penalty-config))
  ([cos-avoid cos-good {:keys [penalty-scale margin penalty-cap]
                        :or {penalty-scale (:penalty-scale default-penalty-config)
                             margin (:margin default-penalty-config)
                             penalty-cap (:penalty-cap default-penalty-config)}}]
   (let [contrast (- (double cos-avoid) (double cos-good) (double margin))]
     (clamp (* (double penalty-scale) (max 0.0 contrast))
            0.0
            (double penalty-cap)))))

(defn apply-penalty
  "final_fitness = llm_fitness * (1 - penalty). nil fitness passes through nil
   (the :colbert-fallback path leaves fitness nil on purpose)."
  [fitness penalty]
  (when (some? fitness)
    (* (double fitness) (- 1.0 (double penalty)))))

;; =============================================================================
;; The candidate's signal SOURCE strings (reuses EL-2's enrichment). UNCHANGED.
;; =============================================================================

(defn avoid-strings
  "The candidate's NEGATIVE signals: its enriched top-level :avoid-when vector
   plus any per-weakness :avoid-when guards. EL-2's enrich-candidate-evidence
   already folds the per-weakness guards into the top-level vector, but we union
   defensively so the penalty reads every guard the body carries."
  [candidate]
  (vec (distinct (concat (:avoid-when candidate)
                         (keep :avoid-when (:weaknesses candidate))))))

(defn positive-strings
  "The candidate's POSITIVE signals: the indexed summary (:content) AND every
   :good-when from the enriched strengths. MAX over these is cos-good — the
   use-case description the avoid-condition must beat for the penalty to fire."
  [candidate]
  (vec (distinct (concat (when (:content candidate) [(:content candidate)])
                         (keep :good-when (:strengths candidate))))))

;; =============================================================================
;; SCORERS — the injected capability (ADR 0016 amendment). A scorer is a fn
;;   (scorer candidate task) -> {:cos-avoid <[0,1]> :cos-good <[0,1]>}
;; computing the MAX-over-guards contrast cosines for ONE candidate against the
;; task. Default to the real backend; inject a fake in tests. The two adapters:
;; =============================================================================

(defn- max-cos
  "MAX cosine of the task embedding against a collection of strings. Embeds each
   non-blank string via the embedding interface; returns 0.0 when there is
   nothing to compare (so a missing signal never fabricates a penalty — a
   candidate with no :avoid-when can't be penalized; a candidate with no
   positive signal has cos-good 0.0, the conservative side)."
  [embed-fn task-embedding strings]
  (let [vals (->> strings
                  (remove (fn [s] (or (nil? s) (str/blank? s))))
                  (keep (fn [s]
                          (when-let [e (embed-fn s)]
                            (embedding/cosine-similarity task-embedding e)))))]
    (if (seq vals) (apply max vals) 0.0)))

(defn embedding-scorer
  "The :embedding backend (original (b) path). embed+cosine, MAX over guards.
   The embedding MODEL is config-swappable via :embedding-model (nil => the
   embedding component default). Pure given embed-fn; embeds the task ONCE per
   candidate-batch by closing over a memo. Returns a scorer fn (candidate task).

   In production, embed-fn defaults to the real embedding interface; tests pass a
   deterministic fake embed-fn so no DJL model loads."
  ([config] (embedding-scorer config embedding/embed-text))
  ([{:keys [embedding-model]} embed-fn]
   (let [embed (if embedding-model
                 (fn [s] (embed-fn s {:model-id embedding-model}))
                 embed-fn)
         ;; Memoize the task embedding so a batch of candidates embeds the task
         ;; once; guard strings vary per candidate so they aren't memoized.
         task->emb (memoize embed)]
     (fn [candidate task]
       (let [task-emb (task->emb task)]
         (if (nil? task-emb)
           ;; Can't embed the task — no penalty source (fail open).
           {:cos-avoid 0.0 :cos-good 0.0}
           {:cos-avoid (max-cos embed task-emb (avoid-strings candidate))
            :cos-good  (max-cos embed task-emb (positive-strings candidate))}))))))

(defn colbert-rerank-scores
  "Adapter helper (PURE given rerank-fn): given a rerank-fn that maps
   {:query :documents} -> [{:content :score}], the candidate's avoid + positive
   strings, and the task, run ONE rerank call over (concat avoid good) so every
   guard is scored against the SAME query on the SAME scale, split the results
   back BY CONTENT, take MAX over each group, and NORMALIZE each via norm-fn ->
   [0,1]. Returns {:cos-avoid :cos-good}.

   No guards on a side => that side's score is 0.0 (the conservative side: a
   candidate with no :avoid-when can't be penalized). Deterministic given
   rerank-fn, so tests stub rerank-fn and assert split+max+normalize without
   touching the bridge."
  [rerank-fn norm-fn avoid good task]
  (let [avoid (vec (remove (fn [s] (or (nil? s) (str/blank? s))) avoid))
        good  (vec (remove (fn [s] (or (nil? s) (str/blank? s))) good))
        docs  (vec (distinct (concat avoid good)))]
    (if (empty? docs)
      {:cos-avoid 0.0 :cos-good 0.0}
      (let [res (rerank-fn {:query task :documents docs})
            ;; Split back by content; the same string can appear in both groups
            ;; only if the body lists it both ways — distinct above dedupes the
            ;; rerank docs, but we score each group against its OWN membership.
            by-content (into {} (map (juxt :content :score)) res)
            max-norm (fn [strings]
                       (let [scores (keep by-content strings)]
                         (if (seq scores)
                           (norm-fn (apply max scores))
                           0.0)))]
        {:cos-avoid (max-norm avoid)
         :cos-good  (max-norm good)}))))

(defn colbert-scorer
  "The :colbert backend (DEFAULT). In-memory MaxSim via colbert/rerank — NO
   index. Returns a scorer fn (candidate task). Closes over ctx + the
   normalization opts. rerank-fn / norm-fn default to the real colbert interface;
   tests inject stubs.

   NB: one rerank call PER CANDIDATE (its own guard set), each scoring all of
   that candidate's guards against the task in a single bridge round-trip."
  ([ctx config] (colbert-scorer ctx config
                                (fn [opts] (colbert/rerank ctx opts))
                                colbert/normalize-colbert-score))
  ([_ctx {:keys [colbert-norm]} rerank-fn norm-fn]
   (let [{:keys [max-score method]
          :or {max-score (:max-score (:colbert-norm default-penalty-config))
               method (:method (:colbert-norm default-penalty-config))}} colbert-norm
         norm (fn [score] (norm-fn score :max-score max-score :method method))]
     (fn [candidate task]
       (colbert-rerank-scores rerank-fn norm
                              (avoid-strings candidate)
                              (positive-strings candidate)
                              task)))))

(defn make-scorer
  "Select + construct the scorer from config (ADR 0016 amendment): :colbert
   (DEFAULT) or :embedding. Default real backend; tests bypass this and inject a
   fake scorer directly into score-candidate / penalize-candidates."
  [ctx {:keys [scorer] :or {scorer (:scorer default-penalty-config)} :as config}]
  (case scorer
    :embedding (embedding-scorer config)
    :colbert   (colbert-scorer ctx config)
    ;; Unknown scorer keyword — fall back to the default backend, but log it so a
    ;; typo'd operator config surfaces (never silently mis-score).
    (do (mu/log ::unknown-scorer :scorer scorer :falling-back-to :colbert)
        (colbert-scorer ctx config))))

;; =============================================================================
;; score-candidate / penalize-candidates — the PASS. Now scorer-driven.
;; =============================================================================

(defn score-candidate
  "Compute {:cos-avoid :cos-good :penalty} for one enriched candidate against the
   task, using the injected SCORER. The scorer ((scorer candidate task) ->
   {:cos-avoid :cos-good}) is the seam: :colbert / :embedding in production, a
   deterministic fake in tests. Pure given the scorer + config.

   FAIL OPEN: if the scorer throws (e.g. the ColBERT bridge is unavailable), the
   candidate scores {0,0} -> penalty 0 (the LLM ordering is left untouched, never
   a FABRICATED penalty). The penalty layer is BEST-EFFORT/additive — a scoring
   outage must degrade retrieval gracefully, exactly like the reranker's own
   try/catch fallback, not crash it."
  [candidate task scorer config]
  (let [{:keys [cos-avoid cos-good]}
        (try
          (scorer candidate task)
          (catch Throwable t
            (mu/log ::scorer-failed
                    :document-id (:document-id candidate)
                    :error (.getMessage t))
            {:cos-avoid 0.0 :cos-good 0.0}))
        cos-avoid (double (or cos-avoid 0.0))
        cos-good  (double (or cos-good 0.0))]
    {:cos-avoid cos-avoid
     :cos-good cos-good
     :penalty (domain-penalty cos-avoid cos-good config)}))

(defn penalize-candidates
  "EL-5 penalty PASS: given enriched candidates (each carrying :fitness-score +
   :avoid-when/:content/:strengths from EL-2) and the task string, compute the
   contrastive domain penalty per candidate via the SELECTED scorer, multiply it
   into :fitness-score, stamp :domain-penalty + :cos-avoid + :cos-good for
   observability, and RE-SORT by the new fitness (descending). Candidates without
   a usable fitness (:colbert-fallback, nil) keep nil fitness and sort last.
   Output map shape is otherwise unchanged (the contract
   {:document-id :reasoning :fitness-score} is preserved; the extra keys are
   additive observability).

   The scorer is built from config (default :colbert) once per pass and reused
   across candidates. Pure given the scorer — tests pass a fake scorer + config
   for full determinism (no ColBERT/LLM/DJL)."
  ([ctx candidates task]
   (penalize-candidates ctx candidates task default-penalty-config))
  ([ctx candidates task config]
   (penalize-candidates ctx candidates task config (make-scorer ctx config)))
  ([_ctx candidates task config scorer]
   (->> candidates
        (mapv (fn [c]
                (let [{:keys [cos-avoid cos-good penalty]}
                      (score-candidate c task scorer config)]
                  (-> c
                      (assoc :cos-avoid cos-avoid
                             :cos-good cos-good
                             :domain-penalty penalty)
                      (assoc :fitness-score (apply-penalty (:fitness-score c) penalty))))))
        (sort-by (fn [c] (or (:fitness-score c) -1.0)) >)
        vec)))
