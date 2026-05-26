# eval — Agent 记忆能力评测体系

本目录包含两套评测方案，各自测不同维度：

| 方案 | 入口 | 测什么 |
|------|------|--------|
| LongMemEval（长期记忆） | `run_longmemeval.py` | Agent 从多个会话历史中回忆事实的能力 |
| RAGas（检索增强生成） | `run_ragas_eval.py` | LoveApp 日记 RAG 路径的答案忠实度、上下文精度 |

---

## 一、LongMemEval — 记忆基准测试（推荐）

### 1.1 测什么

LongMemEval 是 ICLR 2025 的 benchmark，专门测试对话助手的**长期记忆能力**。它构建虚拟用户的"聊天历史"，然后问 Agent 需要回忆历史才能回答的问题。

**五种记忆能力：**

| 类型 | 含义 | 示例 |
|------|------|------|
| `temporal-reasoning` | 时间推理 | "我修车之后遇到的第一个问题是什么？"（需要在多段对话中确定修车日期，再找出之后的问题） |
| `multi-session` | 跨会话推理 | "我在甲会议和乙培训中，先参加的是哪个？"（两个事件出现在不同对话中） |
| `knowledge-update` | 知识更新 | "我的手机号换过吗？现在的号码是什么？"（前期对话有旧号码，后期对话更新了） |
| `single-session-user` | 单会话提取 | "我说过我过敏什么食物？"（出现在某一轮对话的用户发言中） |
| `single-session-preference` | 单会话偏好 | "我喜欢哪个牌子的咖啡？"（某次对话中提过的偏好） |
| 拒答（abstain） | 判断信息不存在 | "第三次去露营是和谁一起？"（历史里根本没提过第三次露营） |

**每条数据长什么样（简化版）：**

```json
{
  "question_id": "gpt4_2655b836",
  "question_type": "temporal-reasoning",
  "question": "What was the first issue I had with my new car after its first service?",
  "answer": "GPS system not functioning correctly",
  "haystack_sessions": [
    [
      {"role": "user", "content": "I bought a silver Honda Civic on Feb 10th...", "has_answer": true},
      {"role": "assistant", "content": "Congrats on the new car!..."},
      {"role": "user", "content": "Got the first service on March 15th...", "has_answer": true},
      ...
    ],
    [
      {"role": "user", "content": "My GPS stopped working on March 22nd...", "has_answer": true},
      {"role": "assistant", "content": "That's frustrating..."},
      ...
    ]
  ]
}
```

关键点：`haystack_sessions` 是"干草堆"（多段对话），`question` 是"针"，Agent 需要在干草堆里找到答案。

### 1.2 怎么和你的 Agent 对接的

你的 Agent 启动时会读取 `workspace/memory/memory.md` 作为记忆上下文。评测脚本利用这个机制：

```
┌──────────────────────────────────────────────────────┐
│                    评测流程                           │
│                                                      │
│  Python 脚本                    Java Agent            │
│  ──────────                    ───────────            │
│                                                      │
│  ① 读 LongMemEval JSON                               │
│  ② 提取 haystack_sessions                            │
│     → 格式化成 Markdown                               │
│     → 写入 memory.md                                 │
│     ## bench_data 区块                                │
│                                                      │
│  ③ GET /api/ai/love_app/chat/sync  ──→  OpenFriend   │
│     (只传问题，不传历史)                 ↓            │
│                                   exportAllContext()  │
│                                   读取 memory.md      │
│                                   看到 bench_data     │
│                                   ↓                  │
│                                   调用 LLM 回答       │
│                                                      │
│  ④ 收到回答                                          │
│  ⑤ DashScope qwen-plus  ──→ 评判 CORRECT/WRONG       │
│                                                      │
│  ⑥ 下一个样本（重复 ①-⑤）                             │
│  ⑦ 恢复原始 memory.md（无侵入）                       │
└──────────────────────────────────────────────────────┘
```

**为什么用 memory.md 而不是直接发消息？** 你的 Agent 的聊天接口是 GET 请求，URL 有长度限制。一条 LongMemEval 样本的对话历史动辄 4 万字符，URL 装不下。写入 `memory.md` 是唯一不修改 Java 代码、不走 POST 就能把大量上下文送入 Agent 的方式。

**数据集大小：**

| 文件 | 大小 | 每个问题的 session 数 | 适合 |
|------|------|----------------------|------|
| `longmemeval_oracle.json` | 15MB | 仅证据 session（1-5 个） | 快速测试，已验证可跑 |
| `longmemeval_s_cleaned.json` | 277MB | ~40 个（含干扰） | 正式评测 |
| `longmemeval_m_cleaned.json` | 2.7GB | ~500 个 | 极限长上下文测试 |

### 1.3 环境准备

```bash
# 1. 创建 Python 环境
conda create -n longmemeval python=3.11 -y
conda run -n longmemeval pip install httpx openai

# 2. 下载数据集（国内用 hf-mirror，已自动配置）
curl -L -o data/longmemeval_oracle.json \
  "https://hf-mirror.com/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_oracle.json"

# 3. 启动 Agent（另一个终端）
mvn spring-boot:run
```

### 1.4 一键测试

```bash
# Windows
eval\run_longmemeval.bat --samples 50

# Linux/Mac/Git Bash
bash eval/run_longmemeval.sh --samples 50

# 只收集回答不评判（调试用）
python eval/run_longmemeval.py --dataset data/longmemeval_oracle.json \
  --max-samples 10 --skip-judge
```

### 1.5 当前测试结果

```
评测时间：2026-05-11
数据集：  longmemeval_oracle.json
Agent 模型：DashScope qwen-plus
Judge 模型：DashScope qwen-plus
样本数：  100

┌──────────────┬────────┐
│ 指标          │ 值     │
├──────────────┼────────┤
│ 准确率         │ 77.66% │
│ 正确 (CORRECT) │ 73     │
│ 错误 (WRONG)   │ 21     │
│ 拒答 (ABSTAIN) │ 0*     │
│ 网络失败        │ 0      │
│ 平均耗时        │ ~4.5秒 │
└──────────────┴────────┘

* Agent 没有"我不知道"的机制，对拒答题全部尝试回答。
  LongMemEval 数据集中有 30 道拒答题（占 6%）。
```

**分题型：**

| 题型 | 表现 |
|------|------|
| temporal-reasoning | ~80%（较强——擅长排时间线） |
| multi-session | ~70%（弱项——跨会话信息容易漏） |
| abstain（拒答） | 0%（需要 agent 架构层面支持） |

### 1.6 结果文件

每次运行在 `eval/results/longmemeval-<时间戳>/` 下生成：

| 文件 | 内容 |
|------|------|
| `summary.json` | 汇总指标（准确率、正确/错误/拒答数） |
| `details.csv` | 逐题详情（问题、参考答案、Agent 回答、Judge 标签、耗时） |

---

## 二、RAGas — RAG 质量评测（原有）

### 2.1 测什么

针对 LoveApp 日记知识库的 RAG 检索-回答链路，衡量四个维度：

- `faithfulness` — 回答是否忠实于检索到的上下文（有没有编造）
- `answer_relevancy` — 回答是否切题
- `context_precision` — 检索到的上下文是否精准
- `context_recall` — 有没有遗漏关键上下文

### 2.2 运行

```bash
# 方式一：Java 抓取 + Python 评分（推荐）
mvn -Dtest=LoveAppRagEvalCaptureTest test
python eval/run_ragas_eval.py \
  --captured-jsonl target/ragas/<时间戳>/loveapp_eval_capture.jsonl \
  --gate

# 方式二：HTTP 直调（无需 Java 测试）
python eval/run_ragas_eval.py --gate
```

### 2.3 数据集

`eval/datasets/loveapp_ragas_v1.jsonl` — 20 条恋爱咨询场景的 RAG 测试用例。每条包含 `question`、`reference_answer`、`reference_contexts`。

---

## 三、目录结构

```
eval/
├── README.md                    ← 你正在看的文件
├── run_longmemeval.py           # LongMemEval 评测主脚本
├── run_longmemeval.sh           # 一键启动（bash）
├── run_longmemeval.bat          # 一键启动（Windows）
├── run_ragas_eval.py            # RAGas 评测主脚本
├── requirements.txt             # Python 依赖
├── config/
│   └── thresholds.yaml          # RAGas 质量门禁阈值
├── datasets/
│   └── loveapp_ragas_v1.jsonl   # RAGas 测试用例
├── docs/
│   └── LONGMEMEVAL.md           # LongMemEval 详细文档
└── results/                     # 所有输出结果
    ├── longmemeval-20260511-*/  # LongMemEval 结果
    └── */                       # RAGas 结果
```
