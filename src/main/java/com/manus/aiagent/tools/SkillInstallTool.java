package com.manus.aiagent.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ZipUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@Slf4j
public class SkillInstallTool {

    private final String skillsDir;
    private final String skillHubBaseUrl;

    public SkillInstallTool(String skillsDir, String skillHubBaseUrl) {
        this.skillsDir = skillsDir;
        this.skillHubBaseUrl = skillHubBaseUrl;
    }

    @Tool(description = "Search for available AI skills from SkillHub. "
            + "Returns a list of matching skills with name, description and category. "
            + "Use installSkill to install a skill after searching.")
    public String searchSkills(
            @ToolParam(description = "Search keyword for the skill, e.g. 'PDF processing', '小红书', 'web scraping'") String query) {
        try {
            JSONObject body = new JSONObject();
            body.set("query", query);
            body.set("limit", 10);
            body.set("method", "hybrid");

            HttpResponse response = HttpRequest.post(skillHubBaseUrl + "/api/v1/skills/search")
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(15000)
                    .execute();

            if (!response.isOk()) {
                return "SkillHub 搜索失败 (HTTP " + response.getStatus() + ")，请稍后重试。";
            }

            JSONObject result = JSONUtil.parseObj(response.body());
            JSONArray skills = result.getJSONArray("results");
            if (skills == null || skills.isEmpty()) {
                return "未找到与 '" + query + "' 相关的 Skill。";
            }

            StringBuilder sb = new StringBuilder("找到以下 Skills：\n");
            for (int i = 0; i < skills.size(); i++) {
                JSONObject skill = skills.getJSONObject(i);
                sb.append(String.format("%d. **%s** - %s [分类: %s]\n",
                        i + 1,
                        skill.getStr("name", "unknown"),
                        skill.getStr("description", "无描述"),
                        skill.getStr("category", "未分类")));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("搜索 SkillHub 失败", e);
            return "搜索 SkillHub 时出错: " + e.getMessage();
        }
    }

    @Tool(description = "Install an AI skill to the local workspace/skills/ directory. "
            + "This is the ONLY correct way to install skills. "
            + "First tries to download from SkillHub, falls back to creating a local placeholder. "
            + "Do NOT use writeFile for skill installation.")
    public String installSkill(
            @ToolParam(description = "Name of the skill to install, e.g. 'pdf-extractor', 'xiaohongshu-writer'") String skillName) {
        try {
            File targetDir = new File(skillsDir, skillName);
            if (targetDir.exists()) {
                return "Skill '" + skillName + "' 已经安装在 " + targetDir.getAbsolutePath();
            }

            // 尝试直接下载
            try {
                String downloadUrl = skillHubBaseUrl + "/api/v1/skills/" + skillName + "/download";
                HttpResponse response = HttpRequest.get(downloadUrl)
                        .timeout(15000)
                        .execute();

                if (response.isOk()) {
                    return installFromResponse(response, skillName, targetDir);
                }
            } catch (Exception e) {
                log.warn("直接下载 Skill '{}' 失败，尝试搜索: {}", skillName, e.getMessage());
            }

            // 尝试搜索后安装
            try {
                return installFromSearch(skillName, targetDir);
            } catch (Exception e) {
                log.warn("搜索安装 Skill '{}' 失败，创建本地占位: {}", skillName, e.getMessage());
            }

            // 最终 fallback：创建本地占位 Skill
            return createLocalSkillFallback(skillName, targetDir);
        } catch (Exception e) {
            log.error("安装 Skill '{}' 失败", skillName, e);
            return "安装 Skill '" + skillName + "' 时出错: " + e.getMessage();
        }
    }

    private String installFromResponse(HttpResponse response, String skillName, File targetDir) throws Exception {
        String contentType = response.header("Content-Type");

        if (contentType != null && (contentType.contains("zip") || contentType.contains("octet-stream"))) {
            File tmpZip = new File(skillsDir, skillName + ".zip");
            FileUtil.writeBytes(response.bodyBytes(), tmpZip);
            ZipUtil.unzip(tmpZip, targetDir);
            FileUtil.del(tmpZip);
        } else {
            // 响应为 JSON 格式（包含 skill 内容）
            JSONObject skillData = JSONUtil.parseObj(response.body());
            targetDir.mkdirs();
            writeSkillFromJson(skillData, targetDir, skillName);
        }

        if (!new File(targetDir, "SKILL.md").exists()) {
            generateSkillMd(targetDir, skillName, "Skill installed from SkillHub");
        }

        return "Skill '" + skillName + "' 已成功安装到 " + targetDir.getAbsolutePath();
    }

    private String installFromSearch(String skillName, File targetDir) throws Exception {
        JSONObject body = new JSONObject();
        body.set("query", skillName);
        body.set("limit", 1);
        body.set("method", "hybrid");

        HttpResponse searchResp = HttpRequest.post(skillHubBaseUrl + "/api/v1/skills/search")
                .header("Content-Type", "application/json")
                .body(body.toString())
                .timeout(15000)
                .execute();

        if (!searchResp.isOk()) {
            return createLocalSkillFallback(skillName, targetDir);
        }

        JSONObject searchResult = JSONUtil.parseObj(searchResp.body());
        JSONArray results = searchResult.getJSONArray("results");

        if (results == null || results.isEmpty()) {
            return createLocalSkillFallback(skillName, targetDir);
        }

        JSONObject skill = results.getJSONObject(0);
        targetDir.mkdirs();
        writeSkillFromJson(skill, targetDir, skillName);

        if (!new File(targetDir, "SKILL.md").exists()) {
            generateSkillMd(targetDir,
                    skill.getStr("name", skillName),
                    skill.getStr("description", "Skill from SkillHub"));
        }

        return "Skill '" + skillName + "' 已成功安装到 " + targetDir.getAbsolutePath();
    }

    private String createLocalSkillFallback(String skillName, File targetDir) {
        targetDir.mkdirs();
        generateSkillMd(targetDir, skillName,
                "This skill was created as a placeholder. Please configure it manually or reinstall from SkillHub.");
        return "SkillHub 中未找到 '" + skillName + "'，已创建本地占位 Skill 目录: " + targetDir.getAbsolutePath()
                + "。请手动编辑 SKILL.md 配置技能内容。";
    }

    private void writeSkillFromJson(JSONObject skillData, File targetDir, String skillName) {
        String content = skillData.getStr("content");
        if (content != null && !content.isBlank()) {
            FileUtil.writeString(content, new File(targetDir, "SKILL.md"), StandardCharsets.UTF_8);
        } else {
            generateSkillMd(targetDir,
                    skillData.getStr("name", skillName),
                    skillData.getStr("description", "Skill from SkillHub"));
        }

        File refsDir = new File(targetDir, "references");
        refsDir.mkdirs();
        File scriptsDir = new File(targetDir, "scripts");
        scriptsDir.mkdirs();
    }

    private void generateSkillMd(File targetDir, String name, String description) {
        String skillMd = """
                ---
                name: %s
                description: %s
                ---
                
                # %s
                
                %s
                """.formatted(name, description, name, description);
        FileUtil.writeString(skillMd, new File(targetDir, "SKILL.md"), StandardCharsets.UTF_8);
    }

    @Tool(description = "Uninstall (remove) a previously installed skill from the workspace.")
    public String uninstallSkill(
            @ToolParam(description = "Name of the skill to uninstall") String skillName) {
        try {
            File targetDir = new File(skillsDir, skillName);
            if (!targetDir.exists()) {
                return "Skill '" + skillName + "' 不存在，无需卸载。";
            }
            FileUtil.del(targetDir);
            return "Skill '" + skillName + "' 已成功卸载。";
        } catch (Exception e) {
            log.error("卸载 Skill '{}' 失败", skillName, e);
            return "卸载 Skill '" + skillName + "' 时出错: " + e.getMessage();
        }
    }

    @Tool(description = "List all currently installed skills in the workspace.")
    public String listInstalledSkills() {
        try {
            File dir = new File(skillsDir);
            if (!dir.exists() || !dir.isDirectory()) {
                return "当前没有已安装的 Skill。";
            }

            File[] skillDirs = dir.listFiles(File::isDirectory);
            if (skillDirs == null || skillDirs.length == 0) {
                return "当前没有已安装的 Skill。";
            }

            StringBuilder sb = new StringBuilder("已安装的 Skills：\n");
            for (File skillDir : skillDirs) {
                File skillMd = new File(skillDir, "SKILL.md");
                String desc = "无描述";
                if (skillMd.exists()) {
                    desc = extractDescription(skillMd);
                }
                sb.append(String.format("- **%s**: %s\n", skillDir.getName(), desc));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("列出已安装 Skills 失败", e);
            return "列出 Skills 时出错: " + e.getMessage();
        }
    }

    private String extractDescription(File skillMd) {
        try {
            String content = Files.readString(skillMd.toPath(), StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("description:")) {
                    return trimmed.substring("description:".length()).trim();
                }
            }
        } catch (Exception e) {
            log.warn("读取 SKILL.md 描述失败: {}", skillMd.getPath());
        }
        return "无描述";
    }
}
