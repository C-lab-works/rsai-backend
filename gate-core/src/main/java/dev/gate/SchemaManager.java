package dev.gate;

import dev.gate.core.Database;
import dev.gate.core.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;

public class SchemaManager {
    private static final Logger logger = new Logger(SchemaManager.class);

    public static void initSchema() throws Exception {
        try (InputStream is = SchemaManager.class.getClassLoader()
                .getResourceAsStream("schema-init.sql")) {
            if (is == null) {
                logger.warn("schema-init.sql not found — skipping");
                return;
            }
            String full = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            // Strip -- line comments before splitting on ;
            String stripped = Arrays.stream(full.split("\n"))
                    .map(line -> {
                        int idx = line.indexOf("--");
                        return idx >= 0 ? line.substring(0, idx) : line;
                    })
                    .collect(java.util.stream.Collectors.joining("\n"));

            String[] statements = stripped.split(";");
            try (Connection conn = Database.getConnection()) {
                for (String raw : statements) {
                    String sql = raw.strip();
                    if (sql.isEmpty()) continue;
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(sql);
                    }
                }
            }
        }
        logger.info("Schema (normalized) initialized");
    }
}
