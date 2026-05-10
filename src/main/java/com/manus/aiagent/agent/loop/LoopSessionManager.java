package com.manus.aiagent.agent.loop;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loop会话管理器，负责管理并发loop会话
 * 使用ThreadLocal确保每个线程有独立的session上下文
 */
@Component
public class LoopSessionManager {
    private final ConcurrentHashMap<String, LoopSession> activeSessions =
        new ConcurrentHashMap<>();
    private final ThreadLocal<String> currentSessionId = new ThreadLocal<>();

    /**
     * 开始一个新的loop会话
     */
    public String startLoop(String chatId, String userMessage) {
        String sessionId = generateSessionId(chatId);
        LoopSession session = new LoopSession(sessionId, chatId);

        // 设置第一条用户消息
        session.setFirstUserMessage(new UserMessage(userMessage));

        activeSessions.put(sessionId, session);
        currentSessionId.set(sessionId);

        return sessionId;
    }

    /**
     * 获取当前线程的loop会话
     */
    public LoopSession getCurrentSession() {
        String sessionId = currentSessionId.get();
        if (sessionId == null) {
            return null;
        }
        return activeSessions.get(sessionId);
    }

    /**
     * 向当前loop会话添加消息
     */
    public void addMessageToCurrentLoop(Message message) {
        LoopSession session = getCurrentSession();
        if (session != null && session.isActive()) {
            session.addInternalMessage(message);
        }
    }

    /**
     * 向当前loop会话添加多条消息
     */
    public void addMessagesToCurrentLoop(List<Message> messages) {
        LoopSession session = getCurrentSession();
        if (session != null && session.isActive()) {
            session.addInternalMessages(messages);
        }
    }

    /**
     * 结束当前loop会话，返回压缩后的摘要
     */
    public String endCurrentLoop(ChatClient chatClient) {
        String sessionId = currentSessionId.get();
        if (sessionId == null) {
            return null;
        }

        LoopSession session = activeSessions.remove(sessionId);
        currentSessionId.remove();

        if (session != null) {
            session.endLoop();
            return session.compressToSummary(chatClient);
        }

        return null;
    }

    /**
     * 强制结束指定会话（异常处理）
     */
    public void forceEndSession(String sessionId) {
        LoopSession session = activeSessions.remove(sessionId);
        if (session != null) {
            session.endLoop();
        }

        // 如果这是当前线程的会话，清理ThreadLocal
        if (sessionId.equals(currentSessionId.get())) {
            currentSessionId.remove();
        }
    }

    /**
     * 检查当前线程是否有活跃的loop会话
     */
    public boolean hasActiveLoop() {
        LoopSession session = getCurrentSession();
        return session != null && session.isActive();
    }

    /**
     * 获取当前活跃会话数量
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    /**
     * 清理超时会话（可选，用于防止内存泄漏）
     */
    public void cleanupExpiredSessions(int timeoutMinutes) {
        // 实现会话超时清理逻辑
        // 可以根据LoopSession的startTime判断是否超时
    }

    /**
     * 生成唯一的会话ID
     */
    private String generateSessionId(String chatId) {
        return chatId + "_" + UUID.randomUUID().toString().substring(0, 8) +
               "_" + System.currentTimeMillis();
    }

    /**
     * 获取指定会话（用于监控和调试）
     */
    public LoopSession getSession(String sessionId) {
        return activeSessions.get(sessionId);
    }

    /**
     * 获取所有活跃会话ID（用于监控）
     */
    public List<String> getAllActiveSessionIds() {
        return new ArrayList<>(activeSessions.keySet());
    }
}