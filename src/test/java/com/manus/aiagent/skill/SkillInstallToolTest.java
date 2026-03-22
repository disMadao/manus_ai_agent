package com.manus.aiagent.skill;

import com.manus.aiagent.tools.SkillInstallTool;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillInstallTool 单元测试（不依赖 Spring 上下文，可独立运行）
 * 测试本地 Skill 安装/卸载/列出功能
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SkillInstallToolTest {

    private static Path tempSkillsDir;
    private static SkillInstallTool skillInstallTool;

    @BeforeAll
    static void setup() throws IOException {
        tempSkillsDir = Files.createTempDirectory("test-skills-");
        skillInstallTool = new SkillInstallTool(
                tempSkillsDir.toString(),
                "https://skillhub.tencent.com"
        );
    }


    @AfterAll
    static void cleanup() {
        deleteRecursive(tempSkillsDir.toFile());
    }

    @Test
    @Order(1)
    void testListInstalledSkills_empty() {
        String result = skillInstallTool.listInstalledSkills();
        assertTrue(result.contains("没有已安装的 Skill"), "空目录应提示无 Skill");
    }

    @Test
    @Order(2)
    void testInstallSkill_fallbackCreatesLocal() {
        // SkillHub 可能无法访问，验证 fallback 逻辑创建本地占位 Skill
        String result = skillInstallTool.installSkill("test-local-skill");
        System.out.println("安装结果: " + result);

        File skillDir = new File(tempSkillsDir.toFile(), "test-local-skill");
        assertTrue(skillDir.exists(), "Skill 目录应被创建（通过 SkillHub 下载或 fallback 创建）");

        File skillMd = new File(skillDir, "SKILL.md");
        assertTrue(skillMd.exists(), "SKILL.md 应被创建");
    }

    @Test
    @Order(3)
    void testInstallSkill_alreadyInstalled() {
        String result = skillInstallTool.installSkill("test-local-skill");
        assertTrue(result.contains("已经安装") || result.contains("已成功安装"),
                "重复安装应提示已安装或成功安装");
    }

    @Test
    @Order(4)
    void testListInstalledSkills_afterInstall() {
        String result = skillInstallTool.listInstalledSkills();
        System.out.println("已安装 Skills: " + result);
        assertTrue(result.contains("test-local-skill"), "应列出已安装的 Skill");
    }

    @Test
    @Order(5)
    void testSkillMdFormat() throws IOException {
        File skillMd = new File(tempSkillsDir.toFile(), "test-local-skill/SKILL.md");
        String content = Files.readString(skillMd.toPath(), StandardCharsets.UTF_8);

        assertTrue(content.contains("name:"), "SKILL.md 应包含 name 字段");
        assertTrue(content.contains("description:"), "SKILL.md 应包含 description 字段");
        assertTrue(content.startsWith("---"), "SKILL.md 应以 YAML front matter 开头");
    }

    @Test
    @Order(6)
    void testUninstallSkill() {
        String result = skillInstallTool.uninstallSkill("test-local-skill");
        assertTrue(result.contains("成功卸载"), "应返回卸载成功");

        File skillDir = new File(tempSkillsDir.toFile(), "test-local-skill");
        assertFalse(skillDir.exists(), "卸载后目录应被删除");
    }

    @Test
    @Order(7)
    void testUninstallSkill_notExists() {
        String result = skillInstallTool.uninstallSkill("nonexistent-skill");
        assertTrue(result.contains("不存在"), "卸载不存在的 Skill 应提示不存在");
    }

    @Test
    @Order(8)
    void testSearchSkills() {
        // 搜索可能因网络不通而失败，但不应抛异常
        String result = skillInstallTool.searchSkills("PDF processing");
        System.out.println("搜索结果: " + result);
        assertNotNull(result, "搜索结果不应为 null");
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}
