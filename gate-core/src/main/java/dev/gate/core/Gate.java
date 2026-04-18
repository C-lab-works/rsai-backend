package dev.gate.core;

import dev.gate.annotation.AnnotationScanner;
import dev.gate.annotation.GateController;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.JarURLConnection;
import java.util.jar.JarFile;

public class Gate {

    private final Router router = new Router();
    private final AnnotationScanner scanner = new AnnotationScanner(router);
    private String corsOrigin = null;

    public void register(Object controller) {
        scanner.scan(controller);
    }

    public Gate cors(String allowedOrigin) {
        this.corsOrigin = allowedOrigin;
        return this;
    }

    public void start(int port) throws Exception {
        Server server = new Server(port);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        JettyWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) -> {
            router.getWsRoutes().forEach((path, wsHandler) -> {
                wsContainer.addMapping(path, (req, res) -> new WsAdapter(wsHandler));
            });
        });

        final String finalCorsOrigin = this.corsOrigin;
        context.addServlet(new ServletHolder(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                try {
                    if (finalCorsOrigin != null) {
                        response.setHeader("Access-Control-Allow-Origin", finalCorsOrigin);
                        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
                    }

                    if ("OPTIONS".equals(request.getMethod())) {
                        response.setStatus(204);
                        return;
                    }

                    String path = request.getPathInfo();
                    if (path == null) path = request.getServletPath();
                    String key = request.getMethod() + ":" + path;
                    Context ctx = new Context(path, request);

                    router.find(key).ifPresentOrElse(
                        handler -> {
                            handler.handle(ctx);
                            response.setStatus(200);
                        },
                        () -> {
                            response.setStatus(404);
                            ctx.result("404 Not Found");
                        }
                    );

                    ctx.headers().forEach(response::addHeader);
                    response.setContentType(ctx.contentType());
                    response.getWriter().print(ctx.responseBody());
                    response.getWriter().flush();
                } catch (Exception e) {
                    System.err.println("[Gate] handle error: " + e.getMessage());
                    response.setStatus(500);
                }
            }
        }), "/*");

        server.setHandler(context);

        System.out.println("starting on port " + port);
        server.start();
        server.join();
    }

    public void scan(String packageName) throws Exception {
        String packagePath = packageName.replace('.', '/');
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        var resources = classLoader.getResources(packagePath);
        if (!resources.hasMoreElements()) {
            System.err.println("notfound: " + packageName);
            return;
        }

        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            switch (url.getProtocol()) {
                case "file" -> scanDirectory(packageName, classLoader, new File(url.getFile()));
                case "jar"  -> scanJar(packageName, classLoader, url);
                default     -> System.err.println("unsupported: " + url.getProtocol());
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
        String packagePath = packageName.replace('.', '/') + "/";

        try (JarFile jar = conn.getJarFile()) {
            jar.stream()
                .filter(e -> e.getName().startsWith(packagePath) && e.getName().endsWith(".class"))
                .forEach(e -> {
                    String className = e.getName().replace('/', '.').replace(".class", "");
                    loadAndRegister(className, classLoader);
                });
        }
    }

        private void loadAndRegister(String className, ClassLoader classLoader) {
        try {
            Class<?> clazz = classLoader.loadClass(className);
            if (clazz.isAnnotationPresent(GateController.class)) {
                register(clazz.getDeclaredConstructor().newInstance());
                System.out.println("load:" + className);
            }
        } catch (Exception e) {
            System.err.println("failed: " + className + " - " + e.getMessage());
        }
    }
}
