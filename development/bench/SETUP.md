# ColBERT environment setup

ColBERT (neural late-interaction retrieval, via [RAGatouille]) powers the optional
`:colbert` signal in the ontology's hybrid search. It runs as a **Python subprocess
bridge** (`scripts/colbert_bridge.py`) that the Clojure `colbert` component talks to
over JSON-RPC on stdin/stdout. There is no Python in the JVM — just a dedicated venv.

The dependency stack is notoriously fragile to resolve, so we pin a **full,
verified-working freeze** (`development/bench/requirements.txt`) rather than loose
constraints. A fresh `pip install ragatouille` will silently build a broken venv.

## Quick start

```bash
bash scripts/setup-colbert.sh            # create/reuse .venv-colbert, install, smoke-test
bash scripts/setup-colbert.sh --force    # always recreate (no prompt)
```

Requires **Python 3.10–3.12** (3.12 is the verified version; 3.13 is untested — torch
wheels may be missing). Installs ~1–2 GB (torch, transformers, faiss, …).

The script fails loudly if the install half-succeeds: it runs a real
`import ragatouille` and the bridge `ping` before declaring success.

## Verify

```bash
# bridge handshake
echo '{"id":1,"method":"ping","params":{}}' | .venv-colbert/bin/python scripts/colbert_bridge.py
# → {"id": 1, "result": {"status": "ok"}}

# from Clojure (runs FROM the orc repo, so the relative paths resolve)
clj -M:dev -e '(require (quote [ai.obney.orc.colbert.interface :as colbert])) (println (colbert/ping))'
```

## Using ColBERT from a downstream app (orc as a git/SHA dependency)

The bridge resolves its venv and script **relative to the JVM working directory**,
which is correct when you run *from* the orc repo. An app that depends on orc as a
read-only `:git/sha` library runs from its own directory, where neither
`.venv-colbert` nor `scripts/colbert_bridge.py` exists. Point both at absolute paths
via system properties (JVM `-D` flags / `:jvm-opts`):

```
-Dcolbert.venv.path=/abs/path/to/.venv-colbert
-Dcolbert.bridge.script=/abs/path/to/orc/scripts/colbert_bridge.py
```

The script lives in your gitlibs checkout of orc, e.g.
`~/.gitlibs/libs/obneyai/orc-*/<sha>/scripts/colbert_bridge.py`. Create the venv
locally (clone orc or copy the two files + run `setup-colbert.sh`), then set both
properties. Without `-Dcolbert.bridge.script` the bridge can't find the script and
ColBERT search silently no-ops back to the other signals.

## Why every dep is pinned (the failure modes)

`requirements.txt` is a full `pip freeze`. The non-obvious pins, all hit in practice:

| Pin | Why |
|-----|-----|
| `ragatouille==0.0.9.post2` (`<0.0.10`) | 0.0.10+ drops the Stanford ColBERT backend. 0.0.9 imports `langchain.retrievers` — the **pre-0.3** langchain API. |
| `langchain==0.2.17`, `langchain-core==0.2.43` (`<0.3`) | ragatouille 0.0.9 breaks against langchain 0.3+. |
| `transformers==4.55.4` (`<5.0`), `tokenizers==0.21.4` (`<0.22`) | colbert-ai 0.2.22 uses `HF_ColBERT.all_tied_weights_keys`, removed in transformers 5.x. |
| `psutil` | `fast-pytorch-kmeans` imports it without declaring it; pip won't pull it in. |
| **no `langgraph`** | A `pip freeze` may sweep in `langgraph` (unrelated to ColBERT). `langgraph` 1.x needs `langchain-core>=1.4`, which conflicts head-on with `langchain-core==0.2.43` → `ResolutionImpossible`, install aborts, **nothing** installs. Keep langgraph out of this file. |

A permissive `pip install ragatouille>=0.0.9 torch>=2.0` picks the newest
compatible-on-paper versions and hits the first three bugs; the venv imports but
fails on the first real index build.

## Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| `ResolutionImpossible` … `langchain-core` | A `langgraph` line is in `requirements.txt`. Remove all `langgraph*` lines. |
| `ModuleNotFoundError: ragatouille` after setup | The install aborted (see above) — `pip list` is empty. Re-run after fixing requirements. |
| `ModuleNotFoundError: psutil` | `pip install psutil` (or use the pinned requirements). |
| `AttributeError: all_tied_weights_keys` | `transformers` is 5.x. Pin `transformers<5.0 tokenizers<0.22`. |
| `import langchain.retrievers` fails | `langchain` is 0.3+. Pin `langchain<0.3`. |
| venv exists but `.venv-colbert/bin/python` won't run | The system Python it was built against is gone (e.g. a 3.11 symlink). Re-run with `--force`. |
| Bridge `ping` ok but search no-ops from another app | Missing `-Dcolbert.bridge.script` (see above). |

## Regenerating the pinned requirements

After intentionally upgrading a dep in a working venv:

```bash
.venv-colbert/bin/pip freeze | grep -v -i '^langgraph' > development/bench/requirements.txt
```

Then verify a clean rebuild reproduces: `bash scripts/setup-colbert.sh --force`.

[RAGatouille]: https://github.com/AnswerDotAI/RAGatouille
