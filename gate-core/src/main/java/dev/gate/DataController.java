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

@GateController
public class DataController {

    private static final Logger logger = new Logger(DataController.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private record CacheEntry(byte[] json, long lastFetchedAt) {}
    private static final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public static void prewarm() {
        DataController instance = new DataController();
        try {
            instance.refreshAll();
            logger.info("Prewarm completed successfully");
        } catch (Exception e) {
            logger.warn("Prewarm failed: {}", e.getMessage());
        }
    }

    public void refreshAll() throws Exception {
        refreshKey("events", this::buildEvents);
        refreshKey("food",   this::buildFood);
        refreshKey("map",    this::buildMap);
    }

    private void refreshKey(String key, Builder builder) throws Exception {
        try (Connection conn = Database.getConnection()) {
            byte[] json = mapper.writeValueAsBytes(builder.build(conn));
            long now = System.currentTimeMillis();
            cache.put(key, new CacheEntry(json, now));
        }
    }

    @GetMapping("/events")
    public void events(Context ctx) { serve(ctx, "events"); }

    @GetMapping("/food")
    public void food(Context ctx) { serve(ctx, "food"); }

    @GetMapping("/map")
    public void map(Context ctx) { serve(ctx, "map"); }

    @FunctionalInterface
    interface Builder { Object build(Connection conn) throws Exception; }

    private static final String CACHE_CONTROL = "public, max-age=30, s-maxage=30, stale-while-revalidate=60";

    private void serve(Context ctx, String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            ctx.status(503).json(Map.of("error", "warming up"));
            return;
        }
        ctx.header("Cache-Control", CACHE_CONTROL);
        ctx.jsonBytes(entry.json());
    }

    // /events

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

    // /food

    private Object buildFood(Connection conn) throws Exception {
        ObjectNode root = mapper.createObjectNode();

        ArrayNode foods = root.putArray("foods");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
               "SELECT id, name, description, image_url FROM foods ORDER BY id")) {
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
             ResultSet rs = s.executeQuery(
               "SELECT id, food_id, name, price, description, is_sold_out " +
               "FROM menus ORDER BY food_id, id")) {
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

    // /map

    private Object buildMap(Connection conn) throws Exception {
        ObjectNode root = mapper.createObjectNode();

        ArrayNode locs = root.putArray("locations");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
               "SELECT id, name, floor, location_code, svg_id, x, y FROM locations ORDER BY floor, id")) {
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

    // util

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
