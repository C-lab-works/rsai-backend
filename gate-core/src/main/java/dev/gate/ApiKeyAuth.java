package dev.gate;

import dev.gate.core.Context;
import dev.gate.core.Handler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

public class ApiKeyAuth implements Handler {

    private static final String HEADER = "X-API-Key";
    private final String adminKey;
    private final String readOnlyKey;

    public ApiKeyAuth() {
        String key = System.getenv("API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("API_KEY environment variable is not set");
        }
        this.adminKey = key;
        this.readOnlyKey = System.getenv("READ_ONLY_KEY");
    }

    @Override
    public void handle(Context ctx) {
        if ("/health".equals(ctx.path())) return;
        // CORSプリフライトはAPI keyチェックをスキップ
        if ("OPTIONS".equals(ctx.method())) return;

        String provided = ctx.requestHeader(HEADER);
        if (provided == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized")).halt();
            return;
        }

        if (constantEquals(provided, adminKey)) return;

        if (readOnlyKey != null && constantEquals(provided, readOnlyKey)) {
            if (ctx.path().startsWith("/admin")) {
                ctx.status(403).json(Map.of("error", "Forbidden: admin access requires admin key")).halt();
                return;
            }
            if (!"GET".equalsIgnoreCase(ctx.method())) {
                ctx.status(403).json(Map.of("error", "Forbidden: read-only access")).halt();
            }
            return;
        }

        ctx.status(401).json(Map.of("error", "Unauthorized")).halt();
    }

    private static boolean constantEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8),
            b.getBytes(StandardCharsets.UTF_8));
    }
}
