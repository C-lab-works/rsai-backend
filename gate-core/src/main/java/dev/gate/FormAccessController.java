package dev.gate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Logger;
import dev.gate.mapping.PostMapping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Google Form(GAS 経由)からの混雑度/遅延アクセス許可メール自動登録エンドポイント。
 *
 * <p>認証はグローバルの X-API-Key ではなく専用の {@code FORM_API_KEY} のみで完結する
 * ({@link ApiKeyAuth} 側でこのパスを明示的に除外している)。CF Access ポリシー同期は
 * 行わない(feature_access テーブルへの登録のみ)。将来同期したくなった場合は
 * {@code CfAccessPolicy.addEmail(email)} を呼ぶ1行を追加するだけで拡張できる。</p>
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

        ctx.json(Map.of("ok", true, "email", email));

        final String finalEmail = email;
        Main.bg.execute(() ->
                AuditLog.write(ADDED_BY, "ADD_FEATURE_EMAIL", "congestion,delays", finalEmail, "ok", null));
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
    }
}
