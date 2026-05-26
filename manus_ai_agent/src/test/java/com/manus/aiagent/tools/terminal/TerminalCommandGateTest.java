package com.manus.aiagent.tools.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalCommandGateTest {

    @Test
    void autoAllowFirstTokenCdLsPwd() {
        assertTrue(TerminalCommandGate.isAutoAllowedPrefix("cd .."));
        assertTrue(TerminalCommandGate.isAutoAllowedPrefix("ls"));
        assertTrue(TerminalCommandGate.isAutoAllowedPrefix("ls -la"));
        assertTrue(TerminalCommandGate.isAutoAllowedPrefix("pwd"));
        assertFalse(TerminalCommandGate.isAutoAllowedPrefix("dir"));
        assertFalse(TerminalCommandGate.isAutoAllowedPrefix("echo hi"));
    }
}
