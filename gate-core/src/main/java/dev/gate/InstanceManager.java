package dev.gate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.sun.management.OperatingSystemMXBean;
import dev.gate.core.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// Firestore paths (via FirestoreRest):
//   instances/{instanceId}                   { revision, host, startedAt, status, cmd, res }
//   instances/{instanceId}/metrics/{auto-id} { t, cpu, heap_used_mb, threads }
//   broadcast/cache                          { refreshAt: ISO string }
public class InstanceManager {

    private static final Logger log = new Logger(InstanceManager.class);
    private static final InstanceManager INSTANCE = new InstanceManager();

    private final String instanceId;
    private final Instant startedAt = Instant.now();
    private final FirestoreRest fs = FirestoreRest.get();

    private Runnable stopCallback;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    // Polling state
    private volatile String lastBroadcastRefreshAt = null;
    private volatile String lastCmdRequestId       = null;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private final ScheduledExecutorService poller =
            Executors.newScheduledThreadPool(3, r -> {
                Thread t = new Thread(r, "firestore-poller");
                t.setDaemon(true);
                return t;
            });

    private InstanceManager() {
        // Combine K_REVISION (revision name) and HOSTNAME (unique per container) for a human-readable unique ID
        String rev  = System.getenv("K_REVISION");
        String host = System.getenv("HOSTNAME");
        if (rev != null && host != null) {
            this.instanceId = rev + "-" + host;
        } else if (host != null) {
            this.instanceId = host;
        } else if (rev != null) {
            this.instanceId = rev;
        } else {
            this.instanceId = "local-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    public static InstanceManager get() { return INSTANCE; }

    public void init(Runnable stopCallback) {
        if (!initialized.compareAndSet(false, true)) return;
        this.stopCallback = stopCallback;
        fs.init();
        if (!fs.isAvailable()) {
            log.warn("Firestore unavailable, InstanceManager disabled");
            return;
        }
        try {
            registerSelf();
            // Initialize broadcast state without triggering a refresh
            try {
                Map<String, Object> bDoc = fs.get("broadcast/cache");
                if (bDoc != null) lastBroadcastRefreshAt = (String) bDoc.get("refreshAt");
            } catch (Exception ignored) {}

            poller.scheduleAtFixedRate(this::heartbeat,      10, 10, TimeUnit.SECONDS);
            poller.scheduleAtFixedRate(this::pollBroadcast, 15, 15, TimeUnit.SECONDS);
            poller.scheduleAtFixedRate(this::pollCommand,    2,  2, TimeUnit.SECONDS);
            registerShutdownHook();
            log.info("InstanceManager initialized: instanceId={}", instanceId);
        } catch (Exception e) {
            log.error("InstanceManager init failed: {}", e.getMessage());
        }
    }

    // ── self registration ────────────────────────────────────────────────────

    private void registerSelf() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("revision",  System.getenv("K_REVISION"));
        data.put("host",      System.getenv("HOSTNAME"));
        data.put("startedAt", startedAt.toString());
        data.put("status",    "running");
        data.put("lastSeen",  startedAt.toString());
        fs.set("instances/" + instanceId, data);
    }

    // ── heartbeat ────────────────────────────────────────────────────────────

    private void heartbeat() {
        if (stopped.get()) return;
        try {
            fs.update("instances/" + instanceId, Map.of("lastSeen", Instant.now().toString()));
        } catch (Exception e) {
            log.warn("heartbeat failed: {}", e.getMessage());
        }
    }

    // ── broadcast polling ────────────────────────────────────────────────────

    private void pollBroadcast() {
        if (stopped.get()) return;
        try {
            Map<String, Object> doc = fs.get("broadcast/cache");
            if (doc == null) return;
            String refreshAt = (String) doc.get("refreshAt");
            if (refreshAt == null || refreshAt.equals(lastBroadcastRefreshAt)) return;
            lastBroadcastRefreshAt = refreshAt;
            log.info("broadcast cache refresh received");
            new DataController().refreshAll();
            AnnouncementsController.refreshCache();
            CongestionController.refreshCache();
        } catch (Exception e) {
            log.warn("pollBroadcast failed: {}", e.getMessage());
        }
    }

    // ── command polling ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void pollCommand() {
        if (stopped.get()) return;
        try {
            Map<String, Object> doc = fs.get("instances/" + instanceId);
            if (doc == null) return;
            Map<String, Object> cmd = (Map<String, Object>) doc.get("cmd");
            if (cmd == null) return;
            String requestId = (String) cmd.get("requestId");
            if (requestId == null || requestId.equals(lastCmdRequestId)) return;

            // Skip if result already written
            Map<String, Object> res = (Map<String, Object>) doc.get("res");
            if (res != null && requestId.equals(res.get("requestId"))) {
                lastCmdRequestId = requestId;
                return;
            }

            lastCmdRequestId = requestId;
            String type = (String) cmd.get("type");
            Map<String, Object> payload = (Map<String, Object>) cmd.get("payload");
            dispatch(type, requestId, payload);
        } catch (Exception e) {
            log.warn("pollCommand failed: {}", e.getMessage());
        }
    }

    // ── command dispatch ─────────────────────────────────────────────────────

    private void dispatch(String type, String requestId, Map<String, Object> payload) {
        CompletableFuture.runAsync(() -> {
            Object data  = null;
            String error = null;
            try {
                data = switch (type) {
                    case "ping"         -> buildPing();
                    case "cpu"          -> buildCpu();
                    case "heap"         -> buildHeap();
                    case "thread-count" -> buildThreadCount();
                    case "gc"           -> buildGc();
                    case "cache-stats"  -> buildCacheStats();
                    case "error"        -> buildErrors();
                    case "log-level"    -> applyLogLevel(payload);
                    case "stop"         -> { handleStop(); yield Map.of("stopped", true); }
                    default             -> Map.of("unknown_command", type);
                };
            } catch (Exception e) {
                log.warn("dispatch {} failed: {}", type, e.getMessage());
                error = e.getMessage();
            }
            writeResult(requestId, data, error);
        });
    }

    private void writeResult(String requestId, Object data, String error) {
        try {
            Map<String, Object> res = new HashMap<>();
            res.put("requestId",   requestId);
            res.put("completedAt", Instant.now().toString());
            if (error != null) res.put("error", error);
            else               res.put("data",  data);
            fs.update("instances/" + instanceId, Map.of("res", res));
        } catch (Exception e) {
            log.warn("writeResult failed: {}", e.getMessage());
        }
    }

    // ── periodic metrics recording ───────────────────────────────────────────

    public void recordMetrics() {
        if (!fs.isAvailable() || stopped.get()) return;
        try {
            OperatingSystemMXBean os = (OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean();
            double cpu = Math.max(0, os.getProcessCpuLoad() * 100.0);

            Runtime rt = Runtime.getRuntime();
            long heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);

            ThreadMXBean tmx = ManagementFactory.getThreadMXBean();

            Map<String, Object> point = new HashMap<>();
            point.put("t",            System.currentTimeMillis());
            point.put("cpu",          Math.round(cpu * 10.0) / 10.0);
            point.put("heap_used_mb", heapUsedMb);
            point.put("threads",      (long) tmx.getThreadCount());

            fs.add("instances/" + instanceId + "/metrics", point);

            // 121件目以降（最古）を削除して 120件上限を維持
            List<FirestoreRest.Entry> all =
                    fs.query("instances/" + instanceId, "metrics", "t", true, 121);
            if (all.size() == 121) {
                try { fs.delete("instances/" + instanceId + "/metrics/" + all.get(120).id()); }
                catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.warn("recordMetrics failed: {}", e.getMessage());
        }
    }

    // ── broadcast cache refresh to other instances ───────────────────────────

    public void broadcastCacheRefresh() {
        if (!fs.isAvailable()) return;
        try {
            fs.set("broadcast/cache", Map.of("refreshAt", Instant.now().toString()));
        } catch (Exception e) {
            log.warn("broadcastCacheRefresh failed: {}", e.getMessage());
        }
    }

    // ── command implementations ───────────────────────────────────────────────

    private Map<String, Object> buildPing() {
        long uptimeSec = Instant.now().getEpochSecond() - startedAt.getEpochSecond();
        return Map.of("startedAt", startedAt.toString(), "uptimeSec", uptimeSec);
    }

    private Map<String, Object> buildCpu() {
        OperatingSystemMXBean os = (OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean();
        double cpu = Math.max(0, os.getProcessCpuLoad() * 100.0);
        return Map.of("cpu_percent", Math.round(cpu * 10.0) / 10.0);
    }

    private Map<String, Object> buildHeap() {
        Runtime rt    = Runtime.getRuntime();
        long total    = rt.totalMemory() / (1024 * 1024);
        long free     = rt.freeMemory()  / (1024 * 1024);
        long max      = rt.maxMemory()   / (1024 * 1024);
        return Map.of(
            "heap_used_mb",  total - free,
            "heap_total_mb", total,
            "heap_max_mb",   max
        );
    }

    private Map<String, Object> buildThreadCount() {
        return Map.of("thread_count", (long) ManagementFactory.getThreadMXBean().getThreadCount());
    }

    private Map<String, Object> buildGc() {
        Runtime rt   = Runtime.getRuntime();
        long before  = rt.freeMemory();
        System.gc();
        long freed   = (rt.freeMemory() - before) / (1024 * 1024);
        return Map.of("freed_mb", Math.max(0L, freed));
    }

    private Map<String, Object> buildCacheStats() {
        Map<String, Object> result = new HashMap<>();
        result.put("data_etags",         DataController.getCacheEtags());
        result.put("announcements_etag", AnnouncementsController.getCacheEtag());
        return result;
    }

    private Map<String, Object> buildErrors() {
        Instant now  = Instant.now();
        Instant from = now.minusSeconds(3600);
        List<RequestMetrics.ErrorEntry> errors = RequestMetrics.get().getRecentErrors(from, now);
        List<Map<String, Object>> errorMaps = new ArrayList<>();
        for (RequestMetrics.ErrorEntry e : errors) {
            Map<String, Object> m = new HashMap<>();
            m.put("timestamp",  e.timestamp());
            m.put("method",     e.method());
            m.put("path",       e.path());
            m.put("status",     (long) e.status());
            m.put("durationMs", e.durationMs());
            errorMaps.add(m);
        }
        return Map.of("errors", errorMaps, "count", (long) errors.size());
    }

    private Map<String, Object> applyLogLevel(Map<String, Object> payload) {
        String level = payload != null ? (String) payload.get("level") : null;
        if (level == null) throw new IllegalArgumentException("payload.level is required");
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ctx.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
           .setLevel(Level.toLevel(level, Level.INFO));
        log.info("log level changed to {}", level);
        return Map.of("level", level);
    }

    private void handleStop() {
        log.warn("stop command received — will exit in 5s");
        stopped.set(true); // stop heartbeat immediately
        try {
            fs.update("instances/" + instanceId, Map.of("status", "stopped", "lastSeen", Instant.now().toString()));
        } catch (Exception e) {
            log.warn("stop status update failed: {}", e.getMessage());
        }
        if (stopCallback != null) stopCallback.run(); // APP_READY=false → health 503
        // Drain in-flight requests briefly, then terminate the process.
        // Cloud Run will detect the exit and remove the instance.
        Thread exit = new Thread(() -> {
            try { Thread.sleep(5_000); } catch (InterruptedException ignored) {}
            log.info("exiting after stop command");
            System.exit(0);
        }, "stop-exit");
        exit.setDaemon(false); // prevent JVM from exiting before this thread runs
        exit.start();
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                fs.update("instances/" + instanceId, Map.of("status", "stopped"));
                log.info("instance status set to stopped on shutdown");
            } catch (Exception e) {
                log.warn("shutdown hook failed: {}", e.getMessage());
            }
        }, "instance-shutdown"));
    }

    public String getInstanceId() { return instanceId; }
}
