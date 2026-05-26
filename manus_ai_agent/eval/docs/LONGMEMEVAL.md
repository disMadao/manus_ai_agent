# LongMemEval 测试指南

## 概述

LongMemEval 是 ICLR 2025 的长期记忆 benchmark，包含 500 个高质量问题，测试 5 种核心记忆能力：

| 类型 | 说明 |
|------|------|
| `single-session-user` | 单会话用户信息提取 |
| `single-session-assistant` | 单会话助手指令记忆 |
| `single-session-preference` | 单会话偏好记忆 |
| `temporal-reasoning` | 时间推理 |
| `knowledge-update` | 知识更新（信息变更） |
| `multi-session` | 跨会话推理 |

数据集地址：https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned

## 文件说明

```
eval/
├── run_longmemeval.py   # 测试主脚本（Python）
├── run_longmemeval.sh   # 一键启动脚本（Linux/Mac/Git Bash）
├── run_longmemeval.bat  # 一键启动脚本（Windows）
├── run_ragas_eval.py    # 原有的 RAGas 评估脚本（不相关）
├── docs/
│   └── LONGMEMEVAL.md   # 本文档
└── results/             # 测试结果输出目录
    └── longmemeval-<timestamp>/
        ├── summary.json  # 汇总结果
        └── details.csv   # 逐样本详情
```

## 环境准备

### 1. Python 环境

```bash
conda create -n longmemeval python=3.11 -y
conda run -n longmemeval pip install httpx openai
```

### 2. 数据集

```bash
# 从 HuggingFace 下载（国内用 hf-mirror）
curl -L -o data/longmemeval_oracle.json \
  "https://hf-mirror.com/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_oracle.json"

# 或者用 s 版本（更多上下文，约 277MB）
curl -L -o data/longmemeval_s_cleaned.json \
  "https://hf-mirror.com/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_s_cleaned.json"
```

推荐使用 `oracle` 版本（15MB），仅包含证据 session，最精简。

### 3. Agent 服务

确保 agent 在 `http://localhost:8123` 运行：

```bash
mvn spring-boot:run
```

## 一键测试

```bash
# Windows
eval\run_longmemeval.bat

# Linux/Mac/Git Bash
bash eval/run_longmemeval.sh

# 自定义参数
bash eval/run_longmemeval.sh --samples 20 --dataset data/longmemeval_s_cleaned.json
```

## 手动测试

### Dry-run 检查数据格式

```bash
conda run -n longmemeval python eval/run_longmemeval.py \
  --dataset data/longmemeval_oracle.json --dry-run
```

### 无 Judge 模式（只收集 agent 回答）

```bash
conda run -n longmemeval python eval/run_longmemeval.py \
  --dataset data/longmemeval_oracle.json \
  --max-samples 30 \
  --skip-judge
```

### 带 GPT-4o Judge（需 OPENAI_API_KEY）

```bash
export OPENAI_API_KEY=sk-xxx
conda run -n longmemeval python eval/run_longmemeval.py \
  --dataset data/longmemeval_oracle.json \
  --max-samples 50
```

## 测试原理

### 数据注入方式

测试脚本将每条 LongMemEval 样本的 `haystack_sessions`（对话历史）写入 `workspace/memory/memory.md` 的 `## bench_data` 区块。

Agent 在每次对话时会通过 `VisualizedMemoryManager.exportAllContext()` 读取 `memory.md`，自然获得这些对话历史作为上下文。

```
┌─────────────────────────────────────────────────────┐
│ Python 测试脚本                                      │
│                                                     │
│  1. 读取 LongMemEval JSON                           │
│  2. 格式化 haystack_sessions → Markdown             │
│  3. 写入 workspace/memory/memory.md (## bench_data) │
│  4. 调用 GET /api/ai/love_app/chat/sync             │
│  5. Agent 读取 memory.md → 回答                     │
│  6. (可选) GPT-4o Judge 评判                        │
│  7. 恢复原始 memory.md                              │
└─────────────────────────────────────────────────────┘
```

### 无侵入设计

- 测试前备份 `memory.md` → `memory.md.bak`
- 测试后自动恢复
- 每次测试使用独立 `chatId`（`lme-{question_id}`）
- 不修改 agent 代码

## 结果解读

`summary.json` 字段：

| 字段 | 说明 |
|------|------|
| `total_samples` | 总样本数 |
| `correct` | 回答正确数 |
| `wrong` | 回答错误数 |
| `abstain` | 正确拒答数（abstention 题） |
| `accuracy` | 准确率（排除 abstain 题） |
| `abstention_accuracy` | 拒答准确率 |
| `request_failures` | 网络/服务错误数 |

`details.csv` 逐样本详情包含：`question_id`, `question`, `reference_answer`, `hypothesis`（agent 回答）, `label`, `judge_reason`, `elapsed_seconds`。

## 已知限制

1. **Judge 需要 OpenAI API key**：默认 `--skip-judge`，不加参数跳过评判
2. **仅支持 GET 同步接口**：agent 需要 `/api/ai/love_app/chat/sync` 端点
3. **memory.md 作为注入路径**：依赖 agent 的 `exportAllContext()` 读取该文件
4. **不支持 _m_cleaned 版本**：500 session 版本超出大多数模型上下文窗口
