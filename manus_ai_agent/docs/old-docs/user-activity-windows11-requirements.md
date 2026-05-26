# Windows 11 用户行程采集：工程规格与第三方方案

本文档与 [user-activity-timeline-research.md](./user-activity-timeline-research.md) 配套：**前者**定范围、MVP、隐私；**本文档**定 **Win11 工程约束**、**GitHub/现成产品选型**、以及 **如何接入本仓库 `manus_ai_agent`（消费侧）**。

**许可证说明**：下表中的开源项目以各仓库根目录 `LICENSE` 及 Release 为准；接入前请在目标版本上复核。

---

## 1. 与当前仓库的边界

| 层级 | 职责 | 说明 |
|------|------|------|
| 采集 | 第三方或独立小进程 | Win32 高频调用不建议放在 JVM 内；见调研文档 §2.1。 |
| 集成 / 同步 | 可选 Java 模块或外部脚本 | 从 HTTP 或目录读取 → 聚合 → 写入 `workspace/` 下约定路径。 |
| Agent 消费 | `manus_ai_agent` | 通过 **文件** 与 [`MemoryWorkspaceTool`](../src/main/java/com/manus/aiagent/tools/MemoryWorkspaceTool.java) 可读范围对接；不负责采样。 |

`MemoryWorkspaceTool` 当前约定（与行程摘要落地相关）：

- 长期记忆：`workspace/memory/memory.md`
- 按日日记正文：`workspace/memory/diary/{yyyy-MM-dd}.md`（**仅此路径**被 `read_diary_date` 视为「某日日记」）
- 基路径通常等价于 `System.getProperty("user.dir") + "/workspace/memory"`（见 [`VisualizedMemoryManager`](../src/main/java/com/manus/aiagent/chatmemory/VisualizedMemoryManager.java)）

**建议的行程摘要落点（产品可二选一或并存）**：

- `workspace/memory/activity/{yyyy-MM-dd}-summary.md` — 独立区块，便于权限与 RAG 范围控制；或
- 合并进 `memory.md` 的 `## activity`（需与 `write_memory_section` / 塌缩流程协调）。

原始事件可放在 **`workspace/activity/`**（例如 `yyyy-MM-dd.jsonl`），与调研文档 §2.1 一致。

---

## 2. 第三方 / 开源方案对比

### 2.1 总览

| 方案 | 类型 | Win11 | 典型集成方式 | 优点 | 代价 / 风险 |
|------|------|-------|----------------|------|-------------|
| [ActivityWatch](https://github.com/ActivityWatch/activitywatch) | 本地服务 + Web UI | 支持 | **HTTP REST**（`localhost`）拉取 buckets / export | 成熟、有文档、覆盖应用/浏览器/AFK 等 | 需安装；数据较全，**必须做脱敏与过滤** |
| C# 小库 + 自写控制台 | 自建采集 | 支持 | **写 JSONL/MD** 到 `workspace/activity/` | 依赖少、行为可控、易审计 | 自维护；功能需自己迭代（浏览器 URL 等） |
| [window-watcher](https://github.com/jamesmcroft/window-watcher) 等 | 库（NuGet） | 支持 | 嵌入自有 .NET 进程，同上落盘 | MIT、专注前台窗口 | 仅解决「变化事件」，聚合与落盘自建 |
| [Walterlv.ForegroundWindowMonitor](https://github.com/walterlv/Walterlv.ForegroundWindowMonitor) | 库 | 支持 | 同上 | MIT | 同上 |
| [WindowTracking](https://github.com/j-gn/WindowTracking) | 示例项目 | 支持 | 参考/fork 后落盘 | 含浏览器相关探索代码 | 需自行评估隐私与维护 |
| [ActiveWindowWatcher](https://github.com/DanStevens/ActiveWindowWatcher) | .NET 组件 | 支持 | 事件驱动 + 落盘 | MIT | 同上 |
| WakaTime / 编辑器插件 | IDE 侧 | 视编辑器 | API / 导出 | 开发时间准确 | **不是全桌面行程**，仅作补充信号 |
| Selfspy 等 | 历史项目 | 视情况 | 一般不建议首选项 | — | 维护与 Win11 适配需单独评估 |

### 2.2 推荐决策顺序

1. **可接受安装第三方、希望少写采集代码**：优先 **ActivityWatch**，Java 侧只做 **同步器 + 摘要 + 脱敏**，写入 `workspace/`。
2. **要求最小依赖、二进制可控**：选用 **MIT 类前台窗口库** 写独立 logger，**ActivityWatch 仍可作为对照** 写在本文档 §2.1 表中备查。
3. **仅关心写代码时长**：WakaTime 等作为 **辅助**，不替代桌面行程。

---

## 3. 接入方式（HTTP / 文件 / MemoryWorkspace）

### 3.1 ActivityWatch：HTTP（REST）

默认服务地址以安装为准，常见为 **`http://127.0.0.1:5600`**。官方说明：**仅允许本机连接**，非 localhost 可能被拒绝。

**发现 bucket（交互式排错）**

```http
GET http://localhost:5600/api/0/buckets/
```

**导出全部 bucket（JSON）**

```http
GET http://localhost:5600/api/0/export
```

命令行示例（与官方文档一致）：

```bash
curl -sS "http://localhost:5600/api/0/export" -o path/to/export.json
```

Java 11+ 最小示例（与 curl 等价，便于写同步器）：

```java
var client = java.net.http.HttpClient.newHttpClient();
var uri = java.net.URI.create("http://127.0.0.1:5600/api/0/buckets/");
var request = java.net.http.HttpRequest.newBuilder(uri).GET().build();
var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
// response.statusCode() == 200 时，body 为 JSON（bucket_id → metadata）
```

**按 bucket 导出**

```http
GET http://localhost:5600/api/0/buckets/<bucket_id>/export
```

**按 bucket 读事件（常用）**

```http
GET http://localhost:5600/api/0/buckets/<bucket_id>/events
```

更完整的交互式文档：本机安装 AW 后打开 **[http://localhost:5600/api/](http://localhost:5600/api/)**（API 浏览器）。官方注明 API 仍可能演进，以实例与 UI 为准。

### 3.2 事件模型与映射到「行程摘要」（字段意图）

ActivityWatch 的通用事件形状（概念上）：

- `timestamp`：ISO8601（UTC）
- `duration`：秒
- `data`：依 **bucket / event type** 变化

与「当前窗口」常见相关的 `data` 形态（摘自官方文档，实际以本机 bucket 为准）：

| Event type（示例） | `data` 常用字段 | 映射建议 |
|--------------------|-----------------|----------|
| `currentwindow` | `app`, `title` | 映射为「应用名 + 窗口标题」时间线；**标题需脱敏** |
| `web.tab.current` | `url`, `title`, … | 若启用浏览器 watcher：URL 可截断为域名级 |
| `afkstatus` | `status` | 映射为 AFK 段，避免把离开误判为「仍在某应用」 |

Java 集成建议：`java.net.http.HttpClient` 定时拉取 → 在 JVM 内做 **按日聚合** → 写出 Markdown/JSONL → 可选再被 Agent 通过 **读文件** 或 **未来专用 tool** 消费。

### 3.3 文件落盘（自建 logger 或 AW 导出后落盘）

- 原始：`workspace/activity/yyyy-MM-dd.jsonl`
- 摘要：`workspace/memory/activity/yyyy-MM-dd-summary.md`（或你方统一命名）

Agent 侧：**不强制** 新增 tool。若希望摘要进入 **知识库 RAG**，需将 Markdown 放在 [`KnowledgeDocumentLoader`](../src/main/java/com/manus/aiagent/rag/knowledge/KnowledgeDocumentLoader.java) 扫描目录：`workspace/memory/knowledge/*.md`（或以你方后续配置为准）。若仅希望 **随对话记忆** 使用，可合并进 `memory.md` 固定 `##` 区块，或写入 [`DiaryDocumentLoader`](../src/main/java/com/manus/aiagent/rag/memory/DiaryDocumentLoader.java) 所读的 `workspace/memory/diary/*.md`（与「日记」语义是否混用需产品确认）。

### 3.4 本机验证（curl，可选）

在 **已安装并启动** ActivityWatch 后，可用下列命令确认 REST 可用（Windows 请使用 `curl.exe` 以免被 PowerShell 别名劫持）：

```bash
curl.exe -sS "http://127.0.0.1:5600/api/0/buckets/"
```

- **成功**：HTTP 200，响应为 JSON 对象，**键名为各 `bucket_id`**，值内含 metadata（可用于后续 `GET .../events`）。
- **失败**：`curl: (7) Failed to connect ... port 5600` — 表示本机未监听该端口（未安装、未启动或服务使用非默认端口）。属预期，**在目标环境安装 AW 后再测**。

导出全量备份（与官方文档一致）：

```bash
curl.exe -sS "http://127.0.0.1:5600/api/0/export" -o aw-export.json
```

> 说明：集成开发机未常驻 ActivityWatch 时，无需强求本步通过；以安装 AW 后的实际 JSON 结构为准做字段映射。

### 3.5 MemoryWorkspaceTool

当前工具 **不** 包含 `read_activity`；行程接入的 **MVP** 一般是：

- 同步任务写 **Markdown 摘要** → 用户或 `read_memory` / RAG 间接使用；或
- 后续迭代：新增子命令（如 `read_activity_date`）专门读 `workspace/memory/activity/`，需单独开发与测试。

---

## 4. Windows 11 工程注意点（摘要）

- **权限与策略**：公司设备可能限制部分 API；采集进程需 **可关闭、可卸载**。
- **隐私**：第三方采集内容往往比「仅前台标题」更丰富，**产品侧过滤与 opt-in** 必须沿用 [user-activity-timeline-research.md §3](./user-activity-timeline-research.md)。
- **验证**：在已安装 ActivityWatch 的机器上，用 `curl`/浏览器访问 `GET /api/0/buckets/` 核对 **真实 `bucket_id`** 再写同步逻辑。

---

## 5. 文档索引

| 文档 | 内容 |
|------|------|
| [user-activity-timeline-research.md](./user-activity-timeline-research.md) | 范围、MVP、隐私与仓库关系 |
| 本文档 | Win11 工程规格、第三方对比、HTTP/文件/Memory 接入 |
| **[activitywatch-manus-integration.md](./activitywatch-manus-integration.md)** | **ActivityWatch 与 manus 集成的可行性分析**（是否可行、各维度条件与不可行边界） |
