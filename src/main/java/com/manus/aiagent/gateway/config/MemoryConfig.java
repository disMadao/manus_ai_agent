package com.manus.aiagent.gateway.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 共享短期记忆 Bean，供 LoveApp（VisualizedMemoryAdvisor）和 AgentGateway 共用
 */
@Configuration
public class MemoryConfig {

    @Bean
    public ChatMemory shortTermMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(100)
                .build();
    }
}
