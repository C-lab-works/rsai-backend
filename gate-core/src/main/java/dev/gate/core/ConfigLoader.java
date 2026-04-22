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
                if (config == null) {
                    throw new IllegalStateException("config.yml is empty or invalid");
                }
            } else {
                logger.info("config.yml not found, using defaults");
                config = new Config();
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse config.yml: " + e.getMessage(), e);
        }
        config.freeze();
        return config;
    }
}
