package com.corebound.thecore.incremental;

import java.util.Map;
import java.util.UUID;

public record BatteryRecord(
        long islandIndex,
        UUID batteryId,
        UUID worldId,
        double x,
        double y,
        double z,
        UUID itemDisplayId,
        UUID interactionId,
        UUID textDisplayId,
        int tier,
        double energy,
        Map<Integer, CellRecord> runes,
        long offlineMillis,
        long lastOfflineCheck,
        double offlineEarnedEnergy) {

    public BatteryRecord {
        tier = Math.max(1, Math.min(3, tier));
        energy = Double.isFinite(energy) ? Math.max(0, energy) : 0;
        offlineEarnedEnergy = Double.isFinite(offlineEarnedEnergy)
                ? Math.max(0, offlineEarnedEnergy)
                : 0;
        runes = Map.copyOf(runes);
        for (Map.Entry<Integer, CellRecord> entry : runes.entrySet()) {
            int slot = entry.getKey();
            CellRecord rune = entry.getValue();
            if (slot < 0 || slot >= tier || !rune.type().isBatteryRune()) {
                throw new IllegalArgumentException("Battery runes must occupy an unlocked battery slot.");
            }
        }
        offlineMillis = Math.max(0, offlineMillis);
        lastOfflineCheck = Math.max(0, lastOfflineCheck);
    }

    public BatteryRecord(
            long islandIndex,
            UUID batteryId,
            UUID worldId,
            double x,
            double y,
            double z,
            UUID itemDisplayId,
            UUID interactionId,
            UUID textDisplayId,
            int tier,
            double energy,
            Map<Integer, CellRecord> runes,
            long offlineMillis,
            long lastOfflineCheck) {
        this(islandIndex, batteryId, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                tier, energy, runes, offlineMillis, lastOfflineCheck, 0);
    }

    public int runeSlots() {
        return tier;
    }

    public double capacity(CoreRecord core) {
        return capacity(core, 0.10);
    }

    public double capacity(CoreRecord core, double runeBonusPerTier) {
        double runeBonus = runes.values().stream()
                .filter(rune -> rune.type() == CellType.BATTERY_CAPACITY)
                .mapToDouble(rune -> rune.tier() * runeBonusPerTier)
                .sum();
        return core.maxEnergy() * 0.5 * (1.0 + runeBonus);
    }

    public double offlineMultiplier() {
        return offlineMultiplier(0.10);
    }

    public double offlineMultiplier(double runeBonusPerTier) {
        return 1.0 + runes.values().stream()
                .filter(rune -> rune.type() == CellType.BATTERY_OFFLINE)
                .mapToDouble(rune -> rune.tier() * runeBonusPerTier)
                .sum();
    }

    public BatteryRecord withEnergy(double updatedEnergy) {
        return new BatteryRecord(
                islandIndex, batteryId, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                tier, updatedEnergy, runes, offlineMillis, lastOfflineCheck, offlineEarnedEnergy);
    }

    public BatteryRecord withOfflineProgress(double updatedEnergy, long updatedOfflineMillis, long checkedAt) {
        return withOfflineProgress(updatedEnergy, updatedOfflineMillis, checkedAt, offlineEarnedEnergy);
    }

    public BatteryRecord withOfflineProgress(
            double updatedEnergy, long updatedOfflineMillis, long checkedAt, double updatedOfflineEarnedEnergy) {
        return new BatteryRecord(
                islandIndex, batteryId, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                tier, updatedEnergy, runes, updatedOfflineMillis, checkedAt, updatedOfflineEarnedEnergy);
    }

    public BatteryRecord withTier(int updatedTier) {
        return new BatteryRecord(
                islandIndex, batteryId, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                updatedTier, energy, runes.entrySet().stream()
                        .filter(entry -> entry.getKey() < updatedTier)
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey, Map.Entry::getValue)),
                offlineMillis, lastOfflineCheck, offlineEarnedEnergy);
    }

    public BatteryRecord withRune(int slot, CellRecord rune) {
        if (slot < 0 || slot >= runeSlots()) {
            throw new IllegalArgumentException("Rune slot is not unlocked for this battery.");
        }
        java.util.HashMap<Integer, CellRecord> updated = new java.util.HashMap<>(runes);
        if (rune == null) {
            updated.remove(slot);
        } else {
            updated.put(slot, rune);
        }
        return new BatteryRecord(
                islandIndex, batteryId, worldId, x, y, z, itemDisplayId, interactionId, textDisplayId,
                tier, energy, updated, offlineMillis, lastOfflineCheck, offlineEarnedEnergy);
    }
}
