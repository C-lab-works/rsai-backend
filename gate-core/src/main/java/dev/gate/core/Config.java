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

    public void freeze() {
        this.frozen = true;
        this.database.freeze();
    }

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
        private String cloudSqlInstance = "";
        private int maxPoolSize = 10;
        private boolean ssl = false;
        private volatile boolean frozen = false;

        private void checkFrozen() {
            if (frozen) throw new IllegalStateException("Config is frozen and cannot be modified");
        }

        void freeze() { this.frozen = true; }

        public String getHost() { return host; }
        public void setHost(String host) { checkFrozen(); this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) {
            checkFrozen();
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port: " + port);
            this.port = port;
        }
        public String getName() { return name; }
        public void setName(String name) { checkFrozen(); this.name = name; }
        public String getUser() { return user; }
        public void setUser(String user) { checkFrozen(); this.user = user; }
        public String getPassword() { return password; }
        public void setPassword(String password) { checkFrozen(); this.password = password; }
        public String getCloudSqlInstance() { return cloudSqlInstance != null ? cloudSqlInstance : ""; }
        public void setCloudSqlInstance(String cloudSqlInstance) { checkFrozen(); this.cloudSqlInstance = cloudSqlInstance != null ? cloudSqlInstance : ""; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) {
            checkFrozen();
            if (maxPoolSize < 1) throw new IllegalArgumentException("Invalid maxPoolSize: " + maxPoolSize);
            this.maxPoolSize = maxPoolSize;
        }
        public boolean isSsl() { return ssl; }
        public void setSsl(boolean ssl) { checkFrozen(); this.ssl = ssl; }
    }
}
