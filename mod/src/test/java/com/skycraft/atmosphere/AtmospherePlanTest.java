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


