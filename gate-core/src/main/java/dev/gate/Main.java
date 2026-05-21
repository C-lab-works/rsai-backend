package dev.gate;

import dev.gate.core.Config;
import dev.gate.core.ConfigLoader;
import dev.gate.core.Database;
import dev.gate.core.Gate;
import dev.gate.core.GateServer;
import dev.gate.core.Logger;

import java.io.InputStream;
import java.nio.channels.CancelledKeyException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {

    private static final Logger log = new Logger(Main.class);

    /** 起動完了したらtrueになる */
    private static final AtomicBoolean APP_READY = new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {
        // Jetty の CancelledKeyException は接続クローズ時の既知の無害なノイズ。
        // GraalVM native image はこれを uncaught exception として stderr に直接出力するため
        // logback フィルタでは捕捉できず、ここで抑制する。
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (throwable instanceof CancelledKeyException) return;
            if (previousHandler != null) previousHandler.uncaughtException(thread, throwable);
            else log.error("Uncaught exception in thread {}: {}", thread.getName(), throwable.getMessage(), throwable);
        });

        String version = "unknown";
        try (InputStream vs = Main.class.getResourceAsStream("/version.txt")) {
            if (vs != null) version = new String(vs.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {}
        System.out.println("rsai-backend v" + version + " starting");

        Config config = ConfigLoader.load();
        // PORT環境変数はconfig.ymlを上書きする（Cloud Runが注入）
        String portEnv = System.getenv("PORT");
        int port = (portEnv != null && !portEnv.isBlank())
                ? Integer.parseInt(portEnv.trim())
                : config.getPort();

        CfAccessAuth cfAccessAuth = new CfAccessAuth();

        Config.DatabaseConfig dbConfig = config.getDatabase();
        // DB初期化・シード・メトリクスをバックグラウンドで起動。失敗時はリトライ（クラッシュループ回避）
        startDatabaseInit(dbConfig);

        cfAccessAuth.prefetchJwks();
        Runtime.getRuntime().addShutdownHook(
                new Thread(RequestMetrics.get()::shutdown, "metrics-shutdown"));

        Gate gate = new Gate();
        // CORS_ALLOWED_ORIGINはカンマ区切り複数指定可。CORS_ALLOWED_EXTRA_ORIGINSで開発用追加（例: localhost）
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
        // 起動中は /health とOPTIONS以外に503を返す（readinessゲート）
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

        // 全ルートを起動時に登録。DBが揃うまではreadinessフィルタが503を返す
        gate.register(new DataController());
        gate.register(new CongestionController());
        gate.register(new AdminController());
        gate.register(new AnnouncementsController());
        gate.register(new GcpMetricsController());

        GateServer server = gate.start(port);
        server.join();
    }

    /**
     * DB初期化・シード・メトリクスをバックグラウンドデーモンスレッドで起動。
     * 失敗時は指数バックオフ（2s → 30s）でリトライし、起動完了までは全ルートが503を返す。
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
                    log.info("DB準備完了 — 全ルート起動");
                } catch (Exception e) {
                    log.error("DB初期化失敗。" + (backoffMs / 1000) + "秒後にリトライ", e);
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
