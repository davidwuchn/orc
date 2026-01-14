(ns bryc-demo
  "BRYC Comprehensive Recommendations Workflow - ORC Demo Implementation

   Re-implements the BRYC Python recommendations workflow as an ORC behavior tree demo.
   Generates personalized college/program recommendations AND scholarships for Louisiana
   high school students through parallel processing pipelines.

   COMPREHENSIVE FEATURES:
   - 1,599 Louisiana programs loaded from real CSV data
   - 8 sample student profiles from real JSON files
   - 8 sample scholarships for demo purposes
   - Parallel execution: Programs + Scholarships tracks run simultaneously

   PROGRAMS TRACK (6 phases):
   1. Student Analysis (LLM) - Extract requirements, interests, preferences
   2. Search Orchestration (code) - Keyword matching from strategies
   3. Portfolio Optimization (code) - Select diverse programs
   4. Multi-Dimensional Scoring (parallel LLM) - Academic, career, preference, financial, outcome, pathway
   5. Personalization (LLM) - Generate tailored content per program
   6. Institution Grouping (code + LLM) - Group by institution with summaries

   SCHOLARSHIPS TRACK (5 phases):
   1. Eligibility Extraction (LLM) - Parse student criteria
   2. Hard Filtering (code) - GPA, Louisiana eligibility, citizenship
   3. Relevance Scoring (code) - Career alignment, demographics, amount, deadline
   4. Categorization (code) - Urgency tiers, complexity classification
   5. Verification (LLM) - Demographic enforcement, personalized explanations

   ADDITIONAL FEATURES:
   - TOPS Coverage: Institution-specific lookup tables for 2-year and 4-year schools
   - Color Profile System: ACT-based degree level weighting (green/blue/purple/yellow)
   - HBCU Detection: Flags when 20%+ of recommendations are HBCUs
   - Financial Enrichment: TOPS-first stacking, excess Pell calculation
   - Louisiana Benchmarks: Cost of living ($48,425), median salary ($52,547)

   Usage:
     ;; Start service first via repl_stuff.clj, then:
     (def sheet-id (build-demo! rs/context))
     (run-demo rs/context sheet-id \"reagan\")  ; Returns comprehensive results
     (run-demo rs/context sheet-id \"aja\")

   Available Students: reagan, aja, claude, maya, dylan, john, maddi, asha"
  (:require [ai.obney.workshop.sheet-service.interface :as sheet]
            [repl-stuff :as rs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Data Loading Functions
;; =============================================================================

(defn parse-double-safe
  "Safely parse a double, returning 0.0 on failure."
  [s]
  (try
    (if (or (nil? s) (str/blank? s) (= "NA" s) (= "null" s))
      0.0
      (Double/parseDouble s))
    (catch Exception _ 0.0)))

(defn parse-csv-line
  "Parse a single CSV line, handling quoted fields with commas."
  [line]
  (let [result (java.util.ArrayList.)
        sb (StringBuilder.)
        in-quotes (atom false)
        chars (seq line)]
    (doseq [c chars]
      (cond
        (and (= c \") (not @in-quotes))
        (reset! in-quotes true)

        (and (= c \") @in-quotes)
        (reset! in-quotes false)

        (and (= c \,) (not @in-quotes))
        (do
          (.add result (.toString sb))
          (.setLength sb 0))

        :else
        (.append sb c)))
    (.add result (.toString sb))
    (vec result)))

(defn load-programs-csv
  "Load the 1,599 Louisiana programs from CSV file."
  []
  (let [csv-path "/Users/obneyai/Documents/code/bryc-workshop/components/recommendations/resources/recommendations/louisiana_programs_with_embeddings.csv"
        lines (str/split-lines (slurp csv-path))
        headers (parse-csv-line (first lines))
        data-rows (rest lines)]
    (->> data-rows
         (map parse-csv-line)
         (map (fn [row]
                (let [m (zipmap headers row)]
                  {:program-id (str (get m "institution-name") "-" (get m "program-title"))
                   :program-title (get m "program-title")
                   :institution (get m "institution-name")
                   :sector (get m "sector")
                   :award-level (get m "award_level")
                   :cip-code (get m "cip-code")
                   :tuition (parse-double-safe (get m "in-state-tuition"))
                   :earnings-y1 (parse-double-safe (get m "earnings_y1_median"))
                   :earnings-y5 (parse-double-safe (get m "earnings_y5_median"))
                   :acceptance-rate (parse-double-safe (get m "acceptance_rate"))
                   :act-25th (parse-double-safe (get m "act_composite_25th"))
                   :act-75th (parse-double-safe (get m "act_composite_75th"))
                   :address (get m "address")
                   :city (get m "city")
                   :online-availability (get m "online-availability")})))
         vec)))

;; Delay to load programs only once
(def programs-data (delay (load-programs-csv)))

(defn load-student-json
  "Load a sample student profile by name (e.g., 'reagan', 'aja', 'claude')."
  [student-name]
  (let [path (str "/Users/obneyai/Documents/code/bryc-workshop/python/sample_students/" student-name ".json")]
    (json/parse-string (slurp path) true)))

;; Available students: reagan, aja, claude, maya, dylan, john, maddi, asha

;; =============================================================================
;; TOPS Coverage Tables (2025-26)
;; Source: Louisiana TOPS Award Calculations PDFs
;; =============================================================================

(def tops-2year-coverage
  "TOPS coverage amounts for 2-year institutions (annual)"
  {:tops-tech
   {"Baton Rouge Community College" 3086.08
    "Bossier Parish Community College" 3214.15
    "Central Louisiana Technical Community College" 3214.15
    "Delgado Community College" 3214.15
    "Fletcher Technical Community College" 3214.15
    "Louisiana Delta Community College" 3214.15
    "Louisiana State University-Eunice" 2710.64
    "Northshore Technical Community College" 3214.15
    "Northwest Louisiana Technical Community College" 3214.15
    "Nunez Community College" 3214.15
    "River Parishes Community College" 3214.15
    "South Louisiana Community College" 3214.15
    "SOWELA Technical Community College" 3214.15
    "Southern University at Shreveport" 2618.00}

   :tops-opportunity
   {"Baton Rouge Community College" 3086.08
    "Bossier Parish Community College" 3214.15
    "Central Louisiana Technical Community College" 3214.15
    "Delgado Community College" 3214.15
    "Fletcher Technical Community College" 3214.15
    "Louisiana Delta Community College" 3214.15
    "Louisiana State University-Eunice" 2710.64
    "Northshore Technical Community College" 3214.15
    "Northwest Louisiana Technical Community College" 3214.15
    "Nunez Community College" 3214.15
    "River Parishes Community College" 3214.15
    "South Louisiana Community College" 3214.15
    "SOWELA Technical Community College" 3214.15
    "Southern University at Shreveport" 2618.00}

   :tops-performance
   {"Baton Rouge Community College" 3486.08
    "Bossier Parish Community College" 3614.15
    "Central Louisiana Technical Community College" 3614.15
    "Delgado Community College" 3614.15
    "Fletcher Technical Community College" 3614.15
    "Louisiana Delta Community College" 3614.15
    "Louisiana State University-Eunice" 3110.64
    "Northshore Technical Community College" 3614.15
    "Northwest Louisiana Technical Community College" 3614.15
    "Nunez Community College" 3614.15
    "River Parishes Community College" 3614.15
    "South Louisiana Community College" 3614.15
    "SOWELA Technical Community College" 3614.15
    "Southern University at Shreveport" 3018.00}

   :tops-honors
   {"Baton Rouge Community College" 3886.08
    "Bossier Parish Community College" 4014.15
    "Central Louisiana Technical Community College" 4014.15
    "Delgado Community College" 4014.15
    "Fletcher Technical Community College" 4014.15
    "Louisiana Delta Community College" 4014.15
    "Louisiana State University-Eunice" 3510.64
    "Northshore Technical Community College" 4014.15
    "Northwest Louisiana Technical Community College" 4014.15
    "Nunez Community College" 4014.15
    "River Parishes Community College" 4014.15
    "South Louisiana Community College" 4014.15
    "SOWELA Technical Community College" 4014.15
    "Southern University at Shreveport" 3418.00}})

(def tops-4year-coverage
  "TOPS coverage amounts for 4-year institutions (annual)"
  {:tops-opportunity
   {"Grambling State University" 5139.75
    "Louisiana State University-Alexandria" 4894.25
    "Louisiana State University and Agricultural & Mechanical College" 7462.98
    "Louisiana State University" 7462.98
    "Louisiana State University-Shreveport" 5553.00
    "Louisiana Tech University" 5553.00
    "McNeese State University" 5147.34
    "Nicholls State University" 4922.28
    "Northwestern State University of Louisiana" 5180.00
    "Southeastern Louisiana University" 5652.21
    "Southern University and A & M College" 4973.10
    "Southern University at New Orleans" 4236.21
    "University of Louisiana at Lafayette" 5406.96
    "University of Louisiana at Monroe" 5787.52
    "University of New Orleans" 6090.37
    ;; LAICU (Private)
    "Centenary College of Louisiana" 5718.00
    "Dillard University" 5718.00
    "Louisiana Christian University" 5718.00
    "Loyola University New Orleans" 5718.00
    "Xavier University of Louisiana" 5718.00}

   :tops-performance
   {"Grambling State University" 5539.75
    "Louisiana State University-Alexandria" 5294.25
    "Louisiana State University and Agricultural & Mechanical College" 7862.98
    "Louisiana State University-Shreveport" 5953.00
    "Louisiana Tech University" 5953.00
    "McNeese State University" 5547.34
    "Nicholls State University" 5322.28
    "Northwestern State University of Louisiana" 5580.00
    "Southeastern Louisiana University" 6052.21
    "Southern University and A & M College" 5373.10
    "Southern University at New Orleans" 4636.21
    "University of Louisiana at Lafayette" 5806.96
    "University of Louisiana at Monroe" 6187.52
    "University of New Orleans" 6490.37
    ;; LAICU
    "Centenary College of Louisiana" 6118.00
    "Dillard University" 6118.00
    "Xavier University of Louisiana" 6118.00}

   :tops-honors
   {"Grambling State University" 5939.75
    "Louisiana State University-Alexandria" 5694.25
    "Louisiana State University and Agricultural & Mechanical College" 8262.98
    "Louisiana State University-Shreveport" 6353.00
    "Louisiana Tech University" 6353.00
    "McNeese State University" 5947.34
    "Nicholls State University" 5722.28
    "Northwestern State University of Louisiana" 5980.00
    "Southeastern Louisiana University" 6452.21
    "Southern University and A & M College" 5773.10
    "Southern University at New Orleans" 5036.21
    "University of Louisiana at Lafayette" 6206.96
    "University of Louisiana at Monroe" 6587.52
    "University of New Orleans" 6890.37
    ;; LAICU
    "Centenary College of Louisiana" 6518.00
    "Dillard University" 6518.00
    "Xavier University of Louisiana" 6518.00}})

;; HBCU institutions in Louisiana
(def louisiana-hbcus
  #{"Southern University and A & M College"
    "Southern University at New Orleans"
    "Southern University at Shreveport"
    "Grambling State University"
    "Dillard University"
    "Xavier University of Louisiana"})

;; Louisiana economic benchmarks (2023-24)
(def la-cost-of-living 48425)
(def la-median-salary 52547)

;; =============================================================================
;; Color Profile System
;; Maps ACT-based profiles to degree level preferences
;; =============================================================================

(def color-profile-weights
  "Degree level preference weights by color profile.
   Higher weight = more appropriate for student's academic profile."
  {:super-green {:bachelors 1.0 :associates 0.25 :cert-1-2yr 0.15 :cert-lt-1yr 0.10 :apprenticeship 0.10}
   :green       {:bachelors 1.0 :associates 0.30 :cert-1-2yr 0.20 :cert-lt-1yr 0.15 :apprenticeship 0.15}
   :super-blue  {:bachelors 0.95 :associates 0.50 :cert-1-2yr 0.40 :cert-lt-1yr 0.35 :apprenticeship 0.35}
   :blue        {:bachelors 0.90 :associates 0.55 :cert-1-2yr 0.45 :cert-lt-1yr 0.40 :apprenticeship 0.40}
   :super-purple {:bachelors 0.70 :associates 0.90 :cert-1-2yr 0.85 :cert-lt-1yr 0.80 :apprenticeship 0.85}
   :purple      {:bachelors 0.55 :associates 0.95 :cert-1-2yr 0.95 :cert-lt-1yr 0.90 :apprenticeship 0.90}
   :super-yellow {:bachelors 0.20 :associates 0.95 :cert-1-2yr 1.0 :cert-lt-1yr 1.0 :apprenticeship 1.0}
   :yellow      {:bachelors 0.10 :associates 0.95 :cert-1-2yr 1.0 :cert-lt-1yr 1.0 :apprenticeship 1.0}})

(defn parse-color-profile
  "Parse color-profile string (e.g., 'super-purple') into structured data."
  [color-profile-str]
  (let [profile-key (keyword color-profile-str)
        weights (get color-profile-weights profile-key
                     {:bachelors 0.7 :associates 0.6 :cert-1-2yr 0.5 :cert-lt-1yr 0.4 :apprenticeship 0.5})]
    {:color-profile color-profile-str
     :profile-key profile-key
     :degree-level-weights weights
     ;; Purple/Yellow are NOT TOPS Opportunity eligible (ACT < 20)
     :tops-opportunity-eligible? (contains? #{:super-green :green :super-blue :blue} profile-key)}))

(defn get-degree-level-weight
  "Get the weight for a specific degree level based on award-level string."
  [color-profile award-level]
  (let [weights (:degree-level-weights color-profile)
        level-lower (str/lower-case (or award-level ""))]
    (cond
      (str/includes? level-lower "bachelor") (:bachelors weights)
      (str/includes? level-lower "associate") (:associates weights)
      (and (str/includes? level-lower "certificate")
           (or (str/includes? level-lower "1-2")
               (str/includes? level-lower "1 to 2"))) (:cert-1-2yr weights)
      (and (str/includes? level-lower "certificate")
           (str/includes? level-lower "less than")) (:cert-lt-1yr weights)
      (= level-lower "apprenticeship") (:apprenticeship weights)
      :else 0.5)))

;; =============================================================================
;; TOPS Utility Functions
;; =============================================================================

(defn parse-tops-award
  "Parse TOPS award string from student data (e.g., ':tops-tech' -> :tops-tech)."
  [tops-award-str]
  (when (and tops-award-str (not= tops-award-str "none"))
    (let [cleaned (-> tops-award-str
                      (str/replace #"^:" "")
                      (str/lower-case)
                      (str/replace "_" "-"))]
      (when (contains? #{:tops-tech :tops-opportunity :tops-performance :tops-honors}
                       (keyword cleaned))
        (keyword cleaned)))))

(defn normalize-institution-name
  "Normalize institution names to match TOPS coverage tables."
  [institution-name]
  (let [name-mappings
        {"BRCC" "Baton Rouge Community College"
         "LSU" "Louisiana State University and Agricultural & Mechanical College"
         "LSU Eunice" "Louisiana State University-Eunice"
         "LSU Alexandria" "Louisiana State University-Alexandria"
         "LSU Shreveport" "Louisiana State University-Shreveport"
         "Southern University" "Southern University and A & M College"
         "Southern University at Baton Rouge" "Southern University and A & M College"
         "SUNO" "Southern University at New Orleans"
         "UL Lafayette" "University of Louisiana at Lafayette"
         "ULM" "University of Louisiana at Monroe"
         "UNO" "University of New Orleans"
         "Northwestern State University" "Northwestern State University of Louisiana"
         "L.E. Fletcher Technical Community College" "Fletcher Technical Community College"
         "L. E. Fletcher Technical Community College" "Fletcher Technical Community College"}]
    (get name-mappings institution-name institution-name)))

(defn get-tops-coverage
  "Get TOPS coverage amount for a program. Returns 0 if not eligible."
  [tops-award sector institution-name]
  (when tops-award
    (let [normalized-inst (normalize-institution-name institution-name)
          is-2year? (str/includes? (str/lower-case (or sector "")) "2-year")
          coverage-table (if is-2year? tops-2year-coverage tops-4year-coverage)]
      (get-in coverage-table [tops-award normalized-inst] 0.0))))

(defn calculate-effective-cost
  "Calculate effective cost after TOPS and Pell. TOPS applied FIRST to maximize Pell refunds."
  [tuition-annual tops-coverage pell-grant]
  (let [after-tops (max 0 (- tuition-annual tops-coverage))
        excess-pell (max 0 (- pell-grant after-tops))
        net-cost (max 0 (- after-tops pell-grant))]
    {:net-tuition-annual net-cost
     :tops-coverage-annual tops-coverage
     :excess-pell-annual excess-pell
     :original-tuition tuition-annual}))

;; Max Pell Grant 2024-25
(def max-pell-grant 7395.0)

;; =============================================================================
;; Sample Scholarship Data (for demo purposes)
;; =============================================================================

(def sample-scholarships
  "Sample Louisiana scholarships for demo purposes."
  [{:scholarship-id "sch-001"
    :name "Louisiana Engineering Foundation Scholarship"
    :amount 5000
    :deadline "2025-03-15"
    :min-gpa 3.0
    :min-act 20
    :career-fields ["Engineering" "Technology" "STEM"]
    :louisiana-eligible? true
    :citizenship-required? true
    :gender-restriction nil
    :ethnicity-restriction nil
    :complexity "essay_required"
    :description "For Louisiana students pursuing engineering degrees."}

   {:scholarship-id "sch-002"
    :name "SUBR Alumni Association Scholarship"
    :amount 2500
    :deadline "2025-04-01"
    :min-gpa 2.75
    :min-act nil
    :career-fields ["Any"]
    :louisiana-eligible? true
    :citizenship-required? false
    :gender-restriction nil
    :ethnicity-restriction nil
    :complexity "quick_apply"
    :description "For students attending Southern University."}

   {:scholarship-id "sch-003"
    :name "Louisiana Nursing Foundation Award"
    :amount 3000
    :deadline "2025-02-28"
    :min-gpa 3.2
    :min-act nil
    :career-fields ["Healthcare and Medical Careers" "Nursing"]
    :louisiana-eligible? true
    :citizenship-required? true
    :gender-restriction nil
    :ethnicity-restriction nil
    :complexity "essay_required"
    :description "Supporting future Louisiana nurses."}

   {:scholarship-id "sch-004"
    :name "Women in Technology Louisiana"
    :amount 4000
    :deadline "2025-03-30"
    :min-gpa 3.0
    :min-act 18
    :career-fields ["Technology" "Computer Science" "Engineering"]
    :louisiana-eligible? true
    :citizenship-required? true
    :gender-restriction "Female"
    :ethnicity-restriction nil
    :complexity "intensive"
    :description "Empowering women in tech fields."}

   {:scholarship-id "sch-005"
    :name "Baton Rouge Community Foundation Grant"
    :amount 2000
    :deadline "2025-05-01"
    :min-gpa 2.5
    :min-act nil
    :career-fields ["Any"]
    :louisiana-eligible? true
    :citizenship-required? false
    :gender-restriction nil
    :ethnicity-restriction nil
    :complexity "quick_apply"
    :description "For East Baton Rouge Parish students."}

   {:scholarship-id "sch-006"
    :name "African American Excellence Award"
    :amount 3500
    :deadline "2025-03-01"
    :min-gpa 3.0
    :min-act nil
    :career-fields ["Any"]
    :louisiana-eligible? true
    :citizenship-required? true
    :gender-restriction nil
    :ethnicity-restriction "African American"
    :complexity "essay_required"
    :description "Celebrating African American academic achievement."}

   {:scholarship-id "sch-007"
    :name "Louisiana Healthcare Heroes Scholarship"
    :amount 5000
    :deadline "2025-04-15"
    :min-gpa 3.0
    :min-act 19
    :career-fields ["Healthcare and Medical Careers" "Nursing" "Allied Health"]
    :louisiana-eligible? true
    :citizenship-required? true
    :gender-restriction nil
    :ethnicity-restriction nil
    :complexity "essay_required"
    :description "For students committed to healthcare careers in Louisiana."}

   {:scholarship-id "sch-008"
    :name "Skilled Trades Future Leaders"
    :amount 2500
    :deadline "2025-06-01"
    :min-gpa 2.0
    :min-act nil
    :career-fields ["Skilled Trades" "Construction" "Manufacturing"]
    :louisiana-eligible? true
    :citizenship-required? false
    :gender-restriction nil
    :ethnicity-restriction nil
    :complexity "quick_apply"
    :description "Supporting students entering skilled trades."}])

;; =============================================================================
;; Scholarship Pipeline Functions (5 Phases)
;; =============================================================================

(defn filter-scholarships-hard
  "Phase 2: Hard filtering - NO LLM. Rejects on Louisiana eligibility, GPA, citizenship."
  [{:keys [inputs]}]
  (let [scholarships (get inputs "scholarships-data" sample-scholarships)
        student-analysis (get inputs "student-analysis" {})
        student-profile (get inputs "student-profile" {})

        ;; Extract student info
        student-gpa (or (:gpa student-profile)
                        (get student-profile "gpa")
                        3.0)
        student-gender (or (:gender student-profile)
                           (get student-profile "gender"))
        student-ethnicity (or (:ethnicity student-profile)
                              (get student-profile "ethnicity"))

        ;; Filter scholarships
        filtered (reduce
                   (fn [acc sch]
                     (let [min-gpa (or (:min-gpa sch) 0)
                           gender-req (:gender-restriction sch)
                           ethnicity-req (:ethnicity-restriction sch)]
                       (cond
                         ;; Check Louisiana eligibility
                         (not (:louisiana-eligible? sch))
                         (update acc :rejected conj
                                 {:scholarship-id (:scholarship-id sch)
                                  :name (:name sch)
                                  :reason "Not available in Louisiana"})

                         ;; Check GPA
                         (< student-gpa min-gpa)
                         (update acc :rejected conj
                                 {:scholarship-id (:scholarship-id sch)
                                  :name (:name sch)
                                  :reason (str "GPA " student-gpa " below minimum " min-gpa)})

                         ;; Gender restriction (soft warning, still include)
                         (and gender-req (not= gender-req student-gender))
                         (update acc :eligible conj
                                 (assoc sch :_gender-warning
                                        (str "Restricted to " gender-req " students")))

                         ;; Ethnicity restriction (soft warning, still include)
                         (and ethnicity-req (not= ethnicity-req student-ethnicity))
                         (update acc :eligible conj
                                 (assoc sch :_ethnicity-warning
                                        (str "Restricted to " ethnicity-req " students")))

                         ;; Passed all checks
                         :else
                         (update acc :eligible conj sch))))
                   {:eligible [] :rejected []}
                   scholarships)]
    {"eligible-scholarships" (:eligible filtered)
     "rejected-scholarships" (:rejected filtered)}))

(defn score-scholarship-relevance
  "Phase 3: Relevance scoring - NO LLM. Career alignment 40%, demographic 20%, amount 20%, deadline 20%."
  [{:keys [inputs]}]
  (let [scholarships (get inputs "eligible-scholarships" [])
        student-analysis (get inputs "student-analysis" {})
        student-profile (get inputs "student-profile" {})

        career-interests (or (get student-analysis "career-interests")
                             (get student-analysis :career-interests)
                             (:career-fields student-profile)
                             (get student-profile "career-fields")
                             [])
        career-set (set (map str/lower-case career-interests))

        today (java.time.LocalDate/now)

        scored (map (fn [sch]
                      (let [;; Career alignment (40%)
                            sch-fields (set (map str/lower-case (:career-fields sch)))
                            career-overlap (count (clojure.set/intersection career-set sch-fields))
                            career-score (if (or (contains? sch-fields "any")
                                                 (pos? career-overlap))
                                           (min 1.0 (+ 0.5 (* 0.25 career-overlap)))
                                           0.2)

                            ;; Demographic alignment (20%)
                            demo-score (if (or (:_gender-warning sch)
                                               (:_ethnicity-warning sch))
                                         0.3
                                         1.0)

                            ;; Amount tier (20%)
                            amount (:amount sch)
                            amount-score (cond
                                           (>= amount 5000) 1.0
                                           (>= amount 3000) 0.8
                                           (>= amount 2000) 0.6
                                           :else 0.4)

                            ;; Deadline urgency (20%)
                            deadline-str (:deadline sch)
                            deadline (try (java.time.LocalDate/parse deadline-str)
                                          (catch Exception _ nil))
                            days-until (when deadline
                                         (.until today deadline java.time.temporal.ChronoUnit/DAYS))
                            deadline-score (cond
                                             (nil? days-until) 0.5
                                             (< days-until 30) 1.0  ;; URGENT
                                             (< days-until 90) 0.8  ;; SOON
                                             (< days-until 180) 0.6 ;; UPCOMING
                                             :else 0.4)

                            ;; Composite score
                            composite (+ (* 0.40 career-score)
                                         (* 0.20 demo-score)
                                         (* 0.20 amount-score)
                                         (* 0.20 deadline-score))]
                        (assoc sch
                               :career-score career-score
                               :demographic-score demo-score
                               :amount-score amount-score
                               :deadline-score deadline-score
                               :relevance-score composite
                               :days-until-deadline days-until)))
                    scholarships)]
    {"scored-scholarships" (vec (sort-by :relevance-score > scored))}))

(defn categorize-scholarships
  "Phase 4: Categorize by complexity and deadline urgency - NO LLM."
  [{:keys [inputs]}]
  (let [scholarships (get inputs "scored-scholarships" [])

        categorized (group-by (fn [sch]
                                (let [days (:days-until-deadline sch)]
                                  (cond
                                    (nil? days) :future
                                    (< days 30) :urgent
                                    (< days 90) :soon
                                    (< days 180) :upcoming
                                    :else :future)))
                              scholarships)

        ;; Take top scholarships for Phase 5 verification
        top-for-verification (take 10 (sort-by :relevance-score > scholarships))]

    {"categorized-scholarships" {:urgent (vec (:urgent categorized))
                                 :soon (vec (:soon categorized))
                                 :upcoming (vec (:upcoming categorized))
                                 :future (vec (:future categorized))}
     "scholarships-for-verification" (vec top-for-verification)}))

;; =============================================================================
;; Apprenticeship Functions (Salary-Focused)
;; =============================================================================

(defn is-apprenticeship?
  "Check if a program is an apprenticeship based on sector."
  [program]
  (= "Apprenticeship" (:sector program)))

(defn generate-outcome-comparison
  "Generate outcome bullet comparing salary to Louisiana benchmarks."
  [starting-salary avg-salary]
  (cond
    (> starting-salary la-cost-of-living)
    (str "Earn above Louisiana's cost of living ($"
         (format "%.0f" (double la-cost-of-living))
         ") from day one with starting pay of $"
         (format "%.0f" (double starting-salary)))

    (> starting-salary (* 0.9 la-cost-of-living))
    (str "Starting pay of $" (format "%.0f" (double starting-salary))
         " covers Louisiana's cost of living, growing to $"
         (format "%.0f" (double avg-salary)))

    :else
    (str "Build toward financial independence during training, with salary growing from $"
         (format "%.0f" (double starting-salary)) " to $"
         (format "%.0f" (double avg-salary)))))

;; =============================================================================
;; Short-Term Certificate Functions
;; =============================================================================

(defn is-short-term-certificate?
  "Check if program is a short-term certificate (< 2 years)."
  [program]
  (let [award-level (str/lower-case (or (:award-level program) ""))]
    (or (str/includes? award-level "certificate")
        (str/includes? award-level "less than 1")
        (str/includes? award-level "1-2 year"))))

(defn get-certificate-cost-framing
  "Get appropriate cost framing based on certificate duration."
  [program]
  (let [award-level (str/lower-case (or (:award-level program) ""))
        tuition (:tuition program)]
    (cond
      (str/includes? award-level "less than 1")
      {:label "Total program cost"
       :amount tuition
       :description "Complete this certificate in under a year"}

      (str/includes? award-level "1-2")
      {:label "Annual tuition"
       :amount tuition
       :description "1-2 year certificate program"}

      :else
      {:label "Tuition"
       :amount tuition
       :description "Certificate program"})))

;; =============================================================================
;; HBCU Detection
;; =============================================================================

(defn detect-hbcu-preference
  "Detect if 20%+ of recommendations are HBCUs."
  [programs]
  (let [total (count programs)
        hbcu-count (count (filter #(contains? louisiana-hbcus (:institution %)) programs))
        hbcu-percentage (if (pos? total) (/ hbcu-count total) 0)]
    {:hbcu-count hbcu-count
     :total-count total
     :hbcu-percentage hbcu-percentage
     :shows-hbcu-preference? (>= hbcu-percentage 0.2)}))

;; =============================================================================
;; Enhanced Code Executor Functions
;; =============================================================================

(defn enrich-program-with-financials
  "Add TOPS coverage and effective cost to a program."
  [{:keys [inputs]}]
  (let [program (get inputs "current-program" {})
        student-profile (get inputs "student-profile" {})
        tops-award (parse-tops-award (or (:tops-award student-profile)
                                         (get student-profile "tops-award")))
        tuition (or (:tuition program) 0)
        coverage (get-tops-coverage tops-award (:sector program) (:institution program))
        effective (calculate-effective-cost tuition coverage max-pell-grant)]
    {"enriched-program" (merge program
                               {:tops-coverage coverage
                                :net-tuition (:net-tuition-annual effective)
                                :excess-pell (:excess-pell-annual effective)
                                :is-hbcu? (contains? louisiana-hbcus (:institution program))})}))

(defn apply-color-profile-weighting
  "Apply color profile weights to program scoring."
  [{:keys [inputs]}]
  (let [program (get inputs "current-program" {})
        student-profile (get inputs "student-profile" {})
        color-profile-str (or (:color-profile student-profile)
                              (get student-profile "color-profile")
                              "blue")
        color-profile (parse-color-profile color-profile-str)
        degree-weight (get-degree-level-weight color-profile (:award-level program))
        base-score (or (:composite-score program) 0.5)
        weighted-score (* base-score degree-weight)]
    {"weighted-program" (assoc program
                               :degree-level-weight degree-weight
                               :color-profile-weighted-score weighted-score)}))

;; =============================================================================
;; Code Executor Functions
;; =============================================================================

(defn search-programs
  "Phase 2: Search programs using keyword matching from search strategies.
   Returns 35-50 candidate programs."
  [{:keys [inputs]}]
  (let [strategies (get inputs "search-strategies" [])
        programs (get inputs "programs-data" [])

        ;; Extract keywords from strategies - handle both vector and map formats
        keywords (->> strategies
                      (mapcat (fn [s]
                                (cond
                                  (map? s) (or (:keywords s) [(:keyword s)])
                                  (string? s) [s]
                                  :else [])))
                      (remove nil?)
                      (map str/lower-case)
                      set)

        ;; Also add career interests from student analysis
        analysis (get inputs "student-analysis" {})
        career-interests (get analysis "career-interests"
                              (get analysis :career-interests []))
        all-keywords (into keywords (map str/lower-case career-interests))

        ;; Score each program by keyword matches
        scored (->> programs
                    (map (fn [prog]
                           (let [text (str/lower-case
                                        (str (:program-title prog) " "
                                             (:institution prog) " "
                                             (:sector prog)))
                                 matches (count (filter #(str/includes? text %) all-keywords))]
                             (assoc prog :match-score matches))))
                    (filter #(pos? (:match-score %)))
                    (sort-by :match-score >)
                    (take 50)
                    vec)]
    {"candidate-programs" scored}))

(defn optimize-portfolio
  "Phase 3: Select diverse programs for scoring.
   For testing, uses a small portfolio (default 2). Set to 25 for full run."
  [{:keys [inputs]}]
  (let [candidates (get inputs "candidate-programs" [])
        ;; Use smaller portfolio for testing - set to 25 for full run
        portfolio-size (or (get inputs "portfolio-size") 25)

        ;; Just take top N programs for simplicity
        portfolio (->> candidates
                       (sort-by :match-score >)
                       (take portfolio-size)
                       (map-indexed (fn [i prog]
                                      (assoc prog :tier (if (zero? i) "primary" "adjacent"))))
                       vec)]
    {"portfolio-programs" portfolio}))

(defn compute-composite-score
  "Phase 4: Weighted combination of all scores.
   Weights: academic 20%, career 30%, preference 20%, financial 15%, outcome 15%"
  [{:keys [inputs]}]
  (let [academic (parse-double-safe (get inputs "academic-score" "0.5"))
        career (parse-double-safe (get inputs "career-score" "0.5"))
        preference (parse-double-safe (get inputs "preference-score" "0.5"))
        financial (parse-double-safe (get inputs "financial-score" "0.5"))
        outcome (parse-double-safe (get inputs "outcome-score" "0.5"))
        pathway (parse-double-safe (get inputs "pathway-score" "0.5"))
        composite (+ (* 0.20 academic)
                     (* 0.30 career)
                     (* 0.20 preference)
                     (* 0.15 financial)
                     (* 0.15 outcome))]
    {"composite-score" composite}))

(defn group-by-institution
  "Phase 6: Group programs by institution, calculate admissions likelihood based on ACT."
  [{:keys [inputs]}]
  (let [programs (get inputs "personalized-programs" [])
        analysis (get inputs "student-analysis" {})
        student-act (or (get analysis "act-score")
                        (get analysis :act-score)
                        (get-in analysis ["hard-requirements" "act-score"])
                        20)

        grouped (->> programs
                     (filter :institution)  ;; Filter out programs with nil institution
                     (group-by :institution)
                     (map (fn [[inst progs]]
                            (let [avg-act-25 (let [acts (keep :act-25th progs)]
                                              (if (seq acts)
                                                (/ (reduce + acts) (count acts))
                                                20))
                                  likelihood (cond
                                               (>= student-act (+ avg-act-25 3)) "strong-match"
                                               (>= student-act avg-act-25) "match"
                                               :else "reach")]
                              {:institution inst
                               :programs (vec progs)
                               :admissions-likelihood likelihood
                               :program-count (count progs)})))
                     (sort-by :program-count >)
                     vec)]
    {"grouped-institutions" grouped}))

;; =============================================================================
;; Workflow Definition - Comprehensive BRYC Pipeline
;; =============================================================================

(def demo-workflow
  "BRYC Comprehensive Recommendations Workflow.

   Features:
   - Programs track: 6-phase pipeline with TOPS coverage, color profile weighting
   - Scholarships track: 5-phase pipeline (extract → filter → score → categorize → verify)
   - HBCU detection and financial enrichment
   - Parallel execution of Programs + Scholarships tracks

   Pipeline Structure:
   1. Student Understanding (shared)
   2. Parallel Tracks:
      - Programs: Search → Portfolio → Scoring → Personalization → Institution Grouping
      - Scholarships: Eligibility → Hard Filter → Relevance Score → Categorize → Verify
   3. Combine Results

   Usage:
     (def sheet-id (build-demo!))
     (run-demo sheet-id \"reagan\")"

  (sheet/workflow "bryc-recommendations"
    (sheet/blackboard
      {;; Inputs (from Clojure data)
       :student-profile [:map-of :keyword :any]
       :programs-data [:vector [:map-of :keyword :any]]
       :scholarships-data [:vector [:map-of :keyword :any]]

       ;; LLM: student-analysis (string keys from JSON)
       :student-analysis [:map-of :keyword :any]

       ;; Code output
       :color-profile-data [:map-of :keyword :any]

       ;; LLM: search-strategies (string keys from JSON)
       :search-strategies [:vector [:map-of :keyword :any]]

       ;; Code outputs - programs pipeline
       :candidate-programs [:vector [:map-of :keyword :any]]
       :portfolio-programs [:vector [:map-of :keyword :any]]
       :current-program [:map-of :keyword :any]

       ;; LLM scores (simple strings)
       :academic-score :string
       :career-score :string
       :preference-score :string
       :financial-score :string
       :outcome-score :string
       :pathway-score :string
       :composite-score :double
       :scored-programs [:vector [:map-of :keyword :any]]

       ;; LLM: personalization (string keys from JSON)
       :personalization [:map-of :keyword :any]
       :personalized-programs [:vector [:map-of :keyword :any]]

       ;; Code outputs
       :grouped-institutions [:vector [:map-of :keyword :any]]
       :current-institution [:map-of :keyword :any]

       ;; LLM: institution-bullets (string keys from JSON)
       :institution-bullets [:map-of :keyword :any]
       :final-program-recommendations [:vector [:map-of :keyword :any]]

       ;; LLM: student-eligibility (string keys from JSON)
       :student-eligibility [:map-of :keyword :any]

       ;; Code outputs - scholarships
       :eligible-scholarships [:vector [:map-of :keyword :any]]
       :rejected-scholarships [:vector [:map-of :keyword :any]]
       :scored-scholarships [:vector [:map-of :keyword :any]]
       :categorized-scholarships [:map-of :keyword :any]
       :scholarships-for-verification [:vector [:map-of :keyword :any]]
       :current-scholarship [:map-of :keyword :any]

       ;; LLM: scholarship-verification (string keys from JSON)
       :scholarship-verification [:map-of :keyword :any]
       :verified-scholarships [:vector [:map-of :keyword :any]]

       ;; Code outputs - final
       :hbcu-analysis [:map-of :keyword :any]
       :comprehensive-results [:map-of :keyword :any]})

    (sheet/sequence

      ;; ========================================
      ;; PHASE 1: Student Understanding (Shared)
      ;; ========================================

      (sheet/llm "analyze-student-comprehensive"
        :model "google/gemini-2.0-flash-001"
        :instruction "Analyze this high school student profile comprehensively. Extract:
- hard-requirements: Non-negotiable needs based on their goals and preferences
- career-interests: Ranked career interests from their career-fields and open-response
- preference-weights: location-preference, size-preference, cost-sensitivity (each 0.0-1.0)
- student-narrative: 2-3 sentence summary of who this student is
- act-score: The student's ACT score
- gpa: The student's GPA
- tops-award: The student's TOPS award type (e.g., 'tops-opportunity')

Be specific based on their GPA, ACT score, TOPS award, color-profile, and stated interests."
        :reads ["student-profile"]
        :writes ["student-analysis"])

      (sheet/code "parse-student-color-profile"
        :fn "bryc-demo/parse-color-profile-for-workflow"
        :reads ["student-profile"]
        :writes ["color-profile-data"])

      (sheet/llm "generate-search-strategies-comprehensive"
        :model "google/gemini-2.0-flash-001"
        :instruction "Generate 5-8 keyword search strategies to find matching college programs for this student.
Based on their career interests, goals, and preferences, create search queries.

For each strategy provide:
- strategy: Short description of the search approach
- keywords: 2-4 keywords to search for in program titles and descriptions

Example strategy: 'Direct nursing programs' with keywords ['nursing', 'BSN', 'healthcare']"
        :reads ["student-profile" "student-analysis"]
        :writes ["search-strategies"])

      ;; ========================================
      ;; PARALLEL TRACKS: Programs + Scholarships
      ;; ========================================

      (sheet/parallel

        ;; ----------------------------------------
        ;; PROGRAMS TRACK
        ;; ----------------------------------------
        (sheet/sequence

          (sheet/code "search-programs"
            :fn "bryc-demo/search-programs"
            :reads ["student-analysis" "search-strategies" "programs-data"]
            :writes ["candidate-programs"])

          (sheet/code "optimize-portfolio"
            :fn "bryc-demo/optimize-portfolio"
            :reads ["candidate-programs" "student-analysis"]
            :writes ["portfolio-programs"])

          (sheet/map-each "score-programs"
            :from "portfolio-programs"
            :as "current-program"
            :into "scored-programs"
            :parallel 10

            (sheet/sequence
              (sheet/parallel
                (sheet/llm "score-academic"
                  :model "google/gemini-2.0-flash-001"
                  :instruction "Score how well this student's academic profile (GPA, ACT) matches this program's requirements.
Consider the program's typical ACT range and sector (4-Year vs 2-Year).
Return ONLY a single decimal number between 0.0 and 1.0. No other text."
                  :reads ["current-program" "student-analysis"]
                  :writes ["academic-score"])

                (sheet/llm "score-career"
                  :model "google/gemini-2.0-flash-001"
                  :instruction "Score how well this program aligns with the student's stated career interests.
Return ONLY a single decimal number between 0.0 and 1.0. No other text."
                  :reads ["current-program" "student-analysis"]
                  :writes ["career-score"])

                (sheet/llm "score-preference"
                  :model "google/gemini-2.0-flash-001"
                  :instruction "Score how well this program matches the student's preferences (location, institution type).
Return ONLY a single decimal number between 0.0 and 1.0. No other text."
                  :reads ["current-program" "student-analysis"]
                  :writes ["preference-score"])

                (sheet/llm "score-financial"
                  :model "google/gemini-2.0-flash-001"
                  :instruction "Score the financial fit considering the student's TOPS award and program tuition.
TOPS Opportunity covers tuition at public 4-year schools. TOPS Tech covers 2-year programs.
Return ONLY a single decimal number between 0.0 and 1.0. No other text."
                  :reads ["current-program" "student-analysis" "student-profile"]
                  :writes ["financial-score"])

                (sheet/llm "score-outcome"
                  :model "google/gemini-2.0-flash-001"
                  :instruction "Score the program's outcome potential based on earnings data and sector reputation.
Return ONLY a single decimal number between 0.0 and 1.0. No other text."
                  :reads ["current-program" "student-analysis"]
                  :writes ["outcome-score"])

                (sheet/llm "score-pathway"
                  :model "google/gemini-2.0-flash-001"
                  :instruction "Score how well this program fits into the student's career trajectory and long-term goals.
Return ONLY a single decimal number between 0.0 and 1.0. No other text."
                  :reads ["current-program" "student-analysis"]
                  :writes ["pathway-score"]))

              (sheet/code "compute-composite"
                :fn "bryc-demo/compute-composite-score"
                :reads ["academic-score" "career-score" "preference-score"
                        "financial-score" "outcome-score" "pathway-score"]
                :writes ["composite-score"])))

          (sheet/map-each "personalize-programs"
            :from "scored-programs"
            :as "current-program"
            :into "personalized-programs"
            :parallel 10

            (sheet/llm "personalize"
              :model "google/gemini-2.0-flash-001"
              :instruction "Generate personalized recommendation content for this program:
- overview: 2-3 sentences explaining why this program fits this specific student
- why-bullets: 3-4 specific reasons this program is a good match
- key-differentiators: 2-3 unique aspects of this program
- best-for: One sentence describing the ideal student for this program

Make it personal - reference the student's specific goals, interests, and circumstances."
              :reads ["current-program" "student-analysis" "student-profile"]
              :writes ["personalization"]))

          (sheet/code "group-institutions"
            :fn "bryc-demo/group-by-institution"
            :reads ["personalized-programs" "student-analysis"]
            :writes ["grouped-institutions"])

          (sheet/map-each "personalize-institutions"
            :from "grouped-institutions"
            :as "current-institution"
            :into "final-program-recommendations"
            :parallel 10

            (sheet/llm "institution-summary"
              :model "google/gemini-2.0-flash-001"
              :instruction "Generate an institution summary for the student:
- financial-bullets: 2 points about financial aid, TOPS coverage, and affordability
- why-fits-bullets: 2-3 points about why this institution fits the student
- is-hbcu: true if this is an HBCU (Historically Black College/University)

Consider the student's TOPS award, preferences, and the institution's programs."
              :reads ["current-institution" "student-analysis" "student-profile"]
              :writes ["institution-bullets"])))

        ;; ----------------------------------------
        ;; SCHOLARSHIPS TRACK (5 Phases)
        ;; ----------------------------------------
        (sheet/sequence

          ;; Phase 1: Extract student eligibility (LLM)
          (sheet/llm "extract-student-eligibility"
            :model "google/gemini-2.0-flash-001"
            :instruction "Extract the student's scholarship eligibility criteria from their profile:
- gpa: The student's GPA
- act-score: The student's ACT score (if available)
- citizenship: 'us_citizen', 'permanent_resident', or 'other'
- gender: 'Male', 'Female', or 'Other'
- ethnicity: e.g., 'African American', 'Hispanic', 'Caucasian', etc.
- state-residency: 'Louisiana' (all BRYC students are LA residents)
- career-fields: List of career interests from their profile
- financial-need: true if the student appears to have financial need"
            :reads ["student-profile"]
            :writes ["student-eligibility"])

          ;; Phase 2: Hard filtering (NO LLM)
          (sheet/code "filter-scholarships"
            :fn "bryc-demo/filter-scholarships-hard"
            :reads ["scholarships-data" "student-eligibility" "student-profile" "student-analysis"]
            :writes ["eligible-scholarships" "rejected-scholarships"])

          ;; Phase 3: Relevance scoring (NO LLM)
          (sheet/code "score-scholarship-relevance"
            :fn "bryc-demo/score-scholarship-relevance"
            :reads ["eligible-scholarships" "student-analysis" "student-profile"]
            :writes ["scored-scholarships"])

          ;; Phase 4: Categorization (NO LLM)
          (sheet/code "categorize-scholarships"
            :fn "bryc-demo/categorize-scholarships"
            :reads ["scored-scholarships"]
            :writes ["categorized-scholarships" "scholarships-for-verification"])

          ;; Phase 5: Verification & Personalization (LLM per scholarship)
          (sheet/map-each "verify-scholarships"
            :from "scholarships-for-verification"
            :as "current-scholarship"
            :into "verified-scholarships"
            :parallel 10

            (sheet/llm "verify-and-personalize-scholarship"
              :model "google/gemini-2.0-flash-001"
              :instruction "Verify this scholarship's fit for the student and generate personalized content.

IMPORTANT: Check if the scholarship name contains demographic indicators (e.g., 'Women's', 'African American').
If so, verify the student matches that demographic.

Provide:
- verified: true if student is eligible after demographic check
- demographic-match: true if student matches any demographic requirements
- explanation: 2-3 sentences explaining why this scholarship fits the student
- application-tips: 1-2 specific tips for applying
- priority: 'high', 'medium', or 'low' based on fit and deadline"
              :reads ["current-scholarship" "student-eligibility" "student-profile"]
              :writes ["scholarship-verification"]))))

      ;; ========================================
      ;; COMBINE RESULTS
      ;; ========================================

      (sheet/code "analyze-hbcu-preference"
        :fn "bryc-demo/analyze-hbcu-preference"
        :reads ["final-program-recommendations"]
        :writes ["hbcu-analysis"])

      (sheet/code "combine-comprehensive-results"
        :fn "bryc-demo/combine-comprehensive-results"
        :reads ["final-program-recommendations" "verified-scholarships" "hbcu-analysis"
                "student-analysis" "color-profile-data" "categorized-scholarships"]
        :writes ["comprehensive-results"]))))

;; =============================================================================
;; Code Functions for Comprehensive Workflow
;; =============================================================================

(defn parse-color-profile-for-workflow
  "Parse color profile from student profile for the workflow."
  [{:keys [inputs]}]
  (let [student-profile (get inputs "student-profile" {})
        color-profile-str (or (:color-profile student-profile)
                              (get student-profile "color-profile")
                              "blue")]
    {"color-profile-data" (parse-color-profile color-profile-str)}))

(defn analyze-hbcu-preference
  "Analyze HBCU representation in recommendations."
  [{:keys [inputs]}]
  (let [recommendations (get inputs "final-program-recommendations" [])
        all-programs (mapcat :programs recommendations)]
    {"hbcu-analysis" (detect-hbcu-preference all-programs)}))

(defn combine-comprehensive-results
  "Combine all tracks into final comprehensive results."
  [{:keys [inputs]}]
  (let [program-recs (get inputs "final-program-recommendations" [])
        scholarships (get inputs "verified-scholarships" [])
        hbcu-analysis (get inputs "hbcu-analysis" {})
        student-analysis (get inputs "student-analysis" {})
        color-profile (get inputs "color-profile-data" {})
        categorized (get inputs "categorized-scholarships" {})]
    {"comprehensive-results"
     {:programs {:recommendations program-recs
                 :total-count (count (mapcat :programs program-recs))
                 :institution-count (count program-recs)}
      :scholarships {:verified scholarships
                     :by-urgency categorized
                     :total-verified (count scholarships)}
      :insights {:hbcu-analysis hbcu-analysis
                 :color-profile color-profile
                 :student-summary (:student-narrative student-analysis)}
      :metadata {:generated-at (str (java.time.Instant/now))
                 :pipeline-version "comprehensive-v1"}}}))

;; =============================================================================
;; API Functions
;; =============================================================================

(defn build-demo!
  "Build the BRYC demo workflow. Returns sheet-id."
  [context]
  (sheet/build-workflow!! context demo-workflow))

(defn run-demo
  "Run the comprehensive demo with a real student profile by name.
   Available students: reagan, aja, claude, maya, dylan, john, maddi, asha

   Returns comprehensive results including:
   - Program recommendations (grouped by institution)
   - Verified scholarships
   - HBCU analysis
   - Color profile insights"
  [context sheet-id student-name]
  (let [student (load-student-json student-name)
        programs @programs-data
        result (sheet/execute context sheet-id
                 {"student-profile" student
                  "programs-data" programs
                  "scholarships-data" sample-scholarships})]
    (if (= :success (:status result))
      {:status :success
       :student student-name
       :student-analysis (get-in result [:outputs "student-analysis"])
       :color-profile (get-in result [:outputs "color-profile-data"])
       :candidate-count (count (get-in result [:outputs "candidate-programs"]))
       :portfolio-count (count (get-in result [:outputs "portfolio-programs"]))
       :scored-programs (get-in result [:outputs "scored-programs"])
       :program-recommendations (get-in result [:outputs "final-program-recommendations"])
       :verified-scholarships (get-in result [:outputs "verified-scholarships"])
       :hbcu-analysis (get-in result [:outputs "hbcu-analysis"])
       :comprehensive-results (get-in result [:outputs "comprehensive-results"])}
      {:status :failed
       :error (:error result)})))

(defn run-demo-with-profile
  "Run the comprehensive demo with a custom student profile map."
  [context sheet-id student-profile]
  (let [programs @programs-data
        result (sheet/execute context sheet-id
                 {"student-profile" student-profile
                  "programs-data" programs
                  "scholarships-data" sample-scholarships})]
    (if (= :success (:status result))
      {:status :success
       :outputs (:outputs result)}
      {:status :failed
       :error (:error result)})))

;; =============================================================================
;; REPL Usage
;; =============================================================================

(comment
  ;; ==========================================================================
  ;; Prerequisites: Start service via repl_stuff.clj first!
  ;; Evaluate the (do ...) form in repl_stuff.clj to define rs/context
  ;; ==========================================================================

  ;; ==========================================================================
  ;; Build the workflow
  ;; ==========================================================================
  (def sheet-id (build-demo! rs/context))

  ;; ==========================================================================
  ;; Run with different students
  ;; ==========================================================================

  ;; Reagan: 4.0 GPA, 22 ACT, nursing aspirant, TOPS Opportunity
  ;; Should see healthcare/nursing programs prioritized
  (run-demo rs/context sheet-id "reagan")

  ;; Aja: 4.0 GPA, 16 ACT, skilled trades, TOPS Tech
  ;; Should see 2-year technical programs
  (run-demo rs/context sheet-id "aja")

  ;; Claude: 2.8 GPA, 18 ACT, automotive/STEM, TOPS Tech
  ;; Should see technical and STEM programs
  (run-demo rs/context sheet-id "claude")

  ;; Maya: 3.3 GPA, 17 ACT, business, TOPS Tech
  ;; Should see business-related programs
  (run-demo rs/context sheet-id "maya")

  ;; ==========================================================================
  ;; Batch execution - all 8 students in parallel
  ;; ==========================================================================
  (require '[ai.obney.workshop.sheet-service.test-helpers :as h])

  (let [students ["reagan" "aja" "claude" "maya" "dylan" "john" "maddi" "asha"]
        inputs-list (mapv (fn [name]
                            {"student-profile" (load-student-json name)
                             "programs-data" @programs-data
                             "scholarships-data" sample-scholarships})
                          students)]
    (h/run-and-apply! rs/context
      (h/make-batch-execute-command sheet-id inputs-list)))

  ;; ==========================================================================
  ;; GEPA Primitives - Versioning
  ;; ==========================================================================

  ;; Publish current version
  (h/run-and-apply! rs/context
    (h/make-publish-version-command sheet-id :description "Initial BRYC demo"))

  ;; Check version history
  (h/run-query rs/context (h/make-version-history-query sheet-id))

  ;; ==========================================================================
  ;; GEPA Primitives - Traces
  ;; ==========================================================================

  ;; Get traces for this sheet
  (h/run-query rs/context (h/make-get-traces-query sheet-id :limit 10))

  ;; Get node statistics
  (h/run-query rs/context (h/make-node-stats-query sheet-id))

  ;; ==========================================================================
  ;; Data inspection
  ;; ==========================================================================

  ;; Check loaded programs count
  (count @programs-data)
  ;; => 1599

  ;; Sample program
  (first @programs-data)

  ;; Load and inspect a student
  (load-student-json "reagan")

  ;; ==========================================================================
  ;; TOPS Coverage Examples
  ;; ==========================================================================

  ;; Check TOPS coverage for BRCC with TOPS Tech
  (get-tops-coverage :tops-tech "Public, 2-year" "Baton Rouge Community College")
  ;; => 3086.08

  ;; Check TOPS coverage for Southern University with TOPS Opportunity
  (get-tops-coverage :tops-opportunity "Public, 4-year" "Southern University and A & M College")
  ;; => 4973.10

  ;; Calculate effective cost with TOPS and Pell
  (calculate-effective-cost 5000 3086.08 max-pell-grant)
  ;; => {:net-tuition-annual 0, :tops-coverage-annual 3086.08, :excess-pell-annual 5481.08}

  ;; ==========================================================================
  ;; Color Profile Examples
  ;; ==========================================================================

  ;; Parse Reagan's color profile (super-purple)
  (parse-color-profile "super-purple")
  ;; => {:color-profile "super-purple"
  ;;     :degree-level-weights {:bachelors 0.70, :associates 0.90, ...}
  ;;     :tops-opportunity-eligible? false}

  ;; Check degree level weight for a Bachelor's program with super-purple profile
  (get-degree-level-weight (parse-color-profile "super-purple") "Bachelor's degree")
  ;; => 0.70

  ;; ==========================================================================
  ;; Scholarship Examples
  ;; ==========================================================================

  ;; Sample scholarships available
  (count sample-scholarships)
  ;; => 8

  ;; Check if Reagan (African American Female) qualifies for scholarships
  (filter-scholarships-hard
    {:inputs {"student-profile" (load-student-json "reagan")
              "scholarships-data" sample-scholarships}})

  ;; ==========================================================================
  ;; HBCU Detection
  ;; ==========================================================================

  ;; Check Louisiana HBCUs
  louisiana-hbcus
  ;; => #{"Southern University and A & M College" "Dillard University" ...}

  ;; Detect HBCU preference in recommendations
  (detect-hbcu-preference [{:institution "Southern University and A & M College"}
                           {:institution "LSU"}])
  ;; => {:hbcu-count 1, :total-count 2, :hbcu-percentage 0.5, :shows-hbcu-preference? true}

  "")
