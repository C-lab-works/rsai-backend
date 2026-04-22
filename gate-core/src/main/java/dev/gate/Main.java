package dev.gate;

import dev.gate.core.Config;
import dev.gate.core.ConfigLoader;
import dev.gate.core.Database;
import dev.gate.core.Gate;
import dev.gate.core.GateServer;

import java.util.Map;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = 8080;
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isBlank()) {
            port = Integer.parseInt(portEnv.trim());
        }

        Config config = ConfigLoader.load();
        try {
            Database.init(config.getDatabase());
            Runtime.getRuntime().addShutdownHook(new Thread(Database::close));
        } catch (Exception e) {
            System.err.println("[WARN] Database initialization failed, running without DB: " + e.getMessage());
        }

        Gate gate = new Gate();
        gate.get("/health", ctx -> ctx.json(Map.of("status", "ok")));
        gate.register(new ScheduleController());

        GateServer server = gate.start(port);
        server.join();
    }
}
