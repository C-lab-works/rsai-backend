package dev.gate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Database;
import dev.gate.core.Logger;
import dev.gate.mapping.PostMapping;

// ── Push 通知 ────────────────────────────────────────────────────────────────
@GateController
public class AdminPushController {

    private static final Logger       logger              = new Logger(AdminPushController.class);
    private static final ObjectMapper mapper              = dev.gate.core.Json.MAPPER;
    private static final HttpClient   http                = dev.gate.core.Http.CLIENT;

    private static final String EXPO_PUSH_URL = "https://exp.host/--/api/v2/push/send";
    private static final int PUSH_BATCH_SIZE = 100;

    // 緊急アナウンスをプッシュ通知で全端末に送信する
    @PostMapping("/admin/push/send")
    public void sendPushNotification(Context ctx) {
        String caller = ctx.getAttribute(CfAccessAuth.ATTR_VERIFIED_EMAIL);

        String title, bodyText, platform;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAs(Map.class);
            if (body == null) { ctx.status(400).json(Map.of("error", "リクエストボディが必要です")); return; }
            title    = body.get("title")    instanceof String s ? s.trim() : null;
            bodyText = body.get("body")     instanceof String s ? s.trim() : null;
            String p = body.get("platform") instanceof String s ? s.trim().toLowerCase() : null;
            platform = ("android".equals(p) || "ios".equals(p)) ? p : null;
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON body"));
            return;
        }
        if (title == null || title.isBlank() || bodyText == null || bodyText.isBlank()) {
            ctx.status(400).json(Map.of("error", "title と body は必須です"));
            return;
        }

        List<String> tokens = new ArrayList<>();
        try (Connection conn = Database.getConnection()) {
            final PreparedStatement ps;
            if (platform != null) {
                ps = conn.prepareStatement("SELECT token FROM push_tokens WHERE platform = ?");
                ps.setString(1, platform);
            } else {
                ps = conn.prepareStatement("SELECT token FROM push_tokens");
            }
            try (ps; ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tokens.add(rs.getString("token"));
            }
        } catch (Exception e) {
            logger.error("sendPushNotification: failed to load tokens", e);
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable"));
            return;
        }

        int sent = 0;
        int errors = 0;
        List<String> ticketErrors = new ArrayList<>();
        for (int start = 0; start < tokens.size(); start += PUSH_BATCH_SIZE) {
            List<String> batch = tokens.subList(start, Math.min(start + PUSH_BATCH_SIZE, tokens.size()));
            ArrayNode payload = mapper.createArrayNode();
            for (String token : batch) {
                ObjectNode msg = payload.addObject();
                msg.put("to", token);
                msg.put("title", title);
                msg.put("body", bodyText);
                msg.put("sound", "default");
                msg.put("channelId", "emergency");
                msg.put("priority", "high");
            }
            try {
                String bodyStr = mapper.writeValueAsString(payload);
                HttpRequest req = HttpRequest.newBuilder(URI.create(EXPO_PUSH_URL))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                    .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) {
                    ticketErrors.add("batch HTTP " + res.statusCode());
                    errors += batch.size();
                    continue;
                }
                Map<?,?> parsed = mapper.readValue(res.body(), Map.class);
                Object dataRaw = parsed.get("data");
                if (dataRaw instanceof List<?> data) {
                    for (Object ticketRaw : data) {
                        if (ticketRaw instanceof Map<?,?> ticket && "error".equals(ticket.get("status"))) {
                            errors++;
                            ticketErrors.add(ticket.get("message") + " / " + ticket.get("details"));
                        } else {
                            sent++;
                        }
                    }
                } else {
                    ticketErrors.add("unexpected Expo response");
                    errors += batch.size();
                }
            } catch (Exception e) {
                ticketErrors.add("exception: " + e.getMessage());
                errors += batch.size();
            }
        }

        String platformLabel = platform != null ? platform : "all";
        String detail = "sent=" + sent + " errors=" + errors + " platform=" + platformLabel + " body=" + bodyText
                + (ticketErrors.isEmpty() ? "" : " | err: " + String.join(", ", ticketErrors));
        logger.warn("push sent by={} platform={} total={} sent={} errors={} ticketErrors={}", caller, platformLabel, tokens.size(), sent, errors, ticketErrors);
        DiscordWebhook.sendAdminOp(caller, "PUSH_SENT", title, detail);
        ctx.json(Map.of("ok", true, "sent", sent, "errors", errors));
    }
}
