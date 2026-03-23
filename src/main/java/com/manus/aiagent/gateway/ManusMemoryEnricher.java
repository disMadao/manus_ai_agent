package com.manus.aiagent.gateway;

import com.manus.aiagent.chatmemory.VisualizedMemoryManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 为 ManusAgent 拼接与 {@link AgentGateway} 超级模式一致的记忆与 RAG 上下文。
 * 单测中若需与网关行为对齐：先 {@code new ManusAgent(allTools, chatModel, visualizedMemoryAdvisor)}，再对本类
 * {@link #buildEnrichedSystemPrompt(String, String)} 的返回值 {@code agent.setSystemPrompt(...)}。
 */
@Component
@Slf4j
public class ManusMemoryEnricher {

    private final VisualizedMemoryManager memoryManager;
    private final VectorStore knowledgeVectorStore;

    public ManusMemoryEnricher(VisualizedMemoryManager memoryManager,
                               @Qualifier("knowledgeVectorStore") VectorStore knowledgeVectorStore) {
        this.memoryManager = memoryManager;
        this.knowledgeVectorStore = knowledgeVectorStore;
    }

    /**
     * @param baseSystemPrompt 通常为新建 {@link com.manus.aiagent.agent.manus.ManusAgent} 后的 {@code getSystemPrompt()}
     * @param userMessage      当前用户消息，用于 RAG 检索
     */
    public String buildEnrichedSystemPrompt(String baseSystemPrompt, String userMessage) {
        String memoryContext = memoryManager.readFullMemory();
        String todayDiary = memoryManager.readTodayDiaryWithHeader();
        String knowledgeContext = searchKnowledge(userMessage);

        StringBuilder enrichedPrompt = new StringBuilder(baseSystemPrompt);
        enrichedPrompt.append("\n\n---\n[记忆上下文]：\n").append(memoryContext);
        if (todayDiary != null && !todayDiary.isEmpty()) {
            enrichedPrompt.append("\n\n---\n[今天的日记（仅供参考）]：\n").append(todayDiary);
        }
        if (knowledgeContext != null && !knowledgeContext.isEmpty()) {
            enrichedPrompt.append("\n\n---\n[RAG 检索到的相关知识]：\n").append(knowledgeContext);
        }
        return enrichedPrompt.toString();
    }

    private String searchKnowledge(String query) {
        if (knowledgeVectorStore == null || query == null || query.isBlank()) {
            return "";
        }
        try {
            List<Document> results = knowledgeVectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(3)
                            .similarityThreshold(0.5)
                            .build()
            );
            if (results == null || results.isEmpty()) {
                return "";
            }
            return results.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n---\n"));
        } catch (Exception e) {
            log.warn("RAG 知识库检索失败: {}", e.getMessage());
            return "";
        }
    }
}
