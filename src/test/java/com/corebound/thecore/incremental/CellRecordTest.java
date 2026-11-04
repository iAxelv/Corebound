package com.corebound.thecore.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CellRecordTest {
    private static final double[] CONDENSER_ORE_WEIGHTS = {
        1.0, 0.4, 0.15, 0.05, 0.02, 0.01, 0.005, 0.002, 0.0005
    };

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
        assertEquals(0.25, new CellRecord(CellType.CONDENSER_EFFICIENCY, 5).condenserEnergyReduction(), 0.0001);
        assertEquals(0.25, new CellRecord(CellType.CONDENSER_YIELD, 5).condenserOreChanceBonus(), 0.0001);
    }

    @Test
    void condenserLevelUnlocksRuneTiersIndependentlyOfCoreLevel() {
        assertEquals(1, CellRecord.maximumTierForCondenserLevel(0));
        assertEquals(1, CellRecord.maximumTierForCondenserLevel(1));
        assertEquals(2, CellRecord.maximumTierForCondenserLevel(2));
        assertEquals(5, CellRecord.maximumTierForCondenserLevel(8));
    }

    @Test
    void cropGeneratorRuneTiersAndEffectsScaleWithGeneratorLevel() {
        assertEquals(1, CellRecord.maximumTierForCropGeneratorLevel(0));
        assertEquals(1, CellRecord.maximumTierForCropGeneratorLevel(1));
        assertEquals(2, CellRecord.maximumTierForCropGeneratorLevel(2));
        assertEquals(5, CellRecord.maximumTierForCropGeneratorLevel(8));
        assertEquals(0.25, new CellRecord(CellType.CROP_ENERGY_EFFICIENCY, 5).cropEnergyReduction(), 0.0001);
        assertEquals(0.50, new CellRecord(CellType.CROP_EFFICIENCY, 5).cropHarvestBonus(), 0.0001);
    }

    @Test
    void cropGeneratorShotDelayDecreasesWithLevelAndHarvestBonusIsFractional() {
        assertEquals(6.0, CropGeneratorMath.shotDelaySeconds(0, 6.0, 0.5, 2.5), 0.0001);
        assertEquals(4.0, CropGeneratorMath.shotDelaySeconds(4, 6.0, 0.5, 2.5), 0.0001);
        assertEquals(3.5, CropGeneratorMath.shotDelaySeconds(5, 6.0, 0.5, 2.5), 0.0001);
        assertEquals(2.5, CropGeneratorMath.shotDelaySeconds(10, 6.0, 0.5, 2.5), 0.0001);
        assertEquals(2.5, CropGeneratorMath.shotDelaySeconds(12, 6.0, 0.5, 2.5), 0.0001);
        assertEquals("3.5", CropGeneratorMath.formatSeconds(3.5));
        assertEquals("3", CropGeneratorMath.formatSeconds(3.0));
        assertEquals(1, CropGeneratorMath.additionalHarvestItems(5, 0.1, 0.49));
        assertEquals(0, CropGeneratorMath.additionalHarvestItems(5, 0.1, 0.5));
        assertEquals(3, CropGeneratorMath.additionalHarvestItems(5, 0.5, 0.4));
    }

    @Test
    void condenserOutputChanceIsDistributedAcrossUnlockedOres() {
        CondenserRecord levelZero = createCondenser(0, Map.of());
        CondenserRecord levelOne = createCondenser(1, Map.of());
        CondenserRecord levelTwo = createCondenser(2, Map.of());
        CondenserRecord levelFive = createCondenser(5, Map.of());
        CondenserRecord levelNine = createCondenser(9, Map.of());
        CondenserRecord levelTwoWithYieldRune = createCondenser(
                2, Map.of(0, new CellRecord(CellType.CONDENSER_YIELD, 2)));

        double[] levelZeroChances = condenserChances(levelZero);
        double[] levelOneChances = condenserChances(levelOne);
        double[] levelTwoChances = condenserChances(levelTwo);
        double[] levelFiveChances = condenserChances(levelFive);
        double[] boostedChances = condenserChances(levelTwoWithYieldRune);

        assertEquals(100.0, levelZeroChances[0], 0.0001);
        assertEquals(90.0, levelOneChances[0], 0.0001);
        assertEquals(10.0, levelOneChances[1], 0.0001);
        assertEquals(86.0, levelTwoChances[0], 0.0001);
        assertEquals(14.0 * 0.45 / 0.85, levelTwoChances[1], 0.0001);
        assertEquals(14.0 * 0.4 / 0.85, levelTwoChances[2], 0.0001);
        assertEquals(0.0, levelOneChances[2], 0.0001);
        assertEquals(76.0, boostedChances[0], 0.0001);
        assertEquals(24.0 * 0.45 / 0.85, boostedChances[1], 0.0001);
        assertEquals(24.0 * 0.4 / 0.85, boostedChances[2], 0.0001);
        assertTrue(levelFiveChances[0] < levelOneChances[0]);
        assertTrue(levelFiveChances[1] < levelOneChances[1]);
        assertTrue(levelFiveChances[1] > levelFiveChances[5]);
        assertEquals(-1, CondenserMath.outputOreIndex(levelTwoChances, 85.99));
        assertEquals(0, CondenserMath.outputOreIndex(levelTwoChances, 86.0));
        assertEquals(1, CondenserMath.outputOreIndex(levelTwoChances, 95.0));

        double[] maximumLevelChances = condenserChances(levelNine);
        assertEquals(100.0,
                java.util.Arrays.stream(maximumLevelChances).sum(), 0.0001);
        assertTrue(maximumLevelChances[0] < levelFiveChances[0]);
        assertTrue(maximumLevelChances[9] < maximumLevelChances[1]);
    }

    @Test
    void condenserEfficiencyReducesEnergyCostLinearly() {
        CondenserRecord condenser = createCondenser(
                2, Map.of(0, new CellRecord(CellType.CONDENSER_EFFICIENCY, 2)));

        assertEquals(1.8, CondenserMath.energyCost(condenser, 1.0, 0.5), 0.0001);
    }

    @Test
    void condenserRuneSlotsUnlockAtConfiguredProgressionAndInactiveRunesDoNotApply() {
        CondenserRecord levelTwo = createCondenser(2, Map.of());
        CondenserRecord levelThree = levelTwo.withLevel(3, 2);
        CondenserRecord storedLockedRune = new CondenserRecord(
                levelTwo.islandIndex(),
                levelTwo.condenserId(),
                levelTwo.worldId(),
                levelTwo.x(),
                levelTwo.y(),
                levelTwo.z(),
                levelTwo.itemDisplayId(),
                levelTwo.interactionId(),
                levelTwo.textDisplayId(),
                levelTwo.level(),
                levelTwo.state(),
                1,
                Map.of(
                        0, new CellRecord(CellType.CONDENSER_EFFICIENCY, 1),
                        1, new CellRecord(CellType.CONDENSER_EFFICIENCY, 1)));

        assertEquals(1, levelTwo.runeSlots());
        assertEquals(2, levelThree.runeSlots());
        assertEquals(1, storedLockedRune.activeRunes().size());
        assertEquals(0.95, CondenserMath.energyCost(storedLockedRune, 1.0, 0.0), 0.0001);
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

    private CondenserRecord createCondenser(int level, Map<Integer, CellRecord> runes) {
        return new CondenserRecord(
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                64,
                0,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                level,
                CondenserState.ACTIVE,
                level >= 6 ? 3 : level >= 3 ? 2 : 1,
                runes);
    }

    private double[] condenserChances(CondenserRecord condenser) {
        return CondenserMath.outputChances(condenser, 0.10, 0.04, 0.75, 0.45, CONDENSER_ORE_WEIGHTS);
    }
}
