package com.manus.aiagent.chatmemory.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 共享短期记忆 Bean，供 OpenFriend（VisualizedMemoryAdvisor）和 AgentGateway 共用
 */
@Configuration
public class MemoryConfig {

    @Bean
    public ChatMemory shortTermMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(1000)//这里短期记忆的宽口值可以配置的大一点，因为后面的记忆塌缩机制里面，塌缩的时候会清空这里面的短期记忆。这里也是个坑，这个值如果更小可能会出问题。
                .build();
    }
}
