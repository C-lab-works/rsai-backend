package dev.gate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DiscordWebhook.shouldSend のデバウンスロジックの単体テスト（#11）。
 * ネットワーク呼び出しなし。
 */
public class DiscordWebhookDebounceTest {

    /**
     * 1 秒間隔で 15 回呼ぶと、t=0, t=5000, t=10000 の 3 回だけ許可される。
     * DEBOUNCE_MS = 5000ms。
     */
    @Test
    void allowsExactlyThreeCallsAt1sIntervalsOver15s() {
        // テスト固有のキーを使って他のテストと干渉しない
        String key = "test-debounce-" + System.nanoTime();
        // 本番の now は System.currentTimeMillis() であり初期値 0L から十分離れているため、
        // テストでも現実的な基準時刻から開始する（base=0 だと初回が初期値 0L との差で拒否される）
        long base = 1_000_000L;
        int allowed = 0;
        for (int i = 0; i < 15; i++) {
            long now = base + (long) i * 1_000; // +0ms, +1000ms, ..., +14000ms
            if (DiscordWebhook.shouldSend(key, now)) {
                allowed++;
            }
        }
        // t=0 (許可), t=1000..4000 (拒否), t=5000 (許可), t=6000..9000 (拒否),
        // t=10000 (許可), t=11000..14000 (拒否) → 3 回
        assertEquals(3, allowed, "1s 間隔 15 回のうち t=0,5,10 の 3 回だけ許可されるべき");
    }
}
