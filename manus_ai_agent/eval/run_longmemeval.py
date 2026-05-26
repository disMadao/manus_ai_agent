#!/usr/bin/env python3
"""
LongMemEval benchmark adapter for OpenFriend agent.

Strategy: inject each sample's haystack_sessions into workspace/memory/memory.md
as a ## bench_data section, which the agent's exportAllContext() naturally reads
into its system prompt. Then ask the question via GET (short message, no URL limit).
After all samples, restore the clean memory.md.

Usage:
  conda run -n longmemeval python eval/run_longmemeval.py \
      --dataset data/longmemeval_oracle.json --max-samples 10
"""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx


# ── data loading ──────────────────────────────────────────────────────────

def load_longmemeval(path: Path) -> list[dict]:
    with path.open("r", encoding="utf-8") as f:
        data = json.load(f)
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        return list(data.values())
    raise ValueError(f"Unexpected data format: {type(data)}")


def format_sessions(sessions: list[list[dict]], session_ids: list[str] | None = None,
                     session_dates: list[str] | None = None) -> str:
    """Format haystack sessions into readable text for memory injection."""
    parts = []
    for i, session in enumerate(sessions):
        sid = session_ids[i] if session_ids and i < len(session_ids) else f"session-{i+1}"
        sdate = session_dates[i] if session_dates and i < len(session_dates) else "unknown date"
        parts.append(f"### Session {sid} [{sdate}]")
        for turn in session:
            role = turn.get("role", "unknown").upper()
            content = turn.get("content", "")
            has_answer = turn.get("has_answer", False)
            marker = " **[EVIDENCE]**" if has_answer else ""
            parts.append(f"**{role}{marker}:** {content}")
            parts.append("")
        parts.append("---")
        parts.append("")
    return "\n".join(parts)


def build_question_prompt(sample: dict) -> str:
    """Build the question prompt - sessions come from memory.md."""
    qtype = sample.get("question_type", "unknown")
    question = sample["question"]
    return (
        f"The ## bench_data section in memory.md contains conversation logs. "
        f"Read that section directly from your system prompt - it is already loaded.\n"
        f"IMPORTANT: Answer using ONLY the bench_data conversations. "
        f"Do NOT call any tools (no memoryWorkspace, no webSearch, no fileOperation). "
        f"Just read the context you already have and answer directly.\n\n"
        f"Question [{qtype}]: {question}"
    )


# ── memory injection ──────────────────────────────────────────────────────

BENCH_MARKER = "## bench_data"


def inject_bench_data(workspace_dir: Path, sessions_text: str):
    """Write sessions into memory.md as ## bench_data section."""
    memory_path = workspace_dir / "memory" / "memory.md"
    base = memory_path.read_text(encoding="utf-8")

    # Remove existing bench_data section if present
    base = _remove_bench_section(base)

    # Append bench data at end
    new_content = base.rstrip() + f"\n\n{BENCH_MARKER}\n{sessions_text}\n"
    memory_path.write_text(new_content, encoding="utf-8")


def clear_bench_data(workspace_dir: Path):
    """Remove ## bench_data section from memory.md."""
    memory_path = workspace_dir / "memory" / "memory.md"
    content = memory_path.read_text(encoding="utf-8")
    content = _remove_bench_section(content)
    memory_path.write_text(content, encoding="utf-8")


def _remove_bench_section(text: str) -> str:
    """Remove ## bench_data section and everything after if it's the last section."""
    idx = text.find(BENCH_MARKER)
    if idx < 0:
        return text
    # Find next ## section after bench_data
    after_marker = text[idx + len(BENCH_MARKER):]
    next_h2 = after_marker.find("\n## ")
    if next_h2 >= 0:
        # There's another section after bench_data - remove just bench_data block
        return text[:idx].rstrip() + "\n" + after_marker[next_h2:]
    else:
        # bench_data is the last section - remove it entirely
        return text[:idx].rstrip() + "\n"


def backup_memory(workspace_dir: Path) -> Path:
    """Backup memory.md, return backup path."""
    memory_path = workspace_dir / "memory" / "memory.md"
    backup_path = memory_path.with_suffix(".md.bak")
    shutil.copy(memory_path, backup_path)
    return backup_path


def restore_memory(workspace_dir: Path):
    """Restore memory.md from backup."""
    memory_path = workspace_dir / "memory" / "memory.md"
    backup_path = memory_path.with_suffix(".md.bak")
    if backup_path.exists():
        shutil.copy(backup_path, memory_path)
        backup_path.unlink()
        print("[INFO] Restored memory.md from backup")


# ── agent call ─────────────────────────────────────────────────────────────

def call_agent(client: httpx.Client, base_url: str, message: str, sample_id: str,
               timeout: float) -> tuple[str, float]:
    """Call the agent's sync chat endpoint."""
    endpoint = f"{base_url.rstrip('/')}/ai/love_app/chat/sync"
    chat_id = f"lme-{sample_id}"
    start = time.perf_counter()
    resp = client.get(endpoint, params={"message": message, "chatId": chat_id}, timeout=timeout)
    elapsed = time.perf_counter() - start
    resp.raise_for_status()
    return resp.text.strip(), elapsed


# ── evaluation ─────────────────────────────────────────────────────────────

def judge_answer(question: str, reference: str, hypothesis: str,
                 model: str = "qwen-plus",
                 judge_api: str = "dashscope",
                 judge_key: str = "",
                 judge_base_url: str = "") -> dict:
    from openai import OpenAI

    judge_system = (
        "You are an automated evaluator for a long-term memory benchmark. "
        "Given a question, the correct reference answer, and the model's hypothesis answer, "
        "judge whether the hypothesis contains the correct answer.\n\n"
        'Reply with a JSON object: {"label": "CORRECT" | "WRONG" | "ABSTAIN", "reason": "brief explanation"}\n\n'
        "Rules:\n"
        "- CORRECT: the hypothesis contains the essential facts from the reference answer. Be lenient: accept minor wording differences if the core facts match.\n"
        "- WRONG: the hypothesis contradicts or misses key facts from the reference answer.\n"
        "- ABSTAIN: the hypothesis states it cannot find the answer in the provided history."
    )

    if judge_api == "dashscope":
        api_key = judge_key or _read_config_key() or _env_key("DASHSCOPE_API_KEY")
        base_url = judge_base_url or "https://dashscope.aliyuncs.com/compatible-mode/v1"
        if not model or model == "gpt-4o":
            model = "qwen-plus"
    else:
        api_key = judge_key or _env_key("OPENAI_API_KEY")
        base_url = judge_base_url or "https://api.openai.com/v1"
        if not model:
            model = "gpt-4o"

    if not api_key:
        raise RuntimeError("No API key configured for judge. Set OPENAI_API_KEY or DASHSCOPE_API_KEY env var, or use --judge-api-key.")

    client = OpenAI(api_key=api_key, base_url=base_url)
    resp = client.chat.completions.create(
        model=model,
        messages=[
            {"role": "system", "content": judge_system},
            {"role": "user", "content": json.dumps({
                "question": question,
                "reference_answer": reference,
                "hypothesis": hypothesis,
            }, ensure_ascii=False)},
        ],
        temperature=0.0,
    )
    content = resp.choices[0].message.content
    # Strip markdown code fences if present
    if content.startswith("```"):
        content = content.replace("```json", "").replace("```", "").strip()
    return json.loads(content)


def _env_key(name: str) -> str:
    import os
    return os.environ.get(name, "")


def _read_config_key() -> str:
    """Try to read DashScope API key from Spring application-local.yml."""
    import os
    from pathlib import Path
    config_paths = [
        Path("src/main/resources/application-local.yml"),
        Path("src/main/resources/application.yml"),
    ]
    for cp in config_paths:
        if cp.exists():
            content = cp.read_text(encoding="utf-8")
            for line in content.splitlines():
                stripped = line.strip()
                if stripped.startswith("api-key:") or stripped.startswith("api_key:"):
                    key = stripped.split(":", 1)[1].strip().strip('"').strip("'")
                    if key and key not in ("your-dashscope-api-key", "your-search-api-key"):
                        return key
    return ""


# ── main ───────────────────────────────────────────────────────────────────

def parse_args():
    p = argparse.ArgumentParser(description="LongMemEval benchmark for OpenFriend agent")
    p.add_argument("--base-url", default="http://localhost:8123/api",
                   help="Backend base URL")
    p.add_argument("--dataset", required=True,
                   help="Path to LongMemEval JSON file")
    p.add_argument("--workspace-dir", default="workspace",
                   help="Path to agent's workspace directory")
    p.add_argument("--max-samples", type=int, default=0,
                   help="Max samples to evaluate (0=all)")
    p.add_argument("--timeout", type=float, default=300.0,
                   help="HTTP timeout per request (seconds)")
    p.add_argument("--judge-model", default="qwen-plus",
                   help="LLM judge model (default: qwen-plus for dashscope, gpt-4o for openai)")
    p.add_argument("--judge-api", default="dashscope", choices=["dashscope", "openai"],
                   help="Judge API backend")
    p.add_argument("--judge-api-key", default="",
                   help="Judge API key (or set DASHSCOPE_API_KEY / OPENAI_API_KEY env var)")
    p.add_argument("--judge-base-url", default="",
                   help="Judge API base URL (defaults based on --judge-api)")
    p.add_argument("--output-dir", default="eval/results",
                   help="Output directory")
    p.add_argument("--skip-judge", action="store_true",
                   help="Skip LLM judge evaluation (just collect answers)")
    p.add_argument("--dry-run", action="store_true",
                   help="Print first sample and exit without calling agent")
    p.add_argument("--delay", type=float, default=1.0,
                   help="Delay between samples in seconds")
    return p.parse_args()


def main():
    args = parse_args()
    dataset_path = Path(args.dataset)
    workspace_dir = Path(args.workspace_dir)
    output_root = Path(args.output_dir)

    if not dataset_path.exists():
        print(f"[ERROR] Dataset not found: {dataset_path}", file=sys.stderr)
        return 2
    if not workspace_dir.exists():
        print(f"[ERROR] Workspace not found: {workspace_dir}", file=sys.stderr)
        return 2

    samples = load_longmemeval(dataset_path)
    print(f"[INFO] Loaded {len(samples)} samples from {dataset_path}")

    if args.max_samples > 0:
        samples = samples[:args.max_samples]
        print(f"[INFO] Limited to {len(samples)} samples")

    if args.dry_run:
        sample = samples[0]
        sessions_text = format_sessions(
            sample.get("haystack_sessions", []),
            sample.get("haystack_session_ids", []),
            sample.get("haystack_dates", []),
        )
        print(f"qid:      {sample.get('question_id')}")
        print(f"type:     {sample.get('question_type')}")
        print(f"question: {sample['question']}")
        print(f"answer:   {sample.get('answer')}")
        print(f"prompt:   {build_question_prompt(sample)}")
        print(f"sessions ({len(sessions_text)} chars):")
        print("=" * 60)
        print(sessions_text[:2000])
        return 0

    # Backup memory.md
    backup_path = backup_memory(workspace_dir)
    print(f"[INFO] Backed up memory.md to {backup_path}")

    run_dir = output_root / datetime.now().strftime("longmemeval-%Y%m%d-%H%M%S")
    run_dir.mkdir(parents=True, exist_ok=True)

    rows: list[dict[str, Any]] = []
    correct = wrong = abstain = failures = 0

    try:
        with httpx.Client(timeout=args.timeout) as client:
            for i, sample in enumerate(samples):
                qid = sample.get("question_id", f"sample-{i}")
                question = sample["question"]
                reference = sample.get("answer", "")
                qtype = sample.get("question_type", "unknown")
                is_abstain = qid.endswith("_abs") if qid else False

                print(f"[{i+1}/{len(samples)}] {qid} ({qtype})...", end=" ", flush=True)

                try:
                    # Step 1: Inject sessions into memory.md
                    sessions_text = format_sessions(
                        sample.get("haystack_sessions", []),
                        sample.get("haystack_session_ids", []),
                        sample.get("haystack_dates", []),
                    )
                    inject_bench_data(workspace_dir, sessions_text)

                    # Step 2: Ask the question (short message)
                    prompt = build_question_prompt(sample)
                    answer, elapsed = call_agent(
                        client, args.base_url, prompt, qid, args.timeout
                    )

                    row = {
                        "question_id": qid,
                        "question_type": qtype,
                        "question": question,
                        "reference_answer": reference,
                        "hypothesis": answer,
                        "is_abstain": is_abstain,
                        "elapsed_seconds": round(elapsed, 1),
                        "input_chars": len(sessions_text),
                    }

                    # Step 3: Judge
                    if not args.skip_judge:
                        try:
                            judge_result = judge_answer(
                                question, reference, answer,
                                model=args.judge_model,
                                judge_api=args.judge_api,
                                judge_key=args.judge_api_key,
                                judge_base_url=args.judge_base_url,
                            )
                            row["label"] = judge_result.get("label", "UNKNOWN")
                            row["judge_reason"] = judge_result.get("reason", "")
                        except Exception as e:
                            row["label"] = "JUDGE_ERROR"
                            row["judge_reason"] = str(e)
                    else:
                        row["label"] = "SKIPPED"
                        row["judge_reason"] = ""

                    label = row["label"]
                    if label == "CORRECT":
                        correct += 1
                    elif label == "WRONG":
                        wrong += 1
                    elif label == "ABSTAIN":
                        abstain += 1
                    elif label == "REQUEST_FAILED":
                        failures += 1

                    print(f"{label} ({elapsed:.1f}s)")

                except Exception as e:
                    print(f"FAILED: {e}")
                    failures += 1
                    row = {
                        "question_id": qid,
                        "question_type": qtype,
                        "question": question,
                        "reference_answer": reference,
                        "hypothesis": "",
                        "is_abstain": is_abstain,
                        "elapsed_seconds": 0,
                        "input_chars": 0,
                        "label": "REQUEST_FAILED",
                        "judge_reason": str(e),
                    }

                rows.append(row)

                if args.delay > 0 and i < len(samples) - 1:
                    time.sleep(args.delay)
    finally:
        # Always restore memory.md
        clear_bench_data(workspace_dir)
        restore_memory(workspace_dir)

    # ── write results ──────────────────────────────────────────────────
    csv_path = run_dir / "details.csv"
    fieldnames = [
        "question_id", "question_type", "question", "reference_answer",
        "hypothesis", "is_abstain", "elapsed_seconds", "input_chars",
        "label", "judge_reason",
    ]
    with csv_path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    non_abs = [r for r in rows if not r["is_abstain"]
               and r["label"] not in ("SKIPPED", "REQUEST_FAILED", "JUDGE_ERROR")]
    correct_non_abs = sum(1 for r in non_abs if r["label"] == "CORRECT") if non_abs else 0
    accuracy = correct_non_abs / len(non_abs) if non_abs else 0.0

    abs_rows = [r for r in rows if r["is_abstain"]
                and r["label"] not in ("SKIPPED", "REQUEST_FAILED", "JUDGE_ERROR")]
    abs_correct = sum(1 for r in abs_rows if r["label"] == "ABSTAIN") if abs_rows else 0
    abs_accuracy = abs_correct / len(abs_rows) if abs_rows else 1.0

    summary = {
        "run_at": datetime.now(timezone.utc).isoformat(),
        "dataset": str(dataset_path),
        "judge_model": args.judge_model if not args.skip_judge else "skipped",
        "base_url": args.base_url,
        "injection_method": "memory_md_bench_data",
        "total_samples": len(samples),
        "correct": correct,
        "wrong": wrong,
        "abstain": abstain,
        "request_failures": failures,
        "accuracy": round(accuracy, 4),
        "abstention_accuracy": round(abs_accuracy, 4),
        "non_abstain_count": len(non_abs),
        "abstain_count": len(abs_rows),
    }

    summary_path = run_dir / "summary.json"
    with summary_path.open("w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)

    print(f"\n{'='*50}")
    print(f"Results: {summary_path}")
    print(f"Details: {csv_path}")
    if not args.skip_judge:
        print(f"\nAccuracy (excl. abstain): {accuracy:.2%} ({correct_non_abs}/{len(non_abs)})")
        if abs_rows:
            print(f"Abstention accuracy:     {abs_accuracy:.2%} ({abs_correct}/{len(abs_rows)})")
    print(f"Request failures:        {failures}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
