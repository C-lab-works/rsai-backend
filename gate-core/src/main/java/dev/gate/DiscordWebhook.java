package dev.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gate.core.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// エラー・管理操作をdiscordにwebhookで送信
public class DiscordWebhook {
    private static final Logger     logger      = new Logger(DiscordWebhook.class);
    private static final String     WEBHOOK     = System.getenv("DISCORD_WEBHOOK_URL");
    private static final String     INSTANCE    = Optional.ofNullable(System.getenv("K_REVISION"))
            .or(() -> Optional.ofNullable(System.getenv("K_SERVICE")))
            .orElse("local");
    private static final long       DEBOUNCE_MS = 5_000L;
    private static final HttpClient HTTP        = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER    = new ObjectMapper();
    private static final ConcurrentHashMap<String, AtomicLong> lastSent = new ConcurrentHashMap<>();

    private DiscordWebhook() {}

    public static void sendError(String method, String path, int status, String message) {
        if (WEBHOOK == null || WEBHOOK.isBlank()) return;

        String key = method + " " + path + " " + status;
        long now   = System.currentTimeMillis();
        AtomicLong ts = lastSent.computeIfAbsent(key, k -> new AtomicLong(0L));
        if (now - ts.getAndSet(now) < DEBOUNCE_MS) return;

        try {
            int color = status >= 500 ? 15158332 : 16776960;
            ObjectNode embed = MAPPER.createObjectNode();
            embed.put("title", status + "  " + (method != null ? method : "") + "  " + (path != null ? path : ""));
            embed.put("description", message != null ? message : "(no message)");
            embed.put("color", color);
            embed.putObject("footer").put("text", "instance: " + INSTANCE);
            embed.put("timestamp", Instant.now().toString());

            ObjectNode root = MAPPER.createObjectNode();
            root.put("content", "<@1086598323642830849>");
            root.putArray("embeds").add(embed);

            post(MAPPER.writeValueAsString(root));
        } catch (Exception e) {
            logger.warn("sendError failed: {}", e.getMessage());
        }
    }

    public static void sendAdminOp(String user, String action, String target, String detail) {
        if (WEBHOOK == null || WEBHOOK.isBlank()) return;
        try {
            ObjectNode embed = MAPPER.createObjectNode();
            embed.put("title", "[ADMIN] " + action + "  " + (target != null ? target : ""));
            if (detail != null && !detail.isBlank()) embed.put("description", detail);
            embed.put("color", 3447003);
            embed.putObject("footer").put("text", "by: " + (user != null ? user : "unknown") + "  |  instance: " + INSTANCE);
            embed.put("timestamp", Instant.now().toString());

            ObjectNode root = MAPPER.createObjectNode();
            root.putArray("embeds").add(embed);

            post(MAPPER.writeValueAsString(root));
        } catch (Exception e) {
            logger.warn("sendAdminOp failed: {}", e.getMessage());
        }
    }

    private static void post(String body) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(WEBHOOK))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .exceptionally(e -> { logger.warn("Discord webhook送信失敗: {}", e.getMessage()); return null; });
    }
}
