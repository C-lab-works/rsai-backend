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
        this.readOnlyKey = System.getenv("READ_ONLY_KEY");
    }

    @Override
    public void handle(Context ctx) {
        if ("/health".equals(ctx.path())) return;

        String provided = ctx.requestHeader(HEADER);
        if (provided == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized")).halt();
            return;
        }

        if (provided.equals(adminKey)) {
            return;
        }

        if (readOnlyKey != null && provided.equals(readOnlyKey)) {
            if (!"GET".equalsIgnoreCase(ctx.method())) {
                ctx.status(403).json(Map.of("error", "Forbidden: read-only access")).halt();
            }
            return;
        }

        ctx.status(401).json(Map.of("error", "Unauthorized")).halt();
    }
}
