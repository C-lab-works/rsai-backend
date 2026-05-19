package dev.gate;

import dev.gate.core.Config;
import dev.gate.core.ConfigLoader;
import dev.gate.core.Database;
import dev.gate.core.Gate;
import dev.gate.core.GateServer;
import dev.gate.core.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {

    private static final Logger log = new Logger(Main.class);

    /** Flips to true once DB init + seed + metrics persistence are all ready. */
    private static final AtomicBoolean APP_READY = new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {
        String version = "unknown";
        try (InputStream vs = Main.class.getResourceAsStream("/version.txt")) {
            if (vs != null) version = new String(vs.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {}
        System.out.println("rsai-backend v" + version + " starting");

        Config config = ConfigLoader.load();
        // PORT env var overrides config.yml (Azure / Cloud Run inject this)
        String portEnv = System.getenv("PORT");
        int port = (portEnv != null && !portEnv.isBlank())
                ? Integer.parseInt(portEnv.trim())
                : config.getPort();

        CfAccessAuth cfAccessAuth = new CfAccessAuth();

        Config.DatabaseConfig dbConfig = config.getDatabase();
        // DB init + seed + metrics run on a background daemon thread that retries
        // on failure instead of terminating the process (avoids Cloud Run
        // crash-loops). Until it succeeds, the readiness gate below keeps every
        // route returning 503 {"status":"starting"}.
        startDatabaseInit(dbConfig);

        cfAccessAuth.prefetchJwks();
        Runtime.getRuntime().addShutdownHook(
                new Thread(RequestMetrics.get()::shutdown, "metrics-shutdown"));

        Gate gate = new Gate();
        // CORS_ALLOWED_ORIGIN accepts comma-separated origins.
        // Append CORS_ALLOWED_EXTRA_ORIGINS for dev-only additions (e.g. http://localhost:8081)
        // without touching the production value.
        String allowedOrigin = System.getenv("CORS_ALLOWED_ORIGIN");
        String extraOrigins  = System.getenv("CORS_ALLOWED_EXTRA_ORIGINS");
        if (allowedOrigin == null || allowedOrigin.isBlank()) {
            System.err.println("WARNING: CORS_ALLOWED_ORIGIN is not set — CORS will be disabled");
        }
        String baseOrigin = (allowedOrigin != null && !allowedOrigin.isBlank()) ? allowedOrigin : "";
        String corsValue  = (baseOrigin.isBlank() || extraOrigins == null || extraOrigins.isBlank())
                ? baseOrigin
                : baseOrigin + "," + extraOrigins;
        gate.cors(corsValue);
        gate.before(ctx -> RequestMetrics.get().startTimer());
        // Readiness gate: until DB init + seed + metrics finish, every route
        // except /health and CORS preflights returns 503 {"status":"starting"}
        // instead of a misleading 404. Placed before auth/CF-IP so the platform
        // and frontend always get a clear startup signal (same non-sensitive
        // information as the public /health endpoint).
        gate.before(ctx -> {
            if ("/health".equals(ctx.path())) return;
            if ("OPTIONS".equals(ctx.method())) return;
            if (!APP_READY.get()) {
                ctx.status(503).json(Map.of("status", "starting")).halt();
            }
        });
        gate.before(new CloudflareIpFilter());
        gate.before(new ApiKeyAuth());
        gate.before(cfAccessAuth);
        gate.get("/health", ctx -> {
            if (APP_READY.get()) {
                ctx.json(Map.of("status", "ok"));
            } else {
                ctx.status(503).json(Map.of("status", "starting"));
            }
        });

        gate.after(SecurityHeaders.get()::handle);
        gate.after(RequestMetrics.get()::record);

        // Register all routes up-front so paths exist immediately; DB-backed
        // handlers are gated by the readiness filter above until init completes.
        gate.register(new DataController());
        gate.register(new CongestionController());
        gate.register(new AdminController());
        gate.register(new AnnouncementsController());
        gate.register(new GcpMetricsController());

        GateServer server = gate.start(port);
        server.join();
    }

    /**
     * Initializes the database, seed data and metrics persistence on a background
     * daemon thread. On failure it retries with exponential backoff (2s → 30s)
     * instead of terminating the process, so a transient DB outage degrades the
     * service to "starting" (HTTP 503) and self-heals once the DB is reachable —
     * rather than crash-looping the container.
     */
    private static void startDatabaseInit(Config.DatabaseConfig dbConfig) {
        Thread t = new Thread(() -> {
            long backoffMs = 2_000L;
            while (!APP_READY.get()) {
                try {
                    if (!Database.isReady()) Database.init(dbConfig);
                    DataSeeder.seed();
                    RequestMetrics.get().init();
                    APP_READY.set(true);
                    log.info("Database ready — all routes now serving");
                } catch (Exception e) {
                    log.error("DB init failed; retrying in " + (backoffMs / 1000) + "s", e);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    backoffMs = Math.min(backoffMs * 2, 30_000L);
                }
            }
        }, "db-init");
        t.setDaemon(true);
        t.start();
    }
}
