package dev.gate.core;

public class Config {
    //デフォルト
    private int port = 8080;
    private String env = "development";
    private String name = "Gate";

    //読み込み
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}