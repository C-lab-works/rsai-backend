package dev.gate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Database;
import dev.gate.core.Logger;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PostMapping;

@GateController
public class CongestionController {

    private static final Logger logger = new Logger(CongestionController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 他エンドポイントと揃えて30秒。2vCPUで瞬間7000req/sスパイクを受けやすくする。
    private static final long CACHE_TTL_MS = 30_000L;
    // CDNもキャッシュしてオリジン負荷を桁で下げる。
    // 混雑レベルは最大30秒遅れて反映される（更新時はオリジン側 clearCache で次回取得を即トリガ）。
    private static final String CACHE_CONTROL = "public, max-age=30, s-maxage=30, stale-while-revalidate=60";
    private static final AtomicReference<byte[]> cachedData = new AtomicReference<>();
    private static final AtomicLong cacheExpiresAt = new AtomicLong(0);
    private static final AtomicBoolean refreshing = new AtomicBoolean(false);

    public static void clearCache() { cacheExpiresAt.set(0); }

    @GetMapping("/congestion")
    public void getCongestion(Context ctx) {
        byte[] cached = cachedData.get();
        if (cached != null) {
            ctx.header("Cache-Control", CACHE_CONTROL);
            ctx.jsonBytes(cached);
            if (System.currentTimeMillis() >= cacheExpiresAt.get()) {
                scheduleRefresh();
            }
            return;
        }
        // 初回のみ同期取得
        try {
            byte[] json = fetchCongestionFromDb();
            cachedData.set(json);
            cacheExpiresAt.set(System.currentTimeMillis() + CACHE_TTL_MS);
            ctx.header("Cache-Control", CACHE_CONTROL);
            ctx.jsonBytes(json);
        } catch (Exception e) {
            logger.error("getCongestion error", e);
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable", "detail", "DB error"));
        }
    }

    private void scheduleRefresh() {
        if (!refreshing.compareAndSet(false, true)) return;
        Thread.ofVirtual().start(() -> {
            try {
                byte[] json = fetchCongestionFromDb();
                cachedData.set(json);
                cacheExpiresAt.set(System.currentTimeMillis() + CACHE_TTL_MS);
            } catch (Exception e) {
                logger.warn("Background congestion cache refresh failed: " + e.getMessage());
            } finally {
                refreshing.set(false);
            }
        });
    }

    private byte[] fetchCongestionFromDb() throws Exception {
        try (Connection conn = Database.getConnection();
             Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT l.id AS location_id, l.location_code, l.name, l.floor, l.svg_id, l.x, l.y, " +
                 "  cs.level, " +
                 "  cs.updated_at, " +
                 "  (SELECT p.title FROM timetables t " +
                 "   JOIN projects p ON p.id = t.project_id " +
                 "   WHERE t.location_id = l.id AND t.event_date >= CURDATE() " +
                 "   ORDER BY t.event_date, t.start_time LIMIT 1) AS project " +
                 "FROM locations l " +
                 "INNER JOIN congestion_status cs ON cs.location_code = l.location_code " +
                 "ORDER BY l.floor, l.id")) {
            ArrayNode arr = MAPPER.createArrayNode();
            while (rs.next()) {
                ObjectNode n = arr.addObject();
                n.put("location_id",   rs.getInt("location_id"));
                n.put("location_code", rs.getString("location_code"));
                n.put("name",          rs.getString("name"));
                n.put("floor",         rs.getInt("floor"));
                int svgId = rs.getInt("svg_id");
                if (!rs.wasNull()) n.put("svg_id", svgId);
                double x = rs.getDouble("x");
                if (!rs.wasNull()) n.put("x", x);
                double y = rs.getDouble("y");
                if (!rs.wasNull()) n.put("y", y);
                n.put("level", rs.getInt("level"));
                String updatedAt = rs.getString("updated_at");
                if (updatedAt != null) n.put("updated_at", updatedAt);
                String project = rs.getString("project");
                if (project != null) n.put("project", project);
            }
            return MAPPER.writeValueAsBytes(arr);
        }
    }
    // 管理者が混雑レベルを更新するエンドポイント。認証必須。
    @PostMapping("/congestion/{code}")
    public void updateCongestion(Context ctx) {
        String locationCode = ctx.pathParam("code");
        if (locationCode == null || locationCode.isBlank()) {
            ctx.status(400).json(Map.of("error", "Invalid location code"));
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAs(Map.class);
            if (body == null) { ctx.status(400).json(Map.of("error", "Request body required")); return; }
            Object levelObj = body.get("level");
            if (levelObj == null) { ctx.status(400).json(Map.of("error", "level required")); return; }
            int level;
            try {
                level = Integer.parseInt(levelObj.toString());
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "level must be a number")); return;
            }
            if (level < 0 || level > 2) { ctx.status(400).json(Map.of("error", "level must be 0-2")); return; }

            String updatedBy = ctx.getAttribute(CfAccessAuth.ATTR_VERIFIED_EMAIL);
            if (updatedBy == null || updatedBy.isBlank()) {
                ctx.status(401).json(Map.of("error", "Authentication required"));
                return;
            }
            String now = LocalDateTime.now().format(FMT);

            try (Connection conn = Database.getConnection()) {
                try (PreparedStatement chk = conn.prepareStatement(
                        "SELECT 1 FROM locations WHERE location_code = ?")) {
                    chk.setString(1, locationCode);
                    try (java.sql.ResultSet r = chk.executeQuery()) {
                        if (!r.next()) {
                            ctx.status(404).json(Map.of("error", "場所が見つかりません"));
                            return;
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO congestion_status (location_code, level, updated_at, updated_by) VALUES (?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE level = VALUES(level), updated_at = VALUES(updated_at), updated_by = VALUES(updated_by)")) {
                    ps.setString(1, locationCode);
                    ps.setInt(2, level);
                    ps.setString(3, now);
                    ps.setString(4, updatedBy);
                    ps.executeUpdate();
                }
            }
            cacheExpiresAt.set(0);
            ctx.json(Map.of("ok", true, "location_code", locationCode, "level", level));
        } catch (Exception e) {
            logger.error("updateCongestion error", e);
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable",
                    "detail", "DB error"));
        }
    }
}
