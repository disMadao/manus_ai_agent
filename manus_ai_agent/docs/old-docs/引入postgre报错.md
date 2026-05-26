# Spring AI + PgVector 多Bean注入与数据库初始化调试记录

## 问题描述

在 Spring Boot 应用启动时，`LoveApp` 实例化失败，错误信息不断变化，先后出现：

- `NoUniqueBeanDefinitionException`：存在多个 `VectorStore` 类型的 Bean。
- `NoSuchBeanDefinitionException`：找不到名为 `pgVectorVectorStore` 的 Bean。
- `PSQLException: 不明的类型 vector`：PostgreSQL 不认识 `vector` 类型。
- `PSQLException: relation "public.vector_store" does not exist`：表不存在导致插入失败。

最终目标：使 `LoveApp` 正常启动，并能正确注入多个 `VectorStore` Bean。

## 逐步排查与解决

### 1. 多Bean注入歧义

**现象**
容器中存在 `diaryVectorStore` 和 `loveAppVectorStore` 两个 `VectorStore` Bean，按类型注入时 Spring 无法确定使用哪一个。

**解决方案**
在 `LoveApp` 的构造器和字段上使用 `@Qualifier` 或 `@Resource(name = "...")` 明确指定 Bean 名称：

java

```
public LoveApp(@Qualifier("dashscopeChatModel") ChatModel dashscopeChatModel,
               VisualizedMemoryManager memoryManager,
               @Qualifier("diaryVectorStore") VectorStore diaryVectorStore,
               ChatMessageStore chatMessageStore) { ... }

@Resource(name = "loveAppVectorStore")
private VectorStore loveAppVectorStore;
```



### 2. 缺失 Bean 定义

**现象**
添加 `@Resource(name = "pgVectorVectorStore")` 后，启动报错：找不到名为 `pgVectorVectorStore` 的 Bean。

**解决方案**
创建对应的配置类，定义 `pgVectorVectorStore` Bean：

java

```
@Configuration
public class PgVectorVectorStoreConfig {
    @Bean
    public VectorStore pgVectorVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel dashscopeEmbeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(1536)
                .distanceType(COSINE_DISTANCE)
                .indexType(HNSW)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName("vector_store")
                .build();
    }
}
```



### 3. pgvector 扩展未启用

**现象**
添加 Bean 后，启动报错：`PSQLException: 不明的类型 vector`。
虽然使用了 `ankane/pgvector` 镜像，但扩展需要手动为数据库启用。

**解决方案**
进入容器，为数据库 `mydb` 启用 `vector` 扩展：

bash

```
docker exec -it postgresql psql -U leikooo -d mydb
```



执行 SQL：

sql

```
CREATE EXTENSION IF NOT EXISTS vector;
```



### 4. 表不存在导致插入失败

**现象**
扩展启用后，启动报错：`relation "public.vector_store" does not exist`。
`PgVectorStore` 虽然配置了 `initializeSchema(true)`，但在 Bean 创建过程中插入数据时，表尚未创建，导致 Bean 实例化失败。

**解决方案**
将文档加载从 `@Bean` 方法中移出，放到 `@PostConstruct` 中，确保 `PgVectorStore` Bean 先成功创建，再执行数据插入。即使插入失败，Bean 也已存在，不影响应用启动。

### 5. 最终代码（分离创建与加载）

java

```
@Configuration
@Slf4j
public class PgVectorVectorStoreConfig {

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;

    private VectorStore vectorStore;

    @Bean
    public VectorStore pgVectorVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel dashscopeEmbeddingModel) {
        vectorStore = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(1536)
                .distanceType(COSINE_DISTANCE)
                .indexType(HNSW)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName("vector_store")
                .maxDocumentBatchSize(10000)
                .build();
        return vectorStore;
    }

    @PostConstruct
    public void loadDocuments() {
        try {
            List<Document> documents = loveAppDocumentLoader.loadMarkdowns();
            vectorStore.add(documents);
            log.info("成功加载 {} 个文档到 PgVectorStore", documents.size());
        } catch (Exception e) {
            log.error("加载文档失败，但 Bean 已创建，应用可继续启动", e);
        }
    }
}
```



## 总结

- 多 Bean 注入必须使用 `@Qualifier` 或 `@Resource(name = "...")` 明确指定。
- `pgvector` 扩展需要手动启用：`CREATE EXTENSION vector;`。
- `PgVectorStore` 的 `initializeSchema(true)` 会在首次使用时建表，但如果在 `@Bean` 方法中立即插入数据，可能因表未就绪而失败。
- 将数据加载移到 `@PostConstruct` 可以避免 Bean 创建失败，提高健壮性。

完成以上调整后，应用成功启动，`LoveApp` 正常注入多个 `VectorStore`。