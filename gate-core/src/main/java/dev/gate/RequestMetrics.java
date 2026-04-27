package dev.gate;

import dev.gate.core.Context;
import dev.gate.core.Database;
import dev.gate.core.Logger;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

public class RequestMetrics {
    private static final Logger logger      = new Logger(RequestMetrics.class);
    private static final int    HOURS       = 24;
    private static final int    SAMPLE_SIZE = 1000;
    private static final int    MAX_KEYS    = 100;
    private static final RequestMetrics INSTANCE = new RequestMetrics();

    private final AtomicLong[] hourlyCounts = new AtomicLong[HOURS];
    private final long[]       slotHour     = new long[HOURS];
    private final Object[]     slotLocks    = new Object[HOURS];

    private final ConcurrentHashMap<String, LongAdder> endpointCounts = new ConcurrentHashMap<>();
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder errorCount    = new LongAdder();

    private final long[]        responseSamples = new long[SAMPLE_SIZE];
    private final AtomicInteger samplePos       = new AtomicInteger(0);

    private final ThreadLocal<Long> requestStart = new ThreadLocal<>();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "metrics-flush");
                t.setDaemon(true);
                return t;
            });

    private RequestMetrics() {
        long currentHour = epochHour();
        for (int i = 0; i < HOURS; i++) {
            hourlyCounts[i] = new AtomicLong(0);
            slotHour[i]     = currentHour - (HOURS - 1 - i);
            slotLocks[i]    = new Object();
        }
    }

    public static RequestMetrics get() { return INSTANCE; }

    private long epochHour() { return System.currentTimeMillis() / 3_600_000L; }

    // ── persistence ───────────────────────────────────────────────────────────

    /** Loads persisted metrics from DB and starts the periodic flush scheduler. */
    public void init() {
        loadFromDb();
        scheduler.scheduleAtFixedRate(this::flushAll, 5, 5, TimeUnit.MINUTES);
        logger.info("RequestMetrics persistence enabled (5-min flush)");
    }

    /** Performs a final flush and stops the scheduler on graceful shutdown. */
    public void shutdown() {
        scheduler.shutdown();
        flushAll();
        logger.info("RequestMetrics final flush complete");
    }

    private void loadFromDb() {
        try (Connection conn = Database.getConnection()) {
            long since = epochHour() - (HOURS - 1);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT hour, requests FROM metrics_hourly WHERE hour >= ?")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long h   = rs.getLong("hour");
                        long req = rs.getLong("requests");
                        int  s   = (int)(h % HOURS);
                        if (s < 0) s += HOURS;
                        synchronized (slotLocks[s]) {
                            slotHour[s] = h;
                            hourlyCounts[s].set(req);
                        }
                    }
                }
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT endpoint, hits FROM metrics_endpoints")) {
                while (rs.next()) {
                    String ep   = rs.getString("endpoint");
                    long   hits = rs.getLong("hits");
                    if (endpointCounts.size() < MAX_KEYS) {
                        endpointCounts.computeIfAbsent(ep, k -> new LongAdder()).add(hits);
                    }
                }
            }
            logger.info("RequestMetrics: restored from DB");
        } catch (Exception e) {
            logger.warn("RequestMetrics: DB restore failed (starting fresh): {}", e.getMessage());
        }
    }

    private void flushAll() {
        flushHourly();
        flushEndpoints();
    }

    private void flushHourly() {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO metrics_hourly(hour, requests) VALUES(?, ?) AS new " +
                     "ON DUPLICATE KEY UPDATE requests = GREATEST(requests, new.requests)")) {
            long currentHour = epochHour();
            for (int i = 0; i < HOURS; i++) {
                long targetHour = currentHour - (HOURS - 1) + i;
                int  s          = (int)(targetHour % HOURS);
                if (s < 0) s += HOURS;
                long count;
                synchronized (slotLocks[s]) {
                    count = slotHour[s] == targetHour ? hourlyCounts[s].get() : 0L;
                }
                if (count > 0) {
                    ps.setLong(1, targetHour);
                    ps.setLong(2, count);
                    ps.addBatch();
                }
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
                     "INSERT INTO metrics_endpoints(endpoint, hits) VALUES(?, ?) AS new " +
                     "ON DUPLICATE KEY UPDATE hits = GREATEST(hits, new.hits)")) {
            for (Map.Entry<String, LongAdder> e : endpointCounts.entrySet()) {
                ps.setString(1, e.getKey());
                ps.setLong(2, e.getValue().sum());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            logger.warn("metrics endpoints flush failed: {}", e.getMessage());
        }
    }

    // ── before filter: record start time ─────────────────────────────────────

    public void startTimer(Context ctx) {
        requestStart.set(System.nanoTime());
    }

    // ── after filter: record metrics ──────────────────────────────────────────

    public void record(Context ctx) {
        if (ctx.path().startsWith("/admin")) {
            requestStart.remove();
            return;
        }

        totalRequests.increment();
        if (ctx.statusCode() >= 500) errorCount.increment();

        long hour = epochHour();
        int  slot = (int)(hour % HOURS);
        synchronized (slotLocks[slot]) {
            if (slotHour[slot] != hour) {
                hourlyCounts[slot].set(1);
                slotHour[slot] = hour;
            } else {
                hourlyCounts[slot].incrementAndGet();
            }
        }

        Long start = requestStart.get();
        if (start != null) {
            long ms = (System.nanoTime() - start) / 1_000_000L;
            requestStart.remove();
            int pos = samplePos.getAndUpdate(p -> (p + 1) % SAMPLE_SIZE);
            responseSamples[pos] = ms;
        }

        String key = ctx.method().toUpperCase() + " " + ctx.path();
        if (endpointCounts.size() < MAX_KEYS) {
            endpointCounts.computeIfAbsent(key, k -> new LongAdder()).increment();
        } else {
            endpointCounts.computeIfPresent(key, (k, v) -> { v.increment(); return v; });
        }
    }

    // ── read methods ──────────────────────────────────────────────────────────

    public long getTotalRequests() { return totalRequests.sum(); }
    public long getErrorCount()    { return errorCount.sum(); }

    public double getErrorRate() {
        long total = getTotalRequests();
        return total == 0 ? 0.0 : (getErrorCount() * 100.0) / total;
    }

    /** Returns request counts for the last 24 hours, oldest slot first. */
    public long[] getHourlyCounts() {
        long currentHour = epochHour();
        long[] result = new long[HOURS];
        for (int i = 0; i < HOURS; i++) {
            long targetHour = currentHour - (HOURS - 1) + i;
            int  s          = (int)(targetHour % HOURS);
            if (s < 0) s += HOURS;
            synchronized (slotLocks[s]) {
                result[i] = slotHour[s] == targetHour ? hourlyCounts[s].get() : 0L;
            }
        }
        return result;
    }

    /** Returns {@code [p50ms, p95ms]} over the last {@value #SAMPLE_SIZE} requests. */
    public long[] getPercentiles() {
        long[] samples = Arrays.copyOf(responseSamples, SAMPLE_SIZE);
        Arrays.sort(samples);
        int start = 0;
        while (start < samples.length && samples[start] == 0) start++;
        int count = samples.length - start;
        if (count == 0) return new long[]{0, 0};
        long p50 = samples[start + (int)(count * 0.50)];
        long p95 = samples[start + Math.min((int)(count * 0.95), count - 1)];
        return new long[]{p50, p95};
    }

    /** Returns the top {@code n} endpoints sorted by hit count descending. */
    public List<Map.Entry<String, Long>> getTopEndpoints(int n) {
        return endpointCounts.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().sum()))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(n)
                .collect(Collectors.toList());
    }
}
