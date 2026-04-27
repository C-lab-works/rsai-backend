package dev.gate.middleware;

import dev.gate.core.Context;
import dev.gate.core.Handler;

/**
 * After-filter that adds security-related HTTP response headers.
 * Both {@code SecurityHeaders} and {@code RequestMetrics} are opt-in:
 * they only take effect when explicitly registered via
 * {@code gate.before()} or {@code gate.after()}.
 *
 * <p>Quick start — all headers with sensible defaults:
 * <pre>
 * gate.after(SecurityHeaders.defaults()::handle);
 * </pre>
 *
 * <p>Custom — enable only what you need:
 * <pre>
 * gate.after(SecurityHeaders.builder()
 *     .hsts(false)                      // skip in development
 *     .frameOptions("SAMEORIGIN")       // override default
 *     .build()::handle);
 * </pre>
 */
public final class SecurityHeaders implements Handler {

    private final String frameOptions;
    private final String contentTypeOptions;
    private final String hsts;
    private final String csp;
    private final String referrerPolicy;
    private final String permissionsPolicy;

    private SecurityHeaders(Builder b) {
        this.frameOptions       = b.frameOptions;
        this.contentTypeOptions = b.contentTypeOptions;
        this.hsts               = b.hsts;
        this.csp                = b.csp;
        this.referrerPolicy     = b.referrerPolicy;
        this.permissionsPolicy  = b.permissionsPolicy;
    }

    /** Returns an instance with all headers set to recommended defaults. */
    public static SecurityHeaders defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void handle(Context ctx) {
        if (frameOptions       != null) ctx.header("X-Frame-Options",           frameOptions);
        if (contentTypeOptions != null) ctx.header("X-Content-Type-Options",    contentTypeOptions);
        if (hsts               != null) ctx.header("Strict-Transport-Security", hsts);
        if (csp                != null) ctx.header("Content-Security-Policy",   csp);
        if (referrerPolicy     != null) ctx.header("Referrer-Policy",           referrerPolicy);
        if (permissionsPolicy  != null) ctx.header("Permissions-Policy",        permissionsPolicy);
    }

    public static final class Builder {
        private String frameOptions       = "DENY";
        private String contentTypeOptions = "nosniff";
        private String hsts               = "max-age=31536000; includeSubDomains";
        private String csp                = "default-src 'none'";
        private String referrerPolicy     = "no-referrer";
        private String permissionsPolicy  = "geolocation=(), camera=(), microphone=()";

        private Builder() {}

        /** Set X-Frame-Options. Pass {@code null} to omit the header. */
        public Builder frameOptions(String value)       { this.frameOptions       = value; return this; }
        /** Set X-Content-Type-Options. Pass {@code null} to omit. */
        public Builder contentTypeOptions(String value) { this.contentTypeOptions = value; return this; }
        /** Set Strict-Transport-Security. Pass {@code null} to omit (e.g. in development). */
        public Builder hsts(String value)               { this.hsts               = value; return this; }
        /** Convenience: {@code hsts(false)} omits the HSTS header entirely. */
        public Builder hsts(boolean enabled)            { return hsts(enabled ? "max-age=31536000; includeSubDomains" : null); }
        /** Set Content-Security-Policy. Pass {@code null} to omit. */
        public Builder csp(String value)                { this.csp                = value; return this; }
        /** Set Referrer-Policy. Pass {@code null} to omit. */
        public Builder referrerPolicy(String value)     { this.referrerPolicy     = value; return this; }
        /** Set Permissions-Policy. Pass {@code null} to omit. */
        public Builder permissionsPolicy(String value)  { this.permissionsPolicy  = value; return this; }

        public SecurityHeaders build() { return new SecurityHeaders(this); }
    }
}
