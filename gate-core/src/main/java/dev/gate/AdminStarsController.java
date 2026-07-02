package dev.gate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Database;
import dev.gate.core.Logger;
import dev.gate.mapping.DeleteMapping;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PostMapping;

// ── Stars 管理 ────────────────────────────────────────────────────────────────
@GateController
public class AdminStarsController {

    private static final Logger       logger              = new Logger(AdminStarsController.class);
    private static final ObjectMapper mapper              = dev.gate.core.Json.MAPPER;

    @GetMapping("/admin/stars/status")
    public void getStarsStatus(Context ctx) {
        ObjectNode res = mapper.createObjectNode();
        res.put("enabled", StarsController.isEnabled());
        ArrayNode blocked = res.putArray("blocked_subs");
        StarsController.getBlockedSubs().forEach(blocked::add);
        ctx.json(res);
    }

    @PostMapping("/admin/stars/enabled")
    public void setStarsEnabled(Context ctx) {
        boolean enabled;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(ctx.body(), Map.class);
            Object val = body.get("enabled");
            if (!(val instanceof Boolean)) {
                ctx.status(400).json(Map.of("error", "enabled フィールドは boolean 必須"));
                return;
            }
            enabled = (Boolean) val;
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON body"));
            return;
        }
        StarsController.setEnabled(enabled);
        String caller = ctx.getAttribute(CfAccessAuth.ATTR_VERIFIED_EMAIL);
        logger.info("stars {} by={}", enabled ? "enabled" : "disabled", caller);
        InstanceManager.get().broadcastStarsEnabled(enabled);
        ctx.json(Map.of("ok", true, "enabled", enabled));
    }

    @PostMapping("/admin/stars/block")
    public void blockSub(Context ctx) {
        String sub;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(ctx.body(), Map.class);
            Object val = body.get("sub");
            if (!(val instanceof String)) {
                ctx.status(400).json(Map.of("error", "sub フィールドは string 必須"));
                return;
            }
            sub = ((String) val).trim();
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON body"));
            return;
        }
        if (sub.isEmpty() || sub.length() > 200 || sub.contains(" ")) {
            ctx.status(400).json(Map.of("error", "sub は空白なし・200文字以内の文字列必須"));
            return;
        }
        boolean added = StarsController.blockSub(sub);
        if (!added) {
            ctx.status(409).json(Map.of("error", "既にブロック済みまたは上限(100件)に達しています"));
            return;
        }
        String caller = ctx.getAttribute(CfAccessAuth.ATTR_VERIFIED_EMAIL);
        logger.info("sub blocked sub={} by={}", sub.substring(0, Math.min(8, sub.length())), caller);
        ctx.json(Map.of("ok", true));
    }

    @DeleteMapping("/admin/stars/block")
    public void unblockSub(Context ctx) {
        String sub;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(ctx.body(), Map.class);
            Object val = body.get("sub");
            if (!(val instanceof String)) {
                ctx.status(400).json(Map.of("error", "sub フィールドは string 必須"));
                return;
            }
            sub = ((String) val).trim();
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON body"));
            return;
        }
        if (sub.isEmpty() || sub.length() > 200 || sub.contains(" ")) {
            ctx.status(400).json(Map.of("error", "sub は空白なし・200文字以内の文字列必須"));
            return;
        }
        boolean removed = StarsController.unblockSub(sub);
        if (!removed) {
            ctx.status(404).json(Map.of("error", "指定された sub はブロックリストに存在しません"));
            return;
        }
        String caller = ctx.getAttribute(CfAccessAuth.ATTR_VERIFIED_EMAIL);
        logger.info("sub unblocked sub={} by={}", sub.substring(0, Math.min(8, sub.length())), caller);
        ctx.json(Map.of("ok", true));
    }

    @GetMapping("/admin/stars/ranking")
    public void getStarsRanking(Context ctx) {
        String view = Objects.requireNonNullElse(ctx.query("view"), "all");
        ctx.header("Cache-Control", "no-store");

        String sql = buildRankingSql(view);
        if (sql == null) {
            ctx.status(400).json(Map.of("error", "invalid view"));
            return;
        }

        List<Map<String, Object>> items = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            int rank = 1;
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rank", rank++);
                row.put("id", rs.getInt("id"));
                row.put("name", rs.getString("name"));
                row.put("organizer", rs.getString("organizer"));
                row.put("type", rs.getString("type"));
                row.put("count", rs.getInt("star_count"));
                items.add(row);
            }
        } catch (Exception e) {
            logger.error("Failed to fetch star rankings", e);
            ctx.status(500).json(Map.of("error", "DB error"));
            return;
        }
        ctx.json(Map.of("items", items));
    }

    private static String buildRankingSql(String view) {
        return switch (view) {
            // projects は utf8mb4_0900_ai_ci、foodtruck は utf8mb4_unicode_ci のため、
            // テキスト列に明示 COLLATE を付けないと UNION で
            // "Illegal mix of collations" (ERROR 1271) となり 500 になる。
            case "all" -> """
                SELECT 'project' COLLATE utf8mb4_0900_ai_ci AS type, id,
                       title COLLATE utf8mb4_0900_ai_ci AS name,
                       organizer COLLATE utf8mb4_0900_ai_ci AS organizer,
                       bookmark_count AS star_count
                FROM projects
                UNION ALL
                SELECT 'foodtruck' COLLATE utf8mb4_0900_ai_ci AS type, id,
                       name COLLATE utf8mb4_0900_ai_ci AS name,
                       NULL AS organizer,
                       bookmark_count AS star_count
                FROM foodtruck
                ORDER BY star_count DESC LIMIT 30
                """;
            case "hs"        -> projectRankSql("organizer REGEXP '^[0-9]-[A-Z]$'");
            case "ms"        -> projectRankSql("organizer REGEXP '^[0-9]-[0-9]$'");
            case "hs1"       -> projectRankSql("organizer REGEXP '^1-[A-Z]$'");
            case "hs2"       -> projectRankSql("organizer REGEXP '^2-[A-Z]$'");
            case "hs3"       -> projectRankSql("organizer REGEXP '^3-[A-Z]$'");
            case "ms1"       -> projectRankSql("organizer REGEXP '^1-[0-9]$'");
            case "ms2"       -> projectRankSql("organizer REGEXP '^2-[0-9]$'");
            case "ms3"       -> projectRankSql("organizer REGEXP '^3-[0-9]$'");
            case "foodtruck" -> """
                SELECT 'foodtruck' AS type, id, name, NULL AS organizer, bookmark_count AS star_count
                FROM foodtruck
                ORDER BY star_count DESC LIMIT 30
                """;
            default -> null;
        };
    }

    private static String projectRankSql(String where) {
        return "SELECT 'project' AS type, id, title AS name, organizer, bookmark_count AS star_count " +
               "FROM projects WHERE " + where + " ORDER BY star_count DESC LIMIT 30";
    }
}
