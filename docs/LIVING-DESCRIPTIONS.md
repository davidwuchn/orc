# Living Descriptions — how ORC trees and nodes learn to describe themselves

> **A running explainer.** This doc grows alongside the Category C work. It explains, in layered detail, how ORC builds up self-knowledge about the workflows it runs — what each tree and each node is good at, what it tends to fail at, and how those descriptions evolve over time as the system observes more events.
>
> Audiences: non-technical stakeholders (Part 1), developers using ORC (Part 2), implementers maintaining the system (Part 3).

## Part 1 — The 30-second story (for stakeholders)

ORC runs LLM-powered workflows as **behavior trees** — structured plans of LLM calls, code transforms, parallel branches, and so on. When the same kind of workflow runs many times, patterns emerge: certain tree shapes work well for certain tasks, certain nodes consistently produce good (or poor) results in certain contexts.

The **Living Descriptions** system captures those patterns automatically:

- Every tree and every node carries a self-description: *what it's good at, when it works well, when it tends to fail, and what to prefer or avoid in similar situations.*
- These descriptions are not written by humans (mostly). They're produced by an automated **reflection step** that periodically reads the recent execution events for that tree/node, compares them against history, and updates the description to reflect what was learned.
- A human-authored seed gets the system started (so it has some sensible starting descriptions on day one), but as workflows run, descriptions evolve from real evidence — successes climb in confidence; failures get principle-shaped lessons attached.
- When a developer designs a new workflow, the system can surface relevant prior descriptions as inspiration: *"a tree shaped like this has worked well before; here's what it tends to be good at."*

The result: ORC gets smarter over time without needing developers to babysit it.

## Part 2 — The developer mental model

If you're building or operating an ORC workflow, here's how to think about Living Descriptions:

### What a description looks like

Every entity — a node type (`:llm`, `:map-each`, etc.), a specific node in a specific workflow, or a tree shape (like "chunked-extraction") — has a description that looks roughly like this:

```clojure
{:capabilities ["chunks large documents", "extracts entities per chunk"]

 :strengths
 [{:trait "bounded :max-concurrency on :map-each over chunks"
   :good-when "input is a large chunked document"
   :recommended-pattern "[:map-each {... :max-concurrency 3} [:llm {...}]]"
   :confidence 0.92
   :evidence-count 45}]

 :weaknesses
 [{:trait "rate-limit exhaustion under unbounded parallelism"
   :avoid-when "input has >6 chunks AND :max-concurrency is unset"
   :recommended-alternative "set :max-concurrency to 3 on the :map-each"
   :confidence 0.6
   :evidence-count 8}]

 :representative-uses ["document-analysis benchmark", "legal-issue-detection"]
 :summary "When extracting from chunked documents, prefer bounded map-each concurrency."
 :version 4
 :consolidated-from-event-count 53}
```

Every strength and weakness is **principle-shaped**: a concrete trait, a context guard (when does it apply?), and actionable advice (a recommended pattern or alternative). Confidence weights the entry — high-confidence entries are surfaced to the model when designing new trees; low-confidence entries are tracked but hidden until evidence accumulates.

### Three granularities

| Granularity | What it groups | Example use |
|---|---|---|
| **node-type** | All instances of `:llm` (or `:map-each`, etc.) across all workflows | *"In general, what do `:map-each` nodes tend to do well?"* |
| **node-instance** | One specific node in one specific workflow | *"The 'validator' LLM in my report-generation workflow has been reliable in the last 50 runs."* |
| **tree-fingerprint** | Trees with the same structural shape (regardless of content) | *"Chunked-extraction trees with a final synthesizer step work well for medium-large docs."* |

### How descriptions evolve

Descriptions update through three signals:

1. **Hand-authored seeds** — at the start, an initial catalog of ~18 descriptions captures known patterns (the 6 basic node types + the 5 benchmark task classes + 7 generic tree shapes). These are reviewed for quality and ground every claim in real benchmark evidence.
2. **Cold-start LLM baseline** — when a brand-new entity appears (a new node instance, an unfamiliar tree shape), the system asks an LLM to write a low-confidence initial description based on the entity's structural shape. Marked at confidence 0.1 so it's invisible to retrieval until real evidence reinforces it.
3. **Rolling consolidation** — as the system observes execution events (successes, failures, and judge scores from the per-event evaluator runtime — see "Judge integration" below), a periodic reflection step updates the description. Stable patterns climb in confidence; anomalies appear as low-confidence-but-actionable entries; outdated entries decay and eventually auto-archive.

### What protects descriptions from over-reacting to recent runs

Four safeguards keep descriptions stable over time:

1. **Decoupled threshold and window.** Consolidation triggers when ~10 new events accumulate, but each consolidation reads the last 500 events — so a single bad burst doesn't reshape the description.
2. **Aggregate + delta in the prompt.** The reflection LLM sees all-time aggregate metrics alongside the recent window's slice — and is told explicitly: "update only when recent evidence is consistent AND substantial."
3. **Per-entry confidence with demotion-not-deletion.** New consistent evidence climbs an entry's confidence; contradicting evidence reduces it proportionally. Confidence below 0.2 hides the entry from retrieval but keeps it in the description; below 0.05, the entry archives to history.
4. **Anomalies are principle-shaped, not flags.** When recent metrics deviate from historical, the new entry is a low-confidence-but-actionable principle (concrete avoid-when + concrete recommended-alternative). Never "investigate" or "observed" placeholders.

### How developers use this

Most developers won't interact with the system directly — they just write their workflows normally, and the system uses recorded descriptions in two ways:

1. **Principle injection** (already shipped in C-1) — when a repl-researcher runs, relevant principles for its task class are prepended to its instruction.
2. **Cross-task retrieval** (C-2b, in progress) — when the model designs a new tree, it can semantically query for similar prior trees or nodes as inspiration.

For developers who want explicit control:

- Set `:context {:tree-id <uuid> :self-learning? true ...}` on a `repl-researcher` to opt into principle injection for a specific task class.
- Read the current description for any tree or node via the ontology API (`ontology/get-description`).
- Inspect a description's history to see how it evolved over time.

## Part 3 — Implementer's view (architecture + status)

### Architecture

```
EVENT STREAM (existing) — :sheet/node-execution-completed,
  :sheet/rlm-tree-execution-completed, :judge/score-emitted (when judges land)
        ↓
ROLLING AGGREGATOR (extends existing rolling-metrics)
  per-node-instance | per-node-type (cross-sheet) | per-tree-fingerprint
        ↓
CONSOLIDATION TRIGGER (new Grain processor)
  threshold (default 10) OR on-demand
        ↓
CONSOLIDATOR (new todo processor)
  Pulls window=500 recent events + accumulated metrics + structural context.
  Runs a single LLM reflection with structured Malli output.
  Emits the appropriate :ontology/*-description-updated event.
        ↓
ONTOLOGY DESCRIPTION READ MODEL
  Projects events into "current description" + "history" per (granularity, target-id).
        ↓
COLBERT RE-INDEX (C-2b)
  Async; re-indexes the :summary into ColBERT on every *-description-updated.
        ↓
SEMANTIC RETRIEVAL
  Model queries via natural language → ColBERT search → RRF rank-fusion across granularities.
```

### Judge integration (added 2026-06)

The consolidator's reflection input was extended to include judge scores alongside raw execution events. Verified live on `legal-issue-detection` multi-cycle:

- **Per-event evaluator runtime** (`components/evaluation/src/.../core/judge_runtime.clj`) subscribes to `:sheet/node-execution-completed` and fires attached judges (default + custom) in parallel via futures with a 60s per-judge timeout. When the Living Description opt-in flag is on, repl-researcher nodes get 5 default judges auto-attached (heuristic-structural + grounding + reasoning + completeness + instruction-following).
- **Score events** (`:judge/score-emitted`) land in the event store tagged with `[:sheet :tick :node]`.
- **Consolidator joins them** via `gather-recent-tree-class-events`: per-observation `:judge-scores` from the recent window + `:judge-averages` per judge across the target's lifetime in `:aggregate-metrics`.
- **Reflection instruction explains the data**: tells the LLM that `:judge-averages` is the stable baseline and to weight per-tick judge divergence with the same anti-recency discipline as success-rate deltas.
- **Custom judges** plug into the same pipeline via `:type :custom` + `:sheet-id` referencing a consumer-built eval workflow. See [`RLM-GUIDE.md`](RLM-GUIDE.md#attaching-judges-to-your-behavior-trees).

Composite scoring (shipped 2026-06): when 2+ judges fire on the same (sheet, node, tick), the runtime emits a `:judge/composite-score-computed` event with the weighted composite. Default policy: even-weight (1/N) when consumers don't set explicit weights; consumer-set `:judge-config :weight` values normalize to sum to 1.0. The per-judge `:judge-averages` map stays alongside in the consolidator's reflection input — consumers wanting the independent per-judge signal aren't disrupted.

### Slice status

| Slice | Status | Notes |
|---|---|---|
| **C-1** | SHIPPED | Hand-authored principles + `:context` plumbing + format-rich-pattern extension. Live-verified with 3-way comparison. |
| **C-2a-1** | IN PROGRESS | Static description event types + read model + 18 hand-authored seeds. The schema + first command landed; remaining: read model + seed authoring. |
| **C-2a-2** | PENDING | Rolling aggregator extension (cross-sheet node-type + tree-fingerprint tag). |
| **C-2a-3** | PENDING | Consolidator processor + LLM reflection + triggers + anti-recency safeguards. |
| **C-2a-4** | PENDING | Live-verify orchestrator (3-run regression + seed-executability + retrieval-quality). |
| **C-2b** | STUB (sub-grill required) | ColBERT integration + RRF rank-fusion across granularities. |
| **C-2c** | STUB (sub-grill required) | Automatic classifier API — the wrapper that assigns `:tree-id` automatically when `:context` unset. |
| **C-3** | STUB (sub-grill required) | Judges + automated principle extraction. |
| **C-4** | STUB (sub-grill required) | Cross-tree pattern reuse + node-type learning. |

> **Note (2026-06):** Several C-2/C-3 facets above were superseded by the judge unification arc (Gaps 1/2/3/5/6/7/7b/4 shipped on `feature/core-orc-upgrades`). The status table preserves the original slicing for historical context; the actual implementation evolved through the gap-N slices, all local-only working notes under `docs/issues/c2d-followups/`.

### Cross-references

- Issue tracker: `docs/issues/category-c/` (per-slice issue files, dependency graph, README)
- C-1 PRD: `docs/prd/category-c-self-improving-loop.md`
- C-2 PRD: `docs/prd/category-c-2-living-descriptions.md`
- Architectural context: `docs/FUTURE-VISION.md` (Theme 6 + Theme 9 + Phase 4a)
- Existing storage surface: `docs/SELF-LEARNING-MANUAL.md`
- Continuous-improvement cycle: `docs/FEEDBACK-LOOP.md`

### How this doc gets updated

This is a living memory doc — it grows alongside the Category C work. Each sub-slice's completion adds:

- New capabilities + concrete examples to Part 2 (developer mental model)
- Status updates + cross-references to Part 3 (implementer view)
- Anything that fundamentally reframes the explanation should land in Part 1 (stakeholder narrative)

The aim is that anyone — a non-technical stakeholder, a developer integrating ORC, or an implementer maintaining the system — can read the part most relevant to them and walk away with an accurate mental model of how ORC learns to describe itself.
