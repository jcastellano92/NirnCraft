package com.nirncraft.atlas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Level 0's ask for a section of a loaded chunk is answered from the chunk (AtlasClientSections.reread). */
class AtlasRereadTest {
    @Test
    void aLoadedChunksSectionIsReadAgain() {
        AtlasClientSections.real(201, 202);
        long before = AtlasClientSections.rereads;
        assertTrue(AtlasClientSections.reread(201, 5, 202));
        assertEquals(before + 1, AtlasClientSections.rereads);
    }

    @Test
    void anUnloadedChunkIsTheGeneratorsToMake() {
        assertFalse(AtlasClientSections.reread(-9001, 5, -9001));
    }
}
