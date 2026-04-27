package dev.gate;

import dev.gate.core.Context;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

public class RequestMetrics {
    private static final int HOURS       = 24;
    private static final int SAMPLE_SIZE = 1000;
    private static final int MAX_KEYS    = 100;
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

    // --- before filter: record start time ---

    public void startTimer(Context ctx) {
        requestStart.set(System.nanoTime());
    }

    // --- after filter: record metrics ---

    public void record(Context ctx) {
        // Exclude admin endpoints from public-facing metrics
        if (ctx.path().startsWith("/admin")) {
            requestStart.remove();
            return;
        }

        totalRequests.increment();
        if (ctx.statusCode() >= 500) errorCount.increment();

        // hourly slot
        long hour = epochHour();
        int slot = (int)(hour % HOURS);
        synchronized (slotLocks[slot]) {
            if (slotHour[slot] != hour) {
                hourlyCounts[slot].set(1);
                slotHour[slot] = hour;
            } else {
                hourlyCounts[slot].incrementAndGet();
            }
        }

        // response time sample
        Long start = requestStart.get();
        if (start != null) {
            long ms = (System.nanoTime() - start) / 1_000_000L;
            requestStart.remove();
            int pos = samplePos.getAndUpdate(p -> (p + 1) % SAMPLE_SIZE);
            responseSamples[pos] = ms;
        }

        // endpoint count (cap map size to avoid unbounded growth from path params)
        String key = ctx.method().toUpperCase() + " " + ctx.path();
        if (endpointCounts.size() < MAX_KEYS) {
            endpointCounts.computeIfAbsent(key, k -> new LongAdder()).increment();
        } else {
            endpointCounts.computeIfPresent(key, (k, v) -> { v.increment(); return v; });
        }
    }

    // --- read methods for /admin/stats ---

    public long getTotalRequests() { return totalRequests.sum(); }
    public long getErrorCount()    { return errorCount.sum(); }

    public double getErrorRate() {
        long total = getTotalRequests();
        return total == 0 ? 0.0 : (getErrorCount() * 100.0) / total;
    }

    /** Returns counts for the last 24 hours, oldest first. */
    public long[] getHourlyCounts() {
        long currentHour = epochHour();
        long[] result = new long[HOURS];
        for (int i = 0; i < HOURS; i++) {
            long targetHour = currentHour - (HOURS - 1) + i;
            int s = (int)(targetHour % HOURS);
            if (s < 0) s += HOURS;
            synchronized (slotLocks[s]) {
                result[i] = slotHour[s] == targetHour ? hourlyCounts[s].get() : 0L;
            }
        }
        return result;
    }

    /** Returns {p50, p95} in milliseconds. */
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

    /** Returns top N endpoints sorted by request count descending. */
    public List<Map.Entry<String, Long>> getTopEndpoints(int n) {
        return endpointCounts.entrySet().stream()
            .map(e -> Map.entry(e.getKey(), e.getValue().sum()))
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .limit(n)
            .collect(Collectors.toList());
    }
}
