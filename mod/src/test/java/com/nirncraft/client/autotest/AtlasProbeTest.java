package com.nirncraft.client.autotest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The atlas gates read the profiler's line (AtlasProbe). This parses a line captured from the real game
 * (src/test/resources/atlas-profile-line.txt, with the recorder block after the core's JSON, the pace line with its gpu
 * share and phases, genCache{not in this library}) and requires every counter a gate needs: when the profiler's format
 * changes this fails, instead of a route reporting -1 as data. Refresh the file from .minecraft/atlas-profile.log.
 */
class AtlasProbeTest {
    static String line() throws IOException {
        try (var in = AtlasProbeTest.class.getResourceAsStream("/atlas-profile-line.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void currentProfileLineHasEveryCounterAGateNeeds() throws IOException {
        var s = AtlasProbe.parse(line());
        assertTrue(AtlasProbe.missing(s).isEmpty(), "absent: " + AtlasProbe.missing(s) + " in " + s);
        assertFalse(s.has("coreError"), String.valueOf(s.get("coreError")));
        assertEquals(7, s.getAsJsonArray("holes").size());
        assertTrue(s.has("budgetGpuMs") && s.has("budgetLongestPhase"), "the pace line's current format");
        assertTrue(s.get("genChunks").getAsDouble() >= 0);
    }

    @Test
    void cacheLineInTheCurrentFormat() throws IOException {
        // AtlasGenNative.cacheLine's format, filled in
        String cache = "genCache{on fp 00ff00ff00ff00ff on disk 120 chunks 700 sections 9.5 MB; queued writes 3; written 11 chunks 70 sections 4 far tiles; "
                + "chunk hits 5 misses 6 (1 incomplete), section reads 40, far tile hits 8, hash errors 0}";
        var s = AtlasProbe.parse(line().replace("genCache{not in this library}", cache));
        assertFalse(s.has("gcAbsent"));
        assertEquals(5, s.get("gcChunkHits").getAsInt());
        assertEquals(70, s.get("gcSectionWrites").getAsInt());
        assertEquals(8, s.get("gcFarTileHits").getAsInt());
        assertTrue(s.get("gcOn").getAsBoolean());
    }

    @Test
    void anAbsentCounterIsNamedNeverANumber() {
        var s = AtlasProbe.parse("frame{atlasMs=1.0 glRenderWallMs=0.1 frameWallMs=2.0 fps=60 unified=true loadedChunks=5 t=1} no context");
        assertTrue(AtlasProbe.missing(s).contains("holes"));
        assertFalse(s.has("holes"));
        assertTrue(s.has("missing"));
    }
}
