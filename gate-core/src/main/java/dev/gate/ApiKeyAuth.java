package dev.gate;

import dev.gate.core.Context;
import dev.gate.core.Handler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
// APIキー認証のミドルウェア
public class ApiKeyAuth implements Handler {

    private static final String HEADER = "X-API-Key";
    private final byte[] adminKeyBytes;
    private final byte[] readOnlyKeyBytes;

    public ApiKeyAuth() {
        String key = System.getenv("API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("API_KEY environment variable is not set"); // APIキーが設定されてないとクラッシュ
        }
        this.adminKeyBytes = key.getBytes(StandardCharsets.UTF_8);
        String rok = System.getenv("READ_ONLY_KEY");
        this.readOnlyKeyBytes = rok != null ? rok.getBytes(StandardCharsets.UTF_8) : null;
    }

    // 認証ロジック
    @Override
    public void handle(Context ctx) {
        if ("/health".equals(ctx.path())) return;
        if ("OPTIONS".equals(ctx.method())) return;

        String provided = ctx.requestHeader(HEADER);
        if (provided == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized")).halt();
            return;
        }

        if (constantEquals(provided, adminKeyBytes)) return;

        if (readOnlyKeyBytes != null && constantEquals(provided, readOnlyKeyBytes)) {
            if (ctx.path().startsWith("/admin")) {
                ctx.status(403).json(Map.of("error", "Forbidden: admin access requires admin key")).halt();
                return;
            }
            if ("POST".equalsIgnoreCase(ctx.method()) && "/stars".equals(ctx.path())) {
                return;
            }
            if ("DELETE".equalsIgnoreCase(ctx.method()) && "/stars".equals(ctx.path())) {
                return;
            }
            if (!"GET".equalsIgnoreCase(ctx.method())) {
                ctx.status(403).json(Map.of("error", "Forbidden: read-only access")).halt();
            }
            return;
        }

        ctx.status(401).json(Map.of("error", "Unauthorized")).halt();
    }

    private static boolean constantEquals(String a, byte[] b) {
        if (a == null || b == null) return false;
        // 長さが違っても早期リターンせず isEqual に任せる（タイミングリーク防止）
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b);
    }
}
