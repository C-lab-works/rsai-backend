package dev.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Logger;
import dev.gate.mapping.DeleteMapping;
import dev.gate.mapping.PostMapping;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * CF Access ポリシー(Allow ルール)へのメール追加・削除を管理者パネルから受け付けるコントローラ。
 *
 * <p>CF API トークンは Gate 側(この裏の {@link CfAccessPolicy})のみが保持する。
 * パネル(rsai2026admin)は X-API-Key + X-Service-Key の M2M 認証で本エンドポイントを呼ぶ。
 * 認証自体は /admin/* ミドルウェア(ApiKeyAuth + CfAccessAuth)で既に強制されるため、
 * 本クラスでは扱わない。</p>
 */
@GateController
public class AdminAccessPolicyController {

    private static final Logger       logger = new Logger(AdminAccessPolicyController.class);
    private static final ObjectMapper mapper = dev.gate.core.Json.MAPPER;
    // 簡易メール形式検証。厳密な RFC5322 準拠はせず、明らかに不正な入力のみ弾く。
    // ドメインラベルを `.` で明示的に区切ることで、`[^\s@]+` 同士が `.` の位置を
    // 奪い合う曖昧性(ReDoS の原因になる多項式バックトラッキング)を排除している。
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@([^\\s@.]+\\.)+[^\\s@.]+$");

    @PostMapping("/admin/access-policy/emails")
    public void addEmail(Context ctx) {
        String email;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(ctx.body(), Map.class);
            Object val = body.get("email");
            if (!(val instanceof String)) {
                ctx.status(400).json(Map.of("error", "email フィールドは string 必須"));
                return;
            }
            email = normalize((String) val);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON body"));
            return;
        }
        if (!isValidEmail(email)) {
            ctx.status(400).json(Map.of("error", "email 形式が不正です"));
            return;
        }

        String caller = ctx.getAttribute(CfAccessAuth.ATTR_VERIFIED_EMAIL);
        CfAccessPolicy.SyncResult result = CfAccessPolicy.addEmail(email);
        logger.info("access-policy add email={} status={} by={}", email, result.status(), caller);
        respond(ctx, result);
    }

    @DeleteMapping("/admin/access-policy/emails/{email}")
    public void removeEmail(Context ctx) {
        String email = normalize(ctx.pathParam("email"));
        if (!isValidEmail(email)) {
            ctx.status(400).json(Map.of("error", "email 形式が不正です"));
            return;
        }

        String caller = ctx.getAttribute(CfAccessAuth.ATTR_VERIFIED_EMAIL);

        // Gate 側の多層防御: パネル側の保護判定(feature_access 残存 + ADMIN/OWNER)に加え、
        // ここでも admin/owner メールは削除させない。
        if (FeatureAccessStore.isAdminOrOwner(email)) {
            logger.info("access-policy remove protected email={} by={}", email, caller);
            ctx.json(Map.of("status", "protected"));
            return;
        }

        CfAccessPolicy.SyncResult result = CfAccessPolicy.removeEmail(email);
        logger.info("access-policy remove email={} status={} by={}", email, result.status(), caller);
        respond(ctx, result);
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase();
    }

    private static boolean isValidEmail(String email) {
        return !email.isEmpty() && email.length() <= 320 && EMAIL_PATTERN.matcher(email).matches();
    }

    private static void respond(Context ctx, CfAccessPolicy.SyncResult result) {
        switch (result.status()) {
            case OK -> ctx.json(Map.of("status", "ok"));
            case SKIPPED -> ctx.json(Map.of("status", "skipped", "reason", result.message()));
            case ERROR -> ctx.status(502).json(Map.of("error", result.message()));
        }
    }
}
