package dev.gate.metrics;

import dev.gate.core.Context;
import dev.gate.core.Handler;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

/**
 * Lock-free request metrics collector. Tracks:
 * <ul>
 *   <li>Total request count and 5xx error rate</li>
 *   <li>Rolling 24-hour hourly request counts (ring buffer)</li>
 *   <li>p50 / p95 response-time percentiles (1 000-sample window)</li>
 *   <li>Per-endpoint hit counts (top-N)</li>
 * </ul>
 *
 * Register as a before- and after-filter:
 * <pre>
 * gate.before(RequestMetrics.get()::startTimer);
 * gate.after(RequestMetrics.get()::record);
 * </pre>
 *
 * Read stats from a handler:
 * <pre>
 * long[]  perc      = RequestMetrics.get().getPercentiles();   // [p50ms, p95ms]
 * double  errorRate = RequestMetrics.get().getErrorRate();      // percent
 * long[]  hourly    = RequestMetrics.get().getHourlyCounts();   // 24 slots, oldest first
 * </pre>
 */
public class RequestMetrics {

    private static final int HOURS       = 24;
    private static final int SAMPLE_SIZE = 1_000;
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

    /** Before-filter: records the start time for the current virtual thread. */
    public void startTimer(Context ctx) {
        requestStart.set(System.nanoTime());
    }

    /** After-filter: records metrics for the completed request. */
    public void record(Context ctx) {
        totalRequests.increment();
        // Count only server-side errors; 4xx are client errors
        if (ctx.statusCode() >= 500) errorCount.increment();

        // Hourly ring-buffer slot
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

        // Response-time sample (nanosecond → millisecond)
        Long start = requestStart.get();
        if (start != null) {
            long ms = (System.nanoTime() - start) / 1_000_000L;
            requestStart.remove();
            int pos = samplePos.getAndUpdate(p -> (p + 1) % SAMPLE_SIZE);
            responseSamples[pos] = ms;
        }

        // Endpoint hit count — cap map size to avoid unbounded growth from path params
        String key = ctx.method().toUpperCase() + " " + ctx.path();
        if (endpointCounts.size() < MAX_KEYS) {
            endpointCounts.computeIfAbsent(key, k -> new LongAdder()).increment();
        } else {
            endpointCounts.computeIfPresent(key, (k, v) -> { v.increment(); return v; });
        }
    }

    // ── read methods ─────────────────────────────────────────────────────────

    public long getTotalRequests() { return totalRequests.sum(); }
    public long getErrorCount()    { return errorCount.sum(); }

    /** Returns the 5xx error rate as a percentage (0–100). */
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

    /** Returns {@code [p50ms, p95ms]} computed over the last {@value #SAMPLE_SIZE} requests. */
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
