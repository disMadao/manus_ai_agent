package com.manus.aiagent.gateway.activitywatch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时打印 ActivityWatch 行程同步开关状态，避免「以为开了心跳其实没写文件」。
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class ActivityWatchStartupLogger implements ApplicationRunner {

    private final ActivityWatchProperties props;

    @Override
    public void run(ApplicationArguments args) {
        if (props.isEnabled()) {
            log.info("ActivityWatch 行程同步已启用：每 {} ms 写入 workspace/memory/activity/{{日期}}-summary.md（需本机 AW 监听 {}）",
                    props.getHeartbeatIntervalMs(), props.getBaseUrl());
        } else {
            log.info("ActivityWatch 行程同步未启用：请在 application.yml / application-local.yml 中设置 activitywatch.enabled=true，并确保本机已运行 ActivityWatch（默认 {}）",
                    props.getBaseUrl());
        }
    }
}
