# Bench Setup — ColBERT venv + first-run flow

This document walks a fresh-machine setup from clean clone through a successful
`(bench/run! legal-issue-detection/task)`. The content is grounded in the venv
rebuild done on 2026-06-08 — every package pin and every troubleshooting entry
below corresponds to a failure mode actually hit during that rebuild.

## 1. One-time machine setup

### Prerequisites

- Python 3.10, 3.11, or 3.12 (verified working — 3.12 is the captured set;
  ragatouille does not yet support 3.13 cleanly)
- Clojure CLI (`clj`) installed
- ~3 GB of free disk space for the Python venv + cached HuggingFace model
  weights downloaded on first use

### Setup

From the repo root:

```bash
bash scripts/setup-colbert.sh
```

The script:

1. Checks the existing `.venv-colbert/` for a broken Python symlink (the
   most common failure when the system Python the venv was built against has
   been upgraded or removed). If broken, recreates the venv.
2. Installs pinned dependencies from `development/bench/requirements.txt`
   into the venv. The pin file is a full `pip freeze` from a verified-working
   venv — every transitive dep is pinned because pip's resolver under loose
   constraints picks combinations that fail at first index-build (see
   Troubleshooting below).
3. Smoke-tests the bridge with `{"id":1,"method":"ping","params":{}}` and
   verifies `{"status": "ok"}` comes back.

Expected runtime: 3-8 minutes (mostly downloading torch + transformers from
PyPI). The script is idempotent — running it again with an intact venv prompts
before recreating; `--force` skips the prompt.

### Sanity check — bridge ping

After setup, the bridge subprocess can be smoke-tested without bringing up
Clojure:

```bash
echo '{"id":1,"method":"ping","params":{}}' | \
  .venv-colbert/bin/python scripts/colbert_bridge.py
# Expected: {"id": 1, "result": {"status": "ok"}}
```

### Deeper check — `colbert/health-check`

A `(colbert/health-check)` fn exercises both the bridge ping AND a tiny
8-document index build using the actual `colbert-ir/colbertv2.0` model. This
catches failure modes that `(colbert/ping)` alone misses — see Troubleshooting
for the specific package-version skews that only surface during the model
load + index build (and not on ping).

Run from a Clojure REPL or via `clj -M:dev -e`:

```clojure
clj -M:dev -e '(require (quote [ai.obney.orc.colbert.interface :as colbert])) (println (colbert/health-check))'
```

Expected (~10-30s cold-load, faster on subsequent calls):

```clojure
{:status :ok, :ping {:status "ok"}, :index-id #uuid "..."}
```

On failure, `:stage` identifies where the chain broke (`:ping` or
`:index-build`) and `:error` carries the underlying message. See
Troubleshooting.

## 2. First-run flow

With a healthy venv + health-check passing:

```clojure
clj -M:dev -e '(require (quote [runner :as r])) (r/start!)'
```

Expected output (annotated):

```
Emitting synthetic padding (80 entries for FAISS clustering floor)...
Seeding description corpus (45 hand-authored seeds)...
Driving concept-graph projectors...
Building ColBERT description index (one-time, expect 2-4 min)...
Index state: {:events-since-last-rebuild 0, :last-rebuild-timestamp "...", :index-built? true}
Benchmark system ready.
```

What to look for:

- The 80 synthetic-padding entries are filler the FAISS k-means clusterer
  needs to hit its minimum-cluster-size requirement. They're tagged so they
  do not surface in retrieval results.
- `:index-built? true` is the canonical "ColBERT-cold-start succeeded"
  signal. Without it, retrieval (`classify-task` / `classify-behaviors`)
  returns 0 hits because there's nothing in the index yet.
- The 2-4 minute build is mostly Python ColBERT loading the model the first
  time. Subsequent `(start!)` calls (after the model + tokenizer are cached)
  are 30-60 seconds.

## 3. Running benches

### Single task

```clojure
(require '[runner :as r])
(require '[legal-issue-detection :as task])
(r/start!)
(r/run! task/task)
(r/stop!)
```

### Full suite

```clojure
(require '[all :as bench])
(bench/start!)
(bench/run-all!)
(bench/summary!)
(bench/stop!)
```

### Result location

Each task run saves an EDN to:

```
development/bench/generalization-results/<task-slug>_<YYYY-MM-DD>_<HHMMSS>.edn
```

The EDN carries:

- `:status` — `:success` if the run completed within timeout
- `:generated-tree-raw` — the model's emitted behavior tree (S-expression form)
- `:r-inject-trace` — the prepend the model received (when `:rlm
  {:auto-classify? true}` is set on the task)
- `:iteration-reasonings` — per-iteration `:reasoning` text from the model
- `:usage` — token counts (prompt / completion / total)
- `:outputs` — the final task outputs

### What's reproducible across runs

- The classifier's `:assigned-tree-id` is stable when classify-task scores
  cleanly above the floor (typical for the 5 bench tasks).
- Reranker confidences are stable to within ~5% across runs.
- Token totals vary ±10-15% per run because the model's generated tree
  shape and reasoning prose are non-deterministic at temperature 0.2.

## 4. Adding your own seeds

Hand-authored tree-class and behavioral-subtree seeds live in
`development/src/seed_descriptions.clj`. Each seed is a static EDN map with:

- A stable target-id (typically derived via `nameUUIDFromBytes` over a string
  like `"seed:tree:<name>"` so re-emitting doesn't create a new identity)
- A description body with `:capabilities` / `:strengths` / `:weaknesses` /
  `:representative-uses` / `:avoid-when` / `:summary`
- Either `:scope :tree-class` (Layer 1, structural) or
  `:scope :behavioral-subtree` (Layer 2, behavioral)

After adding a seed:

1. Restart the runner (`(r/stop!)` then `(r/start!)`). The seed emit is part
   of `seed-corpus-and-build-index!` and runs at startup.
2. Verify the new seed is in the index:
   ```clojure
   (require '[ai.obney.orc.ontology.interface :as ontology])
   (ontology/search-descriptions ctx {:query "<text matching your :summary>"
                                       :granularity :tree-class
                                       :k 3})
   ```
3. The new seed should appear in the top-K with a positive score.

## 5. Troubleshooting

### `Error: python3 is required but not found`

Install Python 3.12 (or 3.10/3.11). On macOS via pyenv:

```bash
pyenv install 3.12.8
pyenv global 3.12.8
```

### `Existing venv is broken (python interpreter missing or unrunnable)`

The setup script detects this and recreates the venv automatically. This
happens when the system Python the venv was built against was upgraded or
removed (the `.venv-colbert/bin/python` symlink no longer resolves).

### `ModuleNotFoundError: No module named 'ragatouille'`

The venv exists but ragatouille isn't installed. Re-run
`bash scripts/setup-colbert.sh` — the requirements.txt path may not have
been correctly resolved.

### `ModuleNotFoundError: No module named 'langchain.retrievers'`

The venv was built with a too-new `langchain` (>= 0.3). ragatouille 0.0.9
imports `langchain.retrievers.document_compressors.base` which moved in
langchain 0.3. The `development/bench/requirements.txt` pins
`langchain==0.2.17`. If a manual `pip install` upgraded langchain, re-run
the setup script with `--force` to recreate the venv from the pin file.

### `ModuleNotFoundError: No module named 'psutil'`

`fast-pytorch-kmeans` depends on `psutil` but doesn't declare it. The pin
file includes `psutil==7.2.2`. Re-install via the setup script.

### `Bridge error: 'HF_ColBERT' object has no attribute 'all_tied_weights_keys'`

The venv has `transformers >= 5.x` but `colbert-ai` 0.2.22 references an
attribute removed in transformers 5. The pin file fixes
`transformers==4.55.4`. Re-install via the setup script.

### `Number of training points (N) should be at least as large as number of clusters (32)`

The corpus passed to ColBERT is too small for the FAISS k-means clusterer.
The bench runner emits 80 synthetic-padding entries at startup specifically
to satisfy this constraint. If you're calling `create-index!` outside the
runner with a tiny corpus, either supply more docs (~10+ with multi-token
content) or set `:use-faiss? false` to skip the k-means step.

### `:r-inject-trace` is nil on a saved EDN even though task has `:rlm {:auto-classify? true}`

Three possible causes:

1. The runner wasn't started fresh — `(r/start!)` builds the corpus index;
   without it, classify-task has nothing to retrieve. Re-run `(r/start!)`
   and verify `:index-built? true`.
2. The reranker fell back to ColBERT (low-confidence top-1). The EDN's
   `:r-inject-trace.:classifier-payload.:structural.:rerank-fallback?` would
   be `true` in that case.
3. The task's structural fingerprint didn't match anything above the
   `min-display-confidence` floor (0.6). Try lowering the floor temporarily
   in `todo_processors.clj` to confirm.

## 6. Reproducing report numbers

The reports under `development/bench/r_inject_reports/` cite specific EDNs
from `2026-06-02` runs. Clean re-runs **will not** produce byte-identical
EDNs (LLM non-determinism + timestamp fields), but they will produce
**structurally-comparable** ones:

- `:r-inject-trace.:prepend` — the corpus-suggestions block. Should start
  with `"## Suggested patterns from corpus"` and include the matched seed
  name with a confidence in the `[:context :tree-id]` neighborhood of the
  reports.
- `:r-inject-trace.:classifier-payload.:structural.:top-candidates[0]
  .:fitness-score` — for `legal-issue-detection`, this is ≈ 1.00 because
  the task description matches the seed almost verbatim.
- `:usage.:total-tokens` — in the 40K-60K range for `legal-issue-detection`.
  Higher = the model emitted a more complex tree; lower = it short-circuited
  to a single LLM call.
- `:iteration-reasonings[0]` — the first iteration's `:reasoning` should
  reference the corpus-prepended patterns (when classify-task fired) or
  state explicitly that no high-confidence match was found.

When in doubt, compare two recent EDNs from the same task to anchor the
expected variance band.

## Cross-references

- `scripts/setup-colbert.sh` — idempotent venv builder
- `development/bench/requirements.txt` — pinned, verified-working dependencies
- `scripts/colbert_bridge.py` — the Python subprocess the JVM talks to
- `components/colbert/src/ai/obney/orc/colbert/interface.clj` — public API
  (including `health-check`)
- `development/bench/r_inject_reports/` — expected-output reference EDNs
