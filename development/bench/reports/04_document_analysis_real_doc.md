# Document Analysis on Real 280K Document (Generalization Re-run)

**File:** `development/bench/generalization-results/document-analysis_2026-05-18_193538.edn`
**Date:** 2026-05-18
**Status:** :success
**Configuration:** Clean task instructions (NO example tree provided - model designed everything from scratch)

## Why This Run Matters

The original baseline (`01_document_analysis.md`) ran on a synthetic 138K-char RFP **with hints to the tree pattern needed provided in the instructions**. This run is the proper generalization test:

1. **Real document** (280K chars, actual Victoria Airport RFP, not synthetic)
2. **No tree template in instructions** - just goals
3. **Clean task description** - "extract summary, key dates, entities"

The instructions only state WHAT to accomplish. The model must design the tree itself, using only the built-in RLM documentation about primitives.

## Result Metrics

| Metric | Value |
|---|---|
| Status | `:success` |
| Total duration | **251.9 seconds** |
| Total tokens | **194,694** |
| Prompt tokens | 147,877 |
| Completion tokens | 46,817 |
| Document size | 280,140 chars |
| Number of chunks | 24 (24 × 12K) |
| Sub-LLM calls | 24 (chunks) + 3 (synthesis) = **27 total** |

## Generated Behavior Tree (Model's Own Design)

```clojure
[:sequence
 [:chunk-document {:from :yyj_rfp, :size 12000, :into :rfp_chunks}]
 [:map-each
  {:from :rfp_chunks,
   :as :chunk,
   :into :extracted_data,
   :max-concurrency 5}
  [:llm
   {:instruction
    "Extract the following from this RFP section:
     1. Summary points (scope of work, requirements)
     2. Key dates (deadlines, meetings, milestones)
     3. Entities (organizations, roles, named individuals, contact info)
     If information is not present in this section, return an empty list for that category.",
    :reads [:chunk],
    :writes [:partial_extraction]}]]
 [:aggregate {:from :extracted_data, :writes [:combined_raw_data]}]

 ;; NOVEL PATTERN: Three separate synthesis LLMs instead of one
 [:llm
  {:instruction "Analyze the combined raw data and provide a concise, high-level Executive Summary..."
   :reads [:combined_raw_data]
   :writes [:summary]}]
 [:llm
  {:instruction "Extract and format a chronological list of all key dates mentioned..."
   :reads [:combined_raw_data]
   :writes [:key-dates]}]
 [:llm
  {:instruction "Identify and list all significant entities mentioned, including organizations..."
   :reads [:combined_raw_data]
   :writes [:entities]}]
 [:final {:keys [:summary :key-dates :entities]}]]
```

## **NOVEL FINDING: Three-LLM Synthesis Pattern**

This is a tree structure we **have not seen before**. The model designed:

- One **fan-out** sub-LLM per chunk (24 parallel-intent extractions)
- Aggregation into a single combined dataset
- **Three separate specialized synthesis LLMs** in sequence — one per output key
  - LLM #1: Executive summary
  - LLM #2: Chronological dates list
  - LLM #3: Entity catalog

Compare to baseline (synthetic 138K doc, hardcoded template): **single synthesis LLM** producing all 3 outputs at once.

Why does this matter? Each specialized synthesis can focus on its specific output structure (date format, entity hierarchy, summary tone) without being distracted by other outputs. This is the model designing a more sophisticated pattern when given freedom.

## Execution Timeline (per-subcall debug logging)

### Phase 2 — Chunked Extraction (24 calls)
```
idx=0  duration=4195ms  tokens=3436
idx=1  duration=4000ms  tokens=3430
idx=2  duration=5036ms  tokens=4004
idx=3  duration=3676ms  tokens=3481
idx=4  duration=3767ms  tokens=3689
idx=5  duration=4592ms  tokens=3478
idx=6  duration=28788ms tokens=11400  ← outlier (API slowness or large response)
idx=7  duration=3566ms  tokens=8917
idx=8  duration=2946ms  tokens=6832
idx=9  duration=1466ms  tokens=11767
idx=10 duration=1681ms  tokens=11795
idx=11 duration=1954ms  tokens=11809
idx=12 duration=4922ms  tokens=4149
idx=13 duration=4104ms  tokens=3699
idx=14 duration=4325ms  tokens=3746
idx=15 duration=4583ms  tokens=3623
idx=16 duration=18426ms tokens=7538  ← second outlier
idx=17 duration=3736ms  tokens=3885
idx=18 duration=3157ms  tokens=3935
idx=19 duration=3271ms  tokens=3354
idx=20 duration=3792ms  tokens=3718
idx=21 duration=3194ms  tokens=3574
idx=22 duration=3992ms  tokens=3805
idx=23 duration=6967ms  tokens=2524
```

### Phase 3 — Three Specialized Synthesis Calls
```
Executive summary  duration=4650ms   tokens=12321
Key dates list     duration=57634ms  tokens=24087  ← very long, large output
Entity catalog     duration=54782ms  tokens=24049  ← very long, large output
```

The dates and entities synthesis each took ~55s because the model output enormous, well-structured lists with section headers and full context.

## Concurrency Observation (Bug Found)

The model wrote `:max-concurrency 5` but the per-subcall logs show **sequential execution** — at any given moment, only ONE subcall was in flight. Confirmed by counting starts vs. dones throughout the run.

This is a bug — `:max-concurrency` is being accepted by the parser but not honored by the runtime. See [05_concurrency_bug_investigation.md](05_concurrency_bug_investigation.md) for the investigation.

If concurrency had worked, expected runtime would have been roughly `(24/5) * avg_chunk_time + synthesis = ~30s + 120s = 150s` instead of the observed 251s.

## Output Quality Verification (Spot-Checks vs. Source)

| Model Claim | Source Document |
|---|---|
| Feb 13, 2025: RFP Issue Date | ✓ EXACT: `Date of Issue: Thursday, February 13th, 2025` |
| Feb 20, 2025 (2:00 pm PST): Receipt Confirmation | ✓ EXACT: `Receipt Confirmation Form due February 20th, 2025 2:00 pm PST` |
| Feb 27, 2025 (1:30 pm PST): Pre-Bid Meeting | ✓ EXACT: `Pre-Bid Meeting and Site Tour (Mandatory) February 27th, 2025 1:30 pm PST` |
| March 6, 2025 (2:00 pm PST): Question Deadline | ✓ EXACT: `Deadline for Questions March 6th, 2025 2:00 pm PST` |
| March 31, 2025 (2:00 pm PST): Closing Date | ✓ EXACT: `closing Date and Time is Monday, March 31st, 2025 no later than 2:00 pm` |
| Tseycum, Tsartlip, Tsawout, Pauquachin First Nations | ✓ EXACT: `Tseycum First Nation, Tsartlip First Nation, Tsawout First Nation, and Pauquachin First Nation` |
| SKIDATA, FLOWBIRD, Precise Parklink vendors | ✓ verified in source |
| Victoria Airport Authority (VAA) | ✓ EXACT |

**Zero hallucinations detected.**

## Generalization Evidence

This is the strongest generalization evidence so far because the instructions had **NO tree template**:

1. **Model independently chose chunk-document + map-each + aggregate + synthesis pattern** without being shown that pattern
2. **Model chose 12K chunks** (24 chunks for 280K doc) — a different size than baseline (8K) or G02 risk-analysis (15K)
3. **Model chose `:max-concurrency 5`** without being shown that parameter
4. **Model invented a 3-LLM synthesis pattern** — one specialized LLM per output key — instead of a single combined synthesis
5. **Model wrote distinct, specialized prompts** for each output ("chronological list of dates", "list of organizations including departments and roles", etc.)

This proves the engine is genuinely adaptive. The model designs trees from scratch based on the task goals, and produces novel patterns we did not anticipate.

## Comparison to Original Baseline

| Aspect | Baseline (template-driven) | This Run (autonomous design) |
|---|---|---|
| Document | Synthetic 138K | Real 280K |
| Instructions | Provided exact tree | Goals only |
| Chunk size | 8K (forced) | **12K (chosen)** |
| Concurrency | none in original | **5 (chosen, though bug prevents actual parallelism)** |
| Synthesis | 1 LLM, 3 outputs | **3 LLMs, 1 output each (novel)** |
| Total tokens | 80,576 | 194,694 |
| Duration | 160.8s | 251.9s |
| Quality | Perfect | **Perfect** |

The token usage is higher because:
- Document is 2x larger
- Final synthesis runs 3 separate calls (each re-reads the aggregated data) instead of 1

This trade-off (more tokens for specialized output quality) is the model's own design choice.

---

# Rerun with parallel actually working (G06, 2026-05-19)

After P01 was fixed (max-concurrency now honored), the same task was re-run.

**Run file:** `development/bench/generalization-results/document-analysis_2026-05-19_131441.edn`

## Result Metrics — Parallel Run

| Metric | Serial (P01 bug present) | Parallel (P01 fixed) |
|---|---|---|
| Status | `:success` | `:success` |
| Duration | **251.9 seconds** | **126.3 seconds (2.0x faster)** |
| Total tokens | 194,694 | 212,066 (+9%) |
| Chunk count | 24 | 24 |
| max-concurrency declared | 5 (ignored) | 5 (honored) |
| Synthesis style | 3 specialized LLMs | 3 specialized LLMs (same novel pattern!) |

## Generated Tree (Parallel Run)

```clojure
[:sequence
 [:chunk-document {:from :yyj_rfp, :size 12000, :into :chunks}]
 [:map-each
  {:from :chunks, :as :chunk, :into :extracted_data, :max-concurrency 5}
  [:llm
   {:instruction "Extract: 1) Key points for executive summary,
                  2) Important dates (with context),
                  3) Entities (people/roles, organizations, contact info).
                  If info is not present in this segment, leave it blank."
    :reads [:chunk]
    :writes [:segment_info]}]]
 [:aggregate {:from :extracted_data, :writes [:combined_info]}]
 [:sequence
  [:llm {:instruction "Synthesize Executive Summary..."
         :reads [:combined_info] :writes [:summary]}]
  [:llm {:instruction "List all Key Dates with context, chronologically..."
         :reads [:combined_info] :writes [:key-dates]}]
  [:llm {:instruction "List all unique Entities including organizations, roles, contacts..."
         :reads [:combined_info] :writes [:entities]}]]
 [:final {:keys [:summary :key-dates :entities]}]]
```

## Pattern Stability

The model designed the **same multi-synthesis pattern** in both runs (serial and parallel) — 1 chunked extraction + aggregate + 3 specialized synthesis LLMs. The pattern is stable across runs.

Compare to G07 (risk-analysis): the model chose 1 unified synthesis with 4 outputs.

This shows the model picks different synthesis strategies for different tasks — perhaps because:
- **Document analysis**: the 3 outputs (summary, dates, entities) are quite different in shape/style → specialized LLMs help
- **Risk analysis**: the 4 outputs (obligations, penalties, risk-matrix, exec-summary) are interrelated and analytical → one synthesis can reason about all of them together

## Quality Verification — Parallel Run (spot-checks)

| Claim | Verification |
|---|---|
| 3,243 parking spaces, four main lots | ✓ Verified in source |
| 1.87M passengers in 2024 | ✓ Verified |
| April 1, 1997: Head Lease date (Crown / VAA) | ✓ EXACT in source |
| November 25, 2024: Ice Control & Snow Clearing Map (Appendix C) | ✓ EXACT |
| December 31, 2024: Net Book Value reference date | ✓ EXACT |
| Feb 13, 2025: RFP Issue | ✓ EXACT (date format match) |
| Curb Management Program | ✓ EXACT terminology in source |

**Zero hallucinations. Quality matches or exceeds prior runs.**

The parallel run picked up additional historical reference dates (1997 Head Lease, 2024 reporting dates) that previous runs didn't surface — possibly because more compute capacity allowed more thorough per-chunk extraction.

## Concurrency Verification

Per-subcall debug logs showed:
- Chunks processed with steady-state 5 in-flight (no duplicate starts, no serialization)
- Final 3 synthesis calls run serially (because the model wrapped them in `:sequence`)

This explains why the speedup is "only" 2x: parallelism speeds up the 24 chunks, but the model's 3 serial synthesis calls (~30-60s each) are still a sequential bottleneck. For tasks where the model designs a single synthesis (like risk-analysis), the speedup is closer to 2.4x.

## Comparison: Three Patterns Observed Across Tasks

| Task | Synthesis Pattern | Serial syn time | Parallel total |
|---|---|---|---|
| G07 risk-analysis | **1 unified LLM** with 4 outputs | ~40s | 64.8s |
| G06 doc-analysis | **3 specialized LLMs** in sequence | ~100s | 126.3s |
| (potentially) | N-LLM with parallel synthesis | future | future |

If the RLM DSL exposed `:parallel`, the model could put the 3 synthesis LLMs in parallel and we'd see another ~50% speedup on G06. This is a potential future enhancement.
