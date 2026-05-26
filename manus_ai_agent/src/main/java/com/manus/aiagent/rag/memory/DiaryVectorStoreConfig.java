package com.manus.aiagent.rag.memory;

import com.manus.aiagent.rag.MyKeywordEnricher;
import com.manus.aiagent.rag.MyTokenTextSplitter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

/**
 * 日记记忆专用向量库（与知识库 loveAppVectorStore 分离）
 *
 */
@Configuration
@Slf4j
public class DiaryVectorStoreConfig {


//    @Resource
//    private LoveAppDocumentLoader loveAppDocumentLoader;

    @Resource
    private MyTokenTextSplitter myTokenTextSplitter;

//    @Resource
//    private MyKeywordEnricher myKeywordEnricher;

    @Resource
    private DiaryDocumentLoader diaryDocumentLoader;

    @Bean
    VectorStore diaryVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel dashscopeEmbeddingModel) {
        // 旧实现（保留供参考）：内存向量库，不会落库到 PostgreSQL
    //        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel).build();

        // 当前实现：使用 PGVector，向量写入 public.vector_store
        VectorStore pgDiaryVectorStore = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(1024)
                .distanceType(COSINE_DISTANCE)
                .indexType(HNSW)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName("vector_store")
                .maxDocumentBatchSize(10)
                .build();

        // 加载文档
        List<Document> documentList = diaryDocumentLoader.loadMarkdowns();
        // 自主切分文档
//        List<Document> splitDocuments = myTokenTextSplitter.splitCustomized(documentList);
        // 自动补充关键词元信息
//        List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(documentList);

        // 写入前：为每个 chunk 生成稳定 hash，用于去重/更新
        for (Document doc : documentList) {
            String filename = Objects.toString(doc.getMetadata().get("filename"), "");
            String chunkIndex = Objects.toString(doc.getMetadata().get("chunk_index"), "");
            String text = doc.getText() == null ? "" : doc.getText();
            String chunkHash = sha256(filename + "\n" + chunkIndex + "\n" + text);
            doc.getMetadata().put("chunk_hash", chunkHash);
        }

        boolean locked = acquireLock(jdbcTemplate, 2026031702L);
        try {
            cleanupDuplicateDiaryRows(jdbcTemplate);
            syncDiaryVectorStore(jdbcTemplate, documentList);
            List<Document> toAdd = documentList.stream()
                    .filter(d -> !isDiaryChunkEmbedded(jdbcTemplate, d))
                    .collect(Collectors.toList());
            if (!toAdd.isEmpty()) {
                batchAdd(pgDiaryVectorStore, toAdd, 10);
                log.info("diary 向量库新增写入文档分片数：{}", toAdd.size());
            } else {
                log.info("diary 向量库无需新增写入（已是最新）");
            }
        } finally {
            if (locked) {
                releaseLock(jdbcTemplate, 2026031702L);
            }
        }
        return pgDiaryVectorStore;
    }

    private boolean acquireLock(JdbcTemplate jdbcTemplate, long lockKey) {
        if (jdbcTemplate == null) return false;
        try {
            Boolean ok = jdbcTemplate.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, lockKey);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("获取 diary 启动锁失败，继续无锁执行: {}", e.getMessage());
            return false;
        }
    }

    private void releaseLock(JdbcTemplate jdbcTemplate, long lockKey) {
        if (jdbcTemplate == null) return;
        try {
            jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, lockKey);
        } catch (Exception e) {
            log.warn("释放 diary 启动锁失败: {}", e.getMessage());
        }
    }

    private void cleanupDuplicateDiaryRows(JdbcTemplate jdbcTemplate) {
        if (jdbcTemplate == null) return;
        try {
        jdbcTemplate.update("""
                WITH ranked AS (
                    SELECT ctid,
                           ROW_NUMBER() OVER (
                               PARTITION BY COALESCE(metadata->>'source',''),
                                            COALESCE(metadata->>'filename',''),
                                            COALESCE(NULLIF(metadata->>'chunk_hash',''), md5(content))
                               ORDER BY id
                           ) AS rn
                    FROM public.vector_store
                    WHERE metadata->>'source'='diary'
                )
                DELETE FROM public.vector_store v
                USING ranked r
                WHERE v.ctid = r.ctid AND r.rn > 1
                """);
        } catch (Exception e) {
            log.warn("清理重复日记行跳过（表可能尚未创建）: {}", e.getMessage());
        }
    }

    private void syncDiaryVectorStore(JdbcTemplate jdbcTemplate, List<Document> currentDocs) {
        if (jdbcTemplate == null || currentDocs == null) {
            return;
        }
        try {
            Set<String> currentFilenames = currentDocs.stream()
                    .map(d -> Objects.toString(d.getMetadata().get("filename"), ""))
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toSet());

            List<String> dbFilenames = jdbcTemplate.queryForList(
                    "SELECT DISTINCT metadata->>'filename' FROM public.vector_store WHERE COALESCE(metadata->>'source','') IN ('diary','')",
                    String.class
            );
            for (String dbFilename : dbFilenames) {
                if (dbFilename == null || dbFilename.isBlank()) continue;
                if (!currentFilenames.contains(dbFilename)) {
                    jdbcTemplate.update(
                            "DELETE FROM public.vector_store WHERE metadata->>'source'='diary' AND metadata->>'filename'=?",
                            dbFilename
                    );
                }
            }

            for (String filename : currentFilenames) {
                Set<String> currentHashes = currentDocs.stream()
                        .filter(d -> filename.equals(Objects.toString(d.getMetadata().get("filename"), "")))
                        .map(d -> Objects.toString(d.getMetadata().get("chunk_hash"), ""))
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toSet());

                List<String> dbHashes = jdbcTemplate.queryForList(
                        "SELECT metadata->>'chunk_hash' FROM public.vector_store WHERE COALESCE(metadata->>'source','') IN ('diary','') AND metadata->>'filename'=?",
                        String.class,
                        filename
                );
                for (String dbHash : dbHashes) {
                    if (dbHash == null || dbHash.isBlank()) {
                        jdbcTemplate.update(
                                "DELETE FROM public.vector_store WHERE metadata->>'source'='diary' AND metadata->>'filename'=? AND (metadata->>'chunk_hash' IS NULL OR metadata->>'chunk_hash'='')",
                                filename
                        );
                        continue;
                    }
                    if (!currentHashes.contains(dbHash)) {
                        jdbcTemplate.update(
                                "DELETE FROM public.vector_store WHERE metadata->>'source'='diary' AND metadata->>'filename'=? AND metadata->>'chunk_hash'=?",
                                filename,
                                dbHash
                        );
                    }
                }
            }
        } catch (Exception e) {
            log.warn("同步日记向量库跳过（表可能尚未创建）: {}", e.getMessage());
        }
    }

    private boolean isDiaryChunkEmbedded(JdbcTemplate jdbcTemplate, Document doc) {
        if (jdbcTemplate == null || doc == null) {
            return false;
        }
        String filename = Objects.toString(doc.getMetadata().get("filename"), "");
        String chunkHash = Objects.toString(doc.getMetadata().get("chunk_hash"), "");
        if (filename.isBlank() || chunkHash.isBlank()) {
            return false;
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM public.vector_store WHERE COALESCE(metadata->>'source','') IN ('diary','') AND metadata->>'filename'=? AND metadata->>'chunk_hash'=?",
                    Integer.class,
                    filename,
                    chunkHash
            );
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void batchAdd(VectorStore store, List<Document> docs, int batchSize) {
        for (int i = 0; i < docs.size(); i += batchSize) {
            store.add(docs.subList(i, Math.min(i + batchSize, docs.size())));
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
