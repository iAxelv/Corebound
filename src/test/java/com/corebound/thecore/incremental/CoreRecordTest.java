package com.corebound.thecore.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreRecordTest {
    private static final long COMBO_WINDOW_MILLIS = 3000;

    @Test
    void accumulatesEnergyUsingTheConsecutiveHitCombo() {
        CoreRecord core = createCore(1);

        CoreRecord firstHit = core.withHit(1000, COMBO_WINDOW_MILLIS);
        assertEquals(1.04, firstHit.combo(1000, COMBO_WINDOW_MILLIS), 0.0001);
        assertEquals(1.04, firstHit.energy(), 0.0001);

        CoreRecord fiftiethHit = firstHit;
        for (int hit = 2; hit <= 50; hit++) {
            fiftiethHit = fiftiethHit.withHit(1000 + hit * 50L, COMBO_WINDOW_MILLIS);
        }

        assertEquals(3.0, fiftiethHit.combo(fiftiethHit.lastHitAt(), COMBO_WINDOW_MILLIS), 0.0001);
        assertEquals(50, fiftiethHit.hits());
    }

    @Test
    void resetsComboAfterWindowAndUsesLevelBaseEnergy() {
        CoreRecord levelTwoCore = createCore(2);
        CoreRecord firstHit = levelTwoCore.withHit(1000, COMBO_WINDOW_MILLIS);
        CoreRecord hitAfterWindow = firstHit.withHit(4001, COMBO_WINDOW_MILLIS);

        assertEquals(1.25, hitAfterWindow.baseEnergy(), 0.0001);
        assertEquals(1.04, hitAfterWindow.combo(4001, COMBO_WINDOW_MILLIS), 0.0001);
        assertEquals(2.6, hitAfterWindow.energy(), 0.0001);
    }

    private CoreRecord createCore(int level) {
        return new CoreRecord(
                1,
                UUID.randomUUID(),
                0,
                64,
                0,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                level,
                0,
                0,
                0);
    }
}
