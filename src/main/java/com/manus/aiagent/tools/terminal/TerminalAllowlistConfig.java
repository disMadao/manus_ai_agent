package com.manus.aiagent.tools.terminal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * workspace/config/terminal-allowlist.json 结构
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TerminalAllowlistConfig {

    private Set<String> exactCommands = new LinkedHashSet<>();

    public Set<String> getExactCommands() {
        if (exactCommands == null) {
            exactCommands = new LinkedHashSet<>();
        }
        return exactCommands;
    }

    public void setExactCommands(Set<String> exactCommands) {
        this.exactCommands = exactCommands != null ? exactCommands : new LinkedHashSet<>();
    }
}
