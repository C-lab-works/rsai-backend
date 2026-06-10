package dev.gate;

import dev.gate.core.Logger;
import dev.gate.core.YamlRouteLoader;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 書き込み起点のキャッシュ全同期(オリジン再構築 + 他インスタンス broadcast + CF purge)を
 * コアレッシング付きで非同期実行するヘルパー。
 *
 * <p>エッジ TTL を 5 分に延ばした前提では、管理画面でデータを編集した後に
 * キャッシュクリアを押し忘れると最大 5 分 stale が公開され続ける。
 * そのため AdminController の書き込み系エンドポイント成功時に必ずここを通す。</p>
 *
 * <p>一括インポート等の連続編集で refresh/purge が連打されないよう、
 * {@link #COALESCE_MILLIS} の窓内の要求は 1 回にまとめる。実行直前にフラグを
 * 解除するため、同期実行中に発生した編集は取りこぼさず次回の同期に乗る。</p>
 */
public final class CacheSync {

    private static final Logger logger = new Logger(CacheSync.class);
    static final long COALESCE_MILLIS = 3_000L;

    private final ScheduledExecutorService scheduler;
    private final long coalesceMillis;
    private final Runnable syncAction;
    private final AtomicBoolean scheduled = new AtomicBoolean(false);

    /** テスト用にスケジューラ・窓幅・同期処理を注入できるコンストラクタ。 */
    CacheSync(ScheduledExecutorService scheduler, long coalesceMillis, Runnable syncAction) {
        this.scheduler = scheduler;
        this.coalesceMillis = coalesceMillis;
        this.syncAction = syncAction;
    }

    // Main.bg へのアクセスを初回利用時まで遅延させる(テストで Main を起動せずに済む)
    private static final class Holder {
        static final CacheSync INSTANCE = new CacheSync(Main.bg, COALESCE_MILLIS, CacheSync::defaultSync);
    }

    /** 書き込み成功後に呼ぶ。コアレッシング窓内の重複要求は 1 回にまとめられる。 */
    public static void scheduleFullSync(String trigger) {
        Holder.INSTANCE.schedule(trigger);
    }

    void schedule(String trigger) {
        if (scheduled.compareAndSet(false, true)) {
            logger.info("cache full-sync scheduled (trigger={})", trigger);
            scheduler.schedule(this::runSync, coalesceMillis, TimeUnit.MILLISECONDS);
        }
        // 既にスケジュール済み: 窓内のためコアレッシング(何もしない)
    }

    private void runSync() {
        // 実行前に解除する: 同期中に来た編集は次の schedule で新たに同期される
        scheduled.set(false);
        try {
            syncAction.run();
        } catch (Exception e) {
            logger.error("cache full-sync failed: {}", e.getMessage(), e);
        }
    }

    /** 本番の同期処理: 全キャッシュ再構築 → broadcast → CF purge。個別失敗は他を妨げない。 */
    private static void defaultSync() {
        refreshQuietly("data", () -> new DataController().refreshAll());
        refreshQuietly("announcements", AnnouncementsController::refreshCache);
        refreshQuietly("congestion", CongestionController::refreshCache);
        refreshQuietly("yaml", YamlRouteLoader::refreshAll);
        InstanceManager.get().broadcastCacheRefresh();
        CfPurge.purgeEverythingAsync();
        logger.info("cache full-sync done");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void refreshQuietly(String what, ThrowingRunnable r) {
        try {
            r.run();
        } catch (Exception e) {
            logger.warn("full-sync {} refresh failed: {}", what, e.getMessage());
        }
    }
}
