package com.corebound.thecore.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

public final class ConfigFiles {
    private ConfigFiles() {
    }

    public static YamlConfiguration load(File file) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalArgumentException("Could not load YAML configuration " + file.getName() + ".", exception);
        }
        return configuration;
    }

    public static YamlConfiguration load(File file, InputStream defaults) {
        if (defaults == null) {
            throw new IllegalArgumentException("Bundled defaults are missing for " + file.getName() + ".");
        }
        try (InputStreamReader reader = new InputStreamReader(defaults, StandardCharsets.UTF_8)) {
            YamlConfiguration configuration = load(file);
            configuration.setDefaults(YamlConfiguration.loadConfiguration(reader));
            return configuration;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not load YAML defaults for " + file.getName() + ".", exception);
        }
    }
}
