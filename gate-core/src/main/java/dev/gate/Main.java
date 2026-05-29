package dev.gate;

import dev.gate.core.Config;
import dev.gate.core.ConfigLoader;
import dev.gate.core.Database;
import dev.gate.core.Gate;
import dev.gate.core.Logger;
import dev.gate.core.YamlRouteLoader;

import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    private static final Logger log = new Logger(Main.class);
    private static final AtomicBoolean APP_READY = new AtomicBoolean(false);
    private static final AtomicInteger BG_COUNTER = new AtomicInteger();
    private static final ScheduledExecutorService bg =
            Executors.newScheduledThreadPool(6, r -> {
                Thread t = new Thread(r, "bg-poller-" + BG_COUNTER.getAndIncrement());
                t.setDaemon(true);
                return t;
            });

    public static void main(String[] args) throws Exception {
        String version = loadVersion();
        log.info("Starting rsai-backend {}...", version);

        Config config = ConfigLoader.load();
        Gate gate = new Gate();

        // CF Access 認証ハンドラの初期化
        CfAccessAuth cfAccessAuth = new CfAccessAuth();
        cfAccessAuth.prefetchJwks();

        // Database init (background thread)
        startDatabaseInit(config.getDatabase(), cfAccessAuth);

        // --- Middleware & Auth ---

        // セキュリティヘッダは認証より先に付与する。
        // 認証フィルタが halt した 401/403 応答にもヘッダが確実に乗るようにするため。
        gate.before(SecurityHeaders.get());
        gate.before(new CloudflareIpFilter());
        gate.before(new ApiKeyAuth());
        gate.before(cfAccessAuth);

        RequestMetrics metrics = RequestMetrics.get();
        metrics.init();
        gate.before(metrics::startTimer);
        gate.after(metrics::record);

        // --- Core routes ---

        gate.get("/health", ctx -> {
            if (APP_READY.get()) {
                ctx.json(java.util.Map.of("status", "ok", "version", version));
            } else {
                ctx.status(503).json(java.util.Map.of("status", "starting", "version", version));
            }
        });

        // インスタンスを明示的に登録
        gate.register(new DataController());
        gate.register(new CongestionController());
        gate.register(new AnnouncementsController());
        gate.register(new AdminController());
        gate.register(new CfMetricsController());
        if (!"azure".equalsIgnoreCase(System.getenv("RUNMODE"))) {
            gate.register(new GcpMetricsController());
        }

        // routes.yaml から宣言的ルートを登録
        YamlRouteLoader.load(gate);

        // --- Startup ---

        gate.start(config.getPort());
        log.info("rsai-backend is running on port {}", config.getPort());
    }

    private static void startDatabaseInit(Config.DatabaseConfig dbConfig, CfAccessAuth cfAccessAuth) {
        Thread t = new Thread(() -> {
            long backoffMs = 2000L;
            while (true) {
                try {
                    Database.init(dbConfig);
                    DataSeeder.seed();

                    // 起動時キャッシュ初回充填（同期）
                    log.info("Performing initial cache fill...");
                    new DataController().refreshAll();
                    CongestionController.refreshCache();
                    AnnouncementsController.refreshCache();
                    log.info("Initial cache fill OK");

                    APP_READY.set(true);
                    log.info("Application is now READY");

                    startBackgroundJobs(cfAccessAuth);
                    return;
                } catch (Exception e) {
                    log.error("Database initialization failed: {}. Retrying in {}ms...", e.getMessage(), backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ex) {
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

    private static void startBackgroundJobs(CfAccessAuth cfAccessAuth) {
        final DataController dataController = new DataController();
        final AtomicInteger dataFailCount = new AtomicInteger(0);
        final AtomicInteger announcementsFailCount = new AtomicInteger(0);
        final AtomicInteger congestionFailCount = new AtomicInteger(0);

        // 30秒ごとにevents/food/mapを更新
        bg.scheduleAtFixedRate(() -> {
            try {
                dataController.refreshAll();
                dataFailCount.set(0);
            } catch (Exception e) {
                int fails = dataFailCount.incrementAndGet();
                log.warn("DataController poll failed ({}): {}", fails, e.getMessage());
                if (fails == 1 || fails % 5 == 0) {
                    DiscordWebhook.sendError("POLL", "/events,/food,/map", 500,
                            "Poll failed (" + fails + "): " + e.getMessage());
                }
            }
        }, 30, 30, TimeUnit.SECONDS);

        // 60秒ごとにお知らせを更新（display_from/until の時刻変化に追従）
        // お知らせは表示遅延しても致命ではないので Discord 通知はしない
        bg.scheduleAtFixedRate(() -> {
            try {
                AnnouncementsController.refreshCache();
                announcementsFailCount.set(0);
            } catch (Exception e) {
                int fails = announcementsFailCount.incrementAndGet();
                log.warn("AnnouncementsController poll failed ({}): {}", fails, e.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS);

        // 30秒ごとに混雑情報を更新
        bg.scheduleAtFixedRate(() -> {
            try {
                CongestionController.refreshCache();
                congestionFailCount.set(0);
            } catch (Exception e) {
                int fails = congestionFailCount.incrementAndGet();
                log.warn("CongestionController poll failed ({}): {}", fails, e.getMessage());
                if (fails == 1 || fails % 5 == 0) {
                    DiscordWebhook.sendError("POLL", "/congestion", 500,
                            "Poll failed (" + fails + "): " + e.getMessage());
                }
            }
        }, 30, 30, TimeUnit.SECONDS);

        // 50分ごとにJWKS公開鍵を事前更新（TTL=60分の10分前）
        bg.scheduleAtFixedRate(cfAccessAuth::prefetchJwks, 50, 50, TimeUnit.MINUTES);

        // Firestore インスタンス管理（自己登録・コマンドリスナー・ブロードキャスト）
        InstanceManager.get().init(() -> APP_READY.set(false));

        // 30秒ごとにインスタンスメトリクスを記録
        bg.scheduleAtFixedRate(
                () -> InstanceManager.get().recordMetrics(),
                30, 30, TimeUnit.SECONDS);
    }

    private static String loadVersion() {
        // getClassLoader().getResourceAsStream は GraalVM native image で null を返す場合があるため
        // Class.getResourceAsStream("/...") (絶対パス) を使用する
        try (InputStream is = Main.class.getResourceAsStream("/version.txt")) {
            if (is == null) return "unknown";
            Properties props = new Properties();
            props.load(is);
            return props.getProperty("version", "unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }
}
