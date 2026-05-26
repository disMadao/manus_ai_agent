package com.manus.aiagent.gateway.heartbeat;

import com.manus.aiagent.gateway.activitywatch.ActivityWatchSync;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 验证「心跳调度 → ActivityWatch 同步」链路。
 * <p>
 * <strong>端到端（心跳入口 → 写 activity 文件）</strong>见
 * {@link GatewayHeartbeatSchedulerIntegrationTest}（模拟 AW REST，无需本机 5600）。
 */
class GatewayHeartbeatSchedulerTest {

    @Nested
    @DisplayName("单元：心跳方法委托给 ActivityWatchSync")
    @ExtendWith(MockitoExtension.class)
    class DelegationUnitTest {

        @Mock
        ActivityWatchSync activityWatchSync;

        @InjectMocks
        GatewayHeartbeatScheduler scheduler;

        @Test
        void tickActivityWatch_callsSyncTodayActivity() {
            scheduler.tickActivityWatch();
            verify(activityWatchSync, times(1)).syncTodayActivity();
        }
    }
}
