package dev.gate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Logger;
import dev.gate.mapping.PostMapping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Google Form(GAS 経由)からの混雑度/遅延アクセス許可メール自動登録エンドポイント。
 *
 * <p>認証はグローバルの X-API-Key ではなく専用の {@code FORM_API_KEY} のみで完結する
 * ({@link ApiKeyAuth} 側でこのパスを明示的に除外している)。feature_access テーブルへの
 * 登録は常に行い、リクエストで {@code grantCfAccess: true} が指定された場合のみ
 * {@link CfAccessPolicy#addEmail(String)} を呼んで CF Access Allow ポリシーへも同期する
 * (ドメイン等に基づく許可判断は呼び出し元の GAS 側が行い、ここではフラグに従うのみ)。</p>
 */
@GateController
public class FormAccessController {

    private static final Logger logger = new Logger(FormAccessController.class);
    private static final String HEADER = "X-Form-Key";
    private static final String[] FEATURES = {"congestion", "delays"};
    private static final String ADDED_BY = "google-form";

    // AdminAccessPolicyController と同一の(ReDoS 耐性のある)簡易メール形式検証。
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@([^\\s@.]+\\.)+[^\\s@.]+$");

    @PostMapping("/form/feature-access")
    public void register(Context ctx) {
        byte[] expected = formKeyBytes();
        if (expected == null) {
            ctx.status(503).json(Map.of("error", "FORM_API_KEY is not configured"));
            return;
        }
        if (!constantEquals(ctx.requestHeader(HEADER), expected)) {
            ctx.status(403).json(Map.of("error", "Forbidden"));
            return;
        }

        FormAccessRequest req;
        try {
            req = ctx.bodyAs(FormAccessRequest.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON body"));
            return;
        }
        if (req == null || req.email == null) {
            ctx.status(400).json(Map.of("error", "email is required"));
            return;
        }

        String email = req.email.trim().toLowerCase();
        if (!isValidEmail(email)) {
            ctx.status(400).json(Map.of("error", "email 形式が不正です"));
            return;
        }

        try {
            for (String feature : FEATURES) {
                FeatureAccessStore.insertIgnore(feature, email, ADDED_BY);
            }
        } catch (SQLException e) {
            logger.error("FormAccessController: insertIgnore failed", e);
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable"));
            return;
        }

        // feature_access への登録は既に成功している。CF Access 同期はあくまで追加の
        // 便宜であり、その成否で feature_access 登録の成功可否を覆さない(DB が真実源)。
        String cfSync = null;
        if (Boolean.TRUE.equals(req.grantCfAccess)) {
            cfSync = cfSyncStatus(CfAccessPolicy.addEmail(email));
        }

        Map<String, Object> res = new HashMap<>();
        res.put("ok", true);
        res.put("email", email);
        res.put("cfSync", cfSync);
        ctx.json(res);

        final String finalEmail = email;
        final String finalCfSync = cfSync;
        Main.bg.execute(() -> AuditLog.write(
                ADDED_BY, "ADD_FEATURE_EMAIL", "congestion,delays",
                finalCfSync == null ? finalEmail : finalEmail + " cfSync=" + finalCfSync,
                "ok", null));
    }

    private static String cfSyncStatus(CfAccessPolicy.SyncResult result) {
        return switch (result.status()) {
            case OK -> "ok";
            case SKIPPED -> "skipped";
            case ERROR -> "error";
        };
    }

    private static boolean isValidEmail(String email) {
        return !email.isEmpty() && email.length() <= 320 && EMAIL_PATTERN.matcher(email).matches();
    }

    private static byte[] formKeyBytes() {
        String key = System.getenv("FORM_API_KEY");
        return (key == null || key.isBlank()) ? null : key.getBytes(StandardCharsets.UTF_8);
    }

    // ApiKeyAuth.constantEquals と同一実装(private のため複製。定数時間比較で早期リターンしない)。
    private static boolean constantEquals(String a, byte[] b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class FormAccessRequest {
        public String email;
        /** true の場合のみ CF Access Allow ポリシーへも同期する(省略時 false 扱い)。 */
        public Boolean grantCfAccess;
    }
}
