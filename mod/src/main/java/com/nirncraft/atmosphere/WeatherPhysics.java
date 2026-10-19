package com.nirncraft.atmosphere;

import com.nirncraft.atmosphere.weather.WeatherKind;
import com.nirncraft.atmosphere.weather.WeatherProfile;
import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.TamrielMap;

import java.util.Collection;
import java.util.Random;

/**
 * Physical landscape- and hydrology-driven meteorology for Tamriel.
 *
 * <p>Weather across Nirn is governed by the underlying terrain and water bodies ({@link TamrielMap}):
 * <ul>
 *   <li><b>Evaporation &amp; Moisture Flux</b>: Oceans, bays (Iliac Bay, Topal Bay, Sea of Ghosts, Abecean, Padomaic),
 *       lakes, major rivers, and marshes supply water vapour to the lower troposphere, modulated by temperature/latitude
 *       and onshore wind fetch.</li>
 *   <li><b>Prevailing Wind Belts &amp; Topographic Deflection</b>: Polar easterlies/northerlies off the Sea of Ghosts,
 *       mid-latitude westerlies off the Iliac Bay and Abecean Sea, and southern trade/monsoon winds off Topal Bay and
 *       the Padomaic are deflected by mountain gradients and coastal sea-breeze convergence.</li>
 *   <li><b>Orographic Lift &amp; Leeward Rainshadow</b>: When moist air is forced upslope against mountain ranges
 *       (Jeralls, Velothi, Druadach, Wrothgar, Dragontail, Eton Nir, Valus, Colovian Highlands), adiabatic cooling
 *       condenses clouds into towering cumulonimbus and windward precipitation curtains; descending air on the leeward
 *       side warms and dries (Foehn/rainshadow), breaking apart cloud decks.</li>
 *   <li><b>Synoptic Cyclogenesis &amp; Mesoscale Rain Bands</b>: Moving {@link WeatherFront}s form over physical source
 *       regions, strengthen over warm water and windward slopes, organize into curved mesoscale rain bands and squall
 *       lines, and dump sloping curtains of rain, snow, haboob dust, or volcanic ash as they cross the landscape.</li>
 * </ul>
 *
 * <p>Pure common logic with zero client dependencies; safe to call on server, client, worker threads, and Tier 0 tests.</p>
 */
public final class WeatherPhysics {

    /** World-space coverage of the toroidal weather field texture (128 cells * 64 blocks = 8192 blocks). */
    public static final int FIELD_CELL_BLOCKS = 64;
    public static final int FAR_FIELD_GRID_SIZE = 128;
    public static final int FAR_FIELD_CELL_BLOCKS = 512;
    public static final int FIELD_GRID_SIZE = 128;
    public static final int FIELD_WORLD_SPAN = FIELD_CELL_BLOCKS * FIELD_GRID_SIZE; // 8192

    /** Aerosol / precipitation regime encoded in the alpha channel of the shader weather field. */
    public static final float AEROSOL_WATER = 0.12f;
    public static final float AEROSOL_SNOW = 0.42f;
    public static final float AEROSOL_SAND = 0.70f;
    public static final float AEROSOL_ASH = 0.92f;

    private static final ThreadLocal<MapSample> SCRATCH_A = ThreadLocal.withInitial(MapSample::new);
    private static final ThreadLocal<MapSample> SCRATCH_B = ThreadLocal.withInitial(MapSample::new);
    private static final ThreadLocal<float[]> SCRATCH_FACTORS = ThreadLocal.withInitial(() -> new float[2]);

    private WeatherPhysics() {}

    /**
     * Evaluated physical meteorology at a world column {@code (x, z)}.
     *
     * @param moisture       0..1 lower-troposphere moisture availability from oceans, lakes, rivers, wetlands
     * @param orographicLift 0..1 windward upslope condensation drive
     * @param leewardDry     0..1 leeward downslope + rainshadow drying factor
     * @param windX          local terrain-adjusted unit wind X
     * @param windZ          local terrain-adjusted unit wind Z
     * @param windSpeed      0..1 local wind speed
     * @param coverage       0..1 total 3D cloud coverage (stratus + cumulus + anvil canopy)
     * @param stormTower     0..1 vertical cumulonimbus / anvil growth and cloud-base darkening
     * @param rainBand       0..1 mesoscale rain-band / squall line structure factor
     * @param curtainDensity 0..1 sub-cloud precipitation shaft / curtain optical density
     * @param aerosolCode    0..1 encoded regime (water rain, snow/blizzard, desert sand/haboob, volcanic ash/blight)
     * @param cloudBaseOffset blocks offset applied to cloud base height (negative under heavy rain bands / windward slopes)
     * @param cloudTopBoost  blocks boost applied to convective cloud tops
     */
    public record ColumnState(
            float moisture,
            float orographicLift,
            float leewardDry,
            float windX,
            float windZ,
            float windSpeed,
            float coverage,
            float stormTower,
            float rainBand,
            float curtainDensity,
            float aerosolCode,
            float cloudBaseOffset,
            float cloudTopBoost,
            float surfaceHeight,
            float temperatureC,
            float relativeHumidity,
            float dewPointC,
            float groundFog
    ) {}

    /** A scaled lifting-condensation model: temperatures are Celsius, height is in game blocks. */
    public record Thermals(float temperatureC, float relativeHumidity, float dewPointC, float condensationHeight, float groundFog) {}

    /** Charge separation requires deep, precipitating convection, not merely a storm label.
     * Water storms additionally need a plausible supercooled/mixed-phase depth.
     * Dense turbulent ash can electrify by particle collisions without an ice layer. */
    public static float electricalActivity(ColumnState column, float chargeDrive) {
        if (chargeDrive <= 0.05f) return 0f;
        float depth = 72f + 120f * column.coverage() + 300f * column.stormTower();
        float deep = smooth(0.48f, 0.82f, column.stormTower()) * smooth(0.55f, 0.84f, column.coverage());
        float precipitation = smooth(0.28f, 0.68f, column.rainBand());
        if (column.aerosolCode() > 0.80f) {
            return clamp01(chargeDrive * deep * precipitation * smooth(0.35f, 0.75f, column.windSpeed()));
        }
        float baseAir = column.temperatureC() - Math.max(0f, 232f + column.cloudBaseOffset() - column.surfaceHeight()) * 0.045f;
        float topAir = baseAir - depth * 0.085f;
        float mixedPhase = smooth(-28f, -15f, baseAir) * (1f - smooth(-6f, 2f, topAir));
        return clamp01(chargeDrive * deep * precipitation * mixedPhase);
    }

    /** Rain and falling snow stop above their cloud source; drifting surface snow
     * in a blizzard is handled separately by the client. */
    public static float precipitationAltitudeFactor(ColumnState column, double eyeY) {
        float top = 232f + column.cloudBaseOffset() + 72f + 120f * column.coverage() + 300f * column.stormTower();
        return 1f - smooth(top, top + 32f, (float) eyeY);
    }

    public static Thermals thermals(double z, MapSample s, float moisture, float wetPrecip, float fogDrive, long dayTime) {
        float solar = solarHeating(dayTime);
        float surface = Math.max(TamrielMap.SEA_LEVEL, Math.max(s.elevation, Float.isFinite(s.waterLevel) ? s.waterLevel : s.elevation));
        float temperature = (float) (28.0 - 42.0 * AtmosphereMath.latitudeFactor(z))
                - (surface - TamrielMap.SEA_LEVEL) * 0.018f + 6f * solar - 4f * (1f - solar) + s.heat * 16f;
        float humidity = clamp01(0.30f + 0.52f * moisture + 0.07f * s.wetland
                + 0.12f * (1f - solar) * moisture - 0.08f * solar * (1f - moisture) + 0.10f * wetPrecip);
        humidity = Math.max(0.16f, Math.min(0.995f, lerp(clamp01(fogDrive), humidity, 0.995f)));
        // Magnus approximation; using the same moisture parcel for both values makes the
        // condensation height respond smoothly to daytime drying and nighttime saturation.
        float gamma = (float) Math.log(humidity) + 17.625f * temperature / (243.04f + temperature);
        float dewPoint = 243.04f * gamma / (17.625f - gamma);
        float gap = Math.max(0f, temperature - dewPoint);
        float condensation = Math.max(6f, Math.min(440f, gap * 45f));
        // Advected saturated air carries its own moisture over locally dry ground.
        float fog = (1f - smooth(0.25f, 1.4f, gap)) * smooth(0.45f, 0.85f, Math.max(moisture, fogDrive * 0.9f));
        return new Thermals(temperature, humidity, dewPoint, surface + condensation, fog);
    }

    /**
     * Computes prevailing wind vector {@code (wx, wz)} at world coordinates {@code (x, z)}, combining Nirn's
     * latitude circulation belts with coastal sea-breeze convergence and mountain barrier deflection.
     */
    public static void prevailingWind(double x, double z, float regionalWindDeg, boolean hasRegionalWind, float[] outVec2) {
        double lat = AtmosphereMath.latitudeFactor(z);
        // Base synoptic circulation belt:
        // North (Atmora / Sea of Ghosts, lat > 0.65): polar NNW/NNE gales blowing SSE (+z, slight +x)
        // Mid-latitudes (Skyrim, High Rock, Hammerfell, Cyrodiil, Morrowind): westerlies blowing ENE (+x, slight +z)
        // South (Elsweyr, Valenwood, Black Marsh, Summerset, lat < 0.25): trade/monsoon winds blowing WNW/NNW
        float beltX, beltZ;
        if (lat >= 0.65) {
            float t = (float) ((lat - 0.65) / 0.35);
            beltX = lerp(t, 0.72f, 0.32f);
            beltZ = lerp(t, 0.69f, 0.95f);
        } else if (lat >= 0.25) {
            float t = (float) ((lat - 0.25) / 0.40);
            beltX = lerp(t, 0.86f, 0.72f);
            beltZ = lerp(t, -0.18f, 0.69f);
        } else {
            float t = (float) (lat / 0.25);
            beltX = lerp(t, -0.62f, 0.86f);
            beltZ = lerp(t, -0.78f, -0.18f);
        }

        if (hasRegionalWind && regionalWindDeg >= 0f) {
            float rad = regionalWindDeg * ((float) Math.PI / 180f);
            float rx = -(float) Math.sin(rad);
            float rz = (float) Math.cos(rad);
            beltX = lerp(0.65f, beltX, rx);
            beltZ = lerp(0.65f, beltZ, rz);
        }

        // Topographic deflection: steep mountain slopes deflect part of the flow along the contour
        MapSample s = SCRATCH_B.get();
        TamrielMap.sample((int) Math.floor(x), (int) Math.floor(z), s);
        if (s.slope > 0.08f && (Math.abs(s.aspectX) > 1e-3f || Math.abs(s.aspectZ) > 1e-3f)) {
            // aspect points downslope; when wind blows steeply upslope, deflect slightly around the ridge
            float dot = -(beltX * s.aspectX + beltZ * s.aspectZ);
            if (dot > 0f) {
                float deflect = Math.min(0.45f, s.slope * 0.6f) * dot;
                beltX += s.aspectX * deflect;
                beltZ += s.aspectZ * deflect;
            }
        }

        float len = (float) Math.sqrt(beltX * beltX + beltZ * beltZ);
        if (len < 1e-4f) {
            outVec2[0] = 0.8f;
            outVec2[1] = 0.6f;
        } else {
            outVec2[0] = beltX / len;
            outVec2[1] = beltZ / len;
        }
    }

    /**
     * Computes the surface &amp; boundary-layer moisture availability in {@code [0, 1]} at {@code (x, z)} from
     * {@link TamrielMap} oceans, bays, lakes, rivers, wetlands, temperature, and rainshadow.
     */
    public static float moistureAt(int x, int z, MapSample s) {
        TamrielMap.sample(x, z, s);
        TamrielMap.deriveStrengths(s);
        return moistureFromSample(x, z, s);
    }

    public static float moistureFromSample(int x, int z, MapSample s) {
        double lat = AtmosphereMath.latitudeFactor(z);
        // Warmer low-latitude seas evaporate more moisture than polar seas, though polar lows still carry snow moisture
        float tempEvap = (float) (0.72 + 0.28 * (1.0 - 0.55 * lat));

        // 1. Marine moisture: open sea (coastDistance <= 0) and coastal marine layer penetrating inland
        float marine;
        if (s.coastDistance <= 0f) {
            float depthBoost = clamp01(-s.coastDistance / 400f);
            marine = (0.78f + 0.20f * depthBoost) * tempEvap;
        } else {
            marine = (float) Math.exp(-s.coastDistance / 950.0) * 0.72f * tempEvap;
        }

        // 2. Freshwater hydrology: lakes, major rivers, and marshes/wetlands
        float lakeMoisture = s.lakeDistance >= 0f
                ? 0.78f
                : (s.lakeDistance > -360f ? (1f + s.lakeDistance / 360f) * 0.48f : 0f);
        float riverMoisture = s.riverHalfWidth >= 2.5f && s.riverDistance < 280f
                ? (1f - s.riverDistance / 280f) * Math.min(0.52f, 0.18f + 0.03f * s.riverHalfWidth)
                : 0f;
        float wetlandMoisture = s.wetland * 0.75f;
        float steamMoisture = s.heat * 0.38f;

        float raw = Math.max(marine, Math.max(lakeMoisture, Math.max(riverMoisture, wetlandMoisture)))
                + 0.28f * (lakeMoisture + riverMoisture + wetlandMoisture + steamMoisture);

        // 3. Continental aridity and leeward rainshadow drying
        float duneDry = s.dunes * 0.52f;
        float shadowDry = clamp01((s.rainshadow - 0.25f) / 0.75f) * 0.42f;
        float baseline = 0.24f * (1f - 0.5f * s.dunes);
        return clamp01(Math.max(baseline, raw) * (1f - 0.55f * duneDry) * (1f - 0.45f * shadowDry));
    }

    /**
     * Computes windward orographic lift (0..1) and leeward rainshadow/Foehn drying (0..1) at {@code (x, z)}
     * for a wind blowing in unit direction {@code (windX, windZ)}.
     *
     * <p>Combines the exact Catmull-Rom local slope gradient with a 220-block mesoscale upwind-to-downwind elevation
     * difference so broad mountain ranges (Jerall, Velothi, Druadach, Wrothgar, Dragontail, Summerset peaks) generate
     * realistic windward condensation and leeward cloud breaks.</p>
     */
    public static void orographicFactors(int x, int z, float windX, float windZ, MapSample center, float[] outLiftDry) {
        // Local gradient: aspect points downslope (-gradE / |gradE|), so upslope = -aspect * slope
        float localUpslope = center.slope * (-(windX * center.aspectX + windZ * center.aspectZ));

        // Mesoscale gradient across 440 blocks along the wind vector
        MapSample up = SCRATCH_B.get();
        int step = 220;
        int downX = x + Math.round(windX * step);
        int downZ = z + Math.round(windZ * step);
        TamrielMap.sample(downX, downZ, up);
        float eDownwind = Math.max(TamrielMap.SEA_LEVEL, up.elevation);

        int upX = x - Math.round(windX * step);
        int upZ = z - Math.round(windZ * step);
        TamrielMap.sample(upX, upZ, up);
        float eUpwind = Math.max(TamrielMap.SEA_LEVEL, up.elevation);

        float mesoSlope = (eDownwind - eUpwind) / (2.0f * step);
        float combinedUpslope = 0.55f * localUpslope + 0.45f * mesoSlope;

        float altFactor = smooth(85f, 260f, Math.max(center.elevation, eDownwind));
        float lift = 0f;
        float leeDry = clamp01((center.rainshadow - 0.25f) * 1.25f);

        if (combinedUpslope > 0.008f) {
            lift = clamp01((combinedUpslope - 0.008f) * 6.5f) * (0.45f + 0.55f * altFactor);
        } else if (combinedUpslope < -0.008f) {
            float downslope = clamp01((-combinedUpslope - 0.008f) * 5.8f) * (0.4f + 0.6f * altFactor);
            leeDry = clamp01(Math.max(leeDry, downslope));
        }

        // High mountain summits also trigger standing cap/lenticular condensation
        if (center.elevation > 185f) {
            float summit = smooth(185f, 340f, center.elevation) * 0.38f;
            lift = Math.max(lift, summit);
        }

        outLiftDry[0] = clamp01(lift);
        outLiftDry[1] = clamp01(leeDry);
    }

    /**
     * True if {@code front} is a rotating supercell / mesocyclone system (spiral inflow bands, hook echo, overshooting
     * anvil dome, and intense cloud-to-cloud lightning), false if it is a classic linear/bowed frontal squall line.
     */
    public static boolean isMesocyclone(WeatherFront front) {
        if (front == null) return false;
        WeatherKind k = front.kind();
        if (k != WeatherKind.STORM && k != WeatherKind.ASH_STORM && k != WeatherKind.BLIGHT && k != WeatherKind.BLIZZARD) {
            return false;
        }
        return ((front.id() * 0x9E3779B9) & 1) == 0 || front.intensity() >= 0.88f;
    }

    /**
     * Evaluates the curved mesoscale rain-band / squall or mesocyclone spiral structure inside {@code front} at world
     * position {@code (px, pz)} and time {@code timeSec}.
     *
     * <p>Depending on {@link #isMesocyclone(WeatherFront)}, systems organize either into a rotating mesocyclone with
     * spiral feeder bands and a hook echo, or into a bowed frontal squall line with trailing wave rain bands.</p>
     */
    public static float frontRainBandAt(WeatherFront front, double px, double pz, double timeSec) {
        if (front == null || front.expired() || front.kind().precip == WeatherKind.Precip.NONE) return 0f;
        float baseEnv = front.intensityAt(px, pz);
        if (baseEnv <= 0.005f) return 0f;

        float vx = front.vx();
        float vz = front.vz();
        float vlen = (float) Math.sqrt(vx * vx + vz * vz);
        float dirX = vlen > 1e-4f ? vx / vlen : 0.7071f;
        float dirZ = vlen > 1e-4f ? vz / vlen : 0.7071f;
        float perpX = -dirZ;
        float perpZ = dirX;

        // Coordinates relative to the front center in wind-aligned frame, normalized by radius (-1..1)
        float invR = 1.0f / Math.max(200.0f, front.radius());
        float dx = (float) (px - front.x()) * invR;
        float dz = (float) (pz - front.z()) * invR;
        float along = dx * dirX + dz * dirZ;
        float cross = dx * perpX + dz * perpZ;

        // Domain warp from seeded mesoscale turbulence so bands look organic and never geometric
        double warp = (vnoise(px / 380.0 + timeSec * 0.008, pz / 380.0, front.id() * 17 + 3) - 0.5) * 0.32;
        float cells = 0.65f * vnoise(px / 240.0 - dirX * timeSec * 0.02, pz / 240.0 - dirZ * timeSec * 0.02, front.id() * 31 + 9)
                + 0.35f * vnoise(px / 110.0, pz / 110.0, front.id() * 31 + 10);

        float structure;
        if (isMesocyclone(front)) {
            // Rotating mesocyclone: tight cyclonic vortex core + logarithmic spiral feeder rain bands + hook echo
            float rNorm = (float) Math.sqrt(along * along + cross * cross);
            float angle = (float) Math.atan2(cross, along);
            float spiralArm1 = 0.5f + 0.5f * (float) Math.cos(angle * 2.0f + rNorm * 11.5f - timeSec * 0.032 + warp * 4.5);
            float spiralArm2 = 0.5f + 0.5f * (float) Math.cos(angle * 1.0f + rNorm * 8.0f - timeSec * 0.024 + 1.7f + warp * 3.5);
            spiralArm1 *= spiralArm1;
            spiralArm2 *= spiralArm2 * 0.85f;
            // Wall cloud & hook echo ring around rNorm ~ 0.14..0.38, with a small vault dip right at rNorm < 0.07
            float wallRing = smooth(0.03f, 0.14f, rNorm) * (1.0f - 0.55f * smooth(0.28f, 0.58f, rNorm));
            structure = Math.max(wallRing, Math.max(spiralArm1, spiralArm2) * 0.88f) * (0.65f + 0.55f * cells);
        } else {
            // Classic frontal squall line: parabolic bow echo + trailing parallel wave rain bands
            float bowedAlong = along + 0.38f * cross * cross;
            float bandCoord = (float) (bowedAlong + warp);
            float leadingSquall = (float) Math.exp(-Math.pow((bandCoord - 0.30f) / 0.22f, 2.0));
            float wavePhase = bandCoord * 11.0f - (float) (timeSec * 0.022) + (front.id() & 7) * 1.1f;
            float waveBands = 0.5f + 0.5f * (float) Math.cos(wavePhase);
            waveBands *= waveBands;
            structure = Math.max(leadingSquall * 0.95f, waveBands * 0.80f) * (0.62f + 0.58f * cells);
        }
        return clamp01(baseEnv * (0.28f + 0.92f * structure));
    }

    /**
     * Full physical column evaluation at world {@code (x, z)}, combining {@link TamrielMap} hydrology &amp; relief,
     * regional weather parameters, and active synoptic {@link WeatherFront}s.
     *
     * @param x            world block X
     * @param z            world block Z
     * @param timeSec      world time in seconds
     * @param regionParams eased 13-element {@code CloudClimate} array for the region (or {@code null} to derive from {@code kind})
     * @param fallbackKind regional {@link WeatherKind} when {@code regionParams} is null
     * @param windX        current wind X
     * @param windZ        current wind Z
     * @param fronts       active synoptic weather fronts (nullable)
     */
    public static ColumnState evaluate(
            double x,
            double z,
            double timeSec,
            float[] regionParams,
            WeatherKind fallbackKind,
            float windX,
            float windZ,
            Collection<WeatherFront> fronts
    ) {
        return evaluate(x, z, timeSec, regionParams, fallbackKind, windX, windZ, fronts, (long) (timeSec * 20.0));
    }

    /** The animation clock and solar clock are separate: sleeping and /time must change heating without jumping fronts. */
    public static ColumnState evaluate(double x, double z, double timeSec, float[] regionParams,
                                       WeatherKind fallbackKind, float windX, float windZ,
                                       Collection<WeatherFront> fronts, long dayTime) {
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        MapSample s = SCRATCH_A.get();
        TamrielMap.sample(ix, iz, s);
        TamrielMap.deriveStrengths(s);

        Region region = TamrielMap.region(ix, iz);
        WeatherProfile profile = WeatherProfile.of(region);

        float wl = (float) Math.sqrt(windX * windX + windZ * windZ);
        float wx = wl > 1e-4f ? windX / wl : 0.8f;
        float wz = wl > 1e-4f ? windZ / wl : 0.6f;
        if (wl <= 1e-4f) {
            float[] wv = SCRATCH_FACTORS.get();
            prevailingWind(x, z, -1f, false, wv);
            wx = wv[0];
            wz = wv[1];
        } else if (s.slope > 0.08f) {
            float intoSlope = -(wx * s.aspectX + wz * s.aspectZ);
            if (intoSlope > 0f) {
                float deflect = Math.min(0.55f, s.slope * 0.7f) * intoSlope;
                wx += s.aspectX * deflect;
                wz += s.aspectZ * deflect;
                float length = (float) Math.sqrt(wx * wx + wz * wz);
                if (length > 1e-4f) { wx /= length; wz /= length; }
            }
        }

        float moisture = moistureFromSample(ix, iz, s);
        float[] liftDry = SCRATCH_FACTORS.get();
        orographicFactors(ix, iz, wx, wz, s, liftDry);
        float orographicLift = liftDry[0] * (0.45f + 0.55f * moisture);
        float leewardDry = liftDry[1];

        // Extract base regional weather signals
        WeatherKind baseKind = fallbackKind != null ? fallbackKind : WeatherKind.CLEAR;
        float cu, st, cb, rain, snow, ash, sand, dark, blight, gale;
        if (regionParams != null && regionParams.length >= 13) {
            cu = regionParams[0];
            st = regionParams[1];
            cb = regionParams[2];
            rain = regionParams[4];
            snow = regionParams[5];
            ash = regionParams[6];
            sand = regionParams[7];
            dark = regionParams[9];
            blight = regionParams[10];
            gale = regionParams[12];
        } else {
            cu = baseKind == WeatherKind.CLOUDY ? 0.55f : (baseKind == WeatherKind.CLEAR ? 0.22f : 0.18f);
            st = baseKind.clouds * 0.88f;
            cb = baseKind == WeatherKind.STORM ? 0.95f : (baseKind == WeatherKind.RAIN ? 0.28f : (baseKind == WeatherKind.ASH_STORM || baseKind == WeatherKind.BLIGHT ? 0.48f : 0f));
            rain = baseKind.precip == WeatherKind.Precip.RAIN ? baseKind.amount : 0f;
            snow = baseKind.precip == WeatherKind.Precip.SNOW ? baseKind.amount : 0f;
            ash = baseKind.precip == WeatherKind.Precip.ASH ? baseKind.amount : 0f;
            sand = baseKind.precip == WeatherKind.Precip.SAND ? baseKind.amount : 0f;
            dark = baseKind == WeatherKind.STORM ? 0.90f : (baseKind.wet() ? 0.58f : 0.10f);
            blight = baseKind == WeatherKind.BLIGHT ? 1.0f : 0f;
            gale = baseKind.wind;
        }

        // Synoptic WeatherFront influence + curved mesoscale rain bands
        float frontEnvMax = 0f;
        float frontBandMax = 0f;
        WeatherFront domFront = null;
        if (fronts != null) {
            for (WeatherFront f : fronts) {
                if (f == null || f.expired()) continue;
                float env = f.intensityAt(x, z);
                if (env > 0.005f) {
                    float band = frontRainBandAt(f, x, z, timeSec);
                    if (env > frontEnvMax) {
                        frontEnvMax = env;
                        domFront = f;
                    }
                    if (band > frontBandMax) {
                        frontBandMax = band;
                    }
                }
            }
        }

        if (domFront != null && frontEnvMax > 0.01f) {
            WeatherKind fk = domFront.kind();
            st = lerp(frontEnvMax, st, fk.clouds);
            dark = lerp(frontEnvMax, dark, fk == WeatherKind.STORM ? 0.92f : fk.clouds * 0.68f);
            gale = lerp(frontEnvMax, gale, fk.wind);
            if (fk == WeatherKind.STORM || fk == WeatherKind.BLIZZARD || fk == WeatherKind.ASH_STORM || fk == WeatherKind.BLIGHT) {
                cb = Math.max(cb, frontEnvMax * 0.72f + frontBandMax * 0.35f);
            } else if (fk == WeatherKind.RAIN || fk == WeatherKind.SNOW) {
                cb = Math.max(cb, frontBandMax * 0.55f);
            }
            switch (fk.precip) {
                case RAIN -> rain = Math.max(rain, frontEnvMax * 0.55f + frontBandMax * 0.55f);
                case SNOW -> snow = Math.max(snow, frontEnvMax * 0.55f + frontBandMax * 0.55f);
                case ASH -> ash = Math.max(ash, frontEnvMax * 0.65f + frontBandMax * 0.45f);
                case SAND -> sand = Math.max(sand, frontEnvMax * 0.70f + frontBandMax * 0.40f);
                default -> {}
            }
            if (fk == WeatherKind.BLIGHT) blight = Math.max(blight, frontEnvMax);
        }

        // Landscape & Hydrology modulation:
        // 1. Marine & Lake moisture feeds cumulus/stratocumulus; windward orographic lift builds cumulus -> cumulonimbus -> rain curtains
        float marineLayer = s.coastDistance < 380f ? clamp01(1f - Math.max(0f, s.coastDistance) / 380f) * 0.22f : 0f;
        float wetlandMist = s.wetland * 0.20f;
        float orographicCloud = orographicLift * (0.42f + 0.38f * moisture);
        float orographicTower = orographicLift * smooth(0.32f, 0.85f, moisture + st * 0.5f) * 0.72f;

        // 2. Mesoscale rain-band & discrete supercell cumulonimbus cell organization drifting with the wind
        double driftX = x - wx * timeSec * 4.5;
        double driftZ = z - wz * timeSec * 4.5;
        float mesoWave = vnoise(driftX / 520.0, driftZ / 520.0, 401) * 0.6f
                + vnoise(driftX / 210.0, driftZ / 210.0, 402) * 0.4f;
        // Discrete cumulonimbus supercell cores (~900..1500m spacing) with downwind anvil spread
        float cbCellCore = vnoise(driftX / 680.0, driftZ / 680.0, 405) * 0.65f
                + vnoise(driftX / 260.0, driftZ / 260.0, 406) * 0.35f;
        float cbAnvilDownwind = vnoise((driftX - wx * 180.0) / 720.0, (driftZ - wz * 180.0) / 720.0, 405) * 0.70f
                + vnoise((driftX - wx * 140.0) / 310.0, (driftZ - wz * 140.0) / 310.0, 406) * 0.30f;
        float supercellTower = Math.max(smooth(0.56f, 0.84f, cbCellCore), smooth(0.58f, 0.86f, cbAnvilDownwind) * 0.88f);

        // Distinct sub-cloud rain-shaft cells (~260..480m wide) so rain hangs in visible curtains with sunlit gaps between
        float shaftCell = smooth(0.36f, 0.74f,
                vnoise(driftX / 290.0, driftZ / 290.0, 409) * 0.65f
                        + vnoise(driftX / 135.0, driftZ / 135.0, 410) * 0.35f);

        float wetPrecip = Math.max(rain, snow);
        float dryPrecip = Math.max(ash, sand);
        float precipTotal = Math.max(wetPrecip, dryPrecip * 0.85f);
        float regionalBand = precipTotal > 0.06f
                ? precipTotal * smooth(0.28f, 0.74f, mesoWave + 0.30f * orographicLift - 0.25f * leewardDry)
                : 0f;
        float rainBand = clamp01(Math.max(frontBandMax, regionalBand) + orographicLift * precipTotal * 0.35f);

        // 3. Orographic precipitation enhancement on windward slopes & rainshadow suppression on leeward slopes
        float drySuppression = (1f - 0.52f * leewardDry * (1f - 0.65f * frontEnvMax) * (1f - 0.70f * clamp01(precipTotal * 1.4f)));
        boolean isHaboobOnly = (sand > 0.12f || profile == WeatherProfile.ARID || s.dunes > 0.30f) && wetPrecip < 0.08f && ash < 0.12f;

        float solarHeat = solarHeating(dayTime);
        // Fair-weather thermals settle overnight. Existing frontal rain/snow and windward lift persist.
        float thermalCycle = 0.58f + 0.42f * solarHeat;
        cu *= thermalCycle;
        float convectiveDrive = clamp01(cb * (0.72f + 0.28f * solarHeat)
                + 0.45f * cu * moisture + 0.55f * orographicLift + 0.40f * rainBand);
        float cellTower = supercellTower * clamp01(0.35f + 0.85f * convectiveDrive);

        float coverage = clamp01((Math.max(cu * 0.78f, st) + marineLayer + wetlandMist + orographicCloud + 0.28f * rainBand + 0.35f * cellTower) * drySuppression);
        if (snow > 0.08f) {
            // Snow & alpine blizzards sustain a full diffused whiteout overcast canopy
            float snowFloor = clamp01(0.80f + 0.18f * snow);
            coverage = Math.max(coverage, snowFloor);
        } else if (rain > 0.08f) {
            // Rain & thunderstorms have a heavy dark core in squall bands with structured breaks on the flanks
            float rainFloor = clamp01(0.54f + 0.38f * (0.45f * rain + 0.55f * Math.max(rainBand, shaftCell)));
            coverage = Math.max(coverage, rainFloor);
        }
        if (isHaboobOnly) {
            // Desert haboobs roll beneath a mostly clear/lightly-clouded blue sky so the towering dust wall contrasts against the sky
            coverage = Math.min(coverage, 0.18f + 0.22f * supercellTower);
        }

        float stormTower = clamp01(Math.max(cb * (0.45f + 0.55f * supercellTower), Math.max(orographicTower, cellTower))
                + 0.35f * rainBand + 0.22f * dark + 0.22f * snow + 0.65f * sand + s.heat * 0.30f);

        // 4. Sub-cloud precipitation curtain / haboob / blizzard mist density
        float rawCurtain = 0f;
        if (sand > 0.08f) {
            // Haboob dust storm wall: billowy front lobes rolling across the desert floor
            float haboobWave = 0.45f + 0.55f * smooth(0.25f, 0.78f, mesoWave * 0.6f + shaftCell * 0.4f);
            rawCurtain = sand * haboobWave * (0.65f + 0.35f * Math.max(rainBand, frontBandMax));
        } else if (snow > 0.06f) {
            // Alpine blizzard whiteout & blowing snow fog hugging terrain
            rawCurtain = snow * (0.58f + 0.42f * Math.max(rainBand, shaftCell)) * (1f + 0.35f * orographicLift);
        } else if (precipTotal > 0.05f || (orographicLift > 0.35f && moisture > 0.50f && (st > 0.40f || cellTower > 0.45f))) {
            float orographicSquall = orographicLift * moisture * Math.max(st, cellTower) * 0.65f;
            float pDrive = Math.max(precipTotal, orographicSquall);
            rawCurtain = pDrive * (0.22f + 0.78f * Math.max(shaftCell, Math.max(rainBand, frontBandMax))) * (1f + 0.40f * orographicLift) * drySuppression;
        }
        float curtainDensity = clamp01(rawCurtain);

        // 5. Regional aerosol / precipitation type (water rain vs snow/blizzard vs desert haboob vs volcanic ash)
        double lat = AtmosphereMath.latitudeFactor(z);
        float thermalSnowLine = profile.snowLine + 22.0f * solarHeat - 12.0f * (1.0f - solarHeat) + s.heat * 45.0f;
        boolean coldSnow = snow > rain || s.elevation + 35f > thermalSnowLine || (lat > 0.70 && s.heat < 0.15f);
        float aerosolCode;
        if (ash > 0.15f || blight > 0.15f || profile == WeatherProfile.VOLCANIC || s.heat > 0.42f) {
            // This is a category, not a density: interpolating light ashfall toward water
            // crosses the snow/sand codes and gives distant ash the wrong material.
            aerosolCode = AEROSOL_ASH;
        } else if (sand > 0.12f || profile == WeatherProfile.ARID || s.dunes > 0.30f) {
            aerosolCode = AEROSOL_SAND;
        } else if (coldSnow) {
            aerosolCode = AEROSOL_SNOW;
        } else {
            aerosolCode = AEROSOL_WATER;
        }

        float windSpeed = clamp01(0.22f + 0.65f * gale + 0.18f * orographicLift + (s.coastDistance < 120f ? 0.10f : 0f));
        WeatherKind effectiveKind = domFront != null && frontEnvMax >= 0.42f ? domFront.kind() : baseKind;
        float fogDrive = effectiveKind == WeatherKind.FOG ? 1f : (regionParams != null && regionParams.length > 8 ? regionParams[8] * 0.65f : 0f);
        Thermals thermals = thermals(z, s, moisture, wetPrecip, fogDrive, dayTime);
        float surfaceHeight = Math.max(TamrielMap.SEA_LEVEL, Math.max(s.elevation, Float.isFinite(s.waterLevel) ? s.waterLevel : s.elevation));
        // Surface moisture can change sharply at a river bank. Its dew fog stays
        // local, while the elevated cloud layer follows a much broader air mass.
        float airMoisture = clamp01(0.78f + 0.16f * wetPrecip - 0.30f * s.dunes
                + 0.10f * (vnoise(x / 2400.0, z / 2400.0, 717) - 0.5f));
        Thermals cloudAir = thermals(z, s, airMoisture, wetPrecip, fogDrive * 0.45f, dayTime);
        // A condensation level belongs to an air mass, not to every surface column.
        // Modest terrain lift lets peaks penetrate a bank instead of stretching the
        // entire deck into cones above each mountain. Ground fog is a separate layer.
        float cloudBaseOffset = TamrielMap.SEA_LEVEL + (cloudAir.condensationHeight() - surfaceHeight)
                + Math.min(75f, (surfaceHeight - TamrielMap.SEA_LEVEL) * 0.16f)
                - 26f * rainBand - 18f * orographicLift - 232f;
        float cloudTopBoost = 220.0f * stormTower + 65.0f * orographicLift;

        return new ColumnState(
                moisture,
                orographicLift,
                leewardDry,
                wx,
                wz,
                windSpeed,
                coverage,
                stormTower,
                rainBand,
                curtainDensity,
                aerosolCode,
                cloudBaseOffset,
                cloudTopBoost,
                surfaceHeight,
                thermals.temperatureC(),
                thermals.relativeHumidity(),
                thermals.dewPointC(),
                thermals.groundFog()
        );
    }

    /** Smooth sunrise/noon/sunset heating, with a cool night; world day ticks wrap safely across long saves. */
    public static float solarHeating(long dayTime) {
        double phase = (Math.floorMod(dayTime, 24000L) - 6000L) * (Math.PI * 2.0 / 24000.0);
        return (float) Math.max(0.0, Math.cos(phase));
    }

    /**
     * Evolves a moving {@link WeatherFront} over {@code ticks} according to the underlying landscape and water at its
     * current position:
     * <ul>
     *   <li>Over warm water, bays, lakes, and wetlands, marine/storm/rain fronts absorb moisture and intensify.</li>
     *   <li>Ascending windward mountain slopes triggers orographic intensification (peaking intensity &amp; rain release),
     *       followed by moisture depletion and faster decay once crossing into a leeward rainshadow or dry interior.</li>
     *   <li>Over hot arid dune fields, {@code SANDSTORM} haboobs strengthen while wet fronts dry out.</li>
     *   <li>Over volcanic calderas, {@code ASH_STORM} and {@code BLIGHT} fronts recharge.</li>
     *   <li>Topographic barrier gradients gently steer the front's velocity vector around major massifs.</li>
     * </ul>
     */
    public static void advectAndEvolveFront(WeatherFront front, int ticks) {
        if (front == null || ticks <= 0) return;
        front.advect(ticks);

        int ix = (int) Math.floor(front.x);
        int iz = (int) Math.floor(front.z);
        MapSample s = SCRATCH_A.get();
        TamrielMap.sample(ix, iz, s);
        TamrielMap.deriveStrengths(s);

        float vlen = (float) Math.sqrt(front.vx * front.vx + front.vz * front.vz);
        float wx = vlen > 1e-4f ? front.vx / vlen : 0.7071f;
        float wz = vlen > 1e-4f ? front.vz / vlen : 0.7071f;

        float moisture = moistureFromSample(ix, iz, s);
        float[] liftDry = new float[2];
        orographicFactors(ix, iz, wx, wz, s, liftDry);
        float lift = liftDry[0];
        float leeDry = liftDry[1];

        float dt = ticks / 20.0f; // seconds
        WeatherKind k = front.kind;
        float deltaIntensity = 0f;

        if (k == WeatherKind.SANDSTORM || k == WeatherKind.DUST) {
            // Haboob sandstorms feed on dry thermal convection over dunes/arid basins and collapse over water/wetlands
            if (s.dunes > 0.2f || s.rainshadow > 0.45f) {
                deltaIntensity += 0.0035f * dt * (0.5f + s.dunes);
            }
            if (s.coastDistance <= 0f || moisture > 0.68f) {
                deltaIntensity -= 0.008f * dt * moisture;
                front.ticksLeft = Math.max(0, front.ticksLeft - (int) (ticks * 0.6f));
            }
        } else if (k == WeatherKind.ASH_STORM || k == WeatherKind.ASHFALL || k == WeatherKind.BLIGHT) {
            // Volcanic ash & blight storms recharge over high geothermal heat zones (Red Mountain / Stonefalls)
            if (s.heat > 0.25f) {
                deltaIntensity += 0.0045f * dt * s.heat;
            } else if (moisture > 0.72f) {
                // Heavy marine moisture washes out airborne ash faster
                deltaIntensity -= 0.003f * dt;
            }
        } else {
            // Wet synoptic systems (STORM, RAIN, BLIZZARD, SNOW, FOG):
            // 1. Strengthen over open water, bays, lakes, and wetlands (cyclogenesis / latent heat flux)
            if (moisture > 0.56f) {
                deltaIntensity += 0.0040f * dt * (moisture - 0.50f);
            }
            // 2. Windward orographic lift squeezes out intense precipitation while leeward rainshadows dry the system out
            if (lift > 0.18f) {
                deltaIntensity += 0.0030f * dt * lift * moisture;
            }
            if (leeDry > 0.22f || s.dunes > 0.25f) {
                float dryLoss = Math.max(leeDry, s.dunes * 0.8f);
                deltaIntensity -= 0.0065f * dt * dryLoss;
                front.ticksLeft = Math.max(0, front.ticksLeft - (int) (ticks * 0.45f * dryLoss));
            }
        }

        front.intensity = clamp(front.intensity + deltaIntensity, 0.18f, 1.0f);

        // Gentle topographic steering around steep mountain barriers
        if (s.slope > 0.12f && vlen > 1e-4f) {
            float upslopeDot = -(wx * s.aspectX + wz * s.aspectZ);
            if (upslopeDot > 0.15f) {
                float steer = Math.min(0.08f, s.slope * 0.06f * dt);
                float nx = wx + s.aspectX * steer;
                float nz = wz + s.aspectZ * steer;
                float nlen = (float) Math.sqrt(nx * nx + nz * nz);
                if (nlen > 1e-4f) {
                    front.vx = (nx / nlen) * vlen;
                    front.vz = (nz / nlen) * vlen;
                }
            }
        }
    }

    /**
     * Spawns a new physically grounded {@link WeatherFront} from one of Tamriel's hydrological/topographic cyclogenesis
     * source basins (Sea of Ghosts polar lows, Iliac Bay / Abecean marine cyclones, Alik'r / Anequina thermal haboobs,
     * Red Mountain volcanic plumes, Topal Bay / Black Marsh monsoon squalls, Jerall / Velothi orographic snowstorms).
     */
    public static WeatherFront spawnPhysicalFront(int id, int basinIndex, Random rng) {
        int b = Math.floorMod(basinIndex, 6);
        return switch (b) {
            case 0 -> // Sea of Ghosts -> Northern Skyrim / Winterhold / Pale polar low blizzard moving SSE
                    new WeatherFront(id,
                            -1200f + rng.nextFloat() * 2600f,
                            -3600f + rng.nextFloat() * 900f,
                            1150f + rng.nextFloat() * 550f,
                            0.07f + rng.nextFloat() * 0.10f,
                            0.20f + rng.nextFloat() * 0.12f,
                            WeatherKind.BLIZZARD,
                            0.84f + rng.nextFloat() * 0.16f,
                            6400 + rng.nextInt(5600));
            case 1 -> // Iliac Bay / Abecean Sea marine cyclone moving ENE across High Rock, Hammerfell & Colovia
                    new WeatherFront(id,
                            -8200f + rng.nextFloat() * 2800f,
                            1800f + rng.nextFloat() * 3400f,
                            1250f + rng.nextFloat() * 650f,
                            0.24f + rng.nextFloat() * 0.12f,
                            -0.04f + rng.nextFloat() * 0.12f,
                            WeatherKind.STORM,
                            0.82f + rng.nextFloat() * 0.18f,
                            6400 + rng.nextInt(6000));
            case 2 -> // Alik'r Desert / Anequina thermal haboob sandstorm moving E/ENE
                    new WeatherFront(id,
                            -6400f + rng.nextFloat() * 1800f,
                            3800f + rng.nextFloat() * 1600f,
                            950f + rng.nextFloat() * 500f,
                            0.22f + rng.nextFloat() * 0.12f,
                            -0.05f + rng.nextFloat() * 0.10f,
                            WeatherKind.SANDSTORM,
                            0.85f + rng.nextFloat() * 0.15f,
                            5400 + rng.nextInt(5400));
            case 3 -> // Red Mountain / Vvardenfell caldera ash-storm plume drifting SW/W across Morrowind
                    new WeatherFront(id,
                            4600f + rng.nextFloat() * 1400f,
                            -1500f + rng.nextFloat() * 1100f,
                            950f + rng.nextFloat() * 500f,
                            -0.18f - rng.nextFloat() * 0.10f,
                            0.10f + rng.nextFloat() * 0.12f,
                            WeatherKind.ASH_STORM,
                            0.85f + rng.nextFloat() * 0.15f,
                            5600 + rng.nextInt(5600));
            case 4 -> // Topal Bay / Padomaic / Nibenay tropical monsoon rain & thunderstorm squall moving NNW
                    new WeatherFront(id,
                            800f + rng.nextFloat() * 2600f,
                            5200f + rng.nextFloat() * 2800f,
                            1200f + rng.nextFloat() * 650f,
                            -0.12f + rng.nextFloat() * 0.18f,
                            -0.20f - rng.nextFloat() * 0.10f,
                            rng.nextBoolean() ? WeatherKind.STORM : WeatherKind.RAIN,
                            0.80f + rng.nextFloat() * 0.20f,
                            6200 + rng.nextInt(5800));
            default -> // Druadach / Jerall / White River windward orographic snow & rain front moving ESE across Skyrim
                    new WeatherFront(id,
                            -1800f + rng.nextFloat() * 1800f,
                            -1400f + rng.nextFloat() * 1200f,
                            950f + rng.nextFloat() * 550f,
                            0.18f + rng.nextFloat() * 0.10f,
                            0.12f + rng.nextFloat() * 0.10f,
                            rng.nextBoolean() ? WeatherKind.SNOW : WeatherKind.RAIN,
                            0.78f + rng.nextFloat() * 0.20f,
                            5600 + rng.nextInt(5400));
        };
    }

    // ------------------------------------------------------------------ math helpers

    public static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(1f, v);
    }

    public static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(max, v);
    }

    public static float lerp(float t, float a, float b) {
        return a + (b - a) * t;
    }

    public static float smooth(float a, float b, float x) {
        float t = clamp01((x - a) / (b - a));
        return t * t * (3f - 2f * t);
    }

    private static long mix(long h) {
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }

    private static float hash(int i, int j, int salt) {
        long h = i * 0x9E3779B97F4A7C15L ^ j * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        return (mix(h) >>> 40) / 16777216f;
    }

    private static float vnoise(double x, double z, int salt) {
        int xi = (int) Math.floor(x);
        int zi = (int) Math.floor(z);
        float fx = (float) (x - xi);
        float fz = (float) (z - zi);
        fx = fx * fx * (3f - 2f * fx);
        fz = fz * fz * (3f - 2f * fz);
        float a = hash(xi, zi, salt);
        float b = hash(xi + 1, zi, salt);
        float c = hash(xi, zi + 1, salt);
        float d = hash(xi + 1, zi + 1, salt);
        float ab = a + (b - a) * fx;
        float cd = c + (d - c) * fx;
        return ab + (cd - ab) * fz;
    }
}
