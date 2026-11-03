package com.corebound.thecore.incremental;

import java.util.UUID;

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
        CondenserState state) {

    public CondenserRecord {
        level = Math.max(0, Math.min(8, level));
        if (state == null) {
            state = CondenserState.ACTIVE;
        }
    }

    public CondenserRecord withLevel(int updatedLevel) {
        return new CondenserRecord(
                islandIndex, condenserId, worldId, x, y, z, itemDisplayId,
                interactionId, textDisplayId, updatedLevel, state);
    }

    public CondenserRecord withState(CondenserState updatedState) {
        return new CondenserRecord(
                islandIndex, condenserId, worldId, x, y, z, itemDisplayId,
                interactionId, textDisplayId, level, updatedState);
    }
}
