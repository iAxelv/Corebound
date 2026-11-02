package com.corebound.thecore.island;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IslandSettingsTest {
    @Test
    void defaultsKeepPvpAndExplosionsDisabled() {
        IslandSettings settings = IslandSettings.defaults();

        assertFalse(settings.pvp());
        assertFalse(settings.explosions());
        assertTrue(settings.damage());
        assertTrue(settings.waterFlow());
        assertTrue(settings.lavaFlow());
    }

    @Test
    void enablingOneFixedTimeDisablesTheOtherFixedTimes() {
        IslandSettings settings = IslandSettings.defaults()
                .toggle("alwaysDay")
                .toggle("alwaysNoon");

        assertFalse(settings.alwaysDay());
        assertTrue(settings.alwaysNoon());
    }

    @Test
    void alwaysRainAndAlwaysSunnyAreMutuallyExclusive() {
        IslandSettings settings = IslandSettings.defaults()
                .toggle("alwaysRainy")
                .toggle("alwaysSunny");

        assertFalse(settings.alwaysRainy());
        assertTrue(settings.alwaysSunny());
        assertFalse(settings.rainCycle());
    }
}
