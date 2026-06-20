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
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
// Cloudflare Access JWT 認証のミドルウェア 管理者用認証
public class CfAccessAuth implements Handler {
    public static final String ATTR_VERIFIED_EMAIL = "cf_verified_email";
    private static final Logger logger = new Logger(CfAccessAuth.class);
    private static final ObjectMapper mapper = dev.gate.core.Json.MAPPER;

    // アプリ全体で共有する HttpClient（接続タイムアウト 5s。読み取り上限は各 HttpRequest 側で指定）。
    private static final HttpClient httpClient = dev.gate.core.Http.CLIENT;

    private static final Duration JWKS_CACHE_TTL = Duration.ofHours(1);
    private static final long CLOCK_SKEW_LEEWAY_SECS = 30L;
    private static final int VERIFICATION_CACHE_MAX = 10_000;
    private record VerificationResult(String email, long expiresAtEpochSec) {}

    private static final Cache<String, VerificationResult> verificationCache =
        Caffeine.newBuilder()
            .maximumSize(VERIFICATION_CACHE_MAX)
            .build();

    private static volatile Set<String> adminEmailsRef = Set.of();
    // メールアドレスチェック
    public static boolean isAdmin(String email) {
        if (email == null || email.isBlank()) return false;
        return adminEmailsRef.contains(email.toLowerCase());
    }

    private final AtomicReference<ConcurrentHashMap<String, PublicKey>> keyCacheRef
            = new AtomicReference<>(new ConcurrentHashMap<>());

    private volatile Instant keysCachedAt = Instant.EPOCH;
    private final ReentrantLock jwksLock = new ReentrantLock();
    private final String audience;
    private final String teamDomain;
    private final String certsUrl;
    private final boolean enabled;
    private final Set<String> adminEmails;
    private final byte[] internalServiceKeyBytes;

    // 初期化部分。環境変数が入ってないかつ開発無効化フラグが立ってない場合は例外を出しクラッシュさせる。
    public CfAccessAuth() {
        // github secrets　
        String aud     = System.getenv("CF_ACCESS_AUD");
        String domain  = System.getenv("CF_ACCESS_TEAM_DOMAIN");
        String devFlag = System.getenv("CF_ACCESS_DEV_DISABLE"); // 開発環境用　本番環境ではfalseにすること。
        String admins  = System.getenv("ADMIN_EMAILS");
        this.adminEmails = (admins != null && !admins.isBlank())
            ? Arrays.stream(admins.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet())
            : Set.of();

        String sk = System.getenv("INTERNAL_SERVICE_KEY");
        this.internalServiceKeyBytes = (sk != null && !sk.isBlank())
            ? sk.getBytes(StandardCharsets.UTF_8)
            : null;
        if (internalServiceKeyBytes != null) {
            logger.info("CfAccessAuth: internal service key enabled (X-Service-Key)");
        }

        if (aud == null || aud.isBlank() || domain == null || domain.isBlank()) {
            if (!"true".equalsIgnoreCase(devFlag)) {
                throw new IllegalStateException( // CF_ACCESS_AUDとCF_ACCESS_TEAM_DOMAINが入ってない場合はクラッシュさせる (localはCF_ACCESS_DEV_DISABLE=trueで回避)
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
                throw new IllegalStateException( // 管理者アクセスに必要なメールアドレスが設定されていない場合もクラッシュさせる。
                    "CfAccessAuth: ADMIN_EMAILS must be set when CF Access is enabled. " +
                    "An empty list would grant admin access to every authenticated user.");
            }
            adminEmailsRef = this.adminEmails;
        }
    }

    public void prefetchJwks() {
        if (!enabled) return;
        try {
            jwksLock.lock();
            try {
                refreshKeysLocked();
            } finally {
                jwksLock.unlock();
            }
            logger.info("JWKS prefetch complete ({} keys cached)", keyCacheRef.get().size());
        } catch (Exception e) {
            logger.warn("JWKS prefetch failed (will retry on first request): {}", e.getMessage());
        }
    }

    // JWT 抽出・検証をこのフィルタで行う対象かどうかの判定。
    // 注意: 「true=admin限定で拒否」ではない。/admin は handle() で admin email を強制するが、
    // POST /congestion/ は handle() の非 /admin 分岐に落ち、トークンがあれば email を
    // opportunistic に抽出するだけ（admin か否かは問わない）。
    // POST /congestion の実際のアクセス制御は ApiKeyAuth（admin API キー）が担保し、
    // ここで抽出した email は監査ログ用途（誰が更新したか）として CongestionController に渡る。
    private static boolean needsJwtVerification(Context ctx) {
        String path = ctx.path();
        if (path.startsWith("/admin")) return true;
        if ("POST".equalsIgnoreCase(ctx.method()) &&
                (path.startsWith("/congestion/") || path.startsWith("/events/delays/"))) return true;
        return false;
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

        if (!needsJwtVerification(ctx)) return;

        String token = ctx.requestHeader("CF-Access-Jwt-Assertion");

        if (ctx.path().startsWith("/admin")) {
            // サーバー間 M2M 認証: X-Service-Key が設定済みかつ一致すれば JWT 検証をスキップ。
            if (internalServiceKeyBytes != null) {
                String provided = ctx.requestHeader("X-Service-Key");
                if (provided != null && MessageDigest.isEqual(
                        provided.getBytes(StandardCharsets.UTF_8), internalServiceKeyBytes)) {
                    ctx.setAttribute(ATTR_VERIFIED_EMAIL, "service@internal");
                    return;
                }
            }
            if (token == null || token.isBlank()) {
                ctx.status(401).json(Map.of("error", "Missing CF-Access-Jwt-Assertion header")).halt();
                return;
            }
            try {
                String email = verifyAndExtractEmailCached(token);
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
            // 非 /admin（実質 POST /congestion/）。ここでは admin 判定は行わず、
            // 有効なトークンから email を抽出して監査用に attribute へ載せるだけ。
            // アクセス可否は ApiKeyAuth（admin API キー）が既に担保しており、
            // CongestionController 側は「email が存在する=CF認証済み」を要件とする
            // （ADMIN_EMAILS 限定ではなく、CF Access 配下の認証ユーザを許容する設計）。
            // トークンが無効/期限切れなら email は載らず、CongestionController が 401 を返す。
            try {
                String email = verifyAndExtractEmailCached(token);
                ctx.setAttribute(ATTR_VERIFIED_EMAIL, email);
            } catch (Exception e) {
                logger.debug("CF Access JWT opportunistic extraction failed: {}", e.getMessage());
            }
        }
    }

    // 負荷軽減のためJWT結果のキャッシュ
    private String verifyAndExtractEmailCached(String token) throws Exception {
        long now = Instant.now().getEpochSecond();
        VerificationResult cached = verificationCache.getIfPresent(token);
        if (cached != null && now < cached.expiresAtEpochSec()) {
            if (cached.email() != null) return cached.email();
            throw new SecurityException("JWT previously rejected (cached)");
        }

        try {
            String[] parts = token.split("\\.");
            long exp = 0L;
            if (parts.length == 3) {
                try {
                    JsonNode payload = mapper.readTree(decodeBase64Url(parts[1]));
                    exp = payload.path("exp").asLong(0);
                } catch (Exception ignored) {}
            }
            String email = verifyAndExtractEmail(token);
            long cacheExp = exp > 0 ? exp : (now + 300);
            verificationCache.put(token, new VerificationResult(email, cacheExp));
            return email;
        } catch (SecurityException | IllegalArgumentException e) {
            // 署名・クレーム検証の確定的な失敗のみネガティブキャッシュする
            verificationCache.put(token, new VerificationResult(null, now + 60));
            throw e;
        } catch (Exception e) {
            // JWKS 取得失敗などの一時的なインフラ障害はキャッシュしない。
            // 一時障害で正当なトークンを最大60秒間ロックアウトしてしまうのを防ぐ。
            throw e;
        }
    }

    // JWT 検証

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

        // 時間クレームを検証
        long now = Instant.now().getEpochSecond();
        long exp = payload.path("exp").asLong(0);
        long iat = payload.path("iat").asLong(0);
        if (exp <= 0) throw new SecurityException("JWT missing required exp claim");
        if (now > exp + CLOCK_SKEW_LEEWAY_SECS) throw new SecurityException("JWT has expired (exp=" + exp + ")");
        if (iat > 0 && now - iat > 86400) throw new SecurityException("JWT iat too old (>24h)");
        long nbf = payload.path("nbf").asLong(0);
        if (nbf > 0 && now + 60 < nbf) throw new SecurityException("JWT not yet valid (nbf=" + nbf + ")");

        // 発行者を検証
        JsonNode issNode = payload.path("iss");
        String iss = issNode.isNull() || issNode.isMissingNode() ? "" : issNode.asText();
        if (!teamDomain.equals(iss)) throw new SecurityException("JWT issuer mismatch: " + iss);

        // オーディエンスを検証
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

        JsonNode emailNode = payload.path("email");
        String email = emailNode.isNull() || emailNode.isMissingNode() ? null : emailNode.asText();
        if (email == null || email.isBlank()) throw new SecurityException("JWT missing email claim");
        return email;
    }

    // JWKS 取得 / キャッシュ

    private PublicKey getPublicKey(String kid) throws Exception {
        ConcurrentHashMap<String, PublicKey> current = keyCacheRef.get();
        boolean cacheValid = !current.isEmpty() &&
                Instant.now().isBefore(keysCachedAt.plus(JWKS_CACHE_TTL));
        if (cacheValid) {
            PublicKey cached = current.get(kid);
            if (cached != null) return cached;
            // TTL 内の最新キャッシュに kid が存在しない — 再取得しても同じ結果になる
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

    // JWKSキャッシュ更新
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
        keyCacheRef.set(fresh);
        keysCachedAt = Instant.now();
    }

    private static String decodeBase64Url(String input) {
        return new String(Base64.getUrlDecoder().decode(input), StandardCharsets.UTF_8);
    }
}
