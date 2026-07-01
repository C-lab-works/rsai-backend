package dev.gate;

import dev.gate.core.Config;
import dev.gate.core.ConfigLoader;
import dev.gate.core.Database;
import dev.gate.core.Gate;
import dev.gate.core.Logger;
import dev.gate.core.YamlRouteLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    private static final Logger log = new Logger(Main.class);
    private static final AtomicBoolean APP_READY = new AtomicBoolean(false);
    private static final AtomicInteger BG_COUNTER = new AtomicInteger();
    static final ScheduledExecutorService bg =
            Executors.newScheduledThreadPool(8, r -> {
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
        // prefetchJwks は DB 初期化後にキャッシュ充填と並列で実行

        // --- Middleware & Auth ---

        // セキュリティヘッダは認証より先に付与する。
        gate.before(SecurityHeaders.get());
        gate.before(new CloudflareIpFilter());
        gate.before(new ApiKeyAuth());
        gate.before(cfAccessAuth);
        gate.before(new RequestIdMiddleware());

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
        gate.register(new AdminYamlController());
        gate.register(new StarsController());
        gate.register(new PushTokenController());
        gate.register(new CfMetricsController());
        gate.register(new DelayController());
        if (!"azure".equalsIgnoreCase(System.getenv("RUNMODE"))) {
            gate.register(new GcpMetricsController());
        }

        YamlRouteLoader.load(gate);
        startDatabaseInit(config.getDatabase(), cfAccessAuth);

        // --- Startup ---
        gate.timeout(620_000);
        gate.start(config.getPort());
        log.info("rsai-backend is running on port {}", config.getPort());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.warn("Shutdown: flushing pending star ops before exit");
            StarsController.flushPending();
        }, "shutdown-stars-flush"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.warn("Shutdown: flushing pending push tokens before exit");
            PushTokenController.flushPending();
        }, "shutdown-pushtokens-flush"));
    }

    private static void startDatabaseInit(Config.DatabaseConfig dbConfig, CfAccessAuth cfAccessAuth) {
        Thread t = new Thread(() -> {
            long backoffMs = 2000L;
            while (true) {
                try {
                    Database.init(dbConfig);
                    DataSeeder.seed();

                    // 起動時キャッシュ初回充填（並列: events/food/map/混雑/お知らせ/JWKS）
                    log.info("Performing initial cache fill...");
                    List<Future<?>> initFutures = new ArrayList<>();
                    initFutures.add(bg.submit((Callable<Void>) () -> { new DataController().refreshAll(); return null; }));
                    initFutures.add(bg.submit((Callable<Void>) () -> { CongestionController.refreshCache(); return null; }));
                    initFutures.add(bg.submit((Callable<Void>) () -> { AnnouncementsController.refreshCache(); return null; }));
                    initFutures.add(bg.submit((Callable<Void>) () -> { YamlRouteLoader.refreshAll(); return null; }));
                    initFutures.add(bg.submit((Callable<Void>) () -> { cfAccessAuth.prefetchJwks(); return null; }));
                    Exception initError = null;
                    for (Future<?> f : initFutures) {
                        try { f.get(); }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw ex; }
                        catch (ExecutionException ex) { if (initError == null) initError = (Exception) ex.getCause(); }
                    }
                    if (initError != null) throw initError;
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

        // routes.yaml の cache 指定ルートを、各 TTL 間隔で背景リフレッシュ
        YamlRouteLoader.startBackgroundRefreshes(bg);

        // Firestore インスタンス管理（自己登録・コマンドリスナー・ブロードキャスト）
        InstanceManager.get().init(() -> APP_READY.set(false));

        // 30秒ごとにインスタンスメトリクスを記録
        bg.scheduleAtFixedRate(
                () -> InstanceManager.get().recordMetrics(),
                30, 30, TimeUnit.SECONDS);

        // 1分ごとにスターをフラッシュ・異常検知カウンターをリセット
        bg.scheduleAtFixedRate(StarsController::minuteTick, 1, 1, TimeUnit.MINUTES);

        // 1分ごとにプッシュトークンをフラッシュ
        bg.scheduleAtFixedRate(PushTokenController::minuteTick, 1, 1, TimeUnit.MINUTES);

    }

    private static String loadVersion() {
        // バージョンはビルド成果物に焼き込まず、デプロイ時に環境変数 APP_VERSION で注入する。
        // こうすることで version.txt を src 内で書き換える必要がなくなり、
        // GraalVM nativeCompile のレイヤーキャッシュが SHA 変化で無効化されなくなる。
        // 取得できない場合は空によるエラーを避けるため文字列 "null" を返す。
        String version = System.getenv("APP_VERSION");
        return (version == null || version.isEmpty()) ? "null" : version;
    }
}
