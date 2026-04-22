package dev.gate;

import dev.gate.core.Context;
import dev.gate.core.Handler;

import java.util.Map;

public class ApiKeyAuth implements Handler {

    private static final String HEADER = "X-API-Key";
    private final String expectedKey;

    public ApiKeyAuth() {
        String key = System.getenv("API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("API_KEY environment variable is not set");
        }
        this.expectedKey = key;
    }

    @Override
    public void handle(Context ctx) {
        // /health は認証不要
        if ("/health".equals(ctx.path())) return;

        String provided = ctx.requestHeader(HEADER);
        if (provided == null || !provided.equals(expectedKey)) {
            ctx.status(401).json(Map.of("error", "Unauthorized")).halt();
        }
    }
}
