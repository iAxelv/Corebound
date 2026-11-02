package com.corebound.thecore.message;

import java.io.File;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;

public final class Messages {
    private final YamlConfiguration messages;

    public Messages(File file) {
        messages = YamlConfiguration.loadConfiguration(file);
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
