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
                "jdbc:mysql:///%s?cloudSqlInstance=%s&socketFactory=com.google.cloud.sql.mysql.SocketFactory&useSSL=false",
                dbName, cloudSqlInstance
            ));
            logger.info("Connecting to Cloud SQL (MySQL): {}/{}", cloudSqlInstance, dbName);
        } else {
            String host = envOrDefault("DB_HOST", config.getHost());
            int    port = Integer.parseInt(envOrDefault("DB_PORT", String.valueOf(config.getPort())));
            hikari.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=30000",
                host, port, dbName
            ));
            logger.info("Connecting to MySQL at {}:{}/{}", host, port, dbName);
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
