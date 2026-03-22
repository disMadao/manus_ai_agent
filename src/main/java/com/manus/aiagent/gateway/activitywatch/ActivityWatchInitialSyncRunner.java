package com.manus.aiagent.gateway.activitywatch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启用 ActivityWatch 后，在应用启动完成时<strong>立即同步一次</strong>今日行程，
 * 避免误以为「要等 20 分钟心跳才有文件」。
 * <p>
 * 之后仍由 {@link com.manus.aiagent.gateway.heartbeat.GatewayHeartbeatScheduler} 按固定间隔执行。
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "activitywatch", name = "enabled", havingValue = "true")
public class ActivityWatchInitialSyncRunner implements ApplicationRunner {

    private final ActivityWatchSync activityWatchSync;

    @Override
    public void run(ApplicationArguments args) {
        log.info("ActivityWatch：启动后立即同步一次今日行程（不依赖等待心跳间隔）");
        activityWatchSync.syncTodayActivity();
    }
}
