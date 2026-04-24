package dev.gate;

import dev.gate.core.Logger;

public class DataSeeder {
    private static final Logger logger = new Logger(DataSeeder.class);

    public static void seed() {
        logger.info("Data seeding is manual — insert data via SQL client");
    }
}

