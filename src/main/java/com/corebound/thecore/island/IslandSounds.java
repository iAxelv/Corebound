package com.corebound.thecore.island;

import com.corebound.thecore.TheCorePlugin;
import java.io.File;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public final class IslandSounds {
    private final TheCorePlugin plugin;
    private final YamlConfiguration settings;

    public IslandSounds(TheCorePlugin plugin) {
        this.plugin = plugin;
        File file = plugin.getDataFolder().toPath().resolve("is/is_settings.yml").toFile();
        settings = YamlConfiguration.loadConfiguration(file);
    }

    public void play(Player player, String soundPath) {
        ConfigurationSection section = settings.getConfigurationSection(soundPath);
        String configuredSound = section == null ? null : section.getString("sound");
        NamespacedKey soundKey = configuredSound == null ? null : NamespacedKey.fromString(configuredSound);
        Sound sound = soundKey == null ? null : Registry.SOUNDS.get(soundKey);
        if (sound == null) {
            plugin.getLogger().warning("Invalid or missing sound configured at is_settings.yml: "
                    + soundPath + ".sound");
            return;
        }

        float volume = (float) section.getDouble("volume", 1.0);
        float pitch = (float) section.getDouble("pitch", 1.0);
        player.playSound(player.getLocation(), sound, volume, pitch);
    }
}
