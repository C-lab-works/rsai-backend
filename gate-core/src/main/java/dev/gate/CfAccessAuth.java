package dev.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gate.core.Context;
import dev.gate.core.Handler;
import dev.gate.core.Logger;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Validates Cloudflare Access JWT tokens ({@code CF-Access-Jwt-Assertion} header).
 *
 * <p>When Cloudflare Access is configured in front of the backend, every request from
 * an authenticated user carries a signed JWT. This filter verifies that JWT, preventing
 * forged {@code Cf-Access-Authenticated-User-Email} headers from reaching controllers.</p>
 *
 * <p>The verified email is stored in the request context under the attribute key
 * {@link #ATTR_VERIFIED_EMAIL} so controllers can use it for audit logging.</p>
 *
 * <p>Required environment variables:
 * <ul>
 *   <li>{@code CF_ACCESS_AUD}         — Application Audience tag from the Cloudflare Access dashboard</li>
 *   <li>{@code CF_ACCESS_TEAM_DOMAIN} — Team domain, e.g. {@code myteam.cloudflareaccess.com}</li>
 * </ul>
 * If either variable is absent the filter is disabled (no-op). This allows the same
 * binary to run locally without Cloudflare in front.</p>
 */
public class CfAccessAuth implements Handler {

    public static final String ATTR_VERIFIED_EMAIL = "cf_verified_email";

    private static final Logger logger = new Logger(CfAccessAuth.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final Duration JWKS_CACHE_TTL = Duration.ofHours(1);

    /** Atomic reference for lock-free reads; replaced atomically on refresh. */
    private final AtomicReference<ConcurrentHashMap<String, PublicKey>> keyCacheRef
            = new AtomicReference<>(new ConcurrentHashMap<>());
    private volatile Instant keysCachedAt = Instant.EPOCH;

    private final String audience;
    private final String teamDomain;
    private final String certsUrl;
    private final boolean enabled;
    private final Set<String> adminEmails;

    public CfAccessAuth() {
        String aud     = System.getenv("CF_ACCESS_AUD");
        String domain  = System.getenv("CF_ACCESS_TEAM_DOMAIN");
        String devFlag = System.getenv("CF_ACCESS_DEV_DISABLE");
        String admins  = System.getenv("ADMIN_EMAILS");
        // Normalise to lowercase so email comparisons are case-insensitive
        this.adminEmails = (admins != null && !admins.isBlank())
            ? Arrays.stream(admins.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet())
            : Set.of();

        if (aud == null || aud.isBlank() || domain == null || domain.isBlank()) {
            if (!"true".equalsIgnoreCase(devFlag)) {
                throw new IllegalStateException(
                    "CfAccessAuth: CF_ACCESS_AUD and CF_ACCESS_TEAM_DOMAIN must be set. " +
                    "To disable CF Access JWT validation in development, set CF_ACCESS_DEV_DISABLE=true.");
            }
            logger.warn("CfAccessAuth: JWT validation DISABLED (CF_ACCESS_DEV_DISABLE=true)");
            this.audience   = null;
            this.teamDomain = null;
            this.certsUrl   = null;
            this.enabled    = false;
        } else {
            this.audience = aud.strip();
            String d = domain.strip();
            if (!d.contains(".")) {
                d = d + ".cloudflareaccess.com";
            }
            this.teamDomain = "https://" + d;
            this.certsUrl   = "https://" + d + "/cdn-cgi/access/certs";
            this.enabled    = true;
            logger.info("CfAccessAuth enabled. Audience={} Certs={} AdminEmails={}", audience, certsUrl, adminEmails.size());
            if (this.adminEmails.isEmpty()) {
                throw new IllegalStateException(
                    "CfAccessAuth: ADMIN_EMAILS must be set when CF Access is enabled. " +
                    "An empty list would grant admin access to every authenticated user.");
            }
        }
    }

    /**
     * Eagerly fetches and caches the JWKS public keys.
     */
    public void prefetchJwks() {
        if (!enabled) return;
        try {
            synchronized (this) {
                refreshKeysLocked();
            }
            logger.info("JWKS prefetch complete ({} keys cached)", keyCacheRef.get().size());
        } catch (Exception e) {
            logger.warn("JWKS prefetch failed (will retry on first request): {}", e.getMessage());
        }
    }

    @Override
    public void handle(Context ctx) {
        if ("/health".equals(ctx.path())) return;
        if ("OPTIONS".equals(ctx.method())) return;

        if (!enabled) {
            if (ctx.path().startsWith("/admin")) {
                ctx.status(503).json(Map.of("error",
                    "Admin access unavailable: CF Access is disabled")).halt();
            }
            return;
        }

        String token = ctx.requestHeader("CF-Access-Jwt-Assertion");

        if (ctx.path().startsWith("/admin")) {
            // Admin endpoints: JWT required + must be in ADMIN_EMAILS (if configured)
            if (token == null || token.isBlank()) {
                ctx.status(401).json(Map.of("error", "Missing CF-Access-Jwt-Assertion header")).halt();
                return;
            }
            try {
                String email = verifyAndExtractEmail(token);
                if (!adminEmails.contains(email.toLowerCase())) {
                    logger.warn("Admin access denied for email={}", email);
                    ctx.status(403).json(Map.of("error", "Forbidden: admin access required")).halt();
                    return;
                }
                ctx.setAttribute(ATTR_VERIFIED_EMAIL, email);
            } catch (Exception e) {
                logger.warn("CF Access JWT validation failed: {}", e.getMessage());
                ctx.status(401).json(Map.of("error", "Invalid or expired Cloudflare Access token")).halt();
            }
        } else if (token != null && !token.isBlank()) {
            // Non-admin endpoints: extract email opportunistically if JWT is present
            try {
                String email = verifyAndExtractEmail(token);
                ctx.setAttribute(ATTR_VERIFIED_EMAIL, email);
            } catch (Exception e) {
                logger.debug("CF Access JWT opportunistic extraction failed: {}", e.getMessage());
            }
        }
    }

    // ── JWT verification ──────────────────────────────────────────────────────

    private String verifyAndExtractEmail(String token) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new IllegalArgumentException("JWT must have 3 parts");

        String headerJson  = decodeBase64Url(parts[0]);
        String payloadJson = decodeBase64Url(parts[1]);
        byte[] sigBytes    = Base64.getUrlDecoder().decode(parts[2]);

        JsonNode header  = mapper.readTree(headerJson);
        JsonNode payload = mapper.readTree(payloadJson);

        String kid = header.path("kid").asText();
        String alg = header.path("alg").asText();
        if (!"RS256".equals(alg)) throw new IllegalArgumentException("Unsupported algorithm: " + alg);

        PublicKey key = getPublicKey(kid);
        byte[] signedData = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(key);
        sig.update(signedData);
        if (!sig.verify(sigBytes)) throw new SecurityException("JWT signature verification failed");

        // Validate time claims
        long now = Instant.now().getEpochSecond();
        long exp = payload.path("exp").asLong(0);
        long iat = payload.path("iat").asLong(0);
        if (exp <= 0) throw new SecurityException("JWT missing required exp claim");
        if (now > exp) throw new SecurityException("JWT has expired (exp=" + exp + ")");
        if (iat > 0 && now - iat > 86400) throw new SecurityException("JWT iat too old (>24h)");
        long nbf = payload.path("nbf").asLong(0);
        if (nbf > 0 && now + 60 < nbf) throw new SecurityException("JWT not yet valid (nbf=" + nbf + ")");

        // Validate issuer
        String iss = payload.path("iss").asText("");
        if (!teamDomain.equals(iss)) throw new SecurityException("JWT issuer mismatch: " + iss);

        // Validate audience
        JsonNode audNode = payload.get("aud");
        boolean audMatched = false;
        if (audNode != null) {
            if (audNode.isArray()) {
                for (JsonNode a : audNode) {
                    if (audience.equals(a.asText())) { audMatched = true; break; }
                }
            } else {
                audMatched = audience.equals(audNode.asText());
            }
        }
        if (!audMatched) throw new SecurityException("JWT audience mismatch");

        String email = payload.path("email").asText(null);
        if (email == null || email.isBlank()) throw new SecurityException("JWT missing email claim");
        return email;
    }

    // ── JWKS fetching / caching ───────────────────────────────────────────────

    private PublicKey getPublicKey(String kid) throws Exception {
        // Fast-path: lock-free read from current cache snapshot
        ConcurrentHashMap<String, PublicKey> current = keyCacheRef.get();
        if (!current.isEmpty() && Instant.now().isBefore(keysCachedAt.plus(JWKS_CACHE_TTL))) {
            PublicKey cached = current.get(kid);
            if (cached != null) return cached;
        }

        // Slow-path: refresh under lock
        synchronized (this) {
            current = keyCacheRef.get();
            if (!current.isEmpty() && Instant.now().isBefore(keysCachedAt.plus(JWKS_CACHE_TTL))) {
                PublicKey cached = current.get(kid);
                if (cached != null) return cached;
            }

            refreshKeysLocked();

            PublicKey key = keyCacheRef.get().get(kid);
            if (key == null) throw new SecurityException("No JWK found for kid=" + kid);
            return key;
        }
    }

    /**
     * Fetches JWKS and atomically replaces the key cache. Must be called under {@code synchronized(this)}.
     */
    private void refreshKeysLocked() throws Exception {
        logger.info("Refreshing JWKS from {}", certsUrl);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(certsUrl))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch JWKS: HTTP " + resp.statusCode());
        }

        ConcurrentHashMap<String, PublicKey> fresh = new ConcurrentHashMap<>();
        JsonNode jwks = mapper.readTree(resp.body());
        for (JsonNode jwk : jwks.path("keys")) {
            if (!"RSA".equals(jwk.path("kty").asText())) continue;
            String  k = jwk.path("kid").asText();
            byte[] n  = Base64.getUrlDecoder().decode(jwk.path("n").asText());
            byte[] e  = Base64.getUrlDecoder().decode(jwk.path("e").asText());
            RSAPublicKeySpec spec = new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e));
            PublicKey pubKey = KeyFactory.getInstance("RSA").generatePublic(spec);
            fresh.put(k, pubKey);
        }
        // Atomic swap — no window where cache is empty
        keyCacheRef.set(fresh);
        keysCachedAt = Instant.now();
    }

    // ── util ──────────────────────────────────────────────────────────────────

    private static String decodeBase64Url(String input) {
        return new String(Base64.getUrlDecoder().decode(input), StandardCharsets.UTF_8);
    }
}
