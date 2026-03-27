(ns ai.obney.orc.evaluation.core.feedback
  "Core feedback types and utilities for GEPA-compatible evaluation.

   This namespace provides the foundational data structures for returning
   evaluation results with actionable feedback that can drive instruction
   optimization via GEPA-style reflection."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Core Types
;; =============================================================================

(defrecord ScoreWithFeedback [score feedback dimensions]
  Object
  (toString [_]
    (format "ScoreWithFeedback(score=%.3f, feedback='%s...')"
            score
            (subs feedback 0 (min 50 (count feedback))))))

(defrecord MetricDimension [name weight score feedback]
  Object
  (toString [_]
    (format "MetricDimension(%s: %.2f @ weight=%.2f)"
            name score weight)))

;; =============================================================================
;; Constructors
;; =============================================================================

(defn ->score-with-feedback
  "Create a ScoreWithFeedback with validation.

   Args:
     score: Float between 0.0 and 1.0
     feedback: Actionable text explaining the evaluation
     dimensions: Optional vector of MetricDimension records

   Example:
     (->score-with-feedback
       0.75
       \"Good grounding but missing one key entity\"
       [{:name \"grounding\" :weight 0.6 :score 0.8 :feedback \"...\"}])"
  ([score feedback]
   (->score-with-feedback score feedback nil))
  ([score feedback dimensions]
   {:pre [(number? score)
          (<= 0.0 score 1.0)
          (string? feedback)]}
   (->ScoreWithFeedback (double score) feedback dimensions)))

(defn ->metric-dimension
  "Create a MetricDimension with validation.

   Args:
     name: Human-readable name of this dimension
     weight: Float between 0.0 and 1.0 (weights should sum to 1.0 across dimensions)
     score: Float between 0.0 and 1.0
     feedback: Feedback specific to this dimension

   Example:
     (->metric-dimension \"Source Grounding\" 0.35 0.8 \"Well grounded in sources\")"
  [name weight score feedback]
  {:pre [(string? name)
         (number? weight)
         (<= 0.0 weight 1.0)
         (number? score)
         (<= 0.0 score 1.0)
         (string? feedback)]}
  (->MetricDimension name (double weight) (double score) feedback))

;; =============================================================================
;; Dimension Combination
;; =============================================================================

(defn- score-indicator
  "Return a textual indicator for a score."
  [score]
  (cond
    (>= score 0.8) "GOOD"
    (>= score 0.5) "NEEDS IMPROVEMENT"
    :else "POOR"))

(defn combine-dimension-scores
  "Combine multiple weighted dimensions into a single ScoreWithFeedback.

   Calculates weighted average score and combines feedback, prioritizing
   low-scoring dimensions (they appear first in feedback).

   Args:
     dimensions: Vector of MetricDimension records or maps with
                 :name, :weight, :score, :feedback keys

   Returns:
     ScoreWithFeedback with weighted score and combined feedback

   Example:
     (combine-dimension-scores
       [{:name \"Grounding\" :weight 0.6 :score 0.9 :feedback \"Well grounded\"}
        {:name \"Completeness\" :weight 0.4 :score 0.5 :feedback \"Missing aspects\"}])
     ;; => ScoreWithFeedback(score=0.74, feedback='[Completeness - NEEDS IMPROVEMENT]...')"
  [dimensions]
  (if (empty? dimensions)
    (->score-with-feedback 0.0 "No dimensions to evaluate." [])
    (let [total-weight (reduce + (map :weight dimensions))
          _ (when (zero? total-weight)
              (throw (ex-info "Total weight of dimensions cannot be zero" {})))
          weighted-score (/ (reduce + (map #(* (:weight %) (:score %)) dimensions))
                           total-weight)
          ;; Sort by score ascending to prioritize low scores in feedback
          sorted-dims (sort-by :score dimensions)
          feedback-parts (map (fn [{:keys [name score feedback]}]
                               (let [indicator (score-indicator score)]
                                 (format "[%s - %s]: %s" name indicator feedback)))
                             sorted-dims)
          combined-feedback (str/join "\n" feedback-parts)]
      (->score-with-feedback weighted-score combined-feedback dimensions))))

;; =============================================================================
;; Feedback Templates
;; =============================================================================

(def ^:const FEEDBACK_TEMPLATES
  "Common feedback templates for evaluation patterns.

   Use with format or string interpolation to generate actionable feedback."
  {:missing-entity
   "Missing key entity: %s. This information is critical for %s. Consider: %s"

   :hallucination
   "Response contains claim '%s' which is NOT found in any source. This is hallucination. Consider: only include facts from %s."

   :incomplete-coverage
   "Query has %d aspects but only %d were addressed. Missing: %s. Consider: ensure all parts of user query are covered."

   :wrong-action
   "Recommended %s but outcome indicates %s was needed. Signal missed: %s. Consider: %s"

   :instruction-not-followed
   "Instruction specified: '%s'. Response did not follow this. Specifically: %s. Consider: %s"

   :reasoning-unclear
   "Reasoning is unclear or inconsistent. Issue: %s. Consider: provide step-by-step reasoning that clearly connects premises to conclusions."

   :sarcasm-missed
   "Sarcasm detection failure. The phrase '%s' uses positive words ironically. Sarcasm patterns present: %s. Consider: Add specific sarcasm detection to the instruction."

   :score-miscalibrated
   "Score of %s is %s for the actual quality. Evidence: %s. Consider: %s"})

(defn render-feedback
  "Render a feedback template with the given arguments.

   Args:
     template-key: Keyword key from FEEDBACK_TEMPLATES
     & args: Arguments to format into the template

   Example:
     (render-feedback :hallucination
                      \"classes are free\"
                      \"the provided FAQ documents\")"
  [template-key & args]
  (if-let [template (get FEEDBACK_TEMPLATES template-key)]
    (apply format template args)
    (str "Unknown feedback template: " template-key)))

;; =============================================================================
;; Feedback Analysis Utilities
;; =============================================================================

(defn aggregate-feedback-summary
  "Create a concise summary of multiple dimension feedbacks.

   Args:
     score-with-feedback: A ScoreWithFeedback record

   Returns:
     A condensed summary string suitable for quick review"
  [{:keys [score feedback dimensions]}]
  (let [poor-dims (filter #(< (:score %) 0.5) dimensions)
        needs-work-dims (filter #(and (>= (:score %) 0.5) (< (:score %) 0.8)) dimensions)]
    (cond
      (>= score 0.9)
      (format "Excellent (%.0f%%): All dimensions performing well." (* 100 score))

      (>= score 0.7)
      (format "Good (%.0f%%): %d dimension(s) need improvement: %s"
              (* 100 score)
              (count needs-work-dims)
              (str/join ", " (map :name needs-work-dims)))

      :else
      (format "Poor (%.0f%%): %d dimension(s) failing: %s. Action needed."
              (* 100 score)
              (count poor-dims)
              (str/join ", " (map :name poor-dims))))))
