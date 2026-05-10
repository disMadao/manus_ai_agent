package com.manus.aiagent.agent.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenFriend 集成测试
 */
@SpringBootTest
class OpenFriendTest {

    @Autowired
    private OpenFriend openFriend;

    @Test
    void testOpenFriend() {
        assertNotNull(openFriend, "OpenFriend should be injected");
        assertNotNull(openFriend.doChat("你好", "test-chat-1"));
    }

    @Test
    void testDoChatByStreamWithLoop() {
        // 测试流式loop方法
        assertNotNull(openFriend);
    }
}