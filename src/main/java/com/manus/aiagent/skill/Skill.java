package com.manus.aiagent.skill;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Skill 实体类
 * 兼容 Claude Code 的 SKILL.md 格式
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Skill {
    
    /**
     * 唯一标识（目录名或 frontmatter 中的 name）
     */
    private String name;
    
    /**
     * 显示名称
     */
    private String displayName;
    
    /**
     * 描述（给模型看的，帮助模型决定何时使用此 skill）
     */
    private String description;
    
    /**
     * 何时使用此 skill（给模型的触发条件提示）
     */
    private String whenToUse;
    
    /**
     * 允许使用的工具列表（可选，用于限制 skill 内可调用的工具）
     */
    private List<String> allowedTools;
    
    /**
     * 参数列表（可选，支持 ${ARG_NAME} 替换）
     */
    private List<String> arguments;
    
    /**
     * 参数提示（用户界面显示）
     */
    private String argumentHint;
    
    /**
     * skill 正文内容（提示词模板）
     */
    private String content;
    
    /**
     * 来源文件路径
     */
    private String filePath;
    
    /**
     * skill 所在目录路径
     */
    private String skillDir;
    
    /**
     * 是否允许用户直接调用（默认 true）
     */
    @Builder.Default
    private boolean userInvocable = true;
    
    /**
     * 是否禁止模型调用（默认 false）
     */
    @Builder.Default
    private boolean disableModelInvocation = false;
    
    /**
     * 版本号
     */
    private String version;
    
    /**
     * 执行上下文：inline（内联）或 fork（子代理）
     */
    @Builder.Default
    private String context = "inline";
    
    /**
     * 模型覆盖（可选，使用特定模型执行此 skill）
     */
    private String model;
    
    /**
     * 额外的 frontmatter 字段（用于扩展）
     */
    private Map<String, Object> extraFields;
}
