package com.corebound.thecore.incremental;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class BatteryRepository {
    private final File file;
    private final JavaPlugin plugin;
    private final Object writeLock = new Object();
    private final AtomicLong version = new AtomicLong();
    private final Map<UUID, BatteryRecord> batteries = new HashMap<>();

    public BatteryRepository(File file, JavaPlugin plugin) {
        this.file = file;
        this.plugin = plugin;
        load();
    }

    public synchronized List<BatteryRecord> all() {
        return List.copyOf(batteries.values());
    }

    public synchronized List<BatteryRecord> forIsland(long islandIndex) {
        return batteries.values().stream()
                .filter(battery -> battery.islandIndex() == islandIndex)
                .toList();
    }

    public synchronized BatteryRecord get(UUID batteryId) {
        return batteries.get(batteryId);
    }

    public synchronized void add(BatteryRecord battery) {
        if (batteries.putIfAbsent(battery.batteryId(), battery) != null) {
            throw new IllegalStateException("Battery is already registered: " + battery.batteryId());
        }
        save();
    }

    public synchronized void update(BatteryRecord battery) {
        if (!batteries.containsKey(battery.batteryId())) {
            throw new IllegalStateException("Battery is not registered: " + battery.batteryId());
        }
        batteries.put(battery.batteryId(), battery);
        save();
    }

    public synchronized BatteryRecord remove(UUID batteryId) {
        BatteryRecord removed = batteries.remove(batteryId);
        if (removed != null) {
            save();
        }
        return removed;
    }

    public synchronized void restore(BatteryRecord battery) {
        batteries.put(battery.batteryId(), battery);
        save();
    }

    public synchronized List<BatteryRecord> removeIsland(long islandIndex) {
        List<BatteryRecord> removed = forIsland(islandIndex);
        removed.forEach(battery -> batteries.remove(battery.batteryId()));
        save();
        return removed;
    }

    public synchronized void flush() throws IOException {
        writeSnapshot(List.copyOf(batteries.values()), version.incrementAndGet());
    }

    private synchronized void save() {
        List<BatteryRecord> snapshot = List.copyOf(batteries.values());
        long snapshotVersion = version.incrementAndGet();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                writeSnapshot(snapshot, snapshotVersion);
            } catch (IOException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not asynchronously save battery data.", exception);
            }
        });
    }

    private void writeSnapshot(List<BatteryRecord> snapshot, long snapshotVersion) throws IOException {
        synchronized (writeLock) {
            if (snapshotVersion < version.get()) {
                return;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            for (BatteryRecord battery : snapshot) {
                String path = "batteries." + battery.batteryId();
                yaml.set(path + ".island-index", battery.islandIndex());
                yaml.set(path + ".world", battery.worldId().toString());
                yaml.set(path + ".x", battery.x());
                yaml.set(path + ".y", battery.y());
                yaml.set(path + ".z", battery.z());
                yaml.set(path + ".item-display", battery.itemDisplayId().toString());
                yaml.set(path + ".interaction", battery.interactionId().toString());
                yaml.set(path + ".text-display", battery.textDisplayId().toString());
                yaml.set(path + ".tier", battery.tier());
                yaml.set(path + ".energy", battery.energy());
                yaml.set(path + ".offline-millis", battery.offlineMillis());
                yaml.set(path + ".last-offline-check", battery.lastOfflineCheck());
                yaml.set(path + ".offline-earned-energy", battery.offlineEarnedEnergy());
                for (Map.Entry<Integer, CellRecord> rune : battery.runes().entrySet()) {
                    String runePath = path + ".runes." + rune.getKey();
                    yaml.set(runePath + ".type", rune.getValue().type().name());
                    yaml.set(runePath + ".tier", rune.getValue().tier());
                }
            }
            Path target = file.toPath();
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), "batteries-", ".yml.tmp");
            try {
                Files.writeString(temporary, yaml.saveToString());
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("batteries");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection battery = section.getConfigurationSection(id);
            if (battery == null) {
                continue;
            }
            UUID batteryId = UUID.fromString(id);
            Map<Integer, CellRecord> runes = new HashMap<>();
            ConfigurationSection runeSection = battery.getConfigurationSection("runes");
            if (runeSection != null) {
                for (String slot : runeSection.getKeys(false)) {
                    ConfigurationSection rune = runeSection.getConfigurationSection(slot);
                    if (rune != null) {
                        runes.put(Integer.parseInt(slot), new CellRecord(
                                CellType.valueOf(rune.getString("type")), rune.getInt("tier")));
                    }
                }
            }
            batteries.put(batteryId, new BatteryRecord(
                    battery.getLong("island-index"),
                    batteryId,
                    UUID.fromString(battery.getString("world")),
                    battery.getDouble("x"),
                    battery.getDouble("y"),
                    battery.getDouble("z"),
                    UUID.fromString(battery.getString("item-display")),
                    UUID.fromString(battery.getString("interaction")),
                    UUID.fromString(battery.getString("text-display")),
                    battery.getInt("tier", 1),
                    battery.getDouble("energy"),
                    runes,
                    battery.getLong("offline-millis"),
                    battery.getLong("last-offline-check"),
                    battery.getDouble("offline-earned-energy")));
        }
    }
}
