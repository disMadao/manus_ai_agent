package com.manus.aiagent.advisor;

import com.manus.aiagent.chatmemory.ChatMessageStore;
import com.manus.aiagent.chatmemory.VisualizedMemoryManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 可视化记忆核心拦截器
 * - memory.md 灵魂 + 偏好
 * - 最近 2 天日记
 * - RAG 检索历史日记
 * - 消息持久化到 PostgreSQL
 */
@Slf4j
public class VisualizedMemoryAdvisor implements CallAdvisor, StreamAdvisor, Ordered {

    private final VisualizedMemoryManager memoryManager;
    private final ChatMemory shortTermMemory;
    private final ChatModel chatModel;
    private final ChatClient collapseChatClient;
    private final VectorStore diaryVectorStore;
    private final VectorStore knowledgeVectorStore;
    private final ChatMessageStore chatMessageStore;

    private static final int MEMORY_COLLAPSE_THRESHOLD = 6;

    public VisualizedMemoryAdvisor(VisualizedMemoryManager memoryManager,
                                   ChatMemory shortTermMemory,
                                   ChatModel chatModel,
                                   VectorStore diaryVectorStore,
                                   VectorStore knowledgeVectorStore,
                                   ChatMessageStore chatMessageStore) {
        this.memoryManager = memoryManager;
        this.shortTermMemory = shortTermMemory;
        this.chatModel = chatModel;
        this.collapseChatClient = ChatClient.builder(chatModel).build();
        this.diaryVectorStore = diaryVectorStore;
        this.knowledgeVectorStore = knowledgeVectorStore;
        this.chatMessageStore = chatMessageStore;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return 0; // 确保记忆组装在最前面执行
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String conversationId = extractConversationId(request);
        if (conversationId == null) {
            log.warn("未提供 Conversation ID，跳过记忆增强");
            return chain.nextCall(request);
        }

        List<Message> userMessages = extractUserMessages(request);
        ChatClientRequest updatedRequest = buildUpdatedRequest(request, conversationId, userMessages);

        // ===== 调用 LLM =====
        ChatClientResponse response = chain.nextCall(updatedRequest);

        // ===== 后置：保存消息 =====
        if (response != null && response.chatResponse() != null && !response.chatResponse().getResults().isEmpty()) {
            Message assistantMsg = response.chatResponse().getResults().get(0).getOutput();
            persistRound(conversationId, userMessages, assistantMsg.getText());
        }

        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String conversationId = extractConversationId(request);
        if (conversationId == null) {
            log.warn("未提供 Conversation ID，跳过记忆增强");
            return chain.nextStream(request);
        }

        List<Message> userMessages = extractUserMessages(request);
        ChatClientRequest updatedRequest = buildUpdatedRequest(request, conversationId, userMessages);

        StringBuilder assistantTextBuilder = new StringBuilder();
        return chain.nextStream(updatedRequest)
                .doOnNext(resp -> {
                    if (resp != null
                            && resp.chatResponse() != null
                            && !resp.chatResponse().getResults().isEmpty()
                            && resp.chatResponse().getResults().get(0).getOutput() != null
                            && resp.chatResponse().getResults().get(0).getOutput().getText() != null) {
                        assistantTextBuilder.append(resp.chatResponse().getResults().get(0).getOutput().getText());
                    }
                })
                .doOnComplete(() -> persistRound(conversationId, userMessages, assistantTextBuilder.toString()))
                .doOnError(e -> log.warn("流式响应出错，跳过本轮消息持久化: {}", e.getMessage()));
    }

    private String extractConversationId(ChatClientRequest request) {
        Map<String, Object> context = request.context();
        return context != null ? (String) context.get(ChatMemory.CONVERSATION_ID) : null;
    }

    private List<Message> extractUserMessages(ChatClientRequest request) {
        return request.prompt().getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .collect(Collectors.toList());
    }

    private ChatClientRequest buildUpdatedRequest(ChatClientRequest request,
                                                  String conversationId,
                                                  List<Message> userMessages) {
        String memory = memoryManager.readFullMemory();
        String userQuery = userMessages.stream().map(Message::getText).collect(Collectors.joining(" "));
        String knowledgeContext = searchKnowledgeByRag(userQuery);
        String todayDiary = memoryManager.readTodayDiaryWithHeader();

        StringBuilder systemPromptBuilder = new StringBuilder(memory);
        if (!todayDiary.isEmpty()) {
            systemPromptBuilder.append("\n\n---\n[今天的日记（仅供参考，不要主动复述）]：\n").append(todayDiary).append("\n");
        }
        if (!knowledgeContext.isEmpty()) {
            systemPromptBuilder.append("\n\n---\n[RAG 检索到的相关知识库内容，参考其中的风格]：\n").append(knowledgeContext);
        }

        SystemMessage systemMessage = new SystemMessage(systemPromptBuilder.toString());
        List<Message> historyMessages = shortTermMemory.get(conversationId);
        if (historyMessages == null) {
            historyMessages = new ArrayList<>();
        }

        List<Message> allMessages = new ArrayList<>();
        allMessages.add(systemMessage);// 将memory 和最近两天日记直接添加到系统提示词中
        allMessages.addAll(historyMessages);
        allMessages.addAll(userMessages);

        return request.mutate()
                .prompt(request.prompt().mutate().messages(allMessages).build())
                .build();
    }

    private void persistRound(String conversationId, List<Message> userMessages, String assistantText) {
        if (assistantText == null || assistantText.isBlank()) {
            assistantText = "（空回复）";
        }
        Message assistantMsg = new AssistantMessage(assistantText);
        shortTermMemory.add(conversationId, userMessages);
        shortTermMemory.add(conversationId, List.of(assistantMsg));

        if (chatMessageStore != null) {
            for (Message um : userMessages) {
                chatMessageStore.saveMessage(conversationId, "USER", um.getText());
            }
            chatMessageStore.saveMessage(conversationId, "ASSISTANT", assistantText);
        }

        checkAndTriggerMemoryCollapse(conversationId);
    }
    /**
     * RAG 检索知识库
     */
    private String searchKnowledgeByRag(String query) {
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
            if (results == null || results.isEmpty()) return "";

            return results.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n---\n"));
        } catch (Exception e) {
            log.warn("RAG 知识库检索失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * RAG 检索历史日记
     */
    private String searchDiaryByRag(String query) {
        if (diaryVectorStore == null || query == null || query.isBlank()) {
            return "";
        }
        try {
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
        } catch (Exception e) {
            log.warn("RAG 日记检索失败: {}", e.getMessage());
            return "";
        }
    }

    private void checkAndTriggerMemoryCollapse(String conversationId) {
        List<Message> currentHistory = shortTermMemory.get(conversationId);
        if (currentHistory == null) currentHistory = new ArrayList<>();

        if (currentHistory.size() >= MEMORY_COLLAPSE_THRESHOLD) {
            log.info("触发记忆坍缩机制，当前记忆条数：{}", currentHistory.size());
            List<Message> memoryToCollapse = new ArrayList<>(currentHistory);
            shortTermMemory.clear(conversationId);
            CompletableFuture.runAsync(() -> collapseMemory(conversationId, memoryToCollapse));
        }
    }

    private void collapseMemory(String conversationId, List<Message> memoryToCollapse) {
        try {
            log.info("开始后台提炼日记...");
            StringBuilder chatLog = new StringBuilder("以下是最近的对话记录：\n");
            for (Message msg : memoryToCollapse) {
                chatLog.append(msg.getMessageType().getValue()).append(": ").append(msg.getText()).append("\n");
            }

            String diaryPromptText = """
                    你是后台日记总结器。请只输出日记正文内容，不要标题、不要时间戳、不要开场白。
                    要求：简洁、可检索，突出事件与情绪变化。
                    \n
                    %s
                    """.formatted(chatLog);
            String diaryEntry = collapseChatClient.prompt()
                    .user(diaryPromptText)
                    .call()
                    .chatResponse()
                    .getResult()
                    .getOutput()
                    .getText();

            String today = memoryManager.getTodayDate();
            String existingTodayDiary = memoryManager.readDiary(today);
            String compressPrompt = """
                    你是日记压缩器。将【已有今天日记】与【新增日记】融合压缩为一份更短、更清晰的“今天日记正文”。
                    规则：
                    1. 只输出正文，不要标题、不要日期、不要时间戳、不要编号。
                    2. 结合已有内容与新增内容，去重合并，保留关键信息与情绪走向。
                    3. 输出应可直接写入 YYYY-MM-DD.md 文件。
                    
                    【已有今天日记】：
                    %s
                    
                    【新增日记】：
                    %s
                    """.formatted(existingTodayDiary, diaryEntry);
            String compressedTodayDiary = collapseChatClient.prompt()
                    .user(compressPrompt)
                    .call()
                    .chatResponse()
                    .getResult()
                    .getOutput()
                    .getText();
            memoryManager.overwriteDiary(today, compressedTodayDiary);

            // 今天日记不进入 RAG；塌缩时检查昨天是否已入库，若未入库则补入库
            if (diaryVectorStore != null) {
                String yesterday = memoryManager.getDateDaysAgo(1);
                String yesterdayDiary = memoryManager.readDiary(yesterday);
                if (!yesterdayDiary.isEmpty() && !memoryManager.isDiaryEmbedded(yesterday)) {
                    Document diaryDoc = new Document(yesterdayDiary);
                    diaryDoc.getMetadata().put("source", "diary");
                    diaryDoc.getMetadata().put("kind", "daily");
                    diaryDoc.getMetadata().put("date", yesterday);
                    diaryDoc.getMetadata().put("filename", yesterday + ".md");
                    diaryVectorStore.add(List.of(diaryDoc));
                    log.info("昨日({})日记已补写入向量库供 RAG 检索", yesterday);
                }
            }

            // 重建日记地图：今天之前的最近 5 天，一天一句
            List<String> prevDates = memoryManager.listPreviousDiaryDates(5);
            if (!prevDates.isEmpty()) {
                StringBuilder mapInput = new StringBuilder();
                for (String date : prevDates) {
                    String d = memoryManager.readDiary(date);
                    if (d.isEmpty()) continue;
                    mapInput.append("### ").append(date).append("\n").append(d).append("\n\n");
                }
                String mapPrompt = """
                        你是日记地图生成器。对下面每一天的日记内容生成一句话浓缩。
                        输出规则：
                        - 只输出多行文本，每行格式必须是：yyyy-MM-dd：一句话浓缩
                        - 只允许输出输入中出现的日期，严禁编造日期
                        - 不要编号，不要额外解释
                        
                        %s
                        """.formatted(mapInput);
                String mapText = collapseChatClient.prompt()
                        .user(mapPrompt)
                        .call()
                        .chatResponse()
                        .getResult()
                        .getOutput()
                        .getText();
                List<String> mapLines = mapText.lines()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .filter(s -> prevDates.stream().anyMatch(d -> s.startsWith(d + "：") || s.startsWith(d + ":")))
                        .collect(Collectors.toList());
                memoryManager.updateDiaryMapLines(mapLines);
            } else {
                memoryManager.updateDiaryMapLines(List.of("待更新"));
            }

            // 更新 preference（只重写 preference 区块；role 与 diary-map 由代码负责稳定结构）
            String currentMemory = memoryManager.readMemory();
            String currentPreferenceBlock = memoryManager.extractPreferenceBlock(currentMemory);
            String preferencePrompt = """
                    你是 OpenFriend 的偏好更新器。
                    任务：根据【新增日记】更新【当前 preference 区块】的内容，合并去重，保留长期稳定信息。
                    规则：
                    1. 只输出更新后的 `## preference` 区块（包含标题行与正文），不要输出其他任何章节。
                    2. 不要加入日期、不要加入时间戳。
                    3. 保持中文为主，风格精炼。
                    
                    【当前 preference 区块】：
                    %s
                    
                    【新增日记】：
                    %s
                    """.formatted(currentPreferenceBlock, diaryEntry);
            String newPreferenceBlock = collapseChatClient.prompt()
                    .user(preferencePrompt)
                    .call()
                    .chatResponse()
                    .getResult()
                    .getOutput()
                    .getText();
            memoryManager.replacePreferenceText(newPreferenceBlock);

            // 写完后重新加载（确保后续请求读取的是最新文件内容）
            memoryManager.readMemory();
            memoryManager.readTodayDiaryWithHeader();
            shortTermMemory.clear(conversationId);
            log.info("记忆坍缩完成：已压缩重写今天日记，已重建日记地图，并更新 preference。");
        } catch (Exception e) {
            log.error("记忆坍缩失败", e);
        }
    }
}
