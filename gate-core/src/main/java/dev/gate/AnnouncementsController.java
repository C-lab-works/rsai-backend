package dev.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Database;
import dev.gate.core.HttpCache;
import dev.gate.core.Logger;
import dev.gate.mapping.GetMapping;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

// /announcements エンドポイント
@GateController
public class AnnouncementsController {

    private static final Logger logger = new Logger(AnnouncementsController.class);
    private static final ObjectMapper MAPPER = dev.gate.core.Json.MAPPER;
    // s-maxage(エッジ)は 5 分: 管理画面の編集は CacheSync が自動 purge するため長くできる。
    // max-age(ブラウザ)は purge が届かないので 30 秒のまま。
    private static final String CACHE_CONTROL = "public, max-age=30, s-maxage=300, stale-while-revalidate=600";
    private static final String SELECT_ACTIVE_ANNOUNCEMENTS_SQL = """
            SELECT id, title, content, is_emergency
            FROM announcements
            ORDER BY is_emergency DESC, id DESC
            """;

    private static final AtomicReference<HttpCache.Entry> cache = new AtomicReference<>();

    public static String getCacheEtag() {
        HttpCache.Entry entry = cache.get();
        return entry != null ? entry.etag() : null;
    }

    // キャッシュを更新する（管理者更新・定期リフレッシュ共用）
    public static void refreshCache() throws Exception {
        try {
            byte[] json = fetchAnnouncementsFromDb();
            cache.set(HttpCache.entryOf(json));
            logger.info("announcements cache refreshed");
        } catch (Exception e) {
            logger.error("announcements refreshCache failed", e);
            throw e;
        }
    }

    // キャッシュからアナウンス内容を返す
    @GetMapping("/announcements")
    public void list(Context ctx) {
        HttpCache.Entry entry = cache.get();
        if (entry == null) {
            ctx.status(503).json(Map.of("error", "warming up"));
            return;
        }
        HttpCache.serveJson(ctx, entry, CACHE_CONTROL);
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

    private static void appendAnnouncement(ObjectNode n, ResultSet rs) throws Exception {
        n.put("id", rs.getInt("id"));
        n.put("title", rs.getString("title"));
        n.put("content", rs.getString("content"));
        n.put("is_emergency", rs.getInt("is_emergency") == 1);
    }
}
