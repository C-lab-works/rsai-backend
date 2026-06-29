package dev.gate;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.gate.core.Context;
import dev.gate.core.Handler;
import dev.gate.core.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

public class RequestIdMiddleware implements Handler {

    private static final Logger logger = new Logger(RequestIdMiddleware.class);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    // Populated only after App Check passes (see StarsController.markSeenOrReject).
    // Bounded to prevent unbounded growth from unauthenticated callers.
    private static final Cache<String, Boolean> seen = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    @Override
    public void handle(Context ctx) {
        boolean isStarsMutation = "/stars".equals(ctx.path())
                && ("POST".equalsIgnoreCase(ctx.method()) || "DELETE".equalsIgnoreCase(ctx.method()));
        if (!isStarsMutation) return;

        String requestId = ctx.requestHeader("X-Request-Id");
        if (requestId == null || !UUID_PATTERN.matcher(requestId).matches()) {
            ctx.status(400).json(Map.of("error", "Missing or invalid X-Request-Id")).halt();
        }
    }

    /**
     * Returns true and marks the request ID as seen if it has not been seen before.
     * Returns false (caller should respond 409) if it is a duplicate.
     * Must be called only after App Check + blocklist validation to avoid
     * unauthenticated requests polluting the dedup cache.
     */
    public static boolean markSeenOrReject(String requestId) {
        Boolean existing = seen.asMap().putIfAbsent(requestId, Boolean.TRUE);
        if (existing != null) {
            logger.warn("Duplicate X-Request-Id rejected: {}", requestId.substring(0, 8));
            return false;
        }
        return true;
    }
}
