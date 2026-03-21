> 想象很美好，现实很现实，简直一坨。屎上雕花。

# 总体设计

将单纯的记忆操作封装成一个类，将 更多复杂的内容，比如日记提示词、日记 rag、知识库 rag 、重写日记和记忆的部分 封装成 Spring AI 的一个 Advisor，只要直接加到所需要转载这个记忆系统的  ChatClient 中就行。

## 记忆模块
将记忆模块的主要功能设计成一个 VisualizedMemoryManager，包含功能：
- readFullMemory：读取 memory.md
- overwriteMemory：重写 memory.md
- appendDiaryLog：向今天的日记追加内容
- readRecentDiaries：读取最近几天的日记

将上面的 memoryManager 抛给 定义的 VisulizedMemoryAdvisor，实现的两个重要接口  `CallAdvisor（同步调用）, StreamAdvisor（流式调用）`，里面还包含的重要数据对象：
- memoryManager：记忆操作
- shortTermMemory：短期记忆
- chatModel：模型
- chatMessageStore：基于 JdbcTemplate 的消息持久化，java代码里封装 嵌入需要的 sql。
主要功能：
- adviseCall：同步调用
- adviseStream：流式调用
- buildUpdateRequest：自定义的方法，被上方两个方法调用，用于实现 rag 检索历史日记 增强用户的 query。
- persistRound：用于在达成条件后重写今天的日记，条件指短期消息达到指定的数量。

## rag
记忆部分 rag 检索实现很简陋，全部用的现成的框架：
```java
List<Document> results = diaryVectorStore.similaritySearch(  
        SearchRequest.builder()  
                .query(query)  
                .topK(3)  
                .similarityThreshold(0.5)  
                .build()  
);  
if (results == null || results.isEmpty()) return "";  
  
return results.stream()  
        .map(Document::getText)  
        .collect(Collectors.joining("\n---\n"));
```
生成、增强部分就是普通的提示词拼接。
还剩下的部分就是 文档的处理了。切 check和token 的策略，文档加载策略、利用文档信息打上元信息的策略。这些东西都是直接配置在 VectorStore 的Config类中，最终被Spring框架自动加载。
实现了 按chucksize 切分文档。
```java
/**  
 * 自定义基于 Token 的切词器  
 */  
@Component  
class MyTokenTextSplitter {  
    public List<Document> splitDocuments(List<Document> documents) {  
        TokenTextSplitter splitter = new TokenTextSplitter();  
        return splitter.apply(documents);  
    }  
  
    public List<Document> splitCustomized(List<Document> documents) {  
        TokenTextSplitter splitter = new TokenTextSplitter(200, 100, 10, 5000, true);  
        return splitter.apply(documents);  
    }  
}
```
基于AI的文档元信息增强器。这就是个string处理，不展示了。

最终的向量库是一个 VectoreStore，通过spring的config机制配置，将上面的东西组装起来，最后将一个 VectoreStore 传入智能体的构造函数中的 visualizedMemopryAdvisor 的构造函数中，这里的 调用关系 loveApp -> visualizedMemoryAdvisor -> diaryVecotrStore：
```java
/**  
 * 日记记忆专用向量库（与知识库 loveAppVectorStore 分离）  
 * MVP 阶段用内存向量库，后续可切换到 PGVector 持久化  
 */  
@Configuration  
public class DiaryVectorStoreConfig {  
  
      
    @Resource  
    private LoveAppDocumentLoader loveAppDocumentLoader;  
  
    @Resource  
    private MyTokenTextSplitter myTokenTextSplitter;  
  
    @Resource  
    private MyKeywordEnricher myKeywordEnricher;  
  
    @Resource  
    private DiaryDocumentLoader diaryDocumentLoader;  
    @Bean  
    VectorStore diaryVectorStore(EmbeddingModel dashscopeEmbeddingModel) {  
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel).build();  
        // 加载文档  
        List<Document> documentList = loveAppDocumentLoader.loadMarkdowns();  
        documentList.addAll(diaryDocumentLoader.loadMarkdowns());  
        // 自主切分文档  
//        List<Document> splitDocuments = myTokenTextSplitter.splitCustomized(documentList);  
        // 自动补充关键词元信息  
        List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(documentList);  
        simpleVectorStore.add(enrichedDocuments);  
        return simpleVectorStore;  
    }  
}
```

## 出现的问题
这里麻烦的是 Spring 的相关配置，因为要接入 **Postgre**SQL 的扩展  pgvector 。会遇到一连串的spring 依赖问题。报错老长一串。之前的系统是没有做消息的持久了的，所以这里通过 JdbcTemplate 向Postgre 中持久化消息，虽然没什么用，本来就是demo，还是加上吧，后面方便可能的扩展。
实际上在设计中还要加上让 ai 可以从大量历史消息中查找消息的工具调用的，实现应该也简单。






