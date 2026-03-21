package com.manus.aiagent.gateway.channel;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.manus.aiagent.gateway.AgentGateway;
import com.manus.aiagent.gateway.model.GatewayRequest;
import com.manus.aiagent.gateway.model.GatewayResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * QQ Bot 渠道：通过 WebSocket 接收消息，通过 REST API 回复消息。
 * 使用 QQBot access_token 鉴权（v2 API）。
 */
@Component
@Slf4j
public class QQChannel implements MessageChannel {

    private static final String QQ_API_BASE = "https://api.sgroup.qq.com";
    private static final String QQ_TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    private static final String QQ_DEFAULT_WS_URL = "wss://api.sgroup.qq.com/websocket/";
    private static final int MAX_RECONNECT = 10;

    @Value("${gateway.qq.app-id:}")
    private String appId;

    @Value("${gateway.qq.app-secret:}")
    private String appSecret;

    private final AgentGateway agentGateway;

    private volatile String accessToken;
    private volatile long tokenExpiresAt;
    private volatile WebSocket webSocket;
    private volatile int lastSequence = -1;
    private volatile String sessionId;
    private volatile boolean identified = false;
    private int reconnectCount = 0;

    // 保证同一时刻只有一个连接流程在跑
    private final Object connectLock = new Object();
    private volatile boolean connecting = false;

    private ScheduledExecutorService heartbeatExecutor;
    private final ScheduledExecutorService reconnectScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "qq-reconnect"));
    private final ExecutorService messageExecutor = Executors.newFixedThreadPool(4);
    private final AtomicInteger msgSeqCounter = new AtomicInteger(1);

    public QQChannel(AgentGateway agentGateway) {
        this.agentGateway = agentGateway;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (appId == null || appId.isBlank() || appId.contains("your-qq-app-id")) {
            log.info("[QQ] 未配置有效的 app-id，跳过 QQ Bot 初始化");
            return;
        }
        reconnectScheduler.submit(this::startBot);
    }

    // ==================== 启动 / 重连 ====================

    private void startBot() {
        synchronized (connectLock) {
            if (connecting) {
                log.debug("[QQ] 已有连接流程在执行，跳过");
                return;
            }
            connecting = true;
        }
        try {
            refreshAccessToken();
            String wsUrl = fetchGatewayUrl();
            connectWebSocket(wsUrl);
        } catch (Exception e) {
            log.error("[QQ] Bot 启动失败: {}", e.getMessage());
            scheduleReconnect();
        } finally {
            synchronized (connectLock) {
                connecting = false;
            }
        }
    }

    private void scheduleReconnect() {
        if (reconnectCount >= MAX_RECONNECT) {
            log.error("[QQ] 已达最大重连次数 ({})，停止。请检查配置后重启。", MAX_RECONNECT);
            return;
        }
        reconnectCount++;
        int delay = Math.min(5 * reconnectCount, 30);
        log.info("[QQ] 将在 {}s 后重连 (第 {}/{} 次)", delay, reconnectCount, MAX_RECONNECT);
        reconnectScheduler.schedule(this::startBot, delay, TimeUnit.SECONDS);
    }

    // ==================== Access Token ====================

    private void refreshAccessToken() {
        String body = JSONUtil.createObj()
                .set("appId", appId)
                .set("clientSecret", appSecret)
                .toString();
        String resp = HttpRequest.post(QQ_TOKEN_URL)
                .header("Content-Type", "application/json")
                .body(body)
                .timeout(10000)
                .execute()
                .body();
        log.debug("[QQ] getAppAccessToken 响应: {}", resp);
        JSONObject json = JSONUtil.parseObj(resp);
        if (json.containsKey("access_token")) {
            this.accessToken = json.getStr("access_token");
            int expiresIn = json.getInt("expires_in", 7200);
            this.tokenExpiresAt = System.currentTimeMillis() + (expiresIn - 120) * 1000L;
            log.info("[QQ] access_token 获取成功，有效期 {}s", expiresIn);
        } else {
            throw new RuntimeException("获取 access_token 失败: " + resp);
        }
    }

    private void ensureAccessToken() {
        if (System.currentTimeMillis() >= tokenExpiresAt) {
            refreshAccessToken();
        }
    }

    private String authHeader() {
        return "QQBot " + accessToken;
    }

    // ==================== WebSocket ====================

    private String fetchGatewayUrl() {
        try {
            String resp = HttpRequest.get(QQ_API_BASE + "/gateway")
                    .header("Authorization", authHeader())
                    .timeout(10000)
                    .execute()
                    .body();
            log.info("[QQ] /gateway 响应: {}", resp);
            JSONObject json = JSONUtil.parseObj(resp);
            String url = json.getStr("url");
            if (url != null && !url.isBlank()) {
                return url;
            }
            log.warn("[QQ] /gateway 未返回 url，使用默认地址");
        } catch (Exception e) {
            log.warn("[QQ] /gateway 调用失败: {}，使用默认地址", e.getMessage());
        }
        return QQ_DEFAULT_WS_URL;
    }

    private void connectWebSocket(String url) {
        log.info("[QQ] 连接 WebSocket: {}", url);
        identified = false;
        try {
            HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI.create(url), new WsListener())
                    .get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("WebSocket 连接失败: " + e.getMessage(), e);
        }
    }

    private class WsListener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            webSocket = ws;
            ws.request(1);
            log.info("[QQ] WebSocket 已连接");
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String text = buf.toString();
                buf.setLength(0);
                handleWsMessage(text);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            log.warn("[QQ] WebSocket 断开: code={}, reason={}", code, reason);
            stopHeartbeat();
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.error("[QQ] WebSocket 错误: {}", error.getMessage());
            stopHeartbeat();
            scheduleReconnect();
        }
    }

    // ==================== WS 消息处理 ====================

    private void handleWsMessage(String raw) {
        log.debug("[QQ] WS 收到: {}", raw);
        JSONObject msg = JSONUtil.parseObj(raw);
        int op = msg.getInt("op", -1);

        switch (op) {
            case 10 -> {
                int interval = msg.getJSONObject("d").getInt("heartbeat_interval");
                log.info("[QQ] Hello, heartbeat={}ms", interval);
                startHeartbeat(interval);
                if (sessionId != null && identified) {
                    sendResume();
                } else {
                    sendIdentify();
                }
            }
            case 0 -> {
                if (msg.get("s") != null) lastSequence = msg.getInt("s");
                handleDispatch(msg);
            }
            case 7 -> {
                log.info("[QQ] 服务端要求重连");
                stopHeartbeat();
                scheduleReconnect();
            }
            case 9 -> {
                log.warn("[QQ] Invalid Session (op:9)，完整响应: {}", raw);
                identified = false;
                sessionId = null;
                stopHeartbeat();
                scheduleReconnect();
            }
            case 11 -> {} // Heartbeat ACK
            default -> log.debug("[QQ] op={}: {}", op, raw);
        }
    }

    private void sendIdentify() {
        ensureAccessToken();
        int intents = (1 << 25); // GROUP_AND_C2C_EVENT
        JSONObject payload = JSONUtil.createObj()
                .set("op", 2)
                .set("d", JSONUtil.createObj()
                        .set("token", authHeader())
                        .set("intents", intents)
                        .set("shard", new int[]{0, 1})
                );
        String text = payload.toString();
        log.info("[QQ] 发送 IDENTIFY: {}", text);
        webSocket.sendText(text, true);
    }

    private void sendResume() {
        ensureAccessToken();
        JSONObject payload = JSONUtil.createObj()
                .set("op", 6)
                .set("d", JSONUtil.createObj()
                        .set("token", authHeader())
                        .set("session_id", sessionId)
                        .set("seq", lastSequence)
                );
        webSocket.sendText(payload.toString(), true);
        log.info("[QQ] 发送 RESUME (session={}, seq={})", sessionId, lastSequence);
    }

    // ==================== 心跳 ====================

    private void startHeartbeat(int intervalMs) {
        stopHeartbeat();
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
                r -> { Thread t = new Thread(r, "qq-heartbeat"); t.setDaemon(true); return t; }
        );
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                if (webSocket != null) {
                    String hb = JSONUtil.createObj()
                            .set("op", 1)
                            .set("d", lastSequence >= 0 ? lastSequence : null)
                            .toString();
                    webSocket.sendText(hb, true);
                }
            } catch (Exception e) {
                log.error("[QQ] 心跳失败: {}", e.getMessage());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            heartbeatExecutor.shutdownNow();
        }
    }

    // ==================== 事件分发 ====================

    private void handleDispatch(JSONObject msg) {
        String t = msg.getStr("t", "");
        JSONObject d = msg.getJSONObject("d");

        switch (t) {
            case "READY" -> {
                sessionId = d.getStr("session_id");
                identified = true;
                reconnectCount = 0;
                String botName = d.getJSONObject("user") != null ? d.getJSONObject("user").getStr("username") : "unknown";
                log.info("[QQ] Bot 就绪! name={}, session={}", botName, sessionId);
            }
            case "RESUMED" -> {
                reconnectCount = 0;
                log.info("[QQ] 会话已恢复");
            }
            case "C2C_MESSAGE_CREATE" -> handleC2CMessage(d);
            case "GROUP_AT_MESSAGE_CREATE" -> handleGroupMessage(d);
            default -> log.debug("[QQ] 事件 {}: {}", t, d);
        }
    }

    // ==================== 消息处理 ====================

    private void handleC2CMessage(JSONObject data) {
        String content = data.getStr("content", "").trim();
        JSONObject author = data.getJSONObject("author");
        String userOpenId = author != null ? author.getStr("user_openid", "") : "";
        String msgId = data.getStr("id", "");

        log.info("[QQ] 私聊: user={}, msg={}", userOpenId, content);
        if (content.isEmpty() || userOpenId.isEmpty()) return;

        messageExecutor.submit(() -> {
            try {
                GatewayResponse resp = agentGateway.chatSync(GatewayRequest.builder()
                        .message(content)
                        .chatId("qq_c2c_" + userOpenId)
                        .mode("normal")
                        .source("qq")
                        .build());
                if (resp.isSuccess() && resp.getContent() != null) {
                    replyC2C(userOpenId, resp.getContent(), msgId);
                }
            } catch (Exception e) {
                log.error("[QQ] 处理私聊异常", e);
            }
        });
    }

    private void handleGroupMessage(JSONObject data) {
        String content = data.getStr("content", "").trim();
        String groupOpenId = data.getStr("group_openid", "");
        String msgId = data.getStr("id", "");

        log.info("[QQ] 群聊: group={}, msg={}", groupOpenId, content);
        if (content.isEmpty() || groupOpenId.isEmpty()) return;

        messageExecutor.submit(() -> {
            try {
                GatewayResponse resp = agentGateway.chatSync(GatewayRequest.builder()
                        .message(content)
                        .chatId("qq_group_" + groupOpenId)
                        .mode("normal")
                        .source("qq")
                        .build());
                if (resp.isSuccess() && resp.getContent() != null) {
                    replyGroup(groupOpenId, resp.getContent(), msgId);
                }
            } catch (Exception e) {
                log.error("[QQ] 处理群聊异常", e);
            }
        });
    }

    // ==================== 消息回复 ====================

    private void replyC2C(String userOpenId, String content, String msgId) {
        ensureAccessToken();
        JSONObject body = JSONUtil.createObj()
                .set("content", content)
                .set("msg_type", 0)
                .set("msg_id", msgId)
                .set("msg_seq", msgSeqCounter.getAndIncrement());
        try {
            String resp = HttpRequest.post(QQ_API_BASE + "/v2/users/" + userOpenId + "/messages")
                    .header("Authorization", authHeader())
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(30000)
                    .execute()
                    .body();
            log.info("[QQ] 私聊回复: {}", resp);
        } catch (Exception e) {
            log.error("[QQ] 私聊回复失败", e);
        }
    }

    private void replyGroup(String groupOpenId, String content, String msgId) {
        ensureAccessToken();
        JSONObject body = JSONUtil.createObj()
                .set("content", content)
                .set("msg_type", 0)
                .set("msg_id", msgId)
                .set("msg_seq", msgSeqCounter.getAndIncrement());
        try {
            String resp = HttpRequest.post(QQ_API_BASE + "/v2/groups/" + groupOpenId + "/messages")
                    .header("Authorization", authHeader())
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(30000)
                    .execute()
                    .body();
            log.info("[QQ] 群聊回复: {}", resp);
        } catch (Exception e) {
            log.error("[QQ] 群聊回复失败", e);
        }
    }

    // ==================== MessageChannel 接口 ====================

    @Override
    public String getChannelName() { return "qq"; }

    @Override
    public void sendMessage(String chatId, String content) {
        if (chatId.startsWith("group_")) {
            replyGroup(chatId.substring(6), content, "");
        } else {
            replyC2C(chatId, content, "");
        }
    }

    @Override
    public boolean verifyCallback(String payload, String signature) { return true; }

    @Override
    public String extractMessage(String payload) {
        try {
            JSONObject d = JSONUtil.parseObj(payload).getJSONObject("d");
            return d != null ? d.getStr("content", "").trim() : null;
        } catch (Exception e) { return null; }
    }

    @Override
    public String extractChatId(String payload) {
        try {
            JSONObject d = JSONUtil.parseObj(payload).getJSONObject("d");
            if (d == null) return null;
            String gid = d.getStr("group_openid");
            if (gid != null && !gid.isBlank()) return "group_" + gid;
            JSONObject author = d.getJSONObject("author");
            return author != null ? author.getStr("user_openid") : null;
        } catch (Exception e) { return null; }
    }
}
