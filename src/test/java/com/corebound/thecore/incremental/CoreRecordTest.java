package com.corebound.thecore.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        CoreRecord cappedHit = fiftiethHit.withHit(fiftiethHit.lastHitAt() + 50, COMBO_WINDOW_MILLIS);
        assertEquals(50, cappedHit.hits());
        assertEquals(3.0, cappedHit.combo(cappedHit.lastHitAt(), COMBO_WINDOW_MILLIS), 0.0001);
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

    @Test
    void passiveHitAddsConfiguredFractionAndKeepsComboAlive() {
        CoreRecord core = createCore(1);
        CoreRecord manualHit = core.withHit(1000, COMBO_WINDOW_MILLIS);
        CoreRecord passiveHit = manualHit.withHit(2000, COMBO_WINDOW_MILLIS, 0.4);

        assertEquals(1.08, passiveHit.combo(2000, COMBO_WINDOW_MILLIS), 0.0001);
        assertEquals(1.04 + 0.432, passiveHit.energy(), 0.0001);
        assertEquals(2, passiveHit.hits());
    }

    @Test
    void upgradesLevelAndDeductsTheExponentialCost() {
        CoreRecord levelTwoCore = createCore(2, 300.0);

        assertEquals(200.0, levelTwoCore.upgradeCost(), 0.0001);
        CoreRecord upgraded = levelTwoCore.upgrade(levelTwoCore.upgradeCost());

        assertEquals(3, upgraded.level());
        assertEquals(100.0, upgraded.energy(), 0.0001);
        assertEquals(1.5625, upgraded.baseEnergy(), 0.0001);
    }

    @Test
    void refusesUpgradeWhenEnergyIsInsufficient() {
        CoreRecord core = createCore(1, 99.99);

        assertNull(core.upgrade(core.upgradeCost()));
    }

    @Test
    void pylonCapacityGrowsByOnePerCoreLevelAfterUnlock() {
        assertEquals(0, createCore(1).pylonCapacity());
        assertEquals(1, createCore(2).pylonCapacity());
        assertEquals(4, createCore(5).pylonCapacity());
    }

    private CoreRecord createCore(int level) {
        return createCore(level, 0);
    }

    private CoreRecord createCore(int level, double energy) {
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
                energy,
                0,
                0);
    }
}
