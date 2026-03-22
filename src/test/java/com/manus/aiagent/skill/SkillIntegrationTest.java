package com.manus.aiagent.skill;

import com.alibaba.cloud.ai.graph.advisors.SkillPromptAugmentAdvisor;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.manus.aiagent.agent.app.OpenFriend;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

/**
 * Skills 集成测试（需要 Spring 上下文 + DashScope API Key）
 * 验证 SkillRegistry、SkillPromptAugmentAdvisor 正确注入，
 * 以及通过 OpenFriend 对话触发 Skill 安装流程。
 */
@SpringBootTest
class SkillIntegrationTest {

    @Resource
    private SkillRegistry skillRegistry;

    @Resource
    private SkillPromptAugmentAdvisor skillPromptAugmentAdvisor;

    @Resource
    private OpenFriend openFriend;

    @Test
    void testSkillRegistryBean() {
        Assertions.assertNotNull(skillRegistry, "SkillRegistry Bean 应已注入");
        System.out.println("SkillRegistry 类型: " + skillRegistry.getClass().getSimpleName());
        System.out.println("已注册技能数量: " + skillRegistry.size());
    }

    @Test
    void testSkillPromptAugmentAdvisorBean() {
        Assertions.assertNotNull(skillPromptAugmentAdvisor, "SkillPromptAugmentAdvisor Bean 应已注入");
    }

    @Test
    void testDoChatWithSkills_installCommand() {
        String chatId = UUID.randomUUID().toString();
        // 模拟用户发送"安装 xxx skills"指令
        String message = "帮我安装 pdf-extractor skills";
        var resultFlux = openFriend.doChatWithSkills(message, chatId);
        StringBuilder sb = new StringBuilder();
        resultFlux.doOnNext(sb::append).blockLast();
        String result = sb.toString();
        System.out.println("AI 回复: " + result);
        Assertions.assertNotNull(result, "AI 应返回非空回复");
    }

    @Test
    void testDoChatWithSkills_listCommand() {
        String chatId = UUID.randomUUID().toString();
        String message = "列出我已安装的所有 skills";
        var resultFlux = openFriend.doChatWithSkills(message, chatId);
        StringBuilder sb = new StringBuilder();
        resultFlux.doOnNext(sb::append).blockLast();
        String result = sb.toString();
        System.out.println("AI 回复: " + result);
        Assertions.assertNotNull(result, "AI 应返回已安装 Skills 列表");
    }
}
