# LoveApp RAGas Evaluation

This folder contains a RAGas workflow for the LoveApp diary RAG path.

Recommended flow uses Java test execution to generate model outputs first, then Python computes metrics.

## Scope
- Target: LoveApp diary-related RAG behavior.
- Java execution entry: `LoveApp` via test class `LoveAppRagEvalCaptureTest`.
- Metrics: `faithfulness`, `answer_relevancy`, `context_precision`, `context_recall`.

## Prerequisites
1. Python 3.10+ available in your environment.
2. Java project test runtime can call model services successfully.
3. LLM provider keys configured for backend and for RAGas metric judging model (if required by your setup).

## Install dependencies
From workspace root:

```bash
pip install -r eval/requirements.txt
```

## Run evaluation (recommended: Java capture + Python score)

Step 1: generate captured samples from Java tests.

```bash
mvn -Dtest=LoveAppRagEvalCaptureTest test
```

Output file example:

- `target/ragas/<timestamp>/loveapp_eval_capture.jsonl`

Step 2: run RAGas scoring from captured file.

```bash
python eval/run_ragas_eval.py \
  --captured-jsonl target/ragas/<timestamp>/loveapp_eval_capture.jsonl \
  --gate
```

## Alternative run (HTTP direct mode)

Minimal run:

```bash
python eval/run_ragas_eval.py --gate
```

Optional args:

```bash
python eval/run_ragas_eval.py \
  --base-url http://localhost:8123/api \
  --dataset eval/datasets/loveapp_ragas_v1.jsonl \
  --thresholds eval/config/thresholds.yaml \
  --output-dir eval/results \
  --gate
```

## Output artifacts
Each run writes to `eval/results/<timestamp>/`:
- `summary.json`: aggregate metrics, thresholds, gate status.
- `details.csv`: per-sample fields and per-metric values.

## Context handling note
- Java capture mode: contexts come from `diaryVectorStore.similaritySearch(topK=3, threshold=0.5)` in test code.
- HTTP direct mode: `/ai/love_app/chat/sync` returns plain text only, so contexts may fall back to `reference_contexts`.

## Troubleshooting
- Backend unreachable:
  - Confirm app started and `server.port/context-path` match defaults.
  - Check `--base-url`.
- Java capture test fails at startup with `加载文档到 PgVectorStore 失败`:
  - `chat_messages` data does not populate `vector_store`; they are different tables.
  - Current `PgVectorVectorStoreConfig` uses `@PostConstruct` + field `vectorStore`, which can run before bean assignment and cause empty vector writes.
  - Evaluation can still proceed because capture test uses `diaryVectorStore` path.
- Missing API key / model errors:
  - Configure backend LLM API keys.
  - Ensure evaluation-side model credentials required by RAGas are available.
- Contexts always empty:
  - In HTTP direct mode this is expected with current sync API response format.
  - Prefer Java capture mode for real contexts without changing production controller response.
