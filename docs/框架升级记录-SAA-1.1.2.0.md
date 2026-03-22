# 框架升级记录：Spring AI Alibaba 1.0.0.2 → 1.1.2.0

## 版本变更

| 组件 | 旧版本 | 新版本 |
|------|--------|--------|
| Spring Boot | 3.4.4 | 3.5.3 |
| Spring AI | 1.0.0 | 1.1.2 |
| Spring AI Alibaba | 1.0.0.2 | 1.1.2.0 |
| Spring AI Extensions | 无 | 1.1.2.1（新增） |
| Agent Framework | 无 | 1.1.2.0（新增） |

## 升级目的

引入 Spring AI Alibaba Agent Framework 的 **Skills 技能体系**（`FileSystemSkillRegistry`、`SkillPromptAugmentAdvisor`、`SkillsAgentHook`），支持从 SkillHub 动态安装和使用 AI 技能。

## 遇到的冲突与修复

### 1. Bean 名称变更（影响最大）

DashScope 自动配置注册的 ChatModel Bean 名从 `dashscopeChatModel` 变为 `dashScopeChatModel`（大写 S）。Spring Boot 3.5.x 对参数名推断更严格，导致所有未加 `@Qualifier` 的注入点报 `NoUniqueBeanDefinitionException`。

**涉及文件**：`LoveApp`、`AgentGateway`、`ManusAgent`、`QueryRewriter`、`MyKeywordEnricher`、`MultiQueryExpanderDemo`

**修复**：统一添加 `@Qualifier("dashScopeChatModel")`

### 2. QuestionAnswerAdvisor 构造方式变更

Spring AI 1.1.2 中 `QuestionAnswerAdvisor` 不再支持单参数构造器，改用 builder 模式。

```java
// 旧：new QuestionAnswerAdvisor(vectorStore)
// 新：QuestionAnswerAdvisor.builder(vectorStore).build()
```

**涉及文件**：`LoveApp.doChatWithRag()`

### 3. Embedding 维度变更（启动时报错）

DashScope 默认 embedding 模型从 `text-embedding-v1`（1536 维）变为 `text-embedding-v3`（1024 维），但代码和配置中硬编码了 `dimensions(1536)`，导致向 PgVector 插入新 embedding 时报 `expected 1536 dimensions, not 1024`。

之前未暴露是因为没有新日记需要 embed（`toAdd` 为空），跳过了 insert。

**涉及文件**：`DiaryVectorStoreConfig`、`KnowledgeVectorStoreConfig`、`PgVectorVectorStoreConfig`、`application.yml`

**修复**：所有 `.dimensions(1536)` 改为 `.dimensions(1024)`，`application.yml` 中 `dimensions: 1536` 改为 `1024`，drop 旧 `vector_store` 表后启动自动重建。

### 4. 无冲突的部分

- **Advisor API**（`CallAdvisor` / `StreamAdvisor`）：接口签名未变，`ReReadingAdvisor`、`VisualizedMemoryAdvisor`、`MyLoggerAdvisor` 三个自定义 Advisor 无需修改
- **Tool API**（`@Tool` / `@ToolParam`）：完全兼容
- **ChatClient API**：`ChatClient.builder()`、`.prompt()`、`.stream()` 等链式调用未变
- **第三方依赖**（hutool 5.8、knife4j 4.4、kryo 5.6）：与 Spring Boot 3.5.x 兼容，无冲突

## 新增模块

| 文件 | 用途 |
|------|------|
| `skill/SkillConfig.java` | 配置 `FileSystemSkillRegistry` + `SkillPromptAugmentAdvisor` |
| `tools/SkillInstallTool.java` | AI 工具：搜索/安装/卸载/列出 SkillHub 技能 |
| `application.yml` 新增 `skillhub` 配置段 | SkillHub 地址和本地 skills 目录 |

## 总结

整体升级平滑，**唯一的破坏性变更是 Bean 名称大小写**。建议后续所有 `ChatModel` 注入点统一使用 `@Qualifier("dashScopeChatModel")`，避免依赖参数名隐式匹配。
