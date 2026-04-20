package dev.gate.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

public class Router {

    private static final Logger log = new Logger(Router.class);
    private static final Pattern SLASH = Pattern.compile("/", Pattern.LITERAL);
    private static final int CACHE_MAX_SIZE = 1024;

    public record RouteMatch(Handler handler, Map<String, String> pathParams) {}

    private record PatternRoute(String method, String[] segments, String[] paramNames, Handler handler) {}

    // Cache key for pattern route matches (method + path → matched PatternRoute index + params)
    private record CachedMatch(int routeIndex, Map<String, String> params) {}

    private final Map<String, Handler> exactRoutes = new ConcurrentHashMap<>();
    private final List<PatternRoute> patternRoutes = new CopyOnWriteArrayList<>();
    private final Map<String, WsHandler> wsRoutes = new ConcurrentHashMap<>();
    private final Map<String, CachedMatch> patternCache = new ConcurrentHashMap<>();

    private static String normalizePath(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    public void register(String key, Handler handler) {
        int colonIdx = key.indexOf(':');
        String method = key.substring(0, colonIdx);
        String rawPath = key.substring(colonIdx + 1);
        String path = normalizePath(rawPath);
        String normalizedKey = method + ":" + path;

        if (path.contains("{")) {
            String[] segments = SLASH.split(path, -1);
            String[] paramNames = new String[segments.length];
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < segments.length; i++) {
                if (segments[i].startsWith("{") && segments[i].endsWith("}")) {
                    String name = segments[i].substring(1, segments[i].length() - 1);
                    if (name.isEmpty()) {
                        throw new IllegalArgumentException("Empty path parameter name in: " + path);
                    }
                    if (!seen.add(name)) {
                        throw new IllegalArgumentException("Duplicate path parameter '{" + name + "}' in: " + path);
                    }
                    paramNames[i] = name;
                    segments[i] = null; // null = param slot
                }
            }

            // ルート競合検出
            for (PatternRoute existing : patternRoutes) {
                if (existing.method().equals(method) && existing.segments().length == segments.length) {
                    boolean ambiguous = true;
                    for (int i = 0; i < segments.length; i++) {
                        boolean curIsParam = segments[i] == null;
                        boolean exIsParam = existing.segments()[i] == null;
                        if (!curIsParam && !exIsParam && !segments[i].equals(existing.segments()[i])) {
                            ambiguous = false;
                            break;
                        }
                    }
                    if (ambiguous) {
                        log.warn("Ambiguous route: {} {} may conflict with an existing pattern route", method, path);
                    }
                }
            }

            patternRoutes.add(new PatternRoute(method, segments, paramNames, handler));
            patternCache.clear(); // Invalidate cache on route mutation
        } else {
            exactRoutes.put(normalizedKey, handler);
        }
    }

    public Optional<RouteMatch> find(String key) {
        // 完全一致を優先
        Handler exact = exactRoutes.get(key);
        if (exact != null) {
            return Optional.of(new RouteMatch(exact, Map.of()));
        }

        // キャッシュチェック
        CachedMatch cached = patternCache.get(key);
        if (cached != null) {
            PatternRoute route = patternRoutes.get(cached.routeIndex());
            return Optional.of(new RouteMatch(route.handler(), cached.params()));
        }

        // パターンマッチ
        int colonIdx = key.indexOf(':');
        String method = key.substring(0, colonIdx);
        String path = key.substring(colonIdx + 1);
        String[] requestSegments = SLASH.split(path, -1);

        List<PatternRoute> routes = patternRoutes;
        for (int ri = 0; ri < routes.size(); ri++) {
            PatternRoute route = routes.get(ri);
            if (!route.method().equals(method)) continue;
            if (route.segments().length != requestSegments.length) continue;

            boolean match = true;
            for (int i = 0; i < route.segments().length; i++) {
                if (route.segments()[i] == null) {
                    if (requestSegments[i].isEmpty()) {
                        match = false;
                        break;
                    }
                } else if (!route.segments()[i].equals(requestSegments[i])) {
                    match = false;
                    break;
                }
            }

            if (match) {
                // マッチ確認後にパラメータを抽出
                Map<String, String> params = new HashMap<>();
                for (int i = 0; i < route.segments().length; i++) {
                    if (route.segments()[i] == null) {
                        params.put(route.paramNames()[i], requestSegments[i]);
                    }
                }
                Map<String, String> immutableParams = Map.copyOf(params);

                // キャッシュに保存（サイズ制限付き）
                if (patternCache.size() < CACHE_MAX_SIZE) {
                    patternCache.put(key, new CachedMatch(ri, immutableParams));
                }

                return Optional.of(new RouteMatch(route.handler(), immutableParams));
            }
        }

        return Optional.empty();
    }

    public void registerWs(String path, WsHandler handler) {
        wsRoutes.put(path, handler);
    }

    public Map<String, WsHandler> getWsRoutes() {
        return Map.copyOf(wsRoutes);
    }
}
