package com.manus.aiagent.agent.loop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LoopSession 单元测试
 * 测试重点：会话创建、消息管理、状态控制
 */
class LoopSessionTest {

    private LoopSession session;

    @BeforeEach
    void setUp() {
        session = new LoopSession("test-session-1", "chat-1");
    }

    @Test
    void testCreateSession() {
        assertEquals("test-session-1", session.getSessionId());
        assertEquals("chat-1", session.getChatId());
        assertTrue(session.isActive());
        assertNotNull(session.getStartTime());
    }

    @Test
    void testAddInternalMessages() {
        UserMessage userMsg = new UserMessage("Hello");
        AssistantMessage assistantMsg = new AssistantMessage("Hi there");

        session.addInternalMessage(userMsg);
        session.addInternalMessage(assistantMsg);

        assertEquals(2, session.getMessageCount());
        assertEquals(2, session.getInternalMessages().size());
    }

    @Test
    void testSetFirstUserMessage() {
        UserMessage userMsg = new UserMessage("Test message");
        session.setFirstUserMessage(userMsg);

        assertEquals(userMsg, session.getFirstUserMessage());
    }

    @Test
    void testSetFinalResult() {
        AssistantMessage result = new AssistantMessage("Final answer");
        session.setFinalResult(result);

        assertEquals(result, session.getFinalResult());
    }

    @Test
    void testEndLoop() {
        assertTrue(session.isActive());

        session.endLoop();

        assertFalse(session.isActive());
    }

    @Test
    void testAddMessagesAfterEndLoop() {
        session.endLoop();
        UserMessage msg = new UserMessage("Should not be added");
        session.addInternalMessage(msg);

        // 消息应该不会被添加因为loop已经结束
        assertEquals(0, session.getMessageCount());
    }

    @Test
    void testCompressWithEmptyMessages() {
        // 没有消息时，返回"无中间消息"
        String summary = session.compressToSummary(null);

        assertEquals("无中间消息", summary);
    }

    @Test
    void testCompressSimpleWithFewMessages() {
        // 添加少量消息（<=3条），使用简单压缩
        session.addInternalMessage(new UserMessage("User query"));
        session.addInternalMessage(new AssistantMessage("Thought: I should search"));
        session.addInternalMessage(new AssistantMessage("Result: Found 10 items"));

        // 不传入chatClient，使用简单压缩
        String summary = session.compressToSummary(null);

        assertNotNull(summary);
        assertFalse(summary.isEmpty());
        // 简单压缩会包含消息统计
        assertTrue(summary.contains("Loop摘要") || summary.contains("消息"));
    }

    @Test
    void testCompressWithManyMessages() {
        // 添加多条消息模拟复杂loop（>3条）
        for (int i = 0; i < 5; i++) {
            session.addInternalMessage(new UserMessage("User message " + i));
            session.addInternalMessage(new AssistantMessage("Assistant response " + i));
        }

        // 消息较多时，如果chatClient为null，会回退到简单压缩
        String summary = session.compressToSummary(null);

        assertNotNull(summary);
    }

    @Test
    void testMessageExtraction() {
        // 测试消息内容提取
        session.addInternalMessage(new UserMessage("Test user message"));
        session.addInternalMessage(new AssistantMessage("Test assistant message"));

        List<org.springframework.ai.chat.messages.Message> messages = session.getInternalMessages();
        assertEquals(2, messages.size());
        assertTrue(messages.get(0) instanceof UserMessage);
        assertTrue(messages.get(1) instanceof AssistantMessage);
    }

    @Test
    void testMultipleSessionsIndependent() {
        // 测试多个session相互独立
        LoopSession session1 = new LoopSession("session-1", "chat-1");
        LoopSession session2 = new LoopSession("session-2", "chat-2");

        session1.addInternalMessage(new UserMessage("Message 1"));
        session2.addInternalMessage(new UserMessage("Message 2"));

        assertEquals(1, session1.getMessageCount());
        assertEquals(1, session2.getMessageCount());
        assertNotEquals(session1.getSessionId(), session2.getSessionId());
    }
}