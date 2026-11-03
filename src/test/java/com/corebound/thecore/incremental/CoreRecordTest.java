package com.corebound.thecore.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void passiveEnergyDoesNotChangeManualCombo() {
        CoreRecord core = createCore(1);
        CoreRecord manualHit = core.withHit(1000, COMBO_WINDOW_MILLIS);
        CoreRecord passiveEnergy = manualHit.withPassiveEnergy(0.4);

        assertEquals(1.04, passiveEnergy.combo(2000, COMBO_WINDOW_MILLIS), 0.0001);
        assertEquals(1.04 + 0.4, passiveEnergy.energy(), 0.0001);
        assertEquals(manualHit.hits(), passiveEnergy.hits());
        assertEquals(manualHit.lastHitAt(), passiveEnergy.lastHitAt());
    }

    @Test
    void upgradesLevelAndDeductsTheExponentialCost() {
        CoreRecord levelTwoCore = createCore(2, 1000.0);

        assertEquals(550.0, levelTwoCore.upgradeCost(), 0.0001);
        CoreRecord upgraded = levelTwoCore.upgrade(levelTwoCore.upgradeCost());

        assertEquals(3, upgraded.level());
        assertEquals(450.0, upgraded.energy(), 0.0001);
        assertEquals(1.5625, upgraded.baseEnergy(), 0.0001);
    }

    @Test
    void upgradeCostAlwaysFitsWithinCurrentCapacity() {
        for (int level = 1; level <= 100; level++) {
            CoreRecord core = createCore(level);
            assertEquals(core.maxEnergy() * 0.5, core.upgradeCost(), 0.0001);
            assertTrue(core.upgradeCost() <= core.maxEnergy());
        }
    }

    @Test
    void levelFourCoreCanAffordItsNextUpgradeAtFullCapacity() {
        CoreRecord levelFourCore = createCore(4, 5324.0);

        assertEquals(2662.0, levelFourCore.upgradeCost(), 0.0001);
        CoreRecord upgraded = levelFourCore.upgrade(levelFourCore.upgradeCost());

        assertEquals(5, upgraded.level());
        assertEquals(2662.0, upgraded.energy(), 0.0001);
    }

    @Test
    void refusesUpgradeWhenEnergyIsInsufficient() {
        CoreRecord core = createCore(1, 249.99);

        assertNull(core.upgrade(core.upgradeCost()));
    }

    @Test
    void growsEnergyCapacityWithCoreLevelWithoutCriticalUpgrades() {
        CoreRecord base = createCore(1, 499.5);
        CoreRecord critical = new CoreRecord(
                base.islandIndex(),
                base.worldId(),
                base.x(),
                base.y(),
                base.z(),
                base.itemDisplayId(),
                base.interactionId(),
                base.textDisplayId(),
                base.level(),
                499.5,
                base.hits(),
                base.lastHitAt(),
                0,
                0,
                1);

        assertEquals(500.0, base.maxEnergy(), 0.0001);
        assertEquals(1100.0, createCore(2).maxEnergy(), 0.0001);
        assertEquals(500.0, critical.maxEnergy(), 0.0001);
        assertEquals(0.05, critical.criticalChance(), 0.0001);
        assertEquals(500.0, base.withHit(1000, COMBO_WINDOW_MILLIS).energy(), 0.0001);
        assertEquals(500.0, base.withPassiveEnergy(10).energy(), 0.0001);
        assertEquals(500.0, createCore(1, 700).energy(), 0.0001);
    }

    @Test
    void enhancementLevelsIncreaseComboWindowAndCapacity() {
        CoreRecord enhanced = new CoreRecord(
                1,
                UUID.randomUUID(),
                0,
                64,
                0,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                500,
                50,
                1000,
                2,
                3,
                1);

        assertEquals(4.0, enhanced.maxCombo(), 0.0001);
        assertEquals(75, enhanced.maxComboHits());
        assertEquals(4500, enhanced.comboWindowMillis(3000));
        assertEquals(500, enhanced.maxEnergy(), 0.0001);
        assertEquals(0.05, enhanced.criticalChance(), 0.0001);
        assertEquals(250.0, createCore(1).upgradeCost(), 0.0001);
    }

    @Test
    void criticalUpgradeSpendsEnergyAndPreservesItsLevel() {
        CoreRecord core = createCore(1, 500.0);
        CoreRecord enhanced = core.upgradeEnhancement(CoreEnhancement.CRITICAL, 300.0, 5);
        CoreRecord spent = enhanced.spendEnergy(100.0);

        assertEquals(200.0, enhanced.energy(), 0.0001);
        assertEquals(1, enhanced.upgradeCritical());
        assertEquals(0.05, enhanced.criticalChance(), 0.0001);
        assertEquals(500.0, enhanced.maxEnergy(), 0.0001);
        assertEquals(100.0, spent.energy(), 0.0001);
        assertEquals(1, spent.upgradeCritical());
        assertNull(enhanced.upgradeEnhancement(CoreEnhancement.CRITICAL, 200.0, 1));
    }

    @Test
    void fractureResetsEnergyAndLevelAndIncreasesPermanentProduction() {
        CoreRecord core = createCore(10, 500.0);

        CoreRecord fractured = core.fracture();

        assertEquals(1, fractured.level());
        assertEquals(0.0, fractured.energy(), 0.0001);
        assertEquals(1, fractured.prestigeCount());
        assertEquals(1, fractured.coreFragments());
        assertEquals(1.5, fractured.prestigeMultiplier(), 0.0001);
        assertEquals(0.6, fractured.withPassiveEnergy(0.4).energy(), 0.0001);
        assertNull(createCore(9).fracture());
    }

    @Test
    void astralResonanceRequiresFracturesAndFragmentsAndImprovesManualYield() {
        CoreRecord locked = createCore(1, 0, 0, 10, 0, 0);
        assertNull(locked.upgradeAstralSkill(AstralSkill.RESONANCE));

        CoreRecord eligible = createCore(1, 0, 1, 1, 0, 0);
        CoreRecord upgraded = eligible.upgradeAstralSkill(AstralSkill.RESONANCE);
        assertEquals(1, upgraded.astralResonance());
        assertEquals(0, upgraded.coreFragments());
        assertNull(upgraded.upgradeAstralSkill(AstralSkill.RESONANCE));

        CoreRecord producing = upgraded.withHit(1000, COMBO_WINDOW_MILLIS);
        assertEquals(1.716, producing.energy(), 0.0001);
        assertEquals(1.10, producing.astralResonanceMultiplier(), 0.0001);
    }

    @Test
    void astralPylonAttunementRequiresHigherFractureAndImprovesPassiveYield() {
        CoreRecord firstFracture = createCore(1, 0, 1, 5, 0, 0);
        assertNull(firstFracture.upgradeAstralSkill(AstralSkill.PYLON_ATTUNEMENT));

        CoreRecord secondFracture = createCore(1, 0, 2, 2, 0, 0);
        CoreRecord upgraded = secondFracture.upgradeAstralSkill(AstralSkill.PYLON_ATTUNEMENT);
        assertEquals(1, upgraded.astralPylonAttunement());
        assertEquals(0, upgraded.coreFragments());
        assertEquals(1.15, upgraded.astralPylonMultiplier(), 0.0001);
        assertNull(upgraded.upgradeAstralSkill(AstralSkill.PYLON_ATTUNEMENT));
    }

    @Test
    void astralSkillsSurviveRebirthAndKeepTheirRanks() {
        CoreRecord core = createCore(10, 500, 3, 5, 2, 1);

        CoreRecord reborn = core.fracture();

        assertEquals(2, reborn.astralResonance());
        assertEquals(1, reborn.astralPylonAttunement());
        assertEquals(4, reborn.prestigeCount());
        assertEquals(6, reborn.coreFragments());
    }

    @Test
    void pylonCapacityUnlocksAtLevelsThreeSevenAndTen() {
        assertEquals(0, createCore(1).pylonCapacity());
        assertEquals(1, createCore(3).pylonCapacity());
        assertEquals(1, createCore(6).pylonCapacity());
        assertEquals(2, createCore(7).pylonCapacity());
        assertEquals(2, createCore(9).pylonCapacity());
        assertEquals(3, createCore(10).pylonCapacity());
        assertEquals(3, createCore(100).pylonCapacity());
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

    private CoreRecord createCore(
            int level,
            double energy,
            int prestige,
            int fragments,
            int resonance,
            int pylonAttunement) {
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
                0,
                0,
                0,
                0,
                prestige,
                fragments,
                resonance,
                pylonAttunement);
    }
}
