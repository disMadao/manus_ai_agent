# MemoryWorkspaceTool 实现规划（仅本工具，不涉及网关 / Skills）

## 1. 目标

- **一个** Spring `@Component` 类，对外 **一个** `@Tool` 方法（避免多 `@Tool` 膨胀）。
- 用 **`command` + `argument`** 覆盖：`memory.md` 的读/写、**日记地图**片段、**按日期读日记**（只读）、按 `##` 区块名改写 `memory.md`；**不提供「改写今日日记」**；**SOUL.md 仅允许读取，禁止通过本工具写入**（见 §3.1）。
- 依赖仅限：`VisualizedMemoryManager`、`LiteAgent`；**不**新增 Service 层。
- 实现：**[`MemoryWorkspaceTool`](../../src/main/java/com/manus/aiagent/tools/MemoryWorkspaceTool.java)** 已替换原 MemoryOperationTool；[`ToolRegistration`](../../src/main/java/com/manus/aiagent/tools/ToolRegistration.java) 只注册新类一个 Bean。

## 2. 对外签名

```java
@Tool(description = "...见下节，必须把 command 枚举写进 description...")
public String memoryWorkspace(
    @ToolParam(description = "子命令英文关键字，见工具描述中的列表") String command,
    @ToolParam(description = "视 command 而定：可为空、日期、意图、或 section|意图") String argument
)
```

- `command`：**小写**约定（实现里 `trim().toLowerCase(Locale.ROOT)`），非法时返回一行 **支持的 command 列表**。
- `argument`：允许 `null` 或空串；各分支自行校验。

## 3. command 与行为

### 3.1 SOUL.md 约束（产品安全）

- **不允许**用户在对话中通过本工具 **创建/修改/覆盖 SOUL.md**。
- 工具 **不提供** `write_soul`、`write_memory_and_soul` 等任何写入 SOUL 的 `command`。
- **允许** `read_soul`：仅用于模型在推理时查看人设（只读）。
- 若模型或用户话术试图改 SOUL：实现中 **不得**调用 `VisualizedMemoryManager.writeSoul`；`@Tool` description 中明确写「不可通过本工具修改 SOUL」。

### 3.2 命令表

| command | argument | 行为 |
|---------|----------|------|
| `read_memory` | 忽略 | `memoryManager.readMemory()` |
| `write_memory` | 用户意图（必填） | 读当前 memory 全文 → LiteAgent 生成新全文 → `writeMemoryDirect` |
| `read_soul` | 忽略 | `readSoul()`，只读 |
| `read_diary_map` | 忽略 | `memoryManager.getDiaryMapSection()`（**新增**，见 §4） |
| `read_diary_date` | 绝对日期或相对词（必填） | `resolveDateArgument` → `readDiary(yyyy-MM-dd)`。支持 `yyyy-MM-dd`；`today`/`yesterday`/`tomorrow`；`今天`/`昨天`/`前天`/`大前天`；`N天前`（0–3650）。**不**解析「前几天」等模糊说法（由模型追问或自行推断后再填）。 |
| `write_memory_section` | `区块名\|用户意图`（必填，`\|` 分隔） | `findstrH2Section` 定位 `## 区块名` → LiteAgent 生成单区块 → 合并 → `writeMemoryDirect`（**仅 memory.md**，不得写 SOUL 文件） |

## 4. VisualizedMemoryManager 增补

新增 **`String getDiaryMapSection()`**（或 `getDiaryMapLines()`）：

- 逻辑与 `updateDiaryMapLines` 一致：从 `readMemory()` 中定位 `## diary` → `- 日记地图` → 到 `\n- 读取规则：` 之前。
- 若结构缺失：返回空串或简短说明（工具内统一成「未找到日记地图区块」）。

**不**在此类里做「列所有 ## 标题」的独立 API。

## 5. 类内私有方法（不单独建 util 包）

| 方法 | 作用 |
|------|------|
| `findstrH2Section` / `stripFence` | 已在 `MemoryWorkspaceTool` 内实现 |
| `memoryWorkspace` | `switch` 分发各 command |

## 6. LiteAgent 提示词（每个分支一行原则）

- **write_memory**：system 强调「只输出 memory.md 完整正文、无围栏」。
- **write_memory_section**：强调「只输出单个 `##` 区块（含标题行）」。
- **不涉及** SOUL 的 LiteAgent 写入路径；**日记**经本工具仅 `read_diary_date` 读取，无 LiteAgent 改写日记路径。

## 7. Tool 的 description 文案（给模型看）

在 `@Tool(description = "...")` 中**写死**可用 command 列表（中英文关键词可并列一行），例如：

> 统一操作 workspace/memory：command 可选 read_memory、write_memory、read_soul（只读）、read_diary_map、read_diary_date、write_memory_section。**禁止通过本工具修改 SOUL.md**；长对话里用户要看某日/昨天等日记时应调 `read_diary_date`。argument 见各命令说明。

避免模型猜错命令名。

## 8. 注册与清理（已完成）

1. 已新增 `MemoryWorkspaceTool.java`。
2. `ToolRegistration` 已改为注入 `MemoryWorkspaceTool`。
3. 已删除 `MemoryOperationTool.java`。

## 9. 测试建议

- 单测：`command` 非法、日期格式错、`write_memory_section` 无 `|`、找不到 `##` 区块。
- 手工：对真实 `workspace/memory/memory.md` 与 `diary/` 跑一轮 `read_diary_map` → `read_diary_date`。

---

本文件仅约束 **该 Tool 的写法与文件改动**，不包含网关、Skills、前端。

**挂载说明**：OpenFriend 与 Manus 均通过 Spring 容器中的 `allTools` Bean（含本工具）使用 `MemoryWorkspaceTool`；集成验证见测试类 `OpenFriendMemoryWorkspaceIT`、`ManusAgentMemoryWorkspaceIT`（自然语言用户 prompt，需可用模型与密钥）。

**提示可靠性**：`MemoryWorkspacePrompts.SYSTEM_TOOL_ROUTING_BLOCK` 注入 OpenFriend（经 `VisualizedMemoryAdvisor`）与 `ManusAgent` 基础系统提示；网关超级模式通过 `ManusMemoryEnricher` 追加与线上一致的记忆/RAG 块。
