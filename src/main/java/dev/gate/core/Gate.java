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


/** フレームワークのエントリーポイント。サーバーの起動とコントローラーの登録を担う */
public class Gate {

    public static void main(String[] args) throws Exception {
        Gate gate = new Gate();
        gate.register(new Gate());
        gate.scan("app");
        gate.start(8080);
    }

    private final Router router = new Router();
    private final AnnotationScanner scanner = new AnnotationScanner(router);

    /** コントローラーを登録する。アノテーションをスキャンしてルートに追加する */
    public void register(Object controller) {
        scanner.scan(controller);
    }

    /** Jetty サーバーを起動し、全リクエストをルーターに委譲する */
    public void start(int port) throws Exception {
        Server server = new Server(port);

        server.setHandler(new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest,
                               HttpServletRequest request,
                               HttpServletResponse response) throws IOException {

                // "GET:/hello" のようなキーでルートを検索する
                String key = request.getMethod() + ":" + target;
                Context ctx = new Context(target, request);

                Handler handler = router.find(key);
                if (handler != null) {
                    handler.handle(ctx);
                    response.setStatus(200);
                } else {
                    response.setStatus(404);
                    ctx.result("404 Not Found");
                }

                ctx.headers().forEach(response::addHeader);
                response.setContentType(ctx.contentType());
                response.getWriter().print(ctx.responseBody());
                baseRequest.setHandled(true);
            }
        });

        System.out.println("Gate starting on port " + port);
        server.start();
        server.join();
    }

    public void scan(String packageName) throws Exception {
        String packagePath = packageName.replace('.', '/');
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        var resources = classLoader.getResources(packagePath);
        while (resources.hasMoreElements()) {
            File directory = new File(resources.nextElement().getFile());
            scanDirectory(packageName, classLoader, directory);
        }
    }

    private void scanDirectory(String packageName, ClassLoader classLoader, File directory) throws Exception {
        if (!directory.isDirectory()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (!file.getName().endsWith(".class")) {
                continue;
            }

            String className = file.getName().replace(".class", "");
            Class<?> clazz = classLoader.loadClass(packageName + "." + className);
            if (clazz.isAnnotationPresent(GateController.class)) {
                register(clazz.getDeclaredConstructor().newInstance());
            }
        }
    }
}