(ns ai.obney.orc.gepa.core.reflection
  "Reflective Dataset Building for GEPA - EXACT 1:1 Python GEPA Parity.

   This module handles building reflective datasets from evaluation traces,
   matching Python GEPA's ReflectiveExample structure and markdown formatting.

   Python Reference:
   - ReflectiveExample TypedDict from adapters/dspy_adapter/dspy_adapter.py
   - format_samples from strategies/instruction_proposal.py"
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [com.brunobonacci.mulog :as u]))

;; =============================================================================
;; ReflectiveExample Structure - Matches Python TypedDict
;; =============================================================================
;; From: src/gepa/adapters/dspy_adapter/dspy_adapter.py
;;
;; ReflectiveExample = TypedDict(
;;     "ReflectiveExample",
;;     {
;;         "Inputs": dict[str, Any],
;;         "Generated Outputs": dict[str, Any] | str,
;;         "Feedback": str,
;;     },
;; )

(defn make-reflective-example
  "Create a ReflectiveExample matching Python's TypedDict structure.

   Arguments:
   - inputs: Map of input field names to values
   - generated-outputs: Map of output field names to values, OR error string
   - feedback: Feedback text for the LLM

   Returns:
   {:Inputs {...}
    :Generated-Outputs {...} or \"error message\"
    :Feedback \"...\"}"
  [inputs generated-outputs feedback]
  {"Inputs" inputs
   "Generated Outputs" generated-outputs
   "Feedback" feedback})

;; =============================================================================
;; Value Formatting - Matches Python's render_value
;; =============================================================================
;; From: src/gepa/strategies/instruction_proposal.py
;; Function: format_samples -> render_value

(defn render-value
  "Render a value as markdown, matching Python's render_value logic.

   - Images are replaced with [IMAGE-N] placeholders (not applicable in Clojure)
   - Dicts become nested headers
   - Lists become numbered items
   - Other values are stringified

   Arguments:
   - value: The value to render
   - level: Markdown header depth (default 3)

   Returns:
   Formatted string"
  ([value] (render-value value 3))
  ([value level]
   (cond
     ;; Maps become nested headers
     (map? value)
     (if (empty? value)
       "\n"
       (str/join
         (for [[k v] value]
           (str (apply str (repeat level "#")) " " (name k) "\n"
                (render-value v (min (inc level) 6))))))

     ;; Vectors/seqs become numbered items
     (sequential? value)
     (if (empty? value)
       "\n"
       (str/join
         (map-indexed
           (fn [i item]
             (str (apply str (repeat level "#")) " Item " (inc i) "\n"
                  (render-value item (min (inc level) 6))))
           value)))

     ;; Everything else: stringify and trim
     :else
     (str (str/trim (str value)) "\n\n"))))

;; =============================================================================
;; Sample Formatting - Matches Python's convert_sample_to_markdown
;; =============================================================================

(defn convert-sample-to-markdown
  "Convert a single ReflectiveExample to markdown.

   Matches Python's convert_sample_to_markdown function.

   Arguments:
   - sample: Map with 'Inputs', 'Generated Outputs', 'Feedback' keys
   - example-num: 1-based example number

   Returns:
   Formatted markdown string"
  [sample example-num]
  (let [sb (StringBuilder.)]
    (.append sb (str "# Example " example-num "\n"))
    (doseq [[k v] sample]
      (.append sb (str "## " k "\n"))
      (.append sb (render-value v 3)))
    (.toString sb)))

(defn format-reflective-examples
  "Format a list of ReflectiveExamples as markdown.

   Matches Python's format_samples function from instruction_proposal.py.

   Arguments:
   - examples: Sequence of ReflectiveExample maps

   Returns:
   Formatted markdown string with all examples"
  [examples]
  (->> examples
       (map-indexed (fn [idx sample]
                      (convert-sample-to-markdown sample (inc idx))))
       (str/join "\n\n")))

;; =============================================================================
;; Reflective Dataset Building - Matches Python's make_reflective_dataset
;; =============================================================================

(defn- extract-predictor-inputs
  "Extract inputs from a trace for a specific predictor.

   In ORC, this maps to extracting the inputs that were
   passed to a specific LLM node."
  [trace predictor-name]
  ;; ORC traces have different structure than DSPy
  ;; This extracts the inputs section for the named node
  (get-in trace [:nodes predictor-name :inputs] {}))

(defn- extract-predictor-outputs
  "Extract outputs from a trace for a specific predictor."
  [trace predictor-name]
  (get-in trace [:nodes predictor-name :outputs] {}))

(defn- format-parse-failure
  "Format a parse failure message matching Python's FailedPrediction handling.

   Python format:
   \"Couldn't parse the output as per the expected output format. The model's raw response was:
   ```
   {raw_response}
   ```\""
  [raw-response]
  (str "Couldn't parse the output as per the expected output format. "
       "The model's raw response was:\n"
       "```\n"
       raw-response "\n"
       "```\n\n"))

(defn build-reflective-example
  "Build a single ReflectiveExample from evaluation data.

   Arguments:
   - inputs: Map of input field names to values
   - outputs: Map of output field names to values, or nil for failures
   - raw-response: Raw LLM response (for parse failures)
   - feedback-fn: Function that generates feedback from (inputs, outputs, expected)
   - expected: Expected outputs for comparison

   Returns:
   ReflectiveExample map"
  [{:keys [inputs outputs raw-response feedback-fn expected parse-failed?]}]
  (let [;; Format inputs - convert all values to strings
        formatted-inputs (into {}
                           (map (fn [[k v]]
                                  [(if (keyword? k) (name k) (str k))
                                   (if (string? v) v (json/generate-string v))])
                                inputs))

        ;; Format outputs - either error string or map of stringified values
        formatted-outputs (if parse-failed?
                            (format-parse-failure raw-response)
                            (into {}
                              (map (fn [[k v]]
                                     [(if (keyword? k) (name k) (str k))
                                      (if (string? v) v (str v))])
                                   outputs)))

        ;; Generate feedback
        feedback (if parse-failed?
                   "Your output failed to parse. Please follow the expected output format."
                   (if feedback-fn
                     (feedback-fn {:inputs inputs
                                   :outputs outputs
                                   :expected expected})
                     "No feedback provided."))]

    (make-reflective-example formatted-inputs formatted-outputs feedback)))

(defn make-reflective-dataset
  "Build a reflective dataset from evaluation traces.

   This matches Python's DspyAdapter.make_reflective_dataset method.

   Arguments:
   - candidate: Map of component-name → instruction
   - evaluation-traces: Sequence of trace data from evaluation
   - components-to-update: List of component names to include
   - feedback-map: Map of component-name → feedback-fn

   Returns:
   Map of component-name → list of ReflectiveExample maps"
  [candidate evaluation-traces components-to-update feedback-map]
  (u/log ::building-reflective-dataset
         :components components-to-update
         :num-traces (count evaluation-traces))

  (reduce
    (fn [dataset component-name]
      (let [examples
            (->> evaluation-traces
                 (keep (fn [trace-data]
                         (let [{:keys [inputs outputs expected score raw-response parse-failed?]}
                               (get-in trace-data [:components component-name])]
                           (when inputs
                             (build-reflective-example
                               {:inputs inputs
                                :outputs outputs
                                :raw-response raw-response
                                :parse-failed? parse-failed?
                                :expected expected
                                :feedback-fn (get feedback-map component-name)})))))
                 (vec))]
        (if (seq examples)
          (assoc dataset component-name examples)
          (do
            (u/log ::no-reflective-examples
                   :component component-name)
            dataset))))
    {}
    components-to-update))

;; =============================================================================
;; ORC-Specific Helpers
;; =============================================================================

(defn orc-trace->reflective-data
  "Convert an ORC execution trace to reflective dataset format.

   ORC traces have structure:
   {:nodes {\"node-name\" {:inputs {...} :outputs {...}}
            ...}
    :blackboard {...}
    :status :success/:failure}

   Arguments:
   - trace: ORC execution trace
   - component-name: Name of the LLM node
   - expected: Expected outputs
   - feedback-fn: Function to generate feedback

   Returns:
   ReflectiveExample or nil if component not in trace"
  [trace component-name expected feedback-fn]
  (when-let [node-data (get-in trace [:nodes component-name])]
    (let [{:keys [inputs outputs status raw-response]} node-data
          parse-failed? (= status :parse-failure)]
      (build-reflective-example
        {:inputs inputs
         :outputs outputs
         :raw-response raw-response
         :parse-failed? parse-failed?
         :expected expected
         :feedback-fn feedback-fn}))))

(defn build-orc-reflective-dataset
  "Build reflective dataset from ORC evaluation results.

   Arguments:
   - evaluation-results: Sequence of {:trace, :inputs, :expected, :score} maps
   - components: List of LLM node names
   - feedback-fns: Map of component-name → feedback-fn

   Returns:
   Map of component-name → formatted-examples-string (ready for proposer)"
  [evaluation-results components feedback-fns]
  (let [raw-dataset
        (reduce
          (fn [dataset {:keys [trace inputs expected]}]
            (reduce
              (fn [d component]
                (if-let [example (orc-trace->reflective-data
                                   trace
                                   component
                                   expected
                                   (get feedback-fns component))]
                  (update d component (fnil conj []) example)
                  d))
              dataset
              components))
          {}
          evaluation-results)]

    ;; Format each component's examples as markdown
    (into {}
      (map (fn [[component examples]]
             [component (format-reflective-examples examples)])
           raw-dataset))))

;; =============================================================================
;; Default Feedback Functions
;; =============================================================================

(defn simple-comparison-feedback
  "Generate feedback by comparing outputs to expected values.

   This is a simple default feedback function that can be used
   when no custom feedback logic is needed."
  [{:keys [inputs outputs expected]}]
  (cond
    (nil? expected)
    "No expected output provided for comparison."

    (= outputs expected)
    "Output matches expected result."

    :else
    (let [diffs (for [[k v] expected
                      :let [actual (get outputs k)]
                      :when (not= v actual)]
                  (str "- " k ": expected '" v "', got '" actual "'"))]
      (str "Output differs from expected:\n"
           (str/join "\n" diffs)))))

(defn score-based-feedback
  "Generate feedback based on a numeric score.

   Arguments:
   - score: Numeric score (0.0 to 1.0)
   - threshold: Score threshold for success (default 0.8)

   Returns:
   Feedback string"
  [score & {:keys [threshold] :or {threshold 0.8}}]
  (cond
    (>= score threshold)
    (str "Good result with score " (format "%.2f" score) ".")

    (>= score 0.5)
    (str "Partial success with score " (format "%.2f" score)
         ". Consider improving accuracy and completeness.")

    :else
    (str "Poor result with score " (format "%.2f" score)
         ". The output significantly differs from expectations.")))
