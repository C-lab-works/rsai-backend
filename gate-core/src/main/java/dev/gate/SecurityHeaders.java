package dev.gate;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.gate.core.Context;
import dev.gate.core.Handler;
// セキュリティヘッダーを追加するミドルウェア
public final class SecurityHeaders implements Handler {

    private static final SecurityHeaders INSTANCE = new SecurityHeaders();
    private static final Map<String, String> HEADERS;
    static {
        Map<String, String> m = new LinkedHashMap<>(8);
        m.put("X-Frame-Options", "DENY");
        m.put("X-Content-Type-Options", "nosniff");
        m.put("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        m.put("Content-Security-Policy", "default-src 'none'");
        m.put("Referrer-Policy", "no-referrer");
        m.put("Permissions-Policy", "geolocation=(), camera=(), microphone=()");
        HEADERS = java.util.Collections.unmodifiableMap(m);
    }

    private SecurityHeaders() {}

    public static SecurityHeaders get() {
        return INSTANCE;
    }

    @Override
    public void handle(Context ctx) {
        ctx.addTrustedHeaders(HEADERS);
    }
}
