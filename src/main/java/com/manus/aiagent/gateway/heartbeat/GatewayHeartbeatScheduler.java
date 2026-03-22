package com.manus.aiagent.gateway.heartbeat;

import com.manus.aiagent.gateway.activitywatch.ActivityWatchSync;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 网关心跳调度：仅负责<strong>定时触发</strong>，与具体业务解耦。
 * <p>
 * 当前注册：ActivityWatch 行程同步（间隔见 {@code activitywatch.heartbeat-interval-ms}）。
 * 后续其它周期任务可在此类中新增方法，或再拆子调度器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "activitywatch", name = "enabled", havingValue = "true")
public class GatewayHeartbeatScheduler {

    private final ActivityWatchSync activityWatchSync;

    /**
     * 定时同步本机 ActivityWatch → workspace 行程摘要。
     */
    @Scheduled(fixedRateString = "${activitywatch.heartbeat-interval-ms:1200000}")
    public void tickActivityWatch() {
        log.debug("心跳：触发 ActivityWatch 行程同步");
        activityWatchSync.syncTodayActivity();
    }
}
