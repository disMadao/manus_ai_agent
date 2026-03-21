package com.manus.aiagent.gateway.controller;

import com.manus.aiagent.gateway.AgentGateway;
import com.manus.aiagent.gateway.channel.FeishuChannel;
import com.manus.aiagent.gateway.channel.QQChannel;
import com.manus.aiagent.gateway.model.GatewayRequest;
import com.manus.aiagent.gateway.model.GatewayResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 外部平台 Webhook 回调控制器
 * <p>
 * QQ Bot 回调：POST /gateway/qq/callback
 * 飞书 Bot 回调：POST /gateway/feishu/callback
 */
@RestController
@RequestMapping("/gateway")
@RequiredArgsConstructor
@Slf4j
public class GatewayController {

    private final AgentGateway agentGateway;
    private final QQChannel qqChannel;
    private final FeishuChannel feishuChannel;

    /**
     * QQ Bot 事件回调
     * 当 QQ 用户向机器人发送消息时，QQ 开放平台会 POST 到此端点。
     * Agent 处理后通过 QQChannel.sendMessage() 回复。
     */
    @PostMapping("/qq/callback")
    public Map<String, Object> handleQQCallback(@RequestBody String payload) {
        log.info("[QQ] 收到回调: {}", payload);

        String message = qqChannel.extractMessage(payload);
        String chatId = qqChannel.extractChatId(payload);

        if (message == null || chatId == null) {
            log.info("[QQ] 非消息事件或解析失败，忽略");
            return Map.of("status", "ok");
        }

        GatewayRequest request = GatewayRequest.builder()
                .message(message)
                .chatId("qq_" + chatId)
                .mode("normal")
                .source("qq")
                .build();

        GatewayResponse response = agentGateway.chatSync(request);
        if (response.isSuccess()) {
            qqChannel.sendMessage(chatId, response.getContent());
        }

        return Map.of("status", "ok");
    }

    /**
     * 飞书 Bot 事件回调
     * 飞书事件订阅会 POST 到此端点，包含 url_verification 和 im.message.receive_v1 等事件。
     */
    @PostMapping("/feishu/callback")
    public Map<String, Object> handleFeishuCallback(@RequestBody String payload) {
        log.info("[飞书] 收到回调: {}", payload);

        // 飞书 url_verification 事件需要返回 challenge
        if (payload.contains("\"type\":\"url_verification\"")) {
            // TODO: 解析 challenge 字段并返回
            //  { "challenge": "<from payload>" }
            log.info("[飞书] url_verification 事件，需要返回 challenge");
            return Map.of("status", "ok");
        }

        String message = feishuChannel.extractMessage(payload);
        String chatId = feishuChannel.extractChatId(payload);

        if (message == null || chatId == null) {
            log.info("[飞书] 非消息事件或解析失败，忽略");
            return Map.of("status", "ok");
        }

        GatewayRequest request = GatewayRequest.builder()
                .message(message)
                .chatId("feishu_" + chatId)
                .mode("normal")
                .source("feishu")
                .build();

        GatewayResponse response = agentGateway.chatSync(request);
        if (response.isSuccess()) {
            feishuChannel.sendMessage(chatId, response.getContent());
        }

        return Map.of("status", "ok");
    }
}
