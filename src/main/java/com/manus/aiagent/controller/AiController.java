package com.manus.aiagent.controller;

import com.manus.aiagent.agent.app.OpenFriend;
import com.manus.aiagent.chatmemory.ChatMessageStore;
import com.manus.aiagent.gateway.AgentGateway;
import com.manus.aiagent.gateway.model.GatewayRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ai")
@Slf4j
public class AiController {

    @Resource
    private OpenFriend openFriend;

    @Resource
    private AgentGateway agentGateway;

    @Resource
    private ChatMessageStore chatMessageStore;

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
     * 所有模式通过 AgentGateway 路由，super 模式共享记忆系统且仅返回最终结果
     */
    @GetMapping(value = "/love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithLoveAppSSE(String message, String chatId, String mode) {
        GatewayRequest request = GatewayRequest.builder()
                .message(message)
                .chatId(chatId)
                .mode(mode != null ? mode : "normal")
                .source("web")
                .build();
        return agentGateway.chat(request);
    }

    /**
     * SSE 流式调用（ServerSentEvent 包装）
     */
    @GetMapping(value = "/love_app/chat/server_sent_event")
    public Flux<ServerSentEvent<String>> doChatWithOpenFriendServerSentEvent(String message, String chatId) {
        GatewayRequest request = GatewayRequest.builder()
                .message(message)
                .chatId(chatId)
                .mode("normal")
                .source("web")
                .build();
        return agentGateway.chat(request)
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
     * @deprecated 请使用 /love_app/chat/sse?mode=super 代替，已统一到 AgentGateway
     */
    @Deprecated
    @GetMapping("/manus/chat")
    public Flux<String> doChatWithManus(String message) {
        GatewayRequest request = GatewayRequest.builder()
                .message(message)
                .chatId("manus_default")
                .mode("super")
                .source("web")
                .build();
        return agentGateway.chat(request);
    }
}
