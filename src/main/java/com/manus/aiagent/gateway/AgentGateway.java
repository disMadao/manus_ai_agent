package com.manus.aiagent.gateway;

import com.manus.aiagent.agent.ManusAgent;
import com.manus.aiagent.app.LoveApp;
import com.manus.aiagent.chatmemory.ChatMessageStore;
import com.manus.aiagent.chatmemory.VisualizedMemoryManager;
import com.manus.aiagent.gateway.model.GatewayRequest;
import com.manus.aiagent.gateway.model.GatewayResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 统一网关服务：负责路由消息到对应的 Agent，注入记忆上下文，持久化对话。
 * 所有渠道（前端 SSE、QQ Bot、飞书 Bot）均通过此网关与 AI 交互。
 */
@Service
@Slf4j
public class AgentGateway {

    private final LoveApp loveApp;
    private final ToolCallback[] allTools;
    private final ChatModel dashscopeChatModel;
    private final VisualizedMemoryManager memoryManager;
    private final ChatMessageStore chatMessageStore;
    private final ChatMemory shortTermMemory;
    private final VectorStore knowledgeVectorStore;

    public AgentGateway(LoveApp loveApp,
                        ToolCallback[] allTools,
                        @Qualifier("dashscopeChatModel") ChatModel dashscopeChatModel,
                        VisualizedMemoryManager memoryManager,
                        ChatMessageStore chatMessageStore,
                        ChatMemory shortTermMemory,
                        @Qualifier("knowledgeVectorStore") VectorStore knowledgeVectorStore) {
        this.loveApp = loveApp;
        this.allTools = allTools;
        this.dashscopeChatModel = dashscopeChatModel;
        this.memoryManager = memoryManager;
        this.chatMessageStore = chatMessageStore;
        this.shortTermMemory = shortTermMemory;
        this.knowledgeVectorStore = knowledgeVectorStore;
    }

    /**
     * 统一对话入口（流式），根据 mode 路由到不同 Agent
     */
    public Flux<String> chat(GatewayRequest request) {
        String mode = request.getMode() != null ? request.getMode() : "normal";
        return switch (mode.toLowerCase()) {
            case "super" -> chatWithManusAgent(request);
            case "thinking" -> loveApp.doChatByStream(request.getMessage(), request.getChatId(), true);
            default -> loveApp.doChatByStream(request.getMessage(), request.getChatId(), false);
        };
    }

    /**
     * 统一对话入口（同步），用于 QQ/飞书等非流式渠道
     */
    public GatewayResponse chatSync(GatewayRequest request) {
        try {
            String mode = request.getMode() != null ? request.getMode() : "normal";
            if ("super".equalsIgnoreCase(mode)) {
                String result = chatWithManusAgentSync(request);
                return GatewayResponse.ok(result, request.getChatId());
            }
            String result = loveApp.doChat(request.getMessage(), request.getChatId());
            return GatewayResponse.ok(result, request.getChatId());
        } catch (Exception e) {
            log.error("同步对话失败", e);
            return GatewayResponse.error(e.getMessage());
        }
    }

    /**
     * 超级智能体模式（流式）：注入记忆 → 运行 ManusAgent → 仅返回最终结果 → 持久化
     */
    private Flux<String> chatWithManusAgent(GatewayRequest request) {
        ManusAgent agent = createMemoryEnrichedManusAgent(request.getMessage());
        StringBuilder resultBuilder = new StringBuilder();

        return agent.runFluxFinalOnly(request.getMessage())
                .doOnNext(resultBuilder::append)
                .doOnComplete(() -> persistManusConversation(
                        request.getChatId(), request.getMessage(), resultBuilder.toString()
                ))
                .doOnError(e -> log.error("ManusAgent 执行失败", e));
    }

    /**
     * 超级智能体模式（同步）：注入记忆 → 运行 ManusAgent → 持久化
     */
    private String chatWithManusAgentSync(GatewayRequest request) {
        ManusAgent agent = createMemoryEnrichedManusAgent(request.getMessage());
        String result = agent.run(request.getMessage());
        persistManusConversation(request.getChatId(), request.getMessage(), result);
        return result;
    }

    /**
     * 创建注入了记忆上下文的 ManusAgent 实例
     */
    private ManusAgent createMemoryEnrichedManusAgent(String userMessage) {
        ManusAgent agent = new ManusAgent(allTools, dashscopeChatModel);

        String memoryContext = memoryManager.readFullMemory();
        String todayDiary = memoryManager.readTodayDiaryWithHeader();
        String knowledgeContext = searchKnowledge(userMessage);

        StringBuilder enrichedPrompt = new StringBuilder(agent.getSystemPrompt());
        enrichedPrompt.append("\n\n---\n[记忆上下文]：\n").append(memoryContext);
        if (todayDiary != null && !todayDiary.isEmpty()) {
            enrichedPrompt.append("\n\n---\n[今天的日记（仅供参考）]：\n").append(todayDiary);
        }
        if (knowledgeContext != null && !knowledgeContext.isEmpty()) {
            enrichedPrompt.append("\n\n---\n[RAG 检索到的相关知识]：\n").append(knowledgeContext);
        }

        agent.setSystemPrompt(enrichedPrompt.toString());
        return agent;
    }

    /**
     * 持久化 ManusAgent 对话到 ChatMessageStore 和 shortTermMemory
     */
    private void persistManusConversation(String chatId, String userMessage, String assistantText) {
        if (chatId == null || chatId.isBlank()) {
            chatId = "manus_default";
        }
        try {
            chatMessageStore.saveMessage(chatId, "USER", userMessage);
            chatMessageStore.saveMessage(chatId, "ASSISTANT", assistantText);

            shortTermMemory.add(chatId, List.of(new UserMessage(userMessage)));
            shortTermMemory.add(chatId, List.of(new AssistantMessage(assistantText)));

            log.info("ManusAgent 对话已持久化，chatId={}", chatId);
        } catch (Exception e) {
            log.error("ManusAgent 对话持久化失败", e);
        }
    }

    /**
     * RAG 检索知识库
     */
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
            if (results == null || results.isEmpty()) return "";
            return results.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n---\n"));
        } catch (Exception e) {
            log.warn("RAG 知识库检索失败: {}", e.getMessage());
            return "";
        }
    }
}
