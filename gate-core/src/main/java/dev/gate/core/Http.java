package dev.gate.core;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * アプリ全体で共有する単一の {@link HttpClient}。
 *
 * <p>従来は各クラスが個別に {@code HttpClient.newHttpClient()} を保持しており、
 * それぞれが専用のセレクタ／コネクションプール（GraalVM Native Image では常駐スレッド）を
 * 抱えていた。挙動はリクエスト単位の {@code HttpRequest.timeout(...)} で個別に制御できるため、
 * クライアント自体は1インスタンスを共有すれば十分。リソース（スレッド・コネクション）を節約する。</p>
 *
 * <p>{@link HttpClient} はスレッドセーフであり、複数スレッドからの同時利用が可能。</p>
 */
public final class Http {

    /** 接続確立のデフォルト上限。各呼び出しは {@code HttpRequest.timeout} で読み取り上限を別途指定する。 */
    public static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private Http() {}
}
