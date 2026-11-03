package com.corebound.thecore.config;

import java.io.File;
import java.io.IOException;
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
}
