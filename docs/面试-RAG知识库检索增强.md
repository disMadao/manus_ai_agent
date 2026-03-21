# RAG 知识库检索增强 — 面试准备文档

---

## 一、简历写法

> **RAG 知识库检索增强**：基于 Spring AI RAG 模块，实现了完整的检索增强生成流程。文档层面，使用 Markdown 文档读取器加载知识库，通过 TokenTextSplitter 分片，并利用 AI 自动为文档补充关键词元信息（KeywordMetadataEnricher），提升检索召回率。检索层面，支持 **SimpleVectorStore 内存向量库**与 **PGVector 持久化向量库**双方案，采用 HNSW 索引 + 余弦距离度量；同时实现了**查询重写（RewriteQueryTransformer）**优化用户意图表达，以及**基于元信息的过滤检索**实现文档分类精准命中。生成层面，自定义 ContextualQueryAugmenter 控制空检索结果的兜底回复，防止模型幻觉。此外对接了**阿里云百炼知识库 RAG 云服务**作为备选方案，具备本地与云端双链路切换能力。

---

## 二、技术实现讲解

### 2.1 整体架构

RAG 流程分三个阶段：

```
用户提问 → [Pre-Retrieval] 查询重写/扩展 → [Retrieval] 向量检索 → [Post-Retrieval] 上下文增强 → LLM 生成回答
```

### 2.2 文档加载与预处理

**思路**：从 `resources/document/` 目录批量加载 Markdown 知识库文档，按文件名提取分类标签（如"单身""恋爱""已婚"），写入文档元信息，为后续过滤检索做准备。

```java
// LoveAppDocumentLoader.java —— 核心加载逻辑
Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.md");
for (Resource resource : resources) {
    String filename = resource.getFilename();
    // 从文件名提取分类标签，如"单身篇.md" → status="单身"
    String status = filename.substring(filename.length() - 6, filename.length() - 4);

    MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
            .withHorizontalRuleCreateDocument(true)  // 按分割线切分文档
            .withAdditionalMetadata("status", status) // 附加元信息
            .build();
    MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
    allDocuments.addAll(reader.get());
}
```

**要点**：
- `withHorizontalRuleCreateDocument(true)` 利用 Markdown 分割线自然切分 Q&A 文档，比固定 token 切分语义更完整
- 元信息 `status` 从文件名自动提取，零维护成本

### 2.3 文档增强：AI 关键词元信息

**思路**：加载完文档后，调用 LLM 为每篇文档自动生成关键词，写入元信息。检索时可通过关键词匹配提升召回率。

```java
// MyKeywordEnricher.java
public List<Document> enrichDocuments(List<Document> documents) {
    KeywordMetadataEnricher enricher = new KeywordMetadataEnricher(dashscopeChatModel, 5);
    return enricher.apply(documents);  // 每篇文档生成 5 个关键词
}
```

### 2.4 向量存储方案

项目实现了两套方案，可按需切换：

**方案 A：SimpleVectorStore（内存，开发调试用）**

```java
// LoveAppVectorStoreConfig.java
SimpleVectorStore store = SimpleVectorStore.builder(dashscopeEmbeddingModel).build();
List<Document> docs = loveAppDocumentLoader.loadMarkdowns();
List<Document> enriched = myKeywordEnricher.enrichDocuments(docs); // AI 补充关键词
store.add(enriched);
```

**方案 B：PGVector（持久化，生产用）**

```java
// PgVectorVectorStoreConfig.java
VectorStore store = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
        .dimensions(1536)
        .distanceType(COSINE_DISTANCE)
        .indexType(HNSW)
        .initializeSchema(true)
        .build();
store.add(loveAppDocumentLoader.loadMarkdowns());
```

### 2.5 查询重写

**思路**：用户口语化输入（如"被绿了咋办"）可能和知识库文档的书面表达差距大，通过 LLM 重写查询，让检索更精准。

```java
// QueryRewriter.java
QueryTransformer transformer = RewriteQueryTransformer.builder()
        .chatClientBuilder(ChatClient.builder(dashscopeChatModel))
        .build();

public String doQueryRewrite(String prompt) {
    return transformer.transform(new Query(prompt)).text();
}
```

**调用端**：

```java
// LoveApp.doChatWithRag()
String rewrittenMessage = queryRewriter.doQueryRewrite(message);
chatClient.prompt().user(rewrittenMessage)
        .advisors(new QuestionAnswerAdvisor(loveAppVectorStore))
        .call();
```

### 2.6 自定义 RAG Advisor（高级检索）

**思路**：需要对特定分类文档做过滤检索时（如只查"单身"状态的知识），自定义 DocumentRetriever + ContextualQueryAugmenter。

```java
// LoveAppRagCustomAdvisorFactory.java
public static Advisor createLoveAppRagCustomAdvisor(VectorStore vectorStore, String status) {
    // 1. 按元信息过滤
    Filter.Expression expr = new FilterExpressionBuilder().eq("status", status).build();

    // 2. 配置检索器
    DocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
            .vectorStore(vectorStore)
            .filterExpression(expr)
            .similarityThreshold(0.5)
            .topK(3)
            .build();

    // 3. 空结果兜底（防止幻觉）
    return RetrievalAugmentationAdvisor.builder()
            .documentRetriever(retriever)
            .queryAugmenter(LoveAppContextualQueryAugmenterFactory.createInstance())
            .build();
}
```

**空结果兜底**：

```java
// LoveAppContextualQueryAugmenterFactory.java
PromptTemplate emptyContextPromptTemplate = new PromptTemplate("""
        你应该输出下面的内容：
        抱歉，我只能回答恋爱相关的问题，别的没办法帮到您哦
        """);
return ContextualQueryAugmenter.builder()
        .allowEmptyContext(false)    // 禁止空上下文直接回答
        .emptyContextPromptTemplate(emptyContextPromptTemplate)
        .build();
```

### 2.7 云端 RAG 备选方案

对接阿里云百炼知识库服务，无需自建向量库：

```java
// LoveAppRagCloudAdvisorConfig.java
DashScopeApi api = DashScopeApi.builder().apiKey(dashScopeApiKey).build();
DocumentRetriever retriever = new DashScopeDocumentRetriever(api,
        DashScopeDocumentRetrieverOptions.builder()
                .withIndexName("恋爱大师").build());
return RetrievalAugmentationAdvisor.builder()
        .documentRetriever(retriever).build();
```

---

## 三、模拟面试 Q&A

### Q1：介绍一下 RAG，RAG 的具体过程？

**答**：RAG（Retrieval-Augmented Generation，检索增强生成）核心思路是：**先从知识库里检索相关文档，再把文档作为上下文喂给 LLM 生成回答**，让模型基于事实而非凭空编造。

具体过程分五步：

```
1. 离线阶段：文档加载 → 分片 → Embedding 向量化 → 存入向量库
2. 用户提问
3. Pre-Retrieval：查询重写 / 查询扩展，优化检索意图
4. Retrieval：用户查询向量化 → 向量库相似度检索 → 返回 Top-K 文档
5. Generation：将检索到的文档拼接到 Prompt 上下文 → LLM 生成回答
```

在我的项目中，离线阶段用 `MarkdownDocumentReader` 加载文档、`TokenTextSplitter` 分片、DashScope Embedding 向量化、存入 PGVector/SimpleVectorStore；在线阶段用 `QueryRewriter` 做查询重写，`QuestionAnswerAdvisor` 完成检索+上下文注入，一条链路打通。

---

### Q2：RAG 的底层大模型选了什么？为什么？

**答**：
- **对话模型**：阿里通义千问 `qwen-plus`，通过 DashScope API 调用。选它因为中文能力强、API 稳定、成本适中，而且 Spring AI Alibaba 有官方 starter 集成，开箱即用
- **Embedding 模型**：同样用 DashScope 提供的 Embedding 模型，输出 1536 维向量。和对话模型共用一个 API Key，不需要额外接入第三方 Embedding 服务
- 同时项目里也配了 Ollama 支持（`gemma3:1b`），可以切换到本地部署的开源模型，适合离线或数据敏感场景

---

### Q3：RAG 系统是如何评估的？各模块的时延是多少？

**答**：评估分两个维度——

**效果评估**（如果要做的话，用 RAGAS 框架的指标）：
- **Faithfulness**（忠实度）：回答是否基于检索到的文档，不编造
- **Answer Relevance**（回答相关性）：回答是否切题
- **Context Precision**（上下文精确率）：检索到的文档是否真正相关
- **Context Recall**（上下文召回率）：相关文档是否都被检索到

思路是构造一批标注好的测试 QA 对，跑一遍流程，用 LLM 自动打分。

**时延估算**（基于实际调用观测）：

| 模块 | 时延 | 说明 |
|------|------|------|
| 查询重写（LLM 调用） | ~800ms-1.5s | 需要一次 LLM 调用 |
| Embedding 向量化 | ~100-200ms | DashScope API，单次查询 |
| 向量检索（PGVector） | ~10-50ms | 万级文档 HNSW 索引 |
| LLM 生成回答 | ~1-3s | 取决于回答长度，流式可首 token ~300ms |
| **端到端** | **~2-5s** | 不含查询重写约 1.5-3.5s |

查询重写是最大的额外开销，但它对检索质量的提升很明显，是值得的。如果对延迟敏感，可以把查询重写做成可选项，简单查询跳过。

---

### Q4：你的文档上传是怎么确保高质量的？文档的编写相关？

**答**：从三个层面保证——

1. **文档格式规范化**：知识库统一用 Markdown 格式，每个 Q&A 用分割线 `---` 隔开，标题用 `####`。这样 `MarkdownDocumentReader` 能按结构化方式解析，不会出现混乱切分

2. **分类标签体系**：文件名包含分类信息（如"单身篇""恋爱篇""已婚篇"），加载时自动提取写入元信息。这样即使文档增多，分类检索也不受影响

3. **AI 自动质量增强**：用 `KeywordMetadataEnricher` 让 LLM 为每篇文档生成关键词，相当于自动加标签。这比人工打标签效率高、一致性好

如果后续要支持用户上传文档，我会加入**格式校验**（检查 Markdown 结构是否规范）、**内容去重**（向量相似度判重）、**质量打分**（LLM 评估内容可读性和完整性，低于阈值退回）。

---

### Q5：文本切分你是怎么做的？

**答**：两种策略，按场景选择——

**策略一：语义切分（主用）**
利用 Markdown 的分割线 `---` 做自然切分。知识库本身是 Q&A 格式，每个 Q&A 之间有分割线，配置 `withHorizontalRuleCreateDocument(true)` 后，Reader 自动按分割线拆成独立文档。**优势是每个 chunk 语义完整**，不会把一个回答截成两半。

**策略二：Token 切分（备用）**
用 `TokenTextSplitter`，按 token 数切分，支持重叠窗口：
```java
// 每 200 token 切一片，重叠 100 token，防止语义断裂
TokenTextSplitter splitter = new TokenTextSplitter(200, 100, 10, 5000, true);
```
适用于长篇文章（没有天然分隔符的场景）。重叠窗口保证切分边界处的上下文不丢失。

选择思路：**有结构用结构，没结构用 token + 重叠**。

---

### Q6：检索文本的排序与重排序问题？

**答**：当前项目的检索排序策略——

**初次排序**：向量相似度排序（余弦距离），由向量库直接完成，返回 Top-K（默认 Top 3）。再加 `similarityThreshold(0.5)` 过滤低质量结果。

**如果要做重排序（Re-rank）**，思路是：
1. **粗检索阶段**：放大召回量（比如 Top 20），用向量检索快速筛一遍
2. **精排阶段**：用一个 Cross-Encoder 重排序模型（如 bge-reranker）对这 20 条结果重新打分排序，取 Top 3

粗检索是**双塔模型**（query 和 doc 分别 embedding，算余弦），速度快但精度一般；精排是**交叉编码**（query 和 doc 拼在一起过模型），精度高但慢。两阶段结合是工业界标准做法。

Spring AI 目前没有内置 Re-rank，要做的话可以在 `DocumentRetriever` 和 `QueryAugmenter` 之间加一个自定义步骤。

---

### Q7：介绍一下 BM25 算法？

**答**：BM25 是经典的**基于词频的文本相关性算法**，也叫 Okapi BM25，是 TF-IDF 的改进版。

核心公式考虑三个因素：
- **TF（词频）**：查询词在文档中出现的次数，但有**饱和函数**——出现 10 次和 100 次的差别不大（用参数 k1 控制），解决了 TF-IDF 中词频线性增长的问题
- **IDF（逆文档频率）**：查询词在整个语料库中越稀有，权重越高
- **文档长度归一化**：长文档天然词频高，用参数 b 做长度惩罚（b=0 不惩罚，b=1 完全归一化）

和向量检索的区别：BM25 是**精确词匹配**，"吵架"搜不到"争吵"；向量检索是**语义匹配**，能理解同义词。最佳实践是**两路召回 + 融合排序**：BM25 保证精确命中，向量检索保证语义覆盖。

---

### Q8：用户可以直接做 AI 问答吗？

**答**：可以。项目里 AI 问答有三种模式，用户体感上都是直接聊天：

1. **纯对话模式**（`doChat`）：直接和 LLM 聊，带多轮记忆，不走知识库
2. **RAG 问答模式**（`doChatWithRag`）：先检索知识库，再让 LLM 基于文档回答。用户无感，只是回答更准确
3. **工具调用模式**（`doChatWithTools`）：LLM 可以调用搜索、文件操作等工具

三种模式通过不同 API 接口暴露，前端可以按场景选择。RAG 模式下，如果知识库没有相关内容，兜底机制会告诉用户"我只能回答相关领域的问题"，而不是瞎编。

---

### Q9：AI 问答有没有做流式传输？如何做流式传输的？

**答**：做了，两种实现方式——

**方式一：Reactor Flux 流式（恋爱大师模块）**

```java
@GetMapping(value = "/love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> doChatWithLoveAppSSE(String message, String chatId) {
    return loveApp.doChatByStream(message, chatId);
}
```

底层调用 `chatClient.prompt().stream().content()`，Spring AI 返回 `Flux<String>`，每个元素是 LLM 生成的一个 token 片段。Spring WebFlux 自动把 Flux 转成 SSE 事件流推给前端。

**方式二：SseEmitter（超级智能体模块）**

```java
@GetMapping("/manus/chat")
public SseEmitter doChatWithManus(String message) {
    SseEmitter emitter = new SseEmitter(300000L);
    CompletableFuture.runAsync(() -> {
        // 每执行完一个 Step，调用 emitter.send(result) 推送
        sseEmitter.send(stepResult);
    });
    return emitter;
}
```

这是 Spring MVC 的传统 SSE 方案，适合非 Reactive 的场景。Agent 每完成一步推理就 send 一次，前端实时看到思考过程。

---

### Q10：讲下 SSE 的基本原理？

**答**：SSE（Server-Sent Events）是 **HTTP 协议原生支持的服务端推送技术**。

**原理**：
1. 客户端发起一个普通 HTTP GET 请求
2. 服务端返回 `Content-Type: text/event-stream`，**不关闭连接**
3. 服务端随时往这个连接里写数据，格式是 `data: xxx\n\n`
4. 浏览器通过 `EventSource` API 自动监听，每收到一条 `data` 就触发 `onmessage`
5. 连接断开后，浏览器会**自动重连**

**和 WebSocket 的区别**：

| 对比项 | SSE | WebSocket |
|--------|-----|-----------|
| 方向 | 单向（服务端→客户端） | 双向 |
| 协议 | 标准 HTTP | 独立协议（ws://） |
| 复杂度 | 极低，原生支持 | 需要额外握手、心跳 |
| 适用场景 | AI 流式输出、消息推送 | 实时聊天、游戏 |
| 断线重连 | 浏览器自动 | 需要自己实现 |

**为什么 AI 场景选 SSE**：AI 对话是典型的"请求-流式响应"模式，用户发一条消息，服务端不断推送 token 片段。不需要双向通信，SSE 最简单，而且 Spring MVC/WebFlux 原生支持，前端用 `EventSource` 几行代码就搞定。

---

### Q11：你为什么要用 RAG，而不是直接微调模型或加大 Prompt？

**答**：三个原因——
1. **知识时效性**：微调成本高、周期长，而 RAG 只需更新文档即可实时生效
2. **减少幻觉**：RAG 让模型基于检索到的真实文档回答，而非凭空生成
3. **成本**：微调需要 GPU 资源和训练数据，RAG 只需一个向量库和 Embedding 模型

直接塞 Prompt 则受限于上下文窗口长度，知识库大了就放不下。

---

### Q12：为什么选 PGVector 而不是 Milvus 或 Chroma？

**答**：结合项目场景做的选型——
- 项目知识库规模在**几千到几万条**，PGVector 完全够用
- 后端已经用 PostgreSQL，PGVector 是 PG 扩展，**无需引入新中间件**，运维成本最低
- PGVector 支持 **SQL 原生混合查询**（向量 + 标量过滤），比如我按 `status` 过滤特定分类文档，一条 SQL 就能做到
- 同时保留了 SimpleVectorStore 做开发调试，**双方案可切换**

如果后续知识库到亿级规模或需要 GPU 加速，会考虑迁移到 Milvus。

---

### Q13：KeywordMetadataEnricher 的作用是什么？为什么不直接用向量检索？

**答**：纯向量检索有时会漏掉关键实体词。比如文档讲"婆媳关系"，用户搜"和婆婆吵架"，向量语义能匹配上，但如果加了"婆媳""家庭矛盾"等关键词元信息，还能做**关键词+向量的混合检索**，双保险提升召回率。

`KeywordMetadataEnricher` 调 LLM 为每篇文档自动生成 5 个关键词，写入 metadata，后续检索时可做 filter 或 boost。

---

### Q14：你的 RAG 有哪些防幻觉的措施？

**答**：三层防线——
1. **查询重写**：优化查询表达，让检索更准，源头减少"查不到"的情况
2. **相似度阈值**：`similarityThreshold(0.5)`，低于阈值的文档不返回，避免不相关内容误导模型
3. **空结果兜底**：`ContextualQueryAugmenter` 设置 `allowEmptyContext(false)`，检索结果为空时不让模型自由发挥，而是返回固定的引导话术

---

### Q15：SimpleVectorStore 和 PGVector 之间怎么切换的？

**答**：通过 Spring 的 `@Configuration` 注解控制。`PgVectorVectorStoreConfig` 的 `@Configuration` 注释掉时，Spring 不会创建 PGVector 的 Bean，此时生效的是 `LoveAppVectorStoreConfig` 中配置的 SimpleVectorStore。需要上生产时取消注释即可切换，**代码零改动**，符合 Spring Boot 的条件化配置思想。

---

### Q16：如果让你优化这个 RAG 系统，你会怎么做？

**答**：几个方向——
1. **多路召回**：同时走向量检索 + BM25 关键词检索，结果 Re-rank 融合
2. **查询扩展**：用 `MultiQueryExpander` 把一个查询扩展成多个角度的子查询，提升覆盖面（项目里已有 demo）
3. **分层索引**：先用摘要索引粗筛文档，再对命中文档做细粒度检索
4. **评估体系**：加入 RAG 评估指标（如 Faithfulness、Answer Relevance），用 RAGAS 等框架做离线评测，持续优化

---

### Q17：为什么你还做了阿里云知识库的对接？和本地 RAG 有什么区别？

**答**：这是一个**双链路容灾设计**——
- **本地 RAG**：可控性强，数据不出域，适合敏感场景；但需要自己维护向量库和 Embedding 服务
- **云端 RAG**：阿里云百炼提供托管的知识库服务，文档上传后自动分片、向量化、索引，**零运维**；但数据需要上云，且有 API 调用成本

实际项目中，核心知识走本地 RAG 保数据安全，非敏感的公开知识可走云端方案降低运维压力。通过 Spring 的 Advisor 机制，切换只需改一行配置。
