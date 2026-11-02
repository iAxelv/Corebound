package com.corebound.thecore.incremental;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class CoreRepository {
    private final File file;
    private final JavaPlugin plugin;
    private final Object writeLock = new Object();
    private final AtomicLong version = new AtomicLong();
    private final Map<Long, CoreRecord> cores = new HashMap<>();
    private final Set<UUID> pendingItems = new HashSet<>();

    public CoreRepository(File file, JavaPlugin plugin) {
        this.file = file;
        this.plugin = plugin;
        load();
    }

    public synchronized boolean hasCore(long islandIndex) {
        return cores.containsKey(islandIndex);
    }

    public synchronized CoreRecord core(long islandIndex) {
        return cores.get(islandIndex);
    }

    public synchronized List<CoreRecord> allCores() {
        return List.copyOf(cores.values());
    }

    public synchronized void addCore(CoreRecord core) {
        if (cores.putIfAbsent(core.islandIndex(), core) != null) {
            throw new IllegalStateException("An island core is already registered for island " + core.islandIndex());
        }
        save();
    }

    public synchronized CoreRecord removeCore(long islandIndex) {
        CoreRecord removed = cores.remove(islandIndex);
        if (removed != null) {
            save();
        }
        return removed;
    }

    public synchronized CoreRecord registerHit(long islandIndex, long now, long comboWindowMillis) {
        CoreRecord current = cores.get(islandIndex);
        if (current == null) {
            return null;
        }
        CoreRecord updated = current.withHit(now, comboWindowMillis);
        cores.put(islandIndex, updated);
        save();
        return updated;
    }

    public synchronized CoreRecord expireCombo(long islandIndex, long expectedLastHitAt) {
        CoreRecord current = cores.get(islandIndex);
        if (current == null || current.lastHitAt() != expectedLastHitAt || current.hits() == 0) {
            return null;
        }
        CoreRecord updated = current.withoutCombo();
        cores.put(islandIndex, updated);
        save();
        return updated;
    }

    public synchronized void markItemPending(UUID playerId) {
        if (pendingItems.add(playerId)) {
            save();
        }
    }

    public synchronized boolean hasPendingItem(UUID playerId) {
        return pendingItems.contains(playerId);
    }

    public synchronized void clearPendingItem(UUID playerId) {
        if (pendingItems.remove(playerId)) {
            save();
        }
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
                plugin.getLogger().log(Level.SEVERE, "Could not asynchronously save core data.", exception);
            }
        });
    }

    private Snapshot snapshot() {
        return new Snapshot(Map.copyOf(cores), Set.copyOf(pendingItems));
    }

    private void writeSnapshot(Snapshot snapshot, long snapshotVersion) throws IOException {
        synchronized (writeLock) {
            if (snapshotVersion < version.get()) {
                return;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            for (CoreRecord core : snapshot.cores().values()) {
                String path = "cores." + core.islandIndex();
                yaml.set(path + ".world", core.worldId().toString());
                yaml.set(path + ".x", core.x());
                yaml.set(path + ".y", core.y());
                yaml.set(path + ".z", core.z());
                yaml.set(path + ".item-display", core.itemDisplayId().toString());
                yaml.set(path + ".interaction", core.interactionId().toString());
                yaml.set(path + ".text-display", core.textDisplayId().toString());
                yaml.set(path + ".level", core.level());
                yaml.set(path + ".energy", core.energy());
                yaml.set(path + ".hits", core.hits());
                yaml.set(path + ".last-hit-at", core.lastHitAt());
            }
            yaml.set("pending-items", snapshot.pendingItems().stream().map(UUID::toString).toList());
            Path target = file.toPath();
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), "cores-", ".yml.tmp");
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
        ConfigurationSection section = yaml.getConfigurationSection("cores");
        if (section != null) {
            for (String indexKey : section.getKeys(false)) {
                ConfigurationSection core = section.getConfigurationSection(indexKey);
                if (core == null) {
                    continue;
                }
                long index = Long.parseLong(indexKey);
                cores.put(index, new CoreRecord(
                        index,
                        UUID.fromString(core.getString("world")),
                        core.getDouble("x"),
                        core.getDouble("y"),
                        core.getDouble("z"),
                        UUID.fromString(core.getString("item-display")),
                        UUID.fromString(core.getString("interaction")),
                        UUID.fromString(core.getString("text-display")),
                        core.getInt("level", 1),
                        core.getDouble("energy", 0.0),
                        core.getInt("hits", 0),
                        core.getLong("last-hit-at", 0L)));
            }
        }
        for (String playerId : yaml.getStringList("pending-items")) {
            pendingItems.add(UUID.fromString(playerId));
        }
    }

    private record Snapshot(Map<Long, CoreRecord> cores, Set<UUID> pendingItems) {
    }
}
