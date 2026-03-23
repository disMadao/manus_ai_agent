<!-- 项目名称与介绍：与当前主入口 OpenFriend、AgentGateway 行为一致（2026-03） -->
# OpenFriend / Manus AI Agent

## 项目介绍

基于 **Spring Boot 3 + Java 21 + Spring AI** 构建的通用智能体后端：**OpenFriend** 为默认对话智能体（`ChatClient` + Advisor + 工具）；**AgentGateway** 按 `mode` 分流 **normal / thinking / super**，其中 **super** 走 **ManusAgent**（ReAct 式工具循环）。配套 **workspace** 落盘记忆、RAG（PGVector）、多渠道网关扩展与 ActivityWatch 行为摘要等能力。

### 核心功能

- **OpenFriend**：多轮对话、流式输出（含深度思考开关）、可视化记忆（Advisor + `workspace/memory`）、RAG、工具调用、Skills 提示增强、可选 MCP 工具接入
- **统一网关（AgentGateway）**：同一套 HTTP/SSE 入口按模式路由至 OpenFriend 或 ManusAgent，超级模式结束后持久化会话
- **ManusAgent**：基于 ReAct 思路的分层智能体（`BaseAgent` → `ToolCallAgent` → `ManusAgent`），支持多步工具调用与终止控制
- **工具与扩展**：联网搜索、文件操作、网页抓取、资源下载、终端操作、PDF 生成、工作区记忆工具、SkillHub 安装技能等
- **MCP**：独立 **image-search-mcp-server**；主工程可通过 `mcp-servers.json` 以 stdio 方式接入高德地图等 MCP（可选）

## 技术栈

- Java 21 + Spring Boot 3
- Spring AI + LangChain4j
- RAG 知识库 + PGVector 向量数据库
- Tool Calling 工具调用
- MCP 模型上下文协议
- ReAct Agent 智能体
- SSE 异步推送
- Ollama 本地大模型部署
- Kryo 序列化 + Jsoup 网页抓取 + iText PDF 生成 + Knife4j 接口文档

## 项目结构

<!-- 目录树与仓库一致；运行时工作目录下的 workspace/ 不入 jar，勿与 src 混淆 -->
```
manus_ai_agent/
├── src/main/java/com/manus/aiagent/
│   ├── advisor/                  # 自定义 Advisor（如 VisualizedMemoryAdvisor）及 advisor/config
│   ├── agent/
│   │   ├── app/                  # OpenFriend（主 ChatClient 智能体）
│   │   ├── manus/                # ManusAgent、ReAct/ToolCall 分层与状态机
│   │   └── LiteAgent.java        # 轻量单次调用（如记忆工具内联改写）
│   ├── gateway/                  # AgentGateway、ManusMemoryEnricher、GatewayController
│   │   ├── channel/              # 飞书 / QQ 等渠道抽象（与统一请求模型配合）
│   │   ├── model/                # GatewayRequest / GatewayResponse
│   │   ├── activitywatch/        # ActivityWatch REST → workspace/memory/activity 摘要
│   │   └── heartbeat/            # 定时触发 ActivityWatch 同步等
│   ├── chatmemory/               # 可视化记忆管理、消息存储、MemoryConfig
│   ├── controller/               # REST（如 AiController、HealthController）
│   ├── rag/                      # 向量库、文档加载、查询重写、LoveApp* 遗留命名配置
│   ├── skill/                    # FileSystemSkillRegistry、SkillPromptAugmentAdvisor
│   ├── tools/                    # ToolRegistration、各类 @Tool
│   ├── config/                   # 如 CorsConfig
│   ├── constant/
│   └── demo/                     # 试验与示例代码
├── src/main/resources/           # application*.yml、document/ 等
├── workspace/                    # 运行时：memory（SOUL/memory/diary/activity）、skills 等
├── tmp/                          # 临时文件目录（如工具写盘约定）
├── ai-agent-frontend/            # 前端（Vue 3）
├── image-search-mcp-server/      # MCP 图片搜索独立服务
└── docs/                         # 设计说明与集成文档
```

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.9+
- Node.js 16+（前端）

### 后端启动

1. 在 `src/main/resources/application.yml` 中配置 AI 大模型 API Key
2. 运行主类 `AiAgentApplication`
3. 访问 `http://localhost:8123/api/swagger-ui.html` 查看接口文档

### 前端启动

```bash
cd ai-agent-frontend
npm install
npm run dev
```
### PostgreSQL 启动
```bash
docker run -e POSTGRES_USER=leikooo -e POSTGRES_PASSWORD=mypassword -e POSTGRES_DB=mydb --name postgresql -p 5432:5432 -v D:/code/manus_ai_agent/PostgreData:/var/lib/postgresql/data -d ankane/pgvector
docker exec -it postgresql psql -U leikooo -d mydb
CREATE EXTENSION IF NOT EXISTS vector ; //启用pgvector扩展
\dx
```
### MCP 服务启动

```bash
cd image-search-mcp-server
mvn spring-boot:run
```

## 配置说明

### 要跑起来需要配置什么

| 配置项 | 是否必填 | 说明 |
|--------|----------|------|
| `spring.ai.dashscope.api-key` | **必填** | 阿里云百炼/灵积大模型 API Key，对话与 Embedding 均依赖此 Key |
| `search-api.api-key` | **必填** | [SearchAPI](https://www.searchapi.io/) 的 API Key，ManusAgent 的联网搜索工具需要 |
| 数据源 + PGVector | 可选 | 使用 RAG 知识库时需要 |
| MCP 连接配置 | 可选 | 使用 MCP 图片搜索等能力时需要，并需先启动 `image-search-mcp-server` |

### AI 大模型配置（DashScope）

`dashscope` 即**阿里云百炼/灵积的大模型 API Key**，用于对话模型（Chat）和向量模型（Embedding）。在 `application.yml` 中配置：

```yaml
spring:
  ai:
    dashscope:
      api-key: your-api-key   # 替换为你在阿里云控制台申请的 API Key
      chat:
        options:
          model: qwen-plus    # 对话模型，可改为 qwen-turbo 等
```

**代码中的使用出处：**

- **Spring AI 自动装配**：依赖 `spring-ai-alibaba-starter-dashscope`，会根据 `spring.ai.dashscope.*` 自动创建 `ChatModel`、`EmbeddingModel`，并被以下组件注入使用：
  - `AiController`、`LoveApp`、`ManusAgent`、`QueryRewriter`、`MyKeywordEnricher` 等使用 **ChatModel**（对话）
  - `PgVectorVectorStoreConfig`、`LoveAppVectorStoreConfig` 使用 **EmbeddingModel**（向量化）
- **直接读取 API Key**：`LoveAppRagCloudAdvisorConfig` 中通过 `@Value("${spring.ai.dashscope.api-key}")` 使用，用于阿里云知识库 RAG（见 `rag/LoveAppRagCloudAdvisorConfig.java`）。

### 联网搜索配置（SearchAPI）

ManusAgent 的网页搜索工具依赖 [SearchAPI](https://www.searchapi.io/)（当前使用百度引擎）。在 `application.yml` 中配置：

```yaml
search-api:
  api-key: 你的 API Key   # 在 ToolRegistration 中注入，供 WebSearchTool 使用
```

出处：`tools/ToolRegistration.java` 注入 `search-api.api-key`，并传给 `tools/WebSearchTool.java` 发起搜索请求。

### 向量数据库（可选）

如需使用 RAG 功能，需在 `application.yml` 中取消注释并填写：

- `spring.datasource`：PGVector 所在 PostgreSQL 的 url、username、password
- `spring.ai.vectorstore.pgvector`：向量库相关配置（当前在配置中为注释状态）

相关代码：`rag/PgVectorVectorStoreConfig.java`、`rag/LoveAppVectorStoreConfig.java`。

## License

MIT
