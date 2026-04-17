package dev.gate.core;

import dev.gate.annotation.AnnotationScanner;
import dev.gate.annotation.GateController;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.JarURLConnection;
import java.util.jar.JarFile;

public class Gate {
    public static void main(String[] args) throws Exception {
        Gate gate = new Gate();
        gate.scan("app");
        gate.start(8080);
    }

    private final Router router = new Router();
    private final AnnotationScanner scanner = new AnnotationScanner(router);

    public void register(Object controller) {
        scanner.scan(controller);
    }

    public void start(int port) throws Exception {
        Server server = new Server(port);

        server.setHandler(new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest,
                               HttpServletRequest request,
                               HttpServletResponse response) throws IOException {

                String key = request.getMethod() + ":" + target;
                Context ctx = new Context(target, request);

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
                baseRequest.setHandled(true);
            }
        });

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
