package dev.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Database;
import dev.gate.core.Logger;
import dev.gate.mapping.GetMapping;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// /announcements エンドポイント
@GateController
public class AnnouncementsController {

    private static final Logger logger = new Logger(AnnouncementsController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CACHE_CONTROL = "public, max-age=30, s-maxage=30, stale-while-revalidate=60";
    private static final String CACHE_KEY = "announcements";
    private static final String SELECT_ACTIVE_ANNOUNCEMENTS_SQL = """
            SELECT id, title, content, is_emergency, display_from, display_until
            FROM announcements
            WHERE display_from IS NOT NULL AND display_from <= NOW()
              AND display_until IS NOT NULL AND display_until >= NOW()
            ORDER BY is_emergency DESC, id DESC
            """;

    private record CacheEntry(byte[] json) {}
    private static final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    // キャッシュを更新するやつ　管理者更新用
    public static void refreshCache() throws Exception {
        try {
            byte[] json = fetchAnnouncementsFromDb();
            cache.put(CACHE_KEY, new CacheEntry(json));
            logger.info("announcements cache refreshed");
        } catch (Exception e) {
            logger.error("announcements refreshCache failed", e);
            throw e;
        }
    }

    // キャッシュからアナウンス内容を返す
    @GetMapping("/announcements")
    public void list(Context ctx) {
        CacheEntry entry = cache.get(CACHE_KEY);
        if (entry == null) {
            ctx.status(503).json(Map.of("error", "warming up"));
            return;
        }
        ctx.header("Cache-Control", CACHE_CONTROL);
        ctx.jsonBytes(entry.json());
    }

    // DBからjsonへの変換
    private static byte[] fetchAnnouncementsFromDb() throws Exception {
        try (Connection conn = Database.getConnection();
             Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(SELECT_ACTIVE_ANNOUNCEMENTS_SQL)) {
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode arr = root.putArray("announcements");
            while (rs.next()) {
                appendAnnouncement(arr.addObject(), rs);
            }
            return MAPPER.writeValueAsBytes(root);
        }
    }

    // nullチェックとフィールド名変更
    private static void appendAnnouncement(ObjectNode n, ResultSet rs) throws Exception {
        n.put("id", rs.getInt("id"));
        n.put("title", rs.getString("title"));
        n.put("content", rs.getString("content"));
        n.put("is_emergency", rs.getInt("is_emergency") == 1);

        putIfNotNull(n, "display_from", rs.getString("display_from"));
        putIfNotNull(n, "display_until", rs.getString("display_until"));
    }

    private static void putIfNotNull(ObjectNode n, String fieldName, String value) {
        if (value != null) {
            n.put(fieldName, value);
        }
    }
}
