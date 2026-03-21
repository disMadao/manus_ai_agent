package com.manus.aiagent.agent;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

/**
 * ReAct (Reasoning and Acting) 模式的代理抽象类
 * 实现了思考-行动的循环模式
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public abstract class ReActAgent extends BaseAgent {

    private String lastThinkResult = "";

    /**
     * 处理当前状态并决定下一步行动
     *
     * @return 是否需要执行行动，true表示需要执行，false表示不需要执行
     */
    public abstract boolean think();

    /**
     * 执行决定的行动
     *
     * @return 行动执行结果
     */
    public abstract String act();

    /**
     * 执行单个步骤：思考和行动
     *
     * @return 步骤执行结果（返回 LLM 的思考/分析文本）
     */
    @Override
    public String step() {
        try {
            boolean shouldAct = think();
            if (!shouldAct) {
                return lastThinkResult != null && !lastThinkResult.isBlank()
                        ? lastThinkResult : "思考完成";
            }
            act();
            return lastThinkResult != null && !lastThinkResult.isBlank()
                    ? lastThinkResult : "正在处理...";
        } catch (Exception e) {
            e.printStackTrace();
            return "步骤执行失败：" + e.getMessage();
        }
    }

}
