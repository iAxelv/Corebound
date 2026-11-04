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
        runes.forEach((slot, rune) -> {
            if (slot < 0 || slot >= 3 || !rune.type().isCondenserRune()) {
                throw new IllegalArgumentException("Condenser runes must occupy a valid condenser slot.");
            }
        });
        if (state == null) {
            state = CondenserState.ACTIVE;
        }
    }

    public CondenserRecord withLevel(int updatedLevel) {
        return withLevel(updatedLevel, runeSlots);
    }

    public CondenserRecord withLevel(int updatedLevel, int updatedRuneSlots) {
        return new CondenserRecord(
                islandIndex, condenserId, worldId, x, y, z, itemDisplayId,
                interactionId, textDisplayId, updatedLevel, state, updatedRuneSlots, runes);
    }

    public CondenserRecord withState(CondenserState updatedState) {
        return new CondenserRecord(
                islandIndex, condenserId, worldId, x, y, z, itemDisplayId,
                interactionId, textDisplayId, level, updatedState, runeSlots, runes);
    }

    public CondenserRecord withRune(int slot, CellRecord rune) {
        if (slot < 0 || slot >= runeSlots) {
            throw new IllegalArgumentException("Rune slot is not unlocked for this condenser.");
        }
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

    public Map<Integer, CellRecord> activeRunes() {
        return runes.entrySet().stream()
                .filter(entry -> entry.getKey() < runeSlots)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
