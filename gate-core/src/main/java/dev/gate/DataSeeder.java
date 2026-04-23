package dev.gate;

import dev.gate.core.Database;
import dev.gate.core.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;

public class DataSeeder {

    private static final Logger logger = new Logger(DataSeeder.class);
    private static final String[] KEYS = {"events", "food", "map"};

    public static void seed() throws Exception {
        int seeded = 0;
        for (String key : KEYS) {
            String resourcePath = "data/" + key + ".json";
            try (InputStream is = DataSeeder.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (is == null) {
                    logger.warn("Resource not found for seeding: {}", resourcePath);
                    continue;
                }
                String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                try (Connection conn = Database.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                         "INSERT IGNORE INTO static_data (data_key, data_json) VALUES (?, ?)")) {
                    ps.setString(1, key);
                    ps.setString(2, json);
                    int rows = ps.executeUpdate();
                    if (rows > 0) {
                        logger.info("Seeded initial data for key: {}", key);
                        seeded++;
                    }
                }
            }
        }
        if (seeded == 0) {
            logger.info("DB already seeded — no inserts needed");
        }
    }
}
