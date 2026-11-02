package com.corebound.thecore.island;

import java.util.HashSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record IslandData(
        UUID owner,
        String name,
        long index,
        long centerX,
        long centerZ,
        boolean open,
        long createdAt,
        Map<UUID, Long> members,
        Map<UUID, IslandRank> memberRanks,
        Set<UUID> coops,
        Set<UUID> bannedPlayers,
        IslandSettings settings,
        Map<IslandPermission, IslandRank> permissionMinimums,
        Map<UUID, Map<IslandPermission, Boolean>> permissionOverrides,
        Map<UUID, Boolean> allPermissionOverrides,
        IslandSpawn spawn) {

    public IslandData {
        members = Map.copyOf(members);
        Map<UUID, IslandRank> normalizedRanks = new HashMap<>();
        for (UUID member : members.keySet()) {
            IslandRank rank = memberRanks.getOrDefault(member, IslandRank.MEMBER);
            normalizedRanks.put(member, rank == IslandRank.OWNER ? IslandRank.MEMBER : rank);
        }
        memberRanks = Map.copyOf(normalizedRanks);
        coops = Set.copyOf(coops);
        bannedPlayers = Set.copyOf(bannedPlayers);
        Map<IslandPermission, IslandRank> normalizedMinimums = new EnumMap<>(IslandPermission.class);
        for (IslandPermission permission : IslandPermission.values()) {
            IslandRank minimum = permissionMinimums.getOrDefault(permission, IslandRank.MEMBER);
            normalizedMinimums.put(permission,
                    minimum == IslandRank.OWNER ? IslandRank.ADMINISTRATOR : minimum);
        }
        permissionMinimums = Map.copyOf(normalizedMinimums);
        Map<UUID, Map<IslandPermission, Boolean>> immutableOverrides = new HashMap<>();
        for (Map.Entry<UUID, Map<IslandPermission, Boolean>> entry : permissionOverrides.entrySet()) {
            if (members.containsKey(entry.getKey())) {
                immutableOverrides.put(entry.getKey(), Map.copyOf(entry.getValue()));
            }
        }
        permissionOverrides = Map.copyOf(immutableOverrides);
        Map<UUID, Boolean> immutableAllOverrides = new HashMap<>();
        for (Map.Entry<UUID, Boolean> entry : allPermissionOverrides.entrySet()) {
            if (members.containsKey(entry.getKey())) {
                immutableAllOverrides.put(entry.getKey(), entry.getValue());
            }
        }
        allPermissionOverrides = Map.copyOf(immutableAllOverrides);
    }

    public IslandData(
            UUID owner,
            String name,
            long index,
            long centerX,
            long centerZ,
            boolean open,
            long createdAt,
            Map<UUID, Long> members,
            Set<UUID> coops,
            Set<UUID> bannedPlayers,
            IslandSettings settings) {
        this(owner, name, index, centerX, centerZ, open, createdAt, members, defaultMemberRanks(members),
                coops, bannedPlayers, settings, IslandPermissions.defaults(), Map.of(), Map.of(), null);
    }

    public boolean canBuild(UUID playerId) {
        return owner.equals(playerId) || members.containsKey(playerId) || coops.contains(playerId);
    }

    public IslandRank rank(UUID playerId) {
        if (owner.equals(playerId)) {
            return IslandRank.OWNER;
        }
        return memberRanks.getOrDefault(playerId, IslandRank.MEMBER);
    }

    public boolean hasPermission(UUID playerId, IslandPermission permission) {
        IslandRank rank = rank(playerId);
        if (rank == IslandRank.OWNER || rank == IslandRank.ADMINISTRATOR) {
            return true;
        }
        Boolean individual = permissionOverrides.getOrDefault(playerId, Map.of()).get(permission);
        if (individual != null) {
            return individual;
        }
        Boolean all = allPermissionOverrides.get(playerId);
        if (all != null) {
            return all;
        }
        IslandRank minimum = permissionMinimums.getOrDefault(permission, IslandRank.MEMBER);
        return rank.level() >= minimum.level();
    }

    public boolean hasAllPermissions(UUID playerId) {
        return allPermissionOverrides.getOrDefault(playerId, false);
    }

    public IslandData withMember(UUID playerId, boolean member, long joinedAt) {
        Map<UUID, Long> updatedMembers = new HashMap<>(members);
        Map<UUID, IslandRank> updatedRanks = new HashMap<>(memberRanks);
        Map<UUID, Map<IslandPermission, Boolean>> updatedOverrides = mutableOverrides();
        Map<UUID, Boolean> updatedAllOverrides = new HashMap<>(allPermissionOverrides);
        if (member) {
            updatedMembers.put(playerId, joinedAt);
            updatedRanks.putIfAbsent(playerId, IslandRank.MEMBER);
        } else {
            updatedMembers.remove(playerId);
            updatedRanks.remove(playerId);
            updatedOverrides.remove(playerId);
            updatedAllOverrides.remove(playerId);
        }
        return copy(owner, name, open, updatedMembers, updatedRanks, coops, bannedPlayers, settings,
                permissionMinimums, updatedOverrides, updatedAllOverrides, spawn);
    }

    public IslandData withRank(UUID playerId, IslandRank rank) {
        if (owner.equals(playerId) || !members.containsKey(playerId) || rank == IslandRank.OWNER) {
            throw new IllegalArgumentException("Only a non-owner island member can receive a non-owner rank.");
        }
        Map<UUID, IslandRank> updatedRanks = new HashMap<>(memberRanks);
        updatedRanks.put(playerId, rank);
        return copy(owner, name, open, members, updatedRanks, coops, bannedPlayers, settings,
                permissionMinimums, permissionOverrides, allPermissionOverrides, spawn);
    }

    public IslandData withCoop(UUID playerId, boolean coop) {
        Set<UUID> updatedCoops = new HashSet<>(coops);
        if (coop) {
            updatedCoops.add(playerId);
        } else {
            updatedCoops.remove(playerId);
        }
        return copy(owner, name, open, members, memberRanks, updatedCoops, bannedPlayers, settings,
                permissionMinimums, permissionOverrides, allPermissionOverrides, spawn);
    }

    public IslandData withBanned(UUID playerId, boolean banned) {
        Set<UUID> updatedBans = new HashSet<>(bannedPlayers);
        if (banned) {
            updatedBans.add(playerId);
        } else {
            updatedBans.remove(playerId);
        }
        Map<UUID, Long> updatedMembers = new HashMap<>(members);
        updatedMembers.remove(playerId);
        Map<UUID, IslandRank> updatedRanks = new HashMap<>(memberRanks);
        updatedRanks.remove(playerId);
        Set<UUID> updatedCoops = new HashSet<>(coops);
        updatedCoops.remove(playerId);
        Map<UUID, Map<IslandPermission, Boolean>> updatedOverrides = mutableOverrides();
        updatedOverrides.remove(playerId);
        Map<UUID, Boolean> updatedAllOverrides = new HashMap<>(allPermissionOverrides);
        updatedAllOverrides.remove(playerId);
        return copy(owner, name, open, updatedMembers, updatedRanks, updatedCoops, updatedBans, settings,
                permissionMinimums, updatedOverrides, updatedAllOverrides, spawn);
    }

    public IslandData withSettings(IslandSettings settings) {
        return copy(owner, name, open, members, memberRanks, coops, bannedPlayers, settings,
                permissionMinimums, permissionOverrides, allPermissionOverrides, spawn);
    }

    public IslandData withOpen(boolean open) {
        return copy(owner, name, open, members, memberRanks, coops, bannedPlayers, settings,
                permissionMinimums, permissionOverrides, allPermissionOverrides, spawn);
    }

    public IslandData withPermissionMinimum(IslandPermission permission, IslandRank minimumRank) {
        if (minimumRank == IslandRank.OWNER) {
            throw new IllegalArgumentException("Island permissions cannot require owner rank.");
        }
        Map<IslandPermission, IslandRank> updatedMinimums = new EnumMap<>(IslandPermission.class);
        updatedMinimums.putAll(permissionMinimums);
        updatedMinimums.put(permission, minimumRank);
        return copy(owner, name, open, members, memberRanks, coops, bannedPlayers, settings,
                updatedMinimums, permissionOverrides, allPermissionOverrides, spawn);
    }

    public IslandData withPermissionOverride(UUID playerId, IslandPermission permission, boolean enabled) {
        Map<UUID, Map<IslandPermission, Boolean>> updatedOverrides = mutableOverrides();
        Map<IslandPermission, Boolean> playerOverrides = new EnumMap<>(IslandPermission.class);
        playerOverrides.putAll(updatedOverrides.getOrDefault(playerId, Map.of()));
        playerOverrides.put(permission, enabled);
        updatedOverrides.put(playerId, playerOverrides);
        return copy(owner, name, open, members, memberRanks, coops, bannedPlayers, settings,
                permissionMinimums, updatedOverrides, allPermissionOverrides, spawn);
    }

    public IslandData withAllPermissions(UUID playerId, boolean enabled) {
        Map<UUID, Boolean> updated = new HashMap<>(allPermissionOverrides);
        updated.put(playerId, enabled);
        return copy(owner, name, open, members, memberRanks, coops, bannedPlayers, settings,
                permissionMinimums, permissionOverrides, updated, spawn);
    }

    public IslandData withName(String name) {
        return copy(owner, name, open, members, memberRanks, coops, bannedPlayers, settings,
                permissionMinimums, permissionOverrides, allPermissionOverrides, spawn);
    }

    public IslandData withSpawn(IslandSpawn spawn) {
        return copy(owner, name, open, members, memberRanks, coops, bannedPlayers, settings,
                permissionMinimums, permissionOverrides, allPermissionOverrides, spawn);
    }

    public IslandData withOwner(UUID newOwner, long joinedAt) {
        if (!members.containsKey(newOwner)) {
            throw new IllegalArgumentException("The new island owner must be an existing member.");
        }
        Map<UUID, Long> updatedMembers = new HashMap<>(members);
        updatedMembers.remove(newOwner);
        updatedMembers.put(owner, joinedAt);
        Map<UUID, IslandRank> updatedRanks = new HashMap<>(memberRanks);
        updatedRanks.remove(newOwner);
        updatedRanks.put(owner, IslandRank.ADMINISTRATOR);
        return copy(newOwner, name, open, updatedMembers, updatedRanks, coops, bannedPlayers, settings,
                permissionMinimums, permissionOverrides, allPermissionOverrides, spawn);
    }

    private Map<UUID, Map<IslandPermission, Boolean>> mutableOverrides() {
        Map<UUID, Map<IslandPermission, Boolean>> mutable = new HashMap<>();
        permissionOverrides.forEach((player, values) -> {
            Map<IslandPermission, Boolean> playerOverrides = new EnumMap<>(IslandPermission.class);
            playerOverrides.putAll(values);
            mutable.put(player, playerOverrides);
        });
        return mutable;
    }

    private IslandData copy(
            UUID owner,
            String name,
            boolean open,
            Map<UUID, Long> members,
            Map<UUID, IslandRank> memberRanks,
            Set<UUID> coops,
            Set<UUID> bannedPlayers,
            IslandSettings settings,
            Map<IslandPermission, IslandRank> permissionMinimums,
            Map<UUID, Map<IslandPermission, Boolean>> permissionOverrides,
            Map<UUID, Boolean> allPermissionOverrides,
            IslandSpawn spawn) {
        return new IslandData(owner, name, index, centerX, centerZ, open, createdAt,
                members, memberRanks, coops, bannedPlayers, settings,
                permissionMinimums, permissionOverrides, allPermissionOverrides, spawn);
    }

    private static Map<UUID, IslandRank> defaultMemberRanks(Map<UUID, Long> members) {
        Map<UUID, IslandRank> ranks = new HashMap<>();
        members.keySet().forEach(member -> ranks.put(member, IslandRank.MEMBER));
        return ranks;
    }
}
