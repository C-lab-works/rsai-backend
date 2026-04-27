package dev.gate.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.stream.Collectors;

public class Database {
    private static final Logger logger = new Logger(Database.class);
    private static volatile HikariDataSource dataSource;

    public static void init(Config.DatabaseConfig config) throws Exception {
        HikariConfig hikari = new HikariConfig();

        String cloudSqlInstance = envOrDefault("CLOUD_SQL_INSTANCE", config.getCloudSqlInstance());
        String dbName    = envOrDefault("DB_NAME",     config.getName());
        String user      = envOrDefault("DB_USER",     config.getUser());
        String password  = envOrDefault("DB_PASSWORD", config.getPassword());
        int    poolSize  = config.getMaxPoolSize();

        if (!cloudSqlInstance.isBlank()) {
            hikari.setJdbcUrl(String.format(
                "jdbc:postgresql:///%s?cloudSqlInstance=%s&socketFactory=com.google.cloud.sql.postgres.SocketFactory",
                dbName, cloudSqlInstance
            ));
            logger.info("Connecting to Cloud SQL: {}/{}", cloudSqlInstance, dbName);
        } else {
            String host = envOrDefault("DB_HOST", config.getHost());
            int    port = Integer.parseInt(envOrDefault("DB_PORT", String.valueOf(config.getPort())));
            String sslMode = config.isSsl() ? "require" : "disable";
            hikari.setJdbcUrl(String.format(
                "jdbc:postgresql://%s:%d/%s?sslmode=%s&connectTimeout=5&socketTimeout=30",
                host, port, dbName, sslMode
            ));
            logger.info("Connecting to PostgreSQL at {}:{}/{}", host, port, dbName);
        }

        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setMaximumPoolSize(poolSize);
        hikari.setMinimumIdle(poolSize / 2);
        hikari.setPoolName("gate-pool");
        hikari.setInitializationFailTimeout(10_000);
        hikari.setConnectionTimeout(5_000);
        hikari.setIdleTimeout(600_000);
        hikari.setMaxLifetime(1_800_000);
        hikari.setKeepaliveTime(60_000);

        HikariDataSource ds = new HikariDataSource(hikari);
        try {
            dataSource = ds;
            runSchema();
        } catch (Exception e) {
            ds.close();
            dataSource = null;
            throw e;
        }
        logger.info("Database connection pool initialized");
    }

    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new IllegalStateException("Database has not been initialized. Call Database.init() first.");
        }
        return dataSource.getConnection();
    }

    public static void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private static void runSchema() throws Exception {
        try (InputStream is = Database.class.getClassLoader().getResourceAsStream("schema.sql")) {
            if (is == null) {
                logger.warn("schema.sql not found — skipping schema initialization");
                return;
            }
            String full = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            // Strip -- line comments before splitting on ; to support multi-statement files
            String stripped = Arrays.stream(full.split("\n"))
                    .map(line -> { int i = line.indexOf("--"); return i >= 0 ? line.substring(0, i) : line; })
                    .collect(Collectors.joining("\n"));
            try (Connection conn = getConnection()) {
                for (String raw : stripped.split(";")) {
                    String sql = raw.strip();
                    if (sql.isEmpty()) continue;
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(sql);
                    }
                }
            }
        }
        logger.info("Schema applied");
    }

    private static String envOrDefault(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }
}
