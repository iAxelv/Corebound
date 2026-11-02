package com.corebound.thecore.island;

import java.util.Set;
import java.util.UUID;
import java.util.HashSet;

public record IslandData(
        UUID owner,
        String name,
        long index,
        long centerX,
        long centerZ,
        boolean open,
        Set<UUID> trustedMembers) {

    public IslandData {
        trustedMembers = Set.copyOf(trustedMembers);
    }

    public boolean canBuild(UUID playerId) {
        return owner.equals(playerId) || trustedMembers.contains(playerId);
    }

    public IslandData withTrustedMember(UUID playerId, boolean trusted) {
        HashSet<UUID> members = new HashSet<>(trustedMembers);
        if (trusted) {
            members.add(playerId);
        } else {
            members.remove(playerId);
        }
        return new IslandData(owner, name, index, centerX, centerZ, open, members);
    }

    public IslandData withOpen(boolean open) {
        return new IslandData(owner, name, index, centerX, centerZ, open, trustedMembers);
    }
}
