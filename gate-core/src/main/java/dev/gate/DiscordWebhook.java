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

public class DiscordWebhook {
    private static final Logger logger     = new Logger(DiscordWebhook.class);
    private static final String WEBHOOK    = System.getenv("DISCORD_WEBHOOK_URL");
    private static final String INSTANCE   = Optional.ofNullable(System.getenv("HOSTNAME")).orElse("local");
    private static final long   DEBOUNCE_MS = 5_000L;
    private static final HttpClient HTTP   = HttpClient.newHttpClient();

    // key = "METHOD PATH STATUS"  value = last sent epoch ms
    private static final ConcurrentHashMap<String, AtomicLong> lastSent = new ConcurrentHashMap<>();

    private DiscordWebhook() {}

    public static void sendError(String method, String path, int status, String message) {
        if (WEBHOOK == null || WEBHOOK.isBlank()) return;

        String key = method + " " + path + " " + status;
        long now   = System.currentTimeMillis();
        AtomicLong ts = lastSent.computeIfAbsent(key, k -> new AtomicLong(0L));
        if (now - ts.get() < DEBOUNCE_MS) return;
        ts.set(now);

        int color = status >= 500 ? 15158332 : 16776960; // red : yellow
        String safeMsg = message != null
                ? message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
                : "(no message)";
        String body = """
                {
                  "embeds": [{
                    "title": "%d  %s  %s",
                    "description": "%s",
                    "color": %d,
                    "footer": { "text": "instance: %s" },
                    "timestamp": "%s"
                  }]
                }
                """.formatted(status, method, path, safeMsg, color, INSTANCE, Instant.now());

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(WEBHOOK))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .exceptionally(e -> { logger.warn("Discord webhook failed: {}", e.getMessage()); return null; });
    }
}
