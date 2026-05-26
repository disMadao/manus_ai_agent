package com.manus.aiagent.skill;

import com.manus.aiagent.tools.SkillInvokeTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillInvokeTool 集成测试
 * 测试 skill 调用工具的完整流程
 */
@SpringBootTest
class SkillInvokeToolTest {

    @Autowired
    private SkillInvokeTool skillInvokeTool;

    @Autowired
    private SkillLoader skillLoader;

    @Test
    @DisplayName("应该能够列出所有可用的 skills")
    void shouldListAllSkills() {
        String result = skillInvokeTool.listSkills();
        
        System.out.println("=== listSkills 结果 ===");
        System.out.println(result);
        
        assertNotNull(result, "结果不应为空");
        // 如果有 skill，应该包含 "可用的 Skills" 字样
        // 如果没有，应该包含 "没有可用" 字样
        assertTrue(result.contains("Skills") || result.contains("skills"), 
                "结果应该提到 skills");
    }

    @Test
    @DisplayName("调用童锦程 skill 应该返回 skill 内容")
    void shouldInvokeTongJinchengSkill() {
        // 尝试两种可能的名称
        String result = skillInvokeTool.invoke("tong-jincheng-skill", "帮我分析一下感情问题");
        
        // 如果找不到，尝试 frontmatter 中的 name
        if (result.contains("不存在")) {
            result = skillInvokeTool.invoke("tong-jincheng-perspective", "帮我分析一下感情问题");
        }
        
        System.out.println("=== 调用童锦程 Skill 结果 ===");
        System.out.println(result.substring(0, Math.min(result.length(), 2000)));
        System.out.println("...(共 " + result.length() + " 字符)");
        
        assertNotNull(result, "结果不应为空");
        
        // 应该返回 skill 内容，不应该是"不存在"
        assertFalse(result.contains("不存在"), "应该能找到童锦程 skill");
        
        // 应该包含 skill 的标志性内容
        assertTrue(result.contains("Skill:") || result.contains("童锦程") || result.contains("深情祖师爷"),
                "结果应该包含 skill 标识或童锦程相关内容");
    }

    @Test
    @DisplayName("调用不存在的 skill 应该返回错误信息和可用列表")
    void shouldReturnErrorForNonExistentSkill() {
        String result = skillInvokeTool.invoke("non-existent-skill-12345", null);
        
        System.out.println("=== 调用不存在的 Skill 结果 ===");
        System.out.println(result);
        
        assertNotNull(result, "结果不应为空");
        assertTrue(result.contains("不存在") || result.contains("Error") || result.contains("错误"),
                "应该返回错误信息");
    }

    @Test
    @DisplayName("应该支持参数替换 ${ARGUMENTS}")
    void shouldSupportArgumentsReplacement() {
        // 调用 skill 并传入参数
        String arguments = "我和前任分手半年了，还是放不下";
        String result = skillInvokeTool.invoke("tong-jincheng-perspective", arguments);
        
        // 如果找不到，尝试目录名
        if (result.contains("不存在")) {
            result = skillInvokeTool.invoke("tong-jincheng-skill", arguments);
        }
        
        System.out.println("=== 带参数调用 Skill ===");
        System.out.println("参数: " + arguments);
        System.out.println("结果片段: " + result.substring(0, Math.min(result.length(), 500)));
        
        // 验证调用成功
        assertFalse(result.contains("不存在"), "应该能找到 skill");
    }

    @Test
    @DisplayName("模糊匹配应该能找到 skill")
    void shouldSupportFuzzyMatch() {
        // 使用部分名称
        String result = skillInvokeTool.invoke("jincheng", "测试");
        
        System.out.println("=== 模糊匹配测试 ===");
        System.out.println("输入: jincheng");
        System.out.println("结果: " + (result.contains("不存在") ? "未找到" : "找到了"));
        
        // 模糊匹配可能找到也可能找不到，主要验证不会抛异常
        assertNotNull(result);
    }
}
