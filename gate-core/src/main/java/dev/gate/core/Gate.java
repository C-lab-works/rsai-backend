package dev.gate.core;

import dev.gate.annotation.AnnotationScanner;
import dev.gate.annotation.GateController;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class Gate {

    private final Router router = new Router();
    private final AnnotationScanner scanner = new AnnotationScanner(router);
    private final Logger logger = new Logger(Gate.class);
    private final List<Handler> beforeFilters = new CopyOnWriteArrayList<>();
    private final List<Handler> afterFilters = new CopyOnWriteArrayList<>();
    private int wsMaxMessageSize = 64 * 1024;
    private int idleTimeoutMs = 30_000;
    // TCP accept キューの深さ。未設定だと JDK が backlog を 50 に丸めるため、
    // バースト時（開場直後など）に接続を取りこぼす。512 で瞬間的な接続殺到を吸収する。
    private int acceptQueueSize = 512;
    private volatile boolean started = false;

    private ErrorHandler errorHandler = (ctx, e) -> {
        logger.error("Unhandled error: " + e.getMessage(), e);
        ctx.status(500).result("500 Internal Server Error");
    };

    // --- Programmatic route registration ---

    public Gate get(String path, Handler handler) {
        router.register("GET:" + path, handler);
        return this;
    }

    public Gate post(String path, Handler handler) {
        router.register("POST:" + path, handler);
        return this;
    }

    public Gate put(String path, Handler handler) {
        router.register("PUT:" + path, handler);
        return this;
    }

    public Gate delete(String path, Handler handler) {
        router.register("DELETE:" + path, handler);
        return this;
    }

    public Gate patch(String path, Handler handler) {
        router.register("PATCH:" + path, handler);
        return this;
    }

    // --- Middleware ---

    public Gate before(Handler handler) {
        beforeFilters.add(handler);
        return this;
    }

    public Gate after(Handler handler) {
        afterFilters.add(handler);
        return this;
    }

    // --- Configuration ---

    public void register(Object controller) {
        scanner.scan(controller);
    }

    public Gate wsMaxMessageSize(int bytes) {
        if (started) {
            throw new IllegalStateException("wsMaxMessageSize() must be called before start()");
        }
        this.wsMaxMessageSize = bytes;
        return this;
    }

    public Gate timeout(int ms) {
        this.idleTimeoutMs = ms;
        return this;
    }

    public Gate acceptQueueSize(int size) {
        if (started) {
            throw new IllegalStateException("acceptQueueSize() must be called before start()");
        }
        if (size < 0) {
            throw new IllegalArgumentException("acceptQueueSize must be >= 0");
        }
        this.acceptQueueSize = size;
        return this;
    }

    public Gate errorHandler(ErrorHandler handler) {
        this.errorHandler = handler;
        return this;
    }

    // --- Server lifecycle ---

    public GateServer start(int port) throws Exception {
        started = true;
        installCancelledKeyExceptionSuppressor();

        QueuedThreadPool threadPool = new QueuedThreadPool() {
            @Override
            protected void runJob(Runnable job) {
                try {
                    super.runJob(job);
                } catch (java.nio.channels.CancelledKeyException ignored) {
                    // Jetty selector key cancellation noise — harmless
                }
            }
        };
        // Virtual threads with a custom factory so their uncaught-exception handler
        // also suppresses CancelledKeyException before it reaches GraalVM stderr.
        java.util.concurrent.ThreadFactory vtFactory = Thread.ofVirtual()
                .uncaughtExceptionHandler((t, e) -> {
                    if (!isCancelledKeyException(e)) e.printStackTrace(System.err);
                })
                .factory();
        threadPool.setVirtualThreadsExecutor(Executors.newThreadPerTaskExecutor(vtFactory));
        Server server = new Server(threadPool);
        HttpConfiguration httpConfig = new HttpConfiguration();
        // Server: Jetty(...) ヘッダを抑制（毎レスポンスの無駄なバイト + 実装のフィンガープリント防止）
        httpConfig.setSendServerVersion(false);
        httpConfig.setSendXPoweredBy(false);
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        connector.setPort(port);
        connector.setIdleTimeout(idleTimeoutMs);
        connector.setAcceptQueueSize(acceptQueueSize);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        final int finalWsMaxSize = this.wsMaxMessageSize;
        JettyWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) -> {
            wsContainer.setMaxTextMessageSize(finalWsMaxSize);
            router.getWsRoutes().forEach((path, wsHandler) -> {
                // WS アップグレードはサーブレット service() を通らないため、
                // creator 内で before-filter チェーンの認可を必ず通す。
                wsContainer.addMapping(path, (req, res) ->
                        authorizeWsUpgrade(req, res) ? new WsAdapter(wsHandler) : null);
            });
        });

        context.addServlet(new ServletHolder(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                String path = resolvePath(request);

                // Jetty の正規化に依存しない多層防御: デコード後のパスに ".." が残っている場合は拒否。
                // 正規ルートは ".." を含まないため、誤検知は発生しない。
                if (path.contains("..")) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST);
                    return;
                }

                // ホットパスでは request.getMethod() を複数回参照するのでローカルで 1 回だけ取る。
                // Jetty の HttpServletRequest実装では getMethod() はフィールド読みだが、
                // ホットパスでは回数 = req の不必要なレピートを避ける。
                final String httpMethod = request.getMethod();

                Context ctx = new Context(path, request);

                try {
                    // Run ALL before-filters first (CloudflareIpFilter, ApiKeyAuth, CfAccessAuth).
                    // This ensures OPTIONS preflights are also subject to IP and auth checks.
                    // Individual filters that require credentials (ApiKeyAuth, CfAccessAuth) must
                    // skip OPTIONS themselves since browsers do not send credentials in preflights.
                    for (Handler filter : beforeFilters) {
                        filter.handle(ctx);
                        if (ctx.isHalted()) break;
                    }

                    if (!ctx.isHalted()) {
                        if ("OPTIONS".equals(httpMethod)) {
                            ctx.status(204);
                        } else {
                            String key = httpMethod + ':' + path;
                            router.find(key).ifPresentOrElse(
                                match -> {
                                    ctx.setPathParams(match.pathParams());
                                    match.handler().handle(ctx);
                                },
                                () -> ctx.status(404).result("404 Not Found")
                            );
                        }
                    }
                } catch (Exception e) {
                    errorHandler.handle(ctx, e);
                } finally {
                    // after filters always run (logging, metrics, cleanup)
                    for (Handler filter : afterFilters) {
                        try {
                            filter.handle(ctx);
                        } catch (Exception ex) {
                            logger.error("After-filter error: {}", ex.getMessage(), ex);
                        }
                    }
                }

                response.setStatus(ctx.statusCode());
                ctx.headers().forEach(response::setHeader);
                response.setContentType(ctx.contentType());

                // String→char[]→byte[]の二重変換を避けるため、UTF-8 byte[] を直接書き込む。
                // Content-Lengthも明示してチャンク化を抑制し、CDN/プロキシでのキャッシュ判定を安定させる。
                byte[] body = ctx.responseBodyBytes();
                response.setContentLength(body.length);
                OutputStream out = response.getOutputStream();
                if (body.length > 0) out.write(body);
                out.flush();
            }
        }), "/*");

        server.setHandler(context);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down server...");
            try { server.stop(); } catch (Exception ex) { logger.error("Error stopping server", ex); }
        }));

        logger.info("Starting on port {}", port);
        try {
            server.start();
        } catch (Exception e) {
            server.stop();
            throw e;
        }

        return new GateServer(server);
    }

    /**
     * サーブレットと WebSocket アップグレードで共通のリクエストパス解決。
     * pathInfo → servletPath → "/" の順にフォールバックし、末尾スラッシュを除去する。
     */
    private static String resolvePath(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null || path.isEmpty()) path = request.getServletPath();
        if (path == null || path.isEmpty()) path = "/";
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    /**
     * WebSocket アップグレード要求に HTTP と同じ before-filter チェーン
     * （CloudflareIpFilter / ApiKeyAuth / CfAccessAuth など）を適用する。
     *
     * <p>Jetty の WS アップグレードは WebSocketUpgradeFilter がフィルタチェーン先頭で
     * 処理しサーブレットの service() に到達しないため、ここで実行しない限り
     * WS ルートは無認証・IP フィルタなしで公開されてしまう。</p>
     *
     * <p>制約: {@link JettyServerUpgradeResponse} はボディ書き込み API を持たないため、
     * 拒否時のボディは Jetty 標準のエラーページになる（ステータスコードとヘッダは反映される）。
     * 許可時の 101 レスポンスにはヘッダを付けない（CSP 等は 101 には意味がないため）。</p>
     *
     * @return アップグレード許可なら true。false の場合は拒否レスポンス送信済み。
     */
    private boolean authorizeWsUpgrade(JettyServerUpgradeRequest req, JettyServerUpgradeResponse res) {
        Context ctx = new Context(resolvePath(req.getHttpServletRequest()), req.getHttpServletRequest());
        if (ctx.path().contains("..")) {
            // サーブレット側と同じ多層防御（デコード後パスの ".." を拒否）
            ctx.status(HttpServletResponse.SC_BAD_REQUEST).halt();
        } else {
            try {
                for (Handler filter : beforeFilters) {
                    filter.handle(ctx);
                    if (ctx.isHalted()) break;
                }
            } catch (Exception e) {
                logger.error("WS upgrade filter error: " + e.getMessage(), e);
                ctx.status(500).halt();
            }
        }
        if (!ctx.isHalted()) return true;

        try {
            ctx.headers().forEach(res::setHeader);
            res.sendError(ctx.statusCode(), "WebSocket upgrade rejected");
        } catch (IOException e) {
            logger.error("WS upgrade rejection response failed: " + e.getMessage(), e);
        }
        return false;
    }

    public void scan(String packageName) throws Exception {
        String packagePath = packageName.replace('.', '/');
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        Enumeration<URL> resources = classLoader.getResources(packagePath);
        if (!resources.hasMoreElements()) {
            logger.warn("Package not found: " + packageName);
            return;
        }

        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            switch (url.getProtocol()) {
                case "file" -> scanDirectory(packageName, classLoader, Paths.get(url.toURI()).toFile());
                case "jar"  -> scanJar(packageName, classLoader, url);
                default     -> logger.warn("Unsupported protocol: " + url.getProtocol());
            }
        }
    }

    private void scanDirectory(String packageName, ClassLoader classLoader, File directory) throws Exception {
        if (!directory.isDirectory()) return;

        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(packageName + "." + file.getName(), classLoader, file);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "." + file.getName().replace(".class", "");
                loadAndRegister(className, classLoader);
            }
        }
    }

    private void scanJar(String packageName, ClassLoader classLoader, URL url) throws Exception {
        JarURLConnection conn = (JarURLConnection) url.openConnection();
        conn.setUseCaches(false);
        String packagePath = packageName.replace('.', '/') + "/";

        try (JarFile jar = conn.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith(packagePath) && entry.getName().endsWith(".class")) {
                    String className = entry.getName().replace('/', '.').replace(".class", "");
                    loadAndRegister(className, classLoader);
                }
            }
        }
    }

    private static boolean isCancelledKeyException(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.nio.channels.CancelledKeyException) return true;
        }
        return false;
    }

    private static void installCancelledKeyExceptionSuppressor() {
        Thread.UncaughtExceptionHandler existing = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (isCancelledKeyException(throwable)) return;
            if (existing != null) existing.uncaughtException(thread, throwable);
            else throwable.printStackTrace(System.err);
        });
    }

    private void loadAndRegister(String className, ClassLoader classLoader) {
        try {
            Class<?> clazz = classLoader.loadClass(className);
            if (clazz.isAnnotationPresent(GateController.class)) {
                register(clazz.getDeclaredConstructor().newInstance());
                logger.info("Loaded controller: " + className);
            }
        } catch (Exception e) {
            logger.error("Failed to load class: " + className + " - " + e.getMessage(), e);
        }
    }
}
