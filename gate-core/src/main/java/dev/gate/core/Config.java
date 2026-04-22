package dev.gate.core;

public class Config {
    private int port = 8080;
    private String env = "development";
    private String name = "Gate";
    private DatabaseConfig database = new DatabaseConfig();
    private volatile boolean frozen = false;

    private void checkFrozen() {
        if (frozen) throw new IllegalStateException("Config is frozen and cannot be modified");
    }

    public void freeze() { this.frozen = true; }

    public int getPort() { return port; }
    public void setPort(int port) {
        checkFrozen();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port: " + port);
        this.port = port;
    }
    public String getEnv() { return env; }
    public void setEnv(String env) { checkFrozen(); this.env = env; }
    public String getName() { return name; }
    public void setName(String name) { checkFrozen(); this.name = name; }
    public DatabaseConfig getDatabase() { return database; }
    public void setDatabase(DatabaseConfig database) { checkFrozen(); this.database = database; }

    public static class DatabaseConfig {
        private String host = "localhost";
        private int port = 5432;
        private String name = "rsai";
        private String user = "postgres";
        private String password = "";
        // Cloud SQL instance connection name: "project:region:instance"
        // Leave blank to use direct TCP connection (local dev)
        private String cloudSqlInstance = "";
        private int maxPoolSize = 10;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getCloudSqlInstance() { return cloudSqlInstance; }
        public void setCloudSqlInstance(String cloudSqlInstance) { this.cloudSqlInstance = cloudSqlInstance; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
    }
}