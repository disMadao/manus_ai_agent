package com.manus.aiagent.gateway.activitywatch;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ActivityWatch 本机 REST 与定时同步配置。
 */
@ConfigurationProperties(prefix = "activitywatch")
public class ActivityWatchProperties {

    /**
     * 是否启用：为 true 时注册定时任务并拉取数据；默认 false 避免未装 AW 的环境报错。
     */
    private boolean enabled = false;

    /**
     * aw-server 基址（仅本机）。
     */
    private String baseUrl = "http://127.0.0.1:5600";

    /**
     * 单次拉取 events 条数上限。
     */
    private int eventsLimit = 8000;

    /**
     * 心跳间隔（毫秒），默认 20 分钟；与 application.yml 中 activitywatch.heartbeat-interval-ms 一致。
     */
    private long heartbeatIntervalMs = 20L * 60L * 1000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getEventsLimit() {
        return eventsLimit;
    }

    public void setEventsLimit(int eventsLimit) {
        this.eventsLimit = eventsLimit;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }
}
