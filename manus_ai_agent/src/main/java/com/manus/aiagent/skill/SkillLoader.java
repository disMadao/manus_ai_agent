package com.manus.aiagent.skill;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Skill 加载器（按需加载版本）
 * 
 * 启动时只加载 SKILL.md 的 frontmatter（元信息），
 * 完整内容在用户激活 skill 时才由 SkillSessionManager 加载。
 * 
 * 加载层次：
 * - L0: frontmatter（name, description 等）→ 启动时加载
 * - L1: SKILL.md 正文 → 激活时由 SkillSessionManager 加载
 */
@Slf4j
@Component
public class SkillLoader {

    /**
     * Skill 文件名
     */
    private static final String SKILL_FILE_NAME = "SKILL.md";

    /**
     * Frontmatter 匹配正则（匹配 ---\n...\n---）
     */
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$",
            Pattern.DOTALL
    );

    /**
     * 已加载的 Skill 元信息（name -> SkillMetadata）
     * 只包含 frontmatter，不包含正文内容
     */
    @Getter
    private final Map<String, SkillMetadata> skillMetadataMap = new ConcurrentHashMap<>();

    /**
     * 兼容旧接口：已加载的 skills（name -> Skill）
     * @deprecated 请使用 skillMetadataMap
     */
    @Deprecated
    @Getter
    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    /**
     * Skill 扫描目录（可配置）
     */
    @Value("${skill.dirs:}")
    private String customSkillDirs;

    /**
     * 默认的 skill 目录列表
     */
    private List<String> getSkillDirs() {
        List<String> dirs = new ArrayList<>();
        
        // 1. 用户级（~/.manus/skills）
        String userHome = System.getProperty("user.home");
        dirs.add(userHome + "/.manus/skills");
        
        // 2. 项目级（workspace/skills）
        String projectDir = System.getProperty("user.dir");
        dirs.add(projectDir + "/workspace/skills");
        
        // 3. 项目级备选（.manus/skills）
        dirs.add(projectDir + "/.manus/skills");
        
        // 4. 自定义目录（从配置文件读取）
        if (StrUtil.isNotBlank(customSkillDirs)) {
            String[] customDirs = customSkillDirs.split(",");
            for (String dir : customDirs) {
                if (StrUtil.isNotBlank(dir.trim())) {
                    dirs.add(dir.trim());
                }
            }
        }
        
        return dirs;
    }

    /**
     * 启动时加载所有 skills 的元信息（只加载 frontmatter）
     */
    @PostConstruct
    public void loadAllSkills() {
        log.info("开始加载 Skills 元信息（仅 frontmatter）...");
        
        for (String dir : getSkillDirs()) {
            Path path = Paths.get(dir);
            if (Files.isDirectory(path)) {
                log.info("扫描 skill 目录: {}", dir);
                loadSkillsFromDir(path);
            }
        }
        
        log.info("Skills 元信息加载完成，共加载 {} 个 skill: {}", 
                skillMetadataMap.size(), 
                String.join(", ", skillMetadataMap.keySet()));
    }

    /**
     * 从目录加载所有 skills 的元信息
     */
    private void loadSkillsFromDir(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                    .forEach(skillDir -> {
                        Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
                        if (Files.exists(skillFile)) {
                            loadSkillMetadata(skillFile, skillDir);
                        }
                    });
        } catch (Exception e) {
            log.warn("扫描目录失败: {}, 原因: {}", dir, e.getMessage());
        }
    }

    /**
     * 加载单个 skill 的元信息（只解析 frontmatter，不读取正文）
     */
    private void loadSkillMetadata(Path file, Path skillDir) {
        try {
            String content = FileUtil.readUtf8String(file.toFile());
            String defaultName = skillDir.getFileName().toString();
            
            // 只解析 frontmatter，获取元信息
            SkillMetadata metadata = parseFrontmatterOnly(content, defaultName);
            metadata.setFilePath(file.toString());
            metadata.setSkillDir(skillDir.toString());
            
            // 后加载的覆盖先加载的（项目级覆盖用户级）
            skillMetadataMap.put(metadata.getName(), metadata);
            
            // 兼容旧接口：同时创建 Skill 对象（但不包含 content）
            Skill skill = convertToLegacySkill(metadata);
            skills.put(skill.getName(), skill);
            
            log.debug("加载 skill 元信息: {} from {}", metadata.getName(), file);
            
        } catch (Exception e) {
            log.error("加载 skill 元信息失败: {}, 原因: {}", file, e.getMessage());
        }
    }

    /**
     * 只解析 SKILL.md 的 frontmatter 部分
     * 不读取 markdown 正文，实现按需加载
     */
    private SkillMetadata parseFrontmatterOnly(String content, String defaultName) {
        SkillMetadata.SkillMetadataBuilder builder = SkillMetadata.builder();
        builder.name(defaultName);
        
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        
        if (matcher.matches()) {
            String yamlContent = matcher.group(1);
            // 只解析 frontmatter，不处理 body
            parseFrontmatterToMetadata(yamlContent, builder);
        }
        // 如果没有 frontmatter，只保留默认名称
        
        return builder.build();
    }

    /**
     * 解析 YAML frontmatter 到 SkillMetadata
     */
    @SuppressWarnings("unchecked")
    private void parseFrontmatterToMetadata(String yamlContent, SkillMetadata.SkillMetadataBuilder builder) {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(yamlContent);
            
            if (data == null) {
                return;
            }
            
            // 保存额外字段
            Map<String, Object> extraFields = new HashMap<>();
            
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                
                if (value == null) continue;
                
                switch (key) {
                    case "name" -> builder.name(value.toString());
                    case "displayName", "display-name" -> builder.displayName(value.toString());
                    case "description" -> builder.description(value.toString());
                    case "when_to_use", "whenToUse", "when-to-use" -> builder.whenToUse(value.toString());
                    case "allowed-tools", "allowedTools" -> builder.allowedTools(toStringList(value));
                    case "arguments" -> builder.arguments(toStringList(value));
                    case "argument-hint", "argumentHint" -> builder.argumentHint(value.toString());
                    case "user-invocable", "userInvocable" -> builder.userInvocable(toBoolean(value));
                    case "disable-model-invocation", "disableModelInvocation" -> 
                            builder.disableModelInvocation(toBoolean(value));
                    case "version" -> builder.version(value.toString());
                    case "context" -> builder.context(value.toString());
                    case "model" -> builder.model(value.toString());
                    case "paths" -> builder.paths(toStringList(value));
                    default -> extraFields.put(key, value);
                }
            }
            
            if (!extraFields.isEmpty()) {
                builder.extraFields(extraFields);
            }
            
        } catch (Exception e) {
            log.warn("解析 YAML frontmatter 失败: {}", e.getMessage());
        }
    }

    /**
     * 解析 YAML frontmatter（兼容旧接口）
     * @deprecated 请使用 parseFrontmatterToMetadata
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    private void parseFrontmatter(String yamlContent, Skill.SkillBuilder builder) {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(yamlContent);
            
            if (data == null) {
                return;
            }
            
            // 保存额外字段
            Map<String, Object> extraFields = new HashMap<>();
            
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                
                if (value == null) continue;
                
                switch (key) {
                    case "name" -> builder.name(value.toString());
                    case "displayName", "display-name" -> builder.displayName(value.toString());
                    case "description" -> builder.description(value.toString());
                    case "when_to_use", "whenToUse", "when-to-use" -> builder.whenToUse(value.toString());
                    case "allowed-tools", "allowedTools" -> builder.allowedTools(toStringList(value));
                    case "arguments" -> builder.arguments(toStringList(value));
                    case "argument-hint", "argumentHint" -> builder.argumentHint(value.toString());
                    case "user-invocable", "userInvocable" -> builder.userInvocable(toBoolean(value));
                    case "disable-model-invocation", "disableModelInvocation" -> 
                            builder.disableModelInvocation(toBoolean(value));
                    case "version" -> builder.version(value.toString());
                    case "context" -> builder.context(value.toString());
                    case "model" -> builder.model(value.toString());
                    default -> extraFields.put(key, value);
                }
            }
            
            if (!extraFields.isEmpty()) {
                builder.extraFields(extraFields);
            }
            
        } catch (Exception e) {
            log.warn("解析 YAML frontmatter 失败: {}", e.getMessage());
        }
    }

    /**
     * 转换为字符串列表
     */
    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object value) {
        if (value instanceof List) {
            return ((List<?>) value).stream()
                    .map(Object::toString)
                    .toList();
        } else if (value instanceof String) {
            // 处理 "Read, Write, Bash" 格式
            return Arrays.stream(value.toString().split(","))
                    .map(String::trim)
                    .filter(StrUtil::isNotBlank)
                    .toList();
        }
        return List.of();
    }

    /**
     * 转换为布尔值
     */
    private boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return "true".equalsIgnoreCase(value.toString());
    }

    /**
     * 根据名称获取 skill（兼容旧接口）
     * @deprecated 请使用 getSkillMetadata
     */
    @Deprecated
    public Skill getSkill(String name) {
        return skills.get(name);
    }

    /**
     * 获取所有 skills（兼容旧接口）
     * @deprecated 请使用 getAllSkillMetadata
     */
    @Deprecated
    public Collection<Skill> getAllSkills() {
        return skills.values();
    }

    /**
     * 根据名称获取 skill 元信息
     */
    public SkillMetadata getSkillMetadata(String name) {
        return skillMetadataMap.get(name);
    }

    /**
     * 获取所有 skill 元信息
     */
    public Collection<SkillMetadata> getAllSkillMetadata() {
        return skillMetadataMap.values();
    }

    /**
     * 重新加载所有 skills
     */
    public void reload() {
        skills.clear();
        skillMetadataMap.clear();
        loadAllSkills();
    }

    /**
     * 生成给模型的 skill 列表描述（基于元信息）
     */
    public String getSkillListDescription() {
        if (skillMetadataMap.isEmpty()) {
            return "当前没有可用的 skills。";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("可用的 Skills:\n\n");
        
        for (SkillMetadata metadata : skillMetadataMap.values()) {
            // 跳过禁止模型调用的 skill
            if (metadata.isDisableModelInvocation()) {
                continue;
            }
            
            sb.append("- **").append(metadata.getName()).append("**");
            
            if (StrUtil.isNotBlank(metadata.getDescription())) {
                sb.append(": ").append(metadata.getDescription());
            }
            
            if (StrUtil.isNotBlank(metadata.getWhenToUse())) {
                sb.append("\n  触发条件: ").append(metadata.getWhenToUse());
            }
            
            if (metadata.getArguments() != null && !metadata.getArguments().isEmpty()) {
                sb.append("\n  参数: ").append(String.join(", ", metadata.getArguments()));
            }
            
            sb.append("\n");
        }
        
        return sb.toString();
    }

    /**
     * 将 SkillMetadata 转换为旧版 Skill 对象（不包含 content）
     */
    private Skill convertToLegacySkill(SkillMetadata metadata) {
        return Skill.builder()
                .name(metadata.getName())
                .displayName(metadata.getDisplayName())
                .description(metadata.getDescription())
                .whenToUse(metadata.getWhenToUse())
                .allowedTools(metadata.getAllowedTools())
                .arguments(metadata.getArguments())
                .argumentHint(metadata.getArgumentHint())
                .filePath(metadata.getFilePath())
                .skillDir(metadata.getSkillDir())
                .userInvocable(metadata.isUserInvocable())
                .disableModelInvocation(metadata.isDisableModelInvocation())
                .version(metadata.getVersion())
                .context(metadata.getContext())
                .model(metadata.getModel())
                .extraFields(metadata.getExtraFields())
                // content 不设置，实现按需加载
                .build();
    }
}
