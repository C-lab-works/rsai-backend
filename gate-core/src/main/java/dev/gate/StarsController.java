package dev.gate;

import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Database;
import dev.gate.core.Logger;
import dev.gate.mapping.DeleteMapping;
import dev.gate.mapping.PostMapping;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@GateController
public class StarsController {
    private static final Logger logger = new Logger(StarsController.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int STAR_ALERT_THRESHOLD = 500;

    private record StarOp(String type, int id, boolean add) {}

    private static final ConcurrentLinkedQueue<StarOp> pendingOps = new ConcurrentLinkedQueue<>();
    private static final AtomicLong starCountInWindow = new AtomicLong(0);
    private static final AtomicBoolean alertSentInWindow = new AtomicBoolean(false);

    @PostMapping("/stars")
    public void postStar(Context ctx) {
        StarRequest req;
        try {
            req = ctx.bodyAs(StarRequest.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON body"));
            return;
        }

        if (req == null || req.type == null || req.id == null) {
            ctx.status(400).json(Map.of("error", "Missing required fields (type, id)"));
            return;
        }

        String type = req.type.trim();
        if (!"project".equals(type) && !"foodtruck".equals(type)) {
            ctx.status(400).json(Map.of("error", "type must be either 'project' or 'foodtruck'"));
            return;
        }

        pendingOps.add(new StarOp(type, req.id, true));
        ctx.json(Map.of("ok", true));

        long count = starCountInWindow.incrementAndGet();
        if (count >= STAR_ALERT_THRESHOLD && alertSentInWindow.compareAndSet(false, true)) {
            DiscordWebhook.sendError("STARS", "/stars", 429,
                    "Anomaly: " + count + " POST /stars in current 1-minute window");
        }
    }

    @DeleteMapping("/stars")
    public void deleteStar(Context ctx) {
        StarRequest req;
        try {
            req = ctx.bodyAs(StarRequest.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON body"));
            return;
        }

        if (req == null || req.type == null || req.id == null) {
            ctx.status(400).json(Map.of("error", "Missing required fields (type, id)"));
            return;
        }

        String type = req.type.trim();
        if (!"project".equals(type) && !"foodtruck".equals(type)) {
            ctx.status(400).json(Map.of("error", "type must be either 'project' or 'foodtruck'"));
            return;
        }

        pendingOps.add(new StarOp(type, req.id, false));
        ctx.json(Map.of("ok", true));
    }

    public static void flushPending() {
        List<StarOp> ops = new ArrayList<>();
        StarOp op;
        while ((op = pendingOps.poll()) != null) ops.add(op);
        if (ops.isEmpty()) return;

        // net delta per (type:id)
        LinkedHashMap<String, Integer> deltas = new LinkedHashMap<>();
        for (StarOp o : ops) {
            String key = o.type() + ":" + o.id();
            deltas.merge(key, o.add() ? 1 : -1, Integer::sum);
        }

        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String now = LocalDateTime.now(ZoneId.of("Asia/Tokyo")).format(FMT);
                for (Map.Entry<String, Integer> entry : deltas.entrySet()) {
                    int delta = entry.getValue();
                    if (delta == 0) continue;

                    String[] parts = entry.getKey().split(":", 2);
                    String type = parts[0];
                    int targetId = Integer.parseInt(parts[1]);
                    boolean isProject = "project".equals(type);

                    if (delta > 0) {
                        String insertSql = isProject
                            ? "INSERT INTO project_stars (project_id, created_at) VALUES (?, ?)"
                            : "INSERT INTO foodtruck_stars (foodtruck_id, created_at) VALUES (?, ?)";
                        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                            for (int i = 0; i < delta; i++) {
                                ps.setInt(1, targetId);
                                ps.setString(2, now);
                                ps.addBatch();
                            }
                            ps.executeBatch();
                        }
                        String updateSql = isProject
                            ? "UPDATE projects SET bookmark_count = bookmark_count + ? WHERE id = ?"
                            : "UPDATE foodtruck SET bookmark_count = bookmark_count + ? WHERE id = ?";
                        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                            ps.setInt(1, delta);
                            ps.setInt(2, targetId);
                            ps.executeUpdate();
                        }
                    } else {
                        int absCount = -delta;
                        String deleteSql = isProject
                            ? "DELETE FROM project_stars WHERE project_id = ? LIMIT ?"
                            : "DELETE FROM foodtruck_stars WHERE foodtruck_id = ? LIMIT ?";
                        try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                            ps.setInt(1, targetId);
                            ps.setInt(2, absCount);
                            ps.executeUpdate();
                        }
                        String updateSql = isProject
                            ? "UPDATE projects SET bookmark_count = GREATEST(bookmark_count - ?, 0) WHERE id = ?"
                            : "UPDATE foodtruck SET bookmark_count = GREATEST(bookmark_count - ?, 0) WHERE id = ?";
                        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                            ps.setInt(1, absCount);
                            ps.setInt(2, targetId);
                            ps.executeUpdate();
                        }
                    }
                }
                conn.commit();
                logger.info("Flushed {} star ops, {} distinct targets", ops.size(), deltas.size());
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            logger.error("Failed to flush pending stars, re-queuing {} ops", ops.size(), e);
            for (Map.Entry<String, Integer> entry : deltas.entrySet()) {
                int delta = entry.getValue();
                if (delta == 0) continue;
                String[] parts = entry.getKey().split(":", 2);
                String type = parts[0];
                int targetId = Integer.parseInt(parts[1]);
                boolean add = delta > 0;
                int count = Math.abs(delta);
                for (int i = 0; i < count; i++) {
                    pendingOps.add(new StarOp(type, targetId, add));
                }
            }
        }
    }

    public static void minuteTick() {
        flushPending();
        starCountInWindow.set(0);
        alertSentInWindow.set(false);
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static class StarRequest {
        public String type;
        public Integer id;
    }
}
