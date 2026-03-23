package com.manus.aiagent.rag;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 AI 的文档元信息增强器（为文档补充元信息）
 */
@Component
public class MyKeywordEnricher {

    private final ChatModel dashScopeChatModel;

    public MyKeywordEnricher(@Qualifier("dashScopeChatModel") ChatModel dashScopeChatModel) {
        this.dashScopeChatModel = dashScopeChatModel;
    }

    public List<Document> enrichDocuments(List<Document> documents) {
        //生成5个标签在元信息中，如 ："excerpt_keywords": "boredom, stillness, emptiness, quietude, inertia"
        // 可以让检索更快，但需要手动实现。而且比较硬编码，不是那么的有用，感觉
        KeywordMetadataEnricher keywordMetadataEnricher = new KeywordMetadataEnricher(dashScopeChatModel, 5);
        return  keywordMetadataEnricher.apply(documents);
    }
}
