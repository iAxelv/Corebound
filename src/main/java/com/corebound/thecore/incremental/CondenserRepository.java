package com.corebound.thecore.incremental;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
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

public final class CondenserRepository {
    private final File file;
    private final JavaPlugin plugin;
    private final Object writeLock = new Object();
    private final AtomicLong version = new AtomicLong();
    private final Map<Long, CondenserRecord> condensers = new HashMap<>();
    private final Map<Long, UUID> pendingItems = new HashMap<>();

    public CondenserRepository(File file, JavaPlugin plugin) {
        this.file = file;
        this.plugin = plugin;
        load();
    }

    public synchronized CondenserRecord get(long islandIndex) {
        return condensers.get(islandIndex);
    }

    public synchronized CondenserRecord getById(UUID condenserId) {
        return condensers.values().stream()
                .filter(condenser -> condenser.condenserId().equals(condenserId))
                .findFirst()
                .orElse(null);
    }

    public synchronized boolean hasPendingItem(long islandIndex) {
        return pendingItems.containsKey(islandIndex);
    }

    public synchronized List<CondenserRecord> all() {
        return List.copyOf(condensers.values());
    }

    public synchronized UUID reserveItem(long islandIndex) {
        if (condensers.containsKey(islandIndex) || pendingItems.containsKey(islandIndex)) {
            return null;
        }
        UUID itemId = UUID.randomUUID();
        pendingItems.put(islandIndex, itemId);
        save();
        return itemId;
    }

    public synchronized boolean canPlace(long islandIndex, UUID itemId) {
        return itemId != null
                && !condensers.containsKey(islandIndex)
                && itemId.equals(pendingItems.get(islandIndex));
    }

    public synchronized void add(CondenserRecord condenser, UUID itemId) {
        if (!canPlace(condenser.islandIndex(), itemId)) {
            throw new IllegalStateException("Condenser placement was not reserved for this island.");
        }
        pendingItems.remove(condenser.islandIndex());
        condensers.put(condenser.islandIndex(), condenser);
        save();
    }

    public synchronized void rollbackPlacement(long islandIndex, UUID condenserId, UUID itemId) {
        CondenserRecord current = condensers.get(islandIndex);
        if (current != null && current.condenserId().equals(condenserId)) {
            condensers.remove(islandIndex);
            pendingItems.put(islandIndex, itemId);
            save();
        }
    }

    public synchronized void releasePendingItem(long islandIndex, UUID itemId) {
        if (itemId != null && itemId.equals(pendingItems.get(islandIndex))) {
            pendingItems.remove(islandIndex);
            save();
        }
    }

    public synchronized void update(CondenserRecord condenser) {
        if (!condensers.containsKey(condenser.islandIndex())) {
            throw new IllegalStateException("Condenser is not registered for island " + condenser.islandIndex());
        }
        condensers.put(condenser.islandIndex(), condenser);
        save();
    }

    public synchronized UUID removeAndReserveItem(long islandIndex, UUID condenserId) {
        CondenserRecord existing = condensers.get(islandIndex);
        if (existing == null || !existing.condenserId().equals(condenserId)
                || pendingItems.containsKey(islandIndex)) {
            return null;
        }
        condensers.remove(islandIndex);
        UUID itemId = UUID.randomUUID();
        pendingItems.put(islandIndex, itemId);
        save();
        return itemId;
    }

    public synchronized void restore(CondenserRecord condenser) {
        condensers.put(condenser.islandIndex(), condenser);
        pendingItems.remove(condenser.islandIndex());
        save();
    }

    public synchronized void flush() throws IOException {
        writeSnapshot(snapshot(), version.incrementAndGet());
    }

    private synchronized void save() {
        Snapshot snapshot = snapshot();
        long snapshotVersion = version.incrementAndGet();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                writeSnapshot(snapshot, snapshotVersion);
            } catch (IOException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not asynchronously save condenser data.", exception);
            }
        });
    }

    private Snapshot snapshot() {
        return new Snapshot(Map.copyOf(condensers), Map.copyOf(pendingItems));
    }

    private void writeSnapshot(Snapshot snapshot, long snapshotVersion) throws IOException {
        synchronized (writeLock) {
            if (snapshotVersion < version.get()) {
                return;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            for (CondenserRecord condenser : snapshot.condensers().values()) {
                String path = "condensers." + condenser.islandIndex();
                yaml.set(path + ".id", condenser.condenserId().toString());
                yaml.set(path + ".world", condenser.worldId().toString());
                yaml.set(path + ".x", condenser.x());
                yaml.set(path + ".y", condenser.y());
                yaml.set(path + ".z", condenser.z());
                yaml.set(path + ".item-display", condenser.itemDisplayId().toString());
                yaml.set(path + ".interaction", condenser.interactionId().toString());
                yaml.set(path + ".text-display", condenser.textDisplayId().toString());
                yaml.set(path + ".level", condenser.level());
                yaml.set(path + ".state", condenser.state().name());
            }
            snapshot.pendingItems().forEach((island, itemId) ->
                    yaml.set("pending-items." + island, itemId.toString()));

            Path target = file.toPath();
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), "condensers-", ".yml.tmp");
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
        ConfigurationSection section = yaml.getConfigurationSection("condensers");
        if (section != null) {
            for (String islandKey : section.getKeys(false)) {
                ConfigurationSection condenser = section.getConfigurationSection(islandKey);
                if (condenser == null) {
                    continue;
                }
                long islandIndex = Long.parseLong(islandKey);
                condensers.put(islandIndex, new CondenserRecord(
                        islandIndex,
                        UUID.fromString(condenser.getString("id")),
                        UUID.fromString(condenser.getString("world")),
                        condenser.getDouble("x"),
                        condenser.getDouble("y"),
                        condenser.getDouble("z"),
                        UUID.fromString(condenser.getString("item-display")),
                        UUID.fromString(condenser.getString("interaction")),
                        UUID.fromString(condenser.getString("text-display")),
                        condenser.getInt("level"),
                        CondenserState.valueOf(condenser.getString("state", "ACTIVE"))));
            }
        }
        ConfigurationSection pending = yaml.getConfigurationSection("pending-items");
        if (pending != null) {
            for (String islandKey : pending.getKeys(false)) {
                pendingItems.put(Long.parseLong(islandKey), UUID.fromString(pending.getString(islandKey)));
            }
        }
    }

    private record Snapshot(Map<Long, CondenserRecord> condensers, Map<Long, UUID> pendingItems) {
    }
}
