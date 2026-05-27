# Category C — Self-Improving Loop

**Parent PRDs:**
- [`docs/prd/category-c-self-improving-loop.md`](../../prd/category-c-self-improving-loop.md) — Category C overview
- [`docs/prd/category-c-2-living-descriptions.md`](../../prd/category-c-2-living-descriptions.md) — C-2 (Living Descriptions) deep PRD

**Status:** Local issue tracking — these files live in the repo (committed to `feature/core-orc-upgrades`).
- **C-1 — SHIPPED** (live-verified; `:context` plumbing + `format-rich-pattern` extension + 1 hand-authored principle proven load-bearing in a 3-way comparison).
- **C-2** has been expanded into a multi-slice initiative — see C-2 parent + the C-2a / C-2b / C-2c sub-issues.
- **C-3** and **C-4** remain HITL stubs awaiting their own sub-grills.

## Issue List

| # | Title | Type | Status | Blocked by |
|---|-------|------|--------|------------|
| [C-1](C-1-principle-storage-retrieval-injection.md) | Principle storage + retrieval + injection | AFK | **SHIPPED** | — |
| [C-2](C-2-task-class-classification.md) | Living Descriptions (parent/index) | — | Expanded into sub-issues | C-1 |
| └ [C-2a-1](C-2a-1-static-descriptions-and-seeds.md) | Static description event types + read model + 18 hand-authored seeds | AFK | Next-action | None |
| └ [C-2a-2](C-2a-2-rolling-aggregator-extension.md) | Rolling aggregator extension (cross-sheet + tree-fingerprint) | AFK | Ready | None |
| └ [C-2a-3](C-2a-3-consolidator-and-reflection.md) | Consolidator + LLM reflection + anti-recency safeguards | AFK (HITL audit) | Pending | C-2a-1 + C-2a-2 |
| └ [C-2a-4](C-2a-4-live-verify.md) | Live-verify (3-run regression + seed-executability) | AFK | Pending | C-2a-1 + C-2a-2 + C-2a-3 |
| └ [C-2b](C-2b-multi-granularity-indexing.md) | Multi-granularity indexing + retrieval (ColBERT) | HITL (sub-grill) | Pending | C-2a complete |
| └ [C-2c](C-2c-classifier-api.md) | Automatic classifier API | HITL (sub-grill) | Pending | C-2a + C-2b complete |
| [C-3](C-3-automated-extraction-judges-audit.md) | Automated principle extraction + judges audit | HITL (sub-grill) | Pending | C-1 |
| [C-4](C-4-cross-tree-pattern-reuse.md) | Cross-tree pattern reuse + node-type learning | HITL (sub-grill) | Pending | C-1, C-3 |

## Dependency Graph

```
C-1 (SHIPPED)
 │
 ├──→ C-2a-1 (seeds + storage)  ─┐
 │                                ├──→ C-2a-3 (consolidator) ──→ C-2a-4 (live-verify) ──→ C-2b ──→ C-2c
 │    C-2a-2 (aggregator)      ─┘
 │
 ├──→ C-3 (judges + automated extraction)
 │
 └──→ C-4 (cross-tree pattern reuse) [also depends on C-3]
```

The C-2a-1 and C-2a-2 sub-slices are parallelizable (independent surfaces). C-2a-3 needs both. C-2a-4 gates the C-2a sub-category. C-2b and C-2c remain stubs awaiting their own sub-grills before implementation.

## Priority — C-2a-1 is the next-action slice

With C-1 shipped, the next concrete unit of work is **C-2a-1 — static description event types + read model + 18 hand-authored seeds**. This is the foundation everything else in C-2 depends on.

Recommended order for picking up Category C from here:

1. **C-2a-1** first — write the event types, the read model, the 18 seeds. HITL audit gates the seed quality.
2. **C-2a-2** in parallel — extend the rolling-metrics aggregator. Independent surface.
3. **C-2a-3** after 1 + 2 are green — the consolidator processor + LLM reflection.
4. **C-2a-4** — the live-verify orchestrator + adversarial gate for C-2a.
5. **C-2b** — sub-grill needed first; then implement.
6. **C-2c** — sub-grill needed first; then implement.
7. **C-3** — sub-grill needed first; can also be developed in parallel with later C-2 sub-slices.
8. **C-4** — sub-grill needed first; needs C-3 in place.

## Sequence Rules

- All Category C work stays on `feature/core-orc-upgrades` per the saved branch policy. No commits to main during Category C until the full initiative is reliable end-to-end.
- Each slice is end-to-end testable on its own and leaves the branch in a runnable state.
- Each slice ends with a **live real run** (real LLM, real event store, real Grain pipeline) confirming production-truth — per the project's verification rule.
- All event emissions flow through proper Grain commands (commands emit events; processors react). No direct event-store writes.
- Recursive RLM is the default everywhere — no terminal-mode-forcing in new seeds, examples, or live-verify scripts.
- Framework tests must remain green on the RLM-related namespaces.

## Why this order

**C-1 shipped first** because it defined the principle-shape contract — `:trait` + `:good-when`/`:avoid-when` + `:recommended-pattern`/`:recommended-alternative` + `:confidence` + `:evidence-count`. Every C-2 description entry reuses that shape, and the C-1 `format-rich-pattern` extension renders them in the model's prompt without further work.

**C-2 expanded** beyond the original "compute a task-class UUID" stub when the grill surfaced the broader vision: a self-description library spanning trees + nodes at three granularities (node-type, node-instance, tree-fingerprint), evolving from rolling judge feedback on the event store. See the C-2 PRD for the full architectural decisions.

**C-3 third** because:
- It automates principle generation — wires `:tree-results` entries through `evaluation/evaluate-trace`, adapts GEPA's `propose-new-instruction` for principle extraction.
- Includes the adversarial audit of the existing 4 rubric-based judges: do they produce signal useful for tree-structure decisions, or are they too output-content-focused?
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
- [`docs/prd/category-d-resilience.md`](../../prd/category-d-resilience.md) — D-008 + D-003 (DONE). `:partial-summary`, budget-aware Phase 2 timeout. The failure-mode evidence sources hand-authored principles in C-1 reference.
- [`docs/FUTURE-VISION.md`](../../FUTURE-VISION.md) — Phase 4a (Ontology Knowledge System) + Theme 6 (Tree Hierarchy & Self-Description) + Theme 9 (ColBERT/RAGatouille integration).
- [`docs/SELF-LEARNING-MANUAL.md`](../../SELF-LEARNING-MANUAL.md) — Existing `:ontology/record-tree-strength`/`record-tree-weakness` command surface that C-1 reuses verbatim and C-2 extends with description events.
- [`docs/FEEDBACK-LOOP.md`](../../FEEDBACK-LOOP.md) — The 7-stage continuous improvement cycle. C-1 wires stages 5-6; C-2's consolidator wires stages 2-4; C-3 enriches stages 2-4 further.
- [`docs/GEPA-INTEGRATION-PLAN.md`](../../GEPA-INTEGRATION-PLAN.md) — Existing GEPA proposer + reflection infrastructure. The reuse candidate for C-3's automated extraction.

## Out of scope (deferred or orthogonal)

- Personality layer (FUTURE-VISION Theme 7) — orthogonal.
- Conversational debugging (FUTURE-VISION Theme 5 / Phase 5) — orthogonal.
- Skills → Trees pipeline (FUTURE-VISION Theme 1 / Phase 6) — orthogonal.
- Tree hierarchy graph (FUTURE-VISION Phase 3.4) — orthogonal.
- Rolling-metric threshold alerts (FUTURE-VISION Phase 2.3-2.4) — covered by separate work.
- TTL/SKOS export of the ontology (FUTURE-VISION 4a.7) — already implemented.
