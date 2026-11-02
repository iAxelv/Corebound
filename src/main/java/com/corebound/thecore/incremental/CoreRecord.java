package com.corebound.thecore.incremental;

import java.util.UUID;

public record CoreRecord(
        long islandIndex,
        UUID worldId,
        double x,
        double y,
        double z,
        UUID itemDisplayId,
        UUID interactionId,
        UUID textDisplayId,
        int level,
        double energy,
        int hits,
        long lastHitAt) {

    public CoreRecord withHit(long now, long comboWindowMillis) {
        int currentHits = lastHitAt == 0 || now - lastHitAt > comboWindowMillis ? 0 : hits;
        int updatedHits = Math.min(50, currentHits + 1);
        double updatedCombo = Math.min(3.0, 1.0 + updatedHits * 0.04);
        double updatedEnergy = energy + baseEnergy() * updatedCombo;
        return new CoreRecord(
                islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level, updatedEnergy, updatedHits, now);
    }

    public CoreRecord withoutCombo() {
        return new CoreRecord(
                islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level, energy, 0, 0);
    }

    public CoreRecord upgrade(double cost) {
        if (energy < cost || level == Integer.MAX_VALUE) {
            return null;
        }
        return new CoreRecord(
                islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level + 1, energy - cost, hits, lastHitAt);
    }

    public double combo(long now, long comboWindowMillis) {
        if (lastHitAt == 0 || now - lastHitAt > comboWindowMillis) {
            return 1.0;
        }
        return Math.min(3.0, 1.0 + hits * 0.04);
    }

    public double baseEnergy() {
        return Math.pow(1.25, level - 1);
    }

    public double upgradeCost() {
        return 100.0 * Math.pow(2.0, level - 1);
    }
}
