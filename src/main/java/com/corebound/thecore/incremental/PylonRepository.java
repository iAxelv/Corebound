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

public final class PylonRepository {
    private final File file;
    private final JavaPlugin plugin;
    private final Object writeLock = new Object();
    private final AtomicLong version = new AtomicLong();
    private final Map<UUID, PylonRecord> pylons = new HashMap<>();

    public PylonRepository(File file, JavaPlugin plugin) {
        this.file = file;
        this.plugin = plugin;
        load();
    }

    public synchronized PylonRecord get(UUID pylonId) {
        return pylons.get(pylonId);
    }

    public synchronized List<PylonRecord> forIsland(long islandIndex) {
        return pylons.values().stream()
                .filter(pylon -> pylon.islandIndex() == islandIndex)
                .toList();
    }

    public synchronized List<PylonRecord> all() {
        return List.copyOf(pylons.values());
    }

    public synchronized void add(PylonRecord pylon) {
        if (pylons.putIfAbsent(pylon.pylonId(), pylon) != null) {
            throw new IllegalStateException("Pylon already registered: " + pylon.pylonId());
        }
        save();
    }

    public synchronized void flush() throws IOException {
        writeSnapshot(snapshot(), version.incrementAndGet());
    }

    private synchronized void save() {
        Snapshot snapshot = snapshot();
        long currentVersion = version.incrementAndGet();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                writeSnapshot(snapshot, currentVersion);
            } catch (IOException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not asynchronously save pylon data.", exception);
            }
        });
    }

    private Snapshot snapshot() {
        return new Snapshot(List.copyOf(pylons.values()));
    }

    private void writeSnapshot(Snapshot snapshot, long snapshotVersion) throws IOException {
        synchronized (writeLock) {
            if (snapshotVersion < version.get()) {
                return;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            for (PylonRecord pylon : snapshot.pylons()) {
                String path = "pylons." + pylon.pylonId();
                yaml.set(path + ".island-index", pylon.islandIndex());
                yaml.set(path + ".world", pylon.worldId().toString());
                yaml.set(path + ".x", pylon.x());
                yaml.set(path + ".y", pylon.y());
                yaml.set(path + ".z", pylon.z());
                yaml.set(path + ".item-display", pylon.itemDisplayId().toString());
                yaml.set(path + ".rod-display", pylon.rodDisplayId().toString());
                yaml.set(path + ".interaction", pylon.interactionId().toString());
                yaml.set(path + ".tier", pylon.tier());
            }
            Path target = file.toPath();
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), "pylons-", ".yml.tmp");
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
        ConfigurationSection section = yaml.getConfigurationSection("pylons");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection pylon = section.getConfigurationSection(id);
            if (pylon == null) {
                continue;
            }
            UUID pylonId = UUID.fromString(id);
            pylons.put(pylonId, new PylonRecord(
                    pylon.getLong("island-index"),
                    pylonId,
                    UUID.fromString(pylon.getString("world")),
                    pylon.getDouble("x"),
                    pylon.getDouble("y"),
                    pylon.getDouble("z"),
                    UUID.fromString(pylon.getString("item-display")),
                    UUID.fromString(pylon.getString("rod-display")),
                    UUID.fromString(pylon.getString("interaction")),
                    pylon.getInt("tier", 1)));
        }
    }

    private record Snapshot(List<PylonRecord> pylons) {
    }
}
