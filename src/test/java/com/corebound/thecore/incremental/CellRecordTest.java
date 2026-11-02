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
