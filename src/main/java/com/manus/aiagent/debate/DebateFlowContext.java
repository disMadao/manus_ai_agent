package com.manus.aiagent.debate;

import lombok.Getter;
import lombok.Setter;

/**
 * 单场辩论的「共享黑板」：协调器维护，每次模型调用把必要片段塞进 user 提示词。
 * 辩手 Agent 本身无会话记忆，全靠此处显式传递。
 */
@Getter
@Setter
public class DebateFlowContext {

    /** 正方准备阶段产出的《队内核稿》全文（或摘要） */
    private String proTeamBrief = "";

    private String conTeamBrief = "";

    private String proOpening = "";

    private String conOpening = "";

    private final StringBuilder crossExam = new StringBuilder();

    private final StringBuilder freeDebate = new StringBuilder();

    public void appendCrossRound(int round, String attacker, String defender, String q, String a) {
        crossExam.append("【攻辩第").append(round).append("轮】").append(attacker).append(" 问 → ").append(defender).append(" 答\n");
        crossExam.append("问：").append(q).append("\n答：").append(a).append("\n\n");
    }

    public void appendFreeLine(String line) {
        freeDebate.append(line).append('\n');
    }

    /** 立论阶段摘要，供攻辩 / 自由辩引用 */
    public String openingsBlock(int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("【正方一辩立论】\n").append(nnz(proOpening)).append("\n\n");
        sb.append("【反方一辩立论】\n").append(nnz(conOpening)).append("\n");
        return trimTo(sb.toString(), maxChars);
    }

    /** 赛前 + 立论，供攻辩引用 */
    public String prepAndOpeningsForCross(int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("【正方队内核稿（赛前）】\n").append(nnz(proTeamBrief)).append("\n\n");
        sb.append("【反方队内核稿（赛前）】\n").append(nnz(conTeamBrief)).append("\n\n");
        sb.append(openingsBlock(Integer.MAX_VALUE));
        return trimTo(sb.toString(), maxChars);
    }

    /** 攻辩开始前到当前轮之前的纪要 */
    public String crossSoFarTrimmed(int maxChars) {
        return trimTo(crossExam.toString(), maxChars);
    }

    /** 自由辩当前轮之前全场可用纪要 */
    public String freeDebateContextSoFar(int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append(prepAndOpeningsForCross(7000)).append("\n");
        sb.append("【攻辩实录摘要】\n").append(trimTo(crossExam.toString(), 5000)).append("\n\n");
        sb.append("【自由辩已发言（按时间顺序）】\n").append(freeDebate.toString());
        return trimTo(sb.toString(), maxChars);
    }

    /** 四辩总结用：尽量带全场，由协调器再截断 */
    public String fullDebateForConclusion(int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("【正方队内核稿】\n").append(nnz(proTeamBrief)).append("\n\n");
        sb.append("【反方队内核稿】\n").append(nnz(conTeamBrief)).append("\n\n");
        sb.append(openingsBlock(Integer.MAX_VALUE)).append("\n");
        sb.append("【攻辩】\n").append(crossExam).append("\n");
        sb.append("【自由辩】\n").append(freeDebate);
        return trimTo(sb.toString(), maxChars);
    }

    private static String nnz(String s) {
        return s == null ? "" : s;
    }

    private static String trimTo(String s, int maxChars) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (maxChars == Integer.MAX_VALUE || s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars) + "\n…（上文已截断，请基于可见部分衔接）";
    }
}
