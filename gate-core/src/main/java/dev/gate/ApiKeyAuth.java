package dev.gate;

import dev.gate.core.Context;
import dev.gate.core.Handler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

public class ApiKeyAuth implements Handler {

    private static final String HEADER = "X-API-Key";
    private final byte[] adminKeyBytes;
    private final byte[] readOnlyKeyBytes;

    public ApiKeyAuth() {
        String key = System.getenv("API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("API_KEY environment variable is not set");
        }
        this.adminKeyBytes = key.getBytes(StandardCharsets.UTF_8);
        String rok = System.getenv("READ_ONLY_KEY");
        this.readOnlyKeyBytes = rok != null ? rok.getBytes(StandardCharsets.UTF_8) : null;
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

        if (constantEquals(provided, adminKeyBytes)) return;

        if (readOnlyKeyBytes != null && constantEquals(provided, readOnlyKeyBytes)) {
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

    private static boolean constantEquals(String a, byte[] b) {
        if (a == null || b == null) return false;
        // API キーは ASCII のみを想定しているので、長さチェックを先に行うことで
        // 不一致ケースでオブジェクト生成を抑制できる。
        // 同じ長さの付け似せキーに対しては依然 timing-safe な比較を行う。
        // 一般に API キーは UTF-8 で ASCII だけで構成されるため
        // String#length() と byte[] の長さは一致する。万一 ASCII 以外が渡された場合も
        // length()<bytes となり不一致と判定されるため安全。
        if (a.length() != b.length) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b);
    }
}
