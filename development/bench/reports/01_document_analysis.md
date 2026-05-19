# Baseline: Document Analysis (Extraction Task)

**Run ID:** orc-rlm_01
**File:** `development/bench/runs/2026-05-18_document_analysis/orc-rlm_01.edn`
**Date:** 2026-05-18

## Task Definition

Extract key information from a 138K-character synthetic Victoria Airport RFP document.

- **Document:** Synthetic RFP (~138K chars, generated via template + content variation)
- **Inputs:** `[:document]`
- **Outputs:** `[:summary :key-dates :entities]`
- **Pattern:** Extraction (single document)

## Result Metrics

| Metric | Value |
|---|---|
| Status | `:success` |
| Duration | **160.8 seconds** |
| Total tokens | **80,576** |
| Prompt tokens | 59,800 |
| Completion tokens | 20,776 |
| Document size | 138,361 chars |
| Comparison: predict-rlm baseline | 834,480 tokens (median of 3 runs) |
| Token reduction vs predict-rlm | **~10.4x fewer tokens** |

## Generated Behavior Tree

```clojure
[:sequence
 [:chunk-document {:from :document, :size 8000, :into :chunks}]
 [:map-each
  {:from :chunks, :as :chunk, :into :chunk-results}
  [:llm
   {:instruction
    "Extract any dates (with context), people names, organizations, and key facts from this section. Return as structured data.",
    :reads [:chunk],
    :writes [:extracted-data]}]]
 [:aggregate {:from :chunk-results, :writes [:all-extracted]}]
 [:llm
  {:instruction
   "Synthesize the extracted information into a coherent summary. Deduplicate dates and entities. Provide: 1) Executive summary, 2) All key dates with context, 3) All important entities (people, orgs).",
   :reads [:all-extracted],
   :writes [:summary :key-dates :entities]}]
 [:final {:keys [:summary :key-dates :entities]}]]
```

## What the Model Did

1. **Chose chunk size 8000** — adequate for 138K document
2. **Did NOT use max-concurrency** — chunks processed sequentially
3. **Extraction-oriented sub-prompt** — "Extract dates, people, organizations, key facts"
4. **Single synthesis LLM** at the end with deduplication instruction

## Output Quality (verified against source)

### Executive Summary (excerpt)
> The Victoria International Airport Authority (YYJ) has issued Request for Proposals (RFP) #YYJ-2025-001 for comprehensive parking management services. The airport, which serves over 2.1 million annual passengers, is seeking an experienced operator to manage 3,500 parking spaces across three lots.

### Key Dates Extracted
- January 15, 2025: RFP Issue Date ✓
- February 1, 2025: Questions Deadline ✓
- February 28, 2025 (2:00 PM PST): Proposal Submission Deadline ✓
- March 15, 2025: Contract Award ✓
- April 1, 2025: Service Commencement ✓
- July–August: Peak season (94% occupancy) ✓
- 180 Days Prior: Renewal decision ✓
- 7 Years: Data retention minimum ✓

### Entities Extracted
- **Organizations:** YYJ, Parking Solutions Inc., BC Provincial Government, BidSync, payment providers
- **People:** Sarah Johnson (Procurement Mgr), Amanda Torres (General Counsel), Michael Chen (CFO), Lisa Patel (Controller), James Wilson, Priya Sharma, Robert Kim

All entities verified against the synthetic RFP source — no hallucinations.

## Why This Is the Baseline

This established that:
- `emit-tree!` works end-to-end (Phase 1 generates code, Phase 2 executes)
- The standard chunk → map-each → aggregate → synthesize pattern is effective for extraction
- Token usage is dramatically lower than the predict-rlm baseline (10x reduction)

But it left open the question: **does the model just reproduce this same pattern for every task?** That question is what the G02–G04 generalization suite was designed to answer.
