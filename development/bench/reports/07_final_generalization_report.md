# Final Generalization Report — ORC RLM Adaptive Tree Design

**Date:** 2026-05-19
**Model:** google/gemini-3-flash-preview (via OpenRouter)
**Scope:** Five fundamentally different task types tested with **clean instructions** (no hardcoded tree templates) and **working parallelism** (P01 fixed)

## Bottom Line

**ORC RLM generalizes.** When given goal-only task instructions, the model designs **different trees** for **different task types**, choosing decomposition strategies appropriate to:
- Document size (chunking for large, direct for small)
- Number of documents (multi-doc pipelines for diffs)
- Task semantics (analytical, extraction, classification)
- Quality requirements (adversarial validation → cross-reference data flow)

The engine is **not pattern-matching to a single template**. The model invents novel tree structures we did not anticipate, and adapts to constraints expressed in the instructions.

## Summary Table

| Task | Doc Size | Tree Pattern | Duration | Tokens | Hallucinations |
|------|---------|--------------|----------|--------|---|
| **G04** Legal issue detection | 7K (1 doc) | **No tree — direct answer** | 8.8s | 5,706 | 0 |
| **G08** Contract comparison | 105K (2 docs) | 5-stage pipeline, no chunking | 35.6s | 64,336 | 0 |
| **G09** Contract comparison + adversarial validation | 105K (2 docs) | 4-stage with source cross-reference in final | 26.7s | 84,337 | 0 |
| **G07** Risk analysis | 280K (1 doc) | chunks + map-each(5) + **single** unified synthesis | 64.8s | 170,336 | 0 |
| **G06** Document analysis | 280K (1 doc) | chunks + map-each(5) + **three** specialized synthesis | 126.3s | 212,066 | 0 |

**Total runs: 5 successful, zero hallucinations across all spot-checks.**

## Trees Side-by-Side

### G04: Direct Answer (no tree)

```
[doc 7K] ─→ [LLM with full doc + task] ─→ [outputs]
```

The model bypassed `emit-tree!` entirely. The 7K doc fits in context with the prompt; a tree would just add overhead.

### G08: 5-Stage Pipeline (multi-doc, no chunking)

```
[contract_v2, contract_v3 (105K combined)]
       │
       ├─→ [LLM: structure survey] ─→ :document-survey
       ├─→ [LLM: side-by-side diffs (full docs)] ─→ :section-diffs
                              │
                              ▼
            [LLM: classify MAJOR/MINOR/COSMETIC] ─→ :major-changes
                              │
                              ▼
            [LLM: impact analysis] ─→ :impact-analysis
                              │
                              ▼
            [LLM: executive synthesis] ─→ :executive-summary
```

Pipeline pattern: each stage builds on previous. No chunking since both docs fit in context.

### G09: Adversarial Validation Variant

```
[contract_v2, contract_v3]
       │
       ├─→ [LLM: structure survey] ─→ :document-survey
       │
       ├─→ [chunk-document v2] (dead code — never used)
       ├─→ [chunk-document v3] (dead code — never used)
       │
       ├─→ [LLM "Be adversarial": raw diffs] ─→ :raw_diffs
                              │
                              ▼
       [LLM "ADVERSARIAL CLEARANCE": final synthesis
        REREADS source docs + raw_diffs] ─→ :section-diffs, :major-changes, ...
```

**Critical feature**: final stage `:reads [:raw_diffs :contract_v2 :contract_v3]` — re-reads source documents alongside the prior outputs. This is how the model implemented "adversarial validation" — not as a separate validator node, but as a cross-reference data flow.

**Result**: found 2x more verified differences than G08 baseline. Zero hallucinations.

### G07: Single Synthesis (analytical)

```
[yyj_rfp 280K]
       │
       ├─→ [chunk-document → 24 chunks of 12K]
       ├─→ [map-each max-concurrency=5]
       │      └─→ [LLM: analyze for risks/obligations/penalties per chunk]
       │              (24 calls, 5 in parallel)
       ├─→ [aggregate]
       │
       ├─→ [LLM: unified synthesis (4 outputs)] ─→ :obligations, :penalties, :risk-matrix, :executive-summary
```

One unified synthesis with multiple output keys.

### G06: Multi-Synthesis (extraction)

```
[yyj_rfp 280K]
       │
       ├─→ [chunk-document → 24 chunks of 12K]
       ├─→ [map-each max-concurrency=5]
       │      └─→ [LLM: extract summary points/dates/entities per chunk]
       ├─→ [aggregate]
       │
       ├─→ [LLM: synthesize summary] ─→ :summary
       ├─→ [LLM: extract chronological dates] ─→ :key-dates
       ├─→ [LLM: list entities] ─→ :entities
```

THREE specialized synthesis LLMs in sequence — one per output. The model chose this because the three outputs are structurally different (executive prose vs. chronological list vs. categorized entities).

## Speedup from P01 (max-concurrency fix)

P01 was a 2-part bug:
1. `:max-concurrency` was silently dropped during DSL → ORC DSL compilation (rlm_tree_executor.clj)
2. The completion handler's `:in-flight` set was never updated, so concurrent completions all picked the same next-to-start (todo_processors.clj)

Both bugs found via TDD: a single test reproduced both. After fixing each, the test passes with peak in-flight = 3 (matches max-concurrency).

### Real-world speedup

| Task | Serial (P01 bug present) | Parallel (P01 fixed) | Speedup |
|------|--------------------------|----------------------|---------|
| G07 risk-analysis | 156.5s | **64.8s** | **2.4x** |
| G06 doc-analysis | 251.9s | **126.3s** | **2.0x** |
| G08 contract-comparison | 109.5s (Run 1) / 204.7s (Run 2) | **35.6s** | **3x+ / 6x** |
| G09 validator variant | n/a | **26.7s** | n/a |
| G04 legal-issue | n/a (no tree to parallelize) | 8.8s | n/a |

The speedup is smaller than `max-concurrency=5` would suggest because:
- Models often design serial synthesis stages (which can't parallelize)
- API latency variance (some chunks take 3s, some 45s)
- Overhead of event-driven coordination

But the speedup is **real, measurable, and useful**.

## Novel Patterns Observed (Not Anticipated)

### 1. 3-LLM Specialized Synthesis (G06)

We expected a single synthesis LLM with multiple output keys. The model instead designed THREE specialized synthesis LLMs in sequence, each focused on one output type. This is more expensive (3x prompt tokens for re-reading aggregate) but produces more focused outputs. The model chose this trade-off for document analysis where the outputs are structurally different.

### 2. 5-Stage Pipeline Without Chunking (G08)

We expected chunked comparison for the 105K combined contracts. The model bypassed chunking and used direct multi-doc comparison in a sequential pipeline. Smaller documents made this feasible; the model recognized that.

### 3. Source Cross-Reference for Validation (G09)

When told "every claim must survive adversarial validation," we expected the model might add a validator node or a verification map-each. Instead, it added "be adversarial" to its prompts AND structured the final synthesis to re-read both source documents alongside the prior outputs. This cross-reference data flow IS the validator — implemented as data flow, not as a node type.

### 4. Direct Answer for Small Docs (G04)

For the 7K employment agreement, the model chose to bypass `emit-tree!` entirely. It answered directly. This was acceptable behavior in the task instructions ("you may use direct execution if you choose"), and the model correctly judged that decomposition was unnecessary overhead.

## Common Patterns Across Tasks

When the model DID use trees, certain patterns recurred:
- `:chunk-document` always used for docs > ~100K chars
- `:map-each` with `:max-concurrency 5` always used for parallel chunk processing
- `:aggregate` always used to combine map-each results
- `:final` always declared the output keys
- Sub-LLM prompts always task-specific (analytical, extractive, comparative)

These are the **canonical primitives** for large data processing. The model converges on this pattern without being shown an example.

## Quality Verification (Aggregate)

Across 5 tasks, ~30 specific facts were spot-checked against source documents:

| Task | Spot-checks | Hallucinations |
|------|-------------|---|
| G07 | 9 (interest %, audit threshold, strike days, LC %, radius, marketing %, hours, etc.) | 0 |
| G06 | 7 (dates with times, page counts, vendor names, First Nations names) | 0 |
| G08 | 7 (version numbers, removed sections, modified sections, identical sections) | 0 |
| G09 | 8 (Prescribed Form, Website, Existing Building (b), discretionary clauses, etc.) | 0 |
| G04 | 6 (Schedule E sections, durations, sensitive data fields, role title) | 0 |

**37 spot-checks, 0 hallucinations.** Every cited section number, percentage, date, and named entity verified against source.

## Failure Modes Caught and Mitigated

1. **G03 Run 1 (Contract Comparison, original)** — model summarized chunks then compared summaries, losing specific text. Hallucinated 3 of 4 major claims. Caught by source verification, mitigated by instruction guidance (G08 with anti-hallucination notes preserved text).
2. **P01 Concurrency Bug** — `:max-concurrency` silently dropped during compilation. Caught by writing a TDD test that detected serial behavior. Fixed by adding to the command call AND adding `:in-flight` tracking in completion handler.
3. **Phase 2 timeout** — multi-synthesis pattern took longer than 300s default. Caught by validation runs. Mitigated by bumping timeout to 900s (15 min) for complex synthesis trees.

## Open Questions / Future Work

### 1. Pre-existing preview test failures (B01)

Three tests in `rlm_mode_test.clj` related to preview behavior (token-space representation) are failing. Unrelated to P01 work but should be investigated separately. Tracked in `docs/issues/B01-rlm-sandbox-preview-test-failures.md`.

### 2. Serial synthesis as new bottleneck

After P01, map-each parallelizes correctly but the model's serial `:sequence` of synthesis LLMs is now the slowest stage. Exposing `:parallel` in the RLM DSL would let the model parallelize independent synthesis calls (e.g., the 3 synthesis nodes in G06). Estimated additional 30-50% speedup for that pattern.

### 3. Adversarial validation pattern formalization

G09 showed the model can achieve validation behavior with existing primitives. Could be promoted to a recognized pattern (with documentation in the RLM system) or formalized as a `:validate` node type. Worth deciding when the validation pattern is needed often.

### 4. Larger-scale concurrency tests

Tests at `:max-concurrency 5` verified. Not yet tested:
- `:max-concurrency 20+` (do we hit OpenRouter rate limits cleanly?)
- Nested map-each
- Failure isolation (one of N parallel calls fails)

These are polish-level concerns, not required for the generalization story.

## Conclusion

ORC RLM is genuinely adaptive. The model:
1. Designs different tree structures for different task types (4 distinct patterns observed)
2. Adapts decomposition to document size (chunking thresholds, direct for small)
3. Recognizes when decomposition is unnecessary (G04 used no tree)
4. Translates quality requirements (adversarial validation) into data flow design without being told how
5. Converges on canonical patterns (chunk + map + aggregate + synthesize for large data) when appropriate
6. Produces verified outputs with zero hallucinations across 37 spot-checks

The original concern — "is it overfit to the first demo?" — is resolved. **It is not overfit.** It generalizes.

Combined with the P01 fix (parallel execution actually working), the engine is now production-viable for general behavior tree generation across analytical, extractive, comparative, and validation tasks.

## File Map

| Report | File |
|---|---|
| Suite overview | `00_overview.md` |
| G04 Legal issue detection | `06_legal_issue_detection.md` (NEW) |
| G06 Doc analysis (real 280K) | `04_document_analysis_real_doc.md` (updated) |
| G07 Risk analysis | `02_risk_analysis.md` (updated) |
| G08 Contract comparison | `03_contract_comparison.md` (updated) |
| G09 Adversarial validator | `03_contract_comparison.md` (G09 section added) |
| P01 Bug investigation | `05_concurrency_bug_investigation.md` (resolved) |
| Original baseline | `01_document_analysis.md` (synthetic 138K reference) |
| **Final synthesis** | `07_final_generalization_report.md` (this document) |
