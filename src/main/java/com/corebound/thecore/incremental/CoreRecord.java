package com.corebound.thecore.incremental;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
        int upgradeCritical,
        int prestigeCount,
        int coreFragments,
        int astralResonance,
        int astralPylonAttunement) {

    public CoreRecord {
        level = Math.max(1, level);
        upgradeOvercharge = Math.max(0, Math.min(5, upgradeOvercharge));
        upgradeWindow = Math.max(0, Math.min(5, upgradeWindow));
        upgradeCritical = Math.max(0, Math.min(5, upgradeCritical));
        prestigeCount = Math.max(0, prestigeCount);
        coreFragments = Math.max(0, coreFragments);
        astralResonance = Math.max(0, Math.min(AstralSkill.RESONANCE.maxLevel(), astralResonance));
        astralPylonAttunement = Math.max(
                0, Math.min(AstralSkill.PYLON_ATTUNEMENT.maxLevel(), astralPylonAttunement));
        double capacity = maxEnergy(level);
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
            long lastHitAt,
            int upgradeOvercharge,
            int upgradeWindow,
            int upgradeCritical,
            int prestigeCount,
            int coreFragments) {
        this(islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level, energy, hits, lastHitAt, upgradeOvercharge, upgradeWindow, upgradeCritical,
                prestigeCount, coreFragments, 0, 0);
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
                level, energy, hits, lastHitAt, 0, 0, 0, 0, 0);
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
            long lastHitAt,
            int upgradeOvercharge,
            int upgradeWindow,
            int upgradeCritical) {
        this(islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level, energy, hits, lastHitAt, upgradeOvercharge, upgradeWindow, upgradeCritical, 0, 0);
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
            long lastHitAt,
            int upgradeOvercharge,
            int upgradeWindow,
            int upgradeCritical,
            int prestigeCount) {
        this(islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level, energy, hits, lastHitAt, upgradeOvercharge, upgradeWindow, upgradeCritical,
                prestigeCount, 0);
    }

    public CoreRecord withHit(long now, long comboWindowMillis) {
        return withHit(now, comboWindowMillis, 1.0);
    }

    public CoreRecord withHit(long now, long comboWindowMillis, double yieldRatio) {
        return withHitResult(now, comboWindowMillis, yieldRatio).core();
    }

    public HitResult withHitResult(long now, long comboWindowMillis, double yieldRatio) {
        int currentHits = lastHitAt == 0 || now - lastHitAt > comboWindowMillis ? 0 : hits;
        int updatedHits = Math.min(maxComboHits(), currentHits + 1);
        double updatedCombo = Math.min(maxCombo(), 1.0 + updatedHits * 0.04);
        boolean critical = ThreadLocalRandom.current().nextDouble() < criticalChance();
        double criticalMultiplier = critical ? 3.0 : 1.0;
        double updatedEnergy = Math.min(
                maxEnergy(),
                energy + baseEnergy() * updatedCombo * yieldRatio * criticalMultiplier * prestigeMultiplier()
                        * astralResonanceMultiplier());
        CoreRecord updated = new CoreRecord(
                islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level, updatedEnergy, updatedHits, now,
                upgradeOvercharge, upgradeWindow, upgradeCritical, prestigeCount, coreFragments,
                astralResonance, astralPylonAttunement);
        return new HitResult(updated, critical);
    }

    public CoreRecord withPassiveEnergy(double amount) {
        return new CoreRecord(
                islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level, Math.min(maxEnergy(), energy + amount * prestigeMultiplier()), hits, lastHitAt,
                upgradeOvercharge, upgradeWindow, upgradeCritical, prestigeCount, coreFragments,
                astralResonance, astralPylonAttunement);
    }

    public CoreRecord withoutCombo() {
        return new CoreRecord(
                islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level, energy, 0, 0, upgradeOvercharge, upgradeWindow, upgradeCritical,
                prestigeCount, coreFragments, astralResonance, astralPylonAttunement);
    }

    public CoreRecord upgrade(double cost) {
        if (energy < cost || level == Integer.MAX_VALUE) {
            return null;
        }
        return new CoreRecord(
                islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level + 1, energy - cost, hits, lastHitAt,
                upgradeOvercharge, upgradeWindow, upgradeCritical, prestigeCount, coreFragments,
                astralResonance, astralPylonAttunement);
    }

    public CoreRecord spendEnergy(double cost) {
        if (!Double.isFinite(cost) || cost < 0 || energy < cost) {
            return null;
        }
        return new CoreRecord(
                islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level, energy - cost, hits, lastHitAt,
                upgradeOvercharge, upgradeWindow, upgradeCritical, prestigeCount, coreFragments,
                astralResonance, astralPylonAttunement);
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
                upgradeCritical + (enhancement == CoreEnhancement.CRITICAL ? 1 : 0),
                prestigeCount, coreFragments, astralResonance, astralPylonAttunement);
    }

    public CoreRecord upgradeAstralSkill(AstralSkill skill) {
        int skillLevel = astralSkillLevel(skill);
        int nextLevel = skillLevel + 1;
        int fragmentCost = skill.fragmentCost(nextLevel);
        if (nextLevel > skill.maxLevel()
                || prestigeCount < skill.fractureRequirement(nextLevel)
                || coreFragments < fragmentCost) {
            return null;
        }
        return new CoreRecord(
                islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                level, energy, hits, lastHitAt, upgradeOvercharge, upgradeWindow, upgradeCritical,
                prestigeCount, coreFragments - fragmentCost,
                astralResonance + (skill == AstralSkill.RESONANCE ? 1 : 0),
                astralPylonAttunement + (skill == AstralSkill.PYLON_ATTUNEMENT ? 1 : 0));
    }

    public int astralSkillLevel(AstralSkill skill) {
        return switch (skill) {
            case RESONANCE -> astralResonance;
            case PYLON_ATTUNEMENT -> astralPylonAttunement;
        };
    }

    public double astralResonanceMultiplier() {
        return 1.0 + astralResonance * AstralSkill.RESONANCE.bonusPerLevel();
    }

    public double astralPylonMultiplier() {
        return 1.0 + astralPylonAttunement * AstralSkill.PYLON_ATTUNEMENT.bonusPerLevel();
    }

    public int enhancementLevel(CoreEnhancement enhancement) {
        return switch (enhancement) {
            case OVERCHARGE -> upgradeOvercharge;
            case WINDOW -> upgradeWindow;
            case CRITICAL -> upgradeCritical;
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

    public double criticalChance() {
        return upgradeCritical * 0.05;
    }

    public double prestigeMultiplier() {
        return 1.0 + prestigeCount * 0.50;
    }

    public double baseEnergy() {
        return Math.pow(1.25, level - 1);
    }

    public double upgradeCost() {
        return maxEnergy() * 0.5;
    }

    public double maxEnergy() {
        return maxEnergy(level);
    }

    public CoreRecord fracture() {
        if (level < 10) {
            return null;
        }
        return new CoreRecord(
                islandIndex, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                1, 0.0, 0, 0,
                upgradeOvercharge, upgradeWindow, upgradeCritical, prestigeCount + 1, coreFragments + 1,
                astralResonance, astralPylonAttunement);
    }

    private static double maxEnergy(int level) {
        double capacity = 500.0 * Math.pow(2.2, level - 1);
        return Double.isFinite(capacity) ? capacity : Double.MAX_VALUE;
    }

    public int pylonCapacity() {
        if (level < 3) {
            return 0;
        }
        if (level < 7) {
            return 1;
        }
        if (level < 10) {
            return 2;
        }
        return 3;
    }

    public record HitResult(CoreRecord core, boolean critical) {
    }
}
