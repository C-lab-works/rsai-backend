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

    private static final FirebaseAppCheckAuth APP_CHECK = new FirebaseAppCheckAuth();

    private record StarOp(String type, int id, boolean add, String sub) {}

    private static final ConcurrentLinkedQueue<StarOp> pendingOps = new ConcurrentLinkedQueue<>();
    private static final AtomicLong starCountInWindow = new AtomicLong(0);
    private static final AtomicBoolean alertSentInWindow = new AtomicBoolean(false);
    private static final AtomicBoolean flushing = new AtomicBoolean(false);

    private static final AtomicBoolean STARS_ENABLED = new AtomicBoolean(true);
    private static final Set<String> BLOCKED_SUBS = ConcurrentHashMap.newKeySet();
    private static final int MAX_BLOCKED_SUBS = 100;

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
        String sub = APP_CHECK.verifyAndGetSubject(ctx);
        if (sub == null) {
            ctx.status(403).json(Map.of("error", "Forbidden"));
            return;
        }
        if (BLOCKED_SUBS.contains(sub)) {
            ctx.status(403).json(Map.of("error", "このアプリは受付停止されています"));
            return;
        }

        String requestId = ctx.requestHeader("X-Request-Id");
        if (!RequestIdMiddleware.markSeenOrReject(requestId)) {
            ctx.status(409).json(Map.of("error", "Duplicate request"));
            return;
        }

        pendingOps.add(new StarOp(type, req.id, true, sub));
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
        String sub = APP_CHECK.verifyAndGetSubject(ctx);
        if (sub == null) {
            ctx.status(403).json(Map.of("error", "Forbidden"));
            return;
        }
        if (BLOCKED_SUBS.contains(sub)) {
            ctx.status(403).json(Map.of("error", "このアプリは受付停止されています"));
            return;
        }

        String requestId = ctx.requestHeader("X-Request-Id");
        if (!RequestIdMiddleware.markSeenOrReject(requestId)) {
            ctx.status(409).json(Map.of("error", "Duplicate request"));
            return;
        }

        pendingOps.add(new StarOp(type, req.id, false, sub));
        ctx.json(Map.of("ok", true));
    }

    public static void flushPending() {
        if (!flushing.compareAndSet(false, true)) return;
        try {
            List<StarOp> ops = new ArrayList<>();
            StarOp op;
            while ((op = pendingOps.poll()) != null) ops.add(op);
            if (ops.isEmpty()) return;

            // net delta per (type:id:sub) — last-write-wins for same user+item
            LinkedHashMap<String, Integer> deltas = new LinkedHashMap<>();
            for (StarOp o : ops) {
                String key = o.type() + ":" + o.id() + ":" + o.sub();
                deltas.merge(key, o.add() ? 1 : -1, Integer::sum);
            }

            // pre-validate → flush を同一接続で実行（getConnection() の呼び出しを2→1に削減）
            try (Connection conn = Database.getConnection()) {
                // pre-validate: autoCommit=true のまま SELECT で存在しない target を drop
                // （単一の無効 ID による FK 違反でバッチ全体がロールバックされるのを防ぐ）
                deltas.entrySet().removeIf(entry -> {
                    String[] parts = entry.getKey().split(":", 3);
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

                if (deltas.isEmpty()) return;

                // flush: 同一接続でトランザクション化
                conn.setAutoCommit(false);
                try {
                    String now = LocalDateTime.now(ZoneId.of("Asia/Tokyo")).format(FMT);
                    for (Map.Entry<String, Integer> entry : deltas.entrySet()) {
                        int delta = entry.getValue();
                        if (delta == 0) continue;

                        String[] parts = entry.getKey().split(":", 3);
                        String type = parts[0];
                        int targetId = Integer.parseInt(parts[1]);
                        String sub = parts[2];
                        boolean isProject = "project".equals(type);

                        if (delta > 0) {
                            // UNIQUE(project_id/foodtruck_id, app_check_sub) で重複を防ぐ
                            String insertSql = isProject
                                ? "INSERT IGNORE INTO project_stars (project_id, app_check_sub, created_at) VALUES (?, ?, ?)"
                                : "INSERT IGNORE INTO foodtruck_stars (foodtruck_id, app_check_sub, created_at) VALUES (?, ?, ?)";
                            int inserted;
                            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                                ps.setInt(1, targetId);
                                ps.setString(2, sub);
                                ps.setString(3, now);
                                inserted = ps.executeUpdate();
                            }
                            // 実際に挿入された行数だけカウントを増やす（重複時は 0）
                            if (inserted > 0) {
                                String updateSql = isProject
                                    ? "UPDATE projects SET bookmark_count = bookmark_count + 1 WHERE id = ?"
                                    : "UPDATE foodtruck SET bookmark_count = bookmark_count + 1 WHERE id = ?";
                                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                                    ps.setInt(1, targetId);
                                    ps.executeUpdate();
                                }
                            }
                        } else {
                            String deleteSql = isProject
                                ? "DELETE FROM project_stars WHERE project_id = ? AND app_check_sub = ?"
                                : "DELETE FROM foodtruck_stars WHERE foodtruck_id = ? AND app_check_sub = ?";
                            int deleted;
                            try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                                ps.setInt(1, targetId);
                                ps.setString(2, sub);
                                deleted = ps.executeUpdate();
                            }
                            if (deleted > 0) {
                                String updateSql = isProject
                                    ? "UPDATE projects SET bookmark_count = GREATEST(bookmark_count - 1, 0) WHERE id = ?"
                                    : "UPDATE foodtruck SET bookmark_count = GREATEST(bookmark_count - 1, 0) WHERE id = ?";
                                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                                    ps.setInt(1, targetId);
                                    ps.executeUpdate();
                                }
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
            String[] parts = entry.getKey().split(":", 3);
            String type = parts[0];
            int targetId = Integer.parseInt(parts[1]);
            String sub = parts[2];
            pendingOps.add(new StarOp(type, targetId, delta > 0, sub));
        }
    }

    public static void minuteTick() {
        flushPending();
        starCountInWindow.set(0);
        alertSentInWindow.set(false);
    }

    public static boolean isEnabled() { return STARS_ENABLED.get(); }
    public static void setEnabled(boolean enabled) { STARS_ENABLED.set(enabled); }
    public static Set<String> getBlockedSubs() { return Collections.unmodifiableSet(BLOCKED_SUBS); }
    public static boolean blockSub(String sub) {
        if (BLOCKED_SUBS.size() >= MAX_BLOCKED_SUBS) return false;
        return BLOCKED_SUBS.add(sub);
    }
    public static boolean unblockSub(String sub) { return BLOCKED_SUBS.remove(sub); }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static class StarRequest {
        public String type;
        public Integer id;
    }
}
