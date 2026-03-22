package com.manus.aiagent.gateway;

import com.manus.aiagent.agent.manus.ManusAgent;
import com.manus.aiagent.agent.app.OpenFriend;
import com.manus.aiagent.chatmemory.ChatMessageStore;
import com.manus.aiagent.gateway.model.GatewayRequest;
import com.manus.aiagent.gateway.model.GatewayResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 统一网关服务：负责路由消息到对应的 Agent，注入记忆上下文，持久化对话。
 * 所有渠道（前端 SSE、QQ Bot、飞书 Bot）均通过此网关与 AI 交互。
 */
@Service
@Slf4j
public class AgentGateway {

    private final OpenFriend openFriend;
    private final ToolCallback[] allTools;
    private final ChatModel dashScopeChatModel;
    private final ChatMessageStore chatMessageStore;
    private final ChatMemory shortTermMemory;
    private final ManusMemoryEnricher manusMemoryEnricher;

    public AgentGateway(OpenFriend openFriend,
                        ToolCallback[] allTools,
                        @Qualifier("dashScopeChatModel") ChatModel dashScopeChatModel,
                        ChatMessageStore chatMessageStore,
                        ChatMemory shortTermMemory,
                        ManusMemoryEnricher manusMemoryEnricher) {
        this.openFriend = openFriend;
        this.allTools = allTools;
        this.dashScopeChatModel = dashScopeChatModel;
        this.chatMessageStore = chatMessageStore;
        this.shortTermMemory = shortTermMemory;
        this.manusMemoryEnricher = manusMemoryEnricher;
    }

    /**
     * 统一对话入口（流式），根据 mode 路由到不同 Agent
     */
    public Flux<String> chat(GatewayRequest request) {
        String mode = request.getMode() != null ? request.getMode() : "normal";
        return switch (mode.toLowerCase()) {
            case "super" -> chatWithManusAgent(request);
            case "thinking" -> openFriend.doChatByStream(request.getMessage(), request.getChatId(), true);
            default -> openFriend.doChatByStream(request.getMessage(), request.getChatId(), false);
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
            String result = openFriend.doChat(request.getMessage(), request.getChatId());
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
        ManusAgent agent = new ManusAgent(allTools, dashScopeChatModel);
        agent.setSystemPrompt(manusMemoryEnricher.buildEnrichedSystemPrompt(agent.getSystemPrompt(), userMessage));
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

}
