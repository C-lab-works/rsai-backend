package dev.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gate.core.Database;
import dev.gate.core.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public class DataSeeder {

    private static final Logger logger = new Logger(DataSeeder.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final int SEED_VERSION = 1;

    public static void seed() throws Exception {
        try (Connection conn = Database.getConnection()) {
            if (isAlreadySeeded(conn)) {
                logger.info("DB already seeded — skipping");
                return;
            }
            JsonNode events = loadJson("data/events.json");
            JsonNode food   = loadJson("data/food.json");
            JsonNode map    = loadJson("data/map.json");

            conn.setAutoCommit(false);
            try {
                seedEvents(conn, events);
                seedFood(conn, food);
                seedMap(conn, map);
                markSeeded(conn);
                conn.commit();
                logger.info("DB seeding complete");
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private static boolean isAlreadySeeded(Connection conn) throws Exception {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT version FROM seed_version WHERE id = 1")) {
            return rs.next() && rs.getInt("version") >= SEED_VERSION;
        }
    }

    private static void markSeeded(Connection conn) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.execute("INSERT INTO seed_version (id, version) VALUES (1, " + SEED_VERSION + ") " +
                      "ON DUPLICATE KEY UPDATE version = " + SEED_VERSION);
        }
    }

    private static JsonNode loadJson(String path) throws Exception {
        try (InputStream is = DataSeeder.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) throw new IllegalStateException("Resource not found: " + path);
            return mapper.readTree(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // ── events ────────────────────────────────────────────────

    private static void seedEvents(Connection conn, JsonNode root) throws Exception {
        JsonNode fest = root.get("festival");
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO festival (id, title, theme) VALUES (?,?,?)")) {
            ps.setString(1, fest.get("id").asText());
            ps.setString(2, fest.get("title").asText());
            ps.setString(3, textOrNull(fest.get("theme")));
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO days (id, date, label) VALUES (?,?,?)")) {
            for (JsonNode d : root.get("days")) {
                ps.setString(1, d.get("id").asText());
                ps.setString(2, d.get("date").asText());
                ps.setString(3, d.get("label").asText());
                ps.addBatch();
            }
            ps.executeBatch();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO event_venues (id, name, capacity, category) VALUES (?,?,?,?)")) {
            for (JsonNode v : root.get("venues")) {
                ps.setString(1, v.get("id").asText());
                ps.setString(2, v.get("name").asText());
                ps.setObject(3, v.has("capacity") && !v.get("capacity").isNull()
                        ? v.get("capacity").asInt() : null);
                ps.setString(4, textOrNull(v.get("category")));
                ps.addBatch();
            }
            ps.executeBatch();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO event_categories (id, label) VALUES (?,?)")) {
            for (JsonNode c : root.get("categories")) {
                ps.setString(1, c.get("id").asText());
                ps.setString(2, c.get("label").asText());
                ps.addBatch();
            }
            ps.executeBatch();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO events (id, day_id, venue_id, start_time, end_time, " +
                "category_id, title, description, audience, multi_venue) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            for (JsonNode e : root.get("events")) {
                ps.setString(1, e.get("id").asText());
                ps.setString(2, e.get("day").asText());
                ps.setString(3, e.get("venue").asText());
                ps.setString(4, e.get("start").asText());
                ps.setString(5, e.get("end").asText());
                ps.setString(6, textOrNull(e.get("category")));
                ps.setString(7, e.get("title").asText());
                ps.setString(8, textOrNull(e.get("desc")));
                ps.setString(9, textOrNull(e.get("audience")));
                ps.setInt(10, e.has("multiVenue") && e.get("multiVenue").asBoolean() ? 1 : 0);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── food ────────────────────────────────────────────────

    private static void seedFood(Connection conn, JsonNode root) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO vendors (id, type, name, venue, status, notes, tags, payment) " +
                "VALUES (?,?,?,?,?,?,?,?)")) {
            for (JsonNode v : root.get("vendors")) {
                ps.setString(1, v.get("id").asText());
                ps.setString(2, v.get("type").asText());
                ps.setString(3, v.get("name").asText());
                ps.setString(4, textOrNull(v.get("venue")));
                ps.setString(5, v.get("status").asText());
                ps.setString(6, textOrNull(v.get("notes")));
                ps.setString(7, v.has("tags")    ? mapper.writeValueAsString(v.get("tags"))    : null);
                ps.setString(8, v.has("payment") ? mapper.writeValueAsString(v.get("payment")) : null);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO vendor_hours (vendor_id, day_id, open_time, close_time, last_order) " +
                "VALUES (?,?,?,?,?)")) {
            for (JsonNode v : root.get("vendors")) {
                String vendorId = v.get("id").asText();
                for (JsonNode h : v.get("operatingHours")) {
                    ps.setString(1, vendorId);
                    ps.setString(2, h.get("day").asText());
                    ps.setString(3, h.get("open").asText());
                    ps.setString(4, h.get("close").asText());
                    ps.setString(5, textOrNull(h.get("lastOrder")));
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO dining_areas (id, name, floor, capacity_est, seats_indoor, weather, tables_json) " +
                "VALUES (?,?,?,?,?,?,?)")) {
            for (JsonNode a : root.get("diningAreas")) {
                ps.setString(1, a.get("id").asText());
                ps.setString(2, a.get("name").asText());
                ps.setObject(3, a.has("floor") && !a.get("floor").isNull() ? a.get("floor").asInt() : null);
                ps.setObject(4, a.has("capacity_est") && !a.get("capacity_est").isNull()
                        ? a.get("capacity_est").asInt() : null);
                ps.setObject(5, a.has("seatsIndoor") && !a.get("seatsIndoor").isNull()
                        ? (a.get("seatsIndoor").asBoolean() ? 1 : 0) : null);
                ps.setString(6, textOrNull(a.get("weather")));
                ps.setString(7, a.has("tables") ? mapper.writeValueAsString(a.get("tables")) : null);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO food_rules (rule, sort_order) VALUES (?,?)")) {
            int order = 0;
            for (JsonNode r : root.get("rules")) {
                ps.setString(1, r.asText());
                ps.setInt(2, order++);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO eco_stations (id, location_hint, status) VALUES (?,?,?)")) {
            for (JsonNode e : root.get("ecoStations")) {
                ps.setString(1, e.get("id").asText());
                ps.setString(2, textOrNull(e.get("location_hint")));
                ps.setString(3, textOrNull(e.get("status")));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── map ────────────────────────────────────────────────

    private static void seedMap(Connection conn, JsonNode root) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO floors (id, label) VALUES (?,?)")) {
            for (JsonNode f : root.get("floors")) {
                ps.setString(1, f.get("id").asText());
                ps.setString(2, f.get("label").asText());
                ps.addBatch();
            }
            ps.executeBatch();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO rooms (id, floor_id, name, use_desc, category, is_public, capacity, tags) " +
                "VALUES (?,?,?,?,?,?,?,?)")) {
            for (JsonNode r : root.get("rooms")) {
                ps.setString(1, r.get("id").asText());
                ps.setString(2, r.get("floor").asText());
                ps.setString(3, r.get("name").asText());
                ps.setString(4, textOrNull(r.get("use")));
                ps.setString(5, textOrNull(r.get("category")));
                ps.setInt(6, r.has("public") && r.get("public").asBoolean() ? 1 : 0);
                ps.setObject(7, r.has("capacity") && !r.get("capacity").isNull()
                        ? r.get("capacity").asInt() : null);
                ps.setString(8, r.has("tags") ? mapper.writeValueAsString(r.get("tags")) : null);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO outdoor_areas (id, name, use_desc, category, is_public, weather) " +
                "VALUES (?,?,?,?,?,?)")) {
            for (JsonNode o : root.get("outdoor")) {
                ps.setString(1, o.get("id").asText());
                ps.setString(2, o.get("name").asText());
                ps.setString(3, textOrNull(o.get("use")));
                ps.setString(4, textOrNull(o.get("category")));
                ps.setInt(5, o.has("public") && o.get("public").asBoolean() ? 1 : 0);
                ps.setString(6, textOrNull(o.get("weather")));
                ps.addBatch();
            }
            ps.executeBatch();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO poi (id, name, floor_id, type, accessible, room_ref, note) " +
                "VALUES (?,?,?,?,?,?,?)")) {
            for (JsonNode p : root.get("poi")) {
                ps.setString(1, p.get("id").asText());
                ps.setString(2, p.get("name").asText());
                ps.setString(3, textOrNull(p.get("floor")));
                ps.setString(4, p.get("type").asText());
                ps.setInt(5, p.has("accessible") && p.get("accessible").asBoolean() ? 1 : 0);
                ps.setString(6, textOrNull(p.get("roomRef")));
                ps.setString(7, textOrNull(p.get("note")));
                ps.addBatch();
            }
            ps.executeBatch();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO map_config (id, building, pins_version) VALUES (1,?,?) " +
                "ON DUPLICATE KEY UPDATE building=VALUES(building), pins_version=VALUES(pins_version)")) {
            ps.setString(1, root.get("building").asText());
            ps.setInt(2, root.has("pinsVersion") ? root.get("pinsVersion").asInt() : 1);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO map_notes (note, sort_order) VALUES (?,?)")) {
            int order = 0;
            for (JsonNode n : root.get("notes")) {
                ps.setString(1, n.asText());
                ps.setInt(2, order++);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── util ────────────────────────────────────────────────

    private static String textOrNull(JsonNode node) {
        return (node == null || node.isNull()) ? null : node.asText();
    }
}
