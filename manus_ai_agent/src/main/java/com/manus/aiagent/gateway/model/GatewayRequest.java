package com.manus.aiagent.gateway.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一网关请求模型，适用于前端 SSE、QQ/飞书等所有渠道
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayRequest {

    private String message;

    private String chatId;

    /**
     * 对话模式：normal / thinking / super
     */
    @Builder.Default
    private String mode = "normal";

    /**
     * 消息来源渠道：web / qq / feishu
     */
    @Builder.Default
    private String source = "web";
}
