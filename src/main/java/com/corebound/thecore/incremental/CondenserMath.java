package com.corebound.thecore.incremental;

final class CondenserMath {
    private static final int MAX_ORE_LEVEL = 9;

    private CondenserMath() {}

    static double energyCost(CondenserRecord condenser, double baseCost, double costPerLevel) {
        double reduction = condenser.activeRunes().values().stream()
                .mapToDouble(CellRecord::condenserEnergyReduction)
                .sum();
        return (baseCost + costPerLevel * condenser.level()) * Math.max(0.25, 1.0 - reduction);
    }

    static double[] outputChances(
            CondenserRecord condenser,
            double baseOreChance,
            double oreChanceIncreasePerLevel,
            double maximumOreChance,
            double olderOreWeightDecay,
            double[] oreWeights) {
        double[] chances = new double[MAX_ORE_LEVEL + 1];
        int unlockedOreCount = Math.min(condenser.level(), MAX_ORE_LEVEL);
        if (unlockedOreCount == 0) {
            chances[0] = 100.0;
            return chances;
        }

        double runeBonus = condenser.activeRunes().values().stream()
                .mapToDouble(CellRecord::condenserOreChanceBonus)
                .sum();
        double totalOreChance = Math.min(
                maximumOreChance,
                baseOreChance + oreChanceIncreasePerLevel * (condenser.level() - 1) + runeBonus);
        double totalOreWeight = 0.0;
        double[] unlockedWeights = new double[unlockedOreCount];
        for (int oreIndex = 0; oreIndex < unlockedOreCount; oreIndex++) {
            double weight = oreWeights[oreIndex]
                    * Math.pow(olderOreWeightDecay, condenser.level() - oreIndex - 1);
            unlockedWeights[oreIndex] = weight;
            totalOreWeight += weight;
        }
        chances[0] = (1.0 - totalOreChance) * 100.0;
        for (int oreIndex = 0; oreIndex < unlockedOreCount; oreIndex++) {
            chances[oreIndex + 1] = totalOreChance * 100.0 * unlockedWeights[oreIndex] / totalOreWeight;
        }
        return chances;
    }

    static int outputOreIndex(double[] chances, double roll) {
        double cumulativeChance = chances[0];
        if (roll < cumulativeChance) {
            return -1;
        }
        for (int index = 1; index < chances.length; index++) {
            cumulativeChance += chances[index];
            if (roll < cumulativeChance) {
                return index - 1;
            }
        }
        return -1;
    }
}
