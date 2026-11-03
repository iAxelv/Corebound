package com.corebound.thecore.incremental;

public enum AstralSkill {
    RESONANCE(3, 1, 1, 0.10),
    PYLON_ATTUNEMENT(2, 2, 2, 0.15);

    private final int maxLevel;
    private final int firstFractureRequirement;
    private final int firstFragmentCost;
    private final double bonusPerLevel;

    AstralSkill(int maxLevel, int firstFractureRequirement, int firstFragmentCost, double bonusPerLevel) {
        this.maxLevel = maxLevel;
        this.firstFractureRequirement = firstFractureRequirement;
        this.firstFragmentCost = firstFragmentCost;
        this.bonusPerLevel = bonusPerLevel;
    }

    public int maxLevel() {
        return maxLevel;
    }

    public int fractureRequirement(int nextLevel) {
        return firstFractureRequirement + nextLevel - 1;
    }

    public int fragmentCost(int nextLevel) {
        return firstFragmentCost + nextLevel - 1;
    }

    public double bonusPerLevel() {
        return bonusPerLevel;
    }
}
