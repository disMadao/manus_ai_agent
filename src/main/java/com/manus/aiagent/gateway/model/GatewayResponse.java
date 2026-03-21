package com.manus.aiagent.gateway.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一网关响应模型，用于 QQ/飞书等非 SSE 渠道的同步回复
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayResponse {

    private String content;

    private String chatId;

    private boolean success;

    private String errorMessage;

    public static GatewayResponse ok(String content, String chatId) {
        return GatewayResponse.builder()
                .content(content)
                .chatId(chatId)
                .success(true)
                .build();
    }

    public static GatewayResponse error(String errorMessage) {
        return GatewayResponse.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
