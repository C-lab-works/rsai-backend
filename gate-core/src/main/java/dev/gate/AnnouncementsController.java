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
import java.util.concurrent.atomic.AtomicBoolean;

@GateController
public class AnnouncementsController {

    private static final Logger logger = new Logger(AnnouncementsController.class);
    private static final long CACHE_TTL_MS = 30_000L;
    private final ObjectMapper mapper = new ObjectMapper();

    private record CacheEntry(Object data, long expiresAt) {}
    private static final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final AtomicBoolean refreshing = new AtomicBoolean(false);

    public static void clearCache() { cache.clear(); }

    @GetMapping("/announcements")
    public void list(Context ctx) {
        CacheEntry entry = cache.get("announcements");
        if (entry != null) {
            ctx.header("Cache-Control", "public, max-age=30");
            ctx.json(entry.data());
            if (System.currentTimeMillis() >= entry.expiresAt()) {
                scheduleRefresh();
            }
            return;
        }
        // 初回のみ同期取得
        try {
            Object data = fetchAnnouncementsFromDb();
            cache.put("announcements", new CacheEntry(data, System.currentTimeMillis() + CACHE_TTL_MS));
            ctx.header("Cache-Control", "public, max-age=30");
            ctx.json(data);
        } catch (Exception e) {
            logger.error("announcements error", e);
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable"));
        }
    }

    private void scheduleRefresh() {
        if (!refreshing.compareAndSet(false, true)) return;
        Thread.ofVirtual().start(() -> {
            try {
                Object data = fetchAnnouncementsFromDb();
                cache.put("announcements", new CacheEntry(data, System.currentTimeMillis() + CACHE_TTL_MS));
            } catch (Exception e) {
                logger.warn("Background announcements cache refresh failed: " + e.getMessage());
            } finally {
                refreshing.set(false);
            }
        });
    }

    private Object fetchAnnouncementsFromDb() throws Exception {
        try (Connection conn = Database.getConnection();
             Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT id, title, content, is_emergency, display_from, display_until " +
                "FROM announcements " +
                "WHERE (display_from  IS NULL OR display_from  <= NOW()) " +
                "  AND (display_until IS NULL OR display_until >= NOW()) " +
                "ORDER BY is_emergency DESC, id DESC")) {
            ObjectNode root = mapper.createObjectNode();
            ArrayNode arr  = root.putArray("announcements");
            while (rs.next()) {
                ObjectNode n = arr.addObject();
                n.put("id",          rs.getInt("id"));
                n.put("title",       rs.getString("title"));
                n.put("content",     rs.getString("content"));
                n.put("isEmergency", rs.getInt("is_emergency") == 1);
                String from  = rs.getString("display_from");
                String until = rs.getString("display_until");
                if (from  != null) n.put("displayFrom",  from);
                if (until != null) n.put("displayUntil", until);
            }
            return root;
        }
    }
}
