package com.corebound.thecore.incremental;

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
        int tier) {
}
