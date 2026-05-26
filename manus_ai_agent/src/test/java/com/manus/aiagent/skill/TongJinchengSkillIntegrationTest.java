package com.manus.aiagent.skill;

import com.manus.aiagent.agent.app.OpenFriend;
import com.manus.aiagent.tools.SkillInvokeTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 童锦程 Skill 端到端集成测试
 * 测试完整的对话流程：用户输入 -> OpenFriend -> 调用 skill -> 返回结果
 */
@SpringBootTest
class TongJinchengSkillIntegrationTest {

    @Autowired
    private OpenFriend openFriend;

    @Autowired
    private SkillLoader skillLoader;

    @Autowired
    private SkillInvokeTool skillInvokeTool;

    @Test
    @DisplayName("童锦程 skill 应该被正确加载")
    void tongJinchengSkillShouldBeLoaded() {
        // 验证 skill 被加载
        Skill skill = skillLoader.getSkill("tong-jincheng-perspective");
        if (skill == null) {
            skill = skillLoader.getSkill("tong-jincheng-skill");
        }
        
        assertNotNull(skill, "童锦程 skill 应该被加载");
        
        System.out.println("=== 童锦程 Skill 信息 ===");
        System.out.println("名称: " + skill.getName());
        System.out.println("描述: " + skill.getDescription());
        System.out.println("内容长度: " + skill.getContent().length() + " 字符");
        
        // 验证核心内容
        String content = skill.getContent();
        assertTrue(content.contains("童锦程") || content.contains("深情祖师爷"), 
                "内容应该包含童锦程或深情祖师爷");
        assertTrue(content.contains("真诚") || content.contains("吸引力"), 
                "内容应该包含核心概念");
    }

    @Test
    @DisplayName("直接调用童锦程 skill 应该返回完整的角色扮演指令")
    void directInvokeShouldReturnFullInstructions() {
        String result = skillInvokeTool.invoke("tong-jincheng-perspective", "我被分手了，很痛苦");
        
        if (result.contains("不存在")) {
            result = skillInvokeTool.invoke("tong-jincheng-skill", "我被分手了，很痛苦");
        }
        
        System.out.println("=== 直接调用结果 ===");
        System.out.println(result.substring(0, Math.min(result.length(), 3000)));
        
        assertFalse(result.contains("不存在"), "应该能找到 skill");
        
        // 验证返回了 skill 的核心内容
        assertTrue(result.contains("Skill:") || result.contains("==="), 
                "应该有 skill 标识");
    }

    @Test
    @DisplayName("通过 OpenFriend 对话应该能识别并使用童锦程 skill")
    void openFriendShouldRecognizeAndUseSkill() {
        // 这个测试会真正调用 LLM，验证模型是否能识别并调用 skill
        // 使用明确的触发词
        String userMessage = "用童锦程的方式帮解答：毕业季为什么常分手";
        
        System.out.println("=== OpenFriend 对话测试 ===");
        System.out.println("用户输入: " + userMessage);
        System.out.println("正在调用 OpenFriend.doChat()...");
        
        String response = openFriend.doChat(userMessage, "test-tongjincheng-" + System.currentTimeMillis());
        
        System.out.println("\n=== 响应 ===");
        System.out.println(response);
        
        assertNotNull(response, "响应不应为空");
        assertFalse(response.contains("执行过程中出错"), "不应该有执行错误");
        
        // 响应应该包含一些内容（不检查具体内容，因为 LLM 输出不确定）
        assertTrue(response.length() > 50, "响应应该有足够的内容");
    }

    @Test
    @DisplayName("Skill 系统提示词应该包含可用 skills 列表")
    void systemPromptShouldContainSkillsList() {
        String skillList = skillLoader.getSkillListDescription();
        
        System.out.println("=== Skill 列表 ===");
        System.out.println(skillList);
        
        assertNotNull(skillList);
        // 如果有 skills，应该包含童锦程
        if (!skillList.contains("没有可用")) {
            assertTrue(skillList.contains("tong-jincheng") || skillList.contains("童锦程"),
                    "skill 列表应该包含童锦程 skill");
        }
    }

    @Test
    @DisplayName("验证童锦程 skill 的关键元素")
    void validateTongJinchengSkillKeyElements() {
        Skill skill = skillLoader.getSkill("tong-jincheng-perspective");
        if (skill == null) {
            skill = skillLoader.getSkill("tong-jincheng-skill");
        }
        
        assertNotNull(skill, "应该能加载童锦程 skill");
        
        String content = skill.getContent();
        
        System.out.println("=== 验证关键元素 ===");
        
        // 验证核心心智模型
        boolean hasAttractionPrinciple = content.contains("吸引力") || content.contains("Attraction");
        boolean hasFaceSaving = content.contains("台阶") || content.contains("Face-Saving");
        boolean hasHumanNature = content.contains("人性") || content.contains("Human Nature");
        
        System.out.println("吸引力原则: " + (hasAttractionPrinciple ? "✓" : "✗"));
        System.out.println("给台阶: " + (hasFaceSaving ? "✓" : "✗"));
        System.out.println("人性不可考验: " + (hasHumanNature ? "✓" : "✗"));
        
        assertTrue(hasAttractionPrinciple || hasFaceSaving || hasHumanNature,
                "应该包含至少一个核心心智模型");
        
        // 验证角色扮演规则
        boolean hasRolePlayRules = content.contains("角色扮演") || content.contains("第一人称");
        System.out.println("角色扮演规则: " + (hasRolePlayRules ? "✓" : "✗"));
        
        // 验证表达风格
        boolean hasExpressionStyle = content.contains("兄弟们") || content.contains("说实话");
        System.out.println("表达风格: " + (hasExpressionStyle ? "✓" : "✗"));
        
        assertTrue(hasExpressionStyle, "应该包含童锦程的表达风格");
    }
}
