package com.corebound.thecore.message;

import com.corebound.thecore.config.ConfigFiles;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.Map;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class Messages {
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("EN_en", "ES_es");
    private final File directory;
    private final JavaPlugin plugin;
    private YamlConfiguration messages;

    public Messages(File directory, JavaPlugin plugin, String language) {
        this.directory = directory;
        this.plugin = plugin;
        reload(language);
    }

    public void reload(String language) {
        validateLanguage(language);
        File file = new File(directory, language + ".yml");
        YamlConfiguration loaded = ConfigFiles.load(file);
        loaded.setDefaults(loadDefaults(language));
        messages = loaded;
    }

    public static void validateLanguage(String language) {
        if (!SUPPORTED_LANGUAGES.contains(language)) {
            throw new IllegalArgumentException(
                    "Unsupported language '" + language + "'. Supported languages: " + SUPPORTED_LANGUAGES + ".");
        }
    }

    private YamlConfiguration loadDefaults(String language) {
        validateLanguage(language);
        InputStream input = plugin.getResource("lang/" + language + ".yml");
        if (input == null) {
            throw new IllegalStateException("Bundled default messages are missing for " + language + ".");
        }
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load bundled default messages for " + language + ".", exception);
        }
    }

    public void send(CommandSender recipient, String key) {
        send(recipient, key, Map.of());
    }

    public void send(CommandSender recipient, String key, Map<String, String> replacements) {
        send(recipient, key, replacements, true);
    }

    public void sendWithoutPrefix(CommandSender recipient, String key, Map<String, String> replacements) {
        send(recipient, key, replacements, false);
    }

    private void send(
            CommandSender recipient, String key, Map<String, String> replacements, boolean includePrefix) {
        String prefix = includePrefix ? messages.getString("prefix", "") : "";
        String message = messages.getString(key);
        if (message == null) {
            throw new IllegalArgumentException("Missing localized message: " + key);
        }
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            message = message.replace("<" + replacement.getKey() + ">", replacement.getValue());
            message = message.replace("%" + replacement.getKey() + "%", replacement.getValue());
        }
        recipient.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + message));
    }

    public String text(String key) {
        return PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacyAmpersand().deserialize(value(key)));
    }

    public String parseText(String message) {
        return PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacyAmpersand().deserialize(message));
    }

    public String legacy(String key) {
        String message = messages.getString(key);
        if (message == null) {
            throw new IllegalArgumentException("Missing localized message: " + key);
        }
        return message;
    }

    public String formatText(String key, Map<String, String> replacements) {
        String message = legacy(key);
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            message = message.replace("%" + replacement.getKey() + "%", replacement.getValue());
        }
        return message;
    }

    public String colored(String key) {
        String message = messages.getString(key);
        if (message == null) {
            throw new IllegalArgumentException("Missing localized message: " + key);
        }
        return LegacyComponentSerializer.legacySection().serialize(
                LegacyComponentSerializer.legacyAmpersand().deserialize(message));
    }

    public Component component(String key) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(value(key));
    }

    public Component parseComponent(String message) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(message);
    }

    public Component format(String key, Map<String, Component> replacements) {
        Component formatted = component(key);
        for (Map.Entry<String, Component> replacement : replacements.entrySet()) {
            formatted = formatted.replaceText(TextReplacementConfig.builder()
                    .matchLiteral("%" + replacement.getKey() + "%")
                    .replacement(replacement.getValue())
                    .build());
        }
        return formatted;
    }

    private String value(String key) {
        String message = messages.getString(key);
        if (message == null) {
            throw new IllegalArgumentException("Missing localized message: " + key);
        }
        return message;
    }
}
