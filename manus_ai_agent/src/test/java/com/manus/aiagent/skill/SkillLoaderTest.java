package com.manus.aiagent.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillLoader 单元测试（不依赖 Spring 容器）
 * 测试 SKILL.md 文件的加载和解析
 */
class SkillLoaderTest {

    private SkillLoader skillLoader;

    @BeforeEach
    void setUp() {
        skillLoader = new SkillLoader();
        // 手动触发加载（因为不是 Spring 环境，@PostConstruct 不会自动执行）
        skillLoader.loadAllSkills();
    }

    @Test
    @DisplayName("应该能够加载 workspace/skills 目录下的 skills")
    void shouldLoadSkillsFromWorkspaceDir() {
        // 检查是否加载了任何 skill
        assertFalse(skillLoader.getSkills().isEmpty(), 
                "应该至少加载一个 skill（检查 workspace/skills 目录）");
        
        System.out.println("已加载的 Skills:");
        skillLoader.getAllSkills().forEach(skill -> {
            System.out.println("  - " + skill.getName() + ": " + skill.getDescription());
        });
    }

    @Test
    @DisplayName("应该能够加载童锦程 skill")
    void shouldLoadTongJinchengSkill() {
        Skill skill = skillLoader.getSkill("tong-jincheng-skill");
        
        // 如果目录名不匹配，尝试其他可能的名称
        if (skill == null) {
            skill = skillLoader.getSkill("tong-jincheng-perspective");
        }
        
        assertNotNull(skill, "应该能够加载童锦程 skill");
        
        // 验证基本字段
        System.out.println("\n=== 童锦程 Skill ===");
        System.out.println("Name: " + skill.getName());
        System.out.println("Description: " + skill.getDescription());
        System.out.println("FilePath: " + skill.getFilePath());
        System.out.println("SkillDir: " + skill.getSkillDir());
        System.out.println("Content length: " + (skill.getContent() != null ? skill.getContent().length() : 0) + " chars");
        
        // 验证 description 包含关键词
        assertNotNull(skill.getDescription(), "应该有描述");
        assertTrue(skill.getDescription().contains("童锦程") || skill.getDescription().contains("深情祖师爷"),
                "描述应该包含童锦程相关内容");
        
        // 验证内容不为空
        assertNotNull(skill.getContent(), "内容不应为空");
        assertTrue(skill.getContent().length() > 100, "内容应该足够长");
    }

    @Test
    @DisplayName("应该正确解析 YAML frontmatter")
    void shouldParseFrontmatterCorrectly() {
        Skill skill = skillLoader.getSkill("tong-jincheng-skill");
        if (skill == null) {
            skill = skillLoader.getSkill("tong-jincheng-perspective");
        }
        
        assertNotNull(skill, "应该能够加载童锦程 skill");
        
        // frontmatter 中定义的 name 应该覆盖目录名
        // tong-jincheng-skill/SKILL.md 的 frontmatter 中 name: tong-jincheng-perspective
        assertEquals("tong-jincheng-perspective", skill.getName(), 
                "name 应该来自 frontmatter");
    }

    @Test
    @DisplayName("应该能够加载 create-ex skill")
    void shouldLoadCreateExSkill() {
        Skill skill = skillLoader.getSkill("create-ex");
        
        assertNotNull(skill, "应该能够加载 create-ex skill");
        
        System.out.println("\n=== create-ex Skill ===");
        System.out.println("Name: " + skill.getName());
        System.out.println("Description: " + skill.getDescription());
        System.out.println("AllowedTools: " + skill.getAllowedTools());
        System.out.println("ArgumentHint: " + skill.getArgumentHint());
        System.out.println("UserInvocable: " + skill.isUserInvocable());
        
        // create-ex 定义了 allowed-tools
        assertNotNull(skill.getAllowedTools(), "应该有 allowed-tools");
        assertTrue(skill.getAllowedTools().contains("Read") || skill.getAllowedTools().contains("Bash"),
                "allowed-tools 应该包含 Read 或 Bash");
    }

    @Test
    @DisplayName("生成的 skill 列表描述应该包含关键信息")
    void shouldGenerateSkillListDescription() {
        String description = skillLoader.getSkillListDescription();
        
        System.out.println("\n=== Skill 列表描述 ===");
        System.out.println(description);
        
        assertNotNull(description, "描述不应为空");
        assertFalse(description.contains("没有可用"), "应该有可用的 skills");
    }
}
