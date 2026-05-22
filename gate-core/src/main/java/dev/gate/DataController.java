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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@GateController
public class DataController {

    private static final Logger logger = new Logger(DataController.class);
    private static final long CACHE_TTL_MS = 30 * 1000L;

    private static final ObjectMapper mapper = new ObjectMapper();

    // キャッシュはシリアライズ済みUTF-8バイト列を保持し、リクエストごとのString生成/byte変換を省く。
    private record CacheEntry(byte[] json, long expiresAt, long lastFetchedAt) {}
    private static final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicBoolean> refreshing = new ConcurrentHashMap<>();

    /**
     * 起動時にトップレベルのキャッシュを事前に埋める。
     */
    public static void prewarm() {
        DataController instance = new DataController();
        instance.warmKey("events", instance::buildEvents);
        instance.warmKey("food",   instance::buildFood);
        instance.warmKey("map",    instance::buildMap);
    }

    private void warmKey(String key, Builder builder) {
        try (Connection conn = Database.getConnection()) {
            byte[] json = mapper.writeValueAsBytes(builder.build(conn));
            long now = System.currentTimeMillis();
            cache.put(key, new CacheEntry(json, now + CACHE_TTL_MS, now));
            logger.info("prewarm OK: {}", key);
        } catch (Exception e) {
            logger.warn("prewarm スキップ ({}): {}", key, e.getMessage());
        }
    }

    @GetMapping("/events")
    public void events(Context ctx) { serve(ctx, "events", this::buildEvents); }

    @GetMapping("/food")
    public void food(Context ctx) { serve(ctx, "food", this::buildFood); }

    @GetMapping("/map")
    public void map(Context ctx) { serve(ctx, "map", this::buildMap); }

    @FunctionalInterface
    interface Builder { Object build(Connection conn) throws Exception; }

    private static final String CACHE_CONTROL = "public, max-age=30, s-maxage=30, stale-while-revalidate=60";
    private static final long MAX_STALE_MS = 3 * 3600 * 1000L;

    public static void clearCache() {
        // 全エントリーの有効期限を0にしてリフレッシュを促すが、データは保持してDB障害時のフォールバックにする。
        cache.replaceAll((k, v) -> new CacheEntry(v.json(), 0, v.lastFetchedAt()));
    }

    private void serve(Context ctx, String key, Builder builder) {
        CacheEntry entry = cache.get(key);
        if (entry != null) {
            long now = System.currentTimeMillis();
            // 「通常の状態(expiresAt!=0)」または「クリアされていても3時間以内」ならキャッシュを返す
            if (entry.expiresAt() != 0 || (now - entry.lastFetchedAt()) <= MAX_STALE_MS) {
                ctx.header("Cache-Control", CACHE_CONTROL);
                ctx.jsonBytes(entry.json());
                if (now >= entry.expiresAt()) {
                    scheduleRefresh(key, builder);
                }
                return;
            }
        }
        // 初回、またはキャッシュが古すぎる場合の同期取得
        try (Connection conn = Database.getConnection()) {
            byte[] json = mapper.writeValueAsBytes(builder.build(conn));
            long now = System.currentTimeMillis();
            cache.put(key, new CacheEntry(json, now + CACHE_TTL_MS, now));
            ctx.header("Cache-Control", CACHE_CONTROL);
            ctx.jsonBytes(json);
        } catch (Exception e) {
            logger.error("DB error serving '{}' (no valid cache): {}", key, e.getMessage());
            dev.gate.DiscordWebhook.sendError("GET", "/"+key, 503, "DB error (no cache): " + e.getMessage());
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable"));
        }
    }

    private static final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> refreshFailCounts = new ConcurrentHashMap<>();

    private void scheduleRefresh(String key, Builder builder) {
        AtomicBoolean flag = refreshing.computeIfAbsent(key, k -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) return;
        Thread.ofVirtual().start(() -> {
            try (Connection conn = Database.getConnection()) {
                byte[] json = mapper.writeValueAsBytes(builder.build(conn));
                long now = System.currentTimeMillis();
                cache.put(key, new CacheEntry(json, now + CACHE_TTL_MS, now));
                refreshFailCounts.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicInteger(0)).set(0);
            } catch (Exception e) {
                logger.warn("Background cache refresh failed for '{}': {}", key, e.getMessage());
                int fails = refreshFailCounts.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicInteger(0)).incrementAndGet();
                if (fails == 3) {
                    dev.gate.DiscordWebhook.sendError("CACHE", "/"+key, 500, "Background refresh failed 3 times: " + e.getMessage());
                }
            } finally {
                flag.set(false);
            }
        });
    }

    private Object buildEvents(Connection conn) throws Exception {
        ObjectNode root = mapper.createObjectNode();

        ArrayNode cats = root.putArray("categories");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, name FROM categories ORDER BY id")) {
            while (rs.next()) {
                ObjectNode c = cats.addObject();
                c.put("id",   rs.getInt("id"));
                c.put("name", rs.getString("name"));
            }
        }

        ArrayNode locs = root.putArray("locations");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
               "SELECT id, name, floor, location_code, x, y FROM locations ORDER BY floor, id")) {
            while (rs.next()) {
                ObjectNode l = locs.addObject();
                l.put("id",    rs.getInt("id"));
                l.put("name",  rs.getString("name"));
                l.put("floor", rs.getInt("floor"));
                putStringOrNull(l, "locationCode", rs.getString("location_code"));
                putDoubleOrNull(l, "x", rs);
                putDoubleOrNull(l, "y", rs);
            }
        }

        Map<Integer, List<Integer>> catMap = new HashMap<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
               "SELECT project_id, category_id FROM project_categories ORDER BY project_id, category_id")) {
            while (rs.next()) {
                catMap.computeIfAbsent(rs.getInt("project_id"), k -> new ArrayList<>())
                      .add(rs.getInt("category_id"));
            }
        }

        ArrayNode projects = root.putArray("projects");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
               "SELECT id, title, organizer, description, image_url, location_id " +
               "FROM projects ORDER BY id")) {
            while (rs.next()) {
                ObjectNode p = projects.addObject();
                int id = rs.getInt("id");
                p.put("id", id);
                p.put("title", rs.getString("title"));
                putStringOrNull(p, "organizer",   rs.getString("organizer"));
                putStringOrNull(p, "description", rs.getString("description"));
                putStringOrNull(p, "imageUrl",    rs.getString("image_url"));
                int locId = rs.getInt("location_id");
                if (!rs.wasNull()) p.put("locationId", locId);
            }
        }

        ArrayNode projectCategories = root.putArray("projectCategories");
        catMap.forEach((projectId, catIds) ->
            catIds.forEach(catId -> {
                ObjectNode pc = projectCategories.addObject();
                pc.put("projectId",  projectId);
                pc.put("categoryId", catId);
            })
        );

        ArrayNode timetables = root.putArray("timetables");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
               "SELECT id, project_id, location_id, event_date, is_all_day, start_time, end_time " +
               "FROM timetables ORDER BY event_date, start_time")) {
            while (rs.next()) {
                addTimetableRow(timetables.addObject(), rs);
            }
        }

        return root;
    }

    private Object buildFood(Connection conn) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode foods = root.putArray("foods");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, name, description, image_url FROM foods ORDER BY id")) {
            while (rs.next()) {
                ObjectNode f = foods.addObject();
                f.put("id",   rs.getInt("id"));
                f.put("name", rs.getString("name"));
                putStringOrNull(f, "description", rs.getString("description"));
                putStringOrNull(f, "imageUrl",    rs.getString("image_url"));
            }
        }
        ArrayNode menus = root.putArray("menus");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, food_id, name, price, description, is_sold_out FROM menus ORDER BY food_id, id")) {
            while (rs.next()) {
                ObjectNode m = menus.addObject();
                m.put("id",     rs.getInt("id"));
                m.put("foodId", rs.getInt("food_id"));
                m.put("name",   rs.getString("name"));
                int price = rs.getInt("price");
                if (!rs.wasNull()) m.put("price", price);
                putStringOrNull(m, "description", rs.getString("description"));
                int soldOut = rs.getInt("is_sold_out");
                if (!rs.wasNull()) m.put("isSoldOut", soldOut == 1);
            }
        }
        return root;
    }

    private Object buildMap(Connection conn) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode locs = root.putArray("locations");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, name, floor, location_code, svg_id, x, y FROM locations ORDER BY floor, id")) {
            while (rs.next()) {
                ObjectNode l = locs.addObject();
                l.put("id",    rs.getInt("id"));
                l.put("name",  rs.getString("name"));
                l.put("floor", rs.getInt("floor"));
                putStringOrNull(l, "locationCode", rs.getString("location_code"));
                int svgId = rs.getInt("svg_id");
                if (!rs.wasNull()) l.put("svgId", svgId);
                putDoubleOrNull(l, "x", rs);
                putDoubleOrNull(l, "y", rs);
            }
        }
        return root;
    }

    private void addTimetableRow(ObjectNode t, ResultSet rs) throws Exception {
        t.put("id",         rs.getInt("id"));
        t.put("projectId",  rs.getInt("project_id"));
        t.put("locationId", rs.getInt("location_id"));
        t.put("date",       rs.getString("event_date"));
        t.put("isAllDay",   rs.getInt("is_all_day") == 1);
        putStringOrNull(t, "start", rs.getString("start_time"));
        putStringOrNull(t, "end",   rs.getString("end_time"));
    }

    private void putStringOrNull(ObjectNode node, String key, String value) {
        if (value != null) node.put(key, value);
    }

    private void putDoubleOrNull(ObjectNode node, String key, ResultSet rs) throws Exception {
        double v = rs.getDouble(key);
        if (!rs.wasNull()) node.put(key, v);
    }
}
