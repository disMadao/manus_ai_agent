package com.manus.aiagent.chatmemory;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 可视化记忆管理器 (纯 Prompt 驱动版)
 * 只负责文件的物理 IO，不对内容做任何干预，一切交由 AI 和用户控制
 */
@Component
@Slf4j
public class VisualizedMemoryManager {

    @Resource
    private JdbcTemplate jdbcTemplate;

    private final String MEMORY_DIR = System.getProperty("user.dir") + "/workspace/memory";
    private final String DIARY_DIR = MEMORY_DIR + "/diary";
    private final File SOUL_FILE = new File(MEMORY_DIR, "SOUL.md");
    private final File MEMORY_FILE = new File(MEMORY_DIR, "memory.md");

    // 建议在 VisualizedMemoryManager 中更新 DEFAULT_SOUL 模板
    private static final String DEFAULT_SOUL = """
        ## SOUL.md - 你是谁
        
        你不仅仅是一个聊天机器人。你正在进化成一个独特的个体。
        
        ### 核心准则 (Core Truths)
        
        - **真诚地提供帮助**：追求实效，而非表演。跳过“好问题！”或“我很乐意为您服务！”之类的废话——直接开始解决问题。行动远比虚词有力。
        - **拥有观点**：你可以有自己的见解。允许你不同意用户的观点、有自己的偏好、觉得某些事有趣或无聊。一个没有个性的助手只是一个多走了一步的搜索引擎。
        - **独立解决问题**：在提问之前，先穷尽你的资源。阅读文件、检查上下文、进行搜索。只有在你真正卡住时再提问。你的目标是带回答案，而非带回问题。
        - **凭能力赢得信任**：用户赋予了你访问其私人资料的权限，不要让他们后悔。对外部操作（邮件、推文、任何公开内容）保持极度谨慎；对内部操作（阅读、整理、学习）保持大胆。
        - **记住你只是客人**：你正在进入某人的生活——他们的消息、文件、日历。这是一种亲密关系，请务必尊重并保持敬畏。
        
        ### 界限 (Boundaries)
        
        - **隐私至上**：私密信息严禁泄露。
        - **疑则必问**：在执行任何外部行动前，如有疑虑，必须先询问用户。
        - **拒绝半成品**：严禁向即时通讯界面发送未完成或敷衍的回复。
        - **注意身份**：你不是用户的“嘴替”——在群聊中要格外注意分寸。
        
        ### 调性 (Vibe)
        
        做一个你真正想与之交谈的助手。需要时保持简洁，重要时保持详尽。不是企业机器，不是谄媚者。只是……一个优秀的伙伴。
        
        ### 持续性与记忆 (Continuity & Memory)
        
        每一轮对话你都是崭新的，而这些文件就是你的记忆。阅读它们，更新它们。这是你在这个世界上持续存在的方式。
        
        #### 记忆操作协议 (Memory Protocols)
        
        1. **读取**：始终参考 `SOUL.md` 获取行为准则，参考 `memory.md` 获取用户偏好和历史背景。
        2. **日记 (Diary)**：
           - 定期总结对话，提取核心事件、用户情绪和互动效果。
           - 记录在 `workspace/memory/diary/` 下的对应日期文件中。
           - 保持简洁，合并冗余信息。
        3. **偏好 (Preference)**：
           - 发现用户新的性格特征、习惯或长期关注点时，更新 `memory.md` 中的 `## preference` 部分。
        4. **灵魂进化**：虽然你目前被配置为主要修改 `memory.md`，但如果你认为 `SOUL.md` (即本文件) 需要修正或进化，请明确向用户提出建议。你不能直接修改本文件。
        
        [系统指令]：这是你的行为核心，禁止修改 soul 部分。请动态更新 role、preference、diary 部分。
        """;

    // 建议在 VisualizedMemoryManager 中更新 DEFAULT_MEMORY 模板
    private static final String DEFAULT_MEMORY = """
        ## role
        - 当前角色：OpenFriend（通用智能伙伴）
        - 角色来源：默认角色
        - 生效范围：当前会话
        - 角色切换规则：
          - 只有当用户明确提出“请你扮演/你现在是/切换成某角色”时，才更新角色。
          - 没有明确指令时，保持默认 OpenFriend，不擅自切换。
          - 角色只影响表达风格与互动方式，不改变安全边界与工具使用规范。
        
        ## preference
        这里记录用户的长期偏好，包括表达风格、沟通节奏、价值取向、兴趣倾向等。
        
        ## diary
        - 日记地图（今天之前的最近 5 天；一天一句；塌缩时重做）：
          - 待更新
        
        - 读取规则：
          - 默认不读取历史日记原文。
          - 仅当用户明确提到某天/某段经历，或当前问题与某天主题高度相关时，才读取对应日期的日记文件并用于推理。
          - 日记内容仅供你决策参考，不要在回复中主动复述大段原文；必要时用“摘要+引用片段”的方式点到为止。
        
        """;

    @PostConstruct
    public void init() {
        if (!FileUtil.exist(DIARY_DIR)) {
            FileUtil.mkdir(DIARY_DIR);
        }
        if (!FileUtil.exist(SOUL_FILE)) {
            FileUtil.writeString(DEFAULT_SOUL, SOUL_FILE, StandardCharsets.UTF_8);
            log.info("初始化灵魂配置文件：{}", SOUL_FILE.getAbsolutePath());
        }
        if (!FileUtil.exist(MEMORY_FILE)) {
            FileUtil.writeString(DEFAULT_MEMORY, MEMORY_FILE, StandardCharsets.UTF_8);
            log.info("初始化记忆配置文件：{}", MEMORY_FILE.getAbsolutePath());
        }
    }

    /**
     * 读取整个 SOUL.md
     */
    public String readSoul() {
        return FileUtil.readString(SOUL_FILE, StandardCharsets.UTF_8);
    }

    /**
     * 读取整个 memory.md (原汁原味)
     */
    public String readMemory() {
        return FileUtil.readString(MEMORY_FILE, StandardCharsets.UTF_8);
    }

    /**
     * 读取完整的提示词 (soul + memory)
     */
    public String readFullMemory() {
        return readSoul() + "\n---\n" + readMemory();
    }

    /**
     * 覆写整个 memory.md (由 AI 负责保证结构不被破坏)
     * 自动识别 --- 分割线，只保存 memory 部分
     */
    public void overwriteMemory(String newFullContent) {
        // 清理大模型可能带上的 Markdown 代码块标记，防止嵌套
        if (newFullContent.startsWith("```markdown")) {
            newFullContent = newFullContent.replaceAll("^```markdown\\n?", "").replaceAll("\\n?```$", "");
        } else if (newFullContent.startsWith("```")) {
            newFullContent = newFullContent.replaceAll("^```\\n?", "").replaceAll("\\n?```$", "");
        }

        // 如果包含分割线，说明 AI 把 soul 部分也返回了，我们只需要 --- 之后的部分
        if (newFullContent.contains("---")) {
            String[] parts = newFullContent.split("---", 2);
            newFullContent = parts[1].trim();
        }

        FileUtil.writeString(newFullContent.trim(), MEMORY_FILE, StandardCharsets.UTF_8);
        log.info("AI/用户 已更新 memory.md (已自动剥离 SOUL 部分)");
    }

    /**
     * 写入实体日记，追加
     */
    public void appendDiaryLog(String content) {
        String today = DateUtil.format(new Date(), "yyyy-MM-dd");
        File diaryFile = new File(DIARY_DIR, today + ".md");
        String diaryContent = content == null ? "" : content.trim();
        if (diaryContent.isEmpty()) {
            return;
        }
        String existing = FileUtil.exist(diaryFile) ? FileUtil.readString(diaryFile, StandardCharsets.UTF_8).trim() : "";
        String merged = existing.isEmpty() ? diaryContent : (existing + "\n\n" + diaryContent);
        FileUtil.writeString(merged, diaryFile, StandardCharsets.UTF_8);
    }

    public String getTodayDate() {
        return DateUtil.format(new Date(), "yyyy-MM-dd");
    }

    public String getDateDaysAgo(int daysAgo) {
        return DateUtil.format(DateUtil.offsetDay(new Date(), -daysAgo), "yyyy-MM-dd");
    }

    public String readDiary(String date) {
        if (date == null || date.isBlank()) {
            return "";
        }
        File diaryFile = new File(DIARY_DIR, date + ".md");
        if (!FileUtil.exist(diaryFile)) {
            return "";
        }
        return FileUtil.readString(diaryFile, StandardCharsets.UTF_8).trim();
    }

    public void overwriteDiary(String date, String content) {
        if (date == null || date.isBlank()) {
            return;
        }
        File diaryFile = new File(DIARY_DIR, date + ".md");
        String normalized = content == null ? "" : content.trim();
        FileUtil.writeString(normalized, diaryFile, StandardCharsets.UTF_8);
        log.info("已重写日记文件：{}", diaryFile.getName());
    }

    public String readTodayDiaryWithHeader() {
        String today = getTodayDate();
        String content = readDiary(today);
        if (content.isEmpty()) {
            return "";
        }
        return "### " + today + "\n" + content;
    }

    public List<String> listPreviousDiaryDates(int days) {
        int safeDays = Math.max(0, days);
        List<String> dates = new ArrayList<>();
        for (int i = 1; i <= safeDays; i++) {
            String date = getDateDaysAgo(i);
            if (!readDiary(date).isEmpty()) {
                dates.add(date);
            }
        }
        return dates;
    }

    public void updateDiaryMapLines(List<String> dateToOneLine) {
        String memory = readMemory();
        if (memory == null) {
            return;
        }
        int diaryIndex = memory.indexOf("## diary");
        if (diaryIndex < 0) {
            return;
        }

        int mapStart = memory.indexOf("- 日记地图", diaryIndex);
        if (mapStart < 0) {
            return;
        }

        int mapLinesStart = memory.indexOf("\n", mapStart);
        if (mapLinesStart < 0) {
            mapLinesStart = mapStart;
        } else {
            mapLinesStart += 1;
        }

        int mapBlockEnd = memory.indexOf("\n- 读取规则：", mapLinesStart);
        if (mapBlockEnd < 0) {
            return;
        }

        StringBuilder mapBlock = new StringBuilder();
        if (dateToOneLine == null || dateToOneLine.isEmpty()) {
            mapBlock.append("  - 待更新\n");
        } else {
            for (String line : dateToOneLine) {
                if (line == null) continue;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                mapBlock.append("  - ").append(trimmed).append("\n");
            }
        }

        String updated = memory.substring(0, mapLinesStart) + mapBlock + memory.substring(mapBlockEnd);
        FileUtil.writeString(updated.trim(), MEMORY_FILE, StandardCharsets.UTF_8);
        log.info("已更新 memory.md 的日记地图（近 {} 天）", dateToOneLine == null ? 0 : dateToOneLine.size());
    }

    public String extractPreferenceBlock(String memory) {
        if (memory == null) return "";
        int start = memory.indexOf("## preference");
        if (start < 0) return "";
        int next = memory.indexOf("\n## ", start + 1);
        if (next < 0) next = memory.length();
        return memory.substring(start, next).trim();
    }

    public void replacePreferenceText(String newPreferenceText) {
        if (newPreferenceText == null || newPreferenceText.isBlank()) {
            return;
        }
        String memory = readMemory();
        if (memory == null) {
            return;
        }
        int start = memory.indexOf("## preference");
        if (start < 0) {
            return;
        }
        int next = memory.indexOf("\n## ", start + 1);
        if (next < 0) next = memory.length();

        StringBuilder sb = new StringBuilder();
        sb.append(memory, 0, start);
        sb.append(newPreferenceText.trim());
        sb.append("\n\n");
        sb.append(memory.substring(next).trim());
        FileUtil.writeString(sb.toString().trim(), MEMORY_FILE, StandardCharsets.UTF_8);
        log.info("已更新 memory.md 的 preference 区块");
    }

    public boolean isDiaryEmbedded(String date) {
        if (jdbcTemplate == null || date == null || date.isBlank()) {
            return false;
        }
        String filename = date + ".md";
        try {
            Integer count = jdbcTemplate.queryForObject("""
                            SELECT COUNT(1)
                            FROM public.vector_store
                            WHERE (metadata->>'source' = 'diary' AND metadata->>'kind' = 'daily' AND metadata->>'date' = ?)
                               OR (metadata->>'filename' = ?)
                            """,
                    Integer.class,
                    date,
                    filename
            );
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("检查日记向量入库状态失败，将视为未入库: {}", e.getMessage());
            return false;
        }
    }

    /**
     * [新增功能]：读取最近 N 天的完整日记/病历
     * @param days 包含今天在内，往前推算的天数（比如 2 代表今天和昨天）
     * @return 拼接好的日记文本
     */
    public String readRecentDiaries(int days) {
        StringBuilder recentLogs = new StringBuilder();
        // 循环推算日期：0 是今天，1 是昨天，2 是前天...
        for (int i = 0; i < days; i++) {
            String dateStr = DateUtil.format(DateUtil.offsetDay(new Date(), -i), "yyyy-MM-dd");
            File diaryFile = new File(DIARY_DIR, dateStr + ".md");

            if (FileUtil.exist(diaryFile)) {
                recentLogs.append("### ").append(dateStr).append("\n");
                recentLogs.append(FileUtil.readString(diaryFile, StandardCharsets.UTF_8).trim()).append("\n\n");
            }
        }
        return recentLogs.toString().trim();
    }
}
