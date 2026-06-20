package dev.gate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Database;
import dev.gate.core.Logger;
import dev.gate.mapping.PostMapping;

// 遅延管理（POST のみ。GET は /events に埋め込み）
@GateController
public class DelayController {

    private static final Logger logger = new Logger(DelayController.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ── POST /events/delays/{projectId} ──────────────────────────────────────
    // mod/admin 認証（CfAccessAuth.ATTR_VERIFIED_EMAIL が必須）
    @PostMapping("/events/delays/{projectId}")
    public void updateDelay(Context ctx) {
        String projectIdStr = ctx.pathParam("projectId");
        if (projectIdStr == null || projectIdStr.isBlank()) {
            ctx.status(400).json(Map.of("error", "Invalid project ID"));
            return;
        }
        int projectId;
        try {
            projectId = Integer.parseInt(projectIdStr);
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "project ID must be a number"));
            return;
        }

        // mod/admin 認証チェック（CfAccessAuth が opportunistic 抽出した email）
        String updatedBy = ctx.getAttribute(CfAccessAuth.ATTR_VERIFIED_EMAIL);
        if (updatedBy == null || updatedBy.isBlank()) {
            ctx.status(401).json(Map.of("error", "Authentication required"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAs(Map.class);
            if (body == null) {
                ctx.status(400).json(Map.of("error", "Request body required"));
                return;
            }

            // delay_minutes: null or 0–999
            Integer delayMinutes = null;
            if (body.containsKey("delay_minutes") && body.get("delay_minutes") != null) {
                try {
                    int dm = Integer.parseInt(body.get("delay_minutes").toString());
                    if (dm < 0 || dm > 999) {
                        ctx.status(400).json(Map.of("error", "delay_minutes must be null or 0-999"));
                        return;
                    }
                    delayMinutes = dm;
                } catch (NumberFormatException e) {
                    ctx.status(400).json(Map.of("error", "delay_minutes must be a number or null"));
                    return;
                }
            }

            // note: 自由テキスト (null可)
            String note = null;
            if (body.containsKey("note") && body.get("note") != null) {
                note = body.get("note").toString();
                if (note.length() > 255) note = note.substring(0, 255);
            }

            String now = LocalDateTime.now(ZoneId.of("Asia/Tokyo")).format(FMT);

            try (Connection conn = Database.getConnection()) {
                // projectId 存在チェック
                try (PreparedStatement chk = conn.prepareStatement(
                        "SELECT 1 FROM projects WHERE id = ?")) {
                    chk.setInt(1, projectId);
                    try (ResultSet r = chk.executeQuery()) {
                        if (!r.next()) {
                            ctx.status(404).json(Map.of("error", "企画が見つかりません"));
                            return;
                        }
                    }
                }

                // UPSERT
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO project_delays " +
                        "  (project_id, delay_minutes, note, updated_at, updated_by) " +
                        "VALUES (?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE " +
                        "  delay_minutes = VALUES(delay_minutes), " +
                        "  note          = VALUES(note), " +
                        "  updated_at    = VALUES(updated_at), " +
                        "  updated_by    = VALUES(updated_by)")) {
                    ps.setInt(1, projectId);
                    if (delayMinutes != null) ps.setInt(2, delayMinutes);
                    else                      ps.setNull(2, java.sql.Types.SMALLINT);
                    ps.setString(3, note);
                    ps.setString(4, now);
                    ps.setString(5, updatedBy);
                    ps.executeUpdate();
                }
            }

            // レスポンスを先に返してから非同期伝播
            ctx.json(Map.of("ok", true, "project_id", projectId));

            final int finalProjectId     = projectId;
            final Integer finalDelay     = delayMinutes;
            final String finalNote       = note;
            final String finalUpdatedBy  = updatedBy;

            Main.bg.execute(() -> {
                // /events キャッシュ再構築
                DataController.refreshEventsAsync();
                // CF purge
                CfPurge.purgeUrlsAsync("/events");
                Main.bg.schedule(() -> CfPurge.purgeUrlsAsync("/events"), 10, TimeUnit.SECONDS);
                // d. 監査ログ & Discord
                try {
                    String detail = "delay_minutes=" + finalDelay + ", note=" + finalNote;
                    AuditLog.write(finalUpdatedBy, "UPDATE_DELAY", String.valueOf(finalProjectId), detail, "ok", null);
                    DiscordWebhook.sendAdminOp(finalUpdatedBy, "UPDATE_DELAY", String.valueOf(finalProjectId), detail);
                } catch (Exception e) {
                    logger.warn("delay audit/discord failed: {}", e.getMessage());
                }
            });

        } catch (dev.gate.core.ClientErrorException ce) {
            throw ce;
        } catch (Exception e) {
            logger.error("updateDelay error", e);
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable", "detail", "DB error"));
        }
    }
}
