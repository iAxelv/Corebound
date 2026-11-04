package com.corebound.thecore.incremental;

import java.util.Locale;

public final class CropGeneratorMath {
    private CropGeneratorMath() {
    }

    public static double shotDelaySeconds(
            int level, double baseDelaySeconds, double reductionPerLevelSeconds, double minimumDelaySeconds) {
        return Math.max(minimumDelaySeconds, baseDelaySeconds - level * reductionPerLevelSeconds);
    }

    public static String formatSeconds(double seconds) {
        String formatted = String.format(Locale.ROOT, "%.1f", seconds);
        return formatted.endsWith(".0") ? formatted.substring(0, formatted.length() - 2) : formatted;
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
