package com.corebound.thecore.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IslandDataTest {
    @Test
    void islandGridIndexesInvertGeneratedCoordinates() {
        for (long index = 0; index < 10_000; index++) {
            IslandCoordinates coordinates = IslandGrid.coordinates(index, 3000);
            assertEquals(index, IslandGrid.indexAt(
                    coordinates.x() / 3000, coordinates.z() / 3000));
        }
    }

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

    @Test
    void permissionMinimumsAreInheritedAndIndividualOverridesTakePrecedence() {
        UUID owner = UUID.randomUUID();
        UUID moderator = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        IslandData island = new IslandData(
                owner, "home", 0, 0, 0, true, 1L, Map.of(), Set.of(), Set.of(),
                IslandSettings.defaults())
                .withMember(moderator, true, 2L)
                .withRank(moderator, IslandRank.MODERATOR)
                .withMember(member, true, 3L);

        assertTrue(island.hasPermission(moderator, IslandPermission.INVITE));
        assertFalse(island.hasPermission(member, IslandPermission.INVITE));
        assertTrue(island.hasPermission(member, IslandPermission.OPEN_CLOSE));

        island = island.withPermissionOverride(member, IslandPermission.INVITE, true);
        assertTrue(island.hasPermission(member, IslandPermission.INVITE));
        island = island.withPermissionOverride(member, IslandPermission.OPEN_CLOSE, false);
        assertFalse(island.hasPermission(member, IslandPermission.OPEN_CLOSE));

        island = island.withAllPermissions(member, true);
        assertTrue(island.hasPermission(member, IslandPermission.DELETE));
        island = island.withAllPermissions(member, false);
        assertFalse(island.hasPermission(member, IslandPermission.DELETE));
    }

    @Test
    void transferringOwnershipMakesTheFormerOwnerAnAdministrator() {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        IslandData island = new IslandData(
                owner, "home", 0, 0, 0, true, 1L, Map.of(), Set.of(), Set.of(),
                IslandSettings.defaults())
                .withMember(member, true, 2L);

        IslandData transferred = island.withOwner(member, 3L);

        assertTrue(transferred.owner().equals(member));
        assertTrue(transferred.rank(member) == IslandRank.OWNER);
        assertTrue(transferred.members().containsKey(owner));
        assertTrue(transferred.rank(owner) == IslandRank.ADMINISTRATOR);
    }
}
