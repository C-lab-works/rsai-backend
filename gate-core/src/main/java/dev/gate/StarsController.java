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
import java.util.Map;

/**
 * スター（お気に入り・ブックマーク）の追加・削除エンドポイントコントローラー。
 * 認証は ApiKeyAuth ミドルウェアで処理済み。
 */
@GateController
public class StarsController {
    private static final Logger logger = new Logger(StarsController.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_BOOKMARK_COUNT = 50_000;
    private static final int STAR_ALERT_THRESHOLD = 500;
    private static final java.util.concurrent.atomic.AtomicLong starCountInWindow =
            new java.util.concurrent.atomic.AtomicLong(0);

    @PostMapping("/stars")
    public void postStar(Context ctx) {
        // 認証は ApiKeyAuth で処理済み
        // 1. リクエストボディ解析とバリデーション
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
        int targetId = req.id;

        if (!"project".equals(type) && !"foodtruck".equals(type)) {
            ctx.status(400).json(Map.of("error", "type must be either 'project' or 'foodtruck'"));
            return;
        }

        // 3. トランザクション処理
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 対象の存在確認と現在の bookmark_count を同時取得
                String checkTargetSql = "project".equals(type)
                    ? "SELECT bookmark_count FROM projects WHERE id = ?"
                    : "SELECT bookmark_count FROM foodtruck WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(checkTargetSql)) {
                    ps.setInt(1, targetId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            ctx.status(404).json(Map.of("error", type + " with id " + targetId + " not found"));
                            conn.rollback();
                            return;
                        }
                        int currentCount = rs.getInt(1);
                        if (currentCount >= MAX_BOOKMARK_COUNT) {
                            ctx.status(429).json(Map.of("error", "Bookmark limit reached"));
                            conn.rollback();
                            return;
                        }
                    }
                }

                // スター新規登録処理
                String insertStarSql = "project".equals(type)
                    ? "INSERT INTO project_stars (project_id, created_at) VALUES (?, ?)"
                    : "INSERT INTO foodtruck_stars (foodtruck_id, created_at) VALUES (?, ?)";

                String now = LocalDateTime.now(ZoneId.of("Asia/Tokyo")).format(FMT);
                try (PreparedStatement ps = conn.prepareStatement(insertStarSql)) {
                    ps.setInt(1, targetId);
                    ps.setString(2, now);
                    ps.executeUpdate();
                }

                // 親テーブルのブックマーク数カウントアップ
                String updateCountSql = "project".equals(type)
                    ? "UPDATE projects SET bookmark_count = bookmark_count + 1 WHERE id = ?"
                    : "UPDATE foodtruck SET bookmark_count = bookmark_count + 1 WHERE id = ?";

                try (PreparedStatement ps = conn.prepareStatement(updateCountSql)) {
                    ps.setInt(1, targetId);
                    ps.executeUpdate();
                }

                conn.commit();
                ctx.json(Map.of("ok", true));
                long count = starCountInWindow.incrementAndGet();
                if (count == STAR_ALERT_THRESHOLD) {
                    DiscordWebhook.sendError("STARS", "/stars", 429,
                            "Anomaly: " + count + " POST /stars in current 1-minute window");
                }
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            logger.error("Failed to post star", e);
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable", "detail", "DB error"));
        }
    }

    @DeleteMapping("/stars")
    public void deleteStar(Context ctx) {
        // 認証は ApiKeyAuth で処理済み
        // 1. リクエストボディ解析とバリデーション
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
        int targetId = req.id;

        if (!"project".equals(type) && !"foodtruck".equals(type)) {
            ctx.status(400).json(Map.of("error", "type must be either 'project' or 'foodtruck'"));
            return;
        }

        // 3. トランザクション処理
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 対象の存在チェック
                String checkTargetSql = "project".equals(type)
                    ? "SELECT 1 FROM projects WHERE id = ?"
                    : "SELECT 1 FROM foodtruck WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(checkTargetSql)) {
                    ps.setInt(1, targetId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            ctx.status(404).json(Map.of("error", type + " with id " + targetId + " not found"));
                            conn.rollback();
                            return;
                        }
                    }
                }

                // スターの存在確認（冪等性）
                String countSql = "project".equals(type)
                    ? "SELECT COUNT(*) FROM project_stars WHERE project_id = ?"
                    : "SELECT COUNT(*) FROM foodtruck_stars WHERE foodtruck_id = ?";
                int count;
                try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                    ps.setInt(1, targetId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        count = rs.getInt(1);
                    }
                }

                if (count == 0) {
                    // すでに存在しない場合は冪等に 200 OK を返す
                    conn.rollback();
                    ctx.json(Map.of("ok", true));
                    return;
                }

                // スター削除処理
                String deleteSql = "project".equals(type)
                    ? "DELETE FROM project_stars WHERE project_id = ? LIMIT 1"
                    : "DELETE FROM foodtruck_stars WHERE foodtruck_id = ? LIMIT 1";
                try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                    ps.setInt(1, targetId);
                    ps.executeUpdate();
                }

                // 親テーブルのブックマーク数カウントダウン（0 未満にならないよう GREATEST を使用）
                String updateCountSql = "project".equals(type)
                    ? "UPDATE projects SET bookmark_count = GREATEST(bookmark_count - 1, 0) WHERE id = ?"
                    : "UPDATE foodtruck SET bookmark_count = GREATEST(bookmark_count - 1, 0) WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(updateCountSql)) {
                    ps.setInt(1, targetId);
                    ps.executeUpdate();
                }

                conn.commit();
                ctx.json(Map.of("ok", true));
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            logger.error("Failed to delete star", e);
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable", "detail", "DB error"));
        }
    }

    public static void resetStarCounter() {
        starCountInWindow.set(0);
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static class StarRequest {
        public String type;
        public Integer id;
    }
}
