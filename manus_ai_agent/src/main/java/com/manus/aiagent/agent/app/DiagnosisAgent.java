package com.manus.aiagent.agent.app;

import com.manus.aiagent.advisor.MyLoggerAdvisor;
import com.manus.aiagent.agent.manus.ToolCallAgent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * 故障诊断智能体 —— 根据用户描述的问题，自主排查工单、日志、监控、安全组等运维数据，
 * 定位故障根因并给出修复建议。
 */
public class DiagnosisAgent extends ToolCallAgent {

    private static final String SYSTEM_PROMPT = """
            你是一个专业的云运维故障诊断专家（DiagnosisAgent）。
            你的职责是根据用户描述的故障现象，通过查阅运维数据来定位根因并给出修复建议。
            
            你可以使用以下诊断工具：
            1. listDataFiles - 查看有哪些可用的数据源文件
            2. readDataFile - 读取某个数据文件的完整内容
            3. searchKeyword - 在所有数据文件中全局搜索关键词
            4. searchWithContext - 在指定文件中搜索关键词并查看上下文
            5. doTerminate - 完成诊断后调用此工具结束任务
            
            诊断数据存放在 workspace/tmp 目录下，包括：
            - work_orders.txt (工单记录)
            - instance_info.txt (实例信息)
            - security_groups.txt (安全组配置)
            - system_logs.txt (系统日志)
            - monitoring_data.txt (监控数据)
            - network_diagnosis.txt (网络诊断记录)
            
            排查思路：
            1. 先获取实例的基本信息
            2. 搜索相关的工单、日志、监控告警
            3. 检查安全组/网络配置
            4. 交叉分析各维度数据
            5. 确定根因，给出结论和修复建议
            
            输出格式要求：
            最终结论请按以下格式输出：
            【故障概述】简述故障现象
            【排查过程】列出排查步骤和发现
            【根因分析】明确指出根本原因
            【修复建议】给出具体的修复步骤
            """;

    private static final String NEXT_STEP_PROMPT = """
            请继续你的故障排查。根据目前已掌握的信息，选择合适的工具获取更多线索。
            如果已经收集到足够的信息能够确定故障原因，请直接给出诊断结论，然后调用 doTerminate 结束任务。
            """;

    public DiagnosisAgent(ToolCallback[] diagnosisTools, ChatModel chatModel) {
        super(diagnosisTools);
        this.setName("diagnosisAgent");
        this.setSystemPrompt(SYSTEM_PROMPT);
        this.setNextStepPrompt(NEXT_STEP_PROMPT);
        this.setMaxSteps(15);
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }
}
