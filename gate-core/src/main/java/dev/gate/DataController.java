package dev.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Database;
import dev.gate.core.HttpCache;
import dev.gate.mapping.GetMapping;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

@GateController
public class DataController {

    private static final ObjectMapper MAPPER = dev.gate.core.Json.MAPPER;
    private static final String CACHE_CONTROL = "public, max-age=60, s-maxage=300, stale-while-revalidate=600";
    private static final ConcurrentHashMap<String, HttpCache.Entry> cache = new ConcurrentHashMap<>();

    public static Map<String, String> getCacheEtags() {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        cache.forEach((k, v) -> result.put(k, v.etag()));
        return result;
    }

    public void refreshAll() throws Exception {
        List<Future<Void>> futures = new ArrayList<>();
        futures.add(Main.bg.submit((Callable<Void>) () -> { refreshKey("events", this::buildEvents); return null; }));
        futures.add(Main.bg.submit((Callable<Void>) () -> { refreshKey("food",   this::buildFood); return null; }));
        futures.add(Main.bg.submit((Callable<Void>) () -> { refreshKey("map",    this::buildMap); return null; }));
        Exception err = null;
        for (Future<Void> f : futures) {
            try { f.get(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw ex; }
            catch (ExecutionException ex) { if (err == null) err = (Exception) ex.getCause(); }
        }
        if (err != null) throw err;
    }

    private void refreshKey(String key, Builder builder) throws Exception {
        try (Connection conn = Database.getConnection()) {
            byte[] json = MAPPER.writeValueAsBytes(builder.build(conn));
            cache.put(key, HttpCache.entryOf(json));
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

    private void serve(Context ctx, String key) {
        HttpCache.Entry entry = cache.get(key);
        if (entry == null) {
            ctx.status(503).json(Map.of("error", "warming up"));
            return;
        }
        HttpCache.serveJson(ctx, entry, CACHE_CONTROL);
    }

    // /events

    private Object buildEvents(Connection conn) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();

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
                putStringOrNull(l, "location_code", rs.getString("location_code"));
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
                putStringOrNull(p, "image_url",   rs.getString("image_url"));
                int locId = rs.getInt("location_id");
                if (!rs.wasNull()) p.put("location_id", locId);
            }
        }

        ArrayNode projectCategories = root.putArray("project_categories");
        catMap.forEach((projectId, catIds) ->
            catIds.forEach(catId -> {
                ObjectNode pc = projectCategories.addObject();
                pc.put("project_id",  projectId);
                pc.put("category_id", catId);
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
        ObjectNode root = MAPPER.createObjectNode();

        ArrayNode foods = root.putArray("foods");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
               "SELECT id, name, description, image_url FROM foods ORDER BY id")) {
            while (rs.next()) {
                ObjectNode f = foods.addObject();
                f.put("id",   rs.getInt("id"));
                f.put("name", rs.getString("name"));
                putStringOrNull(f, "description", rs.getString("description"));
                putStringOrNull(f, "image_url",   rs.getString("image_url"));
            }
        }

        ArrayNode menus = root.putArray("menus");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
               "SELECT id, food_id, name, price, description, is_sold_out " +
               "FROM menus ORDER BY food_id, id")) {
            while (rs.next()) {
                ObjectNode m = menus.addObject();
                m.put("id",      rs.getInt("id"));
                m.put("food_id", rs.getInt("food_id"));
                m.put("name",    rs.getString("name"));
                int price = rs.getInt("price");
                if (!rs.wasNull()) m.put("price", price);
                putStringOrNull(m, "description", rs.getString("description"));
                int soldOut = rs.getInt("is_sold_out");
                if (!rs.wasNull()) m.put("is_sold_out", soldOut == 1);
            }
        }

        return root;
    }

    // /map
    private Object buildMap(Connection conn) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();

        ArrayNode locs = root.putArray("locations");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
               "SELECT id, name, floor, location_code, svg_id, x, y FROM locations ORDER BY floor, id")) {
            while (rs.next()) {
                ObjectNode l = locs.addObject();
                l.put("id",    rs.getInt("id"));
                l.put("name",  rs.getString("name"));
                l.put("floor", rs.getInt("floor"));
                putStringOrNull(l, "location_code", rs.getString("location_code"));
                int svgId = rs.getInt("svg_id");
                if (!rs.wasNull()) l.put("svg_id", svgId);
                putDoubleOrNull(l, "x", rs);
                putDoubleOrNull(l, "y", rs);
            }
        }

        return root;
    }

    // フィールド名の短縮/rename
    private void addTimetableRow(ObjectNode t, ResultSet rs) throws Exception {
        t.put("id",          rs.getInt("id"));
        t.put("project_id",  rs.getInt("project_id"));
        t.put("location_id", rs.getInt("location_id"));
        t.put("date",        rs.getString("event_date"));
        t.put("is_all_day",  rs.getInt("is_all_day") == 1);
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
