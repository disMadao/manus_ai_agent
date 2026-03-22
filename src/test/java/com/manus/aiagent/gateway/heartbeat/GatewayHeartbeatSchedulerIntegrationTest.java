package com.manus.aiagent.gateway.heartbeat;

import com.manus.aiagent.gateway.activitywatch.ActivityWatchProperties;
import com.manus.aiagent.gateway.activitywatch.ActivityWatchSync;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端：由 {@link GatewayHeartbeatScheduler#tickActivityWatch()}（与真实心跳相同入口）触发
 * {@link ActivityWatchSync}，最终写入 {@code workspace/memory/activity/{日期}-summary.md}。
 * <p>
 * 使用本机 {@link HttpServer} 模拟 ActivityWatch REST，无需安装 AW，CI 可跑。
 */
@SpringBootTest(classes = {
        ActivityWatchSync.class,
        GatewayHeartbeatScheduler.class
})
@EnableConfigurationProperties(ActivityWatchProperties.class)
class GatewayHeartbeatSchedulerIntegrationTest {

    /** 启动早于 Spring 的 {@link DynamicPropertySource}，保证能绑定 base-url */
    private static final HttpServer MOCK_AW_SERVER;
    private static final int MOCK_AW_PORT;

    static {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            MOCK_AW_PORT = server.getAddress().getPort();
            server.createContext("/", exchange -> {
                try {
                    if (!"GET".equals(exchange.getRequestMethod())) {
                        exchange.sendResponseHeaders(405, -1);
                        return;
                    }
                    String path = exchange.getRequestURI().getPath();
                    if ("/api/0/buckets/".equals(path)) {
                        String body = "{\"aw-watcher-window_host\":{}}";
                        writeJson(exchange, 200, body);
                    } else if (path.contains("/events")) {
                        String body = "[{\"duration\":120.5,\"data\":{\"app\":\"HeartbeatTestApp\",\"title\":\"mock\"}}]";
                        writeJson(exchange, 200, body);
                    } else {
                        exchange.sendResponseHeaders(404, -1);
                    }
                } finally {
                    exchange.close();
                }
            });
            server.start();
            MOCK_AW_SERVER = server;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @AfterAll
    static void stopMockAw() {
        if (MOCK_AW_SERVER != null) {
            MOCK_AW_SERVER.stop(0);
        }
    }

    @DynamicPropertySource
    static void awProps(DynamicPropertyRegistry r) {
        r.add("activitywatch.enabled", () -> "true");
        r.add("activitywatch.base-url", () -> "http://127.0.0.1:" + MOCK_AW_PORT);
        r.add("activitywatch.events-limit", () -> "100");
        r.add("activitywatch.heartbeat-interval-ms", () -> "1200000");
    }

    @Autowired
    GatewayHeartbeatScheduler gatewayHeartbeatScheduler;

    private String previousUserDir;

    @BeforeEach
    void setUserDirToTemp(@TempDir Path tempDir) {
        previousUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
    }

    @AfterEach
    void restoreUserDir() {
        if (previousUserDir != null) {
            System.setProperty("user.dir", previousUserDir);
        }
    }

    @Test
    void tickActivityWatch_writesActivitySummaryViaHeartbeat() throws Exception {
        gatewayHeartbeatScheduler.tickActivityWatch();

        Path summary = Path.of(System.getProperty("user.dir"), "workspace", "memory", "activity",
                LocalDate.now() + "-summary.md");
        assertThat(summary)
                .as("必须由心跳入口 tickActivityWatch → sync 写入，与真实定时任务一致")
                .exists();
        assertThat(Files.readString(summary, StandardCharsets.UTF_8))
                .contains("HeartbeatTestApp");
    }
}
