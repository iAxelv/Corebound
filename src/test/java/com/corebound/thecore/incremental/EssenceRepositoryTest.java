package com.corebound.thecore.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EssenceRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void balancesPersistAndAccumulateExactFractionalValues() throws Exception {
        UUID playerId = UUID.randomUUID();
        Path file = temporaryDirectory.resolve("essences.yml");
        EssenceRepository repository = new EssenceRepository(file.toFile());
        repository.deposit(playerId, new BigDecimal("1.2"), new BigDecimal("0.25"));
        repository.deposit(playerId, new BigDecimal("0.3"), new BigDecimal("0.7"));

        EssenceRepository.Balance balance = new EssenceRepository(file.toFile()).balance(playerId);

        assertEquals(new BigDecimal("1.5"), balance.mineral());
        assertEquals(new BigDecimal("0.95"), balance.vegetal());
    }

}
