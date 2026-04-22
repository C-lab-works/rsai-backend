package dev.gate.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

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
            hikari.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s", host, port, dbName));
            logger.info("Connecting to PostgreSQL at {}:{}/{}", host, port, dbName);
        }

        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setMaximumPoolSize(poolSize);
        hikari.setPoolName("gate-pool");
        hikari.setInitializationFailTimeout(10_000);

        dataSource = new HikariDataSource(hikari);
        logger.info("Database connection pool initialized");

        runSchema();
    }

    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public static void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    /**
     * Looks for schema.sql on the classpath and applies it on startup.
     * Applications place their schema.sql in src/main/resources/.
     */
    private static void runSchema() throws Exception {
        try (InputStream is = Database.class.getClassLoader().getResourceAsStream("schema.sql")) {
            if (is == null) return;
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
        }
        logger.info("Schema applied");
    }

    private static String envOrDefault(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }
}
