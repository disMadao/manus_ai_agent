package com.manus.aiagent.tools;

import com.manus.aiagent.tools.terminal.TerminalCommandGate;
import com.manus.aiagent.tools.terminal.TerminalShellRunner;
import com.manus.aiagent.tools.terminal.TerminalToolChatContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class TerminalOperationToolTest {

    @Test
    void cdRunsWithoutConfirmPrompt(@TempDir Path tempDir) throws Exception {
        Path allowFile = tempDir.resolve("terminal-allowlist.json");
        TerminalShellRunner shell = new TerminalShellRunner();
        TerminalCommandGate gate = new TerminalCommandGate(shell, allowFile.toString());
        TerminalOperationTool tool = new TerminalOperationTool(gate);
        TerminalToolChatContext.setChatId("test-terminal-1");
        try {
            String result = tool.executeTerminalCommand("cd .");
            Assertions.assertNotNull(result);
            Assertions.assertFalse(result.contains("需要用户确认"), result);
        } finally {
            TerminalToolChatContext.clear();
        }
    }

    @Test
    void riskyCommandRequiresConfirm(@TempDir Path tempDir) {
        Path allowFile = tempDir.resolve("terminal-allowlist.json");
        TerminalShellRunner shell = new TerminalShellRunner();
        TerminalCommandGate gate = new TerminalCommandGate(shell, allowFile.toString());
        TerminalOperationTool tool = new TerminalOperationTool(gate);
        TerminalToolChatContext.setChatId("test-terminal-2");
        try {
            String result = tool.executeTerminalCommand("echo should-confirm");
            Assertions.assertTrue(result.contains("需要用户确认"), result);
        } finally {
            TerminalToolChatContext.clear();
        }
    }
}
