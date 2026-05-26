package com.manus.aiagent.agent.manus;

import com.manus.aiagent.advisor.MyLoggerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * AI 超级智能体（拥有自主规划能力，可以直接使用）
 * 集成了 Skill 系统，能够调用预定义的技能
 *
 */
@Component
public class ManusAgent extends ToolCallAgent {


    public ManusAgent(ToolCallback[] allTools,
                        @Qualifier("dashScopeChatModel") ChatModel dashScopeChatModel
                        ) {
        super(allTools);
        this.setName("manusAgent");


        
        String NEXT_STEP_PROMPT = """
                根据用户需求，主动选择最合适的工具、技能或工具组合。
                对于复杂的任务，你可以拆解问题，逐步使用不同的工具或技能来解决。
                在使用每个工具后，清楚地解释执行结果并建议下一步的操作。
                如果你想在任何时候停止交互，请使用 `terminate` 工具或函数调用。
                """;
        this.setNextStepPrompt(NEXT_STEP_PROMPT);
        this.setMaxSteps(20);
        
        ChatClient chatClient = ChatClient.builder(dashScopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }

    @Override
    protected void cleanup() {
        super.cleanup();

    }
}