package com.corebound.thecore.incremental;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class CropGeneratorRepository {
    private final File file;
    private final JavaPlugin plugin;
    private final Object writeLock = new Object();
    private final AtomicLong version = new AtomicLong();
    private final Map<Long, CropGeneratorRecord> generators = new HashMap<>();
    private final Map<Long, Set<UUID>> pendingItems = new HashMap<>();

    public CropGeneratorRepository(File file, JavaPlugin plugin) {
        this.file = file;
        this.plugin = plugin;
        load();
    }

    public synchronized CropGeneratorRecord get(long islandIndex) {
        return generators.get(islandIndex);
    }

    public synchronized CropGeneratorRecord getById(UUID generatorId) {
        return generators.values().stream()
                .filter(generator -> generator.generatorId().equals(generatorId))
                .findFirst()
                .orElse(null);
    }

    public synchronized boolean hasPendingItem(long islandIndex) {
        return !pendingItems.getOrDefault(islandIndex, Set.of()).isEmpty();
    }

    public synchronized java.util.List<CropGeneratorRecord> all() {
        return java.util.List.copyOf(generators.values());
    }

    public synchronized UUID reserveItem(long islandIndex) {
        UUID itemId = UUID.randomUUID();
        pendingItems.computeIfAbsent(islandIndex, ignored -> new java.util.HashSet<>()).add(itemId);
        save();
        return itemId;
    }

    public synchronized boolean canPlace(long islandIndex, UUID itemId) {
        return itemId != null
                && !generators.containsKey(islandIndex)
                && pendingItems.getOrDefault(islandIndex, Set.of()).contains(itemId);
    }

    public synchronized void add(CropGeneratorRecord generator, UUID itemId) {
        if (!canPlace(generator.islandIndex(), itemId)) {
            throw new IllegalStateException("Crop generator placement was not reserved for this island.");
        }
        releaseItem(generator.islandIndex(), itemId);
        generators.put(generator.islandIndex(), generator);
        save();
    }

    public synchronized void rollbackPlacement(long islandIndex, UUID generatorId, UUID itemId) {
        CropGeneratorRecord current = generators.get(islandIndex);
        if (current != null && current.generatorId().equals(generatorId)) {
            generators.remove(islandIndex);
            pendingItems.computeIfAbsent(islandIndex, ignored -> new java.util.HashSet<>()).add(itemId);
            save();
        }
    }

    public synchronized void releasePendingItem(long islandIndex, UUID itemId) {
        if (itemId != null && releaseItem(islandIndex, itemId)) {
            save();
        }
    }

    public synchronized void update(CropGeneratorRecord generator) {
        if (!generators.containsKey(generator.islandIndex())) {
            throw new IllegalStateException("Crop generator is not registered for island "
                    + generator.islandIndex());
        }
        generators.put(generator.islandIndex(), generator);
        save();
    }

    public synchronized UUID removeAndReserveItem(long islandIndex, UUID generatorId) {
        CropGeneratorRecord existing = generators.get(islandIndex);
        if (existing == null || !existing.generatorId().equals(generatorId)) {
            return null;
        }
        generators.remove(islandIndex);
        UUID itemId = UUID.randomUUID();
        pendingItems.computeIfAbsent(islandIndex, ignored -> new java.util.HashSet<>()).add(itemId);
        save();
        return itemId;
    }

    public synchronized void restore(CropGeneratorRecord generator, UUID itemId) {
        generators.put(generator.islandIndex(), generator);
        releaseItem(generator.islandIndex(), itemId);
        save();
    }

    public synchronized CropGeneratorRecord removeForFracture(long islandIndex) {
        CropGeneratorRecord removed = generators.remove(islandIndex);
        pendingItems.remove(islandIndex);
        save();
        return removed;
    }

    private boolean releaseItem(long islandIndex, UUID itemId) {
        Set<UUID> islandItems = pendingItems.get(islandIndex);
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
                plugin.getLogger().log(Level.SEVERE, "Could not asynchronously save crop generator data.", exception);
            }
        });
    }

    private Snapshot snapshot() {
        Map<Long, Set<UUID>> pendingSnapshot = new HashMap<>();
        pendingItems.forEach((island, ids) -> pendingSnapshot.put(island, Set.copyOf(ids)));
        return new Snapshot(Map.copyOf(generators), Map.copyOf(pendingSnapshot));
    }

    private void writeSnapshot(Snapshot snapshot, long snapshotVersion) throws IOException {
        synchronized (writeLock) {
            if (snapshotVersion < version.get()) {
                return;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            for (CropGeneratorRecord generator : snapshot.generators().values()) {
                String path = "generators." + generator.islandIndex();
                yaml.set(path + ".id", generator.generatorId().toString());
                yaml.set(path + ".world", generator.worldId().toString());
                yaml.set(path + ".x", generator.x());
                yaml.set(path + ".y", generator.y());
                yaml.set(path + ".z", generator.z());
                yaml.set(path + ".item-display", generator.itemDisplayId().toString());
                yaml.set(path + ".interaction", generator.interactionId().toString());
                yaml.set(path + ".text-display", generator.textDisplayId().toString());
                yaml.set(path + ".level", generator.level());
                yaml.set(path + ".state", generator.state().name());
                yaml.set(path + ".rune-slots", generator.runeSlots());
                generator.runes().forEach((slot, rune) -> {
                    String runePath = path + ".runes." + slot;
                    yaml.set(runePath + ".type", rune.type().name());
                    yaml.set(runePath + ".tier", rune.tier());
                });
            }
            snapshot.pendingItems().forEach((island, itemIds) -> itemIds.forEach(itemId ->
                    yaml.set("pending-items." + island + "." + itemId, true)));

            Path target = file.toPath();
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), "crop-generators-", ".yml.tmp");
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
        ConfigurationSection section = yaml.getConfigurationSection("generators");
        if (section != null) {
            for (String islandKey : section.getKeys(false)) {
                ConfigurationSection generator = section.getConfigurationSection(islandKey);
                if (generator == null) {
                    continue;
                }
                long islandIndex = Long.parseLong(islandKey);
                generators.put(islandIndex, new CropGeneratorRecord(
                        islandIndex,
                        UUID.fromString(generator.getString("id")),
                        UUID.fromString(generator.getString("world")),
                        generator.getDouble("x"),
                        generator.getDouble("y"),
                        generator.getDouble("z"),
                        UUID.fromString(generator.getString("item-display")),
                        UUID.fromString(generator.getString("interaction")),
                        UUID.fromString(generator.getString("text-display")),
                        generator.getInt("level"),
                        CondenserState.valueOf(generator.getString("state", "ACTIVE")),
                        generator.getInt("rune-slots", 1),
                        loadRunes(generator.getConfigurationSection("runes"))));
            }
        }
        ConfigurationSection pending = yaml.getConfigurationSection("pending-items");
        if (pending != null) {
            for (String islandKey : pending.getKeys(false)) {
                long islandIndex = Long.parseLong(islandKey);
                ConfigurationSection items = pending.getConfigurationSection(islandKey);
                if (items == null) {
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
            Map<Long, CropGeneratorRecord> generators,
            Map<Long, Set<UUID>> pendingItems) {
    }
}
