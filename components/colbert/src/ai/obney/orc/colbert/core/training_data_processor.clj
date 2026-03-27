(ns ai.obney.orc.colbert.core.training-data-processor
  "Training data processing for ColBERT fine-tuning.

   Provides Clojure-side data validation, format conversion, and triplet
   generation with EXACT Python parity to RAGatouille's training_data_processor.py.

   Key algorithms:
   - `make-individual-triplets` - Max 20 triplets per query, distributed across positives
   - `process-raw-pairs` - Convert pairs to data-map with placeholder negatives
   - `process-raw-labeled-pairs` - Convert labeled pairs to data-map

   Usage:
   ```clojure
   ;; Process pairs into triplets
   (def data-map (process-raw-pairs [[\"query1\" \"positive1\"] [\"query2\" \"positive2\"]]))
   (def triplets (make-individual-triplets data-map))

   ;; Export for ColBERT training
   (export-training-data triplets \"./training-data/triplets.jsonl\")
   ```"
  (:require [ai.obney.grain.event-store-v3.interface :as event-store]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]))

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate-pair
  "Validate a [query positive] pair."
  [[query positive :as pair]]
  (cond
    (not (vector? pair))
    {:valid? false :error "Pair must be a vector"}

    (not= 2 (count pair))
    {:valid? false :error (str "Pair must have exactly 2 elements, got " (count pair))}

    (str/blank? query)
    {:valid? false :error "Query cannot be blank"}

    (str/blank? positive)
    {:valid? false :error "Positive passage cannot be blank"}

    :else
    {:valid? true}))

(defn validate-labeled-pair
  "Validate a [query passage label] labeled pair."
  [[query passage label :as pair]]
  (cond
    (not (vector? pair))
    {:valid? false :error "Labeled pair must be a vector"}

    (not= 3 (count pair))
    {:valid? false :error (str "Labeled pair must have exactly 3 elements, got " (count pair))}

    (str/blank? query)
    {:valid? false :error "Query cannot be blank"}

    (str/blank? passage)
    {:valid? false :error "Passage cannot be blank"}

    (not (contains? #{0 1} label))
    {:valid? false :error (str "Label must be 0 or 1, got " label)}

    :else
    {:valid? true}))

(defn validate-triplet
  "Validate a [query positive negative] triplet."
  [[query positive negative :as triplet]]
  (cond
    (not (vector? triplet))
    {:valid? false :error "Triplet must be a vector"}

    (not= 3 (count triplet))
    {:valid? false :error (str "Triplet must have exactly 3 elements, got " (count triplet))}

    (str/blank? query)
    {:valid? false :error "Query cannot be blank"}

    (str/blank? positive)
    {:valid? false :error "Positive passage cannot be blank"}

    (str/blank? negative)
    {:valid? false :error "Negative passage cannot be blank"}

    :else
    {:valid? true}))

(defn validate-training-data
  "Validate training data before processing.

   Args:
     raw-data - Vector of pairs, labeled-pairs, or triplets
     format - One of :pairs, :labeled-pairs, :triplets

   Returns:
     {:valid? bool :errors [...] :warnings [...]}"
  [raw-data format]
  (when (empty? raw-data)
    (throw (ex-info "Training data cannot be empty" {:format format})))

  (let [validator (case format
                    :pairs validate-pair
                    :labeled-pairs validate-labeled-pair
                    :triplets validate-triplet)
        results (map-indexed
                  (fn [idx item]
                    (let [result (validator item)]
                      (when-not (:valid? result)
                        {:index idx :error (:error result) :item item})))
                  raw-data)
        errors (remove nil? results)]

    (if (seq errors)
      {:valid? false
       :errors (vec (take 10 errors))  ; Limit error output
       :total-errors (count errors)}
      {:valid? true
       :count (count raw-data)})))

;; =============================================================================
;; Data Format Conversion
;; =============================================================================

(defn process-raw-pairs
  "Convert [[query positive] ...] pairs to data-map.

   EXACT match to Python TrainingDataProcessor._process_raw_pairs.

   Args:
     pairs - Vector of [query positive] pairs

   Returns:
     {query {:positives [positive ...] :negatives []}}"
  [pairs]
  (reduce
    (fn [data-map [query positive]]
      (update data-map query
              (fn [existing]
                (if existing
                  (update existing :positives conj positive)
                  {:positives [positive] :negatives []}))))
    {}
    pairs))

(defn process-raw-labeled-pairs
  "Convert [[query passage label] ...] labeled pairs to data-map.

   EXACT match to Python TrainingDataProcessor._process_raw_labeled_pairs.
   Label 1 = positive, Label 0 = negative.

   Args:
     labeled-pairs - Vector of [query passage label] triples

   Returns:
     {query {:positives [...] :negatives [...]}}"
  [labeled-pairs]
  (reduce
    (fn [data-map [query passage label]]
      (let [key (if (= label 1) :positives :negatives)]
        (update data-map query
                (fn [existing]
                  (if existing
                    (update existing key conj passage)
                    (if (= key :positives)
                      {:positives [passage] :negatives []}
                      {:positives [] :negatives [passage]}))))))
    {}
    labeled-pairs))

(defn process-raw-triplets
  "Convert [[query positive negative] ...] triplets to data-map.

   EXACT match to Python TrainingDataProcessor._process_raw_triplets.

   Args:
     triplets - Vector of [query positive negative] triples

   Returns:
     {query {:positives [...] :negatives [...]}}"
  [triplets]
  (reduce
    (fn [data-map [query positive negative]]
      (update data-map query
              (fn [existing]
                (if existing
                  (-> existing
                      (update :positives conj positive)
                      (update :negatives conj negative))
                  {:positives [positive] :negatives [negative]}))))
    {}
    triplets))

;; =============================================================================
;; Triplet Generation
;; =============================================================================

(defn make-individual-triplets
  "Generate individual triplets from data-map.

   EXACT match to Python TrainingDataProcessor._make_individual_triplets:
   - Max 20 triplets per query
   - Negatives distributed across positives
   - negs_per_positive = max(1, 20 // len(positives))

   Args:
     data-map - {query {:positives [...] :negatives [...]}}

   Returns:
     Vector of [query positive negative] triplets"
  [data-map]
  (reduce-kv
    (fn [triplets query {:keys [positives negatives]}]
      (if (or (empty? positives) (empty? negatives))
        triplets
        (let [;; EXACT Python parity: negs_per_positive = max(1, 20 // len(positives))
              negs-per-positive (max 1 (quot 20 (count positives)))
              ;; Cycle through negatives
              neg-cycle (cycle negatives)]
          (into triplets
                (for [[pos-idx positive] (map-indexed vector positives)
                      [neg-idx negative] (map-indexed vector
                                                       (take negs-per-positive
                                                             (drop (* pos-idx negs-per-positive)
                                                                   neg-cycle)))]
                  [query positive negative])))))
    []
    data-map))

(defn deduplicate-triplets
  "Remove duplicate triplets.

   Args:
     triplets - Vector of [query positive negative]

   Returns:
     Vector of unique triplets"
  [triplets]
  (vec (distinct triplets)))

;; =============================================================================
;; Export
;; =============================================================================

(defn export-training-data
  "Export triplets to JSONL file for ColBERT training.

   EXACT match to Python output format:
   {\"query\": q, \"positive\": p, \"negative\": n}

   Args:
     triplets - Vector of [query positive negative]
     output-path - Path to write JSONL file

   Returns:
     {:path output-path :num-triplets count}"
  [triplets output-path]
  (io/make-parents output-path)
  (with-open [writer (io/writer output-path)]
    (doseq [[query positive negative] triplets]
      (.write writer
              (json/write-str {"query" query
                               "positive" positive
                               "negative" negative}))
      (.newLine writer)))
  {:path output-path
   :num-triplets (count triplets)})

(defn export-training-data-tsv
  "Export triplets to TSV file (alternative format).

   Args:
     triplets - Vector of [query positive negative]
     output-path - Path to write TSV file

   Returns:
     {:path output-path :num-triplets count}"
  [triplets output-path]
  (io/make-parents output-path)
  (with-open [writer (io/writer output-path)]
    (doseq [[query positive negative] triplets]
      (.write writer (str query "\t" positive "\t" negative))
      (.newLine writer)))
  {:path output-path
   :num-triplets (count triplets)})

;; =============================================================================
;; High-Level API
;; =============================================================================

(defn process-training-data
  "Process raw training data into triplets.

   Args:
     raw-data - Training data in one of the supported formats
     format - One of :pairs, :labeled-pairs, :triplets
     opts:
       :validate? - Whether to validate input (default true)
       :deduplicate? - Whether to remove duplicate triplets (default true)

   Returns:
     {:data-map {...} :triplets [...] :stats {...}}"
  [raw-data format & {:keys [validate? deduplicate?]
                      :or {validate? true deduplicate? true}}]
  ;; Validate input
  (when validate?
    (let [validation (validate-training-data raw-data format)]
      (when-not (:valid? validation)
        (throw (ex-info "Invalid training data"
                        {:format format
                         :errors (:errors validation)
                         :total-errors (:total-errors validation)})))))

  ;; Process to data-map
  (let [data-map (case format
                   :pairs (process-raw-pairs raw-data)
                   :labeled-pairs (process-raw-labeled-pairs raw-data)
                   :triplets (process-raw-triplets raw-data))

        ;; Generate triplets
        triplets (make-individual-triplets data-map)

        ;; Optionally deduplicate
        final-triplets (if deduplicate?
                         (deduplicate-triplets triplets)
                         triplets)]

    {:data-map data-map
     :triplets final-triplets
     :stats {:num-queries (count data-map)
             :num-triplets (count final-triplets)
             :avg-triplets-per-query (when (pos? (count data-map))
                                       (double (/ (count final-triplets)
                                                  (count data-map))))}}))

;; =============================================================================
;; Training Data Extraction from Traces
;; =============================================================================

(defn extract-training-pairs-from-traces
  "Extract query->output pairs from successful tree executions.

   This function queries the event store for execution traces and evaluation
   scores, filtering for high-quality outputs suitable for ColBERT training.
   Produces pairs of [input-text, output-text] that can be used with
   prepare-training-data!.

   Args:
     ctx - Context map containing :event-store
     opts - Options map:
       :sheet-id          - UUID of the sheet to extract from (required)
       :min-score         - Minimum evaluation score to include (default: 0.7)
       :since             - Only include traces after this timestamp (optional)
       :limit             - Maximum number of pairs to extract (default: 1000)
       :input-keys        - Blackboard keys to use as query text (default: all inputs)
       :output-keys       - Blackboard keys to use as positive text (default: all outputs)
       :format-fn         - Custom function to format input/output maps to strings
                            (default: JSON-encodes the map)

   Returns:
     {:pairs     [[query positive] ...]  - Training pairs for ColBERT
      :stats     {:total-traces int
                  :evaluated-traces int
                  :passing-traces int
                  :avg-score double}
      :trace-ids [uuid ...]              - IDs of included traces}"
  [ctx {:keys [sheet-id min-score since limit input-keys output-keys format-fn]
        :or {min-score 0.7
             limit 1000
             format-fn (fn [m] (json/write-str m))}}]
  (when-not sheet-id
    (throw (ex-info "sheet-id is required" {})))

  (let [event-store* (:event-store ctx)
        tenant-id (:tenant-id ctx)

        ;; Query execution traces for this sheet
        trace-events (event-store/read event-store*
                       {:types #{:sheet/execution-traced}
                        :tags #{[:sheet sheet-id]}
                        :since since
                        :limit (* 2 limit)
                        :tenant-id tenant-id})  ; Get extra to allow for filtering

        ;; Build trace-id -> trace data map
        traces-by-id (into {}
                       (for [evt trace-events
                             :let [body (:body evt)]
                             :when (= :success (:status body))]
                         [(:trace-id body) body]))

        ;; Query evaluation events for these traces
        eval-events (when (seq traces-by-id)
                      (event-store/read event-store*
                        {:types #{:evaluation/trace-evaluated}
                         :tags #{[:sheet sheet-id]}
                         :tenant-id tenant-id}))

        ;; Build trace-id -> evaluation score map
        evals-by-trace (into {}
                         (for [evt eval-events
                               :let [body (:body evt)]
                               :when (contains? traces-by-id (:trace-id body))]
                           [(:trace-id body) body]))

        ;; Filter traces by min-score
        passing-traces (for [[trace-id trace] traces-by-id
                             :let [eval-data (get evals-by-trace trace-id)
                                   score (or (:aggregate-score eval-data) 0.0)]
                             :when (>= score min-score)]
                         {:trace-id trace-id
                          :trace trace
                          :score score})

        ;; Sort by score (descending) and limit
        sorted-traces (->> passing-traces
                           (sort-by :score >)
                           (take limit))

        ;; Extract pairs
        pairs (for [{:keys [trace]} sorted-traces
                    :let [inputs (:input-snapshot trace)
                          outputs (:output-snapshot trace)
                          ;; Filter to specified keys if provided
                          filtered-inputs (if input-keys
                                            (select-keys inputs input-keys)
                                            inputs)
                          filtered-outputs (if output-keys
                                             (select-keys outputs output-keys)
                                             outputs)]
                    :when (and (seq filtered-inputs) (seq filtered-outputs))]
                [(format-fn filtered-inputs)
                 (format-fn filtered-outputs)])

        ;; Calculate stats
        total-traces (count trace-events)
        evaluated-traces (count evals-by-trace)
        passing-count (count sorted-traces)
        avg-score (if (pos? passing-count)
                    (/ (reduce + (map :score sorted-traces)) passing-count)
                    0.0)]

    (mu/log ::extracted-training-pairs
            :sheet-id sheet-id
            :total-traces total-traces
            :evaluated-traces evaluated-traces
            :passing-traces passing-count
            :pairs-generated (count pairs)
            :avg-score avg-score)

    {:pairs (vec pairs)
     :stats {:total-traces total-traces
             :evaluated-traces evaluated-traces
             :passing-traces passing-count
             :avg-score avg-score}
     :trace-ids (mapv :trace-id sorted-traces)}))
