package dev.gate;

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
    private static final boolean    IS_DEBUG    = "debug".equalsIgnoreCase(System.getenv("RUNMODE"));
    private static final String     WEBHOOK     = resolveWebhook();
    private static final String     INSTANCE    = Optional.ofNullable(System.getenv("K_REVISION"))
            .or(() -> Optional.ofNullable(System.getenv("K_SERVICE")))
            .orElse(IS_DEBUG ? "debug" : "local");
    private static final long       DEBOUNCE_MS = 5_000L;
    private static final HttpClient HTTP        = dev.gate.core.Http.CLIENT;
    private static final ConcurrentHashMap<String, AtomicLong> lastSent = new ConcurrentHashMap<>();

    private static String resolveWebhook() {
        if (IS_DEBUG) {
            String debugUrl = System.getenv("DISCORD_WEBHOOK_URL_DEBUG");
            if (debugUrl != null && !debugUrl.isBlank()) return debugUrl;
        }
        return System.getenv("DISCORD_WEBHOOK_URL");
    }

    private DiscordWebhook() {}

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }

    public static void sendError(String method, String path, int status, String message) {
        if (WEBHOOK == null || WEBHOOK.isBlank()) return;

        String key = method + " " + path + " " + status;
        long now   = System.currentTimeMillis();
        AtomicLong ts = lastSent.computeIfAbsent(key, k -> new AtomicLong(0L));
        if (now - ts.getAndSet(now) < DEBOUNCE_MS) return;

        int color = status >= 500 ? 15158332 : 16776960;
        String prefix = IS_DEBUG ? "[DEBUG] " : "";
        String body = """
                {
                  "content": "<@1086598323642830849>",
                  "embeds": [{
                    "title": "%s%d  %s  %s",
                    "description": "%s",
                    "color": %d,
                    "footer": { "text": "instance: %s" },
                    "timestamp": "%s"
                  }]
                }
                """.formatted(prefix, status, esc(method), esc(path), esc(message != null ? message : "(no message)"),
                              color, esc(INSTANCE), Instant.now());
        post(body);
    }

    public static void sendAdminOp(String user, String action, String target, String detail) {
        if (WEBHOOK == null || WEBHOOK.isBlank()) return;
        String desc = detail != null && !detail.isBlank() ? ", \"description\": \"" + esc(detail) + "\"" : "";
        String prefix = IS_DEBUG ? "[DEBUG]" : "";
        String body = """
                {
                  "embeds": [{
                    "title": "[ADMIN]%s %s  %s"%s,
                    "color": 3447003,
                    "footer": { "text": "by: %s  |  instance: %s" },
                    "timestamp": "%s"
                  }]
                }
                """.formatted(prefix, esc(action), esc(target), desc, esc(user != null ? user : "unknown"),
                              esc(INSTANCE), Instant.now());
        post(body);
    }

    private static void post(String body) {
        if (WEBHOOK == null || WEBHOOK.isBlank()) return;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(WEBHOOK))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .exceptionally(e -> { logger.warn("Discord webhook送信失敗: {}", e.getMessage()); return null; });
    }
}
