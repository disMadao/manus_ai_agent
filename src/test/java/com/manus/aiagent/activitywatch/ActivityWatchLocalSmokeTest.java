package com.manus.aiagent.activitywatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 最小「跑通」示例：本机 ActivityWatch 已启动（默认 127.0.0.1:5600）时，
 * 拉取 /api/0/buckets/ 与首个 bucket 的少量 events，并生成友好的时间分配摘要，
 * 写入 {@code workspace/memory/activity/{yyyy-MM-dd}-summary-smoke.md}。
 * <p>
 * 未安装或未启动 AW 时，测试会 {@link Assumptions#abort(String)} 跳过，不导致 CI 失败。
 * <p>
 * 运行：{@code mvn test "-Dtest=com.manus.aiagent.activitywatch.ActivityWatchLocalSmokeTest"}
 */
@Tag("activitywatch")
public class ActivityWatchLocalSmokeTest {

    private static final String AW_BASE = "http://127.0.0.1:5600";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Test
    void pullBucketsAndWriteMinimalSummary() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        HttpResponse<String> bucketsRes;
        try {
            bucketsRes = client.send(
                    HttpRequest.newBuilder(URI.create(AW_BASE + "/api/0/buckets/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Assumptions.abort("本机未启动 ActivityWatch（需监听 127.0.0.1:5600）: " + e.getMessage());
            return;
        }

        Assumptions.assumeTrue(bucketsRes.statusCode() == 200,
                "GET /api/0/buckets/ 期望 200，实际 " + bucketsRes.statusCode() + " body=" + bucketsRes.body());

        String bucketsJson = bucketsRes.body();
        JsonNode root = MAPPER.readTree(bucketsJson);
        if (!root.isObject() || root.isEmpty()) {
            Assumptions.abort("buckets 为空，请先运行 ActivityWatch 并采集一段时间");
        }

        String firstBucketId = root.fieldNames().next();
        String eventsUrl = AW_BASE + "/api/0/buckets/" + firstBucketId + "/events?limit=50"; // 取更多事件便于聚合
        HttpResponse<String> eventsRes = client.send(
                HttpRequest.newBuilder(URI.create(eventsUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        Assumptions.assumeTrue(eventsRes.statusCode() == 200,
                "GET events 期望 200，实际 " + eventsRes.statusCode());

        // 解析 events 并生成友好摘要
        String summary = buildSummary(firstBucketId, eventsRes.body(), bucketsJson);

        String projectRoot = System.getProperty("user.dir");
        Path activityDir = Path.of(projectRoot, "workspace", "memory", "activity");
        Files.createDirectories(activityDir);
        String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        Path out = activityDir.resolve(date + "-summary-smoke.md");
        Files.writeString(out, summary, StandardCharsets.UTF_8);
    }

    private String buildSummary(String bucketId, String eventsJson, String bucketsJson) throws Exception {
        JsonNode eventsNode = MAPPER.readTree(eventsJson);
        if (!eventsNode.isArray()) {
            return "错误：events 响应不是数组格式";
        }

        // 按应用聚合时长和标题样例
        Map<String, AppSummary> appMap = new LinkedHashMap<>();
        for (JsonNode event : eventsNode) {
            double duration = event.path("duration").asDouble();
            if (duration <= 0.001) continue; // 忽略时长为 0 的瞬间切换

            JsonNode data = event.path("data");
            String app = data.path("app").asText();
            String title = data.path("title").asText();

            AppSummary summary = appMap.computeIfAbsent(app, k -> new AppSummary());
            summary.totalSeconds += duration;
            // 保留第一个非空标题作为示例，避免重复太多
            if (summary.exampleTitle == null && !title.isBlank()) {
                summary.exampleTitle = title;
            }
        }

        if (appMap.isEmpty()) {
            return """
                    # ActivityWatch 活动摘要
                                        
                    - 生成时间（本地）：%s
                    - 数据源：本机 ActivityWatch REST
                    - 监控窗口：`%s`
                                        
                    ⚠️ 最近事件中没有时长大于 0 的活动记录。请确保 ActivityWatch 正在正常运行并已采集一段时间。
                    """.formatted(
                    LocalDateTimeNow(),
                    bucketId
            );
        }

        // 按总时长降序排序
        // 按总时长降序排序
        List<Map.Entry<String, AppSummary>> sorted = appMap.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, AppSummary>>comparingDouble(e -> e.getValue().totalSeconds).reversed())
                .collect(Collectors.toList());

        // 构建表格
        // 构建表格
        StringBuilder table = new StringBuilder();
        table.append("| 应用 | 时长（秒） | 时长（分钟） | 时长（小时） | 活动窗口示例 |\n");
        table.append("|------|-----------|------------|------------|------------|\n");
        for (Map.Entry<String, AppSummary> entry : sorted) {
            String app = entry.getKey();
            AppSummary sum = entry.getValue();
            double minutes = sum.totalSeconds / 60.0;
            double hours = sum.totalSeconds / 3600.0;
            String example = sum.exampleTitle != null ? escapeMarkdown(sum.exampleTitle) : "—";
            table.append(String.format("| %s | %.1f | %.2f | %.2f | %s |\n",
                    escapeMarkdown(app), sum.totalSeconds, minutes, hours, example));
        }

        // 获取 bucket 元信息（最后更新时间）
        String bucketInfo = "";
        try {
            JsonNode buckets = MAPPER.readTree(bucketsJson);
            JsonNode bucketNode = buckets.path(bucketId);
            if (!bucketNode.isMissingNode()) {
                String lastUpdated = bucketNode.path("last_updated").asText();
                bucketInfo = "\n- 最后更新：`" + lastUpdated + "`";
            }
        } catch (Exception ignored) {}
        return """
                # ActivityWatch 活动摘要
                
                - 生成时间（本地）：%s
                - 数据源：本机 ActivityWatch REST
                - 监控窗口：`%s`%s
                
                ## 时间分配
                
                %s
                
                *注：仅统计时长大于 0 秒的事件（最近最多 50 条）。*
                """.formatted(
                LocalDateTimeNow(),
                bucketId,
                bucketInfo,
                table.toString()
        );
    }

    private static String LocalDateTimeNow() {
        return Instant.now().atZone(ZoneId.systemDefault()).format(DATE_FORMAT);
    }

    private static String escapeMarkdown(String s) {
        if (s == null) return "";
        // 简单转义表格中的竖线
        return s.replace("|", "\\|");
    }

    private static class AppSummary {
        double totalSeconds = 0;
        String exampleTitle = null;
    }
}
