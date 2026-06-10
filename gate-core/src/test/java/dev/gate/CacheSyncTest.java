package dev.gate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CacheSync のコアレッシング挙動のユニットテスト(スケジューラ・同期処理を注入)。 */
public class CacheSyncTest {

    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cache-sync-test");
                t.setDaemon(true);
                return t;
            });

    @AfterAll
    static void shutdown() {
        scheduler.shutdownNow();
    }

    @Test
    void burstWithinWindowCoalescesIntoSingleSync() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1);
        CacheSync sync = new CacheSync(scheduler, 150, () -> {
            runs.incrementAndGet();
            first.countDown();
        });

        for (int i = 0; i < 5; i++) {
            sync.schedule("burst-" + i);
        }

        assertTrue(first.await(2, TimeUnit.SECONDS), "sync should run once after the window");
        Thread.sleep(300); // 余分な実行が無いことを確認する余白
        assertEquals(1, runs.get(), "5 schedules within the window must coalesce to 1 run");
    }

    @Test
    void schedulesAgainAfterPreviousRunCompleted() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CacheSync sync = new CacheSync(scheduler, 80, runs::incrementAndGet);

        sync.schedule("first");
        assertTrue(waitFor(() -> runs.get() == 1, 2_000), "first sync should run");

        sync.schedule("second");
        assertTrue(waitFor(() -> runs.get() == 2, 2_000), "later schedule must trigger a new sync");
    }

    @Test
    void syncFailureDoesNotBreakSubsequentScheduling() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CacheSync sync = new CacheSync(scheduler, 50, () -> {
            if (runs.incrementAndGet() == 1) {
                throw new RuntimeException("boom");
            }
        });

        sync.schedule("fail");
        assertTrue(waitFor(() -> runs.get() == 1, 2_000), "failing sync should still run");

        sync.schedule("retry");
        assertTrue(waitFor(() -> runs.get() == 2, 2_000), "failure must not block the next sync");
    }

    private static boolean waitFor(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return cond.getAsBoolean();
    }
}
