# C-2: Automatic task-class classification

## Parent

PRD: [`docs/prd/category-c-self-improving-loop.md`](../../prd/category-c-self-improving-loop.md) — see "Out of Scope" → "C-2 sub-grill must decide" + the C-2 row in "Proposed Slicing."

## Status: STUB — sub-grill required

This issue is a placeholder. Architectural decisions are open and must be resolved by a `/grill-me` sub-grill before implementation can begin. C-2 cannot be picked up as AFK work in its current form.

## What to build (sketch)

Replace C-1's **manual `:task-class-id` assignment** with an **automatic classifier** that computes the same UUID from task features. Storage layer remains UNCHANGED — same `record-tree-strength`/`record-tree-weakness` commands, same `:tree-id` field, same `get-tree-profile` API. The only change is *who* picks the UUID: in C-1 the user picks; in C-2 a classifier computes it.

The user-facing API stays:

```clojure
;; C-1 (current):
(orc/repl-researcher "researcher"
  :rlm {:recursive? true
        :task-class-id #uuid "doc-extraction-class-uuid"   ; user picks
        :principles-fn (fn [_] ...)})

;; C-2 (target):
(orc/repl-researcher "researcher"
  :rlm {:recursive? true})
;; executor computes :task-class-id automatically from node features
;; (reads, writes, instruction text, possibly input-profile from O03)
```

Hand-authored seed principles from C-1 keep working because the classifier produces UUIDs deterministic from features — when the user originally hand-assigned a UUID for "doc-extraction", the classifier produces the same UUID for any task whose features match doc-extraction.

## Open decisions (require sub-grill)

The PRD's "Open decisions deferred to sub-grills" section enumerates:

1. **Fingerprint computation strategy:**
   - Hash of features (simple, deterministic, but renames break matching)?
   - Structured-tag mapping (manual classification rules; LLM-discovered tags)?
   - LLM classifier (one extra LLM call per RLM tick; semantic but costs tokens)?
   - Hybrid (cheap hash for exact match + LLM fallback for fuzzy)?

2. **Which features go into the fingerprint:**
   - Just `:reads` + `:writes` key names?
   - Plus instruction text (lexical similarity)?
   - Plus instruction class (via LLM classification)?
   - Plus the O03 `:input-profile` data once a tick has actually run (post-hoc tagging)?

3. **Deterministic vs stable-but-relaxed:**
   - Same input → same UUID always (pure hash)?
   - Allow tolerance (e.g., `:writes [:summary :entity-list]` and `:writes [:summary :entities]` match because they're semantically similar)?
   - How is tolerance specified — embedding distance, structural similarity, or explicit alias tables?

4. **Backward compatibility with C-1 hand-authored UUIDs:**
   - Does the classifier need to "learn" the hand-assigned UUIDs so it produces them for matching tasks?
   - Or does C-2 introduce a different UUID space, with a migration step that re-keys C-1's hand-authored principles?

5. **When does classification happen:**
   - At node-build time (once, deterministic)?
   - At each tick (so the classifier sees runtime context)?
   - Cached after first run, invalidated on what?

## Acceptance criteria (high-level — refined in sub-grill)

The sub-grill must produce concrete acceptance criteria. Sketch:

- [ ] Repl-researcher nodes work without manual `:task-class-id` assignment (it's computed).
- [ ] Manual `:task-class-id` assignment STILL WORKS if the user wants to override.
- [ ] Hand-authored seed principles from C-1 are retrievable via the computed UUID for matching tasks.
- [ ] Classification is deterministic for identical inputs (or the relaxation rules are documented).
- [ ] Live-verify: classifier produces matching UUIDs for tasks that should match (positive case) and different UUIDs for tasks that should NOT match (negative case).

## Critical project rules

1. C-2 does NOT change C-1's storage shape. The UUID semantics and `:tree-id` field stay identical.
2. Live-verify is mandatory before declaring done.
3. The classifier itself must be adversarially audited — does it actually produce the right matches? Per "infrastructure existence is not quality."

## Blocked by

- [C-1 — Principle storage + retrieval + injection](C-1-principle-storage-retrieval-injection.md)

C-1 must validate that the principle shape and storage layer work (HITL audit + 3-way live-verify pass) before C-2 starts. Otherwise C-2 automates a key for a shape that might still change.

## Cross-references

- Parent PRD: [`docs/prd/category-c-self-improving-loop.md`](../../prd/category-c-self-improving-loop.md)
- Prior data layer: O03 `:input-profile` (chunk shape, byte/word counts) is a candidate feature for the classifier — see [`docs/prd/phase2-observability-layer.md`](../../prd/phase2-observability-layer.md)
- Existing ontology classifier scaffolding (may or may not be reusable): `components/ontology/src/.../core/classifier.clj`
