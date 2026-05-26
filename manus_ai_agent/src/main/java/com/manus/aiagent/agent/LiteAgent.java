package com.manus.aiagent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 轻量通用调用：无记忆、无工具、无 Advisor，仅极短 system，省 token。
 * 适用于格式化、摘要、分类、简单问答等上下文无关任务。
 */
@Component
public class LiteAgent {

    private static final String SYSTEM = "简洁直接，只回答任务本身，不寒暄。按照用户要求输出指定内容即可。";

    private final ChatClient client;

    public LiteAgent(@Qualifier("dashScopeChatModel") ChatModel model) {
        this.client = ChatClient.builder(model).build();
    }

    public String chat(String user) {
        return client.prompt().system(SYSTEM).user(user).call().content();
    }

    /** 可覆盖 system（仍建议保持极短） */
    public String chat(String system, String user) {
        return client.prompt().system(system != null && !system.isBlank() ? system : SYSTEM).user(user).call().content();
    }

    public Flux<String> stream(String user) {
        return client.prompt().system(SYSTEM).user(user).stream().content();
    }
}
