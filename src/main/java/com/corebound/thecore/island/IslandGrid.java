package com.corebound.thecore.island;

public final class IslandGrid {
    private IslandGrid() {
    }

    public static IslandCoordinates coordinates(long index, long distance) {
        if (index < 0 || distance <= 0) {
            throw new IllegalArgumentException("Island index must be non-negative and distance must be positive.");
        }
        if (index == 0) {
            return new IslandCoordinates(0, 0);
        }

        long ring = (long) Math.ceil((Math.sqrt(index + 1.0) - 1.0) / 2.0);
        long start = square(2 * ring - 1);
        long offset = index - start;
        long side = 2 * ring;
        long gridX;
        long gridZ;

        if (offset < side) {
            gridX = ring;
            gridZ = -ring + 1 + offset;
        } else if (offset < side * 2) {
            long step = offset - side;
            gridX = ring - 1 - step;
            gridZ = ring;
        } else if (offset < side * 3) {
            long step = offset - side * 2;
            gridX = -ring;
            gridZ = ring - 1 - step;
        } else {
            long step = offset - side * 3;
            gridX = -ring + 1 + step;
            gridZ = -ring;
        }

        return new IslandCoordinates(
                Math.multiplyExact(gridX, distance),
                Math.multiplyExact(gridZ, distance));
    }

    private static long square(long value) {
        return Math.multiplyExact(value, value);
    }
}
