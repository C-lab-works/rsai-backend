package dev.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Database;
import dev.gate.core.Logger;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PostMapping;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@GateController
public class CongestionController {

    private static final Logger logger = new Logger(CongestionController.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping("/locations")
    public void locations(Context ctx) {
        try (Connection conn = Database.getConnection();
             Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT l.id, l.name, l.floor, l.svg_id, " +
                 "  (SELECT p.title FROM timetables t " +
                 "   JOIN projects p ON p.id = t.project_id " +
                 "   WHERE t.location_id = l.id " +
                 "   ORDER BY t.event_date, t.start_time LIMIT 1) AS project " +
                 "FROM locations l " +
                 "WHERE l.tracks_congestion = 1 " +
                 "ORDER BY l.floor, l.id")) {
            ArrayNode arr = mapper.createArrayNode();
            while (rs.next()) {
                ObjectNode n = arr.addObject();
                n.put("id",    rs.getInt("id"));
                n.put("name",  rs.getString("name"));
                n.put("floor", rs.getInt("floor"));
                String svgId = rs.getString("svg_id");
                if (svgId != null) n.put("svgId", svgId);
                String project = rs.getString("project");
                if (project != null) n.put("project", project);
            }
            ctx.json(arr);
        } catch (Exception e) {
            logger.error("locations error: {}", e.getMessage());
            ctx.status(503).json(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/congestion")
    public void getCongestion(Context ctx) {
        try (Connection conn = Database.getConnection();
             Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT location_id, level, updated_at, updated_by FROM congestion_status")) {
            ArrayNode arr = mapper.createArrayNode();
            while (rs.next()) {
                ObjectNode n = arr.addObject();
                n.put("location_id", rs.getInt("location_id"));
                n.put("level",       rs.getInt("level"));
                n.put("updated_at",  rs.getString("updated_at"));
                n.put("updated_by",  rs.getString("updated_by"));
            }
            ctx.json(arr);
        } catch (Exception e) {
            logger.error("getCongestion error: {}", e.getMessage());
            ctx.status(503).json(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/congestion/{id}")
    public void updateCongestion(Context ctx) {
        String idStr = ctx.pathParam("id");
        try {
            int locationId = Integer.parseInt(idStr);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAs(Map.class);
            Object levelObj = body.get("level");
            if (levelObj == null) { ctx.status(400).json(Map.of("error", "level required")); return; }
            int level = ((Number) levelObj).intValue();
            if (level < 0 || level > 2) { ctx.status(400).json(Map.of("error", "level must be 0-2")); return; }

            String updatedBy = ctx.requestHeader("Cf-Access-Authenticated-User-Email");
            if (updatedBy == null || updatedBy.isBlank()) updatedBy = "unknown";
            String now = LocalDateTime.now().format(FMT);

            try (Connection conn = Database.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO congestion_status (location_id, level, updated_at, updated_by) VALUES (?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE level = VALUES(level), updated_at = VALUES(updated_at), updated_by = VALUES(updated_by)")) {
                ps.setInt(1, locationId);
                ps.setInt(2, level);
                ps.setString(3, now);
                ps.setString(4, updatedBy);
                ps.executeUpdate();
            }
            ctx.json(Map.of("ok", true, "location_id", locationId, "level", level));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Invalid id"));
        } catch (Exception e) {
            logger.error("updateCongestion error: {}", e.getMessage());
            ctx.status(503).json(Map.of("error", e.getMessage()));
        }
    }
}
