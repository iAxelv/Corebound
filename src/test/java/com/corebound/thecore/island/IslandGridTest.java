package com.corebound.thecore.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class IslandGridTest {
    @Test
    void followsTheDocumentedSquareSpiral() {
        assertEquals(new IslandCoordinates(0, 0), IslandGrid.coordinates(0, 3000));
        assertEquals(new IslandCoordinates(3000, 0), IslandGrid.coordinates(1, 3000));
        assertEquals(new IslandCoordinates(3000, 3000), IslandGrid.coordinates(2, 3000));
        assertEquals(new IslandCoordinates(0, 3000), IslandGrid.coordinates(3, 3000));
        assertEquals(new IslandCoordinates(-3000, 3000), IslandGrid.coordinates(4, 3000));
        assertEquals(new IslandCoordinates(-3000, 0), IslandGrid.coordinates(5, 3000));
        assertEquals(new IslandCoordinates(-3000, -3000), IslandGrid.coordinates(6, 3000));
        assertEquals(new IslandCoordinates(0, -3000), IslandGrid.coordinates(7, 3000));
        assertEquals(new IslandCoordinates(3000, -3000), IslandGrid.coordinates(8, 3000));
        assertEquals(new IslandCoordinates(6000, -3000), IslandGrid.coordinates(9, 3000));
    }

    @Test
    void rejectsNegativeIndexesAndNonPositiveSpacing() {
        assertThrows(IllegalArgumentException.class, () -> IslandGrid.coordinates(-1, 3000));
        assertThrows(IllegalArgumentException.class, () -> IslandGrid.coordinates(0, 0));
    }
}
