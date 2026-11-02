package com.corebound.thecore.incremental;

import java.util.Map;
import java.util.UUID;

public record PylonRecord(
        long islandIndex,
        UUID pylonId,
        UUID worldId,
        double x,
        double y,
        double z,
        UUID itemDisplayId,
        UUID rodDisplayId,
        UUID interactionId,
        UUID textDisplayId,
        int tier,
        Map<Integer, CellRecord> equippedCells) {
    public PylonRecord {
        equippedCells = Map.copyOf(equippedCells);
    }

    public int maxSlots() {
        return Math.max(0, Math.min(tier, 3));
    }

    public PylonRecord withTier(int updatedTier) {
        return new PylonRecord(
                islandIndex, pylonId, worldId, x, y, z, itemDisplayId, rodDisplayId, interactionId,
                textDisplayId, updatedTier, equippedCells);
    }

    public PylonRecord withCell(int slot, CellRecord cell) {
        java.util.HashMap<Integer, CellRecord> updatedCells = new java.util.HashMap<>(equippedCells);
        if (cell == null) {
            updatedCells.remove(slot);
        } else if (slot >= 0 && slot < maxSlots()) {
            updatedCells.put(slot, cell);
        } else {
            throw new IllegalArgumentException("Cell slot is outside this pylon's unlocked slots.");
        }
        return new PylonRecord(
                islandIndex, pylonId, worldId, x, y, z, itemDisplayId, rodDisplayId, interactionId,
                textDisplayId, tier, updatedCells);
    }
}
