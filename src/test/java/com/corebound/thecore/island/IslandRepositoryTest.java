package com.corebound.thecore.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IslandRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesRanksPermissionOverridesAndSpawn() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        Path file = temporaryDirectory.resolve("islands.yml");
        IslandRepository repository = new IslandRepository(file.toFile());
        long index = repository.reserveNextIndex();
        IslandData island = new IslandData(
                owner, "home", index, 100, -100, true, 1L, Map.of(), Set.of(), Set.of(),
                IslandSettings.defaults())
                .withMember(member, true, 2L)
                .withRank(member, IslandRank.MODERATOR)
                .withPermissionMinimum(IslandPermission.INVITE, IslandRank.MEMBER)
                .withPermissionOverride(member, IslandPermission.DELETE, true)
                .withAllPermissions(member, false)
                .withSpawn(new IslandSpawn(101.25, 73, -99.5, 65.0f, -12.0f));
        repository.add(island);

        IslandData loaded = new IslandRepository(file.toFile()).byOwner(owner).orElseThrow();

        assertEquals(IslandRank.MODERATOR, loaded.rank(member));
        assertTrue(loaded.hasPermission(member, IslandPermission.DELETE));
        assertFalse(loaded.hasPermission(member, IslandPermission.INVITE));
        assertEquals(new IslandSpawn(101.25, 73, -99.5, 65.0f, -12.0f), loaded.spawn());
    }

    @Test
    void ownershipTransferChangesRepositoryOwnerKeyAtomically() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        Path file = temporaryDirectory.resolve("transfer.yml");
        IslandRepository repository = new IslandRepository(file.toFile());
        long index = repository.reserveNextIndex();
        IslandData island = new IslandData(
                owner, "home", index, 0, 0, true, 1L, Map.of(), Set.of(), Set.of(),
                IslandSettings.defaults())
                .withMember(member, true, 2L);
        repository.add(island);

        repository.transferOwnership(owner, island.withOwner(member, 3L));

        assertTrue(repository.byOwner(owner).isEmpty());
        assertEquals(IslandRank.OWNER, repository.byOwner(member).orElseThrow().rank(member));
        assertEquals(IslandRank.ADMINISTRATOR, repository.byOwner(member).orElseThrow().rank(owner));
    }
}
