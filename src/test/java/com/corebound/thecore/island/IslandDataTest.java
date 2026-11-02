package com.corebound.thecore.island;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IslandDataTest {
    @Test
    void invitedMembersAndCoopsCanBuildButBannedPlayersCannotRemainMembers() {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID coop = UUID.randomUUID();
        IslandData island = new IslandData(
                owner, "home", 0, 0, 0, true, 1L, Map.of(), Set.of(), Set.of(),
                IslandSettings.defaults());

        island = island.withMember(member, true, 2L).withCoop(coop, true);
        assertTrue(island.canBuild(member));
        assertTrue(island.canBuild(coop));

        island = island.withBanned(member, true);
        assertFalse(island.canBuild(member));
        assertTrue(island.bannedPlayers().contains(member));
        assertFalse(island.members().containsKey(member));
    }
}
