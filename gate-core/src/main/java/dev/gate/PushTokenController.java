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

    private static final ConcurrentLinkedQueue<String> pendingTokens = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean flushing = new AtomicBoolean(false);

    @PostMapping("/push-token")
    public void register(Context ctx) {
        if (!APP_CHECK.verify(ctx)) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }

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

        pendingTokens.add(token);
        ctx.json(Map.of("ok", true));
    }

    public static void minuteTick() {
        flushPending();
    }

    public static void flushPending() {
        if (!flushing.compareAndSet(false, true)) return;
        try {
            List<String> tokens = new ArrayList<>();
            String t;
            while ((t = pendingTokens.poll()) != null) tokens.add(t);
            if (tokens.isEmpty()) return;

            try (Connection conn = Database.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "INSERT IGNORE INTO push_tokens (token, created_at) VALUES (?, ?)")) {
                String now = LocalDateTime.now(ZoneId.of("Asia/Tokyo")).format(FMT);
                for (String token : tokens) {
                    ps.setString(1, token);
                    ps.setString(2, now);
                    ps.addBatch();
                }
                ps.executeBatch();
                logger.info("Flushed {} push token(s) to DB", tokens.size());
            } catch (Exception e) {
                logger.error("Failed to flush push tokens, re-queuing {} tokens", tokens.size(), e);
                tokens.forEach(pendingTokens::add);
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
