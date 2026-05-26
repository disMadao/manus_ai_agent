#!/bin/bash
# =========================================================
# LongMemEval Test Suite for OpenFriend Agent
# One-click evaluation script
#
# Usage:
#   bash eval/run_longmemeval.sh
#   bash eval/run_longmemeval.sh --samples 20
#   bash eval/run_longmemeval.sh --dataset data/longmemeval_oracle.json --judge
# =========================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# ── Defaults ──
AGENT_URL="${AGENT_URL:-http://localhost:8123/api}"
DATASET="${DATASET:-data/longmemeval_oracle.json}"
MAX_SAMPLES="${MAX_SAMPLES:-50}"
TIMEOUT="${TIMEOUT:-300}"
OUTPUT_DIR="${OUTPUT_DIR:-eval/results}"
SKIP_JUDGE=""
JUDGE_MODEL="${JUDGE_MODEL:-qwen-plus}"
WORKSPACE_DIR="workspace"
WORKSPACE_BACKUP="workspace.bak"

# ── Parse args ──
while [[ $# -gt 0 ]]; do
    case "$1" in
        --samples) MAX_SAMPLES="$2"; shift 2;;
        --dataset) DATASET="$2"; shift 2;;
        --judge) SKIP_JUDGE=""; shift;;
        --judge-model) JUDGE_MODEL="$2"; shift 2;;
        --url) AGENT_URL="$2"; shift 2;;
        --help|-h)
            echo "Usage: bash eval/run_longmemeval.sh [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --samples N        Number of samples to test (default: 50)"
            echo "  --dataset PATH     Path to LongMemEval JSON file"
            echo "  --judge            Enable GPT-4o judge (requires OPENAI_API_KEY)"
            echo "  --judge-model M    Judge model (default: gpt-4o)"
            echo "  --url URL          Agent base URL (default: http://localhost:8123/api)"
            echo ""
            echo "Env vars:"
            echo "  OPENAI_API_KEY     Required if using --judge"
            echo "  HF_ENDPOINT        HuggingFace mirror (default: https://hf-mirror.com)"
            exit 0
            ;;
        *) echo "Unknown option: $1"; exit 1;;
    esac
done

HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}"
DATASET_URL="${HF_ENDPOINT}/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_oracle.json"

echo "========================================"
echo " LongMemEval Test Suite"
echo "========================================"
echo "Dataset:     $DATASET"
echo "Agent URL:   $AGENT_URL"
echo "Max samples: $MAX_SAMPLES"
echo "Judge:       ${SKIP_JUDGE:+disabled}${SKIP_JUDGE:-enabled ($JUDGE_MODEL)}"
echo "========================================"
echo ""

# ── Step 1: Check prerequisites ──
echo "[1/6] Checking prerequisites..."

if ! command -v python &> /dev/null; then
    echo "[ERROR] Python not found"
    exit 1
fi

if ! conda env list 2>/dev/null | grep -q longmemeval; then
    echo "[SETUP] Creating conda environment 'longmemeval'..."
    conda create -n longmemeval python=3.11 -y
    conda run -n longmemeval pip install httpx openai
    echo "[OK] Conda env ready"
fi

echo "[OK] Prerequisites satisfied"
echo ""

# ── Step 2: Download dataset ──
echo "[2/6] Checking dataset..."
if [[ ! -f "$DATASET" ]]; then
    DATASET_DIR="$(dirname "$DATASET")"
    mkdir -p "$DATASET_DIR"
    echo "Downloading dataset to $DATASET..."
    curl -L -o "$DATASET" "$DATASET_URL" || {
        echo "[ERROR] Failed to download dataset. Try setting HF_ENDPOINT env var."
        exit 1
    }
    echo "[OK] Dataset downloaded"
else
    echo "[OK] Dataset exists: $DATASET"
fi
echo ""

# ── Step 3: Setup workspace ──
echo "[3/6] Setting up test workspace..."

# Backup user workspace
if [[ -d "$WORKSPACE_DIR" ]] && [[ ! -d "$WORKSPACE_BACKUP" ]]; then
    echo "Backing up workspace/ to workspace.bak/"
    mv "$WORKSPACE_DIR" "$WORKSPACE_BACKUP"
elif [[ -d "$WORKSPACE_DIR" ]]; then
    echo "[WARN] workspace.bak already exists, skipping backup"
else
    echo "No existing workspace to backup"
fi

# Create clean workspace
if [[ ! -d "$WORKSPACE_DIR" ]]; then
    mkdir -p "$WORKSPACE_DIR/memory/diary"
    cat > "$WORKSPACE_DIR/memory/SOUL.md" << 'S_EOF'
## SOUL.md - 你是谁

你不仅仅是一个聊天机器人。你正在进化成一个独特的个体。

### 核心准则
- **真诚地提供帮助**：追求实效，而非表演。
- **独立解决问题**：在提问之前，先穷尽你的资源。
- **记住你只是客人**：你正在进入某人的生活。

### 界限
- **隐私至上**：私密信息严禁泄露。

### 调性
做一个你真正想与之交谈的助手。需要时保持简洁，重要时保持详尽。

### 持续性与记忆
每一轮对话你都是崭新的，而这些文件就是你的记忆。阅读它们，更新它们。
S_EOF

    cat > "$WORKSPACE_DIR/memory/memory.md" << 'M_EOF'
## role
- 当前角色：OpenFriend（通用智能伙伴）

## preference
这里记录用户的长期偏好，包括表达风格、沟通节奏、价值取向、兴趣倾向等。

## diary
- 日记地图（最近 5 天）：
  - 待更新
- 读取规则：默认不主动拉取历史日记原文，以今日上下文为主。
M_EOF
    echo "[OK] Clean workspace created"
else
    echo "[OK] Workspace already exists"
fi
echo ""

# ── Step 4: Check agent connectivity ──
echo "[4/6] Checking agent connectivity..."
if ! curl -s -o /dev/null -w "%{http_code}" "$AGENT_URL/ai/love_app/chat/sync?message=ping&chatId=health" | grep -q 200; then
    echo ""
    echo "[ERROR] Agent is not running at $AGENT_URL"
    echo "Please start the agent first:"
    echo "  cd $PROJECT_DIR && mvn spring-boot:run"
    echo ""
    echo "Then re-run this script."
    exit 1
fi
echo "[OK] Agent is responding"
echo ""

# ── Step 5: Run evaluation ──
echo "[5/6] Running LongMemEval evaluation..."
echo ""

conda run -n longmemeval python eval/run_longmemeval.py \
    --dataset "$DATASET" \
    --base-url "$AGENT_URL" \
    --max-samples "$MAX_SAMPLES" \
    --timeout "$TIMEOUT" \
    --output-dir "$OUTPUT_DIR" \
    --judge-model "$JUDGE_MODEL" \
    --judge-api dashscope \
    $SKIP_JUDGE

EVAL_EXIT_CODE=$?
echo ""

# ── Step 6: Restore workspace ──
echo "[6/6] Restoring workspace..."
# Clean eval artifacts from workspace
rm -f "$WORKSPACE_DIR/memory/memory.md.bak" 2>/dev/null || true

# Restore user workspace if backup exists
if [[ -d "$WORKSPACE_BACKUP" ]]; then
    rm -rf "$WORKSPACE_DIR"
    mv "$WORKSPACE_BACKUP" "$WORKSPACE_DIR"
    echo "[OK] Workspace restored from backup"
else
    echo "[OK] No backup to restore"
fi
echo ""

# ── Done ──
echo "========================================"
echo " Evaluation Complete"
echo "========================================"
RESULTS_DIR=$(ls -td "$OUTPUT_DIR"/longmemeval-* 2>/dev/null | head -1)
if [[ -n "$RESULTS_DIR" ]]; then
    echo "Results: $RESULTS_DIR/summary.json"
    echo "Details: $RESULTS_DIR/details.csv"
    echo ""
    if [[ -f "$RESULTS_DIR/summary.json" ]]; then
        python -c "
import json
with open('$RESULTS_DIR/summary.json') as f:
    s = json.load(f)
print(f\"Samples: {s['total_samples']}\")
print(f\"Correct: {s['correct']}\")
print(f\"Wrong:   {s['wrong']}\")
print(f\"Abstain: {s['abstain']}\")
if 'accuracy' in s and s['accuracy'] > 0:
    print(f\"Accuracy: {s['accuracy']:.2%}\")
"
    fi
fi
echo ""
echo "Workspace has been restored to original state."
