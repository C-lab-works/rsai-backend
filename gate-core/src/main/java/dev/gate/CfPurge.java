package dev.gate;

import dev.gate.core.Json;
import dev.gate.core.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cloudflare キャッシュ purge ユーティリティ。
 *
 * <p>エッジ TTL を長く(5 分)取る代わりに、データ書き込み起点で該当 URL を purge して
 * 鮮度をイベント駆動にする。URL 単位 purge には絶対 URL が必要なため、環境変数
 * {@code PUBLIC_BASE_URL}(例: https://v2.r-sai2026.site)からビルドする。
 * 未設定の環境(debug 等)では URL purge は無効になり、purge_everything のみ使える。</p>
 *
 * <p>注意: CF の purge by URL は完全一致。クエリ文字列付きでキャッシュされたエントリは
 * 対象外なので、Cache Rule 側でクエリ無視のキャッシュキーを推奨(docs/scaling-ops.md 参照)。</p>
 */
public final class CfPurge {

    private static final Logger logger = new Logger(CfPurge.class);
    private static final HttpClient HTTP = dev.gate.core.Http.CLIENT;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final String API_TOKEN = System.getenv("CF_API_TOKEN");
    private static final String ZONE_ID = System.getenv("CF_ZONE_ID");
    private static final String PUBLIC_BASE_URL = normalizeBaseUrl(System.getenv("PUBLIC_BASE_URL"));

    static {
        if (!isConfigured()) {
            logger.warn("CfPurge disabled: CF_API_TOKEN / CF_ZONE_ID not configured");
        } else if (PUBLIC_BASE_URL == null) {
            logger.warn("PUBLIC_BASE_URL not set — URL-level purge disabled (purge_everything only)");
        } else {
            logger.info("CfPurge enabled: base={}", PUBLIC_BASE_URL);
        }
    }

    private CfPurge() {}

    public static boolean isConfigured() {
        return notBlank(API_TOKEN) && notBlank(ZONE_ID);
    }

    /** 全キャッシュ purge(同期)。/admin/cache/clear の応答フラグ用。 */
    public static boolean purgeEverythingSync() {
        if (!isConfigured()) {
            logger.warn("CF purge skipped: CF_API_TOKEN or CF_ZONE_ID not configured");
            return false;
        }
        try {
            HttpResponse<String> res = HTTP.send(
                    request("{\"purge_everything\":true}"), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                logger.info("CF cache purged (everything)");
                return true;
            }
            logger.warn("CF purge returned HTTP {}: {}", res.statusCode(), truncate(res.body()));
            return false;
        } catch (Exception e) {
            logger.warn("CF purge failed: {}", e.getMessage());
            return false;
        }
    }

    /** 全キャッシュ purge(非同期)。CacheSync の自動同期用。 */
    public static void purgeEverythingAsync() {
        if (!isConfigured()) return;
        sendAsync("{\"purge_everything\":true}", "everything");
    }

    /**
     * 指定パス群を URL 単位で purge(非同期)。
     * PUBLIC_BASE_URL 未設定時は何もしない(エッジは s-maxage で自然失効)。
     */
    public static void purgeUrlsAsync(String... paths) {
        if (!isConfigured() || PUBLIC_BASE_URL == null) return;
        List<String> urls = buildUrls(PUBLIC_BASE_URL, paths);
        if (urls.isEmpty()) return;
        try {
            sendAsync(buildFilesBody(urls), String.join(",", paths));
        } catch (Exception e) {
            logger.warn("CF purge body build failed: {}", e.getMessage());
        }
    }

    // ── 純関数部(テスト対象) ───────────────────────────────────────────────

    /** ベース URL の正規化: スキーム補完・末尾スラッシュ除去。空なら null。 */
    static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://" + s;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** base × paths から purge 対象の絶対 URL リストを構築する。空・null パスは無視。 */
    static List<String> buildUrls(String base, String... paths) {
        List<String> urls = new ArrayList<>(paths.length);
        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            urls.add(base + (p.startsWith("/") ? p : "/" + p));
        }
        return urls;
    }

    /** {"files":[...]} ボディを構築する。 */
    static String buildFilesBody(List<String> urls) throws Exception {
        return Json.MAPPER.writeValueAsString(Map.of("files", urls));
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private static void sendAsync(String body, String what) {
        HTTP.sendAsync(request(body), HttpResponse.BodyHandlers.ofString())
            .thenAccept(res -> {
                if (res.statusCode() == 200) {
                    logger.info("CF cache purged ({})", what);
                } else {
                    logger.warn("CF purge ({}) returned HTTP {}: {}",
                            what, res.statusCode(), truncate(res.body()));
                }
            })
            .exceptionally(e -> {
                logger.warn("CF purge ({}) failed: {}", what, e.getMessage());
                return null;
            });
    }

    private static HttpRequest request(String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create("https://api.cloudflare.com/client/v4/zones/" + ZONE_ID + "/purge_cache"))
                .header("Authorization", "Bearer " + API_TOKEN)
                .header("Content-Type", "application/json")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200);
    }
}
