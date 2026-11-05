package com.corebound.thecore.incremental;

public record CellRecord(CellType type, int tier) {
    public static final int MAX_TIER = 5;

    public CellRecord {
        if (type == null || tier < 1 || tier > MAX_TIER) {
            throw new IllegalArgumentException("Cell type and tier must be valid.");
        }
    }

    public CellRecord fuse(CellRecord other) {
        return fuse(other, MAX_TIER);
    }

    public CellRecord fuse(CellRecord other, int maximumTier) {
        if (other == null || type != other.type || tier != other.tier || tier >= MAX_TIER) {
            return null;
        }
        int fusedTier = tier + 1;
        return fusedTier > Math.min(MAX_TIER, maximumTier) ? null : new CellRecord(type, fusedTier);
    }

    public static int maximumTierForCoreLevel(int coreLevel) {
        if (coreLevel < 5) {
            return 1;
        }
        return Math.min(MAX_TIER, 2 + (coreLevel - 5) / 5);
    }

    public static int maximumTierForCondenserLevel(int condenserLevel) {
        return Math.max(1, Math.min(MAX_TIER, 1 + condenserLevel / 2));
    }

    public static int maximumTierForCropGeneratorLevel(int generatorLevel) {
        return Math.max(1, Math.min(MAX_TIER, 1 + generatorLevel / 2));
    }

    public int fireIntervalReductionTicks() {
        return type == CellType.FIRE ? 20 * tier : 0;
    }

    public double lightningBonusPerLink() {
        return type == CellType.LIGHTNING ? 0.10 * tier : 0.0;
    }

    public double voidCriticalChance() {
        return type == CellType.VOID ? 0.05 * tier : 0.0;
    }

    public double pylonEnergyBonus() {
        return type == CellType.ENERGY ? 0.10 * tier : 0.0;
    }

    public double condenserEnergyReduction() {
        return type == CellType.CONDENSER_EFFICIENCY ? 0.05 * tier : 0.0;
    }

    public double condenserOreChanceBonus() {
        return type == CellType.CONDENSER_YIELD ? 0.05 * tier : 0.0;
    }

    public double cropEnergyReduction() {
        return type == CellType.CROP_ENERGY_EFFICIENCY ? 0.05 * tier : 0.0;
    }

    public double cropHarvestBonus() {
        return type == CellType.CROP_EFFICIENCY ? 0.10 * tier : 0.0;
    }

    public double batteryCapacityBonus() {
        return type == CellType.BATTERY_CAPACITY ? 0.10 * tier : 0.0;
    }

    public double batteryOfflineBonus() {
        return type == CellType.BATTERY_OFFLINE ? 0.10 * tier : 0.0;
    }
}
