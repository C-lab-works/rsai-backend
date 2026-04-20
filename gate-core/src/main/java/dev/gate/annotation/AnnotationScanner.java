package dev.gate.annotation;

import dev.gate.core.Context;
import dev.gate.core.Logger;
import dev.gate.core.Router;
import dev.gate.core.WsContext;
import dev.gate.mapping.DeleteMapping;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PatchMapping;
import dev.gate.mapping.PostMapping;
import dev.gate.mapping.PutMapping;
import dev.gate.mapping.WsMapping;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class AnnotationScanner {

    private static final Logger logger = new Logger(AnnotationScanner.class);
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private final Router router;

    public AnnotationScanner(Router router) {
        this.router = router;
    }

    public void scan(Object controller) {
        Class<?> clazz = controller.getClass();

        for (Method method : clazz.getMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            if (Modifier.isStatic(method.getModifiers())) continue;
            if (method.getDeclaringClass() == Object.class) continue;

            registerHttp(method, controller, GetMapping.class, "GET");
            registerHttp(method, controller, PostMapping.class, "POST");
            registerHttp(method, controller, PutMapping.class, "PUT");
            registerHttp(method, controller, DeleteMapping.class, "DELETE");
            registerHttp(method, controller, PatchMapping.class, "PATCH");

            if (method.isAnnotationPresent(WsMapping.class)) {
                validateWsSignature(method);
                String path = method.getAnnotation(WsMapping.class).value();
                MethodHandle mh = bindHandle(method, controller);
                router.registerWs(path, (ctx, message) -> {
                    try {
                        mh.invoke(ctx, message);
                    } catch (RuntimeException | Error e) {
                        throw e;
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                });
            }
        }
    }

    private <A extends Annotation> void registerHttp(Method method, Object controller, Class<A> annotationType, String httpMethod) {
        if (!method.isAnnotationPresent(annotationType)) return;
        validateHttpSignature(method);
        String path;
        try {
            path = (String) annotationType.getMethod("value").invoke(method.getAnnotation(annotationType));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read annotation value from " + method.getName(), e);
        }
        MethodHandle mh = bindHandle(method, controller);
        router.register(httpMethod + ":" + path, ctx -> invokeHandle(mh, ctx));
    }

    private void validateHttpSignature(Method method) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 1 || !Context.class.isAssignableFrom(params[0])) {
            throw new IllegalStateException(
                "HTTP handler " + method.getDeclaringClass().getSimpleName() + "." + method.getName()
                + " must accept exactly one Context parameter"
            );
        }
    }

    private void validateWsSignature(Method method) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 2 || !WsContext.class.isAssignableFrom(params[0]) || !String.class.isAssignableFrom(params[1])) {
            throw new IllegalStateException(
                "WebSocket handler " + method.getDeclaringClass().getSimpleName() + "." + method.getName()
                + " must accept (WsContext, String) parameters"
            );
        }
    }

    private MethodHandle bindHandle(Method method, Object controller) {
        try {
            return LOOKUP.unreflect(method).bindTo(controller);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot access handler: " + method.getName(), e);
        }
    }

    private void invokeHandle(MethodHandle mh, Context ctx) {
        try {
            mh.invoke(ctx);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
