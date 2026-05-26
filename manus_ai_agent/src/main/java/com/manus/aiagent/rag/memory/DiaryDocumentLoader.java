package com.manus.aiagent.rag.memory;

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
 * 日记文档加载器
 */
@Component
@Slf4j
public class DiaryDocumentLoader {

    private final ResourcePatternResolver resourcePatternResolver;

    public DiaryDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    /**
     * 加载多篇 Markdown 文档
     * @return
     */
    public List<Document> loadMarkdowns() {
        List<Document> allDocuments = new ArrayList<>();
        try {
            String diaryDir = System.getProperty("user.dir") + "/workspace/memory/diary";
            Resource[] resources = resourcePatternResolver.getResources("file:" + diaryDir + "/*.md");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".md")) {
                    continue;
                }
                String date = filename.substring(0, filename.length() - 3);

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
                    doc.getMetadata().put("source", "diary");
                    doc.getMetadata().put("kind", "daily");
                    doc.getMetadata().put("date", date);
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
