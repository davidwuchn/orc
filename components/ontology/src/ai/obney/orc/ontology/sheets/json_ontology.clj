(ns ai.obney.orc.ontology.sheets.json-ontology
  "JSON-to-Ontology extraction pipeline.

   Extracts ontologies from arbitrary JSON using LLM reasoning.
   Handles poorly labeled/cryptic data where the LLM must infer
   entity types and relationships from evidence (values, patterns, structure).

   Phases:
   1. Parse Structure - Analyze JSON arrays, objects, nesting, field types
   2. Analyze Domain (LLM) - Identify domain and purpose
   3. Discover Entity Types (LLM) - Infer meaningful entity names
   4. Deduplicate - Remove duplicate concepts
   5. Generate Definitions (LLM) - Create formal definitions
   6. Discover Relationships (LLM) - Find relationships from structure
   7. Validate Quality (LLM) - Check for ambiguity
   8. Build T-Box - Construct OWL classes/properties
   9. Build A-Box - Construct instances
   10. Serialize - Generate OWL output"
  (:require [ai.obney.orc.orc-service.interface :as sheet]
            [clojure.string :as str]
            [clojure.data.json :as json]))

;; =============================================================================
;; JSON Structure Analysis (Phase 1)
;; =============================================================================

(defn analyze-json-structure
  "Analyze JSON structure to understand its shape.

   Returns:
     {:root-type :array/:object/:primitive
      :sample-size int
      :fields [{:name :type :sample-values :nullable?}...]
      :nesting-depth int
      :has-arrays? bool
      :has-nested-objects? bool}"
  [json-data]
  (cond
    ;; Array of objects (most common for entity data)
    (and (vector? json-data) (map? (first json-data)))
    (let [sample (take 5 json-data)
          all-keys (distinct (mapcat keys sample))
          field-analysis (for [k all-keys]
                           (let [values (map #(get % k) sample)
                                 non-nil (remove nil? values)
                                 sample-val (first non-nil)]
                             {:name (name k)
                              :type (cond
                                      (nil? sample-val) :unknown
                                      (string? sample-val) :string
                                      (number? sample-val) :number
                                      (boolean? sample-val) :boolean
                                      (map? sample-val) :object
                                      (vector? sample-val) :array
                                      :else :unknown)
                              :sample-values (vec (take 3 non-nil))
                              :nullable? (some nil? values)}))]
      {:root-type :array
       :element-type :object
       :sample-size (count sample)
       :total-count (count json-data)
       :fields (vec field-analysis)
       :nesting-depth 1
       :has-arrays? (some #(= :array (:type %)) field-analysis)
       :has-nested-objects? (some #(= :object (:type %)) field-analysis)})

    ;; Single object with nested structure
    (map? json-data)
    (let [fields (for [[k v] json-data]
                   {:name (name k)
                    :type (cond
                            (string? v) :string
                            (number? v) :number
                            (boolean? v) :boolean
                            (map? v) :object
                            (vector? v) :array
                            :else :unknown)
                    :sample-values [v]
                    :nullable? false})]
      {:root-type :object
       :element-type nil
       :sample-size 1
       :total-count 1
       :fields (vec fields)
       :nesting-depth (if (some #(#{:object :array} (:type %)) fields) 2 1)
       :has-arrays? (some #(= :array (:type %)) fields)
       :has-nested-objects? (some #(= :object (:type %)) fields)})

    ;; Array of primitives
    (vector? json-data)
    {:root-type :array
     :element-type :primitive
     :sample-size (min 5 (count json-data))
     :total-count (count json-data)
     :fields []
     :nesting-depth 1
     :has-arrays? false
     :has-nested-objects? false}

    ;; Single primitive
    :else
    {:root-type :primitive
     :element-type nil
     :sample-size 1
     :total-count 1
     :fields []
     :nesting-depth 0
     :has-arrays? false
     :has-nested-objects? false}))

(defn format-structure-for-llm
  "Format JSON structure analysis for LLM consumption."
  [structure json-data]
  (let [sample (if (vector? json-data)
                 (take 3 json-data)
                 json-data)]
    (str "JSON Structure Analysis:\n"
         "- Root type: " (name (:root-type structure)) "\n"
         "- Total records: " (:total-count structure) "\n"
         "- Fields detected: " (count (:fields structure)) "\n"
         "\nField Details:\n"
         (str/join "\n" (for [{:keys [name type sample-values]} (:fields structure)]
                          (str "  - " name " (" (clojure.core/name type) "): "
                               (pr-str (take 2 sample-values)))))
         "\n\nSample Data:\n"
         (with-out-str (clojure.pprint/pprint sample)))))

;; =============================================================================
;; Code Executor Functions
;; =============================================================================

(defn parse-json-fn
  "Phase 1: Parse JSON and analyze structure."
  [{:keys [inputs]}]
  (let [json-data (or (:json-data inputs)
                      (when-let [content (:content inputs)]
                        (json/read-str content :key-fn keyword))
                      (when-let [path (:json-path inputs)]
                        (json/read-str (slurp path) :key-fn keyword)))
        structure (analyze-json-structure json-data)]
    {:json-data json-data
     :structure structure
     :structure-summary (format-structure-for-llm structure json-data)}))

(defn extract-concepts-from-analysis-fn
  "Phase 3: Extract concepts from LLM analysis."
  [{:keys [inputs]}]
  (let [entity-types (or (:entity-types inputs) [])
        base-uri (or (:base-uri inputs) "http://json.ontology/")
        json-data (:json-data inputs)

        ;; Create concepts from discovered entity types
        concepts (if (seq entity-types)
                   (mapv (fn [et]
                           {:uri (str base-uri (str/replace (:name et) #"\s+" "_"))
                            :label (:name et)
                            :definition (or (:definition et) "")
                            :entity-type (or (:type et) "Entity")
                            :confidence (or (:confidence et) 0.8)
                            :source-fields (:source-fields et)})
                         entity-types)
                   ;; Fallback: create concept from structure if LLM didn't return types
                   [{:uri (str base-uri "Entity")
                     :label "Entity"
                     :definition "Entity extracted from JSON"
                     :entity-type "Entity"
                     :confidence 0.5}])]
    {:concepts concepts}))

(defn deduplicate-concepts-fn
  "Phase 4: Remove duplicate concepts using label similarity."
  [{:keys [inputs]}]
  (let [concepts (or (:concepts inputs) [])
        seen (atom #{})
        unique (reduce (fn [acc c]
                         (let [label-key (str/lower-case (:label c))]
                           (if (contains? @seen label-key)
                             acc
                             (do
                               (swap! seen conj label-key)
                               (conj acc c)))))
                       []
                       concepts)]
    {:concepts unique
     :dedup-stats {:before (count concepts)
                   :after (count unique)
                   :removed (- (count concepts) (count unique))}}))

(defn build-tbox-fn
  "Phase 8: Build T-Box (OWL classes and properties)."
  [{:keys [inputs]}]
  (let [concepts (or (:concepts inputs) [])
        relationships (or (:relationships inputs) [])
        base-uri (or (:base-uri inputs) "http://json.ontology/")

        ;; Build classes from concepts
        classes (mapv (fn [c]
                        {:uri (:uri c)
                         :label (:label c)
                         :definition (:definition c)})
                      concepts)

        ;; Build object properties from relationships
        object-props (mapv (fn [r]
                             {:uri (str base-uri (:predicate r))
                              :label (:predicate r)
                              :domain (:subject r)
                              :range (:object r)})
                           (filter :predicate relationships))

        ;; Build datatype properties from concept fields
        datatype-props (vec (distinct
                              (for [c concepts
                                    f (:source-fields c)
                                    :when (string? f)]
                                {:uri (str base-uri f)
                                 :label f
                                 :domain (:uri c)})))]
    {:tbox {:classes classes
            :object-properties object-props
            :datatype-properties datatype-props}}))

(defn build-abox-fn
  "Phase 9: Build A-Box (instances from JSON data)."
  [{:keys [inputs]}]
  (let [json-data (:json-data inputs)
        concepts (or (:concepts inputs) [])
        base-uri (or (:base-uri inputs) "http://json.ontology/")

        ;; For array of objects, create instances
        instances (when (and (vector? json-data) (map? (first json-data)))
                    (let [entity-type (or (:entity-type (first concepts)) "Entity")]
                      (map-indexed
                        (fn [idx item]
                          {:uri (str base-uri entity-type "/" idx)
                           :type entity-type
                           :properties item})
                        json-data)))]
    {:abox (vec instances)}))

(defn serialize-to-owl-fn
  "Phase 10: Serialize to OWL Turtle format."
  [{:keys [inputs]}]
  (let [tbox (:tbox inputs)
        abox (:abox inputs)
        base-uri (or (:base-uri inputs) "http://json.ontology/")

        ;; Build Turtle output
        prefixes (str "@prefix : <" base-uri "> .\n"
                      "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                      "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                      "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n\n")

        ;; Classes
        class-ttl (str/join "\n"
                    (for [c (:classes tbox)]
                      (str "<" (:uri c) "> a owl:Class ;\n"
                           "    rdfs:label \"" (:label c) "\" .\n")))

        ;; Object properties
        obj-prop-ttl (str/join "\n"
                       (for [p (:object-properties tbox)]
                         (str "<" (:uri p) "> a owl:ObjectProperty ;\n"
                              "    rdfs:label \"" (:label p) "\" .\n")))

        ;; Datatype properties
        data-prop-ttl (str/join "\n"
                        (for [p (:datatype-properties tbox)]
                          (str "<" (:uri p) "> a owl:DatatypeProperty ;\n"
                               "    rdfs:label \"" (:label p) "\" .\n")))]

    {:owl-output (str prefixes
                      "# Classes\n" class-ttl "\n"
                      "# Object Properties\n" obj-prop-ttl "\n"
                      "# Datatype Properties\n" data-prop-ttl)}))

;; =============================================================================
;; ORC Sheet Pipeline Definition
;; =============================================================================

(def json-to-ontology-pipeline
  "ORC sheet for JSON-to-ontology extraction.

   Pipeline phases:
   1. Parse Structure (code) - Analyze JSON arrays, objects, nesting
   2. Domain Analysis (LLM) - Identify domain and purpose
   3. Entity Type Discovery (LLM) - Infer entity types from structure
   4. Deduplication (code) - Remove duplicate concepts
   5. Definition Enrichment (LLM) - Generate formal definitions
   6. Relationship Discovery (LLM) - Find relationships from structure
   7. Quality Validation (LLM) - Check for ambiguity
   8-10. TBox/ABox construction and serialization (code)"

  (sheet/workflow "json-to-ontology"

    ;; =========================================================================
    ;; BLACKBOARD SCHEMA
    ;; =========================================================================
    (sheet/blackboard
      {;; === Inputs ===
       :json-data :any
       :json-path [:string {:optional true}]
       :content [:string {:optional true}]
       :base-uri [:string {:description "Ontology namespace URI"}]
       :domain [:string {:optional true :description "Optional domain hint"}]
       :existing-concepts [:vector {:optional true} :any]

       ;; === Phase 1: Structure Analysis ===
       :structure [:map {:description "JSON structure analysis"}]
       :structure-summary [:string {:description "Human-readable structure summary for LLM"}]

       ;; === Phase 2: Domain Analysis (LLM) ===
       :domain-reasoning [:string {:description "Step-by-step reasoning for domain analysis"}]
       :domain-description [:string {:description "Description of the domain"}]
       :naming-patterns [:string {:description "Detected naming conventions"}]

       ;; === Phase 3: Entity Type Discovery (LLM) ===
       :entity-reasoning [:string {:description "Step-by-step reasoning for entity discovery"}]
       :entity-types [:vector {:description "Discovered entity types"}
                      [:map
                       [:name :string]
                       [:type :string]
                       [:source-fields [:vector :string]]
                       [:definition :string]
                       [:confidence :double]]]

       ;; === Phase 4: Concepts ===
       :concepts [:vector {:description "Extracted concepts"}
                  [:map
                   [:uri :string]
                   [:label :string]
                   [:definition :string]
                   [:entity-type :string]
                   [:confidence :double]]]
       :dedup-stats [:map [:before :int] [:after :int] [:removed :int]]

       ;; === Phase 5: Definition Enrichment (LLM) ===
       :definition-reasoning [:string {:description "Reasoning for definitions"}]
       :enriched-definitions [:map-of :string :any]

       ;; === Phase 6: Relationship Discovery (LLM) ===
       :relationship-reasoning [:string {:description "Reasoning for relationships"}]
       :relationships [:vector {:description "Discovered relationships"}
                       [:map
                        [:subject :string]
                        [:predicate :string]
                        [:object :string]
                        [:evidence :string]
                        [:confidence :double]]]

       ;; === Phase 7: Quality Validation (LLM) ===
       :validation-reasoning [:string {:description "Reasoning for validation"}]
       :quality [:string {:description "Quality assessment: good/needs-work"}]
       :issues [:vector :string]
       :suggestions [:vector :string]

       ;; === Phase 8-10: TBox/ABox ===
       :tbox [:map {:description "Ontology schema"}
              [:classes [:vector :any]]
              [:object-properties [:vector :any]]
              [:datatype-properties [:vector :any]]]
       :abox [:vector {:description "Instances"} :any]
       :owl-output [:string {:description "OWL Turtle serialization"}]})

    ;; =========================================================================
    ;; MAIN PIPELINE SEQUENCE
    ;; =========================================================================
    (sheet/sequence "json-main-pipeline"
      ;; =======================================================================
      ;; PHASE 1: PARSE JSON STRUCTURE
      ;; =======================================================================
      (sheet/code "parse-json"
        :fn "ai.obney.orc.ontology.sheets.json-ontology/parse-json-fn"
        :reads [:json-data :json-path :content]
        :writes [:json-data :structure :structure-summary])

      ;; =======================================================================
      ;; PHASE 2: DOMAIN ANALYSIS (LLM)
      ;; =======================================================================
      (sheet/llm "analyze-json-domain"
        :model "google/gemini-2.5-flash"
        :instruction "Analyze this JSON structure to understand its domain and purpose.

Based on the field names, values, and structure:
1. Identify the main domain (e.g., 'Human Resources', 'E-commerce', 'Healthcare')
2. Describe what this JSON data represents
3. Note any naming conventions or patterns (including cryptic/abbreviated field names)

Even if field names are cryptic (like 'n' for name, 'a' for age), infer their meaning from sample values."
        :reads [:structure-summary]
        :writes [:domain-reasoning :domain :domain-description :naming-patterns]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; =======================================================================
      ;; PHASE 3: ENTITY TYPE DISCOVERY (LLM)
      ;; =======================================================================
      (sheet/llm "discover-entity-types"
        :model "google/gemini-2.5-flash"
        :instruction "Identify entity types represented in this JSON.

For each entity type, provide:
1. A meaningful name (PascalCase) - even if the JSON fields are cryptic
2. The type category (Person, Organization, Event, Product, Location, Concept, etc.)
3. Which JSON fields belong to this entity
4. A brief definition based on the data

Return as JSON array:
[{\"name\": \"Person\", \"type\": \"Person\", \"source-fields\": [\"n\", \"a\"], \"definition\": \"An individual with name and age\", \"confidence\": 0.9}]

For cryptic fields, explain your reasoning (e.g., 'n' appears to be name based on string values like 'John Smith')."
        :reads [:structure-summary :domain :domain-description :naming-patterns]
        :writes [:entity-reasoning :entity-types]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (sheet/code "extract-concepts"
        :fn "ai.obney.orc.ontology.sheets.json-ontology/extract-concepts-from-analysis-fn"
        :reads [:entity-types :base-uri :json-data]
        :writes [:concepts])

      ;; =======================================================================
      ;; PHASE 4: DEDUPLICATION
      ;; =======================================================================
      (sheet/code "deduplicate-concepts"
        :fn "ai.obney.orc.ontology.sheets.json-ontology/deduplicate-concepts-fn"
        :reads [:concepts]
        :writes [:concepts :dedup-stats])

      ;; =======================================================================
      ;; PHASE 5: DEFINITION ENRICHMENT (LLM)
      ;; =======================================================================
      (sheet/llm "enrich-definitions"
        :model "google/gemini-2.5-flash"
        :instruction "For each entity type, generate a formal 2-3 sentence definition.

Include:
1. What the entity represents
2. Key properties/characteristics
3. How it relates to other entities in the domain

Return as JSON object with entity names as keys:
{\"Person\": {\"definition\": \"...\", \"scope-note\": \"...\"}}"
        :reads [:concepts :domain :domain-description]
        :writes [:definition-reasoning :enriched-definitions]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; =======================================================================
      ;; PHASE 6: RELATIONSHIP DISCOVERY (LLM)
      ;; =======================================================================
      (sheet/llm "discover-relationships"
        :model "google/gemini-2.5-flash"
        :instruction "Discover relationships between entities in this JSON.

Look for:
1. Nesting patterns (parent contains child)
2. ID references (field_id pointing to another entity)
3. Semantic relationships based on domain knowledge
4. Implicit relationships from field names

Return as JSON array:
[{\"subject\": \"Employee\", \"predicate\": \"worksFor\", \"object\": \"Company\", \"evidence\": \"...\", \"confidence\": 0.8}]"
        :reads [:structure-summary :concepts :domain]
        :writes [:relationship-reasoning :relationships]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; =======================================================================
      ;; PHASE 7: QUALITY VALIDATION (LLM)
      ;; =======================================================================
      (sheet/llm "validate-quality"
        :model "google/gemini-2.5-flash"
        :instruction "Validate the extracted ontology for quality issues.

Check for:
1. Ambiguous entity names
2. Overlapping concepts that should be merged
3. Missing definitions
4. Inconsistent naming conventions

Return as JSON:
{\"quality\": \"good\"/\"needs-work\", \"issues\": [...], \"suggestions\": [...]}"
        :reads [:concepts :relationships :domain]
        :writes [:validation-reasoning :quality :issues :suggestions]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; =======================================================================
      ;; PHASE 8: TBOX CONSTRUCTION
      ;; =======================================================================
      (sheet/code "build-tbox"
        :fn "ai.obney.orc.ontology.sheets.json-ontology/build-tbox-fn"
        :reads [:concepts :relationships :base-uri]
        :writes [:tbox])

      ;; =======================================================================
      ;; PHASE 9: ABOX CONSTRUCTION
      ;; =======================================================================
      (sheet/code "build-abox"
        :fn "ai.obney.orc.ontology.sheets.json-ontology/build-abox-fn"
        :reads [:json-data :concepts :base-uri]
        :writes [:abox])

      ;; =======================================================================
      ;; PHASE 10: SERIALIZATION
      ;; =======================================================================
      (sheet/code "serialize-to-owl"
        :fn "ai.obney.orc.ontology.sheets.json-ontology/serialize-to-owl-fn"
        :reads [:tbox :abox :base-uri]
        :writes [:owl-output]))))

;; =============================================================================
;; API Functions
;; =============================================================================

(def json-ontology-sheet-id #uuid "c3d4e5f6-a7b8-9012-cdef-345678901234")

(defn build-json-ontology-pipeline!
  "Build the JSON-to-ontology pipeline workflow. Returns sheet-id."
  [context]
  (sheet/build-workflow! context json-to-ontology-pipeline))

(defn run-json-to-ontology
  "Run the JSON-to-ontology pipeline with given inputs.

   Args:
     context: The ORC context
     sheet-id: The built sheet ID
     opts: Map with keys:
       :json-data - Parsed JSON data (vector/map)
       :json-path - Path to JSON file (alternative to :json-data)
       :content - Raw JSON string (alternative to :json-data)
       :base-uri - Ontology namespace URI
       :domain - Optional domain hint for LLM
       :existing-concepts - For evolution/deduplication

   Returns:
     {:status :success/:failed
      :domain - Detected domain
      :domain-description - Domain description
      :concepts - Extracted concepts
      :relationships - Discovered relationships
      :tbox - Ontology schema (classes, properties)
      :abox - Instances
      :owl-output - OWL Turtle serialization}"
  [context sheet-id {:keys [json-data json-path content base-uri domain existing-concepts]
                     :or {base-uri "http://json.ontology/"}}]
  (let [result (sheet/execute context sheet-id
                 {:json-data json-data
                  :json-path json-path
                  :content content
                  :base-uri base-uri
                  :domain domain
                  :existing-concepts existing-concepts})]
    (if (= :success (:status result))
      {:status :success
       :domain (get-in result [:outputs :domain])
       :domain-description (get-in result [:outputs :domain-description])
       :concepts (get-in result [:outputs :concepts])
       :relationships (get-in result [:outputs :relationships])
       :tbox (get-in result [:outputs :tbox])
       :abox (get-in result [:outputs :abox])
       :owl-output (get-in result [:outputs :owl-output])
       :quality (get-in result [:outputs :quality])
       :validation-issues (get-in result [:outputs :issues])}
      {:status :failed
       :error (:error result)})))

;; =============================================================================
;; Sample Data for Testing
;; =============================================================================

(def sample-json-data
  "Sample JSON for testing."
  [{"name" "John Smith"
    "age" 30
    "department" "Engineering"
    "role" "Senior Developer"}
   {"name" "Jane Doe"
    "age" 28
    "department" "Design"
    "role" "UX Designer"}])

(comment
  ;; Example usage
  (require '[ai.obney.orc.ontology.test-helpers :as h])

  (let [ctx (h/create-test-context)
        sheet-id (build-json-ontology-pipeline! ctx)
        result (run-json-to-ontology ctx sheet-id
                 {:json-data sample-json-data
                  :base-uri "http://example.org/"})]
    (println "Status:" (:status result))
    (println "Domain:" (:domain result))
    (println "Concepts:" (count (:concepts result)))
    (h/stop-context ctx))

  ,)
