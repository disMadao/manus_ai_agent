package com.manus.aiagent.tools;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 集中的工具注册类
 */
@Configuration
public class ToolRegistration {

    @Value("${search-api.api-key}")
    private String searchApiKey;

    @Value("${skillhub.skills-dir:workspace/skills}")
    private String skillsDir;

    @Value("${skillhub.base-url:https://skillhub.tencent.com}")
    private String skillHubBaseUrl;

    @Bean
    public ToolCallback[] allTools(MemoryWorkspaceTool memoryWorkspaceTool) {
        String skillsPath = System.getProperty("user.dir") + "/" + skillsDir;
        FileOperationTool fileOperationTool = new FileOperationTool();
        WebSearchTool webSearchTool = new WebSearchTool(searchApiKey);
        WebScrapingTool webScrapingTool = new WebScrapingTool();
        ResourceDownloadTool resourceDownloadTool = new ResourceDownloadTool();
        TerminalOperationTool terminalOperationTool = new TerminalOperationTool();
        PDFGenerationTool pdfGenerationTool = new PDFGenerationTool();
        TerminateTool terminateTool = new TerminateTool();
        SkillInstallTool skillInstallTool = new SkillInstallTool(skillsPath, skillHubBaseUrl);
        return ToolCallbacks.from(
                fileOperationTool,
                webSearchTool,
                webScrapingTool,
                resourceDownloadTool,
                terminalOperationTool,
                pdfGenerationTool,
                terminateTool,
                skillInstallTool,
                memoryWorkspaceTool
        );
    }
}
