package com.corebound.thecore.island;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class IslandRepository {
    private final File file;
    private final Map<UUID, IslandData> islands = new LinkedHashMap<>();
    private long lastIndex = -1;

    public IslandRepository(File file) {
        this.file = file;
        load();
    }

    public Optional<IslandData> byOwner(UUID owner) {
        return Optional.ofNullable(islands.get(owner));
    }

    public Optional<IslandData> byName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return islands.values().stream()
                .filter(island -> island.name().toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst();
    }

    public List<IslandData> all() {
        return List.copyOf(islands.values());
    }

    public long nextIndex() {
        return lastIndex + 1;
    }

    public long reserveNextIndex() throws IOException {
        long previous = lastIndex;
        lastIndex++;
        try {
            save();
        } catch (IOException exception) {
            lastIndex = previous;
            throw exception;
        }
        return lastIndex;
    }

    public void add(IslandData island) throws IOException {
        if (islands.containsKey(island.owner()) || byName(island.name()).isPresent()) {
            throw new IllegalStateException("Island owner or name is already registered.");
        }
        if (island.index() != lastIndex) {
            throw new IllegalStateException("Island index has not been reserved.");
        }

        islands.put(island.owner(), island);
        try {
            save();
        } catch (IOException exception) {
            islands.remove(island.owner());
            throw exception;
        }
    }

    public void replace(IslandData island) throws IOException {
        IslandData previous = islands.put(island.owner(), island);
        try {
            save();
        } catch (IOException exception) {
            if (previous == null) {
                islands.remove(island.owner());
            } else {
                islands.put(island.owner(), previous);
            }
            throw exception;
        }
    }

    public void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("last-index", lastIndex);
        for (IslandData island : islands.values()) {
            String path = "islands." + island.owner();
            yaml.set(path + ".name", island.name());
            yaml.set(path + ".index", island.index());
            yaml.set(path + ".center-x", island.centerX());
            yaml.set(path + ".center-z", island.centerZ());
            yaml.set(path + ".open", island.open());
            yaml.set(path + ".trusted-members", island.trustedMembers().stream()
                    .map(UUID::toString)
                    .toList());
        }

        Path target = file.toPath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "islands-", ".yml.tmp");
        try {
            Files.writeString(temporary, yaml.saveToString());
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        lastIndex = yaml.getLong("last-index", -1);
        ConfigurationSection section = yaml.getConfigurationSection("islands");
        if (section == null) {
            return;
        }

        for (String ownerKey : section.getKeys(false)) {
            ConfigurationSection islandSection = section.getConfigurationSection(ownerKey);
            if (islandSection == null) {
                throw new IllegalStateException("Invalid island data for owner " + ownerKey + ".");
            }
            try {
                UUID owner = UUID.fromString(ownerKey);
                Set<UUID> trustedMembers = new HashSet<>();
                for (String member : islandSection.getStringList("trusted-members")) {
                    trustedMembers.add(UUID.fromString(member));
                }
                IslandData island = new IslandData(
                        owner,
                        islandSection.getString("name", ""),
                        islandSection.getLong("index"),
                        islandSection.getLong("center-x"),
                        islandSection.getLong("center-z"),
                        islandSection.getBoolean("open", true),
                        trustedMembers);
                islands.put(owner, island);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Invalid UUID in island data for owner " + ownerKey + ".", exception);
            }
        }

        long maximumIndex = islands.values().stream().mapToLong(IslandData::index).max().orElse(-1);
        if (lastIndex < maximumIndex) {
            lastIndex = maximumIndex;
        }
    }
}
