package dev.gate;

import dev.gate.core.Context;
import dev.gate.core.Database;
import dev.gate.core.Logger;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class RequestMetrics {
    private static final Logger logger      = new Logger(RequestMetrics.class);
    private static final int    HOURS       = 24;
    private static final int    MAX_KEYS    = 100;
    private static final int[]  BUCKETS     = {0, 10, 25, 50, 100, 200, 500, 1000, 2000, 5000};
    private static final RequestMetrics INSTANCE = new RequestMetrics();

    // 時間別リングバッファ
    private final AtomicLong[]    hourlyCounts   = new AtomicLong[HOURS];
    private final AtomicLong[]    hourlyErrors   = new AtomicLong[HOURS];
    private final long[]          slotHour       = new long[HOURS];
    // ReentrantLockを使う。Java 21 のVirtual Threadで synchronized を使うと
    // キャリアスレッドがピン留めされるため、高並列時にスループットが落ちる。
    private final ReentrantLock[] slotLocks      = new ReentrantLock[HOURS];
    private final long[]          lastFlushedReq = new long[HOURS];
    private final long[]          lastFlushedErr = new long[HOURS];

    // エンドポイント集計
    private final ConcurrentHashMap<String, LongAdder> endpointCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> lastFlushedEp  = new ConcurrentHashMap<>();

    // レイテンシヒストグラム
    private final AtomicLong[] histogram        = new AtomicLong[BUCKETS.length];
    private final AtomicLong[] lastFlushedHisto = new AtomicLong[BUCKETS.length];

    // 開始時刻は Context attribute に保持する。
    // 以前は ThreadLocal を使っていたが、Java 21 Virtual Thread では各 VT ごとに
    // 独立した TL スロットを保持するため、7000 req/s 規模の同時実行時に余分なメモリ/オーバーヘッドが発生する。
    // Context は 1 リクエスト 1 インスタンスなので attribute で十分かつ低コスト。
    private static final String START_NANOS_ATTR = "_req_start_nanos";

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "metrics-flush");
                t.setDaemon(true);
                return t;
            });

    private RequestMetrics() {
        long currentHour = epochHour();
        for (int i = 0; i < HOURS; i++) {
            hourlyCounts[i]   = new AtomicLong(0);
            hourlyErrors[i]   = new AtomicLong(0);
            slotHour[i]       = currentHour - (HOURS - 1 - i);
            slotLocks[i]      = new ReentrantLock();
        }
        for (int i = 0; i < BUCKETS.length; i++) {
            histogram[i]        = new AtomicLong(0);
            lastFlushedHisto[i] = new AtomicLong(0);
        }
    }

    public static RequestMetrics get() { return INSTANCE; }

    private long epochHour() { return System.currentTimeMillis() / 3_600_000L; }

    // 永続化

    public void init() {
        loadFromDb();
        scheduler.scheduleAtFixedRate(this::flushAll, 45, 45, TimeUnit.SECONDS);
        logger.info("RequestMetrics永続化有効（45秒ごとにフラッシュ）");
    }

    public void shutdown() {
        scheduler.shutdown();
        flushAll();
        logger.info("RequestMetrics最終フラッシュ完了");
    }

    private void loadFromDb() {
        try (Connection conn = Database.getConnection()) {
            long since = epochHour() - (HOURS - 1);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT hour, requests, errors FROM metrics_hourly WHERE hour >= ?")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long h   = rs.getLong("hour");
                        long req = rs.getLong("requests");
                        long err = rs.getLong("errors");
                        int  s   = (int)(h % HOURS);
                        if (s < 0) s += HOURS;
                        slotLocks[s].lock();
                        try {
                            slotHour[s] = h;
                            hourlyCounts[s].set(req);
                            hourlyErrors[s].set(err);
                            lastFlushedReq[s] = req;
                            lastFlushedErr[s] = err;
                        } finally {
                            slotLocks[s].unlock();
                        }
                    }
                }
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT endpoint, hits FROM metrics_endpoints WHERE date = CURDATE()")) {
                while (rs.next()) {
                    String ep   = rs.getString("endpoint");
                    long   hits = rs.getLong("hits");
                    if (endpointCounts.size() < MAX_KEYS) {
                        LongAdder la = endpointCounts.computeIfAbsent(ep, k -> new LongAdder());
                        la.add(hits);
                        lastFlushedEp.computeIfAbsent(ep, k -> new LongAdder()).add(hits);
                    }
                }
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT bucket_ms, count FROM metrics_latency_histogram ORDER BY bucket_ms")) {
                while (rs.next()) {
                    int  bms   = rs.getInt("bucket_ms");
                    long count = rs.getLong("count");
                    int  idx   = bucketIndex(bms);
                    if (idx >= 0) {
                        histogram[idx].set(count);
                        lastFlushedHisto[idx].set(count);
                    }
                }
            }
            logger.info("RequestMetrics: DBから復元完了");
        } catch (Exception e) {
            logger.warn("RequestMetrics: DB復元失敗（初期値で起動）: {}", e.getMessage());
        }
    }

    private void flushAll() {
        flushHourly();
        flushEndpoints();
        flushHistogram();
    }

    private void flushHourly() {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO metrics_hourly(hour, requests, errors) VALUES(?, ?, ?) AS new " +
                     "ON DUPLICATE KEY UPDATE " +
                     "requests = metrics_hourly.requests + new.requests, " +
                     "errors   = metrics_hourly.errors   + new.errors")) {
            long currentHour = epochHour();
            for (int i = 0; i < HOURS; i++) {
                long targetHour = currentHour - (HOURS - 1) + i;
                int  s          = (int)(targetHour % HOURS);
                if (s < 0) s += HOURS;
                long req, err, diffReq, diffErr;
                slotLocks[s].lock();
                try {
                    if (slotHour[s] != targetHour) continue;
                    req     = hourlyCounts[s].get();
                    err     = hourlyErrors[s].get();
                    diffReq = req - lastFlushedReq[s];
                    diffErr = err - lastFlushedErr[s];
                    if (diffReq <= 0 && diffErr <= 0) continue;
                    lastFlushedReq[s] = req;
                    lastFlushedErr[s] = err;
                } finally {
                    slotLocks[s].unlock();
                }
                ps.setLong(1, targetHour);
                ps.setLong(2, diffReq > 0 ? diffReq : 0);
                ps.setLong(3, diffErr > 0 ? diffErr : 0);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            logger.warn("metrics hourly flush failed: {}", e.getMessage());
        }
    }

    private void flushEndpoints() {
        if (endpointCounts.isEmpty()) return;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO metrics_endpoints(endpoint, date, hits) VALUES(?, CURDATE(), ?) AS new " +
                     "ON DUPLICATE KEY UPDATE hits = metrics_endpoints.hits + new.hits")) {
            for (Map.Entry<String, LongAdder> e : endpointCounts.entrySet()) {
                long current = e.getValue().sum();
                long flushed = lastFlushedEp.computeIfAbsent(e.getKey(), k -> new LongAdder()).sum();
                long diff    = current - flushed;
                if (diff <= 0) continue;
                ps.setString(1, e.getKey());
                ps.setLong(2, diff);
                ps.addBatch();
                lastFlushedEp.get(e.getKey()).add(diff);
            }
            ps.executeBatch();
        } catch (Exception e) {
            logger.warn("metrics endpoints flush failed: {}", e.getMessage());
        }
    }

    private void flushHistogram() {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO metrics_latency_histogram(bucket_ms, count) VALUES(?, ?) AS new " +
                     "ON DUPLICATE KEY UPDATE count = metrics_latency_histogram.count + new.count")) {
            for (int i = 0; i < BUCKETS.length; i++) {
                long current = histogram[i].get();
                long flushed = lastFlushedHisto[i].get();
                long diff    = current - flushed;
                if (diff <= 0) continue;
                ps.setInt(1, BUCKETS[i]);
                ps.setLong(2, diff);
                ps.addBatch();
                lastFlushedHisto[i].addAndGet(diff);
            }
            ps.executeBatch();
        } catch (Exception e) {
            logger.warn("metrics histogram flush failed: {}", e.getMessage());
        }
    }

    // beforeフィルタ

    public void startTimer(Context ctx) {
        ctx.setAttribute(START_NANOS_ATTR, System.nanoTime());
    }

    // afterフィルタ

    public void record(Context ctx) {
        // /admin/* はスキップ（/admin/debug/* はカウント）
        String path = ctx.path();
        if (path.startsWith("/admin") && !path.startsWith("/admin/debug/")) {
            return;
        }

        boolean isError = ctx.statusCode() >= 500;

        long hour = epochHour();
        int  slot = (int)(hour % HOURS);
        slotLocks[slot].lock();
        try {
            if (slotHour[slot] != hour) {
                hourlyCounts[slot].set(1);
                hourlyErrors[slot].set(isError ? 1 : 0);
                slotHour[slot] = hour;
                lastFlushedReq[slot] = 0;
                lastFlushedErr[slot] = 0;
            } else {
                hourlyCounts[slot].incrementAndGet();
                if (isError) hourlyErrors[slot].incrementAndGet();
            }
        } finally {
            slotLocks[slot].unlock();
        }

        if ((isError || ctx.statusCode() == 429) && !"/health".equals(path)) {
            // ctx.responseBody() にJSONエラーメッセージが入っている
            DiscordWebhook.sendError(
                ctx.method(), path, ctx.statusCode(), ctx.responseBody());
        }

        Long start = ctx.getAttribute(START_NANOS_ATTR);
        if (start != null) {
            long ms  = (System.nanoTime() - start) / 1_000_000L;
            int idx = upperBucketIndex(ms);
            histogram[idx].incrementAndGet();
        }

        String key = ctx.method() + " " + path;
        if (endpointCounts.size() < MAX_KEYS) {
            endpointCounts.computeIfAbsent(key, k -> new LongAdder()).increment();
        } else {
            endpointCounts.computeIfPresent(key, (k, v) -> { v.increment(); return v; });
        }
    }

    // 読み取り（DB経由・マルチインスタンス対応）

    public long getTotalRequests() {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COALESCE(SUM(requests),0) FROM metrics_hourly WHERE hour >= ?")) {
            ps.setLong(1, epochHour() - (HOURS - 1));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            logger.warn("getTotalRequests DB error: {}", e.getMessage());
            return 0L;
        }
    }

    public long getErrorCount() {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COALESCE(SUM(errors),0) FROM metrics_hourly WHERE hour >= ?")) {
            ps.setLong(1, epochHour() - (HOURS - 1));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            logger.warn("getErrorCount DB error: {}", e.getMessage());
            return 0L;
        }
    }

    public double getErrorRate() {
        long total = getTotalRequests();
        long err   = getErrorCount();
        return total == 0 ? 0.0 : (err * 100.0) / total;
    }

    public long[] getHourlyCounts() {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT hour, requests FROM metrics_hourly WHERE hour >= ? ORDER BY hour ASC")) {
            long currentHour = epochHour();
            ps.setLong(1, currentHour - (HOURS - 1));
            long[] result = new long[HOURS];
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long h   = rs.getLong("hour");
                    int  idx = (int)(h - (currentHour - (HOURS - 1)));
                    if (idx >= 0 && idx < HOURS) result[idx] = rs.getLong("requests");
                }
            }
            return result;
        } catch (Exception e) {
            logger.warn("getHourlyCounts DB error: {}", e.getMessage());
            return new long[HOURS];
        }
    }

    public long[] getPercentiles() {
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT bucket_ms, count FROM metrics_latency_histogram ORDER BY bucket_ms")) {
            long[] counts = new long[BUCKETS.length];
            long total    = 0;
            while (rs.next()) {
                int idx = bucketIndex(rs.getInt("bucket_ms"));
                if (idx >= 0) { counts[idx] = rs.getLong("count"); total += counts[idx]; }
            }
            if (total == 0) return new long[]{0, 0};
            long p50 = percentileFromHistogram(counts, total, 50);
            long p95 = percentileFromHistogram(counts, total, 95);
            return new long[]{p50, p95};
        } catch (Exception e) {
            logger.warn("getPercentiles DB error: {}", e.getMessage());
            return new long[]{0, 0};
        }
    }

    private long percentileFromHistogram(long[] counts, long total, int pct) {
        long target = (long) Math.ceil(total * pct / 100.0);
        long cumul  = 0;
        for (int i = 0; i < BUCKETS.length; i++) {
            cumul += counts[i];
            if (cumul >= target) return BUCKETS[i];
        }
        return BUCKETS[BUCKETS.length - 1];
    }

    public List<Map.Entry<String, Long>> getTopEndpoints(int n) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT endpoint, SUM(hits) AS hits FROM metrics_endpoints " +
                     "GROUP BY endpoint ORDER BY SUM(hits) DESC LIMIT ?")) {
            ps.setInt(1, n);
            List<Map.Entry<String, Long>> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(Map.entry(rs.getString("endpoint"), rs.getLong("hits")));
            }
            return result;
        } catch (Exception e) {
            logger.warn("getTopEndpoints DB error: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<Map.Entry<String, Long>> getEndpointsByDate(String date) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT endpoint, hits FROM metrics_endpoints WHERE date = ? ORDER BY hits DESC")) {
            ps.setString(1, date);
            List<Map.Entry<String, Long>> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(Map.entry(rs.getString("endpoint"), rs.getLong("hits")));
            }
            return result;
        } catch (Exception e) {
            logger.warn("getEndpointsByDate DB error: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // バケットヘルパー

    private int upperBucketIndex(long ms) {
        for (int i = BUCKETS.length - 1; i >= 0; i--) {
            if (ms >= BUCKETS[i]) return i;
        }
        return 0;
    }

    private int bucketIndex(int bucketMs) {
        for (int i = 0; i < BUCKETS.length; i++) {
            if (BUCKETS[i] == bucketMs) return i;
        }
        return -1;
    }
}
