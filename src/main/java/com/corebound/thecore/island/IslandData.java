package com.corebound.thecore.island;

import java.util.HashSet;
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
        Set<UUID> coops,
        Set<UUID> bannedPlayers,
        IslandSettings settings) {

    public IslandData {
        members = Map.copyOf(members);
        coops = Set.copyOf(coops);
        bannedPlayers = Set.copyOf(bannedPlayers);
    }

    public boolean canBuild(UUID playerId) {
        return owner.equals(playerId) || members.containsKey(playerId) || coops.contains(playerId);
    }

    public IslandData withMember(UUID playerId, boolean member, long joinedAt) {
        Map<UUID, Long> updatedMembers = new java.util.HashMap<>(members);
        if (member) {
            updatedMembers.put(playerId, joinedAt);
        } else {
            updatedMembers.remove(playerId);
        }
        return copy(updatedMembers, coops, bannedPlayers, settings, open);
    }

    public IslandData withCoop(UUID playerId, boolean coop) {
        Set<UUID> updatedCoops = new HashSet<>(coops);
        if (coop) {
            updatedCoops.add(playerId);
        } else {
            updatedCoops.remove(playerId);
        }
        return copy(members, updatedCoops, bannedPlayers, settings, open);
    }

    public IslandData withBanned(UUID playerId, boolean banned) {
        Set<UUID> updatedBans = new HashSet<>(bannedPlayers);
        if (banned) {
            updatedBans.add(playerId);
        } else {
            updatedBans.remove(playerId);
        }
        Map<UUID, Long> updatedMembers = new java.util.HashMap<>(members);
        updatedMembers.remove(playerId);
        Set<UUID> updatedCoops = new HashSet<>(coops);
        updatedCoops.remove(playerId);
        return copy(updatedMembers, updatedCoops, updatedBans, settings, open);
    }

    public IslandData withSettings(IslandSettings settings) {
        return copy(members, coops, bannedPlayers, settings, open);
    }

    public IslandData withOpen(boolean open) {
        return copy(members, coops, bannedPlayers, settings, open);
    }

    private IslandData copy(
            Map<UUID, Long> members,
            Set<UUID> coops,
            Set<UUID> bannedPlayers,
            IslandSettings settings,
            boolean open) {
        return new IslandData(owner, name, index, centerX, centerZ, open, createdAt,
                members, coops, bannedPlayers, settings);
    }
}
