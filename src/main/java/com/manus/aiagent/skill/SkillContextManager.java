package com.manus.aiagent.skill;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Skill 上下文管理器
 * 负责在 Agent 执行过程中管理 skill 相关的上下文
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillContextManager {

    private final SkillLoader skillLoader;

    /**
     * 当前会话激活的 skill（可选）
     */
    @Getter
    private final ThreadLocal<Skill> activeSkill = new ThreadLocal<>();

    /**
     * 当前会话允许的工具（受 skill 限制）
     */
    @Getter
    private final ThreadLocal<List<String>> allowedTools = new ThreadLocal<>();

    /**
     * 激活一个 skill（设置为当前上下文）
     */
    public void activateSkill(String skillName) {
        Skill skill = skillLoader.getSkill(skillName);
        if (skill != null) {
            activeSkill.set(skill);
            if (skill.getAllowedTools() != null && !skill.getAllowedTools().isEmpty()) {
                allowedTools.set(skill.getAllowedTools());
            }
            log.info("激活 skill: {}", skillName);
        }
    }

    /**
     * 清除当前 skill 上下文
     */
    public void clearContext() {
        activeSkill.remove();
        allowedTools.remove();
    }

    /**
     * 检查工具是否被允许（基于当前 skill 的限制）
     */
    public boolean isToolAllowed(String toolName) {
        List<String> allowed = allowedTools.get();
        if (allowed == null || allowed.isEmpty()) {
            // 没有限制，允许所有工具
            return true;
        }
        
        // 检查工具名是否在允许列表中（忽略大小写）
        return allowed.stream()
                .anyMatch(t -> t.equalsIgnoreCase(toolName));
    }

    /**
     * 生成 skill 增强的系统提示词
     * 将可用的 skills 列表注入到系统提示词中
     */
    public String enhanceSystemPrompt(String originalPrompt) {
        String skillList = skillLoader.getSkillListDescription();
        
        if (StrUtil.isBlank(skillList) || 
                "当前没有可用的 skills。".equals(skillList)) {
            return originalPrompt;
        }
        
        StringBuilder enhanced = new StringBuilder();
        enhanced.append(originalPrompt);
        enhanced.append("\n\n");
        enhanced.append("---\n\n");
        enhanced.append("## 可用的 Skills（技能）\n\n");
        enhanced.append("你可以使用 `invokeSkill` 工具来调用以下 skills。");
        enhanced.append("当用户的任务符合某个 skill 的触发条件时，优先使用对应的 skill。\n\n");
        enhanced.append(skillList);
        
        return enhanced.toString();
    }

    /**
     * 获取 skill 增强的提示词（用于特定 skill 被调用后）
     */
    public String getSkillInstructionPrompt() {
        Skill skill = activeSkill.get();
        if (skill == null) {
            return "";
        }
        
        StringBuilder prompt = new StringBuilder();
        prompt.append("当前激活的 Skill: ").append(skill.getName()).append("\n\n");
        
        if (skill.getAllowedTools() != null && !skill.getAllowedTools().isEmpty()) {
            prompt.append("⚠️ 工具限制: 在执行此 skill 时，只能使用以下工具: ");
            prompt.append(String.join(", ", skill.getAllowedTools()));
            prompt.append("\n\n");
        }
        
        prompt.append("请按照以下 skill 指令执行:\n\n");
        prompt.append(skill.getContent());
        
        return prompt.toString();
    }
}
