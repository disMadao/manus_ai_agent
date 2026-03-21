package com.manus.aiagent.gateway.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 飞书 Bot 渠道实现（占位）
 * <p>
 * 接入步骤：
 * 1. 在飞书开放平台（https://open.feishu.cn）创建自建应用，获取 App ID / App Secret
 * 2. 启用「机器人」能力，配置事件订阅
 * 3. 在 application.yml 中填写 gateway.feishu.app-id / app-secret / verification-token
 * 4. 配置事件订阅请求地址为：{your-domain}/gateway/feishu/callback
 * 5. 实现下面的占位方法
 */
@Component
@Slf4j
public class FeishuChannel implements MessageChannel {

    // >>> 在 application.yml 的 gateway.feishu 下配置 <<<
    @Value("${gateway.feishu.app-id:}")
    private String appId;

    @Value("${gateway.feishu.app-secret:}")
    private String appSecret;

    @Value("${gateway.feishu.verification-token:}")
    private String verificationToken;

    @Override
    public String getChannelName() {
        return "feishu";
    }

    @Override
    public void sendMessage(String chatId, String content) {
        // TODO: 调用飞书 API 发送消息
        //  1. 获取 tenant_access_token：
        //     POST https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal
        //     Body: { "app_id": appId, "app_secret": appSecret }
        //  2. 发送消息：
        //     POST https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id
        //     Headers: Authorization: Bearer {tenant_access_token}
        //     Body: { "receive_id": chatId, "msg_type": "text", "content": "{\"text\":\"...\"}" }
        log.warn("[飞书] sendMessage 尚未实现 - chatId={}, content length={}", chatId, content.length());
    }

    @Override
    public boolean verifyCallback(String payload, String signature) {
        // TODO: 校验飞书事件订阅回调
        //  飞书使用 Verification Token 或 Encrypt Key 进行验签
        log.warn("[飞书] verifyCallback 尚未实现");
        return true;
    }

    @Override
    public String extractMessage(String payload) {
        // TODO: 从飞书 Event 回调中提取消息文本
        //  解析 JSON：event.message.content -> 解析其中的 text
        //  注意区分 url_verification 事件和 im.message.receive_v1 事件
        log.warn("[飞书] extractMessage 尚未实现");
        return null;
    }

    @Override
    public String extractChatId(String payload) {
        // TODO: 从飞书 Event 回调中提取会话 ID
        //  解析 JSON：event.message.chat_id
        log.warn("[飞书] extractChatId 尚未实现");
        return null;
    }
}
