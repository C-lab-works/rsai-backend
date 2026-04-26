package dev.gate;

import dev.gate.core.Context;
import dev.gate.core.Handler;

/**
 * After-filter that injects security-related HTTP response headers on every response.
 * Register via {@code gate.after(SecurityHeaders.get()::handle)}.
 */
public final class SecurityHeaders implements Handler {

    private static final SecurityHeaders INSTANCE = new SecurityHeaders();

    private SecurityHeaders() {}

    public static SecurityHeaders get() {
        return INSTANCE;
    }

    @Override
    public void handle(Context ctx) {
        // Prevents this API response from being framed; not strictly required for a JSON API
        // but added as defence-in-depth in case an HTML error page is ever returned.
        ctx.header("X-Frame-Options", "DENY");

        // Prevents browsers from MIME-sniffing the content type.
        ctx.header("X-Content-Type-Options", "nosniff");

        // Instructs browsers to only use HTTPS for 1 year (including sub-domains).
        ctx.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains");

        // Restrictive CSP — this is a JSON API so scripts are never expected.
        ctx.header("Content-Security-Policy", "default-src 'none'");

        // Suppress Referrer header when navigating away from this API.
        ctx.header("Referrer-Policy", "no-referrer");

        // Disable all browser features that are not needed for an API.
        ctx.header("Permissions-Policy", "geolocation=(), camera=(), microphone=()");
    }
}
