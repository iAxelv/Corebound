package com.corebound.thecore.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BatteryRecordTest {
    @Test
    void capacityAndOfflineRunesApplyTierBonusesAndSurviveTierChanges() {
        CoreRecord core = new CoreRecord(
                1, UUID.randomUUID(), 0, 0, 0, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        BatteryRecord battery = new BatteryRecord(
                1, UUID.randomUUID(), UUID.randomUUID(), 0, 0, 0,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                2, 100, Map.of(
                        0, new CellRecord(CellType.BATTERY_CAPACITY, 2),
                        1, new CellRecord(CellType.BATTERY_OFFLINE, 3)),
                0, 0);

        assertEquals(core.maxEnergy() * 0.6, battery.capacity(core), 0.0001);
        assertEquals(1.3, battery.offlineMultiplier(), 0.0001);
        BatteryRecord tierOne = battery.withTier(1);
        assertEquals(1, tierOne.tier());
        assertEquals(Map.of(0, new CellRecord(CellType.BATTERY_CAPACITY, 2)), tierOne.runes());
    }

    @Test
    void offlineCheckpointCanAdvanceWithoutResettingAccumulatedTime() {
        BatteryRecord battery = new BatteryRecord(
                1, UUID.randomUUID(), UUID.randomUUID(), 0, 0, 0,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, 100, Map.of(), 3_600_000, 1_000);

        BatteryRecord checkpointed = battery.withOfflineProgress(
                battery.energy(), battery.offlineMillis(), 61_000);

        assertEquals(100, checkpointed.energy());
        assertEquals(3_600_000, checkpointed.offlineMillis());
        assertEquals(61_000, checkpointed.lastOfflineCheck());
    }
}
