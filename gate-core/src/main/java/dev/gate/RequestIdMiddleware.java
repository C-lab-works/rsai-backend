package dev.gate;

import dev.gate.core.Context;
import dev.gate.core.Handler;
import dev.gate.core.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RequestIdMiddleware implements Handler {

    private static final Logger logger = new Logger(RequestIdMiddleware.class);
    private static final long TTL_MS = 30L * 60 * 1000;
    private static final int UUID_LENGTH = 36;
    // uuid → expiry timestamp
    private static final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();

    @Override
    public void handle(Context ctx) {
        boolean isStarsMutation = "/stars".equals(ctx.path())
                && ("POST".equalsIgnoreCase(ctx.method()) || "DELETE".equalsIgnoreCase(ctx.method()));
        if (!isStarsMutation) return;

        String requestId = ctx.requestHeader("X-Request-Id");
        if (requestId == null || requestId.length() != UUID_LENGTH) {
            ctx.status(400).json(Map.of("error", "Missing or invalid X-Request-Id")).halt();
            return;
        }

        long now = System.currentTimeMillis();
        long expiry = now + TTL_MS;
        Long existing = seen.putIfAbsent(requestId, expiry);
        if (existing != null && existing > now) {
            logger.warn("Duplicate X-Request-Id rejected: {}", requestId.substring(0, 8));
            ctx.status(409).json(Map.of("error", "Duplicate request")).halt();
            return;
        }
        if (existing != null) {
            seen.put(requestId, expiry);
        }
    }

    public static void cleanup() {
        long now = System.currentTimeMillis();
        seen.entrySet().removeIf(e -> e.getValue() <= now);
    }
}
