package app;

import dev.gate.core.Config;
import dev.gate.core.Database;
import dev.gate.core.ConfigLoader;
import dev.gate.core.Gate;
import dev.gate.core.GateServer;
import dev.gate.core.Logger;

public class Main {
    private static final Logger logger = new Logger(Main.class);

    public static void main(String[] args) throws Exception {
        Config config = ConfigLoader.load();
        logger.info("Starting {} in {} mode", config.getName(), config.getEnv());

        Database.init(config.getDatabase());

        Gate gate = new Gate();
        gate.register(new UserController());
        GateServer server = gate.start(config.getPort());

        Runtime.getRuntime().addShutdownHook(new Thread(Database::close));

        server.join();
    }
}