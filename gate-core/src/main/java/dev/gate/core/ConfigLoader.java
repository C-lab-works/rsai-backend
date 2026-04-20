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

        try (InputStream input = ConfigLoader.class
                .getClassLoader()
                .getResourceAsStream("config.yml")) {

            if (input == null) {
                logger.info("config.yml not found, using defaults");
                Config config = new Config();
                config.freeze();
                return config;
            }

            Config config = yaml.load(input);
            config.freeze();
            return config;
        } catch (Exception e) {
            logger.warn("Failed to load config.yml: {} — using defaults", e.getMessage());
            Config config = new Config();
            config.freeze();
            return config;
        }
    }
}
