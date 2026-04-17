package dev.gate.annotation;

import dev.gate.core.Router;
import dev.gate.core.Context;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PostMapping;
import java.lang.reflect.Method;

/** コントローラーのメソッドをリフレクションでスキャンし、ルーターに登録する */
public class AnnotationScanner {

    private final Router router;

    public AnnotationScanner(Router router) {
        this.router = router;
    }

    /** コントローラーの全メソッドを走査し、@GetMapping / @PostMapping を検出して登録する */
    public void scan(Object controller) {
        Class<?> clazz = controller.getClass();

        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(GetMapping.class)) {
                String path = method.getAnnotation(GetMapping.class).value();
                router.register("GET:" + path, ctx -> invoke(method, controller, ctx));
            }

            if (method.isAnnotationPresent(PostMapping.class)) {
                String path = method.getAnnotation(PostMapping.class).value();
                router.register("POST:" + path, ctx -> invoke(method, controller, ctx));
            }
        }
    }

    private void invoke(Method method, Object controller, Context ctx) {
        try {
            method.invoke(controller, ctx);
        } catch (Exception e) {
            ctx.result("500 Internal Server Error");
        }
    }
}
