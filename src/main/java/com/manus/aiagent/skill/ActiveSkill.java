package com.manus.aiagent.skill;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 已激活的 Skill（用户激活时加载完整内容）
 * 包含 metadata + 完整的 SKILL.md 正文内容
 * 在会话中持续保持，直到用户显式移除或会话结束
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ActiveSkill {
    
    /**
     * Skill 元信息
     */
    private SkillMetadata metadata;
    
    /**
     * SKILL.md 正文内容（激活时加载）
     */
    private String content;
    
    /**
     * 变量替换后的最终内容（根据激活时的参数处理）
     */
    private String resolvedContent;
    
    /**
     * 激活时的参数
     */
    private String arguments;
    
    /**
     * 激活时间戳
     */
    private long activatedAt;
    
    /**
     * 获取 skill 名称（便捷方法）
     */
    public String getName() {
        return metadata != null ? metadata.getName() : null;
    }
    
    /**
     * 获取 skill 描述（便捷方法）
     */
    public String getDescription() {
        return metadata != null ? metadata.getDescription() : null;
    }
    
    /**
     * 获取 skill 目录（便捷方法）
     */
    public String getSkillDir() {
        return metadata != null ? metadata.getSkillDir() : null;
    }
}
