package com.manus.aiagent.debate;

import com.manus.aiagent.agent.LiteAgent;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 仅用于辩论集成测试上下文：手动注册 {@link LiteAgent}（不扫描整个 agent 包）。
 */
@Configuration
public class LiteAgentImportConfig {

    @Bean
    public LiteAgent liteAgent(@Qualifier("dashScopeChatModel") ChatModel model) {
        return new LiteAgent(model);
    }
}
