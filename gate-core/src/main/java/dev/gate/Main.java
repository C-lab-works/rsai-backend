package dev.gate;

import dev.gate.core.Config;
import dev.gate.core.Database;
import dev.gate.core.Gate;
import dev.gate.core.GateServer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws Exception {
        String version = "unknown";
        try (InputStream vs = Main.class.getResourceAsStream("/version.txt")) {
            if (vs != null) version = new String(vs.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {}
        System.out.println("rsai-backend v" + version + " starting");
        int port = 8080;
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isBlank()) {
            port = Integer.parseInt(portEnv.trim());
        }

        Config.DatabaseConfig dbConfig = new Config.DatabaseConfig();
        dbConfig.setHost("localhost");
        dbConfig.setPort(3306);
        dbConfig.setName("rsai");
        Database.init(dbConfig);
        SchemaManager.initSchema();
        DataSeeder.seed();

        Gate gate = new Gate();
        gate.cors("*");
        gate.before(RequestMetrics.get()::startTimer);
        gate.before(new ApiKeyAuth());
        gate.get("/health", ctx -> ctx.json(Map.of("status", "ok")));
        gate.register(new DataController());
        gate.register(new CongestionController());
        gate.register(new AdminController());

        gate.after(RequestMetrics.get()::record);
        GateServer server = gate.start(port);
        server.join();
    }
}
