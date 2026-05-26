# ActivityWatch 与 manus_ai_agent：可行性分析

本文回答两件事：**（1）是否可行**（结论与边界见 §1–§4）；**（2）怎么最小跑通**（实现步骤与代码见 §5）。REST 端点速查与落盘路径另见 [user-activity-windows11-requirements.md](./user-activity-windows11-requirements.md)。

---

## 1. 总结论（是否可行）

| 问题 | 结论 |
|------|------|
| **整体是否可行？** | **可行**。AW 在 Win11 上提供本机采集与本机 REST；Java 应用可通过 HTTP 读数据并写入 `workspace/`，与现有 memory 架构 **无根本性冲突**。 |
| **是否有硬阻塞？** | **无**（在「用户自愿安装 AW、本机可跑 aw-server」前提下）。 |
| **主要不确定性** | **产品预期**（是否与 AW 网页统计完全一致）、**隐私/合规**（是否允许记录窗口标题与 URL）、**运维**（AW 版本与 API 演进）。这些影响「值不值得做」，不否定技术可行性。 |

---

## 2. 可行性维度分析

### 2.1 采集层（AW 自身）

| 项 | 分析 |
|----|------|
| **Win11 x64** | AW 官方提供 Windows 安装包；个人开发机通常可安装运行。**可行性：高**。 |
| **本机服务** | 默认仅监听本机（常见 `127.0.0.1:5600`），不依赖你把数据发到公网。**可行性：高**。 |
| **浏览器 URL / 标签** | 依赖 AW 的浏览器 watcher / 扩展与浏览器版本；可能随浏览器更新需调整。**可行性：中高**（非 AW 独有问题，任何方案都类似）。 |
| **与「全操作监控」** | AW **不是**键盘记录器；做不到「每一次点击/输入」。若你的需求被理解为那种级别，**本路线不可行**；若需求是「应用级时间线 + 可选 URL」，**可行**。 |

### 2.2 集成层（Java / Spring 读 AW → 写 workspace）

| 项 | 分析 |
|----|------|
| **HTTP 拉取** | `GET /api/0/buckets/`、`.../events`、`/export` 等均为常规 REST；Java 11+ `HttpClient` 足够。**可行性：高**。 |
| **与 Web UI 数字一致** | 若只拉 **raw bucket**，在统计口径上可能与 UI 的「规范活动」不一致；需 **Query API** 或 **canonical 逻辑**（见 AW 文档）。**可行性：中高**（工程复杂度上升，但仍为可实现软件问题）。 |
| **写入 memory** | 本质是写 `user.dir` 下 Markdown/JSONL，与 [`VisualizedMemoryManager`](../src/main/java/com/manus/aiagent/chatmemory/VisualizedMemoryManager.java) 所用路径模型一致。**可行性：高**。 |
| **与主应用解耦** | 同步任务可异步、可开关；AW 未启动时跳过即可，不阻塞聊天。**可行性：高**。 |

### 2.3 Agent 消费层（对话里「用得上」）

| 项 | 分析 |
|----|------|
| **不新增工具** | 摘要落在 `workspace/memory/` 或 `knowledge/` 后，可通过现有 **`read_memory`**、RAG 加载器间接使用。**可行性：高**（体验依赖 prompt 与检索配置）。 |
| **专用「读行程」工具** | 当前 [`MemoryWorkspaceTool`](../src/main/java/com/manus/aiagent/tools/MemoryWorkspaceTool.java) 无 `read_activity`，需开发才有显式子命令。**可行性：高**（纯功能增量）。 |

### 2.4 隐私、合规与使用场景

| 项 | 分析 |
|----|------|
| **个人自用** | 在 opt-in、本地存储、可删除前提下，与 [user-activity-timeline-research.md §3](./user-activity-timeline-research.md) 一致即可推进。**可行性：取决于你的合规接受度**，非纯技术问题。 |
| **公司电脑 / 组策略** | 可能禁止安装或限制后台采集；**环境相关**，无法在未勘测的电脑上保证可行。 |

### 2.5 维护与长期

| 项 | 分析 |
|----|------|
| **AW 升级** | REST 官方标注可能演进；需锁定测试或跟随发行说明。**风险：中**，可通过集成测试与版本记录缓解。 |
| **依赖第三方安装** | 用户必须装 AW；不能接受则本方案 **不适用**，需改自研采集或其它产品。 |

---

## 3. 不可行或需降预期的边界（避免误判）

1. **「记录用户正在进行的所有操作」**：操作系统与合规层面均 **不可行**作为默认产品能力；AW 也不提供该能力。  
2. **100% 准确还原每一天的意图**：只能得到 **应用/窗口/时间** 等可观测信号，存在 AFK、多显示器、标题为空等误差。  
3. **完全不装任何本机组件、纯云端推断**：与「实用行程」矛盾，**不在本文 AW 路线讨论范围内**。

---

## 4. 综合判断

- **技术可行性**：在 Win11 上 **成立**——AW 提供采集与本机 API，manus 提供落盘与 Agent 消费，边界清晰。  
- **产品可行性**：在需求定义为 **「粗粒度每日行程摘要 + 本地优先 + 用户同意」** 时 **成立**；若定义为 **全量行为审计** 则 **不成立**。  
- **建议**：先做 **「AW 常开 + 定时拉取 + 写 Markdown 摘要」** 验证价值；再决定是否投入 Query/canonical 对齐与专用 Tool。

---

## 5. 最小实现：怎么跑通（含仓库内示例代码）

### 5.1 思路（三条线）

1. **ActivityWatch**：安装并常驻，本机 `127.0.0.1:5600` 可访问（浏览器打开 `http://127.0.0.1:5600` 能进 Web UI）。  
2. **拉数据**：Java 使用 **`java.net.http.HttpClient`** 调用 REST（无需额外 HTTP 依赖）。  
3. **落盘**：写成 **`workspace/memory/activity/{yyyy-MM-dd}-summary.md`**（**整文件覆盖**），与现有 memory 目录约定一致。

### 5.2 前置条件（本机）

| 条件 | 说明 |
|------|------|
| OS | Windows 11 x64（与你环境一致即可） |
| ActivityWatch | [官网](https://activitywatch.net/) 安装并启动；托盘/服务正常 |
| 端口 | 默认 **5600**；若改过端口，需同步改配置 `activitywatch.base-url` |
| 防火墙 | 本机回环一般无拦；若拦了需放行 `java.exe` 访问本地端口 |

### 5.3 生产：职责拆分

| 模块 | 类 | 职责 |
|------|-----|------|
| ActivityWatch | [`ActivityWatchSync`](../src/main/java/com/manus/aiagent/gateway/activitywatch/ActivityWatchSync.java) | **仅**拉 AW REST、聚合、写 `workspace/.../summary.md`（无 `@Scheduled`） |
| 心跳调度 | [`GatewayHeartbeatScheduler`](../src/main/java/com/manus/aiagent/gateway/heartbeat/GatewayHeartbeatScheduler.java) | **仅**定时；`activitywatch.enabled=true` 时注册，内部调用 `ActivityWatchSync.syncTodayActivity()` |

| 项 | 说明 |
|------|------|
| 调度间隔 | `activitywatch.heartbeat-interval-ms`（默认 **1200000** = 20 分钟） |
| 开关 | `activitywatch.enabled=true` 时注册心跳；默认 `false` |
| 配置 | [`application.yml`](../src/main/resources/application.yml) 中 `activitywatch.*`；本地可在 `application-local.yml` 覆盖 |

### 5.4 仓库内手动测试

[`ActivityWatchLocalSmokeTest.java`](../src/test/java/com/manus/aiagent/activitywatch/ActivityWatchLocalSmokeTest.java) 直接 `new ActivityWatchSync(props)`，与线上一致，写入 **`{今日}-summary.md`**。本机无 AW 时测试跳过。

### 5.5 核心代码逻辑

流程：**HTTP 拉 events → 遍历 JSON 数组 → `duration` 按 `data.app` 汇总 → 排序后输出表格 → 覆盖写文件**。实现见 [`ActivityWatchSync`](../src/main/java/com/manus/aiagent/gateway/activitywatch/ActivityWatchSync.java)。

**后续可增强**：按日时间窗过滤、脱敏、可选 `POST /api/0/query` 与 Web UI 对齐。

---

## 6. 相关文档

| 文档 | 内容 |
|------|------|
| [user-activity-timeline-research.md](./user-activity-timeline-research.md) | 范围、隐私、MVP |
| [user-activity-windows11-requirements.md](./user-activity-windows11-requirements.md) | REST 速查、第三方对比、落盘路径 |
