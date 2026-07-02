package dev.gate;

import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Database;
import dev.gate.mapping.GetMapping;

// ── 統計 ────────────────────────────────────────────────────────────────
@GateController
public class AdminStatsController {

    private static final ObjectMapper mapper              = dev.gate.core.Json.MAPPER;

    private static final int TOP_ENDPOINTS_COUNT = 10;

    // 管理者パネルのstatsページでリクエスト統計を取得するエンドポイント
    @GetMapping("/admin/stats")
    public void stats(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        RequestMetrics m = RequestMetrics.get();
        long   total    = m.getTotalRequests();
        long   errors   = m.getErrorCount();
        double errRate  = total == 0 ? 0.0 : Math.round((errors * 100.0 / total) * 100.0) / 100.0;
        long[] perc     = m.getPercentiles();

        ObjectNode root = mapper.createObjectNode();
        root.put("total_requests", total);
        root.put("error_count",    errors);
        root.put("error_rate",     errRate);
        root.put("p50_ms",         perc[0]);
        root.put("p95_ms",         perc[1]);
        int instanceCount = 0;
        if (FirestoreRest.get().isAvailable()) {
            try { instanceCount = (int) AdminInstancesController.deriveInstanceViews(Instant.now()).stream()
                .filter(v -> !"stopped".equals(v.status())).count();
            } catch (Exception ignored) {}
        }
        root.put("instances",      instanceCount);
        root.put("max_instances",  30);

        try {
            Map<String, Object> uptimeDoc = FirestoreRest.get().get("broadcast/uptime");
            if (uptimeDoc != null && uptimeDoc.get("serviceStartedAt") instanceof String s) {
                root.put("service_started_at", s);
                if (uptimeDoc.get("stoppedAt") == null) {
                    root.put("service_uptime_sec",
                        java.time.Instant.now().getEpochSecond() - java.time.Instant.parse(s).getEpochSecond());
                }
            }
        } catch (Exception ignored) {}

        ArrayNode chart = root.putArray("chart");
        for (long v : m.getHourlyCounts()) chart.add(v);

        ArrayNode endpoints = root.putArray("endpoints");
        for (var e : m.getTopEndpoints(TOP_ENDPOINTS_COUNT)) {
            String[] parts = e.getKey().split(" ", 2);
            String path = parts.length > 1 ? parts[1] : "";
            if (path.startsWith("/admin")) continue;
            addEndpoint(endpoints, parts[0], path, e.getValue());
        }

        ArrayNode system = root.putArray("system");

        // Database — 接続取得の成否で疎通確認（HikariがgetConnection()時にisValid検証済みのためSELECT 1は不要）
        String dbStatus = "ok", dbValue = "Connected";
        try (Connection _ = Database.getConnection()) {
            // 取得成功 = 疎通OK。try-with-resources で確実にclose()する
        } catch (Exception e) {
            dbStatus = "err"; dbValue = "Unreachable";
        }
        addStatus(system, "Database", dbStatus, dbValue);

        // Firestore
        boolean fsOk = FirestoreRest.get().isAvailable();
        addStatus(system, "Firestore", fsOk ? "ok" : "warn", fsOk ? "Available" : "Unavailable");

        ctx.json(root);
    }

    @GetMapping("/admin/stats/daily")
    public void dailyStats(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        ZonedDateTime jstNow = ZonedDateTime.now(ZoneId.of("Asia/Tokyo"));
        String today     = jstNow.toLocalDate().toString();
        String yesterday = jstNow.toLocalDate().minusDays(1).toString();

        RequestMetrics m = RequestMetrics.get();
        List<Map.Entry<String, Long>> todayEps     = m.getEndpointsByDate(today);
        List<Map.Entry<String, Long>> yesterdayEps = m.getEndpointsByDate(yesterday);

        ObjectNode root = mapper.createObjectNode();
        root.put("today",     today);
        root.put("yesterday", yesterday);

        long todayTotal     = todayEps.stream().mapToLong(Map.Entry::getValue).sum();
        long yesterdayTotal = yesterdayEps.stream().mapToLong(Map.Entry::getValue).sum();
        root.put("today_total",     todayTotal);
        root.put("yesterday_total", yesterdayTotal);
        root.put("diff",            todayTotal - yesterdayTotal);

        Map<String, long[]> merged = new LinkedHashMap<>();
        for (var e : todayEps)     merged.computeIfAbsent(e.getKey(), k -> new long[2])[0] = e.getValue();
        for (var e : yesterdayEps) merged.computeIfAbsent(e.getKey(), k -> new long[2])[1] = e.getValue();

        ArrayNode eps = root.putArray("endpoints");
        for (var entry : merged.entrySet()) {
            String[] parts = entry.getKey().split(" ", 2);
            ObjectNode n = eps.addObject();
            n.put("method",    parts.length > 0 ? parts[0] : "");
            n.put("path",      parts.length > 1 ? parts[1] : "");
            n.put("today",     entry.getValue()[0]);
            n.put("yesterday", entry.getValue()[1]);
            n.put("diff",      entry.getValue()[0] - entry.getValue()[1]);
        }
        ctx.json(root);
    }

    private void addEndpoint(ArrayNode arr, String method, String path, long count) {
        ObjectNode n = arr.addObject();
        n.put("method", method); n.put("path", path); n.put("count", count);
    }

    private void addStatus(ArrayNode arr, String name, String status, String value) {
        ObjectNode n = arr.addObject();
        n.put("name", name); n.put("status", status); n.put("value", value);
    }

}
