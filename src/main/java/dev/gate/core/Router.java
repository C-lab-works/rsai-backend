package dev.gate.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class Router {

    private final Map<String, Handler> routes = new HashMap<>();

    public void register(String key, Handler handler) {
        routes.put(key, handler);
    }

    public Optional<Handler> find(String key) {
        return Optional.ofNullable(routes.get(key));
    }
}
