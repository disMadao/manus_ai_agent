package com.manus.aiagent.debate;

/**
 * 华语辩论 MVP：硬编码阶段顺序。
 */
public enum DebatePhase {
    PREPARATION("准备"),
    PRO_OPENING("正方一辩"),
    CON_OPENING("反方一辩"),
    CROSS_EXAM("攻辩"),
    FREE_DEBATE("自由辩论"),
    CONCLUDING("总结"),
    END("结束");

    private final String description;

    DebatePhase(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public DebatePhase next() {
        return switch (this) {
            case PREPARATION -> PRO_OPENING;
            case PRO_OPENING -> CON_OPENING;
            case CON_OPENING -> CROSS_EXAM;
            case CROSS_EXAM -> FREE_DEBATE;
            case FREE_DEBATE -> CONCLUDING;
            case CONCLUDING -> END;
            case END -> END;
        };
    }
}
