package dev.gate.annotation;

import dev.gate.core.Router;
import dev.gate.core.Context;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PostMapping;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class AnnotationScanner {

    private final Router router;

    public AnnotationScanner(Router router) {
        this.router = router;
    }

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
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            System.err.println("server error: " + method.getName() + " - " + cause.getMessage());
            ctx.result("500 Internal Server Error");
        } catch (IllegalAccessException e) {
            System.err.println("accessfailed: " + method.getName() + " - " + e.getMessage());
            ctx.result("500 Internal Server Error");
        }
    }
}
