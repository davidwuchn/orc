(ns ai.obney.orc.gepa.core.metrics
  "Metric functions for GEPA candidate evaluation.

   Pure functions that compare expected vs actual outputs
   and return a score between 0.0 and 1.0.")

(defn make-exact-match-metric
  "Create a metric function that checks for exact match on a key.

   Usage:
   (make-exact-match-metric \"answer\")
   ;; Returns 1.0 if output[\"answer\"] == expected[\"answer\"], else 0.0"
  [output-key]
  (fn [expected actual]
    (let [exp-val (get expected output-key)
          act-val (get actual output-key)]
      (if (and exp-val act-val (= (str exp-val) (str act-val)))
        1.0
        0.0))))

(defn make-contains-metric
  "Create a metric function that checks if output contains expected.

   Usage:
   (make-contains-metric \"answer\")
   ;; Returns 1.0 if expected is substring of output, else 0.0"
  [output-key]
  (fn [expected actual]
    (let [exp-val (str (get expected output-key ""))
          act-val (str (get actual output-key ""))]
      (if (and (seq exp-val)
               (seq act-val)
               (.contains (.toLowerCase act-val) (.toLowerCase exp-val)))
        1.0
        0.0))))

(defn make-judge-metric
  "Create a metric function from orc-service judges.

   Usage:
   (make-judge-metric {:grounding 0.35
                       :completeness 0.25
                       :instruction-following 0.25
                       :reasoning 0.15})

   Note: Judges are run on the execution trace, not direct comparison."
  [judge-weights]
  ;; TODO: Integrate with orc-service judge infrastructure
  ;; For now, return a placeholder that falls back to contains-based scoring
  (fn [expected actual]
    (let [exp-answer (or (get expected "answer") (get expected "expected") "")
          act-answer (or (get actual "answer") (get actual "output") (get actual "result") "")]
      (cond
        (= (str exp-answer) (str act-answer)) 1.0
        (and (string? act-answer)
             (string? exp-answer)
             (.contains (.toLowerCase (str act-answer))
                        (.toLowerCase (str exp-answer)))) 0.7
        :else 0.0))))
