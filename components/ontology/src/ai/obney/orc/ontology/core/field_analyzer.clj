(ns ai.obney.orc.ontology.core.field-analyzer
  "LLM-driven field analyzer for dynamic embedding detection.

   Provides code node executors for the field-analyzer ORC workflow.
   Unlike the hardcoded heuristics in embedding.clj, this module uses
   LLM analysis of actual data samples to determine what fields are
   worth embedding for semantic search.

   Key functions:
   - extract-field-metadata: Code node to extract schema info + sample values
   - filter-embeddable-fields: Code node to post-process LLM assessment
   - analyze-schema-fields: Helper for schema introspection
   - sample-field-values: Helper for sampling data"
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [malli.core :as m]
            [com.brunobonacci.mulog :as mu]))

;; =============================================================================
;; Schema Introspection Helpers
;; =============================================================================

(defn- get-type-name
  "Get human-readable type name from Malli schema."
  [schema]
  (when schema
    (let [t (m/type schema)]
      (cond
        (= t :string) "string"
        (= t :int) "integer"
        (= t :double) "number"
        (= t :boolean) "boolean"
        (= t :keyword) "keyword"
        (= t :uuid) "uuid"
        (= t :any) "any"
        (= t :map) "map"
        (= t :vector) (str "vector<" (get-type-name (first (m/children schema))) ">")
        (= t :set) (str "set<" (get-type-name (first (m/children schema))) ">")
        (= t :enum) (str "enum" (vec (m/children schema)))
        (= t :maybe) (str "maybe<" (get-type-name (first (m/children schema))) ">")
        :else (str t)))))

(defn- is-optional-field?
  "Check if a schema entry is optional."
  [[_field-name props _schema]]
  (and (map? props) (:optional props)))

(defn- get-field-description
  "Extract description from Malli schema properties."
  [schema]
  (when schema
    (let [props (m/properties schema)]
      (or (:description props) nil))))

(defn- extract-entry-info
  "Extract metadata from a single Malli schema entry."
  [[field-name props field-schema]]
  (let [actual-schema (if (map? props) field-schema props)
        is-optional (if (map? props) (:optional props) false)]
    {:field-name field-name
     :field-type (get-type-name actual-schema)
     :nullable is-optional
     :description (get-field-description actual-schema)}))

;; =============================================================================
;; Data Sampling Helpers
;; =============================================================================

(defn- sample-field-values
  "Extract sample values for a field from data records.
   Returns up to max-samples unique values."
  [field-name records max-samples]
  (when (seq records)
    (->> records
         (map #(get % field-name))
         (remove nil?)
         (distinct)
         (take max-samples)
         vec)))

(defn- compute-value-stats
  "Compute statistics about sampled values."
  [values]
  (when (seq values)
    (let [string-values (filter string? values)]
      {:sample-count (count values)
       :unique-count (count (distinct values))
       :value-lengths (when (seq string-values)
                        (mapv count string-values))
       :avg-length (when (seq string-values)
                     (double (/ (reduce + (map count string-values))
                                (count string-values))))
       :all-strings? (= (count values) (count string-values))})))

;; =============================================================================
;; Schema Analysis
;; =============================================================================

(defn analyze-schema-fields
  "Analyze a Malli schema and extract field metadata with sample values.

   Args:
     schema: Malli [:map ...] schema
     sample-data: Collection of sample records (can be empty)
     opts: {:max-samples 5}

   Returns:
     Vector of field info maps, each containing:
     {:field-name :label
      :field-type \"string\"
      :nullable false
      :description \"User-provided label\"
      :sample-values [\"Acme Corp\" \"Beta Inc\"]
      :value-stats {:avg-length 8.5 :unique-count 2}}"
  [schema sample-data & {:keys [max-samples] :or {max-samples 5}}]
  (when (and schema (= :map (m/type schema)))
    (let [entries (m/entries schema)]
      (mapv (fn [entry]
              (let [base-info (extract-entry-info entry)
                    field-name (:field-name base-info)
                    samples (sample-field-values field-name sample-data max-samples)
                    stats (compute-value-stats samples)]
                (merge base-info
                       {:sample-values samples
                        :value-stats stats})))
            entries))))

;; =============================================================================
;; Code Node Executors
;; =============================================================================

(defn extract-field-metadata
  "Code node executor: Extract schema structure and sample values.

   Reads:
     - schema: Malli schema (as data)
     - sample-data: Vector of sample records

   Writes:
     - field-candidates: Vector of field metadata maps

   Each field candidate includes:
     {:field-name :label
      :field-type \"string\"
      :nullable false
      :description \"...\" ;; from Malli metadata
      :sample-values [\"Acme Corp\" \"Beta Inc\" \"Gamma LLC\"]
      :value-stats {:avg-length 9.3 :unique-count 3}}"
  [{:keys [inputs]}]
  (let [schema (get inputs :schema)
        sample-data (get inputs :sample-data)
        ;; Convert sample-data keys from strings to keywords if needed
        normalized-data (when (seq sample-data)
                          (mapv (fn [record]
                                  (if (and (map? record)
                                           (some string? (keys record)))
                                    (into {} (map (fn [[k v]] [(keyword k) v]) record))
                                    record))
                                sample-data))
        field-candidates (analyze-schema-fields schema normalized-data)]
    {:field-candidates (or field-candidates [])}))

(defn filter-embeddable-fields
  "Code node executor: Post-process LLM assessment.

   Applies confidence threshold, sorts by value, returns final list.

   Reads:
     - llm-assessment: Map with :fields vector from LLM
     - confidence-threshold: Double (default 0.7)

   Writes:
     - embeddable-fields: Vector of field names (keywords)
     - confidence-scores: Map of field-name -> confidence
     - reasoning: Map of field-name -> reasoning string"
  [{:keys [inputs]}]
  (let [assessment (get inputs :llm-assessment)
        threshold (or (get inputs :confidence-threshold) 0.7)
        fields (or (:fields assessment)
                   (get assessment "fields")
                   [])
        ;; Normalize field data (handle both keyword and string keys)
        normalized-fields (mapv (fn [f]
                                  {:field-name (or (:field-name f)
                                                   (keyword (get f "field-name"))
                                                   (keyword (get f "fieldName")))
                                   :confidence (or (:confidence f)
                                                   (get f "confidence")
                                                   0.0)
                                   :should-embed (or (:should-embed f)
                                                     (get f "should-embed")
                                                     (get f "shouldEmbed")
                                                     false)
                                   :reasoning (or (:reasoning f)
                                                  (get f "reasoning")
                                                  "")})
                                fields)
        ;; Filter and sort by confidence
        embeddable (->> normalized-fields
                        (filter #(:should-embed %))
                        (filter #(>= (:confidence %) threshold))
                        (sort-by :confidence >))]
    {:embeddable-fields (mapv :field-name embeddable)
     :confidence-scores (into {}
                              (map (juxt :field-name :confidence)
                                   normalized-fields))
     :reasoning (into {}
                      (map (juxt :field-name :reasoning)
                           normalized-fields))}))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn format-field-candidates-for-llm
  "Format field candidates as a readable string for LLM prompts."
  [field-candidates]
  (str/join "\n\n"
            (map (fn [{:keys [field-name field-type description sample-values value-stats]}]
                   (str "Field: " (name field-name) "\n"
                        "Type: " field-type "\n"
                        (when description
                          (str "Description: " description "\n"))
                        (when (seq sample-values)
                          (str "Sample values:\n"
                               (str/join "\n"
                                         (map-indexed
                                          (fn [i v]
                                            (str "  " (inc i) ". "
                                                 (if (string? v)
                                                   (str "\"" (subs v 0 (min 100 (count v)))
                                                        (when (> (count v) 100) "...") "\"")
                                                   (pr-str v))))
                                          sample-values))
                               "\n"))
                        (when (:avg-length value-stats)
                          (str "Avg length: " (format "%.1f" (:avg-length value-stats)) " chars\n"))
                        (when (:unique-count value-stats)
                          (str "Unique values in sample: " (:unique-count value-stats)))))
                 field-candidates)))

(defn schema-hash
  "Generate a hash for caching schema analysis results."
  [schema]
  (str (hash (pr-str schema))))

(defn combine-fields-for-embedding
  "Combine specified fields from a record into text for embedding.

   Args:
     record: Data record
     fields: Collection of field names (keywords) to include

   Returns:
     Combined text string suitable for embedding"
  [record fields]
  (->> fields
       (map (fn [field]
              (let [v (get record field)]
                (cond
                  (nil? v) nil
                  (string? v) v
                  (sequential? v) (str/join ", " v)
                  :else (pr-str v)))))
       (remove str/blank?)
       (str/join ". ")))

;; =============================================================================
;; Fallback Heuristics (for when LLM unavailable)
;; =============================================================================

(def ^:private likely-semantic-patterns
  "Regex patterns that suggest semantic content in field names."
  [#"(?i).*description.*"
   #"(?i).*summary.*"
   #"(?i).*content.*"
   #"(?i).*text.*"
   #"(?i).*message.*"
   #"(?i).*note.*"
   #"(?i).*comment.*"
   #"(?i).*feedback.*"
   #"(?i).*explanation.*"
   #"(?i).*reasoning.*"
   #"(?i).*narrative.*"
   #"(?i).*instruction.*"])

(def ^:private likely-non-semantic-patterns
  "Regex patterns that suggest non-semantic content."
  [#"(?i).*[-_]?id$"
   #"(?i).*[-_]?uuid$"
   #"(?i).*[-_]?at$"
   #"(?i).*[-_]?date$"
   #"(?i).*[-_]?time$"
   #"(?i).*[-_]?url$"
   #"(?i).*[-_]?uri$"
   #"(?i).*[-_]?email$"
   #"(?i).*[-_]?phone$"
   #"(?i)^status$"
   #"(?i)^type$"
   #"(?i)^version$"])

(defn heuristic-embeddable?
  "Apply heuristics to determine if a field is likely embeddable.
   Used as fallback when LLM unavailable."
  [{:keys [field-name field-type sample-values value-stats]}]
  (let [name-str (name field-name)]
    (cond
      ;; Explicitly non-semantic patterns
      (some #(re-matches % name-str) likely-non-semantic-patterns)
      {:should-embed false
       :confidence 0.8
       :reasoning "Field name matches non-semantic pattern"}

      ;; Explicitly semantic patterns
      (some #(re-matches % name-str) likely-semantic-patterns)
      {:should-embed true
       :confidence 0.8
       :reasoning "Field name matches semantic pattern"}

      ;; String fields with long average values
      (and (= field-type "string")
           (:avg-length value-stats)
           (> (:avg-length value-stats) 50))
      {:should-embed true
       :confidence 0.7
       :reasoning "String field with substantial average length"}

      ;; Vector of strings
      (str/starts-with? (or field-type "") "vector<string>")
      {:should-embed true
       :confidence 0.6
       :reasoning "Vector of strings likely contains semantic content"}

      ;; Non-string types generally not embeddable
      (not (or (= field-type "string")
               (str/starts-with? (or field-type "") "vector<string>")))
      {:should-embed false
       :confidence 0.9
       :reasoning "Non-string type not suitable for embedding"}

      ;; Default: uncertain
      :else
      {:should-embed false
       :confidence 0.3
       :reasoning "Unable to determine from heuristics alone"})))

(defn detect-embeddable-fields-heuristic
  "Schema + data heuristic detection (no LLM).
   Fallback when LLM analysis is unavailable."
  [schema sample-data & {:keys [threshold] :or {threshold 0.6}}]
  (let [field-candidates (analyze-schema-fields schema sample-data)
        assessments (mapv (fn [fc]
                            (merge fc (heuristic-embeddable? fc)))
                          field-candidates)
        embeddable (->> assessments
                        (filter :should-embed)
                        (filter #(>= (:confidence %) threshold))
                        (sort-by :confidence >))]
    {:embeddable-fields (mapv :field-name embeddable)
     :confidence-scores (into {} (map (juxt :field-name :confidence) assessments))
     :reasoning (into {} (map (juxt :field-name :reasoning) assessments))
     :method :heuristic}))

;; =============================================================================
;; ColBERT Field Detection
;; =============================================================================

(def ^:private colbert-semantic-fields
  "Fields that are commonly semantic and good for ColBERT indexing."
  #{:label :name :title :description :definition :summary
    :content :text :explanation :reasoning :notes :comment})

(def ^:private colbert-non-semantic-fields
  "Fields that are NOT good for ColBERT indexing."
  #{:id :uri :uuid :code :created-at :updated-at :timestamp
    :source-id :entity-type :confidence :score})

(defn detect-colbert-fields
  "Detect which fields from concepts should be indexed in ColBERT.

   Analyzes the structure of concept maps to find text-rich semantic fields
   suitable for late-interaction neural retrieval.

   Args:
     concepts - Vector of concept maps

   Returns:
     {:colbert-fields [:label :description ...]
      :reasoning {:field-name \"reason\" ...}}"
  [concepts]
  (if (empty? concepts)
    {:colbert-fields [:label :description]
     :reasoning {:default "No concepts provided, using default fields"}}
    (let [;; Sample first few concepts to analyze structure
          sample (take 10 concepts)
          all-keys (into #{} (mapcat keys sample))

          ;; Find semantic fields that exist in concepts
          semantic-found (filterv #(contains? all-keys %) colbert-semantic-fields)

          ;; Filter out known non-semantic fields
          other-keys (set/difference all-keys
                                     colbert-semantic-fields
                                     colbert-non-semantic-fields)

          ;; For other keys, check if they look semantic based on sample values
          additional-semantic-strings
          (->> other-keys
               (filter (fn [k]
                         (let [values (keep k sample)
                               string-vals (filter string? values)]
                           (and (seq string-vals)
                                (> (/ (count string-vals) (max 1 (count values))) 0.5)
                                (> (/ (reduce + (map count string-vals))
                                      (max 1 (count string-vals)))
                                   20))))) ;; avg length > 20 chars
               vec)

          ;; Also detect vector-of-strings fields (like :skills, :knowledge)
          additional-semantic-vectors
          (->> other-keys
               (filter (fn [k]
                         (let [values (keep k sample)
                               vector-vals (filter sequential? values)
                               non-empty-vectors (filter seq vector-vals)]
                           (and (seq vector-vals)
                                (> (/ (count vector-vals) (max 1 (count values))) 0.5)
                                ;; Check if non-empty vectors contain strings
                                (seq non-empty-vectors)  ;; At least some non-empty
                                (every? (fn [v] (every? string? v))
                                        non-empty-vectors)))))
               vec)

          colbert-fields (vec (concat semantic-found
                                      additional-semantic-strings
                                      additional-semantic-vectors))

          ;; Build reasoning
          reasoning (into {}
                          (concat
                           (for [f semantic-found]
                             [f "Known semantic field type"])
                           (for [f additional-semantic-strings]
                             [f "String field with substantial average length"])
                           (for [f additional-semantic-vectors]
                             [f "Vector of strings field"])))]

      {:colbert-fields (if (seq colbert-fields)
                         colbert-fields
                         [:label :description]) ;; fallback
       :reasoning (if (seq reasoning)
                    reasoning
                    {:default "Using default fields"})})))

(defn detect-embedding-fields-heuristic
  "Detect embedding fields using heuristics (no LLM).

   Uses detect-colbert-fields logic - suitable when LLM is unavailable
   or for performance-critical paths.

   Args:
     concepts - Vector of concept maps

   Returns:
     {:embedding-fields [:label :description ...]
      :reasoning {:field-name \"reason\" ...}
      :method :heuristic}"
  [concepts]
  (let [{:keys [colbert-fields reasoning]} (detect-colbert-fields concepts)]
    {:embedding-fields colbert-fields
     :reasoning reasoning
     :method :heuristic}))

(defn detect-embedding-fields
  "Detect which fields from concepts should be used for embedding.

   When called with ctx (2-arity), uses LLM-driven ORC workflow to analyze
   field samples and determine which are semantically meaningful. Falls back
   to heuristics if workflow execution fails or ctx is not provided.

   The LLM approach:
   - Analyzes actual sample values, not just field names
   - Works with any datasource without hardcoded field lists
   - Provides reasoning for each decision

   Args:
     concepts - Vector of concept maps (1-arity, uses heuristics)
     ctx - Context with :event-store for sheet execution (2-arity, uses LLM)
     concepts - Vector of concept maps

   Returns:
     {:embedding-fields [:label :description ...]
      :reasoning {:field-name \"reason\" ...}
      :confidence-scores {:field-name 0.95 ...}  ;; Only with LLM
      :method :llm|:heuristic}"
  ([concepts]
   ;; Fallback to heuristics when no context available
   (detect-embedding-fields-heuristic concepts))

  ([ctx concepts]
   (if (nil? ctx)
     (detect-embedding-fields-heuristic concepts)
     (try
       ;; Lazy require to avoid circular dependency
       (require '[ai.obney.orc.ontology.core.field-analyzer-workflow :as faw])
       (let [analyze-schema (resolve 'ai.obney.orc.ontology.core.field-analyzer-workflow/analyze-schema)
             ;; Sample up to 10 concepts for analysis
             sample-data (vec (take 10 concepts))
             ;; Use nil schema - workflow will infer from data
             result (analyze-schema ctx nil nil sample-data :confidence-threshold 0.6)]
         (if (:error result)
           (do
             (mu/log ::llm-workflow-failed :error (:error result) :trace-id (:trace-id result))
             (detect-embedding-fields-heuristic concepts))
           {:embedding-fields (:embeddable-fields result)
            :reasoning (:reasoning result)
            :confidence-scores (:confidence-scores result)
            :trace-id (:trace-id result)
            :method :llm}))
       (catch Exception e
         (mu/log ::llm-workflow-exception :error (.getMessage e))
         (detect-embedding-fields-heuristic concepts))))))
