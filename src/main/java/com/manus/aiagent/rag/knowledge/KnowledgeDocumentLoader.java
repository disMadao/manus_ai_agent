package com.manus.aiagent.rag.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库文档加载器
 */
@Component
@Slf4j
public class KnowledgeDocumentLoader {

    private final ResourcePatternResolver resourcePatternResolver;

    public KnowledgeDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    /**
     * 加载知识库 下文档，支持md，pdf，word格式
     * @return
     */
    public List<Document> loadDocuments() {
        List<Document> allDocuments = new ArrayList<>();
        try {
            String knowledgeDir = System.getProperty("user.dir") + "/workspace/memory/knowledge";
            Resource[] resources = resourcePatternResolver.getResources("file:" + knowledgeDir + "/*.md");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".md")) {
                    continue;
                }

                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(false)
                        .withAdditionalMetadata("filename", filename)
                        .build();
                MarkdownDocumentReader markdownDocumentReader = new MarkdownDocumentReader(resource, config);
                List<Document> docs = markdownDocumentReader.get();
                for (int i = 0; i < docs.size(); i++) {
                    Document doc = docs.get(i);
                    doc.getMetadata().put("source", "knowledge");
                    doc.getMetadata().put("kind", "markdown");
                    doc.getMetadata().put("chunk_index", String.valueOf(i));
                }
                allDocuments.addAll(docs);
            }
        } catch (IOException e) {
            log.error("Markdown 文档加载失败", e);
        }
        return allDocuments;
    }
}
