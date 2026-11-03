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
    private final Map<Long, java.util.Set<UUID>> pendingItems = new HashMap<>();

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
        return !pendingItems.getOrDefault(islandIndex, java.util.Set.of()).isEmpty();
    }

    public synchronized List<CondenserRecord> all() {
        return List.copyOf(condensers.values());
    }

    public synchronized UUID reserveItem(long islandIndex) {
        UUID itemId = UUID.randomUUID();
        pendingItems.computeIfAbsent(islandIndex, ignored -> new java.util.HashSet<>()).add(itemId);
        save();
        return itemId;
    }

    public synchronized boolean canPlace(long islandIndex, UUID itemId) {
        return itemId != null
                && !condensers.containsKey(islandIndex)
                && pendingItems.getOrDefault(islandIndex, java.util.Set.of()).contains(itemId);
    }

    public synchronized void add(CondenserRecord condenser, UUID itemId) {
        if (!canPlace(condenser.islandIndex(), itemId)) {
            throw new IllegalStateException("Condenser placement was not reserved for this island.");
        }
        releaseItem(condenser.islandIndex(), itemId);
        condensers.put(condenser.islandIndex(), condenser);
        save();
    }

    public synchronized void rollbackPlacement(long islandIndex, UUID condenserId, UUID itemId) {
        CondenserRecord current = condensers.get(islandIndex);
        if (current != null && current.condenserId().equals(condenserId)) {
            condensers.remove(islandIndex);
            pendingItems.computeIfAbsent(islandIndex, ignored -> new java.util.HashSet<>()).add(itemId);
            save();
        }
    }

    public synchronized void releasePendingItem(long islandIndex, UUID itemId) {
        if (itemId != null && releaseItem(islandIndex, itemId)) {
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
        if (existing == null || !existing.condenserId().equals(condenserId)) {
            return null;
        }
        condensers.remove(islandIndex);
        UUID itemId = UUID.randomUUID();
        pendingItems.computeIfAbsent(islandIndex, ignored -> new java.util.HashSet<>()).add(itemId);
        save();
        return itemId;
    }

    public synchronized void restore(CondenserRecord condenser, UUID itemId) {
        condensers.put(condenser.islandIndex(), condenser);
        releaseItem(condenser.islandIndex(), itemId);
        save();
    }

    public synchronized CondenserRecord removeForFracture(long islandIndex) {
        CondenserRecord removed = condensers.remove(islandIndex);
        pendingItems.remove(islandIndex);
        save();
        return removed;
    }

    private boolean releaseItem(long islandIndex, UUID itemId) {
        java.util.Set<UUID> islandItems = pendingItems.get(islandIndex);
        if (islandItems == null || !islandItems.remove(itemId)) {
            return false;
        }
        if (islandItems.isEmpty()) {
            pendingItems.remove(islandIndex);
        }
        return true;
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
        Map<Long, java.util.Set<UUID>> pendingSnapshot = new HashMap<>();
        pendingItems.forEach((island, ids) -> pendingSnapshot.put(island, java.util.Set.copyOf(ids)));
        return new Snapshot(Map.copyOf(condensers), Map.copyOf(pendingSnapshot));
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
                yaml.set(path + ".rune-slots", condenser.runeSlots());
                condenser.runes().forEach((slot, rune) -> {
                    String runePath = path + ".runes." + slot;
                    yaml.set(runePath + ".type", rune.type().name());
                    yaml.set(runePath + ".tier", rune.tier());
                });
            }
            snapshot.pendingItems().forEach((island, itemIds) -> itemIds.forEach(itemId ->
                    yaml.set("pending-items." + island + "." + itemId, true)));

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
                        CondenserState.valueOf(condenser.getString("state", "ACTIVE")),
                        condenser.getInt("rune-slots", 1),
                        loadRunes(condenser.getConfigurationSection("runes"))));
            }
        }
        ConfigurationSection pending = yaml.getConfigurationSection("pending-items");
        if (pending != null) {
            for (String islandKey : pending.getKeys(false)) {
                long islandIndex = Long.parseLong(islandKey);
                ConfigurationSection items = pending.getConfigurationSection(islandKey);
                if (items == null) {
                    String legacyId = pending.getString(islandKey);
                    if (legacyId != null) {
                        pendingItems.computeIfAbsent(islandIndex, ignored -> new java.util.HashSet<>())
                                .add(UUID.fromString(legacyId));
                    }
                    continue;
                }
                for (String itemId : items.getKeys(false)) {
                    pendingItems.computeIfAbsent(islandIndex, ignored -> new java.util.HashSet<>())
                            .add(UUID.fromString(itemId));
                }
            }
        }
    }

    private Map<Integer, CellRecord> loadRunes(ConfigurationSection section) {
        if (section == null) {
            return Map.of();
        }
        Map<Integer, CellRecord> runes = new HashMap<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection rune = section.getConfigurationSection(key);
            if (rune != null) {
                runes.put(Integer.parseInt(key), new CellRecord(
                        CellType.valueOf(rune.getString("type")), rune.getInt("tier")));
            }
        }
        return Map.copyOf(runes);
    }

    private record Snapshot(
            Map<Long, CondenserRecord> condensers,
            Map<Long, java.util.Set<UUID>> pendingItems) {
    }
}
