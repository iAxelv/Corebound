package com.corebound.thecore.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlaceholderEnergyFormatTest {
    @Test
    void formatsCompactEnergyWithSpanishDecimalSeparator() {
        assertEquals("999,5", PlaceholderEnergyFormat.format(999.5));
        assertEquals("1K", PlaceholderEnergyFormat.format(1_000));
        assertEquals("1,2K", PlaceholderEnergyFormat.format(1_200));
        assertEquals("1M", PlaceholderEnergyFormat.format(1_000_000));
    }
}
