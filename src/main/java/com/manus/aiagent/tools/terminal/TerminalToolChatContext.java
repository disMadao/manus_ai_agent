package com.manus.aiagent.tools.terminal;

/**
 * 将当前 HTTP / Agent 请求对应的 chatId 传入终端工具，用于按会话挂起待确认命令。
 */
public final class TerminalToolChatContext {

    private static final ThreadLocal<String> CHAT_ID = new ThreadLocal<>();

    private TerminalToolChatContext() {
    }

    public static void setChatId(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            CHAT_ID.set("_default");
        } else {
            CHAT_ID.set(chatId);
        }
    }

    public static String getChatIdOrDefault() {
        String id = CHAT_ID.get();
        return id != null ? id : "_default";
    }

    public static void clear() {
        CHAT_ID.remove();
    }
}
