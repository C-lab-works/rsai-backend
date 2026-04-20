package dev.gate.core;

public class Config {
    private int port = 8080;
    private String env = "development";
    private String name = "Gate";
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
}