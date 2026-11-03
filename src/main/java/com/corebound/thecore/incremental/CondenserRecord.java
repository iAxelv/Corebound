package com.corebound.thecore.incremental;

import java.util.UUID;
import java.util.Map;

public record CondenserRecord(
        long islandIndex,
        UUID condenserId,
        UUID worldId,
        double x,
        double y,
        double z,
        UUID itemDisplayId,
        UUID interactionId,
        UUID textDisplayId,
        int level,
        CondenserState state,
        int runeSlots,
        Map<Integer, CellRecord> runes) {

    public CondenserRecord {
        level = Math.max(0, Math.min(9, level));
        runeSlots = Math.max(1, Math.min(3, runeSlots));
        runes = Map.copyOf(runes);
        int unlockedRuneSlots = runeSlots;
        runes.forEach((slot, rune) -> {
            if (slot < 0 || slot >= unlockedRuneSlots || !rune.type().isCondenserRune()) {
                throw new IllegalArgumentException("Condenser runes must occupy an unlocked valid slot.");
            }
        });
        if (state == null) {
            state = CondenserState.ACTIVE;
        }
    }

    public CondenserRecord withLevel(int updatedLevel) {
        return new CondenserRecord(
                islandIndex, condenserId, worldId, x, y, z, itemDisplayId,
                interactionId, textDisplayId, updatedLevel, state, runeSlots, runes);
    }

    public CondenserRecord withState(CondenserState updatedState) {
        return new CondenserRecord(
                islandIndex, condenserId, worldId, x, y, z, itemDisplayId,
                interactionId, textDisplayId, level, updatedState, runeSlots, runes);
    }

    public CondenserRecord withRune(int slot, CellRecord rune) {
        java.util.HashMap<Integer, CellRecord> updated = new java.util.HashMap<>(runes);
        if (rune == null) {
            updated.remove(slot);
        } else {
            updated.put(slot, rune);
        }
        return new CondenserRecord(
                islandIndex, condenserId, worldId, x, y, z, itemDisplayId,
                interactionId, textDisplayId, level, state, runeSlots, updated);
    }

    public CondenserRecord withRuneSlots(int updatedSlots) {
        return new CondenserRecord(
                islandIndex, condenserId, worldId, x, y, z, itemDisplayId,
                interactionId, textDisplayId, level, state, updatedSlots, runes);
    }
}
