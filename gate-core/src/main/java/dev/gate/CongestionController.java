package dev.gate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
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
    private final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final long CACHE_TTL_MS = 10_000L;
    private final AtomicReference<Object> cachedData = new AtomicReference<>();
    private final AtomicLong cacheExpiresAt = new AtomicLong(0);

    @GetMapping("/congestion")
    public void getCongestion(Context ctx) {
        Object cached = cachedData.get();
        if (cached != null && System.currentTimeMillis() < cacheExpiresAt.get()) {
            ctx.header("Cache-Control", "no-store");
            ctx.json(cached);
            return;
        }
        try (Connection conn = Database.getConnection();
             Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT l.id AS location_id, l.name, l.floor, l.svg_id, l.x, l.y, " +
                 "  COALESCE(cs.level, 0) AS level, " +
                 "  cs.updated_at, " +
                 "  (SELECT p.title FROM timetables t " +
                 "   JOIN projects p ON p.id = t.project_id " +
                 "   WHERE t.location_id = l.id AND t.event_date >= CURDATE() " +
                 "   ORDER BY t.event_date, t.start_time LIMIT 1) AS project " +
                 "FROM locations l " +
                 "LEFT JOIN congestion_status cs ON cs.location_id = l.id " +
                 "WHERE l.tracks_congestion = 1 " +
                 "ORDER BY l.floor, l.id")) {
            ArrayNode arr = mapper.createArrayNode();
            while (rs.next()) {
                ObjectNode n = arr.addObject();
                n.put("location_id", rs.getInt("location_id"));
                n.put("name",        rs.getString("name"));
                n.put("floor",       rs.getInt("floor"));
                String svgId = rs.getString("svg_id");
                if (svgId != null) n.put("svg_id", svgId);
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
            cachedData.set(arr);
            cacheExpiresAt.set(System.currentTimeMillis() + CACHE_TTL_MS);
            ctx.header("Cache-Control", "no-store");
            ctx.json(arr);
        } catch (Exception e) {
            logger.error("getCongestion error", e);
            if (cached != null) {
                logger.warn("DBエラーのため /congestion の古いキャッシュを返す");
                ctx.header("Cache-Control", "no-store");
                ctx.json(cached);
                return;
            }
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable",
                    "detail", "DB error"));
        }
    }
    // 管理者が混雑レベルを更新するエンドポイント。認証必須。
    @PostMapping("/congestion/{id}")
    public void updateCongestion(Context ctx) {
        String idStr = ctx.pathParam("id");
        try {
            int locationId = Integer.parseInt(idStr);
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
                        "SELECT 1 FROM locations WHERE id = ? AND tracks_congestion = 1")) {
                    chk.setInt(1, locationId);
                    try (java.sql.ResultSet r = chk.executeQuery()) {
                        if (!r.next()) {
                            ctx.status(404).json(Map.of("error", "場所が見つかりません"));
                            return;
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO congestion_status (location_id, level, updated_at, updated_by) VALUES (?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE level = VALUES(level), updated_at = VALUES(updated_at), updated_by = VALUES(updated_by)")) {
                    ps.setInt(1, locationId);
                    ps.setInt(2, level);
                    ps.setString(3, now);
                    ps.setString(4, updatedBy);
                    ps.executeUpdate();
                }
            }
            // 更新後はキャッシュを即時無効化して次のGETに即反映させる
            cacheExpiresAt.set(0);
            ctx.json(Map.of("ok", true, "location_id", locationId, "level", level));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Invalid id"));
        } catch (Exception e) {
            logger.error("updateCongestion error", e);
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable",
                    "detail", "DB error"));
        }
    }
}
