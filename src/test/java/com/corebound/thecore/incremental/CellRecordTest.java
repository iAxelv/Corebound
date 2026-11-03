package com.corebound.thecore.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.UUID;

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
    void condenserOutputChanceIsDistributedAcrossUnlockedOres() {
        CondenserRecord levelZero = createCondenser(0, Map.of());
        CondenserRecord levelOne = createCondenser(1, Map.of());
        CondenserRecord levelTwo = createCondenser(2, Map.of());
        CondenserRecord levelNine = createCondenser(9, Map.of());
        CondenserRecord levelTwoWithYieldRune = createCondenser(
                2, Map.of(0, new CellRecord(CellType.CONDENSER_YIELD, 2)));

        double[] levelZeroChances = CondenserMath.outputChances(levelZero, 0.10);
        double[] levelOneChances = CondenserMath.outputChances(levelOne, 0.10);
        double[] levelTwoChances = CondenserMath.outputChances(levelTwo, 0.10);
        double[] boostedChances = CondenserMath.outputChances(levelTwoWithYieldRune, 0.10);

        assertEquals(100.0, levelZeroChances[0], 0.0001);
        assertEquals(90.0, levelOneChances[0], 0.0001);
        assertEquals(10.0, levelOneChances[1], 0.0001);
        assertEquals(5.0, levelTwoChances[1], 0.0001);
        assertEquals(5.0, levelTwoChances[2], 0.0001);
        assertEquals(0.0, levelOneChances[2], 0.0001);
        assertEquals(80.0, boostedChances[0], 0.0001);
        assertEquals(10.0, boostedChances[1], 0.0001);
        assertEquals(10.0, boostedChances[2], 0.0001);

        double[] maximumLevelChances = CondenserMath.outputChances(levelNine, 0.10);
        assertEquals(100.0,
                java.util.Arrays.stream(maximumLevelChances).sum(), 0.0001);
        assertEquals(0.10 * 100.0 / 9,
                maximumLevelChances[9], 0.0001);
    }

    @Test
    void condenserEfficiencyReducesEnergyCostLinearly() {
        CondenserRecord condenser = createCondenser(
                2, Map.of(0, new CellRecord(CellType.CONDENSER_EFFICIENCY, 2)));

        assertEquals(1.8, CondenserMath.energyCost(condenser, 1.0, 0.5), 0.0001);
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
                3,
                runes);
    }
}
