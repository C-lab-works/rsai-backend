package dev.gate;

import dev.gate.core.Context;
import dev.gate.core.Handler;

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
        // READ_ONLY_KEY は任意。未設定の場合は読み取り専用アクセスは無効
        this.readOnlyKey = System.getenv("READ_ONLY_KEY");
    }

    @Override
    public void handle(Context ctx) {
        // /health は認証不要
        if ("/health".equals(ctx.path())) return;

        String provided = ctx.requestHeader(HEADER);
        if (provided == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized")).halt();
            return;
        }

        if (provided.equals(adminKey)) {
            // 管理者キー：全メソッド許可
            return;
        }

        if (readOnlyKey != null && provided.equals(readOnlyKey)) {
            // 読み取り専用キー：GETのみ許可
            if (!"GET".equalsIgnoreCase(ctx.method())) {
                ctx.status(403).json(Map.of("error", "Forbidden: read-only access")).halt();
            }
            return;
        }

        ctx.status(401).json(Map.of("error", "Unauthorized")).halt();
    }
}
