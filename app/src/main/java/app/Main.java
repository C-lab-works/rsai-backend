package app;

import dev.gate.core.Config;
import dev.gate.core.Gate;
import dev.gate.core.Loadconfig;

public class Main {
    public static void main(String[] args) throws Exception {
        Config config = Loadconfig.load();
        System.out.println("Starting " + config.getName()
                + " in " + config.getEnv() + " mode");

        Gate gate = new Gate();
        gate.scan("app");
        gate.start(config.getPort());
    }
}
