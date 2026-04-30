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

@GateController
public class DataController {

    private static final Logger logger = new Logger(DataController.class);
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private final ObjectMapper mapper = new ObjectMapper();

    private record CacheEntry(Object data, long expiresAt) {}
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @GetMapping("/events")
    public void events(Context ctx) { serve(ctx, "events", this::buildEvents); }

    @GetMapping("/food")
    public void food(Context ctx) { serve(ctx, "food", this::buildFood); }

    @GetMapping("/map")
    public void map(Context ctx) { serve(ctx, "map", this::buildMap); }

    // ── generic cache+serve ───────────────────────────────────

    @FunctionalInterface
    interface Builder { Object build(Connection conn) throws Exception; }

    private void serve(Context ctx, String key, Builder builder) {
        CacheEntry entry = cache.get(key);
        if (entry != null && System.currentTimeMillis() < entry.expiresAt()) {
            ctx.json(entry.data());
            return;
        }
        try (Connection conn = Database.getConnection()) {
            Object data = builder.build(conn);
            cache.put(key, new CacheEntry(data, System.currentTimeMillis() + CACHE_TTL_MS));
            ctx.json(data);
        } catch (Exception e) {
            logger.error("DB error serving '{}': {}", key, e.getMessage());
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable"));
        }
    }

    // ── /events ───────────────────────────────────────────────

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
                "SELECT id, name, floor, svg_id, is_stage, tracks_congestion FROM locations ORDER BY floor, id")) {
            while (rs.next()) {
                ObjectNode l = locs.addObject();
                l.put("id",               rs.getInt("id"));
                l.put("name",             rs.getString("name"));
                l.put("floor",            rs.getInt("floor"));
                putStringOrNull(l, "svgId", rs.getString("svg_id"));
                l.put("isStage",          rs.getInt("is_stage") == 1);
                l.put("tracksCongestion", rs.getInt("tracks_congestion") == 1);
            }
        }

        ArrayNode projects = root.putArray("projects");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT id, category_id, title, organizer, description, image_url " +
                "FROM projects ORDER BY id")) {
            while (rs.next()) {
                ObjectNode p = projects.addObject();
                p.put("id", rs.getInt("id"));
                int catId = rs.getInt("category_id");
                if (!rs.wasNull()) p.put("categoryId", catId);
                p.put("title", rs.getString("title"));
                putStringOrNull(p, "organizer",   rs.getString("organizer"));
                putStringOrNull(p, "description", rs.getString("description"));
                putStringOrNull(p, "imageUrl",    rs.getString("image_url"));
            }
        }

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

    // ── /food ─────────────────────────────────────────────────

    private Object buildFood(Connection conn) throws Exception {
        ObjectNode root = mapper.createObjectNode();

        ArrayNode projects = root.putArray("projects");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT p.id, p.title, p.organizer, p.description " +
                "FROM projects p " +
                "JOIN categories c ON c.id = p.category_id " +
                "WHERE c.name = '飲食' " +
                "ORDER BY p.id")) {
            while (rs.next()) {
                ObjectNode p = projects.addObject();
                p.put("id",    rs.getInt("id"));
                p.put("title", rs.getString("title"));
                putStringOrNull(p, "organizer",   rs.getString("organizer"));
                putStringOrNull(p, "description", rs.getString("description"));
            }
        }

        ArrayNode timetables = root.putArray("timetables");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT t.id, t.project_id, t.location_id, t.event_date, t.is_all_day, t.start_time, t.end_time " +
                "FROM timetables t " +
                "JOIN projects p ON p.id = t.project_id " +
                "JOIN categories c ON c.id = p.category_id " +
                "WHERE c.name = '飲食' " +
                "ORDER BY t.event_date, t.start_time")) {
            while (rs.next()) {
                addTimetableRow(timetables.addObject(), rs);
            }
        }

        return root;
    }

    // ── /map ─────────────────────────────────────────────────

    private Object buildMap(Connection conn) throws Exception {
        ObjectNode root = mapper.createObjectNode();

        ArrayNode locs = root.putArray("locations");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT id, name, floor, svg_id, is_stage, tracks_congestion FROM locations ORDER BY floor, id")) {
            while (rs.next()) {
                ObjectNode l = locs.addObject();
                l.put("id",               rs.getInt("id"));
                l.put("name",             rs.getString("name"));
                l.put("floor",            rs.getInt("floor"));
                putStringOrNull(l, "svgId", rs.getString("svg_id"));
                l.put("isStage",          rs.getInt("is_stage") == 1);
                l.put("tracksCongestion", rs.getInt("tracks_congestion") == 1);
            }
        }

        return root;
    }

    // ── util ────────────────────────────────────────────────

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
}
