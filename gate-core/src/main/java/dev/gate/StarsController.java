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
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final AtomicBoolean flushing = new AtomicBoolean(false);

    private static final AtomicBoolean STARS_ENABLED = new AtomicBoolean(true);
    private static final Set<String> BLOCKED_DEVICE_IDS = ConcurrentHashMap.newKeySet();
    private static final int MAX_BLOCKED_DEVICES = 100;

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

        if (!STARS_ENABLED.get()) {
            ctx.status(503).json(Map.of("error", "Stars受付を停止中です"));
            return;
        }
        String deviceId = ctx.requestHeader("X-Device-Id");
        if (deviceId != null && BLOCKED_DEVICE_IDS.contains(deviceId)) {
            ctx.status(403).json(Map.of("error", "このデバイスはブロックされています"));
            return;
        }
        if (!isAllowedClient(ctx)) {
            ctx.status(403).json(Map.of("error", "Forbidden"));
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

        if (!STARS_ENABLED.get()) {
            ctx.status(503).json(Map.of("error", "Stars受付を停止中です"));
            return;
        }
        String deviceId = ctx.requestHeader("X-Device-Id");
        if (deviceId != null && BLOCKED_DEVICE_IDS.contains(deviceId)) {
            ctx.status(403).json(Map.of("error", "このデバイスはブロックされています"));
            return;
        }
        if (!isAllowedClient(ctx)) {
            ctx.status(403).json(Map.of("error", "Forbidden"));
            return;
        }

        pendingOps.add(new StarOp(type, req.id, false));
        ctx.json(Map.of("ok", true));
    }

    public static void flushPending() {
        if (!flushing.compareAndSet(false, true)) return;
        try {
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

            // pre-validate: drop entries for non-existent targets before the main transaction
            // prevents a single invalid ID from causing FK violation and rolling back the entire flush
            try (Connection conn = Database.getConnection()) {
                deltas.entrySet().removeIf(entry -> {
                    String[] parts = entry.getKey().split(":", 2);
                    boolean isProject = "project".equals(parts[0]);
                    int id = Integer.parseInt(parts[1]);
                    String sql = isProject
                        ? "SELECT 1 FROM projects WHERE id = ?"
                        : "SELECT 1 FROM foodtruck WHERE id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setInt(1, id);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) return false;
                            logger.warn("Star target not found, dropping from flush: {}", entry.getKey());
                            return true;
                        }
                    } catch (Exception e) {
                        logger.warn("Pre-validation failed for {}, dropping", entry.getKey());
                        return true;
                    }
                });
            } catch (Exception e) {
                logger.error("Pre-validation DB connection failed, re-queuing {} ops", ops.size(), e);
                requeue(deltas);
                return;
            }

            if (deltas.isEmpty()) return;

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
                requeue(deltas);
            }
        } finally {
            flushing.set(false);
        }
    }

    private static void requeue(LinkedHashMap<String, Integer> deltas) {
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

    public static void minuteTick() {
        flushPending();
        starCountInWindow.set(0);
        alertSentInWindow.set(false);
    }

    public static boolean isEnabled() { return STARS_ENABLED.get(); }
    public static void setEnabled(boolean enabled) { STARS_ENABLED.set(enabled); }
    public static Set<String> getBlockedDevices() { return Collections.unmodifiableSet(BLOCKED_DEVICE_IDS); }
    public static boolean blockDevice(String deviceId) {
        if (BLOCKED_DEVICE_IDS.size() >= MAX_BLOCKED_DEVICES) return false;
        return BLOCKED_DEVICE_IDS.add(deviceId);
    }
    public static boolean unblockDevice(String deviceId) { return BLOCKED_DEVICE_IDS.remove(deviceId); }

    private static boolean isAllowedClient(Context ctx) {
        String ua = ctx.requestHeader("User-Agent");
        if (ua != null && !ua.isBlank()
                && !ua.startsWith("okhttp/4.")
                && !ua.startsWith("CFNetwork/")) {
            return false;
        }
        String appVersion = ctx.requestHeader("X-App-Version");
        return appVersion != null && !appVersion.isBlank();
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static class StarRequest {
        public String type;
        public Integer id;
    }
}
