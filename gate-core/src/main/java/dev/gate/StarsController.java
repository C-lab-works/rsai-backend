package dev.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Database;
import dev.gate.core.Logger;
import dev.gate.mapping.PostMapping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * スター（お気に入り・ブックマーク）投稿用のエンドポイントコントローラー。
 * 重複した device_id からの登録に対しては冪等性を担保（200 OK で何もしない）します。
 */
@GateController
public class StarsController {
    private static final Logger logger = new Logger(StarsController.class);
    private static final ObjectMapper MAPPER = dev.gate.core.Json.MAPPER;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FirebaseAppCheckAuth appCheckAuth;
    private final byte[] adminKeyBytes;

    public StarsController() {
        this.appCheckAuth = new FirebaseAppCheckAuth();
        String key = System.getenv("API_KEY");
        this.adminKeyBytes = key != null ? key.getBytes(StandardCharsets.UTF_8) : new byte[0];
    }

    @PostMapping("/stars")
    public void postStar(Context ctx) {
        // 1. 認証チェック
        String apiKey = ctx.requestHeader("X-API-Key");
        boolean isAdmin = apiKey != null && MessageDigest.isEqual(apiKey.getBytes(StandardCharsets.UTF_8), adminKeyBytes);

        if (!isAdmin) {
            // readOnlyKey の場合は Firebase App Check 検証を強制する
            if (!appCheckAuth.verify(ctx)) {
                ctx.status(401).json(Map.of("error", "Firebase App Check verification failed")).halt();
                return;
            }
        }

        // 2. リクエストボディ解析とバリデーション
        Map<?, ?> body;
        try {
            body = ctx.bodyAs(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON body"));
            return;
        }

        if (body == null) {
            ctx.status(400).json(Map.of("error", "Request body required"));
            return;
        }

        Object typeObj = body.get("type");
        Object idObj = body.get("id");
        Object deviceIdObj = body.get("device_id");

        if (typeObj == null || idObj == null || deviceIdObj == null) {
            ctx.status(400).json(Map.of("error", "Missing required fields (type, id, device_id)"));
            return;
        }

        String type = typeObj.toString().trim();
        String deviceId = deviceIdObj.toString().trim();
        int targetId;
        try {
            targetId = Integer.parseInt(idObj.toString());
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "id must be a number"));
            return;
        }

        if (!"project".equals(type) && !"foodtruck".equals(type)) {
            ctx.status(400).json(Map.of("error", "type must be either 'project' or 'foodtruck'"));
            return;
        }

        if (deviceId.isEmpty()) {
            ctx.status(400).json(Map.of("error", "device_id cannot be empty"));
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

                // 重複登録のチェック (同一 device_id の重複制限)
                String checkStarSql = "project".equals(type)
                    ? "SELECT 1 FROM project_stars WHERE project_id = ? AND device_id = ?"
                    : "SELECT 1 FROM foodtruck_stars WHERE foodtruck_id = ? AND device_id = ?";
                
                boolean alreadyStarred = false;
                try (PreparedStatement ps = conn.prepareStatement(checkStarSql)) {
                    ps.setInt(1, targetId);
                    ps.setString(2, deviceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            alreadyStarred = true;
                        }
                    }
                }

                if (alreadyStarred) {
                    // すでに登録されている場合は何もしないで 200 OK を返却する (冪等性の担保)
                    ctx.json(Map.of("ok", true));
                    conn.rollback();
                    return;
                }

                // スター新規登録処理
                String insertStarSql = "project".equals(type)
                    ? "INSERT INTO project_stars (project_id, device_id, created_at) VALUES (?, ?, ?)"
                    : "INSERT INTO foodtruck_stars (foodtruck_id, device_id, created_at) VALUES (?, ?, ?)";
                
                String now = LocalDateTime.now(ZoneId.of("Asia/Tokyo")).format(FMT);
                try (PreparedStatement ps = conn.prepareStatement(insertStarSql)) {
                    ps.setInt(1, targetId);
                    ps.setString(2, deviceId);
                    ps.setString(3, now);
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
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            logger.error("Failed to post star", e);
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable", "detail", "DB error"));
        }
    }
}
