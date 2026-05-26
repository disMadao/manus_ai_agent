package com.manus.aiagent.tools;

import cn.hutool.core.util.StrUtil;
import com.manus.aiagent.skill.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Skill 调用工具
 * 让模型能够激活和管理预定义的 skill
 * 
 * 与旧版本的区别：
 * - 调用 invokeSkill 会激活 skill 到会话中（会话级持久化）
 * - 已激活的 skill 在整个会话中保持，不需要每次重新调用
 * - 新增 deactivateSkill 用于移除已激活的 skill
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillInvokeTool {

    private final SkillLoader skillLoader;
    private final SkillExecutor skillExecutor;
    private final SkillSessionManager skillSessionManager;

    @Tool(name = "invokeSkill",
            description = """
                    激活一个预定义的 skill（技能）。
                    Skill 激活后会在整个会话中保持有效，你需要按照 skill 的指令行事。
                    如果 skill 已经激活，会直接返回已激活状态，无需重复调用。
                    使用 listSkills 查看可用的 skills，使用 deactivateSkill 移除已激活的 skill。
                    """)
    public String invoke(
            @ToolParam(description = "要使用的 skill 名称。")
            String skillName,
            @ToolParam(description = "传递给 skill 的参数，可选。多个参数用空格分隔", required = false) 
            String arguments
    ) {
        log.info("激活 skill: {}, 参数: {}", skillName, arguments);
        
        // 检查是否已激活
        if (skillSessionManager.isActive(skillName)) {
            ActiveSkill active = skillSessionManager.getActiveSkill(skillName);
            return "Skill '" + skillName + "' 已激活（激活于 " + 
                    formatTimestamp(active.getActivatedAt()) + "），无需重复调用。\n" +
                    "你应该按照该 skill 的指令继续行事。";
        }
        
        // 激活 skill
        ActiveSkill activeSkill = skillSessionManager.activateSkill(skillName, arguments);
        
        if (activeSkill == null) {
            return buildNotFoundResponse(skillName);
        }
        
        // 构建激活成功消息
        StringBuilder result = new StringBuilder();
        result.append("✅ Skill '").append(activeSkill.getName()).append("' 激活成功！\n\n");
        
        if (StrUtil.isNotBlank(activeSkill.getDescription())) {
            result.append("描述: ").append(activeSkill.getDescription()).append("\n\n");
        }
        
        result.append("该 skill 现在已激活，你应该按照其中的指令行事。\n");
        result.append("skill 内容已注入到系统提示中，请查看并遵循。");
        
        return result.toString();
    }

    @Tool(name = "deactivateSkill",
            description = """
                    移除一个已激活的 skill。
                    移除后，该 skill 的指令将不再生效。
                    通常在用户明确要求停止使用某个 skill 时调用。
                    """)
    public String deactivate(
            @ToolParam(description = "要移除的 skill 名称") 
            String skillName
    ) {
        log.info("移除 skill: {}", skillName);
        
        boolean removed = skillSessionManager.deactivateSkill(skillName);
        
        if (removed) {
            return "✅ Skill '" + skillName + "' 已移除，相关指令不再生效。";
        } else {
            return "⚠️ Skill '" + skillName + "' 未激活，无需移除。\n" +
                    skillSessionManager.listActiveSkills();
        }
    }

    @Tool(name = "executeSkillScript",
            description = """
                    在沙箱环境中执行 skill 相关的脚本命令。
                    用于执行 skill 中定义的 Python 脚本或其他命令。
                    脚本会在隔离的 conda 环境中运行，确保安全性。
                    """)
    public String executeScript(
            @ToolParam(description = "skill 名称") 
            String skillName,
            @ToolParam(description = "要执行的脚本命令，如 'python3 tools/wechat_parser.py --file xxx'") 
            String command,
            @ToolParam(description = "额外参数，格式为 'key1=value1,key2=value2'，用于替换命令中的变量", required = false) 
            String paramsStr
    ) {
        log.info("执行 skill 脚本: skill={}, command={}, params={}", skillName, command, paramsStr);
        
        // 优先从已激活的 skill 获取
        ActiveSkill activeSkill = skillSessionManager.getActiveSkill(skillName);
        Skill skill;
        
        if (activeSkill != null) {
            // 使用已激活的 skill 信息
            skill = Skill.builder()
                    .name(activeSkill.getName())
                    .skillDir(activeSkill.getSkillDir())
                    .build();
        } else {
            // 回退到从 loader 获取
            skill = skillLoader.getSkill(skillName);
            if (skill == null) {
                return "错误: Skill '" + skillName + "' 不存在。";
            }
        }
        
        // 解析参数字符串为 Map
        Map<String, String> params = parseParams(paramsStr);
        
        // 使用 SkillExecutor 在沙箱中执行
        return skillExecutor.executeInSandbox(skill, command, params);
    }

    @Tool(name = "listSkills",
            description = "列出所有可用的 skills 及其描述，同时显示哪些已激活")
    public String listSkills() {
        StringBuilder sb = new StringBuilder();
        
        // 显示已激活的 skills
        String activeList = skillSessionManager.listActiveSkills();
        if (!activeList.contains("没有激活")) {
            sb.append("【已激活的 Skills】\n");
            sb.append(activeList);
            sb.append("\n\n");
        }
        
        // 显示所有可用的 skills
        sb.append("【所有可用的 Skills】\n");
        sb.append(skillLoader.getSkillListDescription());
        
        return sb.toString();
    }

    /**
     * 解析参数字符串为 Map
     */
    private Map<String, String> parseParams(String paramsStr) {
        Map<String, String> params = new HashMap<>();
        if (StrUtil.isBlank(paramsStr)) {
            return params;
        }
        
        String[] pairs = paramsStr.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0].trim(), kv[1].trim());
            }
        }
        return params;
    }

    /**
     * 构建未找到 skill 的响应
     */
    private String buildNotFoundResponse(String skillName) {
        StringBuilder response = new StringBuilder();
        response.append("错误: Skill '").append(skillName).append("' 不存在或禁止被模型调用。\n\n");
        response.append(skillLoader.getSkillListDescription());
        return response.toString();
    }

    /**
     * 格式化时间戳
     */
    private String formatTimestamp(long timestamp) {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(timestamp);
        java.time.LocalDateTime dateTime = java.time.LocalDateTime.ofInstant(
                instant, java.time.ZoneId.systemDefault());
        return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
    }
}
