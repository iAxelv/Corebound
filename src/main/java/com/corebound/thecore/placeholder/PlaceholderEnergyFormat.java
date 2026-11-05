package com.corebound.thecore.placeholder;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

final class PlaceholderEnergyFormat {
    private PlaceholderEnergyFormat() {
    }

    static String format(double energy) {
        double absolute = Math.abs(energy);
        if (absolute >= 1_000_000_000_000.0) {
            return formatScaled(energy / 1_000_000_000_000.0) + "T";
        }
        if (absolute >= 1_000_000_000.0) {
            return formatScaled(energy / 1_000_000_000.0) + "B";
        }
        if (absolute >= 1_000_000.0) {
            return formatScaled(energy / 1_000_000.0) + "M";
        }
        if (absolute >= 1_000.0) {
            return formatScaled(energy / 1_000.0) + "K";
        }
        return formatScaled(energy);
    }

    private static String formatScaled(double value) {
        DecimalFormat format =
                new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("es")));
        format.setGroupingUsed(false);
        return format.format(value);
    }
}
