package com.corebound.thecore.incremental;

public final class CropGeneratorMath {
    private CropGeneratorMath() {
    }

    public static int additionalGrowthStages(
            int level, double baseStages, double stagesPerLevel, double roll) {
        double expectedStages = Math.max(0.0, baseStages + level * stagesPerLevel);
        if (expectedStages >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        int wholeStages = (int) Math.floor(expectedStages);
        return wholeStages + (roll < expectedStages - wholeStages ? 1 : 0);
    }

    public static int additionalHarvestItems(int amount, double bonusRatio, double roll) {
        double expectedItems = Math.max(0.0, amount * bonusRatio);
        if (expectedItems >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        int wholeItems = (int) Math.floor(expectedItems);
        return wholeItems + (roll < expectedItems - wholeItems ? 1 : 0);
    }
}
