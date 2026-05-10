package com.manus.aiagent.agent;

import com.manus.aiagent.agent.app.DiagnosisAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import jakarta.annotation.Resource;

@SpringBootTest
class DiagnosisAgentTest {

    @Resource
    private ToolCallback[] diagnosisTools;

    @Resource
    @Qualifier("dashScopeChatModel")
    private ChatModel dashScopeChatModel;

    @Test
    public void testSSHDiagnosis() {
        DiagnosisAgent agent = new DiagnosisAgent(diagnosisTools, dashScopeChatModel);
        String result = agent.run("实例 ins-abc123 SSH连不上，帮我排查一下");
        System.out.println("===== 诊断结果 =====");
        System.out.println(result);
        Assertions.assertNotNull(result);
    }
}
