package dev.gate;

import dev.gate.core.Context;
import dev.gate.core.Handler;

/**
 * すべてのレスポンスにセキュリティ関連HTTPヘッダーを付与するアフターフィルタ。
 * {@code gate.after(SecurityHeaders.get()::handle)} で登録する。
 */
public final class SecurityHeaders implements Handler {

    private static final SecurityHeaders INSTANCE = new SecurityHeaders();

    private SecurityHeaders() {}

    public static SecurityHeaders get() {
        return INSTANCE;
    }

    @Override
    public void handle(Context ctx) {
        // クリックジャッキング対策（JSON APIだが多層防御として設定）
        ctx.header("X-Frame-Options", "DENY");

        // ブラウザのMIMEスニッフィングを無効化
        ctx.header("X-Content-Type-Options", "nosniff");

        // 1年間HTTPSのみを使用するよう指示
        ctx.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains");

        // JSON APIのため全スクリプトを禁止
        ctx.header("Content-Security-Policy", "default-src 'none'");

        // このAPIから遷移する際のRefererヘッダーを抑制
        ctx.header("Referrer-Policy", "no-referrer");

        // APIに不要なブラウザ機能をすべて無効化
        ctx.header("Permissions-Policy", "geolocation=(), camera=(), microphone=()");
    }
}
