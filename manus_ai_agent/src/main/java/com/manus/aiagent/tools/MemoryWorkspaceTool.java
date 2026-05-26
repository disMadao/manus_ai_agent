package com.manus.aiagent.tools;

import com.manus.aiagent.agent.LiteAgent;
import com.manus.aiagent.chatmemory.VisualizedMemoryManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一操作 workspace/memory：memory.md 读写、日记地图、按日期读/写日记正文。
 * SOUL.md 仅 read_soul，禁止本工具写入。
 */
@Slf4j
@Component
public class MemoryWorkspaceTool {

    private static final Pattern H2 = Pattern.compile("^##\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern DATE_YYYY_MM_DD = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    /** 如 3天前、2天前 */
    private static final Pattern DAYS_AGO_CN = Pattern.compile("^(\\d+)\\s*天前$");

    private static final String TOOL_DESC = """
            统一操作 workspace/memory 目录下记忆与日记。
            日记正文路径：workspace/memory/diary/{yyyy-MM-dd}.md。
            command（小写）：read_memory | write_memory | read_soul | read_diary_map | read_diary_date | write_diary_date | write_memory_section。
            禁止通过本工具修改 SOUL.md。
            用户要查看某日日记：read_diary_date；要重写/润色某日日记正文：write_diary_date（不要用 writeFile，writeFile 只能写 tmp 目录）。
            argument：read_memory/read_soul/read_diary_map 可空；write_memory 为自然语言意图；
            read_diary_date：yyyy-MM-dd 或 昨天/今天/today 等（与原先一致）；
            write_diary_date：格式「日期|用户意图」，日期规则同 read_diary_date，例如「昨天|把日记改写得更简洁」或「2026-03-21|全文润色」；
            write_memory_section 为「区块名|用户意图」（只改 memory.md 内 ## 区块）。
            """;

    private static final String INVALID_CMD = "无效 command。支持: read_memory, write_memory, read_soul, read_diary_map, read_diary_date, write_diary_date, write_memory_section";
    private static final String NO_SOUL_WRITE = "不允许通过本工具写入或修改 SOUL.md。";

    private final VisualizedMemoryManager memoryManager;
    private final LiteAgent liteAgent;

    public MemoryWorkspaceTool(VisualizedMemoryManager memoryManager, LiteAgent liteAgent) {
        this.memoryManager = memoryManager;
        this.liteAgent = liteAgent;
    }

    @Tool(description = TOOL_DESC)
    public String memoryWorkspace(
            @ToolParam(description = "子命令，见工具描述") String command,
            @ToolParam(description = "见工具描述，可为空") String argument) {
        if (command == null || command.isBlank()) {
            return INVALID_CMD;
        }
        String cmd = command.trim().toLowerCase(Locale.ROOT);
        String arg = argument == null ? "" : argument;

        try {
            return switch (cmd) {
                case "read_memory" -> memoryManager.readMemory();
                case "write_memory" -> doWriteMemory(arg);
                case "read_soul" -> memoryManager.readSoul();
                case "read_diary_map" -> doReadDiaryMap();
                case "read_diary_date" -> doReadDiaryDate(arg);
                case "write_diary_date" -> doWriteDiaryDate(arg);
                case "write_memory_section" -> doWriteMemorySection(arg);
                default -> INVALID_CMD;
            };
        } catch (Exception e) {
            log.error("memoryWorkspace cmd={}", cmd, e);
            return "执行失败: " + e.getMessage();
        }
    }

    private String doWriteMemory(String userIntent) {
        if (userIntent.isBlank()) {
            return "write_memory 需要 argument（用户意图）。";
        }
        String current = memoryManager.readMemory();
        String userPrompt = """
                【当前 memory.md 全文】
                %s

                【用户意图】
                %s

                【要求】只输出新的 memory.md 完整 Markdown 正文。不要其它说明，不要使用代码围栏。
                """.formatted(current, userIntent.trim());
        String system = "你是 memory.md 编辑助手。只输出完整文件正文。";
        String out = stripFence(liteAgent.chat(system, userPrompt).trim());
        memoryManager.writeMemoryDirect(out);
        return "已写回 memory.md。";
    }

    private String doReadDiaryMap() {
        String section = memoryManager.getDiaryMapSection();
        if (section == null || section.isBlank()) {
            return "未找到 memory.md 中的日记地图区块（需含「- 日记地图」与「- 读取规则：」结构）。";
        }
        return section;
    }

    private String doReadDiaryDate(String date) {
        if (date == null || date.isBlank()) {
            return "read_diary_date 需要 argument：绝对日期 yyyy-MM-dd，或 今天/昨天/前天/大前天，或 today/yesterday/tomorrow，或「3天前」。";
        }
        String resolved = resolveDateArgument(date.trim());
        if (resolved == null) {
            return "无法解析日期: " + date.trim() + "。支持: yyyy-MM-dd、今天/昨天/前天/大前天、today/yesterday/tomorrow、数字天前（如 3天前）。";
        }
        String body = memoryManager.readDiary(resolved);
        if (body.isEmpty()) {
            return "该日无日记文件或为空: " + resolved + "\n" + MemoryWorkspacePrompts.diaryEmptyPathHint(resolved);
        }
        return body;
    }

    /**
     * 按日重写日记文件：argument 为「日期|用户意图」，日期解析规则同 {@link #doReadDiaryDate}。
     */
    private String doWriteDiaryDate(String argument) {
        if (argument.isBlank()) {
            return "write_diary_date 需要 argument，格式：日期|用户意图。例如：昨天|全文改写得更口语化；2026-03-21|润色并保留事实。";
        }
        int pipe = argument.indexOf('|');
        if (pipe < 0) {
            return "write_diary_date 须使用 | 分隔日期与用户意图，例如 昨天|按要点重写。";
        }
        String datePart = argument.substring(0, pipe).trim();
        String userIntent = argument.substring(pipe + 1).trim();
        if (userIntent.isEmpty()) {
            return "用户意图不能为空（| 右侧）。";
        }
        String resolved = resolveDateArgument(datePart);
        if (resolved == null) {
            return "无法解析日期: " + datePart + "。支持同 read_diary_date。";
        }
        String current = memoryManager.readDiary(resolved);
        String userPrompt = """
                【当前该日日记全文】（可能为空）
                %s

                【用户意图】
                %s

                【要求】只输出新的日记正文 Markdown，用于覆盖 workspace/memory/diary/%s.md。不要其它说明，不要使用代码围栏。
                """.formatted(current.isEmpty() ? "（尚无内容，可新建）" : current, userIntent, resolved);
        String system = "你是日记编辑助手。只输出日记正文。";
        String out = stripFence(liteAgent.chat(system, userPrompt).trim());
        memoryManager.overwriteDiary(resolved, out);
        return "已重写日记文件 diary/" + resolved + ".md";
    }

    /**
     * 将 argument 解析为 yyyy-MM-dd；不支持「前几天」等模糊表述（需模型自行结合日历或先问用户）。
     */
    private static String resolveDateArgument(String raw) {
        if (DATE_YYYY_MM_DD.matcher(raw).matches()) {
            return raw;
        }
        LocalDate today = LocalDate.now();
        String lower = raw.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "today" -> today.toString();
            case "yesterday" -> today.minusDays(1).toString();
            case "tomorrow" -> today.plusDays(1).toString();
            default -> {
                if (raw.equals("今天")) {
                    yield today.toString();
                }
                if (raw.equals("昨天")) {
                    yield today.minusDays(1).toString();
                }
                if (raw.equals("前天")) {
                    yield today.minusDays(2).toString();
                }
                if (raw.equals("大前天")) {
                    yield today.minusDays(3).toString();
                }
                Matcher m = DAYS_AGO_CN.matcher(raw.replace(" ", ""));
                if (m.matches()) {
                    int n = Integer.parseInt(m.group(1));
                    if (n >= 0 && n <= 3650) {
                        yield today.minusDays(n).toString();
                    }
                }
                yield null;
            }
        };
    }

    private String doWriteMemorySection(String argument) {
        if (argument.isBlank()) {
            return "write_memory_section 需要 argument，格式为「区块名|用户意图」。";
        }
        int pipe = argument.indexOf('|');
        if (pipe < 0) {
            return "write_memory_section 须使用 | 分隔区块名与用户意图，例如 preference|把偏好改成…";
        }
        String heading = argument.substring(0, pipe).trim();
        String userIntent = argument.substring(pipe + 1).trim();
        if (heading.isEmpty() || userIntent.isEmpty()) {
            return "区块名与用户意图均不能为空。";
        }
        if ("soul".equalsIgnoreCase(heading)) {
            return NO_SOUL_WRITE + " 若要改人设请直接说明由助手建议，勿通过本工具写 SOUL 文件。";
        }
        String memory = memoryManager.readMemory();
        SectionBounds bounds = findstrH2Section(memory, heading);
        if (bounds == null) {
            return "在 memory.md 中未找到 ## " + heading + "（忽略大小写）。";
        }
        String oldBlock = memory.substring(bounds.start(), bounds.end());
        String userPrompt = """
                【当前该 ## 区块全文】
                %s

                【用户意图】
                %s

                【要求】只输出替换后的单个 Markdown 二级区块：首行 ## 标题与原标题一致；不要代码围栏。
                """.formatted(oldBlock, userIntent);
        String system = "你是 memory.md 区块编辑助手。只输出一个 ## 区块。";
        String generated = stripFence(liteAgent.chat(system, userPrompt).trim());
        if (!generated.startsWith("##")) {
            generated = "## " + heading + "\n\n" + generated;
        }
        String merged = memory.substring(0, bounds.start()) + generated.trim() + "\n\n" + memory.substring(bounds.end());
        memoryManager.writeMemoryDirect(merged);
        return "已更新 memory.md 中 ## " + heading + " 区块。";
    }

    private static SectionBounds findstrH2Section(String memory, String headingWithoutHash) {
        String key = headingWithoutHash.trim().toLowerCase(Locale.ROOT);
        Matcher m = H2.matcher(memory);
        int start = -1;
        while (m.find()) {
            if (m.group(1).trim().toLowerCase(Locale.ROOT).equals(key)) {
                start = m.start();
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        Matcher m2 = H2.matcher(memory);
        int next = -1;
        while (m2.find()) {
            if (m2.start() > start) {
                next = m2.start();
                break;
            }
        }
        int end = next < 0 ? memory.length() : next;
        return new SectionBounds(start, end);
    }

    private record SectionBounds(int start, int end) {}

    private static String stripFence(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.startsWith("```markdown")) {
            t = t.replaceFirst("^```markdown\\r?\\n?", "");
        } else if (t.startsWith("```")) {
            t = t.replaceFirst("^```\\r?\\n?", "");
        }
        if (t.endsWith("```")) {
            t = t.replaceFirst("\\r?\\n?```\\s*$", "");
        }
        return t.trim();
    }
}
