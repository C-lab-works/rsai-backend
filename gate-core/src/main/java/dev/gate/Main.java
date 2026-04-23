package dev.gate;

import dev.gate.core.Config;
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

        Config.DatabaseConfig dbConfig = new Config.DatabaseConfig();
        dbConfig.setHost("localhost");
        dbConfig.setPort(3306);
        dbConfig.setName("rsai");
        Database.init(dbConfig);
        DataSeeder.seed();

        Gate gate = new Gate();
        gate.cors("*");
        gate.before(new ApiKeyAuth());
        gate.get("/health", ctx -> ctx.json(Map.of("status", "ok")));
        gate.register(new DataController());

        GateServer server = gate.start(port);
        server.join();
    }
}
