package com.corebound.thecore.incremental;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
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

    public synchronized CoreRecord upgradeCore(long islandIndex, double cost) {
        CoreRecord current = cores.get(islandIndex);
        if (current == null) {
            return null;
        }
        CoreRecord upgraded = current.upgrade(cost);
        if (upgraded != null) {
            cores.put(islandIndex, upgraded);
            save();
        }
        return upgraded;
    }

    public synchronized CoreRecord spendEnergy(long islandIndex, double cost) {
        CoreRecord current = cores.get(islandIndex);
        if (current == null) {
            return null;
        }
        CoreRecord updated = current.spendEnergy(cost);
        if (updated != null) {
            cores.put(islandIndex, updated);
            save();
        }
        return updated;
    }

    public synchronized CoreRecord upgradeEnhancement(
            long islandIndex, CoreEnhancement enhancement, double cost, int maxLevel) {
        CoreRecord current = cores.get(islandIndex);
        if (current == null) {
            return null;
        }
        CoreRecord updated = current.upgradeEnhancement(enhancement, cost, maxLevel);
        if (updated != null) {
            cores.put(islandIndex, updated);
            save();
        }
        return updated;
    }

    public synchronized CoreRecord fractureCore(long islandIndex) {
        CoreRecord current = cores.get(islandIndex);
        if (current == null) {
            return null;
        }
        CoreRecord fractured = current.fracture();
        if (fractured != null) {
            cores.put(islandIndex, fractured);
            save();
        }
        return fractured;
    }

    public synchronized void restoreEnergy(long islandIndex, double amount) {
        CoreRecord current = cores.get(islandIndex);
        if (current == null || !Double.isFinite(amount) || amount <= 0) {
            return;
        }
        cores.put(islandIndex, new CoreRecord(
                current.islandIndex(),
                current.worldId(),
                current.x(),
                current.y(),
                current.z(),
                current.itemDisplayId(),
                current.interactionId(),
                current.textDisplayId(),
                current.level(),
                Math.min(current.maxEnergy(), current.energy() + amount),
                current.hits(),
                current.lastHitAt(),
                current.upgradeOvercharge(),
                current.upgradeWindow(),
                current.upgradeCritical(),
                current.prestigeCount(),
                current.coreFragments()));
        save();
    }

    public synchronized CoreRecord registerHit(long islandIndex, long now, long comboWindowMillis) {
        CoreRecord.HitResult result = registerHitResult(islandIndex, now, comboWindowMillis, 1.0);
        return result == null ? null : result.core();
    }

    public synchronized CoreRecord registerHit(
            long islandIndex, long now, long comboWindowMillis, double yieldRatio) {
        CoreRecord.HitResult result = registerHitResult(islandIndex, now, comboWindowMillis, yieldRatio);
        return result == null ? null : result.core();
    }

    public synchronized CoreRecord.HitResult registerHitResult(
            long islandIndex, long now, long comboWindowMillis, double yieldRatio) {
        CoreRecord current = cores.get(islandIndex);
        if (current == null) {
            return null;
        }
        CoreRecord.HitResult result = current.withHitResult(now, comboWindowMillis, yieldRatio);
        cores.put(islandIndex, result.core());
        save();
        return result;
    }

    public synchronized CoreRecord addPassiveEnergy(long islandIndex, double amount) {
        CoreRecord current = cores.get(islandIndex);
        if (current == null) {
            return null;
        }
        CoreRecord updated = current.withPassiveEnergy(amount);
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
                yaml.set(path + ".upgrade-overcharge", core.upgradeOvercharge());
                yaml.set(path + ".upgrade-window", core.upgradeWindow());
                yaml.set(path + ".upgrade-critical", core.upgradeCritical());
                yaml.set(path + ".prestige-count", core.prestigeCount());
                yaml.set(path + ".core-fragments", core.coreFragments());
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
                } catch (AtomicMoveNotSupportedException | AccessDeniedException exception) {
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
                        core.getLong("last-hit-at", 0L),
                        core.getInt("upgrade-overcharge", 0),
                        core.getInt("upgrade-window", 0),
                        core.getInt("upgrade-critical", 0),
                        core.getInt("prestige-count", 0),
                        core.getInt("core-fragments", core.getInt("prestige-count", 0))));
            }
        }
        for (String playerId : yaml.getStringList("pending-items")) {
            pendingItems.add(UUID.fromString(playerId));
        }
    }

    private record Snapshot(Map<Long, CoreRecord> cores, Set<UUID> pendingItems) {
    }
}
