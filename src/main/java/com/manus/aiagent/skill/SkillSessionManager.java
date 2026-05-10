package com.manus.aiagent.skill;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Skill 会话管理器
 * 管理当前会话中已激活的 Skills，实现会话级持久化
 * 
 * 生命周期：
 * - 激活：用户调用 invokeSkill 工具
 * - 保持：整个会话期间保持在 activeSkills 中
 * - 移除：仅当用户显式请求（deactivateSkill）
 * - 清空：会话结束（应用重启/新会话开始）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillSessionManager {

    private final SkillLoader skillLoader;

    /**
     * 已激活的 Skills（会话级持久化）
     * Key: skillName
     * Value: ActiveSkill（包含完整内容）
     */
    private final Map<String, ActiveSkill> activeSkills = new ConcurrentHashMap<>();

    /**
     * Frontmatter 匹配正则
     */
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$",
            Pattern.DOTALL
    );

    /**
     * 激活一个 Skill
     * 如果已激活，直接返回；否则加载完整内容并缓存
     *
     * @param skillName skill 名称
     * @param arguments 激活参数（可选）
     * @return 激活后的 ActiveSkill，如果 skill 不存在返回 null
     */
    public ActiveSkill activateSkill(String skillName, String arguments) {
        // 1. 检查是否已激活
        ActiveSkill existing = activeSkills.get(skillName);
        if (existing != null) {
            log.info("Skill '{}' 已激活，跳过重复加载", skillName);
            return existing;
        }

        // 2. 获取元信息
        SkillMetadata metadata = skillLoader.getSkillMetadata(skillName);
        if (metadata == null) {
            // 尝试模糊匹配
            metadata = findSkillByFuzzyMatch(skillName);
            if (metadata == null) {
                log.warn("Skill '{}' 不存在", skillName);
                return null;
            }
        }

        // 3. 检查是否禁止模型调用
        if (metadata.isDisableModelInvocation()) {
            log.warn("Skill '{}' 禁止被模型调用", skillName);
            return null;
        }

        // 4. 加载完整内容（延迟加载）
        String content = loadFullContent(metadata);
        if (content == null) {
            log.error("无法加载 Skill '{}' 的完整内容", skillName);
            return null;
        }

        // 5. 变量替换
        String resolvedContent = replaceVariables(content, metadata, arguments);

        // 6. 创建 ActiveSkill 并缓存
        ActiveSkill activeSkill = ActiveSkill.builder()
                .metadata(metadata)
                .content(content)
                .resolvedContent(resolvedContent)
                .arguments(arguments)
                .activatedAt(System.currentTimeMillis())
                .build();

        activeSkills.put(metadata.getName(), activeSkill);
        log.info("Skill '{}' 激活成功", metadata.getName());

        return activeSkill;
    }

    /**
     * 移除一个 Skill（用户显式请求）
     *
     * @param skillName skill 名称
     * @return true 如果成功移除，false 如果 skill 未激活
     */
    public boolean deactivateSkill(String skillName) {
        ActiveSkill removed = activeSkills.remove(skillName);
        if (removed != null) {
            log.info("Skill '{}' 已移除", skillName);
            return true;
        }
        log.warn("Skill '{}' 未激活，无需移除", skillName);
        return false;
    }

    /**
     * 检查 Skill 是否已激活
     */
    public boolean isActive(String skillName) {
        return activeSkills.containsKey(skillName);
    }

    /**
     * 获取已激活的 Skill
     */
    public ActiveSkill getActiveSkill(String skillName) {
        return activeSkills.get(skillName);
    }

    /**
     * 获取所有已激活的 Skills
     */
    public Collection<ActiveSkill> getActiveSkills() {
        return activeSkills.values();
    }

    /**
     * 获取已激活的 Skills 数量
     */
    public int getActiveCount() {
        return activeSkills.size();
    }

    /**
     * 构建所有已激活 Skills 的 system prompt 片段
     * 在 Agent 构建 system prompt 时调用
     */
    public String buildActiveSkillsPrompt() {
        if (activeSkills.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== 当前激活的 Skills ===\n\n");
        sb.append("以下 Skills 已激活，请按照其中的指令行事：\n\n");

        for (ActiveSkill skill : activeSkills.values()) {
            sb.append("--- Skill: ").append(skill.getName()).append(" ---\n");
            
            if (StrUtil.isNotBlank(skill.getDescription())) {
                sb.append("描述: ").append(skill.getDescription()).append("\n\n");
            }
            
            // 如果有工具限制，提示
            if (skill.getMetadata().getAllowedTools() != null 
                    && !skill.getMetadata().getAllowedTools().isEmpty()) {
                sb.append("⚠️ 此 skill 限制使用以下工具: ")
                        .append(String.join(", ", skill.getMetadata().getAllowedTools()))
                        .append("\n\n");
            }
            
            sb.append(skill.getResolvedContent());
            sb.append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 列出所有已激活的 Skills（简要描述）
     */
    public String listActiveSkills() {
        if (activeSkills.isEmpty()) {
            return "当前没有激活的 skills。";
        }

        return "已激活的 Skills:\n" + activeSkills.values().stream()
                .map(s -> "- " + s.getName() + 
                        (StrUtil.isNotBlank(s.getDescription()) ? ": " + s.getDescription() : ""))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 清空所有已激活的 Skills（会话结束时调用）
     */
    public void clearAll() {
        int count = activeSkills.size();
        activeSkills.clear();
        log.info("已清空 {} 个激活的 skills", count);
    }

    /**
     * 加载 Skill 的完整内容（从文件读取正文）
     */
    private String loadFullContent(SkillMetadata metadata) {
        try {
            String filePath = metadata.getFilePath();
            if (StrUtil.isBlank(filePath)) {
                return null;
            }

            String fileContent = FileUtil.readUtf8String(filePath);
            Matcher matcher = FRONTMATTER_PATTERN.matcher(fileContent);

            if (matcher.matches()) {
                // 返回 markdown 正文部分
                return matcher.group(2).trim();
            } else {
                // 没有 frontmatter，整个内容作为正文
                return fileContent.trim();
            }
        } catch (Exception e) {
            log.error("读取 Skill 文件失败: {}", metadata.getFilePath(), e);
            return null;
        }
    }

    /**
     * 变量替换
     */
    private String replaceVariables(String content, SkillMetadata metadata, String arguments) {
        if (content == null) return "";

        // 替换 ${ARGUMENTS}
        if (StrUtil.isNotBlank(arguments)) {
            content = content.replace("${ARGUMENTS}", arguments);
        } else {
            content = content.replace("${ARGUMENTS}", "");
        }

        // 替换 ${SKILL_DIR} 和 ${CLAUDE_SKILL_DIR}
        if (StrUtil.isNotBlank(metadata.getSkillDir())) {
            content = content.replace("${CLAUDE_SKILL_DIR}", metadata.getSkillDir());
            content = content.replace("${SKILL_DIR}", metadata.getSkillDir());
        }

        // 替换 ${SESSION_ID}
        String sessionId = String.valueOf(System.currentTimeMillis());
        content = content.replace("${CLAUDE_SESSION_ID}", sessionId);
        content = content.replace("${SESSION_ID}", sessionId);

        return content;
    }

    /**
     * 模糊匹配 skill 名称
     */
    private SkillMetadata findSkillByFuzzyMatch(String name) {
        String normalizedName = name.toLowerCase().replace("-", "").replace("_", "");

        for (SkillMetadata metadata : skillLoader.getAllSkillMetadata()) {
            String skillNameNormalized = metadata.getName().toLowerCase()
                    .replace("-", "").replace("_", "");

            if (skillNameNormalized.contains(normalizedName) ||
                    normalizedName.contains(skillNameNormalized)) {
                return metadata;
            }
        }
        return null;
    }
}
