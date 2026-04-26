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
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** JWKS kid → RSA PublicKey, refreshed when cache expires. */
    private final ConcurrentHashMap<String, PublicKey> keyCache = new ConcurrentHashMap<>();
    private volatile Instant keysCachedAt = Instant.EPOCH;

    private final String audience;
    private final String certsUrl;
    private final boolean enabled;

    public CfAccessAuth() {
        String aud      = System.getenv("CF_ACCESS_AUD");
        String domain   = System.getenv("CF_ACCESS_TEAM_DOMAIN");
        String devFlag  = System.getenv("CF_ACCESS_DEV_DISABLE");

        if (aud == null || aud.isBlank() || domain == null || domain.isBlank()) {
            if (!"true".equalsIgnoreCase(devFlag)) {
                throw new IllegalStateException(
                    "CfAccessAuth: CF_ACCESS_AUD and CF_ACCESS_TEAM_DOMAIN must be set. " +
                    "To disable CF Access JWT validation in development, set CF_ACCESS_DEV_DISABLE=true.");
            }
            logger.warn("CfAccessAuth: JWT validation DISABLED (CF_ACCESS_DEV_DISABLE=true)");
            this.audience  = null;
            this.certsUrl  = null;
            this.enabled   = false;
        } else {
            this.audience = aud.strip();
            this.certsUrl = "https://" + domain.strip() + "/cdn-cgi/access/certs";
            this.enabled  = true;
            logger.info("CfAccessAuth enabled. Audience={} Certs={}", audience, certsUrl);
        }
    }

    @Override
    public void handle(Context ctx) {
        if (!enabled) return;
        if ("/health".equals(ctx.path())) return;
        // CORS preflights do not carry a CF-Access-Jwt-Assertion header — skip JWT check
        if ("OPTIONS".equals(ctx.method())) return;
        String token = ctx.requestHeader("CF-Access-Jwt-Assertion");
        if (token == null || token.isBlank()) {
            ctx.status(401).json(Map.of("error", "Missing CF-Access-Jwt-Assertion header")).halt();
            return;
        }

        try {
            String email = verifyAndExtractEmail(token);
            ctx.setAttribute(ATTR_VERIFIED_EMAIL, email);
        } catch (Exception e) {
            logger.warn("CF Access JWT validation failed: {}", e.getMessage());
            ctx.status(401).json(Map.of("error", "Invalid or expired Cloudflare Access token")).halt();
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

        // Validate claims
        long now = Instant.now().getEpochSecond();
        long exp = payload.path("exp").asLong(0);
        long nbf = payload.path("nbf").asLong(0);
        if (exp > 0 && now > exp) throw new SecurityException("JWT has expired (exp=" + exp + ")");
        if (nbf > 0 && now < nbf) throw new SecurityException("JWT not yet valid (nbf=" + nbf + ")");

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
        // Fast-path: return from cache if still fresh (no lock needed for read)
        if (!keyCache.isEmpty() && Instant.now().isBefore(keysCachedAt.plus(JWKS_CACHE_TTL))) {
            PublicKey cached = keyCache.get(kid);
            if (cached != null) return cached;
        }

        // Slow-path: refresh JWKS under a lock to prevent concurrent fetches
        synchronized (this) {
            // Re-check after acquiring lock — another thread may have refreshed already
            if (!keyCache.isEmpty() && Instant.now().isBefore(keysCachedAt.plus(JWKS_CACHE_TTL))) {
                PublicKey cached = keyCache.get(kid);
                if (cached != null) return cached;
            }

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

            // Build new cache atomically — clear only after successful parse
            Map<String, PublicKey> newCache = new java.util.HashMap<>();
            JsonNode jwks = mapper.readTree(resp.body());
            for (JsonNode jwk : jwks.path("keys")) {
                if (!"RSA".equals(jwk.path("kty").asText())) continue;
                String  k = jwk.path("kid").asText();
                byte[] n  = Base64.getUrlDecoder().decode(jwk.path("n").asText());
                byte[] e  = Base64.getUrlDecoder().decode(jwk.path("e").asText());
                RSAPublicKeySpec spec = new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e));
                PublicKey pubKey = KeyFactory.getInstance("RSA").generatePublic(spec);
                newCache.put(k, pubKey);
            }
            keyCache.clear();
            keyCache.putAll(newCache);
            keysCachedAt = Instant.now();

            PublicKey key = keyCache.get(kid);
            if (key == null) throw new SecurityException("No JWK found for kid=" + kid);
            return key;
        }
    }

    // ── util ──────────────────────────────────────────────────────────────────

    private static String decodeBase64Url(String input) {
        return new String(Base64.getUrlDecoder().decode(input), StandardCharsets.UTF_8);
    }
}
