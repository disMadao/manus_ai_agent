package com.manus.aiagent.agent.loop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LoopSessionManager 单元测试
 * 测试重点：会话管理、消息添加、并发隔离
 */
class LoopSessionManagerTest {

    private LoopSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new LoopSessionManager();
    }

    @Test
    void testStartLoop() {
        String sessionId = sessionManager.startLoop("chat-1", "Hello");

        assertNotNull(sessionId);
        assertTrue(sessionId.startsWith("chat-1_"));
        assertTrue(sessionManager.hasActiveLoop());
    }

    @Test
    void testGetCurrentSession() {
        String sessionId = sessionManager.startLoop("chat-1", "Hello");
        LoopSession session = sessionManager.getCurrentSession();

        assertNotNull(session);
        assertEquals(sessionId, session.getSessionId());
        assertEquals("chat-1", session.getChatId());
    }

    @Test
    void testAddMessageToCurrentLoop() {
        sessionManager.startLoop("chat-1", "Hello");
        sessionManager.addMessageToCurrentLoop(new AssistantMessage("Hi there"));

        LoopSession session = sessionManager.getCurrentSession();
        assertEquals(1, session.getMessageCount());
    }

    @Test
    void testAddMessagesToCurrentLoop() {
        sessionManager.startLoop("chat-1", "Hello");
        List<org.springframework.ai.chat.messages.Message> messages = List.of(
                new AssistantMessage("Response 1"),
                new AssistantMessage("Response 2")
        );
        sessionManager.addMessagesToCurrentLoop(messages);

        LoopSession session = sessionManager.getCurrentSession();
        assertEquals(2, session.getMessageCount());
    }

    @Test
    void testEndCurrentLoop() {
        sessionManager.startLoop("chat-1", "Hello");
        sessionManager.addMessageToCurrentLoop(new AssistantMessage("Response"));

        // endCurrentLoop接收chatClient参数，如果传null则使用简单压缩
        String summary = sessionManager.endCurrentLoop(null);

        assertNotNull(summary);
        assertFalse(sessionManager.hasActiveLoop());
    }

    @Test
    void testEndCurrentLoopWithNoActiveSession() {
        String summary = sessionManager.endCurrentLoop(null);

        assertNull(summary);
    }

    @Test
    void testForceEndSession() {
        String sessionId = sessionManager.startLoop("chat-1", "Hello");
        sessionManager.forceEndSession(sessionId);

        assertFalse(sessionManager.hasActiveLoop());
        assertNull(sessionManager.getCurrentSession());
    }

    @Test
    void testGetActiveSessionCount() {
        assertEquals(0, sessionManager.getActiveSessionCount());

        sessionManager.startLoop("chat-1", "Hello");
        assertEquals(1, sessionManager.getActiveSessionCount());

        sessionManager.startLoop("chat-2", "World");
        assertEquals(2, sessionManager.getActiveSessionCount());
    }

    @Test
    void testGetSession() {
        String sessionId = sessionManager.startLoop("chat-1", "Hello");
        LoopSession session = sessionManager.getSession(sessionId);

        assertNotNull(session);
        assertEquals(sessionId, session.getSessionId());
    }

    @Test
    void testGetAllActiveSessionIds() {
        String sessionId1 = sessionManager.startLoop("chat-1", "Hello");
        String sessionId2 = sessionManager.startLoop("chat-2", "World");

        List<String> allIds = sessionManager.getAllActiveSessionIds();

        assertEquals(2, allIds.size());
        assertTrue(allIds.contains(sessionId1));
        assertTrue(allIds.contains(sessionId2));
    }

    @Test
    void testSessionIsolation() {
        // 每个线程应该有独立的session
        String sessionId1 = sessionManager.startLoop("chat-1", "Message 1");
        LoopSession session1 = sessionManager.getCurrentSession();
        assertEquals(sessionId1, session1.getSessionId());
    }

    @Test
    void testMultipleLoopsInSequence() {
        // 第一个loop
        sessionManager.startLoop("chat-1", "Hello");
        sessionManager.addMessageToCurrentLoop(new AssistantMessage("Hi"));

        sessionManager.endCurrentLoop(null);

        // 第二个loop
        String sessionId2 = sessionManager.startLoop("chat-2", "World");
        assertTrue(sessionId2.contains("chat-2"));

        assertEquals(1, sessionManager.getActiveSessionCount());
    }

    @Test
    void testFirstUserMessageSet() {
        String userMessage = "Test user message";
        sessionManager.startLoop("chat-1", userMessage);

        LoopSession session = sessionManager.getCurrentSession();
        assertNotNull(session.getFirstUserMessage());
        assertEquals(userMessage, session.getFirstUserMessage().getText());
    }

    @Test
    void testAddMessageWithoutActiveSession() {
        // 没有活动session时添加消息应该不抛异常
        sessionManager.addMessageToCurrentLoop(new AssistantMessage("Test"));
        sessionManager.addMessagesToCurrentLoop(List.of(new AssistantMessage("Test2")));

        // 验证没有活动session
        assertFalse(sessionManager.hasActiveLoop());
    }

    @Test
    void testForceEndNonExistentSession() {
        // 强制结束不存在的session不应该抛异常
        assertDoesNotThrow(() -> sessionManager.forceEndSession("non-existent-id"));
    }
}