package com.corebound.thecore.message;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class Messages {
    private final YamlConfiguration messages;

    public Messages(File file, InputStream defaults) {
        messages = YamlConfiguration.loadConfiguration(file);
        if (defaults == null) {
            throw new IllegalStateException("Bundled default messages are missing.");
        }
        try (InputStreamReader reader = new InputStreamReader(defaults, StandardCharsets.UTF_8)) {
            messages.setDefaults(YamlConfiguration.loadConfiguration(reader));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load bundled default messages.", exception);
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

    public String colored(String key) {
        String message = messages.getString(key);
        if (message == null) {
            throw new IllegalArgumentException("Missing localized message: " + key);
        }
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', message);
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
