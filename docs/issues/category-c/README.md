# Category C — Self-Improving Loop

**Parent PRD:** [`docs/prd/category-c-self-improving-loop.md`](../../prd/category-c-self-improving-loop.md) (local)

**Status:** Local issue tracking — these files live in the repo (committed). C-1 is fully scoped and ready for an AFK implementation run. C-2 / C-3 / C-4 are stubs requiring sub-grills before implementation.

## Issue List

| # | Title | Type | Blocked by |
|---|-------|------|------------|
| C-1 | [Principle storage + retrieval + injection](C-1-principle-storage-retrieval-injection.md) | AFK | None |
| C-2 | [Automatic task-class classification](C-2-task-class-classification.md) | HITL (sub-grill required) | C-1 |
| C-3 | [Automated principle extraction + judges audit](C-3-automated-extraction-judges-audit.md) | HITL (sub-grill required) | C-1 |
| C-4 | [Cross-tree pattern reuse + node-type learning](C-4-cross-tree-pattern-reuse.md) | HITL (sub-grill required) | C-1, C-3 |

## Dependency Graph

```
C-1 (principle storage + retrieval + injection)
 ├─→ C-2 (automatic task-class classification)
 │       └─ replaces manual :task-class-id; storage layer unchanged
 │
 ├─→ C-3 (automated principle extraction + judges audit)
 │       └─ writes into C-1's storage shape; adapts GEPA proposer
 │
 └─→ C-4 (cross-tree pattern reuse + node-type learning)
         └─ also depends on C-3; ontology-wide retrieval
```

## Priority — C-1 unblocks the rest

C-1 ships the full pipeline (storage + retrieval + injection) with **hand-authored seed principles** and a rigorous **3-way live-verify**. The HITL audit checkpoint inside C-1 validates that the principle SHAPE is actually useful — what the model receives demonstrably moves its behavior. **All downstream slices write or read against the shape C-1 commits to**, so getting C-1 right is foundational.

C-2 / C-3 / C-4 are HITL because each has open architectural decisions that require a sub-grill before they can be implemented. See each issue's "Open decisions" section.

## Sequence Rules

- Each slice is end-to-end testable on its own and leaves the branch in a runnable state.
- Each slice ends with a **live real run** (real LLM, real event store, real Grain pipeline) confirming production-truth — per the project's verification rule.
- All event emissions flow through proper Grain commands (commands emit events; processors react). No direct event-store writes.
- C-1's `:principles-fn` and `:task-class-id` are optional opt-in config on the repl-researcher node — existing trees without these set get zero behavior change.
- Framework tests must remain green on the RLM-related namespaces.
- After each slice verifies live, framework changes land on `main` directly (R-2 / `:code` port pattern; no separate port step needed).

## Why this order

**C-1 first** because:
- It defines the principle storage shape — `:context-conditions`, `:action-taken`, `:expected-outcome`, `:evidence-trace-ids` — that every later slice writes or reads.
- It validates (via the HITL audit + 3-way live-verify) that the shape is *useful* — that the model demonstrably acts on hand-authored principles. This validation is required BEFORE we automate principle generation (C-3); otherwise C-3 produces automated noise into a shape that doesn't help.
- It surfaces the retrieval-and-injection layer (`:principles-fn` hook + new "Principles for this task class" prompt section) that all downstream slices' principles flow through.

**C-2 second** because:
- It removes the friction of manual `:task-class-id` assignment by computing it from task features.
- It does NOT change the storage layer — same UUID, same record-tree-strength/weakness commands. Just adds a classifier that populates the same key automatically.
- Sub-grill needed: hash-of-features vs. structured-tag vs. LLM classifier vs. hybrid; how O03 input-profile features factor in.

**C-3 third** because:
- It automates principle generation — wires `:tree-results` entries through `evaluation/evaluate-trace`, adapts GEPA's `propose-new-instruction` for principle extraction.
- Includes the adversarial audit of the existing 4 rubric-based judges (plan-doc issue 011): do they produce signal useful for tree-structure decisions, or are they too output-content-focused?
- May add structural judges (tree-shape quality, efficiency).
- Sub-grill needed: GEPA reuse strategy, judge audit findings, structural-judge necessity, when-to-trigger-reflection.

**C-4 last** because:
- It broadens retrieval to node-type-level principles (FUTURE-VISION 4a.6) and ontology-wide pattern discovery (4a.10).
- Builds on C-3's automated extraction to populate the ontology automatically.
- Sub-grill needed: node-type principle storage shape, retrieval composition across signals, anti-pattern handling.

## Cross-references to prior work

Category C builds on:

- [`docs/prd/rlm-recursive-emit-tree.md`](../../prd/rlm-recursive-emit-tree.md) — R-1 + R-2 (DONE). Provides the `:tree-results` accumulating vector + drill-down primitives. This IS the data layer Category C learns from.
- [`docs/prd/phase2-observability-layer.md`](../../prd/phase2-observability-layer.md) — O01-O03 (DONE). Per-node usage, `:input-profile`, trajectory bookend. The per-tree evidence C-3 will consume.
- [`docs/prd/category-d-resilience.md`](../../prd/category-d-resilience.md) — D-008 + D-003 (DONE). `:partial-summary`, budget-aware Phase 2 timeout. The failure-mode evidence sources hand-authored principles in C-1 will reference.
- [`docs/FUTURE-VISION.md`](../../FUTURE-VISION.md) — Phase 4a Ontology Knowledge System (Anterior-inspired); B.3 tree profile shape.
- [`docs/SELF-LEARNING-MANUAL.md`](../../SELF-LEARNING-MANUAL.md) — Existing `:ontology/record-tree-strength`/`record-tree-weakness` command surface that C-1 reuses verbatim.
- [`docs/FEEDBACK-LOOP.md`](../../FEEDBACK-LOOP.md) — The 7-stage continuous improvement cycle. C-1 wires stages 5-6; C-3 wires stages 2-4.
- [`docs/GEPA-INTEGRATION-PLAN.md`](../../GEPA-INTEGRATION-PLAN.md) — Existing GEPA proposer + reflection infrastructure (`gepa/core/reflection.clj`, `gepa/core/proposer.clj`). The reuse candidate for C-3's automated extraction.

## Out of scope (deferred or orthogonal)

- Personality layer (FUTURE-VISION Theme 7) — orthogonal.
- Conversational debugging (FUTURE-VISION Theme 5 / Phase 5) — orthogonal.
- Skills → Trees pipeline (FUTURE-VISION Theme 1 / Phase 6) — orthogonal.
- Tree hierarchy graph (FUTURE-VISION Theme 6 / Phase 3.4) — orthogonal.
- Rolling-metric threshold alerts and auto-analysis triggers (FUTURE-VISION Phase 2.3-2.4) — covered by separate rolling-metrics work.
- TTL/SKOS export of the ontology (FUTURE-VISION 4a.7) — already implemented.
