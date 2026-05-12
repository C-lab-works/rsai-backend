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

@GateController
public class AnnouncementsController {

    private static final Logger logger = new Logger(AnnouncementsController.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping("/announcements")
    public void list(Context ctx) {
        try (Connection conn = Database.getConnection();
             Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT id, content, is_emergency, display_from, display_until " +
                "FROM announcements " +
                "WHERE (display_from  IS NULL OR display_from  <= NOW()) " +
                "  AND (display_until IS NULL OR display_until >= NOW()) " +
                "ORDER BY is_emergency DESC, id DESC")) {

            ObjectNode root = mapper.createObjectNode();
            ArrayNode arr  = root.putArray("announcements");
            while (rs.next()) {
                ObjectNode n = arr.addObject();
                n.put("id",          rs.getInt("id"));
                n.put("content",     rs.getString("content"));
                n.put("isEmergency", rs.getInt("is_emergency") == 1);
                String from  = rs.getString("display_from");
                String until = rs.getString("display_until");
                if (from  != null) n.put("displayFrom",  from);
                if (until != null) n.put("displayUntil", until);
            }
            ctx.json(root);
        } catch (Exception e) {
            logger.error("announcements error", e);
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable"));
        }
    }
}
