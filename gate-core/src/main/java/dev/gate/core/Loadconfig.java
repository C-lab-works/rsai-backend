package dev.gate.core;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.InputStream;

public class Loadconfig {

    public static Config load() {
        LoaderOptions options = new LoaderOptions();
        options.setTagInspector(
                tag -> tag.getClassName().equals(Config.class.getName())
        );

        Yaml yaml = new Yaml(new Constructor(Config.class, options));
        InputStream input = Loadconfig.class
                .getClassLoader()
                .getResourceAsStream("config.yml");

        if (input == null) {
            System.out.println("using default config");
            return new Config();
        }

        return yaml.load(input);
    }
}