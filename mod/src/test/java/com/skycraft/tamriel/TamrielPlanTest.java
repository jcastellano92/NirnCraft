package com.skycraft.tamriel;

import com.skycraft.tamriel.terrain.Beyond;
import com.skycraft.tamriel.terrain.Hydrology;
import com.skycraft.tamriel.terrain.MapNoise;
import com.skycraft.tamriel.terrain.MapPeaks;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 0 unit tests for {@link Places}, {@link Regions}, {@link TamrielMap}, and the pure terrain samplers.
 */
class TamrielPlanTest {

    @Test
    void placesIdsAreUniqueAndAllResolveById() {
        List<Place> all = Places.all();
        assertFalse(all.isEmpty(), "Places.all() must not be empty");

        Set<String> seen = new HashSet<>(all.size());
        for (Place p : all) {
            assertNotNull(p.id());
            assertFalse(p.id().isBlank(), "Place id must not be blank");
            assertTrue(seen.add(p.id()), "Duplicate place id: " + p.id());
            Optional<Place> byId = Places.byId(p.id());
            assertTrue(byId.isPresent(), "Places.byId failed for " + p.id());
            assertSame(p, byId.get());
            assertTrue(p.radius() > 0, "Place radius must be > 0 for " + p.id());
        }
        assertTrue(Places.byId("nonexistent_place_id_xyz").isEmpty());
    }

    @Test
    void placesSpatialIndicesFindKnownCapitalsByChunkBoxAndNearest() {
        Place whiterun = Places.byId("whiterun").orElseThrow();
        ChunkPos cp = new ChunkPos(whiterun.x() >> 4, whiterun.z() >> 4);
        assertTrue(Places.inChunk(cp).contains(whiterun), "inChunk must contain Whiterun at its anchor chunk");

        List<Place> overlap = Places.overlapping(whiterun.x() - 10, whiterun.z() - 10, whiterun.x() + 10, whiterun.z() + 10);
        assertTrue(overlap.contains(whiterun), "overlapping must find Whiterun around its centre");

        Optional<Place> nearest = Places.nearest(PlaceType.CAPITAL, whiterun.x() + 5, whiterun.z() - 5, 64);
        assertTrue(nearest.isPresent());
        assertEquals("whiterun", nearest.get().id());
    }

    @Test
    void regionsAtOriginIsWhiterunAndKnownAnchorsMatchTheirRegions() {
        Region origin = TamrielMap.region(0, 0);
        assertEquals("skyrim/whiterun", origin.id(), "Origin (0, 0) must be skyrim/whiterun");
        assertEquals("skyrim", origin.province());

        for (String id : List.of("whiterun", "solitude", "windhelm", "riften", "markarth", "falkreath", "morthal", "dawnstar", "winterhold")) {
            Place p = Places.byId(id).orElseThrow(() -> new AssertionError("Missing place " + id));
            assertTrue(Regions.byId(p.regionId()).isPresent(), "Place regionId must exist in Regions table: " + p.regionId());
            Region r = TamrielMap.region(p.x(), p.z());
            assertTrue("skyrim".equals(r.province()) || "sea".equals(r.province()),
                    "Capitals of Skyrim must sample in skyrim or coastal sea at anchor: " + id + " -> " + r.id());
        }
        for (String inland : List.of("whiterun", "falkreath", "markarth")) {
            Place p = Places.byId(inland).orElseThrow();
            assertEquals(p.regionId(), TamrielMap.region(p.x(), p.z()).id());
        }
    }

    @Test
    void regionsTableRoundTripsByIdAndByIndex() {
        assertTrue(Regions.count() > 10, "Authored region table must be populated");
        assertEquals("sea/open_ocean", Regions.byIndex(0).id());

        for (Region r : Regions.all()) {
            int idx = Regions.indexOf(r.id());
            assertTrue(idx >= 0 && idx < Regions.count(), "Invalid index for " + r.id());
            assertSame(r, Regions.byIndex(idx));
            assertEquals(Optional.of(r), Regions.byId(r.id()));
            assertNotNull(Regions.terrain(idx));
        }
        assertEquals(-1, Regions.indexOf("unknown/region_xyz"));
    }

    @Test
    void tamrielMapSampleIsDeterministicForFixedInputs() {
        assertTrue(TamrielMap.loaded(), "Tamriel map rasters must load on test classpath");
        int[][] coords = {
                {0, 0}, {-1200, -800}, {1800, 950}, {-4500, 3200}, {6000, -1500}, {-25000, 18000}
        };
        for (int[] c : coords) {
            MapSample a = new MapSample();
            MapSample b = new MapSample();
            TamrielMap.sample(c[0], c[1], a);
            TamrielMap.sample(c[0], c[1], b);

            assertEquals(a.elevation, b.elevation, 0.0f);
            assertEquals(a.slope, b.slope, 0.0f);
            assertEquals(a.coastDistance, b.coastDistance, 0.0f);
            assertEquals(a.waterLevel, b.waterLevel, 0.0f);
            assertEquals(a.riverDistance, b.riverDistance, 0.0f);
            assertEquals(a.riverHalfWidth, b.riverHalfWidth, 0.0f);
            assertEquals(a.lakeDistance, b.lakeDistance, 0.0f);
            assertEquals(a.peak, b.peak, 0.0f);
            assertEquals(a.calm, b.calm, 0.0f);
            assertEquals(a.region, b.region);
            assertEquals(a.elevation, TamrielMap.elevation(c[0], c[1]), 0.0f);
            assertEquals(a.region, TamrielMap.regionIndex(c[0], c[1]));
        }
    }

    @Test
    void tamrielMapSampleAtWhiterunOriginIsDryInlandLandAboveSeaLevel() {
        MapSample s = new MapSample();
        TamrielMap.sample(0, 0, s);
        assertTrue(s.elevation > TamrielMap.SEA_LEVEL, "Whiterun (0,0) elevation must be above sea level");
        assertTrue(s.coastDistance > 0f, "Whiterun (0,0) must be inland (positive coastDistance)");
        assertFalse(s.inWater(), "Whiterun (0,0) must not be in water");
        assertEquals(Regions.indexOf("skyrim/whiterun"), s.region);
    }

    @Test
    void tamrielMapBoundsAndEndlessBeyondRegionsWorkDeterministically() {
        assertTrue(TamrielMap.inTamriel(0, 0));
        assertFalse(TamrielMap.inTamriel(TamrielMap.MIN_X - 100, 0));
        assertFalse(TamrielMap.inTamriel(TamrielMap.MAX_X + 100, 0));

        // Endless lands beyond the raster box return deterministic regions and generated realm regions round-trip by ID
        int farX = TamrielMap.MAX_X + 8000;
        int farZ = TamrielMap.MAX_Z + 8000;
        Region r1 = TamrielMap.region(farX, farZ);
        Region r2 = TamrielMap.region(farX, farZ);
        assertNotNull(r1);
        assertEquals(r1.id(), r2.id());

        Region gen = Beyond.generatedById("realm/x4_z4/0").orElseThrow();
        assertEquals("realm/x4_z4/0", gen.id());
        assertEquals("realm", gen.province());
    }

    @Test
    void deriveStrengthsComputesRiverAndLakeFactorsCorrectly() {
        MapSample s = new MapSample();
        s.waterLevel = Float.NaN;
        s.river = 1f;
        s.lake = 1f;
        TamrielMap.deriveStrengths(s);
        assertEquals(0f, s.river, 0.0f);
        assertEquals(0f, s.lake, 0.0f);

        // Wide river (halfWidth 6 >= 3.5) right on the channel centre
        s.waterLevel = 68f;
        s.riverHalfWidth = 6f;
        s.riverDistance = 2f;
        s.lakeDistance = -64f;
        TamrielMap.deriveStrengths(s);
        assertEquals(1f, s.river, 1e-4f);
        assertEquals(0f, s.lake, 1e-4f);

        // Narrow brook (halfWidth 2 < 3.5) does not trigger river biome strength
        s.riverHalfWidth = 2f;
        TamrielMap.deriveStrengths(s);
        assertEquals(0f, s.river, 1e-4f);
    }

    @Test
    void mapNoiseFunctionsAreDeterministicAndBounded() {
        double n1 = MapNoise.fbm(123.4, -567.8, 64.0, 4, 42);
        double n2 = MapNoise.fbm(123.4, -567.8, 64.0, 4, 42);
        assertEquals(n1, n2, 0.0);
        assertTrue(n1 >= -1.5 && n1 <= 1.5, "fbm value out of expected range: " + n1);

        double r1 = MapNoise.ridged(320.0, 640.0, 64.0, 4, 99);
        double r2 = MapNoise.ridged(320.0, 640.0, 64.0, 4, 99);
        assertEquals(r1, r2, 0.0);
        assertTrue(r1 >= 0.0 && r1 <= 1.5, "ridged value out of expected range: " + r1);
    }

    @Test
    void hydrologyAndMapPeaksApplyDeterministically() {
        assertNotNull(Hydrology.get(), "Hydrology must load from /skycraft/tamriel/hydrology.json");
        MapSample s1 = new MapSample();
        MapSample s2 = new MapSample();
        TamrielMap.sample(200, 200, s1);
        TamrielMap.sample(200, 200, s2);
        MapPeaks.apply(200, 200, s1);
        MapPeaks.apply(200, 200, s2);
        assertEquals(s1.elevation, s2.elevation, 0.0f);
        assertEquals(s1.peak, s2.peak, 0.0f);
    }
}
