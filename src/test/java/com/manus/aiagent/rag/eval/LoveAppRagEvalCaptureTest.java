package com.manus.aiagent.rag.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manus.aiagent.app.LoveApp;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

@SpringBootTest
class LoveAppRagEvalCaptureTest {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Resource
    private LoveApp loveApp;

    @Resource
    @Qualifier("diaryVectorStore")
    private VectorStore diaryVectorStore;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void captureLoveAppRagSamplesForRagas() throws IOException {
        Path dataset = resolveDatasetPath();
        Assertions.assertTrue(Files.exists(dataset), "Dataset not found: " + dataset);

        List<JsonNode> rows = readJsonl(dataset);
        Assertions.assertFalse(rows.isEmpty(), "Dataset is empty: " + dataset);

        Path outDir = Paths.get("target", "ragas", LocalDateTime.now().format(TS_FORMAT));
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve("loveapp_eval_capture.jsonl");

        try (BufferedWriter writer = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            for (JsonNode row : rows) {
                String id = row.path("id").asText();
                String question = row.path("question").asText();
                String referenceAnswer = row.path("reference_answer").asText();
                List<String> referenceContexts = toStringList(row.path("reference_contexts"));
                List<String> tags = toStringList(row.path("tags"));
                String datasetVersion = row.path("dataset_version").asText("v1");

                String chatId = "ragas-capture-" + UUID.randomUUID();
                String answer = loveApp.doChat(question, chatId);
                if (answer == null) {
                    answer = "";
                }

                List<String> retrievedContexts = retrieveDiaryContexts(question);

                String record = objectMapper.writeValueAsString(new CaptureRecord(
                        id,
                        question,
                        answer,
                        retrievedContexts,
                        referenceAnswer,
                        referenceContexts,
                        tags,
                        datasetVersion
                ));
                writer.write(record);
                writer.newLine();
            }
        }

        System.out.println("RAG eval capture written: " + outFile.toAbsolutePath());
    }

    private Path resolveDatasetPath() {
        Path fromEval = Paths.get("eval", "datasets", "loveapp_ragas_v1.jsonl");
        if (Files.exists(fromEval)) {
            return fromEval;
        }
        return Paths.get("src", "test", "resources", "ragas", "loveapp_ragas_v1.jsonl");
    }

    private List<JsonNode> readJsonl(Path file) throws IOException {
        List<JsonNode> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            rows.add(objectMapper.readTree(line));
        }
        return rows;
    }

    private List<String> toStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return list;
        }
        Iterator<JsonNode> it = node.elements();
        while (it.hasNext()) {
            list.add(it.next().asText());
        }
        return list;
    }

    private List<String> retrieveDiaryContexts(String query) {
        List<Document> docs = diaryVectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(3)
                        .similarityThreshold(0.5)
                        .build()
        );

        List<String> contexts = new ArrayList<>();
        if (docs != null) {
            for (Document doc : docs) {
                if (doc != null && doc.getText() != null && !doc.getText().isBlank()) {
                    contexts.add(doc.getText());
                }
            }
        }
        return contexts;
    }

    private record CaptureRecord(
            String id,
            String question,
            String answer,
            List<String> contexts,
            String reference_answer,
            List<String> reference_contexts,
            List<String> tags,
            String dataset_version
    ) {
    }
}
