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

    public Optional<IslandData> byMember(UUID playerId) {
        return islands.values().stream().filter(island -> island.members().containsKey(playerId)).findFirst();
    }

    public Optional<IslandData> byCoop(UUID playerId) {
        return islands.values().stream().filter(island -> island.coops().contains(playerId)).findFirst();
    }

    public Optional<IslandData> forPlayer(UUID playerId) {
        return byOwner(playerId).or(() -> byMember(playerId));
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

    public void remove(UUID owner) throws IOException {
        IslandData previous = islands.remove(owner);
        if (previous == null) {
            return;
        }
        try {
            save();
        } catch (IOException exception) {
            islands.put(owner, previous);
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
            yaml.set(path + ".created-at", island.createdAt());
            yaml.set(path + ".members", null);
            island.members().forEach((member, joinedAt) ->
                    yaml.set(path + ".members." + member, joinedAt));
            yaml.set(path + ".coops", island.coops().stream()
                    .map(UUID::toString)
                    .toList());
            yaml.set(path + ".banned", island.bannedPlayers().stream()
                    .map(UUID::toString)
                    .toList());
            island.settings().save(yaml.createSection(path + ".settings"));
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
                Map<UUID, Long> members = new LinkedHashMap<>();
                ConfigurationSection membersSection = islandSection.getConfigurationSection("members");
                if (membersSection != null) {
                    for (String memberKey : membersSection.getKeys(false)) {
                        members.put(UUID.fromString(memberKey), membersSection.getLong(memberKey));
                    }
                }
                Set<UUID> coops = readUuids(islandSection.getStringList("coops"));
                coops.addAll(readUuids(islandSection.getStringList("trusted-members")));
                IslandData island = new IslandData(
                        owner,
                        islandSection.getString("name", ""),
                        islandSection.getLong("index"),
                        islandSection.getLong("center-x"),
                        islandSection.getLong("center-z"),
                        islandSection.getBoolean("open", true),
                        islandSection.getLong("created-at", 0L),
                        members,
                        coops,
                        readUuids(islandSection.getStringList("banned")),
                        IslandSettings.load(islandSection.getConfigurationSection("settings")));
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

    private Set<UUID> readUuids(List<String> values) {
        Set<UUID> uuids = new HashSet<>();
        for (String value : values) {
            uuids.add(UUID.fromString(value));
        }
        return uuids;
    }
}
