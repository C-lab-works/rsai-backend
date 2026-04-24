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
import java.sql.PreparedStatement;
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

        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, title, theme FROM festival LIMIT 1")) {
            if (rs.next()) {
                ObjectNode f = root.putObject("festival");
                f.put("id",    rs.getString("id"));
                f.put("title", rs.getString("title"));
                putStringOrNull(f, "theme", rs.getString("theme"));
            }
        }

        ArrayNode days = root.putArray("days");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, date, label FROM days ORDER BY date")) {
            while (rs.next()) {
                ObjectNode d = days.addObject();
                d.put("id",    rs.getString("id"));
                d.put("date",  rs.getString("date"));
                d.put("label", rs.getString("label"));
            }
        }

        ArrayNode venues = root.putArray("venues");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, name, capacity, category FROM event_venues")) {
            while (rs.next()) {
                ObjectNode v = venues.addObject();
                v.put("id",   rs.getString("id"));
                v.put("name", rs.getString("name"));
                int cap = rs.getInt("capacity");
                if (!rs.wasNull()) v.put("capacity", cap);
                putStringOrNull(v, "category", rs.getString("category"));
            }
        }

        ArrayNode cats = root.putArray("categories");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, label FROM event_categories")) {
            while (rs.next()) {
                ObjectNode c = cats.addObject();
                c.put("id",    rs.getString("id"));
                c.put("label", rs.getString("label"));
            }
        }

        ArrayNode evts = root.putArray("events");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT id, day_id, venue_id, start_time, end_time, " +
                "category_id, title, description, audience, multi_venue FROM events ORDER BY start_time")) {
            while (rs.next()) {
                ObjectNode e = evts.addObject();
                e.put("id",    rs.getString("id"));
                e.put("day",   rs.getString("day_id"));
                e.put("venue", rs.getString("venue_id"));
                e.put("start", rs.getString("start_time"));
                e.put("end",   rs.getString("end_time"));
                putStringOrNull(e, "category",    rs.getString("category_id"));
                e.put("title",                    rs.getString("title"));
                putStringOrNull(e, "desc",        rs.getString("description"));
                putStringOrNull(e, "audience",    rs.getString("audience"));
                e.put("multiVenue", rs.getInt("multi_venue") == 1);
            }
        }
        return root;
    }

    // ── /food ─────────────────────────────────────────────────

    private Object buildFood(Connection conn) throws Exception {
        ObjectNode root = mapper.createObjectNode();

        ArrayNode vendors = root.putArray("vendors");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT id, type, name, venue, status, notes, tags, payment FROM vendors")) {
            while (rs.next()) {
                ObjectNode v = vendors.addObject();
                v.put("id",     rs.getString("id"));
                v.put("type",   rs.getString("type"));
                v.put("name",   rs.getString("name"));
                putStringOrNull(v, "venue",   rs.getString("venue"));
                v.put("status", rs.getString("status"));
                putStringOrNull(v, "notes",   rs.getString("notes"));
                putJsonArrayOrNull(v, "tags",    rs.getString("tags"));
                putJsonArrayOrNull(v, "payment", rs.getString("payment"));

                // nested operatingHours
                ArrayNode hours = v.putArray("operatingHours");
                String vid = rs.getString("id");
                try (PreparedStatement ph = conn.prepareStatement(
                        "SELECT day_id, open_time, close_time, last_order FROM vendor_hours " +
                        "WHERE vendor_id = ? ORDER BY day_id")) {
                    ph.setString(1, vid);
                    try (ResultSet rh = ph.executeQuery()) {
                        while (rh.next()) {
                            ObjectNode h = hours.addObject();
                            h.put("day",   rh.getString("day_id"));
                            h.put("open",  rh.getString("open_time"));
                            h.put("close", rh.getString("close_time"));
                            putStringOrNull(h, "lastOrder", rh.getString("last_order"));
                        }
                    }
                }
            }
        }

        ArrayNode areas = root.putArray("diningAreas");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT id, name, floor, capacity_est, seats_indoor, weather, tables_json FROM dining_areas")) {
            while (rs.next()) {
                ObjectNode a = areas.addObject();
                a.put("id",   rs.getString("id"));
                a.put("name", rs.getString("name"));
                int floor = rs.getInt("floor");
                if (!rs.wasNull()) a.put("floor", floor);
                int cap = rs.getInt("capacity_est");
                if (!rs.wasNull()) a.put("capacity_est", cap);
                int si = rs.getInt("seats_indoor");
                if (!rs.wasNull()) a.put("seatsIndoor", si == 1);
                putStringOrNull(a, "weather",    rs.getString("weather"));
                putJsonArrayOrNull(a, "tables",  rs.getString("tables_json"));
            }
        }

        ArrayNode rules = root.putArray("rules");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT rule FROM food_rules ORDER BY sort_order")) {
            while (rs.next()) rules.add(rs.getString("rule"));
        }

        ArrayNode eco = root.putArray("ecoStations");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, location_hint, status FROM eco_stations")) {
            while (rs.next()) {
                ObjectNode e = eco.addObject();
                e.put("id", rs.getString("id"));
                putStringOrNull(e, "location_hint", rs.getString("location_hint"));
                putStringOrNull(e, "status",        rs.getString("status"));
            }
        }
        return root;
    }

    // ── /map ─────────────────────────────────────────────────

    private Object buildMap(Connection conn) throws Exception {
        ObjectNode root = mapper.createObjectNode();

        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT building, pins_version FROM map_config LIMIT 1")) {
            if (rs.next()) {
                root.put("building",    rs.getString("building"));
                root.put("pinsVersion", rs.getInt("pins_version"));
            }
        }

        ArrayNode floors = root.putArray("floors");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, label FROM floors ORDER BY id")) {
            while (rs.next()) {
                ObjectNode f = floors.addObject();
                f.put("id",    rs.getString("id"));
                f.put("label", rs.getString("label"));
            }
        }

        ArrayNode rooms = root.putArray("rooms");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT id, floor_id, name, use_desc, category, is_public, capacity, tags FROM rooms")) {
            while (rs.next()) {
                ObjectNode r = rooms.addObject();
                r.put("id",    rs.getString("id"));
                r.put("floor", rs.getString("floor_id"));
                r.put("name",  rs.getString("name"));
                putStringOrNull(r, "use",      rs.getString("use_desc"));
                putStringOrNull(r, "category", rs.getString("category"));
                r.put("public", rs.getInt("is_public") == 1);
                int cap = rs.getInt("capacity");
                if (!rs.wasNull()) r.put("capacity", cap);
                putJsonArrayOrNull(r, "tags", rs.getString("tags"));
            }
        }

        ArrayNode outdoor = root.putArray("outdoor");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT id, name, use_desc, category, is_public, weather FROM outdoor_areas")) {
            while (rs.next()) {
                ObjectNode o = outdoor.addObject();
                o.put("id",    rs.getString("id"));
                o.put("name",  rs.getString("name"));
                putStringOrNull(o, "use",      rs.getString("use_desc"));
                putStringOrNull(o, "category", rs.getString("category"));
                o.put("public", rs.getInt("is_public") == 1);
                putStringOrNull(o, "weather",  rs.getString("weather"));
            }
        }

        ArrayNode poiArr = root.putArray("poi");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT id, name, floor_id, type, is_accessible, room_ref, note FROM poi")) {
            while (rs.next()) {
                ObjectNode p = poiArr.addObject();
                p.put("id",   rs.getString("id"));
                p.put("name", rs.getString("name"));
                putStringOrNull(p, "floor",   rs.getString("floor_id"));
                p.put("type",                 rs.getString("type"));
                p.put("accessible", rs.getInt("is_accessible") == 1);
                putStringOrNull(p, "roomRef", rs.getString("room_ref"));
                putStringOrNull(p, "note",    rs.getString("note"));
            }
        }

        ArrayNode notes = root.putArray("notes");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT note FROM map_notes ORDER BY sort_order")) {
            while (rs.next()) notes.add(rs.getString("note"));
        }
        return root;
    }

    // ── util ────────────────────────────────────────────────

    private void putStringOrNull(ObjectNode node, String key, String value) {
        if (value != null) node.put(key, value);
    }

    private void putJsonArrayOrNull(ObjectNode node, String key, String json) throws Exception {
        if (json == null) return;
        node.set(key, mapper.readTree(json));
    }
}
