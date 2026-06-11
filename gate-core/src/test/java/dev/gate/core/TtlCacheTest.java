package dev.gate.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TtlCache の単体テスト。
 * フェイククロック（可変 long）を注入して時間経過をシミュレートする。
 */
public class TtlCacheTest {

    private static final long TTL_MS = 1000L;

    /** フェイククロック: テストが任意に進められる。 */
    private long fakeNow = 0L;

    private TtlCache<String> newCache() {
        return new TtlCache<>(TTL_MS, () -> fakeNow);
    }

    // TTL 内のキャッシュヒット: ローダーは 1 回だけ呼ばれる
    @Test
    void freshHitDoesNotCallLoaderAgain() throws Exception {
        TtlCache<String> cache = newCache();
        AtomicInteger calls = new AtomicInteger();

        String first  = cache.get("k", () -> { calls.incrementAndGet(); return "v1"; });
        fakeNow = TTL_MS - 1;  // まだ TTL 内
        String second = cache.get("k", () -> { calls.incrementAndGet(); return "v2"; });

        assertEquals("v1", first);
        assertEquals("v1", second);
        assertEquals(1, calls.get());
    }

    // TTL 経過後はローダーが再度呼ばれる
    @Test
    void expiredEntryTriggersReload() throws Exception {
        TtlCache<String> cache = newCache();
        AtomicInteger calls = new AtomicInteger();

        cache.get("k", () -> { calls.incrementAndGet(); return "old"; });
        fakeNow = TTL_MS;  // ちょうど期限切れ
        String reloaded = cache.get("k", () -> { calls.incrementAndGet(); return "new"; });

        assertEquals("new", reloaded);
        assertEquals(2, calls.get());
    }

    // ローダーが失敗しても古いエントリがあれば古い値を返す（stale-on-error）
    @Test
    void loaderFailureWithStaleEntryReturnsStale() throws Exception {
        TtlCache<String> cache = newCache();

        cache.get("k", () -> "stale-value");
        fakeNow = TTL_MS;  // 期限切れにする

        String result = cache.get("k", () -> { throw new RuntimeException("upstream down"); });

        assertEquals("stale-value", result);
    }

    // ローダーが失敗してエントリが存在しない場合は例外を再スローする
    @Test
    void loaderFailureWithNoEntryPropagatesException() {
        TtlCache<String> cache = newCache();

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> cache.get("k", () -> { throw new RuntimeException("no data"); }));

        assertEquals("no data", ex.getMessage());
    }
}
