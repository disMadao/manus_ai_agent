# Plan-00 总览（共同约定）

> 本文是 5 个模块 plan 的**共同前提**：技术栈、目录结构、命名、错误码、配置注入。
> 关联文档：[spec.md](./spec.md) · [plan-01-foundation.md](./plan-01-foundation.md) · [plan-02-sandbox.md](./plan-02-sandbox.md) · [plan-03-workspace.md](./plan-03-workspace.md) · [plan-04-chat-agent.md](./plan-04-chat-agent.md) · [plan-05-frontend.md](./plan-05-frontend.md)
>
> | 项 | 值 |
> |----|----|
> | 状态 | Draft v0.1 |
> | 关联 spec | spec.md v0.1（§Constraints C-1 ~ C-10 全部生效） |

---

## 1. 技术栈与版本

| 层 | 技术 | 版本 |
|----|------|------|
| 后端语言 | Java | 21 (LTS) |
| 后端框架 | Spring Boot | 3.4.x |
| Spring AI | spring-ai | 与现有 `manus_ai_agent` 保持一致（DashScope adapter） |
| JDBC | Spring Data JDBC + JdbcTemplate | 跟随 Spring Boot |
| 数据库迁移 | **Flyway** | 10.x（DDL 版本化，禁用 hibernate auto-ddl） |
| 数据库 | PostgreSQL | 16 + pgvector 0.7+ |
| 鉴权 | jjwt（io.jsonwebtoken） | 0.12.x |
| 密码哈希 | Spring Security `BCryptPasswordEncoder` | cost=12 |
| Docker SDK | docker-java | 3.4.x |
| Reactor | Spring WebFlux 自带 | — |
| 测试 | JUnit 5 + Testcontainers（PG + Docker） | — |
| 构建 | Maven 3.9+ | — |
| 前端 | Vue 3 + Vite + Pinia + TypeScript | Vue 3.5+, Vite 5+ |
| 编辑器 | Monaco Editor | 0.50+ |
| HTTP 客户端 | axios + native EventSource (SSE) | — |
| Sandbox 镜像基础 | `python:3.12-slim` + node 20 + 常用 CLI | — |

---

## 2. 仓库与模块布局

**Maven 多模块**（与现有 `manus_ai_agent` 兼容；新项目放在 `manus_ai_agent2.0/`，可独立编译）：

```
manus_ai_agent2.0/
├── pom.xml                                  # 父 pom，依赖管理 + 模块聚合
├── docs/                                    # spec.md / plan-*.md
├── docker-compose.yml                       # L1 单机部署
├── docker/
│   ├── sandbox/Dockerfile                   # Sandbox 镜像（plan-02）
│   └── agent-runner/                        # 容器内 sidecar 源码（Java 子模块）
├── modules/
│   ├── app-bootstrap/                       # 启动入口 + application.yml
│   │   └── src/main/java/com/manus/aiagent2/Application.java
│   ├── module-common/                       # 公共：异常、错误码、JWT 工具、RLS Context、DTO 基类
│   ├── module-foundation/                   # plan-01：Auth + 用户/会话/配额
│   ├── module-sandbox/                      # plan-02：Sandbox Manager + Docker client
│   ├── module-workspace/                    # plan-03：文件 REST + watcher SSE
│   ├── module-chat-agent/                   # plan-04：会话 / 对话 / Agent / 记忆 / RAG
│   └── module-admin/                        # 管理后台（后置）
├── agent-runner/                            # 容器内 sidecar（独立 Spring Boot 应用，最终打成 jar 放进 sandbox 镜像）
└── ai-agent-frontend/                       # plan-05：Vue 3（沿用现有项目名）
```

**包名规约**：`com.manus.aiagent2.<module>`，例如 `com.manus.aiagent2.foundation.auth`、`com.manus.aiagent2.sandbox.docker`。

**Spring Boot 多模块组装**：`app-bootstrap` 在 `@SpringBootApplication(scanBasePackages="com.manus.aiagent2")` 下汇总所有模块。

---

## 3. 共同约定

### 3.1 命名

- REST 路径全小写、`kebab-case`：`/sessions`、`/workspace/files`；
- Java 类：`*Controller` / `*Service` / `*Repository` / `*DTO` / `*Entity`；
- 数据库表：`snake_case` 单数（`user_account`、`chat_session`、`chat_message`）；
- 主键统一 `uuid`（应用层生成 UUIDv7，时间有序便于索引）。

### 3.2 错误码格式

```json
HTTP 4xx/5xx
{
  "code": "AUTH_TOKEN_EXPIRED",
  "message": "登录已过期，请重新登录",
  "traceId": "..."
}
```

**错误码命名空间**（前缀按模块）：

| 前缀 | 模块 | 示例 |
|------|------|------|
| `AUTH_` | foundation 鉴权 | `AUTH_TOKEN_EXPIRED`, `AUTH_BAD_CREDENTIALS` |
| `USER_` | foundation 用户 | `USER_EMAIL_TAKEN`, `USER_NOT_FOUND` |
| `SESSION_` | chat-agent | `SESSION_NOT_FOUND`, `SESSION_LIMIT_REACHED` |
| `QUOTA_` | foundation 配额 | `QUOTA_TOKENS_EXCEEDED`, `QUOTA_AGENT_CONCURRENCY` |
| `SANDBOX_` | sandbox | `SANDBOX_BOOT_FAILED`, `SANDBOX_BUSY` |
| `FILE_` | workspace | `FILE_NOT_FOUND`, `FILE_TOO_LARGE`, `FILE_CONFLICT`, `FILE_PATH_OUTSIDE_WORKSPACE` |
| `RAG_` | chat-agent | `RAG_EMBEDDING_FAILED` |
| `ADMIN_` | admin | `ADMIN_PERMISSION_DENIED` |

### 3.3 配置注入风格

- 用 `@ConfigurationProperties("manus.*")` 强类型绑定，**不要散写 `@Value`**；
- 顶级命名空间：`manus.auth.*`、`manus.sandbox.*`、`manus.workspace.*`、`manus.chat.*`、`manus.quota.*`；
- 敏感配置（DashScope Key、JWT 私钥）通过环境变量注入，不入 git。

### 3.4 日志约束

- 用 SLF4J + Logback；
- MDC 必含 `traceId` / `userId` / `sessionId`（拦截器写入，filter chain 末尾清理）；
- 用结构化字段（`logger.info("event", kv("userId", uid))`）而非拼字符串。

### 3.5 多租户上下文（RLS 联动）

**关键**：每条业务请求进入后，必须在 DB 连接上 `SET app.current_user_id = '<uuid>'`，否则 RLS POLICY 会挡掉所有查询。

- 由 `module-common` 提供 `TenantContext`（ThreadLocal）+ `TenantAwareDataSourceInterceptor`；
- 拦截器顺序：`JWTFilter` → `TenantContextFilter` → 业务；
- 每次借出 Connection 时执行 `SET LOCAL`（事务内有效，避免连接复用串号）。

详细落地见 plan-01-foundation §RLS 集成。

---

## 4. 模块依赖与并行开发顺序

| 阶段 | 可并行的工作 | 依赖 |
|------|-----------|------|
| **第一波** | plan-01 完成（auth + JWT + DDL + RLS 基础设施） | 无 |
| **第二波（并行）** | plan-02 sandbox、plan-04 chat-agent（部分：记忆 / RAG 改造）、plan-05 frontend（搭骨架 + 假数据） | plan-01 |
| **第三波** | plan-03 workspace（消费 plan-02 的 sidecar API）、plan-04 完整对接 sandbox | plan-02 |
| **第四波** | plan-05 前端打通所有真接口 | 02/03/04 |

**关键解耦点**：plan-02 在最早期就要**冻结** sidecar HTTP API 契约（plan-02 §sidecar API），plan-03/04 可基于 mock 实现并行开发。

---

## 5. 数据库总体规划

**单库单 schema**：`public`。所有业务表带 `user_id uuid NOT NULL` 列 + 索引 + RLS POLICY。

**表归属**（详细 DDL 在各 plan）：

| 模块 | 表 |
|------|---|
| foundation | `user_account`, `plan_tier`, `subscription`, `usage_metric_daily`, `idempotency_key`, `rate_limit_counter`, `audit_log`, `refresh_token` |
| chat-agent | `chat_session`, `chat_message`, `agent_run`, `agent_run_step` |
| sandbox | `sandbox`（每用户 1 行）|
| workspace | `workspace`（每用户 1 行）、`file_trash`（软删元数据，可选） |
| rag | 复用 `vector_store`（Spring AI PgVectorStore 自建），追加 metadata 过滤 |

**Flyway 迁移文件**位置：`module-bootstrap/src/main/resources/db/migration/V{yyyyMMddHHmm}__<desc>.sql`，各模块按版本号合作（每个 PR 一个新版本，不修改历史版本）。

---

## 6. 一些跨模块的共享类

放在 `module-common`：

```java
com.manus.aiagent2.common
├── exception/
│   ├── BusinessException     // RuntimeException + code + message
│   └── GlobalExceptionHandler // @RestControllerAdvice
├── tenant/
│   ├── TenantContext         // ThreadLocal<UUID currentUserId>
│   └── TenantConnectionAware // 借出连接时 SET app.current_user_id
├── jwt/
│   ├── JwtIssuer
│   └── JwtVerifier
├── dto/
│   ├── ApiResponse<T>        // { code, message, data }
│   └── PageDTO<T>
├── id/
│   └── UuidV7Generator
└── util/
    ├── PathSafety            // workspace 路径规范化 + 越界检查
    └── ETagUtil              // mtime + size 短 hash
```

---

## 7. 共同测试约定

- 单元测试：JUnit 5 + Mockito，不依赖外部资源；
- 集成测试：Testcontainers（postgres + dind），每个模块 ≥ 1 个 happy path 集成测试；
- API 契约测试：用 RestAssured，断言响应字段 + 错误码常量；
- E2E（v1 收尾）：Playwright 跑前端关键流程（登录 → 创建会话 → 发消息 → 浏览文件 → 编辑文件保存 → 看到 watcher 通知）。

---

## 8. 启动顺序（L1 docker-compose）

```yaml
services:
  postgres:    # 含 pgvector
  api:         # Spring Boot，挂 /var/run/docker.sock 用于拉沙箱
  nginx:       # 反向代理 + 静态前端
  frontend:    # 构建产物或 dev server
  # 无 redis、无 sandbox（按需启）
```

启动后由 `api` 通过 docker-java 拉起每个用户的 `sandbox-<userId>` 容器（lazy）。

---

## 9. 共同的 Definition of Done

任一 plan 的"完成"必须满足：

1. 单元测试覆盖率 ≥ 70%（核心路径 100%）；
2. 集成测试有至少 1 个 happy path；
3. README 写好本模块的本地启动方式；
4. 错误码已登记到 §3.2 表内；
5. 与其他模块的契约（API / 事件 / 数据库表）已在自己 plan 与 spec 里**同步声明**；
6. 通过 `mvn verify` 与 `mvn spotless:check`（统一代码风格）。
