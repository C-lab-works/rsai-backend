package app.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.gate.core.Config;
import dev.gate.core.Logger;

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
        String dbName  = envOrDefault("DB_NAME",     config.getName());
        String user    = envOrDefault("DB_USER",     config.getUser());
        String password = envOrDefault("DB_PASSWORD", config.getPassword());

        if (!cloudSqlInstance.isBlank()) {
            // Cloud SQL connector: connects via Unix socket without a proxy
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
        hikari.setMaximumPoolSize(config.getMaxPoolSize());
        hikari.setPoolName("rsai-pool");
        // Fail fast if DB is unreachable at startup
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

    private static void runSchema() throws Exception {
        try (InputStream is = Database.class.getResourceAsStream("/schema.sql")) {
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
