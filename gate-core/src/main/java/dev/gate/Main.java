package dev.gate;

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

        Gate gate = new Gate();
        gate.cors("*");
        gate.before(new ApiKeyAuth());
        gate.get("/health", ctx -> ctx.json(Map.of("status", "ok")));
        gate.register(new DataController());

        GateServer server = gate.start(port);
        server.join();
    }
}
