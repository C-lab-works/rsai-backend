package dev.gate.core;

import dev.gate.annotation.AnnotationScanner;
import dev.gate.annotation.GateController;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
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
    private java.util.Set<String> corsOrigins = null;
    private int wsMaxMessageSize = 64 * 1024;
    private int idleTimeoutMs = 30_000;
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

    public Gate cors(String allowedOrigins) {
        if (allowedOrigins == null || allowedOrigins.isBlank()) return this;
        java.util.Set<String> parsed = new java.util.LinkedHashSet<>();
        for (String o : allowedOrigins.split(",")) {
            String trimmed = o.strip();
            if (!trimmed.isEmpty()) parsed.add(trimmed);
        }
        if (parsed.contains("*")) {
            logger.warn("CORS configured with wildcard '*' — credentials cannot be used with this origin");
        }
        this.corsOrigins = java.util.Collections.unmodifiableSet(parsed);
        logger.info("CORS allowed origins: {}", this.corsOrigins);
        return this;
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

    public Gate errorHandler(ErrorHandler handler) {
        this.errorHandler = handler;
        return this;
    }

    // --- Server lifecycle ---

    public GateServer start(int port) throws Exception {
        started = true;
        // Java 21 Virtual Threads via Jetty's official API
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
        Server server = new Server(threadPool);
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        connector.setIdleTimeout(idleTimeoutMs);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        final int finalWsMaxSize = this.wsMaxMessageSize;
        JettyWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) -> {
            wsContainer.setMaxTextMessageSize(finalWsMaxSize);
            router.getWsRoutes().forEach((path, wsHandler) -> {
                wsContainer.addMapping(path, (req, res) -> new WsAdapter(wsHandler));
            });
        });

        final java.util.Set<String> finalCorsOrigins = this.corsOrigins;
        context.addServlet(new ServletHolder(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                String path = request.getPathInfo();
                if (path == null || path.isEmpty()) path = request.getServletPath();
                if (path == null || path.isEmpty()) path = "/";
                if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);

                Context ctx = new Context(path, request);

                try {
                    if (finalCorsOrigins != null && !finalCorsOrigins.isEmpty()) {
                        String requestOrigin = request.getHeader("Origin");
                        String matchedOrigin = null;
                        if (finalCorsOrigins.contains("*")) {
                            matchedOrigin = "*";
                        } else if (requestOrigin != null && finalCorsOrigins.contains(requestOrigin)) {
                            matchedOrigin = requestOrigin;
                        } else if (requestOrigin != null) {
                            // Support "http://localhost:*" / "https://localhost:*" wildcard patterns
                            // for local development (any port on localhost).
                            // The browser sees its own origin echoed back, not the pattern itself.
                            if (finalCorsOrigins.contains("http://localhost:*")
                                    && requestOrigin.startsWith("http://localhost:")) {
                                matchedOrigin = requestOrigin;
                            } else if (finalCorsOrigins.contains("https://localhost:*")
                                    && requestOrigin.startsWith("https://localhost:")) {
                                matchedOrigin = requestOrigin;
                            }
                        }
                        if (matchedOrigin != null) {
                            response.setHeader("Access-Control-Allow-Origin", matchedOrigin);
                            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
                            response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Key");
                            response.setHeader("Access-Control-Max-Age", "86400");
                            response.setHeader("Vary", "Origin");
                            if (!"*".equals(matchedOrigin)) {
                                response.setHeader("Access-Control-Allow-Credentials", "true");
                            }
                        }
                    }

                    // Run ALL before-filters first (CloudflareIpFilter, ApiKeyAuth, CfAccessAuth).
                    // This ensures OPTIONS preflights are also subject to IP and auth checks.
                    // Individual filters that require credentials (ApiKeyAuth, CfAccessAuth) must
                    // skip OPTIONS themselves since browsers do not send credentials in preflights.
                    for (Handler filter : beforeFilters) {
                        filter.handle(ctx);
                        if (ctx.isHalted()) break;
                    }

                    if (!ctx.isHalted()) {
                        if ("OPTIONS".equals(request.getMethod())) {
                            ctx.status(204);
                        } else {
                            String key = request.getMethod() + ':' + path;
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

                PrintWriter writer = response.getWriter();
                writer.print(ctx.responseBody());
                writer.flush();
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
