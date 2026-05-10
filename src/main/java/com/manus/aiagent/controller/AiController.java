package com.manus.aiagent.controller;

import com.manus.aiagent.agent.app.OpenFriend;
import com.manus.aiagent.agent.app.DiagnosisAgent;
import com.manus.aiagent.agent.manus.ManusAgent;
import com.manus.aiagent.chatmemory.ChatMessageStore;
import com.manus.aiagent.gateway.ManusMemoryEnricher;
import com.manus.aiagent.tools.terminal.TerminalCommandGate;
import com.manus.aiagent.tools.terminal.TerminalToolChatContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/ai")
@Slf4j
public class AiController {

    @Resource
    private OpenFriend openFriend;


    @Resource
    private ChatMessageStore chatMessageStore;

    @Resource
    private ToolCallback[] allTools;

    @Resource
    @Qualifier("dashScopeChatModel")
    private ChatModel dashScopeChatModel;

    @Resource
    @Qualifier("shortTermMemory")
    private ChatMemory shortTermMemory;

    @Resource
    private ManusMemoryEnricher manusMemoryEnricher;

    @Resource
    private TerminalCommandGate terminalCommandGate;

    @Resource
    private ToolCallback[] diagnosisTools;

    /**
     * 同步调用 AI 恋爱大师应用
     */
    @GetMapping("/love_app/chat/sync")
    public String doChatWithOpenFriendSync(String message, String chatId) {
        return openFriend.doChat(message, chatId);
    }

    /**
     * SSE 流式调用（统一入口）
     * 支持三种模式：normal, thinking, super
     * normal/thinking 模式直接调用 OpenFriend，super 模式创建 ManusAgent 并注入记忆
     */
    @GetMapping(value = "/love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithLoveAppSSE(String message, String chatId, String mode) {
        String effectiveMode = mode != null ? mode : "normal";

        return switch (effectiveMode.toLowerCase()) {
            case "super" -> chatWithManusAgentStream(message, chatId);
            case "thinking" -> openFriend.doChatByStream(message, chatId, true);
            default -> openFriend.doChatByStream(message, chatId, false);
        };
    }

    /**
     * 超级智能体模式（流式）：注入记忆 → 运行 ManusAgent → 仅返回最终结果 → 持久化
     */
    private Flux<String> chatWithManusAgentStream(String message, String chatId) {
        Optional<String> early = terminalCommandGate.tryConsumeConfirmationReply(chatId, message);
        if (early.isPresent()) {
            String r = early.get();
            return Flux.just(r).doOnComplete(() -> persistManusConversation(chatId, message, r));
        }
        ManusAgent agent = createMemoryEnrichedManusAgent(message);
        StringBuilder resultBuilder = new StringBuilder();
        return Flux.defer(() -> {
            TerminalToolChatContext.setChatId(chatId);
            return agent.runFluxFinalOnly(message)
                    .doOnNext(resultBuilder::append)
                    .doOnComplete(() -> persistManusConversation(chatId, message, resultBuilder.toString()))
                    .doOnError(e -> log.error("ManusAgent 执行失败", e))
                    .doFinally(s -> TerminalToolChatContext.clear());
        });
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

    /**
     * SSE 流式调用（ServerSentEvent 包装）
     */
    @GetMapping(value = "/love_app/chat/server_sent_event")
    public Flux<ServerSentEvent<String>> doChatWithOpenFriendServerSentEvent(String message, String chatId) {
        return openFriend.doChatByStream(message, chatId, false)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }

    /**
     * 查询历史消息（供前端加载聊天记录）
     */
    @GetMapping("/love_app/messages")
    public List<ChatMessageStore.ChatMessageDTO> getChatMessages(String chatId) {
        return chatMessageStore.getMessages(chatId);
    }

    /**
     * 重新加载记忆：清除旧对话上下文 + 从磁盘重读 SOUL.md、memory.md、日记并加载到 Advisor
     */
    @PostMapping("/love_app/memory/reload")
    public Map<String, Object> reloadMemory(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            chatId = "open_friend_default";
        }
        try {
            openFriend.reloadMemory(chatId);
            return Map.of("success", true);
        } catch (Exception e) {
            log.error("记忆重新加载失败, chatId={}", chatId, e);
            return Map.of("success", false);
        }
    }

    /**
     * 故障诊断智能体入口（流式）
     * 使用示例: /ai/diagnosis/chat?message=实例ins-abc123 SSH连不上，帮我排查一下
     */
    @GetMapping(value = "/diagnosis/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithDiagnosis(String message) {
        DiagnosisAgent agent = new DiagnosisAgent(diagnosisTools, dashScopeChatModel);
        return agent.runFluxFinalOnly(message);
    }

    /**
     * @deprecated 请使用 /love_app/chat/sse?mode=super 代替，已直接处理
     */
    @Deprecated
    @GetMapping("/manus/chat")
    public Flux<String> doChatWithManus(String message) {
        return chatWithManusAgentStream(message, "manus_default");
    }
}
