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
    private static volatile boolean ready = false;

    public static boolean isReady() { return ready; }

    public static void init(Config.DatabaseConfig config) throws Exception {
        HikariConfig hikari = new HikariConfig();

        String dbName   = envOrDefault("DB_NAME",     config.getName());
        String user     = envOrDefault("DB_USER",     config.getUser());
        String password = envOrDefault("DB_PASSWORD", config.getPassword());
        String host     = envOrDefault("DB_HOST",     config.getHost());
        int    port     = Integer.parseInt(envOrDefault("DB_PORT", String.valueOf(config.getPort())));
        // DB を使うのは背景ポーラーと管理系のみで、公開 GET はメモリキャッシュ配信。
        // Cloud Run のスケールアウト時に MySQL の max_connections を食い潰さないよう、
        // 環境変数 DB_POOL_SIZE でインスタンスあたりのプール上限を絞れるようにする。
        int    poolSize = intEnvOrDefault("DB_POOL_SIZE", config.getMaxPoolSize());

        boolean ssl = Boolean.parseBoolean(envOrDefault("DB_SSL", String.valueOf(config.isSsl())));
        String sslParams = ssl
            ? "useSSL=true&requireSSL=true&trustServerCertificate=true"
            : "useSSL=false&allowPublicKeyRetrieval=true";

        hikari.setJdbcUrl(String.format(
            "jdbc:mysql://%s:%d/%s?%s&tinyInt1isBit=false&useUnicode=true&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=30000",
            host, port, dbName, sslParams
        ));
        logger.info("Connecting to MySQL at {}:{}/{} (ssl={})", host, port, dbName, ssl);

        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setMaximumPoolSize(poolSize);
        hikari.setMinimumIdle(Math.min(2, poolSize));
        hikari.setPoolName("gate-pool");
        hikari.setInitializationFailTimeout(-1);
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
            // DB初期化失敗を通知
            dev.gate.DiscordWebhook.sendError("DB", "INIT", 500, "Database initialization failed: " + e.getMessage());
            throw e;
        }
        logger.info("Database connection pool initialized (maxPoolSize={}, minimumIdle={})",
                poolSize, Math.min(2, poolSize));
        ready = true;
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
                    .map(Database::stripLineComment)
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

    /** -- コメントを除去するが、シングルクォート内のものは除外する（SQL標準の '' エスケープに対応）。 */
    static String stripLineComment(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'') {
                if (!inString) {
                    inString = true;
                } else if (i + 1 < line.length() && line.charAt(i + 1) == '\'') {
                    i++; // SQL標準の '' エスケープをスキップ
                } else {
                    inString = false;
                }
            } else if (!inString && c == '-' && i + 1 < line.length() && line.charAt(i + 1) == '-') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String envOrDefault(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    /**
     * 整数の環境変数を読む。未設定はデフォルト、不正値（非数値・1未満）は
     * 警告してデフォルトにフォールバックする（設定ミスで起動リトライループに陥らないため）。
     */
    private static int intEnvOrDefault(String key, int defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            int parsed = Integer.parseInt(val.trim());
            if (parsed < 1) {
                logger.warn("{} must be >= 1 but was {} — falling back to {}", key, parsed, defaultValue);
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException e) {
            logger.warn("Invalid {} value '{}' — falling back to {}", key, val, defaultValue);
            return defaultValue;
        }
    }
}
