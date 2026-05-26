package com.manus.aiagent.tools;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 集中的工具注册类
 */
@Configuration
public class ToolRegistration {

    @Value("${search-api.api-key}")
    private String searchApiKey;


    /**
     * 故障诊断 Agent 专用的工具集
     */
    @Bean
    public ToolCallback[] diagnosisTools() {
        String diagnosisDataDir = System.getProperty("user.dir") + "/workspace/tmp";
        DiagnosisSearchTool diagnosisSearchTool = new DiagnosisSearchTool(diagnosisDataDir);
        TerminateTool terminateTool = new TerminateTool();
        return ToolCallbacks.from(diagnosisSearchTool, terminateTool);
    }

    /**
     * 所有工具（包含 Skill 工具）
     */
    @Bean
    public ToolCallback[] allTools(MemoryWorkspaceTool memoryWorkspaceTool,
                                    SkillInvokeTool skillInvokeTool,
                                    TerminalOperationTool terminalOperationTool) {
        FileOperationTool fileOperationTool = new FileOperationTool();
        WebSearchTool webSearchTool = new WebSearchTool(searchApiKey);
        WebScrapingTool webScrapingTool = new WebScrapingTool();
        ResourceDownloadTool resourceDownloadTool = new ResourceDownloadTool();
        PDFGenerationTool pdfGenerationTool = new PDFGenerationTool();
        TerminateTool terminateTool = new TerminateTool();

        // 基础工具
        ToolCallback[] baseTools = ToolCallbacks.from(
                fileOperationTool,
                webSearchTool,
                webScrapingTool,
                resourceDownloadTool,
                terminalOperationTool,
                pdfGenerationTool,
                terminateTool,
                memoryWorkspaceTool
        );

        // Skill 工具
        ToolCallback[] skillTools = ToolCallbacks.from(skillInvokeTool);

        // 合并所有工具
        List<ToolCallback> allToolsList = new ArrayList<>();
        allToolsList.addAll(Arrays.asList(baseTools));
        allToolsList.addAll(Arrays.asList(skillTools));

        return allToolsList.toArray(new ToolCallback[0]);
    }
}
