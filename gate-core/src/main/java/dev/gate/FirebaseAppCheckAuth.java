package dev.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gate.core.Context;
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
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Firebase App Check の検証を行うミドルウェア・ヘルパークラス。
 * GraalVM Native Image での動作を保証するため、外部ライブラリを使わず
 * java.security.Signature と Jackson のみで JWT のデコードと署名検証を行います。
 */
public class FirebaseAppCheckAuth {
    private static final Logger logger = new Logger(FirebaseAppCheckAuth.class);
    private static final ObjectMapper mapper = dev.gate.core.Json.MAPPER;
    private static final HttpClient httpClient = dev.gate.core.Http.CLIENT;

    private static final Duration JWKS_CACHE_TTL = Duration.ofHours(1);
    private static final long CLOCK_SKEW_LEEWAY_SECS = 300L; // 5分間の時計ズレを許容

    // トークン検証結果のキャッシュ (過剰な署名検証の負荷を低減)
    private static final Cache<String, Boolean> tokenVerificationCache =
        Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    private final AtomicReference<ConcurrentHashMap<String, PublicKey>> keyCacheRef
            = new AtomicReference<>(new ConcurrentHashMap<>());

    private volatile Instant keysCachedAt = Instant.EPOCH;
    private final ReentrantLock jwksLock = new ReentrantLock();

    private final String projectId;
    private final String projectNumber;
    private final String certsUrl;
    private final boolean enabled;
    private final boolean devDisable;

    public FirebaseAppCheckAuth() {
        this.projectId = System.getenv("FIREBASE_PROJECT_ID");
        this.projectNumber = System.getenv("FIREBASE_PROJECT_NUMBER");
        
        String devFlag = System.getenv("FIREBASE_APPCHECK_DEV_DISABLE");
        this.devDisable = "true".equalsIgnoreCase(devFlag);

        this.certsUrl = "https://firebaseappcheck.googleapis.com/v1/jwks";

        if ((projectId == null || projectId.isBlank() || projectNumber == null || projectNumber.isBlank()) && !devDisable) {
            logger.warn("FirebaseAppCheckAuth: FIREBASE_PROJECT_ID and FIREBASE_PROJECT_NUMBER must be set unless FIREBASE_APPCHECK_DEV_DISABLE=true");
            this.enabled = false;
        } else {
            this.enabled = true;
            logger.info("FirebaseAppCheckAuth initialized. ProjectID={} ProjectNumber={} DevDisable={}", projectId, projectNumber, devDisable);
        }
    }

    public boolean verify(Context ctx) {
        return verifyAndGetSubject(ctx) != null;
    }

    // sub クレーム（アプリインスタンス識別子）を返す。検証失敗時は null。
    // devDisable 時は "dev" を返す。
    public String verifyAndGetSubject(Context ctx) {
        if (devDisable) {
            logger.debug("Firebase App Check bypassed (dev mode)");
            return "dev";
        }

        if (!enabled) {
            logger.warn("Firebase App Check is disabled or misconfigured");
            return null;
        }

        String token = ctx.requestHeader("X-Firebase-AppCheck");
        if (token == null || token.isBlank()) {
            logger.warn("Missing X-Firebase-AppCheck header");
            return null;
        }

        Boolean cachedResult = tokenVerificationCache.getIfPresent(token);
        if (cachedResult != null) {
            if (!cachedResult) return null;
            // キャッシュヒット(true)でも sub が必要なため token から再取得
            try { return extractSub(token); } catch (Exception e) { return null; }
        }

        try {
            String sub = verifyToken(token);
            tokenVerificationCache.put(token, true);
            return sub;
        } catch (Exception e) {
            logger.warn("Firebase App Check token validation failed");
            logger.debug("Firebase App Check token validation failed: {}", e.getMessage());
            tokenVerificationCache.put(token, false);
            return null;
        }
    }

    // 署名・クレーム検証を行い sub クレームを返す
    private String verifyToken(String token) throws Exception {
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

        // クレーム検証
        long now = Instant.now().getEpochSecond();
        long exp = payload.path("exp").asLong(0);
        long iat = payload.path("iat").asLong(0);

        if (exp <= 0) throw new SecurityException("JWT missing required exp claim");
        if (now > exp + CLOCK_SKEW_LEEWAY_SECS) throw new SecurityException("JWT has expired");
        if (iat > 0 && now + CLOCK_SKEW_LEEWAY_SECS < iat) throw new SecurityException("JWT iat in future");

        // iss: 末尾がプロジェクト番号の場合と、プロジェクトIDの場合の双方に対応する
        String iss = payload.path("iss").asText("");
        String expectedIssNumber = "https://firebaseappcheck.googleapis.com/" + projectNumber;
        String expectedIssId = "https://firebaseappcheck.googleapis.com/" + projectId;
        if (!expectedIssNumber.equals(iss) && !expectedIssId.equals(iss)) {
            throw new SecurityException("JWT issuer mismatch: " + iss);
        }

        // aud: 通常 projects/<project_id> もしくは projects/<project_number>
        String aud = payload.path("aud").asText("");
        String expectedAudId = "projects/" + projectId;
        String expectedAudNumber = "projects/" + projectNumber;
        if (!expectedAudId.equals(aud) && !expectedAudNumber.equals(aud)) {
            throw new SecurityException("JWT audience mismatch: " + aud);
        }

        String sub = payload.path("sub").asText(null);
        if (sub == null || sub.isBlank()) throw new SecurityException("JWT missing sub claim");
        return sub;
    }

    // キャッシュヒット時に sub だけ取り出す（署名検証なし）
    private static String extractSub(String token) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new IllegalArgumentException("JWT must have 3 parts");
        JsonNode payload = mapper.readTree(decodeBase64Url(parts[1]));
        String sub = payload.path("sub").asText(null);
        if (sub == null || sub.isBlank()) throw new SecurityException("JWT missing sub claim");
        return sub;
    }

    private PublicKey getPublicKey(String kid) throws Exception {
        ConcurrentHashMap<String, PublicKey> current = keyCacheRef.get();
        boolean cacheValid = !current.isEmpty() &&
                Instant.now().isBefore(keysCachedAt.plus(JWKS_CACHE_TTL));
        if (cacheValid) {
            PublicKey cached = current.get(kid);
            if (cached != null) return cached;
            throw new SecurityException("No JWK found for kid=" + kid);
        }

        jwksLock.lock();
        try {
            current = keyCacheRef.get();
            cacheValid = !current.isEmpty() &&
                    Instant.now().isBefore(keysCachedAt.plus(JWKS_CACHE_TTL));
            if (cacheValid) {
                PublicKey cached = current.get(kid);
                if (cached != null) return cached;
                throw new SecurityException("No JWK found for kid=" + kid);
            }

            refreshKeysLocked();

            PublicKey key = keyCacheRef.get().get(kid);
            if (key == null) throw new SecurityException("No JWK found for kid=" + kid);
            return key;
        } finally {
            jwksLock.unlock();
        }
    }

    private void refreshKeysLocked() throws Exception {
        logger.info("Refreshing Firebase JWKS from {}", certsUrl);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(certsUrl))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch Firebase JWKS: HTTP " + resp.statusCode());
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
        keyCacheRef.set(fresh);
        keysCachedAt = Instant.now();
    }

    private static String decodeBase64Url(String input) {
        return new String(Base64.getUrlDecoder().decode(input), StandardCharsets.UTF_8);
    }
}
