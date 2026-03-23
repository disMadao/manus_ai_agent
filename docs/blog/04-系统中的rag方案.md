
# RAG

系统中需要用到RAG的有两个地方，一个是 日记，一个是 知识库。

已现在的模型能力，直接用提示词全塞给模型，最后的输出表现应该更好，用rag好处主要是隐私和节省token。

## 两套PGVector
### 知识库向量：KnowledgeVectorStore

#### KnowledgeDocumetloader
用途：按照用户当前句做相似度检索，拼进系统提示。
现有的加载是使用 水平分割线`---` 分割块的。我一个很长的md文件，经过下面的代码 会被分割成很多的 Document。
```java
MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
.withHorizontalRuleCreateDocument(true)   // 遇到水平线（---）时，将其作为分块边界
.withIncludeCodeBlock(false)              // 忽略代码块（```...```）中的内容
.withIncludeBlockquote(false)             // 忽略引用块（> ...）中的内容
.withAdditionalMetadata("filename", filename)  // 给所有生成的文档块添加文件名元数据
.build();
MarkdownDocumentReader markdownDocumentReader = new MarkdownDocumentReader(resource, config);
List<Document> docs = markdownDocumentReader.get();
```
这里的文档加载器的元信息添加做的还有待提升，感觉比较好的方法是，指定一套规则，直接从文件名中提取部分元信息，用户赛文件的时候也需要考虑按规则取名。
这需要提前规划好有哪些取名、哪些类别，不同类别的分类有什么不同。
#### KnowledgeVectorStoreConfig
使用的 `text-embedding-v3`（1024 维）。很坑的点，为什么这个维度一定要显示声明？！之前embedding模型升级导致我的服务挂了。
配置：
```java
VectorStore pgDiaryVectorStore = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(1024)
                .distanceType(COSINE_DISTANCE)
                .indexType(HNSW)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName("vector_store")
                .maxDocumentBatchSize(10)
                .build();
```
**幂等+去重+增量 机制**
启动加载并 enrich 之后，对每个 Document 计算稳定 hash，用于去重/更新。实现知识库有任何变化都能和向量库同步。
这里还涉及到咨询锁的问题，每次启动系统的时候都需要查看知识库是否需要更新。这里为防止多实例出错（实际这里是ai反应过度，我没说过会用上多实例）,需要加锁实现。
这问题第一时间我都没反应过来，这是postgresql原因？为什么用mysql就从来没遇上过这种低级问题？这种简单的并发还要手动加锁？
这里其实是因为Spring AI的 VectoreStore接口，把底层存储封装成了Java的对象。隔了一层抽象。
所以在 Bean 初始化阶段多实例并发 时才会出现问题。
**如何解决这个问题呢？**
这种并发只会出现在多实例且同时操初始化这个Bean的时候，比如一个实例的多个副本同时启动之类的，这里的锁是为了只让一个实例来执行这些操作，其他实例要么等待，要么直接跳过。
这个锁进入后是通过判断上面的 chunk_hash是否变化来判断是否需要更新知识库的向量。
代码使用的是 PostgreSQL 提供的咨询锁（Advisory Lock），这是一种与应用逻辑绑定的轻量级锁，不依赖任何数据库表结构。
而且可以实现分布式，速度和redis集群锁差不多，比mysql的好很多。
而且就算后面我想实现一个按钮检查知识库更新的功能，也是需要这样加锁的。核心原因不只是VectoreStore这一层抽象，应该说抽象的原因就是我的这个功能要求不是 常见的单行更新、简单的选择几行更新。
而是很多行的更新。是批量同步，所以要加锁做幂等，防止重复操作。

**总结**
简单来说，这段代码的整体目标就是让向量库的初始化变成一个“无状态”的幂等操作。通过锁和内容hash指纹实现。
这里的“无状态”指的是初始化过程和之前的历史初始化都没有关系，每次都会重新检查。

### 日记向量：DiaryVectorStore
用途：检索无法全部塞进去的大量记忆。

#### DiaryDocumentLoader
文档加载器只要用日期做元数据，应该没问题。
日记的元数据：
```java
Document doc = docs.get(i);
doc.getMetadata().put("source", "diary");
doc.getMetadata().put("kind", "daily");
doc.getMetadata().put("date", date);
doc.getMetadata().put("chunk_index", String.valueOf(i));
```
日记其实也是用了相同的切块策略，但日记内容一般比较少，所以很少切块。

### 文档增强器
用于给每个文档的元信息添加几个内容的标签。之前添加在了日记中，但感觉没必要，可以添加在知识库文档中。最好是让用户输入。
现在是用ai实现的：
```java
public class MyKeywordEnricher {

    private final ChatModel dashScopeChatModel;

    public MyKeywordEnricher(@Qualifier("dashScopeChatModel") ChatModel dashScopeChatModel) {
        this.dashScopeChatModel = dashScopeChatModel;
    }

    public List<Document> enrichDocuments(List<Document> documents) {
        KeywordMetadataEnricher keywordMetadataEnricher = new KeywordMetadataEnricher(dashScopeChatModel, 5);
        return  keywordMetadataEnricher.apply(documents);
    }
}
```
后续要优化成可选让用户输入的情况，考虑在 knowledge 中添加一个json配置文件用于给每个文件进行这样的配置。
>如果用户修改了配置的元信息修改之后是否需要重新加载文档呢？


## 索引构建流程
KnowledgeVectorStoreConfig：读知识文档 → MyKeywordEnricher 补关键词元数据 → 算 chunk_hash 去重 → pg_try_advisory_lock 防并发 → 增量写入 knowledgeVectorStore。
DiaryVectorStoreConfig：读日记目录 Markdown → 同样 enrich + hash → advisory lock → 增量写入 diaryVectorStore。
PgVectorVectorStoreConfig：另一路 pgVectorVectorStore + LoveAppDocumentLoader，偏演示/备用，与上面两套可并存，主对话记忆链路里常用的是 knowledge / diary 两个 Bean。

## 索引发生在哪
A. OpenFriend 主链路（VisualizedMemoryAdvisor）
每次带 CONVERSATION_ID 的请求在 buildUpdatedRequest 里会：
用本轮用户话拼成 userQuery；
调用 searchKnowledgeByRag(userQuery) → 对 knowledgeVectorStore 做 similaritySearch（topK=3, similarityThreshold=0.5）；
若有结果，追加到 system 里：[RAG 检索到的相关知识库内容，参考其中的风格]；
再拼上磁盘已加载的 loadedSystemContent（SOUL + memory + 今日日记等）和短期记忆历史。
注意：同文件里的 searchDiaryByRag 目前没有被调用，所以「日记向量库」不是在每轮对话里自动 RAG 进 prompt 的，主要用于向量库里的日记检索能力和坍缩时写入向量等逻辑。
B. 超级模式（ManusMemoryEnricher）
AgentGateway 创建 ManusAgent 前会用 buildEnrichedSystemPrompt：读 memory.md、今日日记 + 对 knowledgeVectorStore 按用户消息做一次相似度检索，把结果拼进 system，与 OpenFriend 侧「知识库 RAG」同源。
C. OpenFriend.doChatWithRag
先 QueryRewriter.doQueryRewrite(message) 改写问题，再走 chatClient；注释里很多 QuestionAnswerAdvisor / 云 RAG 是关掉的，所以当前「RAG」在这条方法里主要是查询重写，未必走完整向量问答链路，需以你本地是否解开注释为准。

## 记忆塌缩与日记向量
VisualizedMemoryAdvisor 在记忆坍缩分支里，会把昨日日记打成 Document diaryVectorStore.add(...)，供以后检索。这和「每轮对话自动检索日记向量」是两条线：写入有，自动检索进 prompt 目前主要靠知识库那路 + 磁盘日记/今日块。




