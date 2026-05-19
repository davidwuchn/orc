# ORC RLM Generalization Benchmark Suite

A set of five end-to-end benchmarks that prove ORC RLM (the Repl Researcher + `emit-tree!` workflow) genuinely **adapts behavior tree design to task type** rather than pattern-matching a single template.

**TL;DR:** Across 5 fundamentally different tasks, the model designs 4 distinct tree patterns + 1 "no tree" decision, all from goal-only instructions, with **zero hallucinations** across 37 spot-checked facts.

See [`reports/07_final_generalization_report.md`](reports/07_final_generalization_report.md) for the complete story.

---

## What the suite tests

Five task types, deliberately chosen to be structurally different:

| Task | Doc Size | Pattern | Why it's distinct |
|------|---------|---------|-------------------|
| `:document-analysis` | 280K (1 doc) | Extraction | Pull facts (dates, entities, summary) |
| `:risk-analysis` | 280K (1 doc) | Analytical reasoning | Classify, assess severity, recommend |
| `:contract-comparison` | 105K (2 docs) | Cross-document diff | Multi-doc reasoning |
| `:contract-comparison-validated` | 105K (2 docs) | Diff + adversarial validation | Quality requirement → cross-reference |
| `:legal-issue-detection` | 7K (1 doc) | Analytical reasoning (small) | Tests "no tree" decision |

## How to run

### 1. Set the API key

```bash
export OPENROUTER_API_KEY="sk-or-v1-..."
```

(Configured for `google/gemini-3-flash-preview` via OpenRouter. To change, edit `config` in `development/src/rlm_gen_bench.clj`.)

### 2. Run a single task from a Clojure REPL

```clojure
(require '[rlm-gen-bench :as bench])
(bench/start!)
(bench/run-task! :risk-analysis)        ; or any task keyword
(bench/stop!)
```

Or from the command line:

```bash
clj -M:dev -e '
(require (quote [rlm-gen-bench :as bench]))
(bench/start!)
(bench/run-task! :risk-analysis)
(bench/stop!)'
```

### 3. Run all tasks

```clojure
(bench/run-all-tasks!)
```

This runs each task in sequence and saves results to `development/bench/generalization-results/`.

### 4. Generate a summary table from saved runs

```clojure
(bench/generate-summary-table!)
```

## Available tasks

```clojure
(keys rlm-gen-bench/tasks)
;; => (:document-analysis :risk-analysis :contract-comparison
;;     :contract-comparison-validated :legal-issue-detection)
```

## Output

Each run produces an EDN file with:
- `:task`, `:task-name`, `:pattern`
- `:duration-ms`, `:status`, `:usage` (tokens)
- `:documents` (sizes), `:total-chars`
- `:outputs` — the final results AND `:generated-tree-raw` (the tree the model designed)
- `:evaluation-criteria` — manual quality checklist

EDN files are saved to `development/bench/generalization-results/`.

## Documents used

Located in `development/bench/documents/`:

| File | Size | Content |
|------|------|---------|
| `yyj_rfp.txt` | 280K | Victoria Airport Authority Parking Management RFP (real) |
| `contract_v2.txt` | 56K | Ontario microFIT Contract Version 2.0 (real) |
| `contract_v3.txt` | 49K | Ontario microFIT Contract Version 3.1.1 (real) |
| `employment_agreement.txt` | 7K | Employment agreement (real, names redacted) |
| `invoice_acme.txt` | 800 | Sample invoice |
| `invoice_globaltech.txt` | 900 | Sample invoice |

## Reports

`development/bench/reports/` contains the per-task analysis:

| Report | What it covers |
|--------|----------------|
| `00_overview.md` | Suite overview, goals, key findings |
| `01_document_analysis.md` | Baseline run (synthetic 138K doc, template-driven) |
| `02_risk_analysis.md` | G02/G07 — risk analysis on real 280K, including parallel speedup |
| `03_contract_comparison.md` | G03/G08/G09 — multi-doc diff with full hallucination journey + validator extension |
| `04_document_analysis_real_doc.md` | G06 — doc analysis on real 280K |
| `05_concurrency_bug_investigation.md` | P01 — the two-bug max-concurrency story with diagnostic logs and the fix |
| `06_legal_issue_detection.md` | G04 — model intelligently skips `emit-tree!` for small doc |
| **`07_final_generalization_report.md`** | **The headline story — read this first.** |
| `99_tree_comparison.md` | Side-by-side tree structures showing real generalization |

## Performance characteristics (post-P01)

| Task | Duration | Tokens | Speedup vs serial |
|------|----------|--------|-------------------|
| `:legal-issue-detection` | 8.8s | 5,706 | n/a (no tree) |
| `:contract-comparison-validated` | 26.7s | 84,337 | n/a |
| `:contract-comparison` | 35.6s | 64,336 | 3-6x |
| `:risk-analysis` | 64.8s | 170,336 | 2.4x |
| `:document-analysis` | 126.3s | 212,066 | 2.0x |

## Configuration

`rlm-gen-bench/config` lives at the top of `development/src/rlm_gen_bench.clj`:

```clojure
(def config
  {:model "google/gemini-3-flash-preview"
   :timeout-ms 600000  ;; 10 min wall clock
   :documents-dir "development/bench/documents"
   :results-dir "development/bench/generalization-results"})
```

## Caveats

- Requires a working `OPENROUTER_API_KEY` with credit on `google/gemini-3-flash-preview`.
- Model output is non-deterministic — exact tree structures may vary between runs (timings stay within 1.5x).
- Token usage varies more than wall-clock time; budget at least ~$0.10–$0.50 per full suite run depending on model selection.

## Related framework changes

The P01 fix to `:max-concurrency` (see `reports/05_concurrency_bug_investigation.md`) lives in:
- `components/orc-service/src/ai/obney/orc/orc_service/core/rlm_tree_executor.clj` (DSL → command compilation)
- `components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj` (in-flight tracking)

The behavioral test that locks in the fix:
- `components/orc-service/test/ai/obney/orc/orc_service/rlm_tree_executor_test.clj` — `map-each-max-concurrency-runs-iterations-in-parallel`
