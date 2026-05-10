package com.manus.aiagent.debate;

/**
 * 辩手发言提示模式：赛前队内与公开赛场分离，避免准备阶段幻觉「对方辩手已发言」。
 */
public enum DebateSpeakMode {

    /**
     * 赛前准备：仅本队，反方/正方另一队尚未发言，禁止「对方一辩说…」等虚构交锋。
     */
    TEAM_PREP,

    /**
     * 立论、攻辩、自由辩、总结：可指向真实赛场上的对方表述。
     */
    PUBLIC
}
