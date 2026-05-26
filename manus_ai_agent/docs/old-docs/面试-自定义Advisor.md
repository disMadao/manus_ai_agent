# 自定义 Advisor — 面试准备文档

---

## 一、简历写法

> **自定义 Advisor 增强链**：基于 Spring AI 的 Advisor 机制，实现了多个自定义 Advisor 对 AI 调用链进行增强。包括：Re-Reading（RE2）推理增强 Advisor，通过在 Prompt 中追加"重新阅读问题"指令提升 LLM 推理准确率；自定义日志 Advisor，在调用链前后拦截请求/响应并记录关键信息。所有 Advisor 同时实现 `CallAdvisor`（同步）和 `StreamAdvisor`（流式）双接口，支持链式编排和优先级控制，架构设计参考了 Spring MVC Filter / Servlet Filter Chain 的拦截器模式。

---

## 二、技术实现讲解

### 2.1 核心思想：Advisor 就是 AI 调用链上的"过滤器"

Spring AI 的 Advisor 机制和 Spring MVC 的 `Filter` / `HandlerInterceptor` 是**同一设计思想**——**责任链模式（Chain of Responsibility）**：

```
请求 → [Advisor A] → [Advisor B] → [Advisor C] → 实际调用 LLM → 响应原路返回
         ↕               ↕               ↕
      改写Prompt      记录日志       注入RAG上下文
```

| 对比项 | Servlet Filter | Spring AI Advisor |
|--------|---------------|-------------------|
| 拦截对象 | HTTP 请求/响应 | AI Prompt 请求/AI 响应 |
| 链式调用 | `FilterChain.doFilter()` | `CallAdvisorChain.nextCall()` |
| 排序控制 | `@Order` / `FilterRegistrationBean` | `getOrder()` 方法 |
| 前置/后置 | `doFilter` 前后写逻辑 | `before` / `observeAfter` |
| 同步+异步 | Filter + WebFlux WebFilter | `CallAdvisor` + `StreamAdvisor` |

### 2.2 这种设计是 Spring 的惯用模式，但思想是通用的

**Spring 生态中的同类设计**：
- `javax.servlet.Filter` — Servlet 过滤器
- `HandlerInterceptor` — Spring MVC 拦截器
- `ClientHttpRequestInterceptor` — RestTemplate 拦截器
- `ExchangeFilterFunction` — WebClient 过滤器
- `ChannelInterceptor` — Spring Messaging 拦截器
- **Spring AI `Advisor`** — AI 调用链拦截器

**通用性**：责任链 + 拦截器模式不是 Spring 发明的，是经典的 GoF 设计模式。但 Spring 把它标准化了——**实现接口 → 注册到链 → 框架自动编排调用**，这套范式在 Spring 各个子项目里反复出现。所以掌握一个，其他的上手成本极低。

### 2.3 实现一个 Advisor 的固定套路

```
1. 实现 CallAdvisor（同步）和/或 StreamAdvisor（流式）接口
2. 实现 getOrder() 控制执行顺序
3. 在 before 阶段改写请求（Prompt），在 after 阶段处理响应
4. 调用 chain.nextCall() / chain.nextStream() 交给下一个 Advisor
5. 注册到 ChatClient.defaultAdvisors() 即生效
```

### 2.4 Re-Reading Advisor（RE2 推理增强）

**论文依据**：RE2（Re-Reading）是一种 Prompt Engineering 技巧——在提问后追加"Read the question again"，实验证明能提升 LLM 推理准确率。

**核心只有一个 `before` 方法**：

```java
private ChatClientRequest before(ChatClientRequest request) {
    String userText = request.prompt().getUserMessage().getText();
    // 改写 Prompt：原问题 + "再读一遍"
    String newUserText = """
            %s
            Read the question again: %s
            """.formatted(userText, userText);
    Prompt newPrompt = request.prompt().augmentUserMessage(newUserText);
    return new ChatClientRequest(newPrompt, request.context());
}
```

同步和流式共用同一个 `before`，区别只在于走 `nextCall` 还是 `nextStream`：

```java
@Override
public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
    return chain.nextCall(this.before(req));  // 改写后传递给下一个
}

@Override
public Flux<ChatClientResponse> adviseStream(ChatClientRequest req, StreamAdvisorChain chain) {
    return chain.nextStream(this.before(req));
}
```

### 2.5 自定义日志 Advisor

**经典的 before/after 拦截模式**：

```java
private ChatClientRequest before(ChatClientRequest request) {
    log.info("AI Request: {}", request.prompt());
    return request;  // 不改写，原样传递
}

private void observeAfter(ChatClientResponse response) {
    log.info("AI Response: {}", response.chatResponse().getResult().getOutput().getText());
}
```

流式场景下需要用 `ChatClientMessageAggregator` 聚合流式片段后再记录：

```java
@Override
public Flux<ChatClientResponse> adviseStream(ChatClientRequest req, StreamAdvisorChain chain) {
    req = before(req);
    Flux<ChatClientResponse> flux = chain.nextStream(req);
    return new ChatClientMessageAggregator()
            .aggregateChatClientResponse(flux, this::observeAfter);
}
```

### 2.6 注册与编排

```java
ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultAdvisors(
                MessageChatMemoryAdvisor.builder(chatMemory).build(),  // 记忆
                new MyLoggerAdvisor(),        // 日志
                new ReReadingAdvisor()         // RE2 推理增强
        )
        .build();
```

`getOrder()` 返回值越小越先执行，和 Spring `@Order` 语义一致。

---

## 三、模拟面试 Q&A

### Q1：Advisor 的设计模式是什么？和 Servlet Filter 有什么关系？

**答**：都是**责任链模式**。Servlet Filter 拦截 HTTP 请求，Advisor 拦截 AI 调用。核心机制一样：每个节点可以**修改请求、修改响应、决定是否往下传递**。Spring 把这个模式标准化了——实现接口、注册到容器、框架自动串成链。Spring AI 的 Advisor 就是借鉴了这套成熟设计。

---

### Q2：这种"实现顶层接口"的方法是 Spring 独有的，还是通用的？

**答**：**思想是通用的，实现范式是 Spring 的惯用手法**。责任链 / 拦截器是 GoF 经典设计模式，不限于 Spring。比如 OkHttp 的 Interceptor、Netty 的 ChannelHandler 也是一样的。但 Spring 的特点是把它做成了**声明式**——实现接口 + 注册 Bean，框架自动发现和编排，开发者不需要手动管理链的组装。Spring AI 复用了这套范式，所以如果熟悉 Spring MVC Filter，写 Advisor 几乎零学习成本。

---

### Q3：为什么要同时实现 CallAdvisor 和 StreamAdvisor？

**答**：因为 Spring AI 的 ChatClient 支持两种调用方式——`.call()`（同步阻塞）和 `.stream()`（Reactor Flux 流式）。两种走不同的调用链。如果 Advisor 只实现了 CallAdvisor，那流式调用时这个 Advisor 就不会生效。同时实现两个接口，才能**不管调用方用哪种方式都能被拦截到**。

---

### Q4：RE2 Advisor 的原理是什么？为什么重复一遍问题就能提升效果？

**答**：RE2 是一篇 Prompt Engineering 的论文提出的技巧。原理是让 LLM 在回答前"再读一遍问题"，相当于**给模型一个显式的"审题"环节**。实验表明对复杂推理任务（数学、逻辑）效果明显，因为 LLM 的 Attention 机制会对重复出现的信息给予更高权重，减少遗漏关键条件。实现上只需要在 Prompt 末尾追加一句话，成本几乎为零。

---

### Q5：Advisor 的执行顺序怎么控制？如果顺序不对会怎样？

**答**：通过 `getOrder()` 方法返回值控制，值越小越先执行，和 `@Order` 语义一致。顺序很重要，比如：
- **记忆 Advisor 应该靠前**：先把历史消息注入上下文，后续 Advisor 才能看到完整对话
- **日志 Advisor 靠前**：记录原始请求
- **RAG Advisor 在中间**：检索后注入上下文
- **RE2 Advisor 在末尾**：最终改写 Prompt

如果顺序反了，比如 RAG 在记忆之前，那 RAG 检索时看不到历史上下文，检索质量会下降。

---

### Q6：如果让你再写一个 Advisor，你会做什么？

**答**：几个实用方向——
1. **敏感词过滤 Advisor**：在 before 阶段检测用户输入的敏感内容，拦截或脱敏后再发给 LLM
2. **Token 用量统计 Advisor**：在 after 阶段记录每次调用消耗的 token 数，做成本监控
3. **限流 Advisor**：按用户/会话维度限流，防止 API 被恶意调用
4. **回答质量评估 Advisor**：在 after 阶段对 LLM 回答做评分，低于阈值自动重试

这些都是同一个套路：实现接口 → before/after → 注册到链上。
