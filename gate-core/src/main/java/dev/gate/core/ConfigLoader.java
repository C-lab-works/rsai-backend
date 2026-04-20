package dev.gate.core;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.InputStream;

public class ConfigLoader {

    private static final Logger logger = new Logger(ConfigLoader.class);

    public static Config load() {
        LoaderOptions options = new LoaderOptions();
        options.setTagInspector(
                tag -> tag.getClassName().equals(Config.class.getName())
        );

        Yaml yaml = new Yaml(new Constructor(Config.class, options));

        Config config = null;
        try (InputStream input = ConfigLoader.class
                .getClassLoader()
                .getResourceAsStream("config.yml")) {

            if (input != null) {
                config = yaml.load(input);
            } else {
                logger.info("config.yml not found, using defaults");
            }
        } catch (Exception e) {
            logger.warn("Failed to load config.yml: {} — using defaults", e.getMessage());
        }

        if (config == null) {
            config = new Config();
        }
        config.freeze();
        return config;
    }
}
