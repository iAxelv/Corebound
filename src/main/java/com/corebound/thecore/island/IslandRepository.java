package com.corebound.thecore.island;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class IslandRepository {
    private final File file;
    private final Map<UUID, IslandData> islands = new LinkedHashMap<>();
    private final Map<Long, IslandData> islandsByIndex = new LinkedHashMap<>();
    private final JavaPlugin plugin;
    private final boolean synchronousWrites;
    private final Object writeLock = new Object();
    private final AtomicLong writeVersion = new AtomicLong();
    private long lastIndex = -1;

    public IslandRepository(File file) {
        this(file, null);
    }

    public IslandRepository(File file, JavaPlugin plugin) {
        this.file = file;
        this.plugin = plugin;
        synchronousWrites = plugin == null;
        load();
    }

    public synchronized void transferOwnership(UUID previousOwner, IslandData updatedIsland) throws IOException {
        IslandData previous = islands.remove(previousOwner);
        if (previous == null || !previous.owner().equals(previousOwner)
                || islands.containsKey(updatedIsland.owner())) {
            if (previous != null) {
                islands.put(previousOwner, previous);
            }
            throw new IllegalStateException("Could not transfer island ownership to an existing owner.");
        }
        islandsByIndex.remove(previous.index());
        islands.put(updatedIsland.owner(), updatedIsland);
        islandsByIndex.put(updatedIsland.index(), updatedIsland);
        try {
            save();
        } catch (IOException exception) {
            islands.remove(updatedIsland.owner());
            islands.put(previousOwner, previous);
            islandsByIndex.remove(updatedIsland.index());
            islandsByIndex.put(previous.index(), previous);
            throw exception;
        }
    }

    public synchronized Optional<IslandData> byOwner(UUID owner) {
        return Optional.ofNullable(islands.get(owner));
    }

    public synchronized Optional<IslandData> byIndex(long index) {
        return Optional.ofNullable(islandsByIndex.get(index));
    }

    public synchronized Optional<IslandData> byMember(UUID playerId) {
        return islands.values().stream().filter(island -> island.members().containsKey(playerId)).findFirst();
    }

    public synchronized Optional<IslandData> byCoop(UUID playerId) {
        return islands.values().stream().filter(island -> island.coops().contains(playerId)).findFirst();
    }

    public synchronized Optional<IslandData> forPlayer(UUID playerId) {
        return byOwner(playerId).or(() -> byMember(playerId));
    }

    public synchronized Optional<IslandData> byName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return islands.values().stream()
                .filter(island -> island.name().toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst();
    }

    public synchronized List<IslandData> all() {
        return List.copyOf(islands.values());
    }

    public synchronized long nextIndex() {
        return lastIndex + 1;
    }

    public synchronized long reserveNextIndex() throws IOException {
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

    public synchronized void add(IslandData island) throws IOException {
        if (islands.containsKey(island.owner()) || byName(island.name()).isPresent()) {
            throw new IllegalStateException("Island owner or name is already registered.");
        }
        if (island.index() != lastIndex) {
            throw new IllegalStateException("Island index has not been reserved.");
        }

        islands.put(island.owner(), island);
        islandsByIndex.put(island.index(), island);
        try {
            save();
        } catch (IOException exception) {
            islands.remove(island.owner());
            islandsByIndex.remove(island.index());
            throw exception;
        }
    }

    public synchronized void replace(IslandData island) throws IOException {
        IslandData previous = islands.put(island.owner(), island);
        if (previous != null) {
            islandsByIndex.remove(previous.index());
        }
        islandsByIndex.put(island.index(), island);
        try {
            save();
        } catch (IOException exception) {
            islandsByIndex.remove(island.index());
            if (previous == null) {
                islands.remove(island.owner());
            } else {
                islands.put(island.owner(), previous);
                islandsByIndex.put(previous.index(), previous);
            }
            throw exception;
        }
    }

    public synchronized void remove(UUID owner) throws IOException {
        IslandData previous = islands.remove(owner);
        if (previous == null) {
            return;
        }
        islandsByIndex.remove(previous.index());
        try {
            save();
        } catch (IOException exception) {
            islands.put(owner, previous);
            islandsByIndex.put(previous.index(), previous);
            throw exception;
        }
    }

    public synchronized void save() throws IOException {
        Snapshot snapshot = new Snapshot(new ArrayList<>(islands.values()), lastIndex);
        if (synchronousWrites) {
            writeSnapshot(snapshot, writeVersion.incrementAndGet());
            return;
        }
        long version = writeVersion.incrementAndGet();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                writeSnapshot(snapshot, version);
            } catch (IOException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not asynchronously save island data.", exception);
            }
        });
    }

    public synchronized void flush() throws IOException {
        Snapshot snapshot = new Snapshot(new ArrayList<>(islands.values()), lastIndex);
        writeSnapshot(snapshot, writeVersion.incrementAndGet());
    }

    private void writeSnapshot(Snapshot snapshot, long version) throws IOException {
        synchronized (writeLock) {
            if (version < writeVersion.get()) {
                return;
            }
            writeSnapshot(snapshot);
        }
    }

    private void writeSnapshot(Snapshot snapshot) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("last-index", snapshot.lastIndex());
        for (IslandData island : snapshot.islands()) {
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
            yaml.set(path + ".member-ranks", null);
            island.memberRanks().forEach((member, rank) ->
                    yaml.set(path + ".member-ranks." + member, rank.configName()));
            yaml.set(path + ".coops", island.coops().stream()
                    .map(player -> player.toString())
                    .toList());
            yaml.set(path + ".banned", island.bannedPlayers().stream()
                    .map(player -> player.toString())
                    .toList());
            island.settings().save(yaml.createSection(path + ".settings"));
            yaml.set(path + ".permission-minimums", null);
            island.permissionMinimums().forEach((permission, rank) ->
                    yaml.set(path + ".permission-minimums." + permission.configKey(), rank.configName()));
            yaml.set(path + ".permission-overrides", null);
            island.permissionOverrides().forEach((player, overrides) -> overrides.forEach((permission, enabled) ->
                    yaml.set(path + ".permission-overrides." + player + "." + permission.configKey(), enabled)));
            yaml.set(path + ".all-permission-overrides", null);
            island.allPermissionOverrides().forEach((player, enabled) ->
                    yaml.set(path + ".all-permission-overrides." + player, enabled));
            if (island.spawn() != null) {
                yaml.set(path + ".spawn.x", island.spawn().x());
                yaml.set(path + ".spawn.y", island.spawn().y());
                yaml.set(path + ".spawn.z", island.spawn().z());
                yaml.set(path + ".spawn.yaw", island.spawn().yaw());
                yaml.set(path + ".spawn.pitch", island.spawn().pitch());
            }
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

    private record Snapshot(List<IslandData> islands, long lastIndex) {
        private Snapshot {
            islands = List.copyOf(islands);
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
                Map<UUID, IslandRank> memberRanks = new LinkedHashMap<>();
                ConfigurationSection ranksSection = islandSection.getConfigurationSection("member-ranks");
                members.keySet().forEach(member -> memberRanks.put(member, IslandRank.MEMBER));
                if (ranksSection != null) {
                    for (String memberKey : ranksSection.getKeys(false)) {
                        UUID memberId = UUID.fromString(memberKey);
                        if (members.containsKey(memberId)) {
                            memberRanks.put(memberId, IslandRank.fromConfig(
                                    ranksSection.getString(memberKey), IslandRank.MEMBER));
                        }
                    }
                }
                Set<UUID> coops = readUuids(islandSection.getStringList("coops"));
                coops.addAll(readUuids(islandSection.getStringList("trusted-members")));
                Map<IslandPermission, IslandRank> permissionMinimums = IslandPermissions.defaults();
                ConfigurationSection minimumsSection =
                        islandSection.getConfigurationSection("permission-minimums");
                if (minimumsSection != null) {
                    for (String key : minimumsSection.getKeys(false)) {
                        IslandPermission permission = IslandPermission.fromConfigKey(key);
                        permissionMinimums.put(permission, IslandRank.fromConfig(
                                minimumsSection.getString(key), permissionMinimums.get(permission)));
                    }
                }
                Map<UUID, Map<IslandPermission, Boolean>> permissionOverrides = new LinkedHashMap<>();
                ConfigurationSection permissionOverridesSection =
                        islandSection.getConfigurationSection("permission-overrides");
                if (permissionOverridesSection != null) {
                    for (String memberKey : permissionOverridesSection.getKeys(false)) {
                        UUID memberId = UUID.fromString(memberKey);
                        ConfigurationSection memberOverrides = permissionOverridesSection.getConfigurationSection(memberKey);
                        if (memberOverrides == null) {
                            continue;
                        }
                        Map<IslandPermission, Boolean> overrides = new java.util.EnumMap<>(IslandPermission.class);
                        for (String key : memberOverrides.getKeys(false)) {
                            overrides.put(IslandPermission.fromConfigKey(key), memberOverrides.getBoolean(key));
                        }
                        permissionOverrides.put(memberId, overrides);
                    }
                }
                Map<UUID, Boolean> allPermissionOverrides = new LinkedHashMap<>();
                ConfigurationSection allOverridesSection =
                        islandSection.getConfigurationSection("all-permission-overrides");
                if (allOverridesSection != null) {
                    for (String memberKey : allOverridesSection.getKeys(false)) {
                        allPermissionOverrides.put(UUID.fromString(memberKey),
                                allOverridesSection.getBoolean(memberKey));
                    }
                }
                ConfigurationSection spawnSection = islandSection.getConfigurationSection("spawn");
                IslandSpawn spawn = spawnSection == null ? null : new IslandSpawn(
                        spawnSection.getDouble("x"),
                        spawnSection.getDouble("y"),
                        spawnSection.getDouble("z"),
                        (float) spawnSection.getDouble("yaw"),
                        (float) spawnSection.getDouble("pitch"));
                IslandData island = new IslandData(
                        owner,
                        islandSection.getString("name", ""),
                        islandSection.getLong("index"),
                        islandSection.getLong("center-x"),
                        islandSection.getLong("center-z"),
                        islandSection.getBoolean("open", true),
                        islandSection.getLong("created-at", 0L),
                        members,
                        memberRanks,
                        coops,
                        readUuids(islandSection.getStringList("banned")),
                        IslandSettings.load(islandSection.getConfigurationSection("settings")),
                        permissionMinimums,
                        permissionOverrides,
                        allPermissionOverrides,
                        spawn);
                islands.put(owner, island);
                islandsByIndex.put(island.index(), island);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Invalid UUID in island data for owner " + ownerKey + ".", exception);
            }

        }

        long maximumIndex = islands.values().stream().mapToLong(island -> island.index()).max().orElse(-1);
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
