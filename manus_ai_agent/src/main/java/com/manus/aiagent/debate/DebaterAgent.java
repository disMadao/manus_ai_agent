package com.manus.aiagent.debate;

import com.manus.aiagent.agent.LiteAgent;
import lombok.Getter;

/**
 * 辩手：单次发言通过 {@link LiteAgent} 调用模型。
 * <p>
 * 正反方仅由 {@code team}（{@code pro}/{@code con}）区分；辩题统一为整场 {@code topic}，
 * 不在代码里拼接「恋爱是/不是…」这类立场句，避免依赖句式解析。
 * <p>
 * 无自带会话记忆；赛场内前文由 {@link DebateFlowContext} 经 {@code context} 显式注入。
 */
public class DebaterAgent {

    private static final String BASE_SYSTEM = """
            你是华语辩论赛辩手。
            若用户消息里提供了「队内/赛场上下文」，你必须参考其中真实出现的内容发言，勿虚构队友或对手没说过的话。
            只输出发言正文：不要角色自我介绍、不要括号旁白、不要重复把辩题整句当标题念。""";

    private final LiteAgent liteAgent;

    @Getter
    private final String team;

    @Getter
    private final int role;

    public DebaterAgent(LiteAgent liteAgent, String team, int role) {
        this.liteAgent = liteAgent;
        this.team = team;
        this.role = role;
    }

    public String getName() {
        return team + "_" + role;
    }

    /**
     * 正方 / 反方使用不同 system 后缀，明确「支持辩题 / 反对辩题」角色，与辩题表述形式无关。
     */
    private String systemPrompt(DebateSpeakMode mode) {
        String sideRule = "pro".equals(team)
                ? "你始终代表正方：对给定辩题持「肯定、支持」立场，论证辩题成立或应选择支持一侧。"
                : "你始终代表反方：对给定辩题持「否定、反对」立场，论证辩题不成立或应选择反对一侧。";
        String prepRule = mode == DebateSpeakMode.TEAM_PREP
                ? "当前为赛前队内准备：另一支队伍尚未发言，场上不存在已发生的交叉质询，勿假装对方辩手已经说过话。"
                : "";
        String extra = prepRule.isEmpty() ? "" : "\n" + prepRule;
        return BASE_SYSTEM + "\n" + sideRule + extra;
    }

    /**
     * 默认公开赛场模式（立论及之后环节）。
     */
    public String speak(String topic, String context, int maxChars) {
        return speak(topic, context, maxChars, DebateSpeakMode.PUBLIC);
    }

    /**
     * @param mode {@link DebateSpeakMode#TEAM_PREP} 用于准备阶段，禁止虚构对方辩手发言。
     */
    public String speak(String topic, String context, int maxChars, DebateSpeakMode mode) {
        String user = buildUserPrompt(topic, context, maxChars, mode);
        String raw = liteAgent.chat(systemPrompt(mode), user);
        return truncate(raw, maxChars);
    }

    private String buildUserPrompt(String topic, String context, int maxChars, DebateSpeakMode mode) {
        String side = "pro".equals(team) ? "正" : "反";
        String sideDuty = "pro".equals(team)
                ? "你方任务：论证辩题成立或应选择「支持」一侧。"
                : "你方任务：论证辩题不成立或应选择「反对」一侧。";
        String ctx = context == null ? "" : context.strip();
        String ctxSectionTitle = mode == DebateSpeakMode.TEAM_PREP
                ? "队内上下文（仅本队材料，另一队尚未立论）"
                : "赛场上下文（含赛前与已发言内容；可能很长，请抓住与本轮相关的部分）";
        String ctxPlaceholder = ctx.isEmpty()
                ? (mode == DebateSpeakMode.TEAM_PREP ? "（暂无，仅基于辩题与己方角色。）" : "（暂无，仅基于辩题与己方角色立论。）")
                : ctx;

        String writingBlock = mode == DebateSpeakMode.TEAM_PREP
                ? """
                        写作要求（赛前队内，严禁虚构已与对方交锋）：
                        1. 禁止使用「对方一辩」「对方二辩」「对方刚才说」「对方辩友指出」等表述；另一支队伍此时尚未发言。
                        2. 若需预判对手，只许写「反方可能主张…」「需防备的质疑方向…」「常见反对意见可能是…」，不得写成已经发生的对辩。
                        3. 若上下文非空，应承接队友已写内容，可补充、修正或对齐口径。
                        4. 请发表不超过约%d字的辩论口语发言（有论点、有论据或例证方向）。
                        """.formatted(maxChars)
                : """
                        写作要求（公开赛场）：
                        1. 若上下文非空，至少有一处明确指向「对方论点」或「已出现的赛场表述」的回应（例如「对方一辩称…」「刚才自由辩里对方说…」），再展开你方论证。
                        2. 反驳时尽量具体到逻辑链或前提，避免只喊口号。
                        3. 请发表不超过约%d字的辩论口语发言（有论点、有论据或例证方向）。
                        """.formatted(maxChars);

        return String.format(
                """
                        你是%s方%d辩。%s

                        辩题：「%s」

                        —— %s ——
                        %s
                        —— 上下文结束 ——

                        %s
                        """,
                side,
                role,
                sideDuty,
                topic,
                ctxSectionTitle,
                ctxPlaceholder,
                writingBlock);
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        return t.length() <= maxChars ? t : t.substring(0, maxChars);
    }
}
