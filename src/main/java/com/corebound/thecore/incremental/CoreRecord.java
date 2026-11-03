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
        long lastHitAt,
        int upgradeOvercharge,
        int upgradeWindow,
        int upgradeStorage) {

    public CoreRecord {
        level = Math.max(1, level);
        upgradeOvercharge = Math.max(0, Math.min(5, upgradeOvercharge));
        upgradeWindow = Math.max(0, Math.min(5, upgradeWindow));
        upgradeStorage = Math.max(0, Math.min(5, upgradeStorage));
        double capacity = maxEnergy(level, upgradeStorage);
        energy = Double.isNaN(energy)
                ? 0.0
                : Math.max(0.0, Math.min(capacity, energy));
    }

    public CoreRecord(
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
        this(islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level, energy, hits, lastHitAt, 0, 0, 0);
    }

    public CoreRecord withHit(long now, long comboWindowMillis) {
        return withHit(now, comboWindowMillis, 1.0);
    }

    public CoreRecord withHit(long now, long comboWindowMillis, double yieldRatio) {
        int currentHits = lastHitAt == 0 || now - lastHitAt > comboWindowMillis ? 0 : hits;
        int updatedHits = Math.min(maxComboHits(), currentHits + 1);
        double updatedCombo = Math.min(maxCombo(), 1.0 + updatedHits * 0.04);
        double updatedEnergy = Math.min(maxEnergy(), energy + baseEnergy() * updatedCombo * yieldRatio);
        return new CoreRecord(
                islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level, updatedEnergy, updatedHits, now, upgradeOvercharge, upgradeWindow, upgradeStorage);
    }

    public CoreRecord withPassiveEnergy(double amount) {
        return new CoreRecord(
                islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level, Math.min(maxEnergy(), energy + amount), hits, lastHitAt,
                upgradeOvercharge, upgradeWindow, upgradeStorage);
    }

    public CoreRecord withoutCombo() {
        return new CoreRecord(
                islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level, energy, 0, 0, upgradeOvercharge, upgradeWindow, upgradeStorage);
    }

    public CoreRecord upgrade(double cost) {
        if (energy < cost || level == Integer.MAX_VALUE) {
            return null;
        }
        return new CoreRecord(
                islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level + 1, energy - cost, hits, lastHitAt,
                upgradeOvercharge, upgradeWindow, upgradeStorage);
    }

    public CoreRecord spendEnergy(double cost) {
        if (!Double.isFinite(cost) || cost < 0 || energy < cost) {
            return null;
        }
        return new CoreRecord(
                islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level, energy - cost, hits, lastHitAt, upgradeOvercharge, upgradeWindow, upgradeStorage);
    }

    public CoreRecord upgradeEnhancement(CoreEnhancement enhancement, double cost, int maxLevel) {
        int currentLevel = enhancementLevel(enhancement);
        if (!Double.isFinite(cost) || cost < 0 || energy < cost || currentLevel >= maxLevel) {
            return null;
        }
        return new CoreRecord(
                islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level, energy - cost, hits, lastHitAt,
                upgradeOvercharge + (enhancement == CoreEnhancement.OVERCHARGE ? 1 : 0),
                upgradeWindow + (enhancement == CoreEnhancement.WINDOW ? 1 : 0),
                upgradeStorage + (enhancement == CoreEnhancement.STORAGE ? 1 : 0));
    }

    public int enhancementLevel(CoreEnhancement enhancement) {
        return switch (enhancement) {
            case OVERCHARGE -> upgradeOvercharge;
            case WINDOW -> upgradeWindow;
            case STORAGE -> upgradeStorage;
        };
    }

    public double combo(long now, long comboWindowMillis) {
        if (lastHitAt == 0 || now - lastHitAt > comboWindowMillis) {
            return 1.0;
        }
        return Math.min(maxCombo(), 1.0 + hits * 0.04);
    }

    public double maxCombo() {
        return 3.0 + upgradeOvercharge * 0.5;
    }

    public int maxComboHits() {
        return (int) Math.ceil((maxCombo() - 1.0) / 0.04);
    }

    public long comboWindowMillis(long baseWindowMillis) {
        return baseWindowMillis + upgradeWindow * 500L;
    }

    public double baseEnergy() {
        return Math.pow(1.25, level - 1);
    }

    public double upgradeCost() {
        return 100.0 * Math.pow(2.0, level - 1);
    }

    public double maxEnergy() {
        return maxEnergy(level, upgradeStorage);
    }

    private static double maxEnergy(int level, int upgradeStorage) {
        double capacity = 500.0 * Math.pow(2.2, level - 1) * (1.0 + upgradeStorage * 0.50);
        return Double.isFinite(capacity) ? capacity : Double.MAX_VALUE;
    }

    public int pylonCapacity() {
        return Math.max(0, level - 1);
    }
}
