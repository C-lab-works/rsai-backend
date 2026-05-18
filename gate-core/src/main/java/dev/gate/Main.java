package dev.gate;

import dev.gate.core.Config;
import dev.gate.core.ConfigLoader;
import dev.gate.core.Database;
import dev.gate.core.Gate;
import dev.gate.core.GateServer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class Main {
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
        CompletableFuture<Void> dbFuture = CompletableFuture.runAsync(() -> {
            try {
                Database.init(dbConfig);
                DataSeeder.seed();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        cfAccessAuth.prefetchJwks();
        Runtime.getRuntime().addShutdownHook(
                new Thread(RequestMetrics.get()::shutdown, "metrics-shutdown"));

        Gate gate = new Gate();
        // CORS_ALLOWED_ORIGIN accepts comma-separated origins.
        // Append CORS_ALLOWED_EXTRA_ORIGINS for dev-only additions (e.g. http://localhost:8081)
        // without touching the production value.
        String allowedOrigin = System.getenv("CORS_ALLOWED_ORIGIN");
        String extraOrigins  = System.getenv("CORS_ALLOWED_EXTRA_ORIGINS");
        String baseOrigin    = (allowedOrigin != null && !allowedOrigin.isBlank())
                ? allowedOrigin
                : "https://admin.r-sai2026.site";
        String corsValue = (extraOrigins != null && !extraOrigins.isBlank())
                ? baseOrigin + "," + extraOrigins
                : baseOrigin;
        gate.cors(corsValue);
        gate.before(RequestMetrics.get()::startTimer);
        gate.before(new CloudflareIpFilter());
        gate.before(new ApiKeyAuth());
        gate.before(cfAccessAuth);
        gate.get("/health", ctx -> {
            if (!Database.isReady()) {
                ctx.status(503).json(Map.of("status", "starting"));
            } else {
                ctx.json(Map.of("status", "ok"));
            }
        });

        gate.after(SecurityHeaders.get()::handle);
        gate.after(RequestMetrics.get()::record);

        // Start server immediately so Cloud Run health check passes.
        // DB routes are registered once the Cloud SQL proxy connection is ready.
        GateServer server = gate.start(port);

        try {
            dbFuture.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            throw (cause instanceof Exception ex) ? ex : new RuntimeException(cause);
        }

        RequestMetrics.get().init();
        gate.register(new DataController());
        gate.register(new CongestionController());
        gate.register(new AdminController());
        gate.register(new AnnouncementsController());

        server.join();
    }
}
