package com.manus.aiagent.debate;

import com.manus.aiagent.agent.LiteAgent;
import com.manus.aiagent.tools.WebSearchTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 辩论流程协调器：状态机 + 文件落盘。
 * <p>
 * 单场内的「记忆」由 {@link DebateFlowContext} 在内存中维护，每一轮把前文摘要注入模型，无需持久化库。
 */
@Component
@Slf4j
public class DebateCoordinator {

    private static final int PREP_THINK = 240;
    private static final int PREP_EXCHANGE = 320;
    private static final int PREP_QUERIES = 360;
    private static final int PREP_BRIEF = 960;

    private static final int OPENING_MAX = 1300;
    private static final int CROSS_HALF = 340;
    private static final int FREE_TEAM_BUDGET = 1400;
    private static final int FREE_PER_TURN = 220;
    private static final int MAX_FREE_ROUNDS = 40;

    private static final int CONCLUSION_MAX = 1300;

    /** 注入攻辩轮次的「赛前+立论」窗口上限（字符） */
    private static final int CROSS_BASE_CTX = 8400;
    private static final int CROSS_PRIOR_ROUNDS = 4400;

    /** 自由辩每轮可见前文上限（字符） */
    private static final int FREE_CTX = 14400;

    /** 四辩总结可见全场纪要上限（字符） */
    private static final int CONCLUSION_CTX = 18000;

    /** 落盘文件名带序号，便于按字典序阅读整场顺序 */
    private static final String FILE_01_PRO_PREP = "01_pro_team_prep.txt";
    private static final String FILE_02_CON_PREP = "02_con_team_prep.txt";
    private static final String FILE_03_PRO_OPENING = "03_pro_1_opening.txt";
    private static final String FILE_04_CON_OPENING = "04_con_1_opening.txt";
    private static final String FILE_05_CROSS = "05_cross_exam.txt";
    private static final String FILE_06_FREE = "06_free_debate.txt";
    private static final String FILE_07_CON_CLOSE = "07_con_4_conclusion.txt";
    private static final String FILE_08_PRO_CLOSE = "08_pro_4_conclusion.txt";
    private static final String FILE_09_TRANSCRIPT = "09_transcript.txt";

    private final LiteAgent liteAgent;
    private final String searchApiKey;

    private String transcriptBuffer = "";
    private Path outputDir;

    public DebateCoordinator(LiteAgent liteAgent,
                             @Value("${search-api.api-key:}") String searchApiKey) {
        this.liteAgent = liteAgent;
        this.searchApiKey = searchApiKey;
    }

    /**
     * 仅传辩题：自动生成 debateId。正反由辩手 Agent 的 system / user 角色说明区分，不解析辩题句式。
     *
     * @return 实际使用的 debateId（目录名）
     */
    public String startDebate(String topic) throws IOException {
        String debateId = "debate_" + System.currentTimeMillis();
        runDebate(debateId, topic);
        return debateId;
    }

    /**
     * 运行整场辩论并写入 workspace/debate/{debateId}/ 。
     * <p>
     * 只需传入辩题字符串；正方论证「支持辩题」，反方论证「反对辩题」，与辩题是「是不是」还是其他表述无关。
     */
    public void runDebate(String debateId, String topic) throws IOException {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("辩题不能为空");
        }
        Path base = Path.of(System.getProperty("user.dir"), "workspace", "debate", debateId);
        Files.createDirectories(base);
        this.outputDir = base;
        this.transcriptBuffer = "";

        List<DebaterAgent> proTeam = buildTeam("pro");
        List<DebaterAgent> conTeam = buildTeam("con");

        DebateFlowContext flow = new DebateFlowContext();

        logLine("辩题：" + topic);
        logLine("开始时间：" + Instant.now());

        DebatePhase phase = DebatePhase.PREPARATION;
        while (phase != DebatePhase.END) {
            logLine("=== 阶段：" + phase.getDescription() + " ===");
            executePhase(phase, topic, proTeam, conTeam, flow);
            phase = phase.next();
        }

        Files.writeString(base.resolve(FILE_09_TRANSCRIPT), transcriptBuffer, StandardCharsets.UTF_8);
        log.info("辩论结束，输出目录：{}", base.toAbsolutePath());
    }

    private List<DebaterAgent> buildTeam(String team) {
        List<DebaterAgent> list = new ArrayList<>(4);
        for (int i = 1; i <= 4; i++) {
            list.add(new DebaterAgent(liteAgent, team, i));
        }
        return list;
    }

    private static String prepLabelPro(String topic) {
        return "准备阶段 · 正方：辩题为「" + topic
                + "」。你方整体对辩题持肯定、支持立场，队内口径需一致论证「辩题成立或应选择支持一侧」。";
    }

    private static String prepLabelCon(String topic) {
        return "准备阶段 · 反方：辩题为「" + topic
                + "」。你方整体对辩题持否定、反对立场，队内口径需一致论证「辩题不成立或应选择反对一侧」。";
    }

    private void executePhase(DebatePhase phase, String topic,
                              List<DebaterAgent> proTeam, List<DebaterAgent> conTeam,
                              DebateFlowContext flow) throws IOException {
        switch (phase) {
            case PREPARATION -> {
                String proBrief = teamPreparation(proTeam, topic, prepLabelPro(topic));
                flow.setProTeamBrief(proBrief);
                String conBrief = teamPreparation(conTeam, topic, prepLabelCon(topic));
                flow.setConTeamBrief(conBrief);
            }
            case PRO_OPENING -> {
                String openingCtx = """
 【本轮任务：正方一辩开篇立论】
                        你必须承接并展开下方《队内核稿》中的框架与论点，可补充论证，不要与核稿口径矛盾、也不要完全另起炉灶。

                        【你方赛前队内核稿】
                        %s
                        """.formatted(flow.getProTeamBrief());
                String speech = proTeam.get(0).speak(topic, openingCtx, OPENING_MAX);
                flow.setProOpening(speech);
                logLine("正方一辩立论（节选）：" + preview(speech, 400));
                writeSpeechFile(FILE_03_PRO_OPENING, "pro", "1_opening", speech);
            }
            case CON_OPENING -> {
                String openingCtx = """
                        【本轮任务：反方一辩开篇立论】
                        你必须同时做到：（1）呼应下方你方队内核稿；（2）针对「对方正方一辩立论」逐层反驳或拆解其前提/论据，发言中至少点名对方一个具体论断。

                        【你方赛前队内核稿】
                        %s

                        【对方正方一辩立论】
                        %s
                        """.formatted(flow.getConTeamBrief(), flow.getProOpening());
                String speech = conTeam.get(0).speak(topic, openingCtx, OPENING_MAX);
                flow.setConOpening(speech);
                logLine("反方一辩立论（节选）：" + preview(speech, 400));
                writeSpeechFile(FILE_04_CON_OPENING, "con", "1_opening", speech);
            }
            case CROSS_EXAM -> runCrossExam(topic, proTeam, conTeam, flow);
            case FREE_DEBATE -> freeDebate(topic, proTeam, conTeam, flow);
            case CONCLUDING -> runConcluding(topic, proTeam, conTeam, flow);
            default -> {
            }
        }
    }

    /** @return 《队内核稿》正文，供写入 flow */
    private String teamPreparation(List<DebaterAgent> team, String topic, String positionLabel) throws IOException {
        String teamKey = team.get(0).getTeam();
        StringBuilder teamLog = new StringBuilder();
        teamLog.append("=== 团队准备（思考 → 交流 → 轻量 research）===\n");
        teamLog.append(positionLabel).append("\n\n");

        StringBuilder drafts = new StringBuilder();
        for (DebaterAgent member : team) {
            String draft = member.speak(topic, "仅根据立场独立列出要点，勿引用队友。勿提对方辩手。", PREP_THINK, DebateSpeakMode.TEAM_PREP);
            drafts.append(member.getName()).append("：").append(draft).append("\n");
            teamLog.append("[思考] ").append(member.getName()).append("：").append(draft).append("\n");
        }

        String exchangeSeed = "已知队友独立思考如下：\n" + drafts;
        String exchangeContext = exchangeSeed;
        for (DebaterAgent member : team) {
            String reply = member.speak(topic, exchangeContext + "\n请做回应、补充或对齐口径（队内交流）。", PREP_EXCHANGE, DebateSpeakMode.TEAM_PREP);
            teamLog.append("[交流] ").append(member.getName()).append("：").append(reply).append("\n");
            exchangeContext = exchangeContext + "\n" + member.getName() + "：" + reply;
        }

        String queries = team.get(1).speak(topic,
                "根据上述队内讨论，列出1到3个检索问题，每行一个，不要编号废话。问题须是可直接交给搜索引擎的短关键词句，不要写辩论反驳口吻、不要出现「对方一辩」等字样。",
                PREP_QUERIES,
                DebateSpeakMode.TEAM_PREP);
        teamLog.append("[检索问题]\n").append(queries).append("\n");
        String snippets = optionalWebSearch(queries);
        teamLog.append("[检索摘录]\n").append(snippets).append("\n");

        String brief = team.get(0).speak(
                topic,
                "结合队内交流与检索摘录，写《队内核稿》：核心论点、论据关键词、预判对方可能攻击点（只写「可能质疑方向」，勿写对方辩手已发言）。\n\n"
                        + teamLog,
                PREP_BRIEF,
                DebateSpeakMode.TEAM_PREP);
        teamLog.append("[队内核稿]\n").append(brief).append("\n");

        String filename = "pro".equals(teamKey) ? FILE_01_PRO_PREP : FILE_02_CON_PREP;
        Files.writeString(outputDir.resolve(filename), teamLog.toString(), StandardCharsets.UTF_8);
        logLine("已保存 " + filename);
        return brief;
    }

    private String optionalWebSearch(String queries) {
        if (searchApiKey == null || searchApiKey.isBlank() || "your-search-api-key".equals(searchApiKey)) {
            return "（未配置 search-api，跳过检索）";
        }
        WebSearchTool tool = new WebSearchTool(searchApiKey);
        StringBuilder sb = new StringBuilder();
        int line = 0;
        for (String q : queries.split("\n")) {
            String query = q.strip();
            if (query.isEmpty() || line >= 2) {
                continue;
            }
            try {
                String r = tool.searchWeb(query);
                sb.append("Q: ").append(query).append("\nA: ").append(preview(r, 1200)).append("\n\n");
            } catch (Exception e) {
                sb.append("Q: ").append(query).append("\nA: ").append(e.getMessage()).append("\n\n");
            }
            line++;
        }
        return sb.isEmpty() ? "（无有效检索问题）" : sb.toString();
    }

    private void runCrossExam(String topic, List<DebaterAgent> proTeam, List<DebaterAgent> conTeam,
 DebateFlowContext flow) throws IOException {
        StringBuilder cross = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            DebaterAgent attacker = (i % 2 == 0) ? proTeam.get(1) : conTeam.get(1);
            DebaterAgent defender = (i % 2 == 0) ? conTeam.get(2) : proTeam.get(2);

            String base = flow.prepAndOpeningsForCross(CROSS_BASE_CTX);
            String prior = flow.crossSoFarTrimmed(CROSS_PRIOR_ROUNDS);
            String publicHearing = """
                    【赛场公开性 · 攻辩环节】
                    本环节为公开质询：裁判与双方全队均在场。持球发言者为双方二辩（提问）与三辩（应答），
                    但双方一辩与四辩同样全程旁听，已听到赛前核稿、双方一辩立论以及截至上一轮的全部攻辩内容；
                    以下材料是当前场上「所有人共知」的信息汇总（若后文有截断，仍视为全场已按赛制听完完整发言，仅模型上下文长度受限）。
                    """;
            String block = publicHearing + "\n" + base + "\n【攻辩已进行轮次（公开实录摘要）】\n"
                    + (prior.isEmpty() ? "（本轮为第一轮，尚无已完成问答）\n" : prior);
            String qTask = """
                    【本轮任务：攻辩提问】
                    基于上文双方立论与赛前核稿，向对方二辩或三辩提一个简短、尖锐的问题。
                    你的提问与随后回答将写入公开实录：对方全队（含对方一辩、四辩）与己方未持球辩友均视为已听到。
                    问题中必须点名你要攻击的「对方论点或表述」（可概括，勿空泛「请问对方」）。
                    """;
            String question = attacker.speak(topic, block + "\n" + qTask, CROSS_HALF);

            String aTask = """
                    【本轮任务：攻辩回答】
                    先直接回应问题核心；可引用上文己方立论或队内核稿中的观点承接，必要时指出对方问题中的预设不当之处。
                    你的回答同样为公开实录，双方一辩至四辩均视为已听到。
                    """;
            String answer = defender.speak(topic, block + "\n对方问：\n" + question + "\n" + aTask, CROSS_HALF);

            flow.appendCrossRound(i + 1, attacker.getName(), defender.getName(), question, answer);

            cross.append("轮次").append(i + 1).append(" ")
                    .append(attacker.getName()).append(" 问 → ")
                    .append(defender.getName()).append(" 答\n")
                    .append("问：").append(question).append("\n")
                    .append("答：").append(answer).append("\n\n");
            logLine("攻辩 " + (i + 1) + " " + attacker.getName() + " → " + defender.getName());
        }
        Files.writeString(outputDir.resolve(FILE_05_CROSS), cross.toString(), StandardCharsets.UTF_8);
    }

    private void freeDebate(String topic, List<DebaterAgent> proTeam, List<DebaterAgent> conTeam,
                            DebateFlowContext flow) throws IOException {
        int proLeft = FREE_TEAM_BUDGET;
        int conLeft = FREE_TEAM_BUDGET;
        boolean proSide = true;
        int proIdx = 0;
        int conIdx = 0;
        int totalTurns = 0;
        StringBuilder sb = new StringBuilder();

        while (proLeft > 0 && conLeft > 0 && totalTurns < MAX_FREE_ROUNDS) {
            totalTurns++;
            String ctx = flow.freeDebateContextSoFar(FREE_CTX);
            if (proSide) {
                int words = Math.min(FREE_PER_TURN, proLeft);
                DebaterAgent speaker = proTeam.get(proIdx % 4);
                proIdx++;
                String task = """
                        【本轮任务：自由辩论 · 正方发言】
                        攻辩为公开环节：双方一辩至四辩均已听完「立论 + 攻辩实录」（见上文）。若你是一辩或四辩，你并未在攻辩持球，但信息与场上所有人一致。
                        结合上文立论、攻辩与自由辩已发言，抓反方逻辑漏洞、未回应点或自相矛盾处；至少一处明确指向「对方已说内容」。
                        """;
                String speech = speaker.speak(topic, ctx + "\n" + task, words);
                int used = countChineseChars(speech);
                proLeft -= Math.max(used, 1);
                String line = "T" + totalTurns + " 正 " + speaker.getName() + "：" + speech;
                sb.append(line).append("\n");
                flow.appendFreeLine(line);
            } else {
                int words = Math.min(FREE_PER_TURN, conLeft);
                DebaterAgent speaker = conTeam.get(conIdx % 4);
                conIdx++;
                String task = """
                        【本轮任务：自由辩论 · 反方发言】
                        攻辩为公开环节：双方一辩至四辩均已听完「立论 + 攻辩实录」（见上文）。若你是一辩或四辩，你并未在攻辩持球，但信息与场上所有人一致。
                        结合上文立论、攻辩与自由辩已发言，抓正方逻辑漏洞、未回应点或自相矛盾处；至少一处明确指向「对方已说内容」。
                        """;
                String speech = speaker.speak(topic, ctx + "\n" + task, words);
                int used = countChineseChars(speech);
                conLeft -= Math.max(used, 1);
                String line = "T" + totalTurns + " 反 " + speaker.getName() + "：" + speech;
                sb.append(line).append("\n");
                flow.appendFreeLine(line);
            }
            proSide = !proSide;
        }
        Files.writeString(outputDir.resolve(FILE_06_FREE), sb.toString(), StandardCharsets.UTF_8);
        logLine("自由辩论结束，剩余字数：正" + proLeft + " 反" + conLeft + "，共" + totalTurns + "回合");
    }

    private void runConcluding(String topic, List<DebaterAgent> proTeam, List<DebaterAgent> conTeam,
                             DebateFlowContext flow) throws IOException {
        String basePack = flow.fullDebateForConclusion(CONCLUSION_CTX);
        String conTask = """

                【任务：反方四辩总结陈词】
                在完整回顾赛场纪要的基础上，收束己方论证链，归纳正方核心谬误或未回应处，并回应攻辩、自由辩中的关键交锋点。须引用或概括至少一处「对方具体表述」。""";
        String conSummary = conTeam.get(3).speak(topic, basePack + conTask, CONCLUSION_MAX);
        writeSpeechFile(FILE_07_CON_CLOSE, "con", "4_conclusion", conSummary);
        logLine("反方四辩总结（节选）：" + preview(conSummary, 320));

        String proPack = basePack + "\n【反方四辩总结实录】\n" + conSummary;
        String proTask = """

                【任务：正方四辩总结陈词】
                你已看到反方四辩总结。请回应其中对你的指控，归纳己方胜势，并回扣开篇立论与后续交锋。须引用或概括至少一处「对方具体表述」。""";
        String proSummary = proTeam.get(3).speak(topic, proPack + proTask, CONCLUSION_MAX);
        writeSpeechFile(FILE_08_PRO_CLOSE, "pro", "4_conclusion", proSummary);
        logLine("正方四辩总结（节选）：" + preview(proSummary, 320));
    }

    private static int countChineseChars(String text) {
        if (text == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') {
                n++;
            }
        }
        return n > 0 ? n : text.length();
    }

    private void writeSpeechFile(String fileName, String team, String role, String content) throws IOException {
        Path f = outputDir.resolve(fileName);
        Files.writeString(f, header(team, role) + content, StandardCharsets.UTF_8);
    }

    private static String header(String team, String role) {
        String cn = "pro".equals(team) ? "正方" : "反方";
        return "=== " + cn + " " + role + " ===\n时间：" + Instant.now() + "\n\n";
    }

    private void logLine(String line) {
        log.info("[debate] {}", line);
        transcriptBuffer += line + "\n";
    }

    private static String preview(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
