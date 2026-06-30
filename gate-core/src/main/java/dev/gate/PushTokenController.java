package dev.gate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Database;
import dev.gate.core.Logger;
import dev.gate.mapping.PostMapping;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@GateController
public class PushTokenController {
    private static final Logger logger = new Logger(PushTokenController.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final FirebaseAppCheckAuth APP_CHECK = new FirebaseAppCheckAuth();

    private record PendingToken(String token, String platform) {}

    private static final ConcurrentLinkedQueue<PendingToken> pendingTokens = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean flushing = new AtomicBoolean(false);

    @PostMapping("/push-token")
    public void register(Context ctx) {
        PushTokenRequest req;
        try {
            req = ctx.bodyAs(PushTokenRequest.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON body"));
            return;
        }

        if (req == null || req.token == null || req.token.isBlank()) {
            ctx.status(400).json(Map.of("error", "token is required"));
            return;
        }

        String token = req.token.trim();
        if (!token.startsWith("ExponentPushToken[")) {
            ctx.status(400).json(Map.of("error", "Invalid push token format"));
            return;
        }

        String sub = APP_CHECK.verifyAndGetSubject(ctx);
        if (sub == null) {
            ctx.status(403).json(Map.of("error", "Forbidden"));
            return;
        }

        String platform = (req.platform != null && !req.platform.isBlank()) ? req.platform.trim().toLowerCase() : null;
        pendingTokens.add(new PendingToken(token, platform));
        ctx.json(Map.of("ok", true));
    }

    public static void minuteTick() {
        flushPending();
    }

    public static void flushPending() {
        if (!flushing.compareAndSet(false, true)) return;
        try {
            List<PendingToken> entries = new ArrayList<>();
            PendingToken e;
            while ((e = pendingTokens.poll()) != null) entries.add(e);
            if (entries.isEmpty()) return;

            try (Connection conn = Database.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO push_tokens (token, platform, created_at) VALUES (?, ?, ?)" +
                     " ON DUPLICATE KEY UPDATE platform = VALUES(platform)")) {
                String now = LocalDateTime.now(ZoneId.of("Asia/Tokyo")).format(FMT);
                for (PendingToken entry : entries) {
                    ps.setString(1, entry.token());
                    ps.setString(2, entry.platform());
                    ps.setString(3, now);
                    ps.addBatch();
                }
                ps.executeBatch();
                logger.info("Flushed {} push token(s) to DB", entries.size());
            } catch (Exception ex) {
                logger.error("Failed to flush push tokens, re-queuing {} tokens", entries.size(), ex);
                entries.forEach(pendingTokens::add);
            }
        } finally {
            flushing.set(false);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PushTokenRequest {
        public String token;
        public String platform; // "ios" or "android" (optional)
    }
}
