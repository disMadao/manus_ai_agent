# AI 超级智能体项目

## 项目介绍

基于 **Spring Boot 3 + Java 21 + Spring AI** 构建的 AI 智能体应用，包含 AI 恋爱大师应用和基于 ReAct 模式的自主规划智能体 ManusAgent。

### 核心功能

- **AI 恋爱大师应用**：基于 AI 大模型的情感咨询应用，支持多轮对话、对话记忆持久化、RAG 知识库检索、工具调用、MCP 服务调用
- **AI 超级智能体（ManusAgent）**：基于 ReAct 模式的自主规划智能体，可利用网页搜索、资源下载和 PDF 生成等工具，自主推理和行动完成复杂任务
- **AI 工具集**：联网搜索、文件操作、网页抓取、资源下载、终端操作、PDF 生成
- **MCP 图片搜索服务**：基于 MCP 协议的图片搜索微服务

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

```
├── src/                          # 后端主项目
│   ├── main/java/com/manus/aiagent/
│   │   ├── advisor/              # 自定义 Advisor
│   │   ├── agent/                # AI 智能体（ManusAgent）
│   │   ├── app/                  # AI 应用（恋爱大师）
│   │   ├── chatmemory/           # 对话记忆持久化
│   │   ├── config/               # 配置类
│   │   ├── controller/           # 接口控制器
│   │   ├── rag/                  # RAG 知识库
│   │   └── tools/                # AI 工具集
│   └── main/resources/           # 配置文件和知识库文档
├── ai-agent-frontend/            # 前端项目（Vue 3）
└── image-search-mcp-server/      # MCP 图片搜索服务
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
