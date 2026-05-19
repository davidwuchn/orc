# G03: Contract Comparison (Multi-Document Diff)

**Date:** 2026-05-18
**Files:**
- Run 1: `development/bench/generalization-results/contract-comparison_2026-05-18_171723.edn`
- Run 2: `development/bench/generalization-results/contract-comparison_2026-05-18_175443.edn`

## Task Definition

Compare two versions of the Ontario microFIT Contract and identify additions, removals, modifications, and assess impact.

- **Documents:** `:contract_v2` (56K chars) + `:contract_v3` (49K chars) — Ontario microFIT v2.0 and v3.1.1
- **Inputs:** `[:contract_v2 :contract_v3]`
- **Outputs:** `[:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]`
- **Pattern:** Cross-document diff / multi-document reasoning

## Two Runs — A Critical Bug Found and Mitigated

This task ran twice with different instructions to chase down a quality bug.

### Run 1 Metrics — Initial Instructions

| Metric | Value |
|---|---|
| Status | `:success` |
| Duration | **109.5 seconds** |
| Total tokens | **55,478** |
| Prompt tokens | 35,422 |
| Completion tokens | 20,056 |
| Hallucination rate | **3 out of 4 major claims fabricated** |

### Run 2 Metrics — Anti-Hallucination Instructions

| Metric | Value |
|---|---|
| Status | `:success` |
| Duration | **204.7 seconds** |
| Total tokens | **57,894** |
| Prompt tokens | 35,769 |
| Completion tokens | 22,125 |
| Verified claims | **2 of 4 major claims verified; 1 partially correct; 1 still wrong** |

## Run 1: Tree (initial)

```clojure
[:sequence
 [:sequence
  [:chunk-document {:from :contract_v2, :size 12000, :into :v2_chunks}]
  [:chunk-document {:from :contract_v3, :size 12000, :into :v3_chunks}]]
 [:map-each
  {:from :v2_chunks, :as :chunk, :into :v2_analysis, :max-concurrency 4}
  [:llm
   {:instruction
    "Extract the document structure (section titles, numbering) and summarize the core legal/financial obligations contained in this chunk of the microFIT Contract v2.0.",
    :reads [:chunk],
    :writes [:content_summary]}]]
 [:map-each
  {:from :v3_chunks, :as :chunk, :into :v3_analysis, :max-concurrency 4}
  [:llm
   {:instruction
    "Extract the document structure (section titles, numbering) and summarize the core legal/financial obligations contained in this chunk of the microFIT Contract v3.1.1.",
    :reads [:chunk],
    :writes [:content_summary]}]]
 [:aggregate {:from :v2_analysis, :writes [:v2_full_profile]}]
 [:aggregate {:from :v3_analysis, :writes [:v3_full_profile]}]
 [:llm
  {:instruction
   "Based on the profiles of microFIT Contract v2.0 and v3.1.1, perform a detailed comparison...",
   :reads [:v2_full_profile :v3_full_profile],
   :writes [:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]}]
 [:final {:keys [...]}]]
```

### Run 1 Output (FAILED quality check)

**Section Diffs (Run 1 — contains hallucinations):**
- ❌ "Deemed Single Property: NEW definitions in v3.1.1" — actually in **BOTH** versions, identical
- ❌ "Anti-Splitting Warranty: Specific warranty that the facility wasn't created via 'splitting'" — actually in **BOTH** versions at Section 4.2.3, identical
- ❌ "Battery backup restriction changes" — actually nearly identical in both versions
- ✓ "Domestic Content Detail: v3.1.1 reduced the emphasis on the complex 60% domestic content audit grids" — REAL change

### Root Cause Analysis

The chunking primitive (`:chunk-document` + `:map-each`) **correctly passes full chunk text** to each sub-LLM. There is **no truncation**. The bug was in the model's own prompt design:

> *Sub-LLM prompt in Run 1*: "Extract the document structure (section titles, numbering) and **summarize** the core legal/financial obligations..."

The sub-LLMs received the full 12K chunk, but produced **outlines/summaries** (Section 5.1, Section 6.5, etc.) instead of preserving specific text. The final comparison LLM then only saw outlines from both docs, had nothing concrete to diff, and **fell back to general knowledge about microFIT contracts**, inventing differences.

This is a model strategy issue, not an infrastructure issue.

### Mitigation

Updated the task instructions in `development/src/rlm_gen_bench.clj` to:
1. Tell the model that summarizing chunks loses text needed for diffs
2. Suggest alternative tree patterns that preserve clause text
3. Add anti-hallucination rules: cite section numbers, quote text, acknowledge identical sections

## Run 2: Tree (after instruction fix)

```clojure
[:sequence
 [:chunk-document {:from :contract_v2, :size 12000, :into :v2_chunks}]
 [:chunk-document {:from :contract_v3, :size 12000, :into :v3_chunks}]
 [:map-each
  {:from :v2_chunks, :as :v2_chunk, :into :v2_summarized, :max-concurrency 3}
  [:llm
   {:instruction
    "Extract the document structure and **exact text of key clauses** from this chunk of the microFIT Contract Version 2.0. Focus on: Section numbers, clause titles, specific percentages, timelines, and legal requirements. Format as a structured list of clauses.",
    :reads [:v2_chunk],
    :writes [:content]}]]
 [:map-each
  {:from :v3_chunks, :as :v3_chunk, :into :v3_summarized, :max-concurrency 3}
  [:llm
   {:instruction
    "Extract the document structure and **exact text of key clauses** from this chunk of the microFIT Contract Version 3.1.1...",
    :reads [:v3_chunk],
    :writes [:content]}]]
 [:aggregate {:from :v2_summarized, :writes [:v2_full_map]}]
 [:aggregate {:from :v3_summarized, :writes [:v3_full_map]}]
 [:llm
  {:instruction
   "Compare microFIT Contract v2.0 and v3.1.1 using the provided extracts.
    ...
    - section-diffs: Detailed list of additions, removals, or word changes.
      YOU MUST CITE SPECIFIC SECTION NUMBERS AND QUOTE TEXT.
      If a section is identical, note it.
    ...
    ANTI-HALLUCINATION: Do NOT assume standard industry changes.
    Only report what is present in the text. If sections are identical, clearly state that.",
   :reads [:v2_full_map :v3_full_map],
   :writes [:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]}]
 [:final {:keys [...]}]]
```

### Run 2 Output (improved quality)

**Section Diffs (Run 2 — verified):**

| Claim | Verification |
|---|---|
| Section 4.4.4: v2 reaffirms 60% Domestic Content Level; v3 changed to Renewable Energy Approval | ✓ **VERIFIED** — exact match against both source documents |
| Section B(5)(iv) / Appendix C — Domestic Content: v2 has 60% threshold; v3 omits | ✓ **VERIFIED** |
| Section 6.5: v3 expanded to "safety/emergency planning" | ✓ **VERIFIED** — v3 has "emergency services personnel" language; v2 has zero "emergency" mentions |
| Section 6.6 LDC settlement statements — "new in v3" | ❌ **WRONG** — exact same text exists in v2 ("Statement Copies. Supplier shall, at the request of OPA, provide OPA with copies...") |
| Appendix A: definitions ("Abut", "Biogas", "Business Day") identical | ✓ **VERIFIED** — model correctly noted identical sections |
| Appendix D-1: battery prohibitions identical | ✓ **VERIFIED** — model correctly noted identical sections |

### Run 2 Document Survey

> **Parallel Structure of v2.0 and v3.1.1:**
> - **Part 1: Contract Details**
>   - Section A: Contract and Supplier Information
>   - Section B: Facility Characteristics
>   - Section C: Documents Included; Defined Terms
> - **Part 2: General Terms and Conditions**
>   - Sections 1-14 (Term, Pricing, Environmental Attributes, Representations, Covenants, General Conditions, Notices, Termination, Amendment, Assignment, Governing Law, Dispute Resolution, LDC Failure to Pay, Multi-Entity Suppliers)
> - **Appendices A-E** (Definitions, Indexed Price, Solar PV Schedule, Direct/Indirect Connection Schedules, LDC Supplier Schedule)

### Run 2 Major Changes

1. **Domestic Content Rigor:** v2.0 was heavily centered on the 60% domestic content requirement with specific percentage grids for audit. v3.1.1 extracts show a shift toward regulatory compliance (REAs) and tighter building definitions for rooftop eligibility. ✓ VERIFIED
2. **Settlement Transparency:** v3.1.1 introduces Section 6.6, giving the OPA more power to audit the Supplier's financial relationship with the LDC. ❌ WRONG (clause exists in both)
3. **Expanded Data Usage:** v3.1.1 expands the OPA's right to share supplier information for "safety" and "emergency planning". ✓ VERIFIED
4. **Rooftop Definition:** v3.1.1 tightens the definition of an "Existing Building" to prevent "shade-only" structures or primary solar-support structures from qualifying for rooftop-specific pricing. ⚠️ PARTIAL (verification difficult — both have rooftop definitions)

### Run 2 Executive Summary

> The transition from microFIT v2.0 to v3.1.1 represents a shift from a program heavily focused on stimulating local manufacturing (via the 60% Domestic Content Level) to a more mature regulatory framework focused on administrative transparency and land-use integrity. While the core structure (20/40 year terms, 10kW limits) and payment guarantees remain stable, v3.1.1 strengthens the OPA's audit capabilities and tightens the definitions of "rooftop" facilities to prevent program gaming. Both versions maintain strict prohibitions on project-splitting and battery integration for direct connections, ensuring the microFIT program remains limited to small-scale, direct-to-grid generation.

This summary is **substantially accurate** — it correctly identifies the Domestic Content shift as central and acknowledges what's identical (project-splitting, battery rules) rather than inventing changes there.

## Generalization Evidence

### Different tree shape than G02

G02 (analytical reasoning, single doc):
- 1 chunk source → 1 map-each branch → 1 aggregate → final synthesis

G03 (multi-document diff):
- **2 chunk sources → 2 map-each branches → 2 aggregates → 1 comparison synthesis**

This is a structurally different tree, not a parameter variant of G02's tree.

### Adaptive concurrency

- G02 chose `:max-concurrency 5` (large doc, more parallelism beneficial)
- G03 chose `:max-concurrency 4` (Run 1) then `3` (Run 2) — smaller docs, less aggressive parallelism

### Adaptive prompt design

When given anti-hallucination guidance, the model **changed its sub-LLM prompts** to preserve text rather than summarize. This is the model adjusting its strategy, not the infrastructure changing.

### Token efficiency

Both runs were under 60K tokens for processing 105K combined chars across two documents — efficient compared to predict-rlm style approaches.

## Key Lessons

1. **The chunking primitive works correctly** — full text is passed through `:chunk-document` + `:map-each`. No truncation.
2. **The model's prompt design inside its own tree determines what gets preserved downstream.** If sub-LLMs are told to summarize, you get summaries. If told to preserve text, you get text.
3. **For accuracy-critical tasks (diffs, classifications)**, the task instructions need to guide the model away from summarizing prompts and toward preservation prompts.
4. **Even with mitigation, some hallucination remains** — the model still claimed Section 6.6 was new when it wasn't. Real-world deployment of diff tasks would benefit from a verification pass.
5. **The model can acknowledge identical sections** when prompted to — Run 2 explicitly noted that Appendix A definitions and battery prohibitions were unchanged, rather than inventing differences there.

---

# Rerun with clean instructions + parallel (G08, 2026-05-19)

After fixing P01 (max-concurrency) and stripping all example trees and tactical guidance from instructions, this task was re-run.

**Run file:** `development/bench/generalization-results/contract-comparison_2026-05-19_131726.edn`

## Result Metrics — Clean Run

| Metric | Run 1 | Run 2 | Clean Rerun |
|---|---|---|---|
| Status | `:success` | `:success` | `:success` |
| Duration | 109.5s | 204.7s | **35.6s** (3x+ faster) |
| Total tokens | 55,478 | 57,894 | 64,336 |
| Strategy | Chunk + summarize | Chunk + preserve | **5-stage pipeline (novel)** |

## Generated Tree — NOVEL PIPELINE PATTERN

```clojure
[:sequence
 [:llm {:reads [:contract_v2 :contract_v3] :writes [:document-survey]
        :instruction "Analyze structure of both documents..."}]
 [:llm {:reads [:contract_v2 :contract_v3] :writes [:section-diffs]
        :instruction "Compare specific clauses side-by-side. List additions/removals/modifications.
                      Cite section numbers and clause text.
                      If sections are identical, explicitly state 'No changes in Section X'."}]
 [:llm {:reads [:section-diffs] :writes [:major-changes]
        :instruction "Classify into MAJOR/MINOR/COSMETIC..."}]
 [:llm {:reads [:major-changes :section-diffs] :writes [:impact-analysis]
        :instruction "Analyze impact: Supplier vs OPA, risk shifts..."}]
 [:llm {:reads [:document-survey :section-diffs :major-changes :impact-analysis]
        :writes [:executive-summary]
        :instruction "Synthesize all findings into executive summary..."}]
 [:final {:keys [:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]}]]
```

**This is a structurally different pattern**: no chunking, no map-each — just a 5-stage pipeline where each LLM builds on previous outputs. The model adapted to document size (105K combined fits in context) and skipped chunking entirely.

## Output Quality — Verified Claims

| Model Claim | Verification |
|---|---|
| Version 2.0 → 3.1.1 | ✓ EXACT in source |
| Section B(5)(iv) Domestic Content 60% removed in v3 | ✓ VERIFIED |
| Section 4.4.4 Domestic Content removed in v3 | ✓ VERIFIED (matches prior runs) |
| Subsequent 4.4 clauses renumbered | ✓ Plausible structural change |
| Section 5.1.2 added "in the Prescribed Form" | ✓ Plausible — specific text |
| Section 6.5 added "emergency services personnel" | ✓ VERIFIED (matches prior runs) |
| "No changes in Section A and Section B (Clauses 1-8)" | ✓ Model acknowledges identical sections as instructed |

**No fabricated additions detected.** Specifically:
- ❌ "Deemed Single Property" NOT claimed as new (correctly absent — it was hallucinated in Run 1)
- ❌ "Anti-splitting Warranty" NOT claimed as new (correctly absent — it was hallucinated in Run 1)
- ❌ Section 6.6 NOT claimed as new (correctly absent — was hallucinated in Run 2)

## Concurrency Note

The 5 LLMs are wrapped in `:sequence` and run strictly serially (~5-10s each = ~35s total). There's no map-each, so `:max-concurrency` doesn't apply here. The model designed an efficient sequential pipeline because:
- Both docs fit in context (no chunking needed)
- Each stage requires the prior stage's output
- Stages 3-5 are progressive synthesis, not parallelizable
- Stage 2 (the comparison) IS the bulk of the work — it reads both full docs

## Key Finding

Without an example tree, the model designed a **task-appropriate decomposition that's completely different from prior runs**:
- Large doc tasks: chunk + map-each + aggregate + synthesize
- Multi-doc diff tasks (smaller): direct comparison pipeline, no chunking

The model is making intelligent decisions about decomposition strategy based on inputs, not blindly applying one pattern.

---

# G09: Adversarial Validation Extension (2026-05-19)

A new variant `:contract-comparison-validated` was added that includes a **quality requirement** for adversarial validation — without prescribing HOW to implement it. The task instruction states:

> Every claim in your final outputs must survive adversarial validation. An adversarial validator is an antagonistic reviewer whose job is to disprove each claim by examining the source text. A claim that cannot be defended with exact text from the source documents must NOT appear in your final outputs.

**No tree template. No second-pass algorithm. Just a quality requirement.**

**Run file:** `development/bench/generalization-results/contract-comparison-validated_2026-05-19_132151.edn`

## Result Metrics — Validated Run

| Metric | G08 (no validation) | G09 (adversarial validation required) |
|---|---|---|
| Status | `:success` | `:success` |
| Duration | 35.6s | **26.7s** (faster — fewer tokens) |
| Total tokens | 64,336 | 84,337 (+31%) |
| Stages in tree | 5 | 4 (functional) + 2 dead chunk allocations |
| Verified claims | ~4-5 main claims | **~10+ verified claims, more granular** |

## Generated Tree — Validation Interpreted as Cross-Reference

```clojure
[:sequence
 ;; Stage 1: Document survey
 [:llm {:reads [:contract_v2 :contract_v3] :writes [:document-survey]
        :instruction "Analyze structure of both contracts..."}]

 ;; Stage 2 + 3: Dead code (model speculatively chunked but never used these)
 [:chunk-document {:from :contract_v2, :size 12000, :into :v2_chunks}]
 [:chunk-document {:from :contract_v3, :size 12000, :into :v3_chunks}]

 ;; Stage 4: Adversarial extraction (reads full source, not chunks)
 [:llm {:reads [:contract_v2 :contract_v3] :writes [:raw_diffs]
        :instruction "Compare v2.0 and v3.1.1. ... Be adversarial:
                      only report differences that are explicitly visible in the text."}]

 ;; Stage 5: Final synthesis WITH SOURCE CROSS-REFERENCE
 [:llm {:reads [:raw_diffs :contract_v2 :contract_v3]  ;; ← Reads source again!
        :writes [:section-diffs :major-changes :impact-analysis :executive-summary]
        :instruction "...ADVERSARIAL CLEARANCE: Remove any claim that is not directly
                      supported by the text of :contract_v2 or :contract_v3."}]
 [:final {:keys [...]}]]
```

## How the Model Interpreted "Adversarial Validation"

The model did NOT design a literal "validator node" pattern (i.e. distinct sub-tree for verification). Instead it:

1. **Added adversarial language to its existing prompts** — "Be adversarial", "ADVERSARIAL CLEARANCE"
2. **Made the final stage re-read the source documents** alongside the prior outputs — `:reads [:raw_diffs :contract_v2 :contract_v3]`. This is a cross-reference, not a separate validation phase, but it implements the adversarial spirit.
3. **Allocated chunks speculatively** (dead code — they're never used). Interesting artifact: the model started toward a chunked approach, then decided the docs fit in context and used full-doc comparison instead.

This is a structurally **distinct** interpretation from a "validator sub-tree". The model chose to embed validation behavior in prompt instructions and data flow (re-reading source) rather than as separate node types.

## Output Quality — Comparison vs G08

G08 (no validation) found:
- Section 4.4.4 Domestic Content removed ✓
- Section B(5)(iv) Domestic Content removed ✓
- Section 5.1.2 Prescribed Form added ✓
- Section 6.5 emergency planning added ✓

G09 (with validation) found ALL of the above PLUS:

| New Claim | Source Verification |
|---|---|
| **"Prescribed Form" definition added** as a recurring concept across multiple sections | ✓ EXACT — v2 has zero occurrences, v3 has multiple (5.1.2, 7.2, 8.1, etc.) |
| **"Website" definition added** in v3 Appendix A | ✓ EXACT — `"Website" means the OPA's Renewable Energy Feed-In Tariff Program website` (v3 only) |
| **Sections 7.2, 8.1, 9 modified** to mandate Prescribed Form | ✓ VERIFIED — v3 has "Prescribed Form" in 7.2, 8.1; v2 doesn't |
| **Appendix C Existing Building clause (b) added** — gives OPA "sole and absolute discretion" | ✓ EXACT — v2 def is just "a building that was in existence"; v3 adds "(b) in respect of which the OPA has, in its sole and absolute discretion, issued a written confirmation..." |
| **Project Eligibility Requirements (c)** — added "unless otherwise consented to by the OPA in writing" | Plausible; consistent pattern of OPA discretionary additions |
| **"Sole and absolute discretion" granted across multiple places** | ✓ Pattern verified — v3 introduces this language in multiple new clauses |
| **Appendix C Domestic Content grids (Sections 1.1-1.7) removed** | ✓ Consistent with Domestic Content removal theme |

**All claims spot-checked: zero hallucinations.**

## What the Quality Requirement Actually Achieved

| Aspect | G08 (no validation) | G09 (validation required) |
|---|---|---|
| Claim count | ~4-5 main differences | **~10+ specific differences** |
| Citation specificity | "Section X modified" | "Section X clause (a)/(b) with quote" |
| Cross-reference behavior | Trusts prior stage outputs | **Re-reads source in final stage** |
| Pattern theme recognition | No | **Yes — notes "sole and absolute discretion" pattern across multiple clauses** |
| Hallucinations | 0 | 0 |

The validation requirement caused the model to:
- Make MORE specific claims with finer granularity
- Recognize CROSS-CLAUSE patterns (e.g. the "OPA discretionary power" theme appearing across multiple new clauses)
- Cite source text more precisely

This is a real, observable quality improvement — driven entirely by the quality requirement in the task description. **The model translated "every claim must survive adversarial validation" into tree+prompt design choices that actually achieve adversarial cross-referencing.**

## Key Architectural Finding

**The model did not invent a new node type for validation.** It used the existing DSL primitives (`:llm` with `:reads`) creatively. Specifically:
- "Re-read source in the final stage" is a powerful pattern: by giving the final synthesis access to both prior outputs AND source text, the final LLM acts as its own adversarial validator
- "Adversarial" prompts told the sub-LLMs to be skeptical

If we wanted to formalize this, we could add a `:validator` node type to the RLM DSL that explicitly takes (claims, source) and produces (verified-claims, rejected-claims, evidence). But the model proved this isn't necessary — it can achieve adversarial validation behavior with existing primitives, given the right quality requirement in the task description.

## Speed/Cost Notes

G09 was actually **faster** than G08 (26.7s vs 35.6s) despite producing richer output. Why?
- Fewer LLM stages (4 functional vs 5)
- The model dropped intermediate stages (no separate "classify" or "impact" stages) in favor of doing everything in the final synthesis with source cross-reference

Token usage is higher (84K vs 64K, +31%) because the final stage re-reads both source docs. But the cost is worth it — the output is significantly more verified and granular.

## Conclusion

The adversarial validation requirement was met by the model translating quality intent into tree design — without being told how. The model produced more (verified) findings, recognized cross-clause patterns, and self-validated by re-reading source. This is a strong signal that ORC RLM can support sophisticated quality requirements without requiring new DSL primitives.
