package com.manus.aiagent.gateway.channel;

import com.manus.aiagent.gateway.model.GatewayResponse;

/**
 * 外部消息渠道接口，定义与外部平台（QQ、飞书等）的交互契约
 */
public interface MessageChannel {

    /**
     * 渠道名称标识
     */
    String getChannelName();

    /**
     * 向指定会话发送消息（主动推送回复）
     *
     * @param chatId  会话 ID（群聊 ID 或私聊 ID）
     * @param content 消息内容
     */
    void sendMessage(String chatId, String content);

    /**
     * 验证来自平台的 Webhook 回调签名
     *
     * @param payload   请求体
     * @param signature 签名
     * @return 是否合法
     */
    boolean verifyCallback(String payload, String signature);

    /**
     * 从 Webhook 回调 payload 中提取用户消息文本
     *
     * @param payload Webhook 请求体 JSON
     * @return 用户消息，null 表示非消息事件
     */
    String extractMessage(String payload);

    /**
     * 从 Webhook 回调 payload 中提取会话 ID
     *
     * @param payload Webhook 请求体 JSON
     * @return 会话 ID
     */
    String extractChatId(String payload);
}
