package com.corebound.thecore.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CellRecordTest {
    @Test
    void fusesMatchingCellsAndCapsAtTierFive() {
        CellRecord tierOne = new CellRecord(CellType.FIRE, 1);
        CellRecord tierTwo = tierOne.fuse(tierOne);

        assertEquals(new CellRecord(CellType.FIRE, 2), tierTwo);
        assertNull(tierOne.fuse(new CellRecord(CellType.VOID, 1)));
        assertNull(tierOne.fuse(new CellRecord(CellType.FIRE, 2)));
        assertNull(new CellRecord(CellType.FIRE, CellRecord.MAX_TIER)
                .fuse(new CellRecord(CellType.FIRE, CellRecord.MAX_TIER)));
        assertNull(tierOne.fuse(tierOne, 1));
        assertEquals(new CellRecord(CellType.FIRE, 2), tierOne.fuse(tierOne, 2));
    }

    @Test
    void coreLevelUnlocksRuneTiersEveryFiveLevels() {
        assertEquals(1, CellRecord.maximumTierForCoreLevel(3));
        assertEquals(1, CellRecord.maximumTierForCoreLevel(9));
        assertEquals(2, CellRecord.maximumTierForCoreLevel(10));
        assertEquals(3, CellRecord.maximumTierForCoreLevel(15));
        assertEquals(4, CellRecord.maximumTierForCoreLevel(20));
        assertEquals(5, CellRecord.maximumTierForCoreLevel(25));
    }

    @Test
    void runeEffectsScaleLinearlyByTier() {
        assertEquals(100, new CellRecord(CellType.FIRE, 5).fireIntervalReductionTicks());
        assertEquals(0.5, new CellRecord(CellType.LIGHTNING, 5).lightningBonusPerLink(), 0.0001);
        assertEquals(0.25, new CellRecord(CellType.VOID, 5).voidCriticalChance(), 0.0001);
    }

    @Test
    void pylonTierControlsCellSlotCapacity() {
        PylonRecord tierOne = createPylon(1);
        PylonRecord tierTwo = createPylon(2);
        PylonRecord tierThree = createPylon(3);

        assertEquals(1, tierOne.maxSlots());
        assertEquals(2, tierTwo.maxSlots());
        assertEquals(3, tierThree.maxSlots());
    }

    private PylonRecord createPylon(int tier) {
        return new PylonRecord(
                1,
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                0,
                64,
                0,
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                tier,
                java.util.Map.of());
    }
}
