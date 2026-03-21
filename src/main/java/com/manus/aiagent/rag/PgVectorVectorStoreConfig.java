package com.manus.aiagent.rag;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

@Configuration
@Slf4j
public class PgVectorVectorStoreConfig {

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;

//    @Resource
//    private VectorStore pgVectorVectorStore; // 用于在 @PostConstruct 中引用

    @Bean
    public VectorStore pgVectorVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel dashscopeEmbeddingModel) {
        VectorStore pgVectorVectorStore = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(1536)
                .distanceType(COSINE_DISTANCE)
                .indexType(HNSW)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName("vector_store")
                .maxDocumentBatchSize(10000)
                .build();
        try {
            List<Document> documents = loveAppDocumentLoader.loadMarkdowns();
            pgVectorVectorStore.add(documents);
            log.info("成功加载 {} 个文档到 PgVectorStore", documents.size());
        } catch (Exception e) {
            log.error("加载文档到 PgVectorStore 失败，但 Bean 已创建，应用可继续启动", e);
        }
        return pgVectorVectorStore;
    }

//    @PostConstruct
//    public void loadDocuments() {
//        try {
//            List<Document> documents = loveAppDocumentLoader.loadMarkdowns();
//            pgVectorVectorStore.add(documents);
//            log.info("成功加载 {} 个文档到 PgVectorStore", documents.size());
//        } catch (Exception e) {
//            log.error("加载文档到 PgVectorStore 失败，但 Bean 已创建，应用可继续启动", e);
//        }
//    }
}