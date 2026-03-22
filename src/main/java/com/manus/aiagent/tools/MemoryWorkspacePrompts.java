package com.manus.aiagent.tools;

/**
 * 与 {@link MemoryWorkspaceTool} 配套的系统提示与返回文案片段，供 OpenFriend / Manus 等共用，避免多套说法冲突。
 */
public final class MemoryWorkspacePrompts {

    private MemoryWorkspacePrompts() {
    }

    /**
     * 附在 OpenFriend（经 {@link com.manus.aiagent.advisor.VisualizedMemoryAdvisor}）与 ManusAgent 基础系统提示末尾，
     * 约束「读记忆/日记」时优先走工具，且与磁盘路径一致。
     */
    public static final String SYSTEM_TOOL_ROUTING_BLOCK = """

            ---
            【memoryWorkspace 工具约定】
            - 日记正文文件路径固定为：workspace/memory/diary/{yyyy-MM-dd}.md（文件名即日期）。其它目录（例如 tmp）下的 md 不是本工具读取的「按日日记」。
            - 用户点名「昨天/前天/今天/某日期」或要求**阅读**某日日记时：command=read_diary_date，argument 填相对词或 yyyy-MM-dd。
            - 用户要求**重写/润色/改写**某日日记正文时：command=write_diary_date，argument 格式「日期|意图」，例如「昨天|改写得更简洁」。禁止用 writeFile 写日记（writeFile 只能写 tmp/file）。
            - 用户要看 memory.md：command=read_memory；要看 SOUL.md：command=read_soul（只读）；要看日记地图：command=read_diary_map。
            - 若用户未要求具体日期、仅为闲聊：不必为读历史日记而调用工具。
            """;

    /**
     * 当某日日记文件不存在或为空时，附在返回中便于排查路径问题。
     */
    public static String diaryEmptyPathHint(String resolvedYyyyMmDd) {
        return "（提示：本工具只读取 workspace/memory/diary/" + resolvedYyyyMmDd
                + ".md；若内容写在其它目录，请先移动到该路径或复制一份。）";
    }
}
