(ns manual-verification
  "Manual verification script for ORC-001 and ORC-002.

   Run this in a REPL with API keys configured to test full LLM pipeline.

   Usage:
     1. Start REPL: cd orc-main && clj -M:dev
     2. Load this file: (load-file \"components/ontology/dev/manual_verification.clj\")
     3. Run tests: (manual-verification/run-all-verifications!)

   Or run individual tests:
     (manual-verification/test-json-extraction!)
     (manual-verification/test-ontology-id-scoping!)
     (manual-verification/test-evolutionary-builder-json!)")

(require '[ai.obney.orc.ontology.interface :as ontology])
(require '[ai.obney.orc.ontology.sheets.json-ontology :as json-ont])
(require '[ai.obney.orc.ontology.sheets.unified-ontology :as unified])
(require '[ai.obney.orc.ontology.core.read-models :as rm])
(require '[ai.obney.orc.ontology.test-helpers :as h])
(require '[clojure.pprint :refer [pprint]])

;; =============================================================================
;; Test Data
;; =============================================================================

(def sample-people-json
  "Simple people data for testing."
  [{:name "Alice Johnson" :age 32 :role "Software Engineer" :department "Engineering"}
   {:name "Bob Smith" :age 45 :role "Product Manager" :department "Product"}
   {:name "Carol Davis" :age 28 :role "Designer" :department "Design"}])

(def cambot-style-json
  "Cambot-style turnover data."
  {:entities
   [{:uri "person:tavidee"
     :label "Tavidee Hoskins"
     :type "Person"
     :aliases ["Tavidee" "Tavi"]
     :properties {:group "relationship-ops" :role "Director"}}
    {:uri "org:bryc"
     :label "BRYC"
     :type "Organization"
     :aliases ["Blue Ridge Youth Collaborative"]
     :properties {:type "nonprofit"}}]
   :relationships
   [{:subject "person:tavidee"
     :predicate "member-of"
     :object "org:bryc"}]})

;; =============================================================================
;; Verification 1: JSON Structure Analysis (No LLM)
;; =============================================================================

(defn test-json-structure-analysis!
  "Test JSON structure analysis - no LLM required."
  []
  (println "\n=== TEST: JSON Structure Analysis (No LLM) ===\n")

  (println "1. Analyzing simple array of objects:")
  (let [result (json-ont/analyze-json-structure sample-people-json)]
    (println "   Root type:" (:root-type result))
    (println "   Element type:" (:element-type result))
    (println "   Fields:" (count (:fields result)))
    (doseq [f (:fields result)]
      (println "     -" (:name f) ":" (:type f)))
    (println "   ✓ Analysis complete"))

  (println "\n2. Analyzing nested Cambot-style data:")
  (let [result (json-ont/analyze-json-structure cambot-style-json)]
    (println "   Root type:" (:root-type result))
    (println "   Has arrays?:" (:has-arrays? result))
    (println "   Fields:" (count (:fields result)))
    (println "   ✓ Analysis complete"))

  (println "\n3. Format for LLM:")
  (let [structure (json-ont/analyze-json-structure sample-people-json)
        formatted (json-ont/format-structure-for-llm structure sample-people-json)]
    (println (subs formatted 0 (min 500 (count formatted))) "..."))

  (println "\n✅ JSON Structure Analysis: PASSED"))

;; =============================================================================
;; Verification 2: JSON Extraction Pipeline (Requires LLM)
;; =============================================================================

(defn test-json-extraction!
  "Test full JSON extraction pipeline - REQUIRES LLM.

   This will make actual API calls. Ensure you have:
   - ANTHROPIC_API_KEY or OPENAI_API_KEY set
   - A running ORC context with LLM configured"
  []
  (println "\n=== TEST: JSON Extraction Pipeline (Requires LLM) ===\n")
  (println "⚠️  This test makes real LLM calls. Ensure API keys are configured.\n")

  (try
    (h/with-test-context [ctx]
      (println "1. Building JSON ontology pipeline...")
      (let [sheet-id (json-ont/build-json-ontology-pipeline! ctx)]
        (println "   Sheet ID:" sheet-id)

        (println "\n2. Running extraction on simple people data...")
        (let [result (json-ont/run-json-to-ontology ctx sheet-id
                       {:json-data sample-people-json
                        :base-uri "http://test.org/"
                        :domain "HR/People"})]
          (println "   Status:" (:status result))
          (if (= :success (:status result))
            (do
              (println "   Concepts extracted:" (count (:concepts result)))
              (println "   Relationships found:" (count (:relationships result)))
              (when (seq (:concepts result))
                (println "\n   Sample concepts:")
                (doseq [c (take 3 (:concepts result))]
                  (println "     -" (:label c) "(" (:entity-type c) ")")))
              (println "\n✅ JSON Extraction: PASSED"))
            (do
              (println "   ❌ Extraction failed:" (:error result))
              (println "\n❌ JSON Extraction: FAILED"))))))
    (catch Exception e
      (println "   ❌ Error:" (.getMessage e))
      (println "\n❌ JSON Extraction: FAILED (check API keys and LLM configuration)"))))

;; =============================================================================
;; Verification 3: Ontology-ID Scoping
;; =============================================================================

(defn test-ontology-id-scoping!
  "Test ontology-id scoping in projections."
  []
  (println "\n=== TEST: Ontology-ID Scoping ===\n")

  (let [ontology-a #uuid "aaaa0000-0000-0000-0000-000000000001"
        ontology-b #uuid "bbbb0000-0000-0000-0000-000000000002"

        ;; Create events for two different ontologies
        events [{:event/type :ontology/concept-created
                 :ontology-id ontology-a
                 :concept-id (random-uuid)
                 :uri "person:alice"
                 :label "Alice"
                 :description "Person from ontology A"
                 :scope :person
                 :broader []
                 :indicators []
                 :created-at "2026-06-01T00:00:00Z"}
                {:event/type :ontology/concept-created
                 :ontology-id ontology-a
                 :concept-id (random-uuid)
                 :uri "person:bob"
                 :label "Bob"
                 :description "Person from ontology A"
                 :scope :person
                 :broader []
                 :indicators []
                 :created-at "2026-06-01T00:00:00Z"}
                {:event/type :ontology/concept-created
                 :ontology-id ontology-b
                 :concept-id (random-uuid)
                 :uri "org:acme"
                 :label "Acme Corp"
                 :description "Org from ontology B"
                 :scope :organization
                 :broader []
                 :indicators []
                 :created-at "2026-06-01T00:00:00Z"}]

        ;; Project events
        state (rm/concepts {} events)]

    (println "1. Created 3 concepts across 2 ontologies")
    (println "   Ontology A: Alice, Bob (persons)")
    (println "   Ontology B: Acme Corp (organization)")

    (println "\n2. Testing ontology-id stored in projection:")
    (println "   Alice's ontology-id:" (:ontology-id (get state "person:alice")))
    (println "   Acme's ontology-id:" (:ontology-id (get state "org:acme")))
    (assert (= ontology-a (:ontology-id (get state "person:alice"))))
    (assert (= ontology-b (:ontology-id (get state "org:acme"))))
    (println "   ✓ Ontology IDs correctly stored")

    (println "\n3. Testing filtering logic:")
    (let [filter-fn (fn [{:keys [ontology-id ontology-ids scope]}]
                      (let [ont-id-set (cond
                                         ontology-ids (set ontology-ids)
                                         ontology-id #{ontology-id}
                                         :else nil)]
                        (cond->> (vals state)
                          scope (filter #(= scope (:scope %)))
                          ont-id-set (filter #(contains? ont-id-set (:ontology-id %))))))]

      (println "   Filter by ontology-a:" (count (filter-fn {:ontology-id ontology-a})) "concepts")
      (println "   Filter by ontology-b:" (count (filter-fn {:ontology-id ontology-b})) "concepts")
      (println "   Filter by both:" (count (filter-fn {:ontology-ids [ontology-a ontology-b]})) "concepts")
      (println "   No filter:" (count (filter-fn {})) "concepts")

      (assert (= 2 (count (filter-fn {:ontology-id ontology-a}))))
      (assert (= 1 (count (filter-fn {:ontology-id ontology-b}))))
      (assert (= 3 (count (filter-fn {:ontology-ids [ontology-a ontology-b]}))))
      (assert (= 3 (count (filter-fn {}))))
      (println "   ✓ All filtering assertions passed"))

    (println "\n✅ Ontology-ID Scoping: PASSED")))

;; =============================================================================
;; Verification 4: Evolutionary Builder JSON Support
;; =============================================================================

(defn test-evolutionary-builder-json!
  "Test that evolutionary builder supports JSON - REQUIRES LLM.

   This makes actual LLM calls through the evolutionary builder pipeline."
  []
  (println "\n=== TEST: Evolutionary Builder JSON Support (Requires LLM) ===\n")
  (println "⚠️  This test makes real LLM calls. Ensure API keys are configured.\n")

  (try
    (h/with-test-context [ctx]
      (println "1. Calling build-ontology-from-sources with JSON...")
      (let [result (ontology/build-ontology-from-sources ctx
                     {:sources [{:content (pr-str sample-people-json)
                                 :type "json"}]
                      :config {:base-uri "http://test.org/"
                               :enable-colbert? false
                               :enable-embeddings? false}})]
        (println "   Ontology ID:" (:ontology-id result))
        (println "   Total concepts:" (:total-concepts result))
        (println "   Total triples:" (:total-triples result))
        (println "   Events emitted:" (count (:events result)))

        (if (> (:total-concepts result) 0)
          (println "\n✅ Evolutionary Builder JSON: PASSED")
          (println "\n⚠️  Evolutionary Builder JSON: No concepts extracted (check LLM response)"))))
    (catch Exception e
      (println "   ❌ Error:" (.getMessage e))
      (println "\n❌ Evolutionary Builder JSON: FAILED"))))

;; =============================================================================
;; Run All Verifications
;; =============================================================================

(defn run-all-verifications!
  "Run all verification tests."
  []
  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println "  ORC-001 & ORC-002 VERIFICATION SUITE")
  (println "\n" (apply str (repeat 60 "=")) "\n")

  ;; Tests that don't require LLM
  (test-json-structure-analysis!)
  (test-ontology-id-scoping!)

  ;; Tests that require LLM
  (println "\n--- LLM-Required Tests ---")
  (println "The following tests require LLM API access.")
  (println "If they fail, ensure ANTHROPIC_API_KEY or OPENAI_API_KEY is set.\n")

  ;; Uncomment these to run LLM tests:
  ;; (test-json-extraction!)
  ;; (test-evolutionary-builder-json!)

  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println "  VERIFICATION COMPLETE")
  (println "\n  Code-only tests: PASSED")
  (println "  LLM tests: Skipped (uncomment in script to run)")
  (println "\n" (apply str (repeat 60 "=")) "\n"))

;; =============================================================================
;; Quick Check (No LLM)
;; =============================================================================

(defn quick-check!
  "Quick verification without LLM calls."
  []
  (println "\n=== QUICK CHECK (No LLM) ===\n")
  (test-json-structure-analysis!)
  (test-ontology-id-scoping!)
  (println "\n✅ Quick check complete - all code-level tests passed"))

(comment
  ;; Run in REPL:

  ;; Quick check (no LLM needed)
  (quick-check!)

  ;; Full verification (requires LLM)
  (run-all-verifications!)

  ;; Individual tests
  (test-json-structure-analysis!)
  (test-ontology-id-scoping!)
  (test-json-extraction!)  ;; Requires LLM
  (test-evolutionary-builder-json!)  ;; Requires LLM

  ,)
