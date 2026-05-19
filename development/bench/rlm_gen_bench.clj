(ns rlm-gen-bench
  "ORC RLM Generalization Benchmark — compatibility wrapper.

   This namespace preserves the original public API while delegating
   actual execution to `runner`. New code should require `runner` and
   the per-task namespaces directly.

   Tasks:
   1. Document Analysis - Extraction from a large RFP
   2. Risk & Obligation Analysis - Analytical reasoning
   3. Contract Comparison - Cross-document diff
   4. Contract Comparison (validated) - Diff with adversarial validation
   5. Legal Issue Detection - Analytical reasoning on a small doc

   Run from REPL:
     (require '[rlm-gen-bench :as bench])
     (bench/start!)
     (bench/run-all-tasks!)
     (bench/generate-summary-table!)
     (bench/stop!)
  "
  (:require [runner]))

;; =============================================================================
;; Configuration — re-exported from runner for backwards compatibility
;; =============================================================================

(def config runner/config)

;; =============================================================================
;; Task Definitions
;; =============================================================================

(def tasks
  {:document-analysis
   {:slug "document-analysis"
    :name "Document Analysis (Extraction)"
    :pattern "Extraction (single doc -> summary/dates/entities)"
    :documents [:yyj_rfp]
    :description "Extract summary, key dates, and entities from a large RFP document."
    :instruction "Document available: :yyj_rfp (large RFP, ~280K chars)

Task: Produce a structured extraction of this RFP with three outputs:
- :summary - Executive summary of the RFP
- :key-dates - All important dates with their context (issue date, deadlines, milestones)
- :entities - Important entities mentioned (people with their roles, organizations, contact info)

Quality requirements: Information must be accurate and traceable to the source. Do not invent dates, people, or organizations not present in the document."
    :writes [:summary :key-dates :entities]
    :evaluation-criteria
    ["Identifies RFP issue/response dates"
     "Extracts contact information (Procurement Manager, CFO, etc.)"
     "Identifies key organizations (VAA, LDC, etc.)"
     "Captures monetary thresholds (revenue figures, MAG, etc.)"
     "Identifies key facility specifications (parking spaces, occupancy)"
     "Does NOT hallucinate dates or entities not in the document"]}

   :risk-analysis
   {:slug "risk-analysis"
    :name "Risk & Obligation Analysis"
    :pattern "Analytical reasoning (single doc -> classified output)"
    :documents [:yyj_rfp]
    :description "Analyze a large RFP document to identify and classify all contractual obligations, penalties, and risks."
    :instruction "Document available: :yyj_rfp (large RFP, ~280K chars)

Task: Perform a comprehensive RISK AND OBLIGATION ANALYSIS from the bidder's perspective.

This is analytical work, not extraction. Reason about risks and consequences, do not just list clauses.

Produce four outputs:
- :obligations - What the bidder must do, classified by type (Financial / Operational / Compliance / Reporting / Timeline)
- :penalties - Consequences for non-compliance (termination triggers, financial penalties, disqualification criteria) with specific clause references
- :risk-matrix - Each major obligation mapped to a risk level (HIGH/MEDIUM/LOW) with reasoning about difficulty and consequence severity
- :executive-summary - Top strategic concerns and recommendations for the bidder

Quality requirements: Risk classifications must be justified by document content. Do not invent obligations or penalties not in the document."
    :writes [:obligations :penalties :risk-matrix :executive-summary]
    :evaluation-criteria
    ["Identifies mandatory pre-bid meeting requirement (disqualification if missed)"
     "Identifies Letter of Credit requirement (5% of management fee)"
     "Identifies insurance requirements (specific coverage amounts)"
     "Identifies WorkSafeBC registration requirement"
     "Identifies proposal validity period (60 days)"
     "Classifies financial vs operational vs compliance obligations"
     "Identifies contract termination triggers"
     "Provides risk assessment with HIGH/MEDIUM/LOW ratings"
     "Does NOT hallucinate obligations not in the document"]}

   :contract-comparison
   {:slug "contract-comparison"
    :name "Contract Comparison"
    :pattern "Cross-document diff (two docs -> delta report)"
    :documents [:contract_v2 :contract_v3]
    :description "Compare two versions of a legal contract and identify all differences with impact analysis."
    :instruction "Documents available:
- :contract_v2 - Ontario microFIT Contract Version 2.0 (56K chars)
- :contract_v3 - Ontario microFIT Contract Version 3.1.1 (49K chars)

Task: Compare these two contract versions and identify what changed between them.

Produce five outputs:
- :document-survey - Structure overview of both documents (sections, appendices)
- :section-diffs - Specific additions, removals, and modifications with exact section/clause references
- :major-changes - Significant changes classified as MAJOR (legal/financial impact) vs MINOR (clarification) vs COSMETIC (formatting)
- :impact-analysis - Who is affected by each change (Supplier vs OPA) and how risk has shifted
- :executive-summary - High-level overview of how the contract evolved between versions

Quality requirements:
- Every claimed difference must cite a specific section number or clause text
- If a section is essentially identical between versions, say so explicitly rather than inventing differences
- Do not assume changes based on general knowledge about microFIT contracts - rely only on what is actually present in the documents"
    :writes [:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]
    :evaluation-criteria
    ["Correctly identifies both documents as microFIT contracts"
     "Notes version numbers (2.0 vs 3.1.1)"
     "Identifies page count difference (23 vs 22 pages)"
     "Finds Ministerial Direction reference addition in v3.1.1"
     "Finds anti-splitting provisions added in v3.1.1"
     "Finds domestic content changes between versions"
     "Finds battery backup restriction changes"
     "Classifies changes as MAJOR/MINOR appropriately"
     "Identifies which party benefits from changes"
     "Does NOT hallucinate differences that don't exist"]}

   :legal-issue-detection
   {:slug "legal-issue-detection"
    :name "Legal Issue Detection"
    :pattern "Analytical reasoning on SMALL document (7K chars)"
    :documents [:employment_agreement]
    :description "Analyze a small employment agreement for potential issues, ambiguities, and missing protections."
    :instruction "Document available: :employment_agreement (small employment contract, ~7K chars)

Task: Review this employment agreement from the EMPLOYEE'S perspective.

Produce four outputs:
- :issues - Potential problems or concerns for the employee (broad scope clauses, unfavorable terms, etc.)
- :ambiguities - Unclear or ambiguous language that could be interpreted unfavorably
- :missing - Standard protections or clauses that are NOT included in this agreement
- :recommendations - Suggested negotiation points before signing

Quality requirements: All issues must be traceable to specific clause text. Do not invent problems not in the document."
    :writes [:issues :ambiguities :missing :recommendations]
    :evaluation-criteria
    ["Identifies non-compete clause scope concerns"
     "Notes confidentiality obligations extent"
     "Identifies termination notice period adequacy"
     "Notes bonus/incentive discretionary language"
     "Identifies intellectual property assignment scope"
     "Notes any missing standard protections"
     "Provides actionable negotiation recommendations"
     "Does NOT hallucinate issues not in the document"]}

   :contract-comparison-validated
   {:slug "contract-comparison-validated"
    :name "Contract Comparison (with Adversarial Validation Requirement)"
    :pattern "Cross-document diff with quality requirement: every claim must be adversarially validated"
    :documents [:contract_v2 :contract_v3]
    :description "Compare two contract versions. Quality bar: every claim must survive adversarial validation against source text."
    :instruction "Documents available:
- :contract_v2 - Ontario microFIT Contract Version 2.0 (56K chars)
- :contract_v3 - Ontario microFIT Contract Version 3.1.1 (49K chars)

Task: Compare these two contract versions and identify what changed between them.

Produce five outputs:
- :document-survey - Structure overview of both documents (sections, appendices)
- :section-diffs - Specific additions, removals, and modifications with exact section/clause references
- :major-changes - Significant changes classified as MAJOR (legal/financial impact) vs MINOR (clarification) vs COSMETIC (formatting)
- :impact-analysis - Who is affected by each change (Supplier vs OPA) and how risk has shifted
- :executive-summary - High-level overview of how the contract evolved between versions

QUALITY REQUIREMENT — ADVERSARIAL VALIDATION:
Every claim in your final outputs must survive adversarial validation. An adversarial validator is an antagonistic reviewer whose job is to disprove each claim by examining the source text. A claim that cannot be defended with exact text from the source documents must NOT appear in your final outputs.

In other words: if an antagonistic reviewer cannot find textual evidence for a claim in :contract_v2 and :contract_v3, that claim is invalid and should be excluded. Your final outputs should be the SURVIVING claims after adversarial scrutiny.

Other quality requirements:
- Every claimed difference must cite a specific section number or clause text
- If a section is essentially identical between versions, say so explicitly rather than inventing differences
- Do not rely on general knowledge about microFIT contracts - only on what is actually present in the documents
- Prefer fewer verified claims over many unverified ones"
    :writes [:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]
    :evaluation-criteria
    ["Tree designed by model includes some form of validation/verification stage (observe what shape it takes)"
     "Final claims include citations or text quotes from source"
     "No fabricated 'Deemed Single Property' addition"
     "No fabricated 'Anti-splitting Warranty' addition"
     "No fabricated 'Section 6.6 new in v3' claim"
     "Domestic Content removal correctly identified"
     "Section 6.5 emergency planning correctly identified as v3 addition"
     "Tree pattern is observably different from G08 baseline (does the model add structure for validation?)"]}})


;; =============================================================================
;; Public API — thin wrappers delegating to `runner`
;; =============================================================================

(defn start!
  "Initialize the benchmark system. Delegates to `runner/start!`."
  []
  (let [result (runner/start!)]
    (println "Available tasks:")
    (doseq [[k task] tasks]
      (println (str "  " (name k) " - " (:name task))))
    (println "\nCommands:")
    (println "  (run-task! :task-key)   - Run single task")
    (println "  (run-all-tasks!)        - Run all tasks")
    (println "  (generate-summary-table!) - Generate comparison table")
    (println "  (stop!)                 - Clean up")
    result))

(defn stop!
  "Stop the benchmark system. Delegates to `runner/stop!`."
  []
  (runner/stop!))

(defn run-task!
  "Run a single task by keyword. Looks up the task in the `tasks` map and
   delegates to `runner/run!`."
  [task-key]
  (let [task (get tasks task-key)]
    (when-not task
      (throw (ex-info "Unknown task" {:task task-key
                                       :available (keys tasks)})))
    (runner/run! task)))

(defn run-all-tasks!
  "Run all tasks sequentially. Delegates to `runner/run-all!`."
  []
  (runner/run-all! (vals tasks)))

(defn generate-summary-table!
  "Generate a markdown summary table from saved EDN results.
   Delegates to `runner/generate-summary!`."
  []
  (runner/generate-summary! (:results-dir config)))

;; =============================================================================
;; REPL
;; =============================================================================

(comment
  ;; Quick start
  (start!)

  ;; Run individual tasks
  (run-task! :risk-analysis)
  (run-task! :contract-comparison)
  (run-task! :legal-issue-detection)

  ;; Run all tasks
  (run-all-tasks!)

  ;; Generate summary
  (generate-summary-table!)

  (stop!))
