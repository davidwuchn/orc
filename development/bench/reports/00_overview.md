# ORC RLM Generalization Test Suite — Overview

**Test date:** 2026-05-18
**Model:** google/gemini-3-flash-preview
**Provider:** OpenRouter

## Goal

Prove that ORC RLM (Repl Researcher with `emit-tree!`) is genuinely adaptive — that the same engine can generate **fundamentally different behavior trees** for **fundamentally different task types** without hardcoding.

If we only ran one demo task (document analysis) we couldn't tell whether:
- (a) The RLM is generalizable, OR
- (b) The demo's tree pattern is overfit and the model just repeats it

This suite stress-tests with deliberately different task patterns.

## Tasks

| ID | Task | Pattern | Doc Size | Result |
|----|------|---------|----------|--------|
| Baseline | Document Analysis | Extraction (single doc) | 138K chars | Success — see [01_document_analysis.md](01_document_analysis.md) |
| G02 | Risk & Obligation Analysis | Analytical reasoning (single doc) | 280K chars | Success — see [02_risk_analysis.md](02_risk_analysis.md) |
| G03 | Contract Comparison | Cross-document diff (multi-doc) | 105K combined | Success after iteration — see [03_contract_comparison.md](03_contract_comparison.md) |
| G04 | Legal Issue Detection | Analytical reasoning (small doc) | 7K chars | Pending |

## Summary of Findings

### What the engine does well
- **Adaptive decomposition**: Chooses chunk size and parallelism based on task and document size
- **Adaptive sub-LLM prompts**: Writes task-specific instructions for the sub-LLMs inside the tree
- **Multi-document workflows**: Generates trees with separate processing branches per document
- **Different output schemas**: Selects appropriate output keys for each task semantics

### What requires careful prompt design
- **Hallucination risk on diff tasks**: When asked to compare nearly-identical documents, the model can invent differences if its own sub-LLM prompts only produce summaries (losing the text needed for accurate diffs)
- **Mitigation**: Instructions need to nudge the model to preserve specific text and acknowledge identical sections

## Key Architectural Insight

The `:chunk-document` + `:map-each` primitives correctly pass full chunk text to sub-LLMs (no truncation). What gets preserved through the tree depends on the **sub-LLM prompts the model designs**. If those prompts say "summarize", summaries flow downstream. If they say "preserve exact text", the text flows downstream.

This means the model's autonomy in designing internal prompts is both the **source of its adaptability** AND a **place where guidance matters** for accuracy-sensitive tasks.
