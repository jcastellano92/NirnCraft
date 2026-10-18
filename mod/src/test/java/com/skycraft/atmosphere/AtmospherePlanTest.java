package com.skycraft.atmosphere;

import com.skycraft.atmosphere.weather.RegionalWeather;
import com.skycraft.atmosphere.weather.WeatherKind;
import com.skycraft.atmosphere.weather.WeatherPackets;
import com.skycraft.atmosphere.weather.WeatherProfile;
import com.skycraft.creatures.spawn.RegionSpawner;
import com.skycraft.creatures.spawn.SpawnTables;
import com.skycraft.society.NpcRole;
import com.skycraft.society.Schedule;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 0 unit tests for Phase T10:
 * <ul>
 *   <li>Continuous latitude-driven aurora intensity, elevation band ({@code -Z} northern horizon), and star dome tilt;</li>
 *   <li>Moving synoptic {@link WeatherFront} advection, radial falloff, horizon approach factor, and {@link WeatherPackets.Sync}
 *       network/NBT round-trip;</li>
 *   <li>{@link Schedule#activityAt} NPC indoor shelter routing during {@code BLIZZARD}, {@code SANDSTORM},
 *       {@code ASH_STORM}, and {@code BLIGHT};</li>
 *   <li>{@link RegionSpawner#toSpawnWeather} regional storm mapping;</li>
 *   <li>{@link AtmosphereConfig} Oculus shader compatibility defaults.</li>
 * </ul>
 */
class AtmospherePlanTest {
    private static WeatherPhysics.ColumnState electricalColumn(float tower, float cover, float rain, float temperature, float aerosol, float wind) {
        return new WeatherPhysics.ColumnState(0.8f, 0.3f, 0, 1, 0, wind, cover, tower, rain,
                rain, aerosol, -120, 0, 63, temperature, 0.9f, temperature - 2, 0);
    }

    @Test
    void electricalStormsNeedDeepPrecipitatingMixedPhaseClouds() {
        assertTrue(WeatherPhysics.electricalActivity(electricalColumn(1, 0.95f, 0.9f, 12, WeatherPhysics.AEROSOL_WATER, 0.9f), 1) > 0.8f);
        assertEquals(0f, WeatherPhysics.electricalActivity(electricalColumn(0.2f, 0.95f, 0.9f, 12, WeatherPhysics.AEROSOL_WATER, 0.9f), 1));
        assertEquals(0f, WeatherPhysics.electricalActivity(electricalColumn(1, 0.95f, 0, 12, WeatherPhysics.AEROSOL_WATER, 0.9f), 1));
        assertEquals(0f, WeatherPhysics.electricalActivity(electricalColumn(1, 0.95f, 0.9f, -40, WeatherPhysics.AEROSOL_SNOW, 0.9f), 1));
        assertEquals(0f, WeatherPhysics.electricalActivity(electricalColumn(1, 0.95f, 0.9f, 12, WeatherPhysics.AEROSOL_WATER, 0.9f), 0));
    }

    @Test
    void ashElectrificationRequiresDenseTurbulentConvection() {
        assertTrue(WeatherPhysics.electricalActivity(electricalColumn(1, 0.95f, 0.9f, 60, WeatherPhysics.AEROSOL_ASH, 0.9f), 1) > 0.8f);
        assertEquals(0f, WeatherPhysics.electricalActivity(electricalColumn(0.1f, 0.6f, 0.3f, 60, WeatherPhysics.AEROSOL_ASH, 0.2f), 1));
    }
    @Test
    void lunarPhasePositionAndVisibleLightAgreeWithTheSun() {
        assertEquals(1f, AtmosphereMath.moonElevation(Math.PI, 0, false), 0.0001f);
        assertEquals(-1f, AtmosphereMath.moonElevation(Math.PI, 0.5, false), 0.0001f);
        assertEquals(0f, AtmosphereMath.visibleMoonIllumination(Math.PI, 0.5, false), 0.0001f);
        assertEquals(1f, AtmosphereMath.visibleMoonIllumination(Math.PI, 0, false), 0.0001f);
        assertEquals(0f, AtmosphereMath.moonElevation(Math.PI, 0.25, false), 0.0001f);
    }
    @Test
    void transientAuroraHasNoEmissionWhenInactiveOrOutsideItsLatitudeRange() {
        float[] state = new float[AuroraMath.VALUES];
        AuroraMath.sample(1234, 0, 1, 0, state);
        for (float value : state) assertEquals(0f, value);
        AuroraMath.sample(1234, 1, 0, 0, state);
        for (float value : state) assertEquals(0f, value);
    }

    @Test
    void transientAuroraAndItsLightFadeContinuouslyAcrossPacketReplacement() {
        float[] before = new float[AuroraMath.VALUES], after = new float[AuroraMath.VALUES];
        for (double boundary : new double[]{48, 71, 94, 4800}) {
            AuroraMath.sample(boundary - 0.001, 0.8f, 1, 0.2f, before);
            AuroraMath.sample(boundary + 0.001, 0.8f, 1, 0.2f, after);
            // A new packet may have a new shape only while that packet is dark.
            for (int i = 0; i < 6; i++) assertEquals(before[i*8+4], after[i*8+4], 0.001f);
            for (int i = 48; i < 60; i++) assertEquals(before[i], after[i], 0.001f);
        }
    }

    @Test
    void transientAuroraLightingTracksTheSameLiveCurtains() {
        float[] state = new float[AuroraMath.VALUES];
        float dimmest = Float.MAX_VALUE, brightest = 0;
        for (int second = 0; second < 240; second++) {
            AuroraMath.sample(second, 1, 1, 0, state);
            float luminance = state[48] + state[49] + state[50];
            dimmest = Math.min(dimmest, luminance); brightest = Math.max(brightest, luminance);
            for (float value : state) assertTrue(Float.isFinite(value));
            assertTrue(state[54] >= 0 && state[55] >= 0 && state[56] >= 0);
        }
        assertTrue(brightest > dimmest * 1.5f, "Emission must develop and fade, not remain a uniform night tint");
    }

    @Test
    void lunarCyclesAndNightAdaptationAreContinuous() {
        assertEquals(0, AtmosphereMath.moonCycle(18000, false), 1e-8);
        assertEquals(1, AtmosphereMath.moonIllumination(0), 1e-8);
        assertEquals(0, AtmosphereMath.moonIllumination(0.5), 1e-8);
        assertEquals(AtmosphereMath.moonCycle(18000, true),
                AtmosphereMath.moonCycle(18000 + 9 * 24000, true), 1e-8);
        assertNotEquals(AtmosphereMath.moonCycle(18000, true), AtmosphereMath.moonCycle(18000, false));
        assertTrue(AtmosphereMath.nightSkyVisibility(1, 0, 0) > AtmosphereMath.nightSkyVisibility(1, 1, 0));
        assertTrue(AtmosphereMath.nightSkyVisibility(1, 0, 0) > AtmosphereMath.nightSkyVisibility(1, 0, 1));
        assertEquals(0, AtmosphereMath.nightSkyVisibility(0, 0, 0));
        assertEquals(AtmosphereMath.moonIllumination(AtmosphereMath.moonCycle(18000 - 0.01, false)),
                AtmosphereMath.moonIllumination(AtmosphereMath.moonCycle(18000 + 0.01, false)), 1e-7);
    }

    @Test
    void dewPointControlsFogAndCloudHeight() {
        var sample = new com.skycraft.tamriel.MapSample();
        sample.elevation = 72;
        sample.waterLevel = 72;
        sample.wetland = 0.9f;
        var wetNight = WeatherPhysics.thermals(5500, sample, 1f, 0f, 0f, 18000);
        var wetDay = WeatherPhysics.thermals(5500, sample, 1f, 0f, 0f, 6000);
        var dryDay = WeatherPhysics.thermals(5500, sample, 0.1f, 0f, 0f, 6000);
        assertTrue(wetNight.dewPointC() <= wetNight.temperatureC());
        assertTrue(wetNight.groundFog() > wetDay.groundFog());
        assertTrue(wetNight.condensationHeight() < wetDay.condensationHeight());
        assertTrue(wetDay.condensationHeight() < dryDay.condensationHeight());
        assertEquals(0f, dryDay.groundFog(), 1e-5f);
        var forcedFog = WeatherPhysics.thermals(5500, sample, 0.9f, 0f, 1f, 6000);
        assertTrue(forcedFog.condensationHeight() <= 82);
        var advectedFog = WeatherPhysics.thermals(5500, sample, 0.1f, 0f, 1f, 6000);
        assertTrue(advectedFog.groundFog() > 0.9f, "Saturated incoming air can fog dry ground during daytime");
        sample.elevation = 540;
        sample.waterLevel = Float.NaN;
        var mountain = WeatherPhysics.thermals(-1000, sample, 0.8f, 1f, 0f, 18000);
        assertTrue(mountain.condensationHeight() >= 546);
        assertTrue(Float.isFinite(mountain.temperatureC()));
        var rainyColumn = WeatherPhysics.evaluate(62, -68, 100, null, WeatherKind.RAIN, 0.8f, 0.6f, List.of(), 6000);
        float top = 232f + rainyColumn.cloudBaseOffset() + 72f + 120f * rainyColumn.coverage() + 300f * rainyColumn.stormTower();
        assertEquals(1f, WeatherPhysics.precipitationAltitudeFactor(rainyColumn, top - 1), 1e-5f);
        assertEquals(0f, WeatherPhysics.precipitationAltitudeFactor(rainyColumn, top + 33), 1e-5f);
    }

    @Test
    void everyMappedRegionSupportsWeatherAndContinentScaleField() {
        java.util.Map<String, int[]> sites = new java.util.HashMap<>();
        java.util.Set<String> provinces = new java.util.HashSet<>();
        for (int z = com.skycraft.tamriel.TamrielMap.MIN_Z; z < com.skycraft.tamriel.TamrielMap.MAX_Z; z += 32) {
            for (int x = com.skycraft.tamriel.TamrielMap.MIN_X; x < com.skycraft.tamriel.TamrielMap.MAX_X; x += 32) {
                var region = com.skycraft.tamriel.TamrielMap.region(x, z);
                if (!sites.containsKey(region.id())) sites.put(region.id(), new int[]{x, z});
                provinces.add(region.province());
            }
        }
        assertTrue(provinces.size() >= 9, "Must cover the continent, not just Skyrim");
        for (var site : sites.entrySet()) {
            for (WeatherKind kind : WeatherKind.values()) {
                for (long solar : new long[]{6000, 18000}) {
                    var c = WeatherPhysics.evaluate(site.getValue()[0], site.getValue()[1], 100.0, null, kind, 0.8f, 0.6f, List.of(), solar);
                    assertTrue(Float.isFinite(c.coverage()) && c.coverage() >= 0 && c.coverage() <= 1, site.getKey());
                    assertTrue(Float.isFinite(c.stormTower()) && c.stormTower() >= 0 && c.stormTower() <= 1, site.getKey());
                    assertTrue(Float.isFinite(c.curtainDensity()) && c.curtainDensity() >= 0 && c.curtainDensity() <= 1, site.getKey());
                    assertTrue(Float.isFinite(c.cloudBaseOffset()) && 232f + c.cloudBaseOffset() > 0f, site.getKey());
                    assertTrue(Float.isFinite(c.dewPointC()) && c.dewPointC() <= c.temperatureC() + 0.001f, site.getKey());
                }
            }
        }
        int radius = WeatherPhysics.FAR_FIELD_GRID_SIZE * WeatherPhysics.FAR_FIELD_CELL_BLOCKS / 2;
        assertTrue(radius > com.skycraft.tamriel.TamrielMap.MAX_X - com.skycraft.tamriel.TamrielMap.MIN_X);
        assertTrue(radius > com.skycraft.tamriel.TamrielMap.MAX_Z - com.skycraft.tamriel.TamrielMap.MIN_Z);
    }

    @Test
    void solarClockChangesThermalsWithoutRemovingNighttimeStorms() {
        assertEquals(1.0f, WeatherPhysics.solarHeating(6000), 1e-5f);
        assertEquals(0.0f, WeatherPhysics.solarHeating(18000), 1e-5f);
        assertEquals(WeatherPhysics.solarHeating(-6000), WeatherPhysics.solarHeating(18000), 1e-5f);
        for (WeatherKind kind : WeatherKind.values()) {
            for (int[] site : new int[][]{{0, -3400}, {-6800, 4800}, {7100, -400}, {62, -68}}) {
                var day = WeatherPhysics.evaluate(site[0], site[1], 100.0, null, kind, 0.8f, 0.6f, List.of(), 6000);
                var night = WeatherPhysics.evaluate(site[0], site[1], 100.0, null, kind, 0.8f, 0.6f, List.of(), 18000);
                assertTrue(Float.isFinite(day.coverage()) && Float.isFinite(night.coverage()));
                assertTrue(day.coverage() >= 0 && day.coverage() <= 1);
                assertTrue(night.coverage() >= 0 && night.coverage() <= 1);
                if (kind == WeatherKind.STORM || kind == WeatherKind.BLIZZARD) {
                    assertTrue(night.curtainDensity() > 0.1f, "Frontal storms must continue overnight");
                }
                if (kind == WeatherKind.ASHFALL || kind == WeatherKind.ASH_STORM || kind == WeatherKind.BLIGHT) {
                    assertEquals(WeatherPhysics.AEROSOL_ASH, day.aerosolCode(), "Light ashfall must not turn into snow or sand");
                    assertEquals(WeatherPhysics.AEROSOL_ASH, night.aerosolCode());
                }
            }
        }
        var day = WeatherPhysics.evaluate(62, -68, 100.0, null, WeatherKind.CLOUDY, 0.8f, 0.6f, List.of(), 6000);
        var night = WeatherPhysics.evaluate(62, -68, 100.0, null, WeatherKind.CLOUDY, 0.8f, 0.6f, List.of(), 18000);
        assertTrue(day.coverage() >= night.coverage(), "Fair-weather thermal clouds should settle at night");
        var clearFront = new WeatherFront(99, 62, -68, 1200, 0, 0, WeatherKind.CLEAR, 1, 1200);
        var stormFront = new WeatherFront(99, 62, -68, 1200, 0, 0, WeatherKind.STORM, 1, 1200);
        var clear = WeatherPhysics.evaluate(62, -68, 100.0, null, WeatherKind.CLEAR, 0.8f, 0.6f, List.of(clearFront), 6000);
        var storm = WeatherPhysics.evaluate(62, -68, 100.0, null, WeatherKind.CLEAR, 0.8f, 0.6f, List.of(stormFront), 6000);
        assertTrue(clear.coverage() < storm.coverage(), "A clear front must not acquire the storm overcast floor");
    }

    @Test
    void continuousLatitudeAuroraCurve() {
        double[] sampleZ = {-6500.0, -2000.0, 0.0, 2000.0, 3500.0, 6000.0, 8000.0};

        // Far north (Atmora, Z <= -6500): full polar aurora high overhead (38°..76°)
        assertEquals(1.0, AtmosphereMath.latitudeFactor(-6500.0), 1e-5);
        assertEquals(1.0f, AtmosphereMath.auroraAlphaFactor(-6500.0), 1e-5f);
        assertEquals(38.0f, AtmosphereMath.auroraElevationMinDeg(-6500.0), 1e-4f);
        assertEquals(76.0f, AtmosphereMath.auroraElevationMaxDeg(-6500.0), 1e-4f);

        // Skyrim (Z = -2000): strong northern aurora
        float alphaSkyrim = AtmosphereMath.auroraAlphaFactor(-2000.0);
        float minDegSkyrim = AtmosphereMath.auroraElevationMinDeg(-2000.0);
        float maxDegSkyrim = AtmosphereMath.auroraElevationMaxDeg(-2000.0);
        assertTrue(alphaSkyrim > 0.35f && alphaSkyrim < 1.0f, "Skyrim aurora alpha should be prominent: " + alphaSkyrim);
        assertTrue(minDegSkyrim > 9.0f && maxDegSkyrim > 24.0f);

        // Central Cyrodiil / Morrowind / High Rock (Z = 0): visible lower on the northern horizon
        float alphaMid = AtmosphereMath.auroraAlphaFactor(0.0);
        float minDegMid = AtmosphereMath.auroraElevationMinDeg(0.0);
        float maxDegMid = AtmosphereMath.auroraElevationMaxDeg(0.0);
        assertTrue(alphaMid > 0.08f && alphaMid < alphaSkyrim);
        assertTrue(minDegMid < minDegSkyrim && maxDegMid < maxDegSkyrim);

        // Southern Cyrodiil / Northern Elsweyr (Z = +3500): faint glow hugging the northern (-Z) horizon
        float alphaSouthMid = AtmosphereMath.auroraAlphaFactor(3500.0);
        float minDegSouthMid = AtmosphereMath.auroraElevationMinDeg(3500.0);
        float maxDegSouthMid = AtmosphereMath.auroraElevationMaxDeg(3500.0);
        assertTrue(alphaSouthMid > 0.0f && alphaSouthMid < alphaMid);
        assertTrue(minDegSouthMid >= 2.0f && minDegSouthMid <= 11.0f);
        assertTrue(maxDegSouthMid > minDegSouthMid && maxDegSouthMid <= 28.0f);

        // Far south (Black Marsh / Summerset / Pyandonea, Z >= +6000): aurora fully below the northern horizon
        assertEquals(0.0, AtmosphereMath.latitudeFactor(6000.0), 1e-5);
        assertEquals(0.0f, AtmosphereMath.auroraAlphaFactor(6000.0), 1e-5f);
        assertEquals(0.0f, AtmosphereMath.auroraAlphaFactor(8000.0), 1e-5f);

        // Monotonic non-increasing from north (-Z) to south (+Z) and C1 continuous (no province border step jumps)
        for (int i = 0; i < sampleZ.length - 1; i++) {
            double zNorth = sampleZ[i];
            double zSouth = sampleZ[i + 1];
            assertTrue(AtmosphereMath.latitudeFactor(zNorth) >= AtmosphereMath.latitudeFactor(zSouth));
            assertTrue(AtmosphereMath.auroraAlphaFactor(zNorth) >= AtmosphereMath.auroraAlphaFactor(zSouth));
            assertTrue(AtmosphereMath.auroraElevationMinDeg(zNorth) >= AtmosphereMath.auroraElevationMinDeg(zSouth));
            assertTrue(AtmosphereMath.auroraElevationMaxDeg(zNorth) >= AtmosphereMath.auroraElevationMaxDeg(zSouth));
        }

        for (double z = -7000.0; z < 7000.0; z += 50.0) {
            float diff = Math.abs(AtmosphereMath.auroraAlphaFactor(z + 50.0) - AtmosphereMath.auroraAlphaFactor(z));
            assertTrue(diff < 0.03f, "Aurora alpha must be smooth across Z=" + z + " (diff=" + diff + ")");
        }

        // Verify WeatherProfile delegate matches AtmosphereMath
        assertEquals(AtmosphereMath.auroraAlphaFactor(-1500.0), WeatherProfile.auroraAlphaFactor(-1500.0), 1e-5f);
    }

    @Test
    void weatherFrontAdvectionFalloffAndPacketSync() {
        for (WeatherKind kind : WeatherKind.values()) {
            WeatherFront band = new WeatherFront(7, 1000, -2000, 1200, 0, 0, kind, 1, 400);
            float density = WeatherPhysics.frontRainBandAt(band, 1000, -2000, 30);
            if (kind.precip == WeatherKind.Precip.NONE) assertEquals(0f, density, kind.id());
            else assertTrue(density > 0f, kind.id());
        }
        WeatherFront front = new WeatherFront(
                7, 1000.0f, -2000.0f, 1200.0f,
                0.25f, 0.50f, WeatherKind.BLIZZARD, 0.90f, 400
        );

        // Center vs inner core vs outer falloff vs outside radius
        float atCenter = front.intensityAt(1000.0, -2000.0);
        float atHalfRadius = front.intensityAt(1600.0, -2000.0);
        float atEdge = front.intensityAt(2190.0, -2000.0);
        float outside = front.intensityAt(2500.0, -2000.0);

        assertEquals(0.90f, atCenter, 1e-4f);
        assertTrue(atHalfRadius >= 0.40f && atHalfRadius <= atCenter);
        assertTrue(atEdge > 0.0f && atEdge < 0.15f);
        assertEquals(0.0f, outside, 1e-5f);

        // Approach factor within 200..1,600 blocks outside the edge
        float approachNearEdge = front.approachFactor(1000.0 + 1200.0 + 250.0, -2000.0);
        float approachMid = front.approachFactor(1000.0 + 1200.0 + 900.0, -2000.0);
        float approachFar = front.approachFactor(1000.0 + 1200.0 + 1800.0, -2000.0);
        assertTrue(approachNearEdge > approachMid);
        assertTrue(approachMid > 0.1f);
        assertEquals(0.0f, approachFar, 1e-5f);

        // Advection over 100 ticks
        front.advect(100);
        assertEquals(1025.0f, front.x(), 1e-3f);
        assertEquals(-1950.0f, front.z(), 1e-3f);
        assertEquals(300, front.ticksLeft());
        assertFalse(front.expired());

        // FriendlyByteBuf & WeatherPackets.Sync round-trip
        WeatherFront ashFront = new WeatherFront(
                8, 3200.0f, -1800.0f, 950.0f,
                -0.20f, 0.15f, WeatherKind.ASH_STORM, 0.85f, 1200
        );
        WeatherPackets.Sync original = new WeatherPackets.Sync(
                true,
                List.of(new WeatherPackets.Entry("vvardenfell", (byte) WeatherKind.ASH_STORM.ordinal(), (short) 215)),
                List.of(front, ashFront)
        );
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        WeatherPackets.Sync.encode(original, buf);
        WeatherPackets.Sync decoded = WeatherPackets.Sync.decode(buf);

        assertTrue(decoded.full());
        assertEquals(1, decoded.entries().size());
        assertEquals("vvardenfell", decoded.entries().get(0).region());
        assertEquals(2, decoded.fronts().size());
        WeatherFront d0 = decoded.fronts().get(0);
        assertEquals(7, d0.id());
        assertEquals(1025.0f, d0.x(), 1e-3f);
        assertEquals(-1950.0f, d0.z(), 1e-3f);
        assertEquals(1200.0f, d0.radius(), 1e-3f);
        assertEquals(WeatherKind.BLIZZARD, d0.kind());
        assertEquals(300, d0.ticksLeft());

        // CompoundTag save/load round-trip
        CompoundTag tag = ashFront.save();
        WeatherFront loaded = WeatherFront.load(tag);
        assertEquals(ashFront.id(), loaded.id());
        assertEquals(ashFront.x(), loaded.x(), 1e-3f);
        assertEquals(ashFront.z(), loaded.z(), 1e-3f);
        assertEquals(WeatherKind.ASH_STORM, loaded.kind());

        // RegionalWeather synoptic front seeding and sampling
        List<WeatherFront> seeded = RegionalWeather.seedInitialFronts(12345L);
        assertFalse(seeded.isEmpty());
        WeatherFront first = seeded.get(0);
        assertSame(first, RegionalWeather.sampleFront(seeded, first.x(), first.z()));
    }

    @Test
    void npcScheduleSeeksShelterInSevereStorms() {
        // Fair weather: outdoor daytime workers work, citizens wander, children play
        assertEquals(Schedule.Activity.WORK, Schedule.activityAt(NpcRole.FARMER, 10, false, WeatherKind.CLEAR));
        assertEquals(Schedule.Activity.WORK, Schedule.activityAt(NpcRole.LUMBERJACK, 14, true, WeatherKind.CLEAR));
        assertEquals(Schedule.Activity.WANDER, Schedule.activityAt(NpcRole.CITIZEN, 10, true, WeatherKind.CLEAR));
        assertEquals(Schedule.Activity.PLAY, Schedule.activityAt(NpcRole.CHILD, 10, true, WeatherKind.CLEAR));

        // Severe weather (BLIZZARD, SANDSTORM, ASHSTORM/ASH_STORM, BLIGHT): outdoor roles shelter indoors (HOME/REST or INN/SOCIAL)
        for (WeatherKind storm : List.of(WeatherKind.BLIZZARD, WeatherKind.SANDSTORM, WeatherKind.ASHSTORM, WeatherKind.BLIGHT)) {
            Schedule.Activity farmerHome = Schedule.activityAt(NpcRole.FARMER, 10, false, storm);
            Schedule.Activity farmerSocial = Schedule.activityAt(NpcRole.FARMER, 10, true, storm);
            Schedule.Activity citizenShelter = Schedule.activityAt(NpcRole.CITIZEN, 15, false, storm);
            Schedule.Activity childShelter = Schedule.activityAt(NpcRole.CHILD, 11, true, storm);

            assertEquals(Schedule.Activity.REST, farmerHome);
            assertTrue(farmerHome.isIndoors());
            assertEquals(Schedule.Activity.SOCIAL, farmerSocial);
            assertTrue(farmerSocial.isIndoors());
            assertTrue(citizenShelter.isIndoors());
            assertEquals(Schedule.Activity.HOME, childShelter);

            // Protectors/guards remain on duty even in severe storms
            assertEquals(Schedule.Activity.WORK, Schedule.activityAt(NpcRole.HOUSECARL, 10, true, storm));
            assertEquals(Schedule.Activity.WANDER, Schedule.activityAt(NpcRole.IMPERIAL_SOLDIER, 10, true, storm));
        }
    }

    @Test
    void regionSpawnerMapsRegionalWeatherToSpawnTables() {
        assertEquals(SpawnTables.Weather.STORM, RegionSpawner.toSpawnWeather(WeatherKind.BLIZZARD, false, false));
        assertEquals(SpawnTables.Weather.STORM, RegionSpawner.toSpawnWeather(WeatherKind.SANDSTORM, false, false));
        assertEquals(SpawnTables.Weather.STORM, RegionSpawner.toSpawnWeather(WeatherKind.ASH_STORM, false, false));
        assertEquals(SpawnTables.Weather.STORM, RegionSpawner.toSpawnWeather(WeatherKind.BLIGHT, false, false));
        assertEquals(SpawnTables.Weather.STORM, RegionSpawner.toSpawnWeather(WeatherKind.STORM, false, false));
        assertEquals(SpawnTables.Weather.RAIN, RegionSpawner.toSpawnWeather(WeatherKind.RAIN, false, false));
        assertEquals(SpawnTables.Weather.RAIN, RegionSpawner.toSpawnWeather(WeatherKind.SNOW, false, false));
        assertEquals(SpawnTables.Weather.RAIN, RegionSpawner.toSpawnWeather(WeatherKind.ASHFALL, false, false));
        assertEquals(SpawnTables.Weather.CLEAR, RegionSpawner.toSpawnWeather(WeatherKind.CLEAR, false, false));
    }

    @Test
    void oculusShaderCompatibilityConfigDefaults() {
        assertFalse(AtmosphereConfig.SKIP_WITH_SHADERS.getDefault(),
                "SKIP_WITH_SHADERS should default to false so Masser, Secunda, galaxy, and aurora render under Oculus");
        assertFalse(AtmosphereConfig.CLOUDS_SKIP_SHADERS.getDefault(),
                "CLOUDS_SKIP_SHADERS should default to false so Skycraft regional clouds render under Complementary");
    }

    @Test
    void landscapeDrivenWeatherPhysicsAndRegionalProfiles() {
        // 1. Marine/coastal moisture (Sea of Ghosts / Iliac Bay) exceeds arid desert interior (Alik'r)
        com.skycraft.tamriel.MapSample sample = new com.skycraft.tamriel.MapSample();
        float seaMoisture = WeatherPhysics.moistureAt(0, -3400, sample);
        float desertMoisture = WeatherPhysics.moistureAt(-6800, 4800, sample);
        assertTrue(seaMoisture > desertMoisture,
                "Marine moisture (" + seaMoisture + ") must exceed arid desert moisture (" + desertMoisture + ")");

        // 2. Mesoscale rain bands & mesocyclone spiral structure
        WeatherFront mesoStorm = new WeatherFront(
                2, -500.0f, 1200.0f, 1100.0f,
                0.40f, 0.15f, WeatherKind.STORM, 0.92f, 1200
        );
        WeatherFront squallStorm = new WeatherFront(
                3, -500.0f, 1200.0f, 1100.0f,
                0.40f, 0.15f, WeatherKind.STORM, 0.78f, 1200
        );
        assertTrue(WeatherPhysics.isMesocyclone(mesoStorm));
        assertFalse(WeatherPhysics.isMesocyclone(squallStorm));

        float mesoBandCore = mesoStorm.rainBandAt(-420.0, 1240.0, 100.0f);
        float mesoCurtainCore = mesoStorm.curtainDensityAt(-420.0, 1240.0, 100.0f);
        float squallBandCore = squallStorm.rainBandAt(-280.0, 1280.0, 100.0f);
        assertTrue(mesoBandCore > 0.15f, "Mesocyclone wall-cloud/hook band should be active near core: " + mesoBandCore);
        assertTrue(mesoCurtainCore > 0.10f, "Mesocyclone precipitation curtain should be dense near core: " + mesoCurtainCore);
        assertTrue(squallBandCore > 0.15f, "Squall line leading rain band should be active: " + squallBandCore);
        assertEquals(0.0f, mesoStorm.rainBandAt(5000.0, 5000.0, 100.0f), 1e-5f);

        // 3. Physical front evolution over moisture vs arid rainshadow
        WeatherFront coastalFront = new WeatherFront(
                10, 0.0f, -3400.0f, 1000.0f,
                0.30f, 0.20f, WeatherKind.STORM, 0.65f, 800
        );
        WeatherPhysics.advectAndEvolveFront(coastalFront, 40);
        assertTrue(coastalFront.intensity() >= 0.65f,
                "Storm front over open water should maintain or intensify moisture: " + coastalFront.intensity());

        // 4. Regional Elder Scrolls WeatherProfile mappings
        assertEquals(WeatherProfile.WHITERUN, WeatherProfile.of(com.skycraft.tamriel.Regions.get("skyrim/whiterun")));
        assertEquals(WeatherProfile.WINTERHOLD, WeatherProfile.of(com.skycraft.tamriel.Regions.get("skyrim/winterhold")));
        assertEquals(WeatherProfile.RED_MOUNTAIN, WeatherProfile.of(com.skycraft.tamriel.Regions.get("morrowind/red_mountain")));
        assertEquals(WeatherProfile.GOLD_COAST, WeatherProfile.of(com.skycraft.tamriel.Regions.get("cyrodiil/gold_coast")));
        assertEquals(WeatherProfile.HEARTLANDS, WeatherProfile.of(com.skycraft.tamriel.Regions.get("cyrodiil/heartlands")));
        assertEquals(WeatherProfile.BLACKWOOD, WeatherProfile.of(com.skycraft.tamriel.Regions.get("cyrodiil/blackwood")));
        assertEquals(WeatherProfile.DAGGERFALL_COAST, WeatherProfile.of(com.skycraft.tamriel.Regions.get("high_rock/daggerfall")));
        assertEquals(WeatherProfile.ALIKR, WeatherProfile.of(com.skycraft.tamriel.Regions.get("hammerfell/alikr_desert")));
        assertEquals(WeatherProfile.ANEQUINA, WeatherProfile.of(com.skycraft.tamriel.Regions.get("elsweyr/anequina")));
        assertEquals(WeatherProfile.PELLITINE, WeatherProfile.of(com.skycraft.tamriel.Regions.get("elsweyr/pellitine")));
    }
}


