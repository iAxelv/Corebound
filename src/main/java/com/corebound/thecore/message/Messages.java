package com.corebound.thecore.message;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;

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
        String prefix = messages.getString("prefix", "");
        String message = messages.getString(key);
        if (message == null) {
            throw new IllegalArgumentException("Missing localized message: " + key);
        }
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            message = message.replace("<" + replacement.getKey() + ">", replacement.getValue());
        }
        recipient.sendMessage(MiniMessage.miniMessage().deserialize(prefix + message));
    }

    public String text(String key) {
        String message = messages.getString(key);
        if (message == null) {
            throw new IllegalArgumentException("Missing localized message: " + key);
        }
        return MiniMessage.miniMessage().stripTags(message);
    }
}
