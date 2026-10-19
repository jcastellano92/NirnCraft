package com.nirncraft.atlas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real sections' window (AtlasSections.push): a tile that left it counts as never sent, so a player who returns
 * gets it again (the client's core drops sections outside its keep and never asks for them).
 */
class AtlasSectionsWindowTest {
    @Test
    void tilesLeaveTheWindowBeyondItsRadius() {
        int r = AtlasSections.RADIUS_TILES;
        assertFalse(AtlasSections.outside(AtlasEdits.tileKey(3 + r, -2 - r), 3, -2), "on the window's edge: still in");
        assertTrue(AtlasSections.outside(AtlasEdits.tileKey(4 + r, -2), 3, -2), "one tile past it: out");
        assertTrue(AtlasSections.outside(AtlasEdits.tileKey(3, -3 - r), 3, -2), "past it along z: out");
        assertFalse(AtlasSections.outside(AtlasEdits.tileKey(-5, -7), -5, -7), "negative tiles decode as themselves");
    }

    @Test
    void theWindowStaysInsideTheCoresKeep() {
        // native World: sections kept windowRadius (2) + 3 regions of 256 blocks either side, the tiles' own grid
        assertTrue(AtlasSections.RADIUS_TILES < 2 + 3, "the server sends only what the core keeps, with a ring to spare");
        assertEquals(AtlasNative.TILE, AtlasEdits.TILE, "tiles and core regions share one grid");
    }
}
