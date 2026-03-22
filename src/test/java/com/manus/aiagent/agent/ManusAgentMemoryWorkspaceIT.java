package com.manus.aiagent.agent;

import com.manus.aiagent.agent.manus.ManusAgent;
import com.manus.aiagent.gateway.ManusMemoryEnricher;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 模拟真实用户向 Manus 提出的任务描述；与 {@link com.manus.aiagent.gateway.AgentGateway} 超级模式一致，
 * 每次新建 ManusAgent 并经 {@link ManusMemoryEnricher} 注入记忆/RAG 上下文。
 * 需配置可用的 DashScope 等模型密钥。
 */
@SpringBootTest
class ManusAgentMemoryWorkspaceIT {

    @Resource
    private ToolCallback[] allTools;

    @Resource
    @Qualifier("dashScopeChatModel")
    private ChatModel dashScopeChatModel;

    @Resource
    private ManusMemoryEnricher manusMemoryEnricher;

    /**
     * 与网关 {@code createMemoryEnrichedManusAgent} 行为对齐。
     */
    private ManusAgent newGatewayStyleAgent(String userMessage) {
        ManusAgent agent = new ManusAgent(allTools, dashScopeChatModel);
        agent.setSystemPrompt(manusMemoryEnricher.buildEnrichedSystemPrompt(agent.getSystemPrompt(), userMessage));
        return agent;
    }

    @Test
    @DisplayName("用户让助手回顾前天日记并给建议")
    void userAsksToReviewDayBeforeYesterdayDiary() {
        String userPrompt = """
                我这几天状态起伏挺大的。你帮我翻一下我「前天」那天的日记（本地 workspace 里按日期存的那份），
                看看我那天主要纠结什么，然后给我两条可执行的小建议，别太长。
                如果前天没有日记，就直说没有，别编。
                """;
        String answer = newGatewayStyleAgent(userPrompt).run(userPrompt);
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isBlank(), "应有 Manus 最终回复");
    }

    @Test
    @DisplayName("用户要从记忆文件里确认一件事实再继续任务")
    void userNeedsFactFromMemoryBeforeContinuing() {
        String userPrompt = """
                接下来我要做一个周计划。开始前你先读一下我 workspace 里的 memory.md，
                里面如果有写我固定锻炼或休息的日子，总结成两条约束，再帮我列一个极简周计划草稿。
                如果 memory 里没写清楚，就说明信息不足并问我缺什么。
                """;
        String answer = newGatewayStyleAgent(userPrompt).run(userPrompt);
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isBlank(), "应有 Manus 最终回复");
    }
}
