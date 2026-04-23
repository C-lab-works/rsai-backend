package dev.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Database;
import dev.gate.core.Logger;
import dev.gate.mapping.GetMapping;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@GateController
public class DataController {

    private static final Logger logger = new Logger(DataController.class);
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L; // 5 minutes

    private final ObjectMapper mapper = new ObjectMapper();

    private record CacheEntry(Object data, long expiresAt) {}
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @GetMapping("/events")
    public void events(Context ctx) {
        serveFromDb(ctx, "events");
    }

    @GetMapping("/food")
    public void food(Context ctx) {
        serveFromDb(ctx, "food");
    }

    @GetMapping("/map")
    public void map(Context ctx) {
        serveFromDb(ctx, "map");
    }

    private void serveFromDb(Context ctx, String key) {
        CacheEntry entry = cache.get(key);
        if (entry != null && System.currentTimeMillis() < entry.expiresAt()) {
            ctx.json(entry.data());
            return;
        }

        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT data_json FROM static_data WHERE data_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Object data = mapper.readValue(rs.getString("data_json"), Object.class);
                    cache.put(key, new CacheEntry(data, System.currentTimeMillis() + CACHE_TTL_MS));
                    ctx.json(data);
                } else {
                    ctx.status(404).json(Map.of("error", "Data not found for key: " + key));
                }
            }
        } catch (Exception e) {
            logger.error("DB error serving key '{}': {}", key, e.getMessage());
            ctx.status(503).json(Map.of("error", "Service temporarily unavailable"));
        }
    }
}
