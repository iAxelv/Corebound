package com.corebound.thecore.incremental;

public record CellRecord(CellType type, int tier) {
    public static final int MAX_TIER = 5;

    public CellRecord {
        if (type == null || tier < 1 || tier > MAX_TIER) {
            throw new IllegalArgumentException("Cell type and tier must be valid.");
        }
    }

    public CellRecord fuse(CellRecord other) {
        if (other == null || type != other.type || tier != other.tier || tier >= MAX_TIER) {
            return null;
        }
        return new CellRecord(type, tier + 1);
    }
}
