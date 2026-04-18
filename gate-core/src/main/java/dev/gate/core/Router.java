package dev.gate.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class Router {

    private final Map<String, Handler> routes = new HashMap<>();
    private final Map<String, WsHandle> wsRoutes = new HashMap<>();

    public void register(String key, Handler handler) {
        routes.put(key, handler);
    }

    public Optional<Handler> find(String key) {
        return Optional.ofNullable(routes.get(key));
    }

    public void registerWs(String path, WsHandle handler) {
        wsRoutes.put(path, handler);
    }

    public Optional<WsHandle> findWs(String path) {
        return Optional.ofNullable(wsRoutes.get(path));
    }
    public Map<String, WsHandle> getWsRoutes() {
        return wsRoutes;
    }
}
