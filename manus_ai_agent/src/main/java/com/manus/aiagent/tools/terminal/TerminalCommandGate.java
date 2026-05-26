package com.manus.aiagent.tools.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 终端命令确认：自动放行（cd/ls/pwd）、永久允许列表（JSON）、会话内待确认。
 */
@Slf4j
@Component
public class TerminalCommandGate {

    private final TerminalShellRunner shellRunner;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path allowlistPath;

    private final Map<String, String> pendingByChatId = new ConcurrentHashMap<>();

    public TerminalCommandGate(
            TerminalShellRunner shellRunner,
            @Value("${manus.terminal.allowlist-file:}") String allowlistFileOverride) {
        this.shellRunner = shellRunner;
        if (allowlistFileOverride != null && !allowlistFileOverride.isBlank()) {
            this.allowlistPath = Paths.get(allowlistFileOverride).toAbsolutePath().normalize();
        } else {
            this.allowlistPath = Paths.get(System.getProperty("user.dir"), "workspace", "config", "terminal-allowlist.json")
                    .toAbsolutePath()
                    .normalize();
        }
    }

    /**
     * 用户在下一条消息中回复 y / always / n 时，由 OpenFriend / AgentGateway 先调用。
     *
     * @return 若有待确认命令且消息为确认指令，返回执行结果或取消说明；否则 empty
     */
    public Optional<String> tryConsumeConfirmationReply(String chatId, String userMessage) {
        if (userMessage == null) {
            return Optional.empty();
        }
        String token = normalizeConfirmToken(userMessage);
        if (token == null) {
            return Optional.empty();
        }
        String key = chatIdKey(chatId);
        String pending = pendingByChatId.get(key);
        if (pending == null) {
            return Optional.empty();
        }

        switch (token) {
            case "n" -> {
                pendingByChatId.remove(key);
                return Optional.of("已取消执行终端命令。");
            }
            case "y" -> {
                pendingByChatId.remove(key);
                return Optional.of(shellRunner.run(pending));
            }
            case "always" -> {
                pendingByChatId.remove(key);
                addExactToAllowlist(pending);
                return Optional.of(shellRunner.run(pending));
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /**
     * 工具入口：自动放行、永久列表或挂起待确认。
     */
    public String executeWithPolicy(String command) {
        if (command == null || command.isBlank()) {
            return "命令为空，未执行。";
        }
        String trimmed = command.trim();

        if (isAutoAllowedPrefix(trimmed)) {
            return shellRunner.run(command);
        }

        if (isPermanentlyAllowed(trimmed)) {
            return shellRunner.run(command);
        }

        String key = TerminalToolChatContext.getChatIdOrDefault();
        pendingByChatId.put(key, trimmed);

        return """
                终端命令需要用户确认后再执行。请让用户回复（仅回复一个词即可）：
                - y：仅本次执行
                - always：永久允许这条命令（写入 workspace/config/terminal-allowlist.json）
                - n：本次不执行

                待执行命令：
                ```
                %s
                ```

                在收到用户确认前，请勿重复调用本工具；用户确认后会在下一轮对话中直接完成执行。""".formatted(trimmed.replace("```", "`\u200b``"));
    }

    private static String chatIdKey(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return "_default";
        }
        return chatId;
    }

    /**
     * @return y / always / n，或 null
     */
    private static String normalizeConfirmToken(String userMessage) {
        String t = userMessage.trim();
        if (t.isEmpty()) {
            return null;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if ("y".equals(lower) || "yes".equals(lower)) {
            return "y";
        }
        if ("n".equals(lower) || "no".equals(lower)) {
            return "n";
        }
        if ("always".equals(lower) || "alwasy".equals(lower)) {
            return "always";
        }
        return null;
    }

    /**
     * 首 token 为 cd、ls、pwd 时默认允许（简单拆分，不解析引号）。
     */
    static boolean isAutoAllowedPrefix(String trimmed) {
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) {
            end++;
        }
        String first = trimmed.substring(0, end).toLowerCase(Locale.ROOT);
        return "cd".equals(first) || "ls".equals(first) || "pwd".equals(first);
    }

    private boolean isPermanentlyAllowed(String trimmed) {
        try {
            TerminalAllowlistConfig cfg = readAllowlist();
            return cfg.getExactCommands().contains(trimmed);
        } catch (Exception e) {
            log.warn("读取终端永久允许列表失败: {}", e.getMessage());
            return false;
        }
    }

    private void addExactToAllowlist(String trimmed) {
        try {
            Files.createDirectories(allowlistPath.getParent());
            TerminalAllowlistConfig cfg = readAllowlist();
            cfg.getExactCommands().add(trimmed);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(allowlistPath.toFile(), cfg);
            log.info("已写入终端永久允许命令: {}", trimmed);
        } catch (IOException e) {
            log.error("写入终端允许列表失败", e);
        }
    }

    private TerminalAllowlistConfig readAllowlist() throws IOException {
        if (!Files.exists(allowlistPath)) {
            return new TerminalAllowlistConfig();
        }
        return objectMapper.readValue(allowlistPath.toFile(), TerminalAllowlistConfig.class);
    }
}
