package com.manus.aiagent.gateway.activitywatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ActivityWatch 专用：从本机 REST 拉取前台窗口类 bucket，按应用汇总停留时长，覆盖写入
 * {@code workspace/memory/activity/{yyyy-MM-dd}-summary.md}。
 * <p>
 * <strong>不包含</strong>定时/心跳逻辑；调度由 {@link com.manus.aiagent.gateway.heartbeat.GatewayHeartbeatScheduler} 等模块调用 {@link #syncTodayActivity()}。
 */
@Slf4j
@Service
public class ActivityWatchSync {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ActivityWatchProperties props;
    private final HttpClient httpClient;

    public ActivityWatchSync(ActivityWatchProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 同步「今天」的应用使用摘要（按当前拉取窗口内的事件聚合，整文件覆盖）。
     */
    public void syncTodayActivity() {
        if (!props.isEnabled()) {
            log.debug("ActivityWatch 同步已关闭（activitywatch.enabled=false）");
            return;
        }
        String base = props.getBaseUrl().replaceAll("/+$", "");
        try {
            HttpResponse<String> bucketsRes = httpClient.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/0/buckets/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (bucketsRes.statusCode() != 200) {
                log.warn("ActivityWatch GET /buckets/ 非 200: {} body={}", bucketsRes.statusCode(),
                        truncate(bucketsRes.body(), 300));
                return;
            }

            JsonNode bucketsRoot = MAPPER.readTree(bucketsRes.body());
            if (!bucketsRoot.isObject() || bucketsRoot.isEmpty()) {
                log.warn("ActivityWatch buckets 为空，跳过同步");
                return;
            }

            String bucketId = pickPreferredWindowBucketId(bucketsRoot);
            if (bucketId == null || bucketId.isBlank()) {
                log.warn("ActivityWatch 无可用 bucket");
                return;
            }

            int limit = Math.max(1, props.getEventsLimit());
            String path = "/api/0/buckets/" + URLEncoder.encode(bucketId, StandardCharsets.UTF_8)
                    + "/events?limit=" + limit;
            HttpResponse<String> eventsRes = httpClient.send(
                    HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (eventsRes.statusCode() != 200) {
                log.warn("ActivityWatch GET events 非 200: {} bucket={}", eventsRes.statusCode(), bucketId);
                return;
            }

            JsonNode eventsArray = MAPPER.readTree(eventsRes.body());
            if (!eventsArray.isArray()) {
                log.warn("ActivityWatch events 响应不是 JSON 数组");
                return;
            }

            Map<String, Double> secondsByApp = aggregateSecondsByApp(eventsArray);
            String projectRoot = System.getProperty("user.dir");
            Path activityDir = Path.of(projectRoot, "workspace", "memory", "activity");
            Files.createDirectories(activityDir);
            String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            Path out = activityDir.resolve(date + "-summary.md");

            String md = buildReadableMarkdown(bucketId, eventsArray.size(), secondsByApp);
            Files.writeString(out, md, StandardCharsets.UTF_8);
            // 同步写盘后再打日志，并带上实际字节数，避免 IDE/资源管理器晚刷新时误以为「先打日志后才有文件」
            long bytes = Files.size(out);
            log.info("ActivityWatch 行程已写入: {} ({} 字节, {} 个应用) — 若界面里晚几秒才出现文件，多为编辑器未刷新目录",
                    out.toAbsolutePath(), bytes, secondsByApp.size());
        } catch (Exception e) {
            log.warn("ActivityWatch 同步失败（本机未启动或服务不可用时常出现）: {}", e.getMessage());
            log.debug("ActivityWatch 同步异常", e);
        }
    }

    private static String pickPreferredWindowBucketId(JsonNode bucketsRoot) {
        List<String> ids = new ArrayList<>();
        bucketsRoot.fieldNames().forEachRemaining(ids::add);
        for (String id : ids) {
            if (id.startsWith("aw-watcher-window_") || id.contains("aw-watcher-window")) {
                return id;
            }
        }
        for (String id : ids) {
            if (id.toLowerCase(Locale.ROOT).contains("window")) {
                return id;
            }
        }
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static Map<String, Double> aggregateSecondsByApp(JsonNode eventsArray) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (JsonNode ev : eventsArray) {
            double dur = ev.path("duration").asDouble(0.0);
            if (dur <= 0) {
                continue;
            }
            JsonNode data = ev.path("data");
            String app = data.path("app").asText("").trim();
            if (app.isEmpty()) {
                app = "(未上报应用名)";
            }
            map.merge(app, dur, Double::sum);
        }
        return map;
    }

    private static String buildReadableMarkdown(String bucketId, int eventCount, Map<String, Double> secondsByApp) {
        double total = secondsByApp.values().stream().mapToDouble(Double::doubleValue).sum();
        String now = LocalDateTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        StringBuilder sb = new StringBuilder(1024);
        sb.append("# 今日应用使用时长（ActivityWatch）\n\n");
        sb.append("> 由网关<strong>心跳调度模块</strong>触发写入时，每次同步<strong>整文件覆盖</strong>。时长为事件中 `duration` 字段加总。\n\n");
        sb.append("| 项目 | 值 |\n");
        sb.append("|------|-----|\n");
        sb.append("| 生成时间（本机） | ").append(now).append(" |\n");
        sb.append("| 数据源 bucket | `").append(bucketId).append("` |\n");
        sb.append("| 拉取事件条数（上限见配置） | ").append(eventCount).append(" |\n");
        sb.append("| 有有效时长的应用数 | ").append(secondsByApp.size()).append(" |\n");
        sb.append("| **合计停留（秒）** | **").append(String.format(Locale.ROOT, "%.1f", total)).append("** |\n\n");

        if (secondsByApp.isEmpty()) {
            sb.append("（当前数据中无有效 `duration` + `data.app`，请确认前台窗口 watcher 已启用并已采集。）\n");
            return sb.toString();
        }

        sb.append("## 各应用看了多久（按时长从高到低）\n\n");
        sb.append("| 序号 | 应用 | 时长 | 约占比 |\n");
        sb.append("|:----:|------|------|--------|\n");

        List<Map.Entry<String, Double>> sorted = secondsByApp.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .collect(Collectors.toList());

        int rank = 1;
        for (Map.Entry<String, Double> e : sorted) {
            double sec = e.getValue();
            String pct = total > 0 ? String.format(Locale.ROOT, "%.0f%%", sec / total * 100.0) : "—";
            sb.append("| ").append(rank++).append(" | `").append(escapePipe(e.getKey())).append("` | **")
                    .append(formatDurationHuman(sec)).append("** | ").append(pct).append(" |\n");
        }

        sb.append("\n---\n\n");
        sb.append("*与 ActivityWatch 网页完全一致需使用 Query API / 时间窗过滤；本摘要为 REST 拉取后的聚合。*\n");
        return sb.toString();
    }

    private static String escapePipe(String s) {
        return s.replace("|", "\\|");
    }

    private static String formatDurationHuman(double seconds) {
        long s = Math.round(seconds);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) {
            return String.format(Locale.ROOT, "%d 小时 %d 分 %d 秒（约 %.0f 秒）", h, m, sec, seconds);
        }
        if (m > 0) {
            return String.format(Locale.ROOT, "%d 分 %d 秒（约 %.0f 秒）", m, sec, seconds);
        }
        return String.format(Locale.ROOT, "%d 秒", sec);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
