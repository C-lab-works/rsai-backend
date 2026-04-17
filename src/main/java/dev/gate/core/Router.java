package dev.gate.core;

import java.util.HashMap;
import java.util.Map;

/** "GET:/hello" のようなキーとハンドラーを対応付けるルーティングテーブル */
public class Router {

    private final Map<String, Handler> routes = new HashMap<>();

    public void register(String key, Handler handler) {
        routes.put(key, handler);
    }

    /** 対応するハンドラーがなければ null を返す */
    public Handler find(String key) {
        return routes.get(key);
    }
}
