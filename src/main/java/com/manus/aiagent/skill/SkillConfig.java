package com.manus.aiagent.skill;

import com.alibaba.cloud.ai.graph.advisors.SkillPromptAugmentAdvisor;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

@Configuration
@Slf4j
public class SkillConfig {

    @Value("${skillhub.skills-dir:workspace/skills}")
    private String skillsDir;

    @Bean
    public SkillRegistry skillRegistry() {
        String skillsPath = System.getProperty("user.dir") + "/" + skillsDir;
        File dir = new File(skillsPath);
        if (!dir.exists()) {
            dir.mkdirs();
            log.info("已创建 skills 目录: {}", skillsPath);
        }
        return FileSystemSkillRegistry.builder()
                .projectSkillsDirectory(skillsPath)
                .build();
    }

    @Bean
    public SkillPromptAugmentAdvisor skillPromptAugmentAdvisor(SkillRegistry skillRegistry) {
        return SkillPromptAugmentAdvisor.builder()
                .skillRegistry(skillRegistry)
                .build();
    }
}
