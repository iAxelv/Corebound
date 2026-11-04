package com.corebound.thecore.incremental;

import java.util.Map;
import java.util.UUID;

public record CropGeneratorRecord(
        long islandIndex,
        UUID generatorId,
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

    public CropGeneratorRecord {
        level = Math.max(0, Math.min(10, level));
        runeSlots = Math.max(1, Math.min(3, runeSlots));
        runes = Map.copyOf(runes);
        runes.forEach((slot, rune) -> {
            if (slot < 0 || slot >= 3 || !rune.type().isCropGeneratorRune()) {
                throw new IllegalArgumentException("Crop generator runes must occupy a valid generator slot.");
            }
        });
        if (state == null) {
            state = CondenserState.ACTIVE;
        }
    }

    public CropGeneratorRecord withLevel(int updatedLevel, int updatedRuneSlots) {
        return new CropGeneratorRecord(
                islandIndex, generatorId, worldId, x, y, z, itemDisplayId, interactionId,
                textDisplayId, updatedLevel, state, updatedRuneSlots, runes);
    }

    public CropGeneratorRecord withState(CondenserState updatedState) {
        return new CropGeneratorRecord(
                islandIndex, generatorId, worldId, x, y, z, itemDisplayId, interactionId,
                textDisplayId, level, updatedState, runeSlots, runes);
    }

    public CropGeneratorRecord withRune(int slot, CellRecord rune) {
        if (slot < 0 || slot >= runeSlots) {
            throw new IllegalArgumentException("Rune slot is not unlocked for this crop generator.");
        }
        java.util.HashMap<Integer, CellRecord> updated = new java.util.HashMap<>(runes);
        if (rune == null) {
            updated.remove(slot);
        } else {
            updated.put(slot, rune);
        }
        return new CropGeneratorRecord(
                islandIndex, generatorId, worldId, x, y, z, itemDisplayId,
                interactionId, textDisplayId, level, state, runeSlots, updated);
    }

    public Map<Integer, CellRecord> activeRunes() {
        return runes.entrySet().stream()
                .filter(entry -> entry.getKey() < runeSlots)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
