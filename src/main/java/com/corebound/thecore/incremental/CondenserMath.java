package com.corebound.thecore.incremental;

final class CondenserMath {
    private static final int MAX_ORE_LEVEL = 9;

    private CondenserMath() {}

    static double energyCost(CondenserRecord condenser, double baseCost, double costPerLevel) {
        double reduction = condenser.runes().values().stream()
                .mapToDouble(CellRecord::condenserEnergyReduction)
                .sum();
        return (baseCost + costPerLevel * condenser.level()) * Math.max(0.25, 1.0 - reduction);
    }

    static double[] outputChances(CondenserRecord condenser, double baseOreChance) {
        double[] chances = new double[MAX_ORE_LEVEL + 1];
        int unlockedOreCount = Math.min(condenser.level(), MAX_ORE_LEVEL);
        if (unlockedOreCount == 0) {
            chances[0] = 100.0;
            return chances;
        }

        double runeBonus = condenser.runes().values().stream()
                .mapToDouble(CellRecord::condenserOreChanceBonus)
                .sum();
        double totalOreChance = Math.min(0.95, baseOreChance + runeBonus);
        chances[0] = (1.0 - totalOreChance) * 100.0;
        double chancePerOre = totalOreChance * 100.0 / unlockedOreCount;
        for (int oreIndex = 1; oreIndex <= unlockedOreCount; oreIndex++) {
            chances[oreIndex] = chancePerOre;
        }
        return chances;
    }
}
