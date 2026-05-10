package com.manus.aiagent.tools;

import com.manus.aiagent.tools.terminal.TerminalCommandGate;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 终端操作工具（执行前经 {@link TerminalCommandGate}：自动放行 cd/ls/pwd、永久允许列表、其余需用户 y/always/n 确认）
 */
@Component
@RequiredArgsConstructor
public class TerminalOperationTool {

    private final TerminalCommandGate terminalCommandGate;

    @Tool(description = """
            Execute a command in the terminal (Windows cmd). Safe commands cd/ls/pwd run immediately.
            Other commands require the user to reply y (once), always (remember this exact command), or n (cancel) in the next message.""")
    public String executeTerminalCommand(@ToolParam(description = "Command to execute in the terminal") String command) {
        return terminalCommandGate.executeWithPolicy(command);
    }
}
