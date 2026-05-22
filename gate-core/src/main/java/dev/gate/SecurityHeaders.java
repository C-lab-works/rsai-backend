package dev.gate;

import dev.gate.core.Context;
import dev.gate.core.Handler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * すべてのレスポンスにセキュリティ関連HTTPヘッダーを付与するアフターフィルタ。
 * {@code gate.after(SecurityHeaders.get()::handle)} で登録する。
 *
 * 値はすべてコンパイル時定数のため、ホットパス（7000 req/s 想定）では
 * 個別の {@code ctx.header()} 呼び出しを避け、検証スキップ版の
 * {@link Context#addTrustedHeaders(Map)} で一括ポットする。
 */
public final class SecurityHeaders implements Handler {

    private static final SecurityHeaders INSTANCE = new SecurityHeaders();

    /**
     * すべて RFC7230 上 valid な ASCII 文字のみ。CR/LF を含まないことは目視確認済み。
     * 不変マップとしてキャッシュし、リクエストごとにマップ生成しない。
     */
    private static final Map<String, String> HEADERS;
    static {
        // LinkedHashMap で順序を安定させる（ログ/デバッグ時に再現性確保）。
        // initialCapacity を 8 に明示し、6 要素挿入時のリハッシュを防ぐ。
        Map<String, String> m = new LinkedHashMap<>(8);
        // クリックジャッキング対策（JSON APIだが多層防御として設定）
        m.put("X-Frame-Options", "DENY");
        // ブラウザのMIMEスニッフィングを無効化
        m.put("X-Content-Type-Options", "nosniff");
        // 1年間HTTPSのみを使用するよう指示
        m.put("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        // JSON APIのため全スクリプトを禁止
        m.put("Content-Security-Policy", "default-src 'none'");
        // このAPIから遷移する際のRefererヘッダーを抑制
        m.put("Referrer-Policy", "no-referrer");
        // APIに不要なブラウザ機能をすべて無効化
        m.put("Permissions-Policy", "geolocation=(), camera=(), microphone=()");
        HEADERS = java.util.Collections.unmodifiableMap(m);
    }

    private SecurityHeaders() {}

    public static SecurityHeaders get() {
        return INSTANCE;
    }

    @Override
    public void handle(Context ctx) {
        ctx.addTrustedHeaders(HEADERS);
    }
}
