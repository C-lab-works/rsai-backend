package dev.gate.core;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * シンプルな TTL 付きインメモリキャッシュ。
 *
 * <p>設計上の注意:
 * <ul>
 *   <li>ロックを保持したままローダーを呼び出さない。競合時に同一キーのロードが複数スレッドで
 *       重複実行される可能性（racy double-load）があるが、これは許容する。
 *       管理パネル向けメトリクスキャッシュのような低頻度・副作用なしの用途に適している。</li>
 *   <li>ローダーが例外を投げたとき、古いエントリが存在すればそれを返す（stale-on-error）。
 *       古いエントリもなければ例外を再スローする。</li>
 *   <li>GraalVM native-image の reflect-config 手動登録リスクを避けるため Caffeine を使わない。</li>
 * </ul>
 *
 * @param <V> キャッシュする値の型
 */
public class TtlCache<V> {

    private final long          ttlMillis;
    private final LongSupplier  clock;
    private final ConcurrentHashMap<String, Entry<V>> store = new ConcurrentHashMap<>();

    /** エントリ: 値とロード時刻を保持する。 */
    private record Entry<V>(V value, long loadedAtMillis) {}

    /**
     * @param ttlMillis キャッシュ有効時間（ミリ秒）
     * @param clock     現在時刻を返すサプライヤー（テスト時に差し替え可能）
     */
    public TtlCache(long ttlMillis, LongSupplier clock) {
        this.ttlMillis = ttlMillis;
        this.clock     = clock;
    }

    /**
     * システムクロックを使用する便利コンストラクタ。
     *
     * @param ttlMillis キャッシュ有効時間（ミリ秒）
     */
    public TtlCache(long ttlMillis) {
        this(ttlMillis, System::currentTimeMillis);
    }

    /**
     * キャッシュから値を取得する。TTL 内の新鮮なエントリがあればそれを返す。
     * 期限切れまたは未ロードの場合は {@code loader} を呼び出して値を更新する。
     *
     * <p>ローダーが例外を投げた場合:
     * <ul>
     *   <li>古いエントリが存在する → 古い値をそのまま返す（stale-on-error）</li>
     *   <li>古いエントリがない → 例外を再スローする</li>
     * </ul>
     *
     * @param key    キャッシュキー
     * @param loader 値が存在しないまたは期限切れの場合に呼ばれるローダー
     * @return キャッシュされた値、またはローダーが返した値
     * @throws Exception ローダーが例外を投げ、かつ古いエントリもない場合
     */
    public V get(String key, Callable<V> loader) throws Exception {
        long now = clock.getAsLong();
        Entry<V> existing = store.get(key);
        if (existing != null && (now - existing.loadedAtMillis()) < ttlMillis) {
            return existing.value();
        }

        // TTL 切れ or 未ロード: ロードを試みる（ロックなし — 競合時は重複ロードを許容）
        try {
            V value = loader.call();
            store.put(key, new Entry<>(value, clock.getAsLong()));
            return value;
        } catch (Exception e) {
            // stale-on-error: 古いエントリがあれば返す
            if (existing != null) {
                return existing.value();
            }
            throw e;
        }
    }
}
