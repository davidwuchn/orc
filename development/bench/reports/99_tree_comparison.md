# Side-by-Side: How Trees Differ Across Tasks

This document directly compares the behavior trees the model generated for each task, to demonstrate that ORC RLM is genuinely adaptive rather than producing the same template.

## At a Glance

| Aspect | Baseline (Doc Analysis) | G02 (Risk Analysis) | G03 (Contract Comparison) |
|---|---|---|---|
| Task type | Extraction | Analytical reasoning | Cross-document diff |
| Documents | 1 (`:document`) | 1 (`:yyj_rfp`) | 2 (`:contract_v2`, `:contract_v3`) |
| Doc size | 138K chars | 280K chars | 105K combined |
| Chunk size | 8000 | **15000** | **12000** |
| Parallelism | none (sequential) | **max-concurrency 5** | **max-concurrency 3-4** |
| `chunk-document` nodes | 1 | 1 | **2** (one per doc) |
| `map-each` nodes | 1 | 1 | **2** (one per doc) |
| `aggregate` nodes | 1 | 1 | **2** (one per doc) |
| Output keys | 3 (summary/dates/entities) | 4 (obligations/penalties/risk-matrix/exec) | 5 (survey/diffs/major/impact/exec) |
| Sub-LLM style | "Extract X" | "Analyze for X, classify HIGH/MED/LOW" | "Extract clauses with exact text" |
| Synthesis style | "Deduplicate" | "Categorize and assess strategic concerns" | "Compare and cite section numbers" |

## Baseline Tree (Extraction)

```clojure
[:sequence
 [:chunk-document {:from :document, :size 8000, :into :chunks}]
 [:map-each
  {:from :chunks, :as :chunk, :into :chunk-results}
  [:llm
   {:instruction "Extract any dates (with context), people names, organizations, and key facts from this section. Return as structured data."
    :reads [:chunk]
    :writes [:extracted-data]}]]
 [:aggregate {:from :chunk-results, :writes [:all-extracted]}]
 [:llm
  {:instruction "Synthesize the extracted information into a coherent summary. Deduplicate dates and entities. Provide: 1) Executive summary, 2) All key dates with context, 3) All important entities (people, orgs)."
   :reads [:all-extracted]
   :writes [:summary :key-dates :entities]}]
 [:final {:keys [:summary :key-dates :entities]}]]
```

**Decisions the model made:**
- Single chunking source for single input
- Sequential processing (no `max-concurrency`)
- Sub-prompt focused on **what to extract**
- Synthesis prompt focused on **deduplication and consolidation**

## G02 Tree (Analytical Reasoning)

```clojure
[:sequence
 [:chunk-document {:from :yyj_rfp, :size 15000, :into :rfp_chunks}]   ; ← larger chunks
 [:map-each
  {:from :rfp_chunks
   :as :chunk
   :into :chunk_analyses
   :max-concurrency 5}                                                 ; ← parallel
  [:llm
   {:instruction "Analyze this section of the RFP for Parking Management Services.
                  Identify and evaluate:
                  1. OBLIGATIONS: Mandatory compliance, operational requirements, and financial commitments.
                  2. PENALTIES: Specific triggers for fines, liquidated damages, or termination.
                  3. RISKS: Evaluate the difficulty of meeting these obligations and the severity of impact
                     if failed (HIGH/MEDIUM/LOW).
                  Focus on hidden liabilities and operational complexities."
    :reads [:chunk]
    :writes [:analysis]}]]
 [:aggregate {:from :chunk_analyses, :writes [:aggregated_risk_data]}]
 [:llm
  {:instruction "Consolidate the section-by-section analyses into a final comprehensive report.
                 Provide the following sections:
                 1. Obligations: A structured list categorized by Mandatory, Compliance, Operational, and Financial.
                 2. Penalties: A summary of all non-compliance consequences, including termination rights and financial penalties.
                 3. Risk-Matrix: Map key obligations to their Risk Level (High/Medium/Low) based on difficulty and consequence.
                 4. Executive Summary: A high-level synthesis of major concerns and strategic recommendations for the bidder."
   :reads [:aggregated_risk_data]
   :writes [:obligations :penalties :risk-matrix :executive-summary]}]
 [:final {:keys [:obligations :penalties :risk-matrix :executive-summary]}]]
```

**Decisions the model made (different from baseline):**
- Chose **15K chunks** (larger for bigger doc) vs 8K
- Added **`:max-concurrency 5`** for parallel processing
- Sub-prompt asks for **analysis with classification (HIGH/MED/LOW)**, not extraction
- Synthesis prompt asks for **strategic recommendations**, not deduplication
- Different output schema (4 keys, different semantics)

## G03 Tree (Multi-Document Diff — Run 2)

```clojure
[:sequence
 ;; TWO chunking sources (one per document)
 [:chunk-document {:from :contract_v2, :size 12000, :into :v2_chunks}]
 [:chunk-document {:from :contract_v3, :size 12000, :into :v3_chunks}]

 ;; TWO map-each branches (one per document)
 [:map-each
  {:from :v2_chunks, :as :v2_chunk, :into :v2_summarized, :max-concurrency 3}
  [:llm
   {:instruction "Extract the document structure and exact text of key clauses from this chunk
                  of the microFIT Contract Version 2.0. Focus on: Section numbers, clause titles,
                  specific percentages, timelines, and legal requirements."
    :reads [:v2_chunk]
    :writes [:content]}]]
 [:map-each
  {:from :v3_chunks, :as :v3_chunk, :into :v3_summarized, :max-concurrency 3}
  [:llm
   {:instruction "Extract the document structure and exact text of key clauses from this chunk
                  of the microFIT Contract Version 3.1.1..."
    :reads [:v3_chunk]
    :writes [:content]}]]

 ;; TWO aggregations (one per document)
 [:aggregate {:from :v2_summarized, :writes [:v2_full_map]}]
 [:aggregate {:from :v3_summarized, :writes [:v3_full_map]}]

 ;; Single comparison synthesis reading BOTH aggregated profiles
 [:llm
  {:instruction "Compare microFIT Contract v2.0 and v3.1.1 using the provided extracts.
                 - document-survey: Parallel outline of structures.
                 - section-diffs: Detailed list of additions, removals, or word changes.
                   YOU MUST CITE SPECIFIC SECTION NUMBERS AND QUOTE TEXT. If a section is identical, note it.
                 - major-changes: Significant shifts in policy, payment terms, or supplier obligations.
                 - impact-analysis: How these changes affect the Supplier vs. the OPA.
                 - executive-summary: High-level overview of the evolution from v2.0 to v3.1.1.
                 ANTI-HALLUCINATION: Do NOT assume standard industry changes. Only report what is
                 present in the text. If sections are identical, clearly state that."
   :reads [:v2_full_map :v3_full_map]
   :writes [:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]}]
 [:final {:keys [:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]}]]
```

**Decisions the model made (different from both prior tasks):**
- **TWO chunk-document nodes** (one per input document)
- **TWO map-each loops** (parallel processing for each doc independently)
- **TWO aggregations** producing two separate profiles
- Final LLM reads **both profiles** with explicit instruction to cite section numbers and acknowledge identical sections
- 5 output keys matched to diff-task semantics

## Structural Comparison Visualization

### Baseline & G02 (single-doc):
```
[chunk] → [map-each: analyze] → [aggregate] → [synthesize] → [final]
```

### G03 (multi-doc):
```
[chunk v2] ┐                  ┌→ [map-each: analyze v2] → [aggregate v2] ┐
           │                  │                                          ├→ [compare] → [final]
[chunk v3] ┘                  └→ [map-each: analyze v3] → [aggregate v3] ┘
```

The model didn't just swap parameters — it **generated structurally different trees** based on the task's input cardinality.

## Why This Matters

The original concern from the user was: *"make sure it is general so now we can plan out our additional demos to be sure that it is not just overfit to this task"*

This side-by-side shows the engine is NOT overfit:

1. **Different chunk sizes** based on doc size (8K → 15K → 12K)
2. **Different parallelism** based on task urgency and chunk count
3. **Different sub-LLM prompt styles** based on task type (extract vs analyze vs preserve-for-diff)
4. **Different output schemas** matching each task's actual deliverables
5. **Different tree structures** based on input cardinality (1 doc → linear tree, 2 docs → forked tree with merged comparison)

The model is genuinely designing trees for each task, not pattern-matching a single template.
