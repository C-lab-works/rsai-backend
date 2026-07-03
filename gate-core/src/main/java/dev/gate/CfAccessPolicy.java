package dev.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gate.core.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Cloudflare Access ポリシー(Allow ルール)のメール同期ユーティリティ。
 *
 * <p>CF API トークンは Gate 側にのみ保持する(パネル側 rsai2026admin は直接 CF API を
 * 呼ばず、本クラスを使う {@link AdminAccessPolicyController} を M2M 経由で呼ぶ)。</p>
 *
 * <p>reusable policy を GET → {@code include} 配列を書き換え → PUT で全体置換する方式。
 * {@code include}/{@code exclude}/{@code require} の既存エントリ(対象メール以外)は
 * 必ず保持したまま PUT する。PUT はリトライしない(失敗時は呼び出し元 UI 側の再試行に委ねる)。</p>
 */
public final class CfAccessPolicy {

    private static final Logger logger = new Logger(CfAccessPolicy.class);
    private static final HttpClient HTTP = dev.gate.core.Http.CLIENT;
    private static final ObjectMapper mapper = dev.gate.core.Json.MAPPER;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static final String API_TOKEN = System.getenv("CF_API_TOKEN");
    private static final String ACCOUNT_ID = System.getenv("CF_ACCOUNT_ID");
    private static final String POLICY_ID = System.getenv("CF_ACCESS_POLICY_ID");

    static {
        if (!isConfigured()) {
            logger.warn("CfAccessPolicy disabled: CF_API_TOKEN / CF_ACCOUNT_ID / CF_ACCESS_POLICY_ID not configured");
        } else {
            logger.info("CfAccessPolicy enabled: account={} policy={}", ACCOUNT_ID, POLICY_ID);
        }
    }

    private CfAccessPolicy() {}

    public static boolean isConfigured() {
        return notBlank(API_TOKEN) && notBlank(ACCOUNT_ID) && notBlank(POLICY_ID);
    }

    public enum Status { OK, SKIPPED, ERROR }

    /** 同期結果。SKIPPED/ERROR 時は message に理由(ユーザー表示可能な日本語文言)を持つ。 */
    public record SyncResult(Status status, String message) {
        static SyncResult ok() { return new SyncResult(Status.OK, null); }
        static SyncResult skipped(String reason) { return new SyncResult(Status.SKIPPED, reason); }
        static SyncResult error(String message) { return new SyncResult(Status.ERROR, message); }
    }

    /** メールを Allow ポリシーの include に追加する。既に含まれる場合は SKIPPED。 */
    public static SyncResult addEmail(String email) {
        return sync(email, true);
    }

    /** メールを Allow ポリシーの include から除去する。含まれない場合は SKIPPED。 */
    public static SyncResult removeEmail(String email) {
        return sync(email, false);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private static SyncResult sync(String rawEmail, boolean add) {
        if (!isConfigured()) {
            logger.warn("CF Access policy sync skipped: not configured");
            return SyncResult.skipped("Gate 側で CF 連携(CF_API_TOKEN/CF_ACCOUNT_ID/CF_ACCESS_POLICY_ID)が未設定のため同期をスキップしました");
        }
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase();
        if (email.isEmpty()) {
            return SyncResult.error("email が空です");
        }

        try {
            HttpResponse<String> getRes = HTTP.send(getRequest(), HttpResponse.BodyHandlers.ofString());
            JsonNode getRoot = mapper.readTree(getRes.body());
            if (getRes.statusCode() != 200 || !getRoot.path("success").asBoolean(false)) {
                String msg = extractErrorMessage(getRoot, "CF ポリシー取得に失敗しました (HTTP " + getRes.statusCode() + ")");
                logger.warn("CfAccessPolicy GET failed: {}", msg);
                return SyncResult.error(msg);
            }

            JsonNode result = getRoot.path("result");
            ArrayNode include = result.path("include").isArray()
                    ? (ArrayNode) result.path("include")
                    : mapper.createArrayNode();

            int idx = findEmailIndex(include, email);

            if (add) {
                if (idx >= 0) {
                    // 既に望んだ状態なら PUT せず成功扱い(noop)。SKIPPED は「同期そのものを
                    // 行えなかった」場合(env 未設定)専用で、パネル側では warning 表示になる。
                    return SyncResult.ok();
                }
                ObjectNode entry = mapper.createObjectNode();
                entry.putObject("email").put("email", email);
                include.add(entry);
            } else {
                if (idx < 0) {
                    return SyncResult.ok();
                }
                include.remove(idx);
            }

            ObjectNode putBody = mapper.createObjectNode();
            putBody.set("name", result.path("name"));
            putBody.set("decision", result.path("decision"));
            putBody.set("include", include);
            putBody.set("exclude", result.path("exclude"));
            putBody.set("require", result.path("require"));

            HttpResponse<String> putRes = HTTP.send(
                    putRequest(mapper.writeValueAsString(putBody)), HttpResponse.BodyHandlers.ofString());
            JsonNode putRoot = mapper.readTree(putRes.body());
            if (putRes.statusCode() != 200 || !putRoot.path("success").asBoolean(false)) {
                String msg = extractErrorMessage(putRoot, "CF ポリシー更新に失敗しました (HTTP " + putRes.statusCode() + ")");
                logger.warn("CfAccessPolicy PUT failed: {}", msg);
                return SyncResult.error(msg);
            }

            logger.info("CF Access policy {} email={}", add ? "added" : "removed", email);
            return SyncResult.ok();
        } catch (Exception e) {
            logger.warn("CfAccessPolicy sync failed: {}", e.getMessage());
            return SyncResult.error("CF API 呼び出しに失敗しました: " + e.getMessage());
        }
    }

    /** include 配列中の {"email":{"email":x}} エントリを小文字比較で探す。見つからなければ -1。 */
    private static int findEmailIndex(ArrayNode include, String email) {
        for (int i = 0; i < include.size(); i++) {
            JsonNode emailNode = include.get(i).path("email").path("email");
            if (emailNode.isTextual() && emailNode.asText().trim().equalsIgnoreCase(email)) {
                return i;
            }
        }
        return -1;
    }

    private static String extractErrorMessage(JsonNode root, String fallback) {
        JsonNode errors = root.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode err : errors) {
                String msg = err.path("message").asText("");
                if (!msg.isEmpty()) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(msg);
                }
            }
            if (sb.length() > 0) return sb.toString();
        }
        return fallback;
    }

    private static URI policyUri() {
        return URI.create("https://api.cloudflare.com/client/v4/accounts/" + ACCOUNT_ID
                + "/access/policies/" + POLICY_ID);
    }

    private static HttpRequest getRequest() {
        return HttpRequest.newBuilder()
                .uri(policyUri())
                .header("Authorization", "Bearer " + API_TOKEN)
                .timeout(TIMEOUT)
                .GET()
                .build();
    }

    private static HttpRequest putRequest(String body) {
        return HttpRequest.newBuilder()
                .uri(policyUri())
                .header("Authorization", "Bearer " + API_TOKEN)
                .header("Content-Type", "application/json")
                .timeout(TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
