package com.skycraft.atmosphere.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.skycraft.atmosphere.AtmosphereMath;
import com.skycraft.atmosphere.WeatherFront;
import com.skycraft.atmosphere.WeatherPhysics;
import com.skycraft.atmosphere.weather.WeatherKind;
import com.skycraft.atmosphere.weather.WeatherProfile;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.TamrielMap;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the cloud meshes for {@link CloudRenderer} on its worker thread, from a {@link Job} the render thread
 * captured. Nothing here touches the level or GL; it reads the Tamriel map (thread-safe) and the job's snapshot of
 * the regional weather and moving {@link WeatherFront}s.
 *
 * <p>The sky is laid out in "wind space" (world position minus the accumulated wind drift), so clouds keep their
 * shape while they drift. A coarse weather grid (192 blocks) samples each region's eased cloud parameters
 * ({@link CloudClimate}) through a domain warp, so weather fronts follow region borders without straight edges; a
 * large-scale fractal noise breaks every layer into fields and clearings, and every cloud is a hashed individual
 * (size, height, lobes, sprites, roll and mirror), so nothing repeats. Clouds fade in and out continuously with
 * their coverage, and lose puffs smoothly with distance (the last puff of a level of detail fades).</p>
 *
 * <p>Two meshes come out, both sorted back to front: the drifting sky (cumulus, storm towers, overcast decks,
 * cirrus and the rain/snow curtains under them) and the fixed ground layer (fog banks, peak caps, the Red Mountain
 * plume, desert and ash haze, sandstorm and ash walls). Every quad is a camera-facing billboard, an upright
 * billboard turning about the vertical, or a flat sheet, with per-vertex light: sun and moon from the right side,
 * shadowed bases, dawn and dusk glow on the undersides, a silver lining toward the sun, and haze toward the
 * horizon.</p>
 */
final class CloudBuilder {
    // ---- atlas cells (tools/textures/atmosphere.py)
    static final int C_PUFF = 0;
    static final int C_SHEET = 4;
    static final int C_CIRRUS = 6;
    static final int C_FOG = 8;
    static final int C_RAIN = 9;
    static final int C_SNOW = 10;
    static final int C_BILLOW = 11;
    static final int C_LENS = 12;
    static final int C_GLOW = 13;
    static final int C_WALL = 14;
    static final int C_FLAT = 15;

    /** Everything the builder needs, captured on the render thread. */
    record Job(double camX, double camY, double camZ, double driftX, double driftZ, double highX, double highZ,
               float windX, float windZ, float wind, float sunX, float sunY, float sunZ,
               float fogR, float fogG, float fogB, float localFog, int height, int radius, boolean fancy, boolean distant,
               float mistHour, float seconds, Map<String, float[]> climate,
               double plumeX, double plumeZ, boolean plume, double throatX, double throatZ, boolean throat,
               List<WeatherFront> fronts) {
        Job(double camX, double camY, double camZ, double driftX, double driftZ, double highX, double highZ,
            float windX, float windZ, float wind, float sunX, float sunY, float sunZ,
            float fogR, float fogG, float fogB, float localFog, int height, int radius, boolean fancy, boolean distant,
            float mistHour, float seconds, Map<String, float[]> climate,
            double plumeX, double plumeZ, boolean plume, double throatX, double throatZ, boolean throat) {
            this(camX, camY, camZ, driftX, driftZ, highX, highZ, windX, windZ, wind, sunX, sunY, sunZ,
                    fogR, fogG, fogB, localFog, height, radius, fancy, distant, mistHour, seconds, climate,
                    plumeX, plumeZ, plume, throatX, throatZ, throat, List.of());
        }
    }

    /** A finished build: the two meshes (null when empty), where they were built, and the storm cells for lightning. */
    static final class Result {
        BufferBuilder.RenderedBuffer sky;
        BufferBuilder.RenderedBuffer ground;
        double anchorX, anchorY, anchorZ;
        double driftX, driftZ;
        /** Lightning candidates, {@link DistantStorms#STRIDE} floats each (world x, base y, z, top y, radius, strength). */
        float[] storms = new float[0];
        int stormCount;
        int quads;
        /** What was built (counts per kind), for the profiling log. */
        String census = "";
        long nanos;
        boolean failed;
    }

    // ---- weather grid
    private static final int G = 192;
    private static final int E = CloudClimate.COUNT;     // macro elevation
    private static final int SL = E + 1;                  // snow line
    private static final int MI = E + 2;                  // dawn mist of the climate
    private static final int NP = E + 3;
    private int gx0, gz0, gn;
    private float[] grid = new float[0];
    private final float[] p = new float[NP];
    private final float[] q = new float[NP];

    // ---- terrain cache: 32-block cells -> {elevation, water level (or -1), wetness}
    private final Map<Long, float[]> terrain = new HashMap<>();
    private final MapSample sample = new MapSample();

    private final QuadList sky = new QuadList();
    private final QuadList ground = new QuadList();
    private final BufferBuilder skyBuffer = new BufferBuilder(1 << 18);
    private final BufferBuilder groundBuffer = new BufferBuilder(1 << 16);
    private float[] storms = new float[DistantStorms.STRIDE * 16];
    private int nCumulus, nTowers, nCaps, nFog, nCurtains, nWalls;
    private int stormCount;

    // ---- per-build lighting
    private Job job;
    private float R;
    private float sunR, sunG, sunB, sunI;
    private float ambR, ambG, ambB;
    private float moonI;
    private float duskR, duskG, duskB, dusk;

    // ================================================================== build

    Result build(Job job) {
        long start = System.nanoTime();
        this.job = job;
        this.R = job.radius();
        Result out = new Result();
        out.anchorX = job.camX();
        out.anchorY = job.camY();
        out.anchorZ = job.camZ();
        out.driftX = job.driftX();
        out.driftZ = job.driftZ();
        sky.clear();
        ground.clear();
        stormCount = 0;
        nCumulus = nTowers = nCaps = nFog = nCurtains = nWalls = 0;
        if (terrain.size() > 60_000) terrain.clear();
        try {
            lighting();
            buildGrid();
            cirrus();
            stratus();
            cumulus();
            cumulonimbus();
            if (job.distant()) curtains();
            fogBanks();
            caps();
            if (job.distant()) {
                dustAndAsh();
                approachingFronts();
                if (job.plume()) plume();
            }
            out.sky = write(sky, skyBuffer);
            out.ground = write(ground, groundBuffer);
        } catch (Throwable t) {
            if (skyBuffer.building()) skyBuffer.discard();
            if (groundBuffer.building()) groundBuffer.discard();
            out.failed = true;
            com.skycraft.Skycraft.LOGGER.warn("Skycraft clouds: build failed", t);
        }
        out.storms = Arrays.copyOf(storms, stormCount * DistantStorms.STRIDE);
        out.stormCount = stormCount;
        out.quads = sky.n + ground.n;
        out.census = String.format(java.util.Locale.ROOT, "%d cumulus, %d storm towers, %d caps, %d fog banks, %d curtains, %d storm walls",
                nCumulus, nTowers, nCaps, nFog, nCurtains, nWalls);
        out.nanos = System.nanoTime() - start;
        return out;
    }

    private static BufferBuilder.RenderedBuffer write(QuadList list, BufferBuilder bb) {
        if (list.n == 0) return null;
        list.sort();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        float[] d = list.data;
        for (int k = list.n - 1; k >= 0; k--) {     // far to near
            int base = (int) (list.keys[k] & 0xFFFFFFL) * QuadList.STRIDE;
            for (int v = 0; v < 4; v++) {
                int o = base + v * 6;
                int c = Float.floatToRawIntBits(d[o + 5]);
                bb.vertex(d[o], d[o + 1], d[o + 2]).uv(d[o + 3], d[o + 4])
                        .color(c >>> 24, (c >>> 16) & 0xFF, (c >>> 8) & 0xFF, c & 0xFF).endVertex();
            }
        }
        return bb.end();
    }

    // ================================================================== lighting

    private void lighting() {
        float sy = job.sunY();
        float lat = (float) AtmosphereMath.latitudeFactor(job.camZ());
        Region camRegion = TamrielMap.region((int) Math.floor(job.camX()), (int) Math.floor(job.camZ()));
        WeatherProfile camProfile = WeatherProfile.of(camRegion);
        TamrielMap.sample((int) Math.floor(job.camX()), (int) Math.floor(job.camZ()), sample);
        TamrielMap.deriveStrengths(sample);

        float warm = 1f - smooth(0.02f, 0.42f, sy);
        sunI = smooth(-0.12f, 0.1f, sy);
        float sun = sunI * lerp(warm, 0.55f, 0.8f);
        sunR = sun;
        sunG = sun * lerp(warm, 0.97f, 0.6f);
        sunB = sun * lerp(warm, 0.92f, 0.38f);
        float day = smooth(-0.25f, 0.22f, sy);
        ambR = lerp(day, 0.08f, 0.66f);
        ambG = lerp(day, 0.09f, 0.70f);
        ambB = lerp(day, 0.125f, 0.78f);
        // dawn and dusk: the sun low on the horizon lights the undersides with regional scattering
        dusk = smooth(-0.18f - 0.06f * lat, 0.0f, sy) * (1f - smooth(0.05f, 0.32f + 0.08f * lat, sy));
        if (camProfile == WeatherProfile.VOLCANIC || sample.heat > 0.35f) {
            // Vvardenfell / Stonefalls smoldering volcanic ash-crimson twilight
            duskR = 0.92f;
            duskG = 0.36f;
            duskB = 0.24f;
        } else if (camProfile == WeatherProfile.ARID || sample.dunes > 0.25f) {
            // Alik'r / Anequina warm amber desert twilight
            duskR = 1.0f;
            duskG = 0.68f;
            duskB = 0.38f;
        } else if ("summerset_isles".equals(camRegion.province()) || sample.elevation > 220f) {
            // Summerset / Eton Nir crisp prismatic violet-rose-gold high-altitude light
            duskR = 0.96f;
            duskG = 0.66f;
            duskB = 0.88f;
        } else {
            duskR = 1.0f;
            duskG = 0.52f;
            duskB = 0.42f;
        }
        ambR += 0.08f * dusk * duskR;
        ambG += 0.04f * dusk * duskG;
        ambB += 0.05f * dusk * duskB;
        moonI = 1f - smooth(-0.3f, -0.05f, sy);
    }

    /**
     * Packs the lit colour of a cloud vertex (RGBA bytes as float bits).
     *
     * @param vert   0 at the cloud's base .. 1 at its top
     * @param facing direction from the cloud's heart toward the sun, -1..1
     * @param shadow how dark the base gets in its own shadow, 0..1
     */
    private float color(float ar, float ag, float ab, float vert, float facing, float shadow,
                        float x, float y, float z, float dist, float alpha, float emR, float emG, float emB) {
        float self = 1f - shadow * (1f - vert);
        float ambK = (0.85f + 0.15f * vert) * (1f - 0.45f * shadow * (1f - vert));
        float sunK = Math.max(0f, 0.25f + 0.75f * facing) * self;
        float moonK = Math.max(0f, 0.35f - 0.65f * facing) * self * moonI;
        float lr = ambR * ambK + sunR * sunK + 0.12f * moonK;
        float lg = ambG * ambK + sunG * sunK + 0.12f * moonK;
        float lb = ambB * ambK + sunB * sunK + 0.16f * moonK;
        float under = dusk * (1f - vert) * 0.55f;
        lr += duskR * under;
        lg += duskG * under;
        lb += duskB * under;
        float r = ar * lr + emR, g = ag * lg + emG, b = ab * lb + emB;
        // silver lining: thin cloud edges glow when you look toward the sun through them
        if (dist > 1f && sunI > 0.01f) {
            float s = (x * job.sunX() + y * job.sunY() + z * job.sunZ()) / dist;
            if (s > 0.6f) {
                float s2 = (s - 0.6f) / 0.4f;
                s2 *= s2;
                s2 *= s2;
                float k = s2 * sunI * 0.5f * (1.1f - Math.min(1f, alpha));
                r += sunR * k;
                g += sunG * k;
                b += sunB * k;
            }
        }
        // haze toward the horizon and in regional fog
        float hz = smooth(0.3f * R, 1.05f * R, dist) * 0.62f;
        hz = Math.max(hz, job.localFog() * 0.85f * smooth(0f, 260f, dist));
        r = lerp(hz, r, job.fogR());
        g = lerp(hz, g, job.fogG());
        b = lerp(hz, b, job.fogB());
        float a = alpha * (1f - smooth(0.85f * R, R, dist));
        return pack(r, g, b, a);
    }

    private static float pack(float r, float g, float b, float a) {
        int ir = clampByte(r), ig = clampByte(g), ib = clampByte(b), ia = clampByte(a);
        return Float.intBitsToFloat(ir << 24 | ig << 16 | ib << 8 | ia);
    }

    private static int clampByte(float v) {
        int i = (int) (v * 255f + 0.5f);
        return i < 0 ? 0 : Math.min(255, i);
    }

    // ================================================================== weather grid

    private void buildGrid() {
        double span = R + 2 * G;
        gx0 = (int) Math.floor((job.camX() - span) / G);
        gz0 = (int) Math.floor((job.camZ() - span) / G);
        gn = (int) Math.ceil(2 * span / G) + 2;
        if (grid.length < gn * gn * NP) grid = new float[gn * gn * NP];
        float[] clear = CloudClimate.target(null);
        List<WeatherFront> fronts = job.fronts() != null ? job.fronts() : List.of();
        for (int i = 0; i < gn; i++) {
            for (int j = 0; j < gn; j++) {
                int wx = (gx0 + i) * G, wz = (gz0 + j) * G;
                // warp the lookup so fronts follow region borders without straight lines
                int lx = wx + (int) ((vnoise(wx / 700.0, wz / 700.0, 91) - 0.5f) * 220);
                int lz = wz + (int) ((vnoise(wx / 700.0, wz / 700.0, 92) - 0.5f) * 220);
                Region region = TamrielMap.region(lx, lz);
                WeatherProfile profile = WeatherProfile.of(region);
                float[] params = job.climate().get(region.id());
                if (params == null) params = clear;
                int o = (i * gn + j) * NP;
                System.arraycopy(params, 0, grid, o, CloudClimate.COUNT);

                // Sample TamrielMap hydrology & relief via WeatherPhysics (moisture, windward orographic lift, leeward rainshadow)
                TamrielMap.sample(wx, wz, sample);
                TamrielMap.deriveStrengths(sample);
                float moisture = WeatherPhysics.moistureFromSample(wx, wz, sample);
                float[] liftDry = new float[2];
                WeatherPhysics.orographicFactors(wx, wz, job.windX(), job.windZ(), sample, liftDry);
                float orographicLift = liftDry[0] * (0.45f + 0.55f * moisture);
                float leewardDry = liftDry[1];

                if (sample.coastDistance < 360f) {
                    float marine = clamp01(1f - Math.max(0f, sample.coastDistance) / 360f);
                    grid[o + CloudClimate.ST] = clamp01(grid[o + CloudClimate.ST] + 0.16f * marine);
                    grid[o + CloudClimate.FOG] = clamp01(grid[o + CloudClimate.FOG] + 0.16f * marine);
                }
                if (orographicLift > 0.05f) {
                    grid[o + CloudClimate.CU] = clamp01(grid[o + CloudClimate.CU] + 0.32f * orographicLift);
                    grid[o + CloudClimate.ST] = clamp01(grid[o + CloudClimate.ST] + 0.25f * orographicLift);
                    grid[o + CloudClimate.CB] = clamp01(grid[o + CloudClimate.CB] + 0.42f * orographicLift * moisture);
                }
                if (leewardDry > 0.08f) {
                    grid[o + CloudClimate.CU] *= (1f - 0.45f * leewardDry);
                    grid[o + CloudClimate.ST] *= (1f - 0.35f * leewardDry);
                }
                // the climate always shows a little: ash on the wind of the ashlands, dust over deserts
                if (profile.ambient == WeatherProfile.Ambient.ASH || sample.heat > 0.35f) {
                    grid[o + CloudClimate.ASH] = Math.max(grid[o + CloudClimate.ASH], Math.max(0.2f, sample.heat * 0.45f));
                }
                if (profile.ambient == WeatherProfile.Ambient.SAND || sample.dunes > 0.25f) {
                    grid[o + CloudClimate.SAND] = Math.max(grid[o + CloudClimate.SAND], Math.max(0.22f, sample.dunes * 0.48f));
                    grid[o + CloudClimate.CU] *= 0.55f;
                }
                if (profile == WeatherProfile.SWAMP || sample.wetland > 0.35f) {
                    grid[o + CloudClimate.FOG] = Math.max(grid[o + CloudClimate.FOG], 0.32f + 0.35f * sample.wetland);
                }

                // Blend moving synoptic WeatherFronts and curved mesoscale rain bands / mesocyclone spirals covering this grid cell
                for (WeatherFront front : fronts) {
                    float fi = front.intensityAt(wx, wz);
                    if (fi > 0.01f) {
                        float band = WeatherPhysics.frontRainBandAt(front, wx, wz, job.seconds());
                        float wBlend = clamp01(0.45f * fi + 0.55f * band);
                        float[] ft = CloudClimate.target(front.kind());
                        for (int k = 0; k < CloudClimate.COUNT; k++) {
                            grid[o + k] = lerp(wBlend, grid[o + k], ft[k]);
                        }
                        if (WeatherPhysics.isMesocyclone(front)) {
                            grid[o + CloudClimate.CB] = Math.max(grid[o + CloudClimate.CB], band * 0.92f);
                            grid[o + CloudClimate.THUNDER] = Math.max(grid[o + CloudClimate.THUNDER], band * 0.88f);
                        }
                    }
                }

                grid[o + E] = terrain(wx, wz)[0];
                grid[o + SL] = Math.min(profile.snowLine, 2000);
                grid[o + MI] = Math.max(profile.dawnMist, sample.wetland * 0.85f);
            }
        }
    }

    /** Bilinear weather at world (x, z) into {@code out}. */
    private void weather(double x, double z, float[] out) {
        double fx = x / G - gx0, fz = z / G - gz0;
        int i = (int) Math.floor(fx), j = (int) Math.floor(fz);
        if (i < 0 || j < 0 || i >= gn - 1 || j >= gn - 1) {
            i = Math.max(0, Math.min(gn - 2, i));
            j = Math.max(0, Math.min(gn - 2, j));
            fx = i;
            fz = j;
        }
        float tx = (float) (fx - i), tz = (float) (fz - j);
        int o00 = (i * gn + j) * NP, o10 = ((i + 1) * gn + j) * NP, o01 = (i * gn + j + 1) * NP, o11 = ((i + 1) * gn + j + 1) * NP;
        for (int k = 0; k < NP; k++) {
            float a = grid[o00 + k] + (grid[o10 + k] - grid[o00 + k]) * tx;
            float b = grid[o01 + k] + (grid[o11 + k] - grid[o01 + k]) * tx;
            out[k] = a + (b - a) * tz;
        }
    }

    /** {elevation, water level or -1, wetness 0..1} of the 32-block cell holding (x, z). */
    private float[] terrain(int x, int z) {
        long key = ((long) (x >> 5) << 32) ^ ((z >> 5) & 0xFFFFFFFFL);
        float[] t = terrain.get(key);
        if (t == null) {
            int cx = (x >> 5 << 5) + 16, cz = (z >> 5 << 5) + 16;
            TamrielMap.sample(cx, cz, sample);
            TamrielMap.deriveStrengths(sample);
            float water = Float.isNaN(sample.waterLevel) ? -1f : sample.waterLevel;
            float wet = Math.max(Math.max(sample.wetland, sample.lake), sample.river * 0.8f);
            if (sample.inWater()) wet = 1f;
            t = new float[]{Math.max(sample.elevation, TamrielMap.SEA_LEVEL), water, Math.min(1f, wet)};
            terrain.put(key, t);
        }
        return t;
    }

    private float groundAt(double x, double z) {
        return terrain((int) Math.floor(x), (int) Math.floor(z))[0];
    }

    // ================================================================== sky layers

    /** Large-scale coverage noise in wind space, 0..1: cloud fields and clear stretches a kilometre across. */
    private static float coverage(double x, double z, int salt) {
        return 0.5f * vnoise(x / 1100.0, z / 1100.0, salt) + 0.3f * vnoise(x / 420.0, z / 420.0, salt + 1)
                + 0.2f * vnoise(x / 160.0, z / 160.0, salt + 2);
    }

    /** Fair-weather cumulus: individual cauliflower clouds with flat grey bases. */
    private void cumulus() {
        final int S = 104;
        int full = job.fancy() ? 14 : 7;
        double ox = job.driftX(), oz = job.driftZ();
        int i0 = (int) Math.floor((job.camX() - ox - R) / S), i1 = (int) Math.floor((job.camX() - ox + R) / S);
        int j0 = (int) Math.floor((job.camZ() - oz - R) / S), j1 = (int) Math.floor((job.camZ() - oz + R) / S);
        float H = job.height();
        float windAngle = (float) Math.atan2(job.windZ(), job.windX());
        for (int i = i0; i <= i1; i++) {
            for (int j = j0; j <= j1; j++) {
                long seed = seed(i, j, 11);
                double wsx = (i + 0.1 + 0.8 * hash(seed, 1)) * S, wsz = (j + 0.1 + 0.8 * hash(seed, 2)) * S;
                double wx = wsx + ox, wz = wsz + oz;
                float dx = (float) (wx - job.camX()), dz = (float) (wz - job.camZ());
                float hd = (float) Math.sqrt(dx * dx + dz * dz);
                if (hd > R) continue;
                weather(wx, wz, p);
                float n = coverage(wsx, wsz, 21);
                float v = p[CloudClimate.CU] * smooth(0.3f, 0.72f, n) * 2.0f;
                float strength = clamp01((v - hash(seed, 3)) / 0.14f);
                if (strength <= 0.01f) continue;
                nCumulus++;
                float gale = p[CloudClimate.GALE];
                float cu = p[CloudClimate.CU];
                // humilis (flat), mediocris or congestus (towering), by chance and by how convective the day is
                float build = hash(seed, 7) * (0.6f + 0.6f * cu);
                float r = (24f + 36f * hash(seed, 4) * hash(seed, 4)) * (0.8f + 0.5f * cu) * (0.55f + 0.45f * strength);
                float hgt = r * (0.32f + 0.9f * build * build) * (1f - 0.5f * gale);
                float base = H + (hash(seed, 6) - 0.5f) * 12f;
                float cy = base - (float) job.camY();
                float dist = (float) Math.sqrt(hd * hd + cy * cy);
                float count = lerp(smooth(220f, 1300f, dist), full, 3f);
                float[] alb = albedo(p, 1f);
                float shadow = 0.22f + 0.45f * p[CloudClimate.DARK];
                // gales stretch cumulus into ragged fractus along the wind; otherwise the long axis is random
                float axis = gale > 0.3f ? windAngle : hash(seed, 8) * 6.2832f;
                float ax = (float) Math.cos(axis), az = (float) Math.sin(axis);
                float half = r * (1f + 0.7f * gale), depth = r * (0.45f + 0.3f * hash(seed, 9));
                for (int k = 0; k < full && k < count + 1; k++) {
                    float lod = clamp01(count - k);
                    float t, side, py, rad;
                    int cell;
                    if (k == 0) {
                        t = 0f;
                        side = 0f;
                        rad = Math.max(0.5f * r, 0.6f * hgt);
                        py = base + 0.8f * rad * 0.7f + 0.15f * hgt;
                        cell = C_FLAT;
                    } else if (k <= 6) {
                        // the base row: flat-bottomed puffs strung along the cloud
                        t = -0.85f + 1.7f * (k - 1) / 5f + (hash(seed, 10 + k) - 0.5f) * 0.25f;
                        side = (hash(seed, 20 + k) - 0.5f) * 1.2f;
                        rad = (0.3f + 0.14f * hash(seed, 30 + k)) * r * (1f - 0.35f * Math.abs(t));
                        py = base + 0.81f * rad;
                        cell = C_FLAT;
                    } else {
                        // the domes on top, crowding toward the middle
                        float u = hash(seed, 40 + k) * 2f - 1f;
                        t = u * Math.abs(u) * 0.6f;
                        side = (hash(seed, 50 + k) - 0.5f) * 0.8f;
                        rad = (0.26f + 0.16f * hash(seed, 60 + k)) * r * (1f - 0.4f * Math.abs(t)) + 0.25f * hgt;
                        py = base + (0.35f + 0.6f * hash(seed, 70 + k)) * hgt + 0.4f * rad * (1f - Math.abs(t));
                        cell = C_PUFF + (int) (hash(seed, 75 + k) * 4);
                    }
                    float px = ax * t * half - az * side * depth, pz = az * t * half + ax * side * depth;
                    float x = dx + px, y = py - (float) job.camY(), z = dz + pz;
                    float d = (float) Math.sqrt(x * x + y * y + z * z);
                    float near = smooth(rad * 0.8f, rad * 2.4f, d);
                    float alpha = 0.95f * strength * lod * near;
                    if (alpha < 0.01f) continue;
                    float top = hgt + 0.3f * r;
                    float vb = 0.3f + 0.7f * clamp01((py - rad * 0.7f - base) / top), vt = 0.3f + 0.7f * clamp01((py + rad * 0.7f - base) / top);
                    float facing = facing(px, (py - (base + 0.35f * hgt)) * 1.5f, pz);
                    float cb = color(alb[0], alb[1], alb[2], vb, facing, shadow, x, y - rad * 0.5f, z, d, alpha, 0, 0, 0);
                    float ct = color(alb[0], alb[1], alb[2], vt, facing, shadow, x, y + rad * 0.5f, z, d, alpha, 0, 0, 0);
                    float roll = (hash(seed, 80 + k) - 0.5f) * 0.4f;
                    billboard(sky, x, y, z, d, rad * 1.8f * (1f + 0.5f * gale), rad * 1.8f, roll, cell, hash(seed, 90 + k) < 0.5f, ct, cb);
                }
            }
        }
    }

    /** Towering storm clouds: a dark-based column with crisp cauliflower turrets and a wide glaciated anvil spreading downwind. */
    private void cumulonimbus() {
        final int S = 310;
        int full = job.fancy() ? 52 : 26;
        double ox = job.driftX(), oz = job.driftZ();
        float reach = R + 240;
        int i0 = (int) Math.floor((job.camX() - ox - reach) / S), i1 = (int) Math.floor((job.camX() - ox + reach) / S);
        int j0 = (int) Math.floor((job.camZ() - oz - reach) / S), j1 = (int) Math.floor((job.camZ() - oz + reach) / S);
        float H = job.height();
        float wl = Math.max(1e-3f, (float) Math.sqrt(job.windX() * job.windX() + job.windZ() * job.windZ()));
        float wdx = job.windX() / wl, wdz = job.windZ() / wl;
        for (int i = i0; i <= i1; i++) {
            for (int j = j0; j <= j1; j++) {
                long seed = seed(i, j, 13);
                double wsx = (i + 0.2 + 0.6 * hash(seed, 1)) * S, wsz = (j + 0.2 + 0.6 * hash(seed, 2)) * S;
                double wx = wsx + ox, wz = wsz + oz;
                float dx = (float) (wx - job.camX()), dz = (float) (wz - job.camZ());
                float hd = (float) Math.sqrt(dx * dx + dz * dz);
                if (hd > reach) continue;
                weather(wx, wz, p);
                float n = coverage(wsx, wsz, 31);
                float v = p[CloudClimate.CB] * (0.38f + 0.95f * n);
                float strength = clamp01((v - hash(seed, 3) * 0.85f) / 0.16f);
                if (strength <= 0.01f) continue;
                nTowers++;
                float r = (88f + 60f * hash(seed, 4)) * (0.78f + 0.35f * p[CloudClimate.CB]) * (0.6f + 0.4f * strength);
                float base = H - 30f;
                float top = H + (260f + 175f * hash(seed, 5)) * (0.60f + 0.40f * p[CloudClimate.CB]) * (0.55f + 0.45f * strength);
                float hgt = top - base;
                float cy = base + hgt * 0.4f - (float) job.camY();
                float dist = (float) Math.sqrt(hd * hd + cy * cy);
                float count = lerp(smooth(320f, 1750f, dist), full, 12f);
                float[] darkAlb = albedo(p, 0.90f);
                float baseShadow = 0.68f + 0.26f * p[CloudClimate.DARK];
                int levels = 8;
                for (int k = 0; k < full && k < count + 1; k++) {
                    float lod = clamp01(count - k);
                    float px, py, pz, rad, squash = 1f;
                    int cell;
                    if (k < levels) {
                        // Core updraft column, bottom to top, flaring into a wide flat glaciated anvil canopy at the tropopause
                        float t = (k + 0.4f) / levels;
                        py = base + hgt * t * 0.92f;
                        float lr = r * (1.05f - 0.22f * t);
                        px = (hash(seed, 100 + k) - 0.5f) * 0.22f * r;
                        pz = (hash(seed, 110 + k) - 0.5f) * 0.22f * r;
                        rad = lr * 0.96f;
                        cell = k == 0 ? C_FLAT : (k < 4 ? C_BILLOW : C_PUFF + (k & 3));
                        if (k >= levels - 2) {
                            float anvilTier = (k == levels - 1) ? 2.15f : 1.65f;
                            rad = r * anvilTier;
                            px += wdx * r * (0.35f + 0.30f * (k - (levels - 2)));
                            pz += wdz * r * (0.35f + 0.30f * (k - (levels - 2)));
                            squash = (k == levels - 1) ? 0.32f : 0.44f;
                            cell = (k == levels - 1) ? C_FLAT : C_PUFF + (k & 3);
                        }
                    } else if (k < levels + 30) {
                        // Crisp cauliflower cumulus congestus turrets ringing the lower/mid updraft & wide anvil rim
                        int m = k - levels;
                        int level = m % levels;
                        float t = (level + 0.45f) / levels;
                        float a = hash(seed, 200 + m) * 6.2832f;
                        float lr = r * (1.12f - 0.28f * t);
                        px = (float) Math.cos(a) * lr * (level < 3 ? 0.82f : 0.64f);
                        pz = (float) Math.sin(a) * lr * (level < 3 ? 0.82f : 0.64f);
                        py = base + hgt * t * 0.88f + (hash(seed, 300 + m) - 0.5f) * hgt * 0.07f;
                        rad = lr * (0.45f + 0.18f * hash(seed, 400 + m));
                        cell = (level < 4 && (m & 1) == 0) ? C_BILLOW : C_PUFF + (m & 3);
                        if (level >= levels - 2) {
                            px = px * 1.95f + wdx * r * 0.55f;
                            pz = pz * 1.95f + wdz * r * 0.55f;
                            rad *= 1.35f;
                            squash = 0.36f;
                        }
                    } else {
                        // Wide fibrous glaciated anvil overhang spreading radially and downwind
                        int m = k - levels - 30;
                        float a = (hash(seed, 500 + m) - 0.5f) * 2.35f;
                        float along = r * (0.85f + 1.05f * hash(seed, 510 + m));
                        px = wdx * along + (-wdz) * a * r * 1.15f;
                        pz = wdz * along + wdx * a * r * 1.15f;
                        py = base + hgt * (0.88f + 0.07f * hash(seed, 515 + m));
                        rad = r * (0.72f + 0.32f * hash(seed, 520 + m));
                        squash = 0.30f;
                        cell = (m % 3 == 0) ? C_FLAT : C_PUFF + (m & 3);
                    }
                    float x = dx + px, y = py - (float) job.camY(), z = dz + pz;
                    float d = (float) Math.sqrt(x * x + y * y + z * z);
                    float near = smooth(rad * 0.8f, rad * 2.4f, d);
                    float alpha = 0.98f * strength * lod * near;
                    if (alpha < 0.01f) continue;
                    float vb = clamp01((py - rad * 0.6f * squash - base) / hgt), vt = clamp01((py + rad * 0.6f * squash - base) / hgt);
                    // Upper cauliflower turrets & glaciated anvil gleam bright white in sunlight while the base stays dark slate
                    float sunlitTurret = smooth(0.22f, 0.58f, vt) * (1f - 0.45f * smooth(0.82f, 0.98f, p[CloudClimate.ST]));
                    float ar = lerp(sunlitTurret, darkAlb[0], 0.99f);
                    float ag = lerp(sunlitTurret, darkAlb[1], 0.99f);
                    float ab = lerp(sunlitTurret, darkAlb[2], 1.00f);
                    float localShadow = lerp(sunlitTurret, baseShadow, 0.28f);
                    float facing = facing(px, (py - (base + 0.42f * hgt)) * 0.55f, pz);
                    float cb = color(ar, ag, ab, vb * vb, facing, localShadow, x, y, z, d, alpha, 0, 0, 0);
                    float ct = color(ar, ag, ab, Math.min(1f, vt + 0.15f * sunlitTurret), Math.max(facing, 0.35f * sunlitTurret), localShadow, x, y, z, d, alpha, 0, 0, 0);
                    float roll = (hash(seed, 600 + k) - 0.5f) * 0.35f * squash;
                    billboard(sky, x, y, z, d, rad * 1.85f, rad * 1.85f * squash, roll, cell, hash(seed, 700 + k) < 0.5f, ct, cb);
                }
                // Distinct rain or snow shafts under the storm base
                float precip = Math.max(p[CloudClimate.RAIN], p[CloudClimate.SNOW]);
                if (job.distant() && precip > 0.10f) {
                    float g = groundAt(wx, wz);
                    boolean snow = p[CloudClimate.SNOW] > p[CloudClimate.RAIN] || g + 40 > p[SL];
                    float fade = smooth(110f, 280f, hd);
                    curtain(sky, dx + wdx * r * 0.15f, dz + wdz * r * 0.15f, base + 8f, g - 4f, r * 1.05f,
                            (0.48f + 0.38f * precip) * strength * fade, snow, p, seed);
                }
                if (p[CloudClimate.THUNDER] > 0.15f) {
                    addStorm((float) wx, base, (float) wz, top, r, p[CloudClimate.THUNDER] * strength);
                }
            }
        }
    }

    /** The grey overcast deck: multi-layered rolling dark storm underside with silver-lit crevices and upper sunlit sheets. */
    private void stratus() {
        int[] cells = {96, 192, 384};
        float[] outer = {650f, 1250f, R};
        double ox = job.driftX(), oz = job.driftZ();
        float H = job.height();
        float inner = 0f;
        for (int ring = 0; ring < 3; ring++) {
            int S = cells[ring];
            float ro = Math.min(outer[ring], R);
            int i0 = (int) Math.floor((job.camX() - ox - ro) / S), i1 = (int) Math.floor((job.camX() - ox + ro) / S);
            int j0 = (int) Math.floor((job.camZ() - oz - ro) / S), j1 = (int) Math.floor((job.camZ() - oz + ro) / S);
            for (int i = i0; i <= i1; i++) {
                for (int j = j0; j <= j1; j++) {
                    long seed = seed(i, j, 17 + ring);
                    double wsx = (i + 0.5 + (hash(seed, 11) - 0.5) * 0.7) * S, wsz = (j + 0.5 + (hash(seed, 12) - 0.5) * 0.7) * S;
                    double wx = wsx + ox, wz = wsz + oz;
                    float dx = (float) (wx - job.camX()), dz = (float) (wz - job.camZ());
                    float hd = (float) Math.sqrt(dx * dx + dz * dz);
                    if (hd < inner || hd >= ro) continue;
                    weather(wx, wz, p);
                    float st = p[CloudClimate.ST];
                    if (st < 0.03f) continue;
                    float n = coverage(wsx, wsz, 41);
                    float v = st * (0.45f + 0.9f * n);
                    float alpha = clamp01((v - 0.18f * hash(seed, 1)) * 1.25f - 0.12f) * (0.8f + 0.2f * hash(seed, 2));
                    if (alpha < 0.02f) continue;
                    float[] alb = albedo(p, 0.9f);
                    float dark = p[CloudClimate.DARK];
                    float snowW = p[CloudClimate.SNOW];
                    float half = S * (0.95f + 0.45f * hash(seed, 3));
                    float angle = hash(seed, 4) * 6.2832f;
                    int cell = C_SHEET + (hash(seed, 5) < 0.5f ? 0 : 1);
                    // Lower layer: dark rolling storm/asperitas underside or soft whiteout snow deck
                    float y = H - 8f + (hash(seed, 6) - 0.5f) * 10f - (float) job.camY();
                    float d = (float) Math.sqrt(hd * hd + y * y);
                    float a = alpha * 0.94f * smooth(10f, 60f, Math.abs(y) + hd * 0.05f);
                    boolean isCrevice = dark > 0.35f && snowW < 0.2f && hash(seed, 14) > 0.68f;
                    float underAr = isCrevice ? Math.min(1f, alb[0] * 1.32f) : alb[0] * (1f - 0.28f * dark * (1f - snowW));
                    float underAg = isCrevice ? Math.min(1f, alb[1] * 1.34f) : alb[1] * (1f - 0.26f * dark * (1f - snowW));
                    float underAb = isCrevice ? Math.min(1f, alb[2] * 1.38f) : alb[2] * (1f - 0.22f * dark * (1f - snowW));
                    float c = color(underAr, underAg, underAb, isCrevice ? 0.72f : 0.08f,
                            isCrevice ? 0.45f : (-0.25f - 0.45f * dark), 0.35f + 0.45f * dark, dx, y, dz, d, a,
                            isCrevice ? 0.12f * sunI : 0, isCrevice ? 0.13f * sunI : 0, isCrevice ? 0.15f * sunI : 0);
                    sheet(sky, dx, y, dz, d, half, half, angle, cell, c);
                    // Rolling dark asperitas/mammatus billow pouches under heavy storm decks (ring 0 & 1)
                    if (ring <= 1 && dark > 0.32f && snowW < 0.25f) {
                        float by = y - 7f - 6f * hash(seed, 15);
                        float bd = (float) Math.sqrt(hd * hd + by * by);
                        float br = S * (0.48f + 0.28f * hash(seed, 16));
                        float ba = a * (0.65f + 0.30f * dark) * smooth(br * 0.7f, br * 2.2f, bd);
                        if (ba > 0.02f) {
                            float ctB = color(underAr * 1.08f, underAg * 1.10f, underAb * 1.12f, 0.38f, 0.15f, 0.55f, dx, by, dz, bd, ba, 0, 0, 0);
                            float cbB = color(underAr * 0.62f, underAg * 0.64f, underAb * 0.68f, 0.04f, -0.45f, 0.78f, dx, by, dz, bd, ba, 0, 0, 0);
                            billboard(sky, dx + (hash(seed, 17) - 0.5f) * S * 0.35f, by, dz + (hash(seed, 18) - 0.5f) * S * 0.35f,
                                    bd, br * 1.55f, br * 0.68f, (hash(seed, 19) - 0.5f) * 0.3f, C_BILLOW, hash(seed, 20) < 0.5f, ctB, cbB);
                        }
                    }
                    // Upper layer: lumpy and sunlit, seen from mountain tops and from the air above the deck
                    if (job.camY() < H - 30f) continue;
                    float y2 = H + 12f + hash(seed, 7) * 10f - (float) job.camY();
                    float d2 = (float) Math.sqrt(hd * hd + y2 * y2);
                    float a2 = alpha * 0.75f * smooth(10f, 60f, Math.abs(y2) + hd * 0.05f);
                    float c2 = color(alb[0], alb[1], alb[2], 0.95f, 0.5f, 0.2f, dx, y2, dz, d2, a2, 0, 0, 0);
                    sheet(sky, dx + S * 0.3f, y2, dz - S * 0.25f, d2, half * 0.9f, half * 0.9f, angle + 1.3f, cell ^ 1, c2);
                }
            }
            inner = ro;
        }
    }

    /** High, wind-combed cirrus streaks; they catch the sunset long after the lower clouds are grey. */
    private void cirrus() {
        final int S = 220;
        float reach = R * 1.25f;
        double ox = job.highX(), oz = job.highZ();
        int i0 = (int) Math.floor((job.camX() - ox - reach) / S), i1 = (int) Math.floor((job.camX() - ox + reach) / S);
        int j0 = (int) Math.floor((job.camZ() - oz - reach) / S), j1 = (int) Math.floor((job.camZ() - oz + reach) / S);
        float H = job.height();
        float windAngle = (float) Math.atan2(job.windZ(), job.windX());
        for (int i = i0; i <= i1; i++) {
            for (int j = j0; j <= j1; j++) {
                long seed = seed(i, j, 19);
                double wsx = (i + 0.5 + (hash(seed, 1) - 0.5) * 0.8) * S, wsz = (j + 0.5 + (hash(seed, 2) - 0.5) * 0.8) * S;
                double wx = wsx + ox, wz = wsz + oz;
                float dx = (float) (wx - job.camX()), dz = (float) (wz - job.camZ());
                float hd = (float) Math.sqrt(dx * dx + dz * dz);
                if (hd > reach) continue;
                weather(wx, wz, p);
                float ci = p[CloudClimate.CI];
                if (ci < 0.02f) continue;
                float n = coverage(wsx, wsz, 51);
                float v = ci * (0.15f + 1.5f * n * n);
                float strength = clamp01((v - hash(seed, 3)) / 0.2f);
                if (strength < 0.02f) continue;
                float y = H + 230f + hash(seed, 4) * 60f - (float) job.camY();
                float d = (float) Math.sqrt(hd * hd + y * y);
                float alpha = 0.6f * strength * (1f - smooth(0.85f * reach, reach, hd));
                // high cloud keeps the low sun's colour after sunset
                float glow = dusk * 0.35f;
                float c = color(1f, 1f, 1f, 1f, 0.4f, 0f, dx, y, dz, d, alpha, duskR * glow, duskG * glow * 0.8f, duskB * glow);
                float angle = windAngle + (hash(seed, 5) - 0.5f) * 0.7f;
                sheet(sky, dx, y, dz, d, S * (1.2f + 0.5f * hash(seed, 6)), S * (0.4f + 0.2f * hash(seed, 7)), angle,
                        C_CIRRUS + (hash(seed, 8) < 0.5f ? 0 : 1), c);
            }
        }
    }

    /** Distinct grey rain shafts and white snow squalls hanging under dark storm bases, with sunlit atmospheric haze between shafts. */
    private void curtains() {
        final int S = 230;
        double ox = job.driftX(), oz = job.driftZ();
        int i0 = (int) Math.floor((job.camX() - ox - R) / S), i1 = (int) Math.floor((job.camX() - ox + R) / S);
        int j0 = (int) Math.floor((job.camZ() - oz - R) / S), j1 = (int) Math.floor((job.camZ() - oz + R) / S);
        float H = job.height();
        for (int i = i0; i <= i1; i++) {
            for (int j = j0; j <= j1; j++) {
                long seed = seed(i, j, 23);
                double wsx = (i + 0.2 + 0.6 * hash(seed, 1)) * S, wsz = (j + 0.2 + 0.6 * hash(seed, 2)) * S;
                double wx = wsx + ox, wz = wsz + oz;
                float dx = (float) (wx - job.camX()), dz = (float) (wz - job.camZ());
                float hd = (float) Math.sqrt(dx * dx + dz * dz);
                if (hd > R || hd < 95f) continue;
                weather(wx, wz, p);
                float precip = Math.max(p[CloudClimate.RAIN], p[CloudClimate.SNOW]);
                if (precip < 0.10f) continue;
                float g = groundAt(wx, wz);
                boolean snow = p[CloudClimate.SNOW] > p[CloudClimate.RAIN] || g + 40 > p[SL];
                float mountain = snow ? smooth(150f, 260f, g) : 0f;
                float v = precip * (0.58f + 0.5f * mountain) * (0.45f + 0.85f * coverage(wsx, wsz, 61));
                float strength = clamp01((v - hash(seed, 3) * 0.72f) / 0.2f);
                if (strength < 0.02f) continue;
                float width = (65f + 78f * hash(seed, 4)) * (1f - 0.25f * mountain);
                float alpha = (0.28f + 0.38f * precip + 0.25f * mountain) * (snow ? 1.35f : 1.15f) * strength * smooth(110f, 320f, hd);
                curtain(sky, dx, dz, H - 4f, g - 4f, width, alpha, snow, p, seed);
            }
        }
    }

    /** Multi-layered rain or snow shaft from a dark cloud base to the ground, leaning downwind with sunlit mist halo. */
    private void curtain(QuadList list, float dx, float dz, float topY, float bottomY, float halfWidth, float alpha,
                         boolean snow, float[] w, long seed) {
        if (alpha < 0.01f || topY <= bottomY + 8f) return;
        nCurtains++;
        float yTop = topY - (float) job.camY(), yBot = bottomY - (float) job.camY();
        float lean = (topY - bottomY) * (0.08f + 0.26f * job.wind());
        float d = (float) Math.sqrt(dx * dx + dz * dz + (yTop + yBot) * (yTop + yBot) * 0.25f);
        float dark = w[CloudClimate.DARK];
        float ar = snow ? 0.94f : 0.54f - 0.14f * dark;
        float ag = snow ? 0.96f : 0.59f - 0.14f * dark;
        float ab = snow ? 0.99f : 0.66f - 0.12f * dark;
        float cTop = color(ar * 0.82f, ag * 0.84f, ab * 0.86f, 0.25f, 0.05f, 0.45f, dx, yTop, dz, d, Math.min(0.92f, alpha * 1.12f), 0, 0, 0);
        float cBot = color(ar * 1.06f, ag * 1.06f, ab * 1.05f, 0.65f, 0.25f, 0.15f, dx, yBot, dz, d, alpha * 0.88f, 0, 0, 0);
        upright(list, dx, dz, yBot, yTop, d, halfWidth, job.windX() * lean, job.windZ() * lean,
                snow ? C_SNOW : C_RAIN, hash(seed, 99) < 0.5f, cTop, cBot);
        // Secondary narrower inner rain/snow core shaft for distinct vertical streaking (Ref 3)
        float innerAlpha = Math.min(0.88f, alpha * 0.72f);
        if (innerAlpha > 0.03f) {
            float ox = (hash(seed, 95) - 0.5f) * halfWidth * 0.45f;
            float oz = (hash(seed, 96) - 0.5f) * halfWidth * 0.45f;
            upright(list, dx + ox, dz + oz, yBot, yTop, d, halfWidth * 0.58f, job.windX() * lean * 0.85f, job.windZ() * lean * 0.85f,
                    snow ? C_SNOW : C_RAIN, hash(seed, 97) >= 0.5f, cTop, cBot);
        }
    }

    // ================================================================== ground layer (world-fixed)

    /** Valley fog banks, swamp mist, and alpine blizzard blowing snow-fog enveloping trees and slopes. */
    private void fogBanks() {
        final int S = 40;
        float reach = Math.min(R, 720f);
        int i0 = (int) Math.floor((job.camX() - reach) / S), i1 = (int) Math.floor((job.camX() + reach) / S);
        int j0 = (int) Math.floor((job.camZ() - reach) / S), j1 = (int) Math.floor((job.camZ() + reach) / S);
        for (int i = i0; i <= i1; i++) {
            for (int j = j0; j <= j1; j++) {
                int cx = i * S + S / 2, cz = j * S + S / 2;
                float dx = (float) (cx - job.camX()), dz = (float) (cz - job.camZ());
                float hd = (float) Math.sqrt(dx * dx + dz * dz);
                if (hd > reach) continue;
                float[] t = terrain(cx, cz);
                weather(cx, cz, p);
                float wet = t[2];
                float fogW = p[CloudClimate.FOG];
                float snowW = p[CloudClimate.SNOW];
                boolean blizzardSlope = snowW > 0.25f && (t[0] + 35f >= p[SL] || snowW > 0.55f);
                float valley = 0f;
                if (fogW > 0.35f && wet < 0.3f) {
                    float around = (terrain(cx + 160, cz)[0] + terrain(cx - 160, cz)[0] + terrain(cx, cz + 160)[0] + terrain(cx, cz - 160)[0]) * 0.25f;
                    valley = smooth(4f, 20f, around - t[0]);
                }
                float lowland = Math.max(Math.max(wet, valley), blizzardSlope ? (0.45f + 0.55f * snowW) : 0f);
                if (lowland < 0.25f) continue;
                Region reg = TamrielMap.region(cx, cz);
                boolean swamp = WeatherProfile.of(reg) == WeatherProfile.SWAMP || p[MI] > 0.65f;
                float mist = p[MI] * (swamp ? Math.max(0.55f, job.mistHour()) : job.mistHour());
                float amount = clamp01(Math.max(Math.max(fogW * 0.85f, mist * 1.3f), blizzardSlope ? snowW * 0.95f : 0f) * lowland);
                long seed = seed(i, j, 29);
                float strength = clamp01((amount - hash(seed, 1) * (blizzardSlope ? 0.42f : 0.6f)) / 0.25f);
                if (strength < 0.02f) continue;
                float base = (t[1] > 0 ? t[1] : t[0]) + 1.5f + hash(seed, 2) * (blizzardSlope ? 6.0f : 2.5f);
                float ox = (hash(seed, 3) - 0.5f) * S * 0.6f, oz = (hash(seed, 4) - 0.5f) * S * 0.6f;
                float x = dx + ox, z = dz + oz;
                float y0 = base - (float) job.camY();
                float hh = (blizzardSlope ? 10f + 18f * snowW : 4f + 7f * amount) * (0.7f + 0.6f * hash(seed, 5));
                float d = (float) Math.sqrt(x * x + z * z + (y0 + hh) * (y0 + hh));
                float fade = smooth(blizzardSlope ? 12f : 14f, blizzardSlope ? 42f : 60f, d) * (1f - smooth(reach * 0.8f, reach, hd));
                float alpha = (blizzardSlope ? 0.78f : 0.6f) * strength * fade;
                if (alpha < 0.01f) continue;
                nFog++;
                float er = job.fogR() * 0.45f, eg = job.fogG() * 0.45f, eb = job.fogB() * 0.45f;
                if (blizzardSlope) {
                    er = 0.24f * sunI;
                    eg = 0.25f * sunI;
                    eb = 0.27f * sunI;
                } else if (swamp) {
                    float bio = 0.14f + 0.18f * moonI;
                    er = lerp(bio, er, 0.22f);
                    eg = lerp(bio, eg, 0.56f);
                    eb = lerp(bio, eb, 0.46f);
                }
                float albR = blizzardSlope ? 0.94f : 0.75f;
                float albG = blizzardSlope ? 0.96f : 0.76f;
                float albB = blizzardSlope ? 0.99f : 0.77f;
                float c = color(albR, albG, albB, 0.85f, 0.25f, 0.12f, x, y0 + hh, z, d, alpha, er, eg, eb);
                upright(ground, x, z, y0 - hh * 0.35f, y0 + hh * 1.75f, d, S * (0.82f + 0.45f * hash(seed, 6)), 0, 0, C_FOG,
                        hash(seed, 7) < 0.5f, c, c);
                float c2 = color(albR, albG, albB, 0.92f, 0.4f, 0.1f, x, y0 + hh, z, d, alpha * 0.72f, er, eg, eb);
                sheet(ground, x, y0 + hh, z, d, S * 0.95f, S * 0.95f, hash(seed, 8) * 6.2832f, C_SHEET, c2);
            }
        }
    }

    /** Cap and banner clouds on the high peaks; the Throat of the World always wears one. */
    private void caps() {
        final int S = 128;
        float H = job.height();
        float capMin = H - 125f;
        int i0 = (int) Math.floor((job.camX() - R) / S), i1 = (int) Math.floor((job.camX() + R) / S);
        int j0 = (int) Math.floor((job.camZ() - R) / S), j1 = (int) Math.floor((job.camZ() + R) / S);
        for (int i = i0; i <= i1; i++) {
            for (int j = j0; j <= j1; j++) {
                int cx = i * S + S / 2, cz = j * S + S / 2;
                float dx = (float) (cx - job.camX()), dz = (float) (cz - job.camZ());
                if (dx * dx + dz * dz > R * R) continue;
                float e = terrain(cx, cz)[0];
                if (e < capMin) continue;
                if (terrain(cx + S, cz)[0] > e || terrain(cx - S, cz)[0] > e || terrain(cx, cz + S)[0] > e || terrain(cx, cz - S)[0] > e) continue;
                if (job.throat() && Math.abs(job.throatX() - cx) < S && Math.abs(job.throatZ() - cz) < S) continue;
                weather(cx, cz, p);
                long seed = seed(i, j, 37);
                float humid = clamp01(0.2f + 0.6f * (p[CloudClimate.ST] + p[CloudClimate.CU]) + 0.5f * p[CloudClimate.SNOW] - 0.4f * p[CloudClimate.SAND]);
                float strength = clamp01((humid - hash(seed, 1)) / 0.15f);
                if (strength < 0.02f) continue;
                cap(cx, cz, e, strength, seed, 1f + (e - capMin) / 200f);
            }
        }
        if (job.throat()) {
            int tx = (int) job.throatX(), tz = (int) job.throatZ();
            float e = 0f;
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) e = Math.max(e, terrain(tx + a * 32, tz + b * 32)[0]);
            float dx = (float) (tx - job.camX()), dz = (float) (tz - job.camZ());
            if (dx * dx + dz * dz < R * R) cap(tx, tz, Math.max(e, capMin), 1f, 0x7A12EL, 1.6f);
        }
    }

    private void cap(float cx, float cz, float summit, float strength, long seed, float scale) {
        nCaps++;
        float w = (28f + 18f * hash(seed, 2)) * scale;
        float y0 = summit + 20f - (float) job.camY();
        float dx = cx - (float) job.camX(), dz = cz - (float) job.camZ();
        // lens stack sitting on the summit
        for (int k = 0; k < 3; k++) {
            float y = y0 + k * 7f * scale;
            float hw = w * (1f - 0.2f * k);
            float d = (float) Math.sqrt(dx * dx + dz * dz + y * y);
            float alpha = 0.85f * strength * smooth(hw * 0.6f, hw * 2f, d);
            if (alpha < 0.01f) continue;
            float ct = color(1f, 1f, 1f, 0.9f, 0.4f, 0.3f, dx, y, dz, d, alpha, 0, 0, 0);
            float cb = color(1f, 1f, 1f, 0.3f, 0.2f, 0.3f, dx, y, dz, d, alpha, 0, 0, 0);
            billboard(ground, dx, y, dz, d, hw * 1.15f, hw * 0.38f, 0f, C_LENS, hash(seed, 10 + k) < 0.5f, ct, cb);
        }
        // a banner trailing downwind off the lee side
        float wl = Math.max(1e-3f, (float) Math.sqrt(job.windX() * job.windX() + job.windZ() * job.windZ()));
        float wdx = job.windX() / wl, wdz = job.windZ() / wl;
        for (int k = 0; k < 4; k++) {
            float along = w * (0.7f + 0.75f * k);
            float x = dx + wdx * along, z = dz + wdz * along;
            float y = y0 - 8f - 4f * k;
            float rad = w * 0.42f * (1f - 0.15f * k);
            float d = (float) Math.sqrt(x * x + y * y + z * z);
            float alpha = 0.7f * strength * (1f - 0.2f * k) * smooth(rad, rad * 3f, d);
            if (alpha < 0.01f) continue;
            float ct = color(1f, 1f, 1f, 0.8f, 0.3f, 0.35f, x, y, z, d, alpha, 0, 0, 0);
            float cb = color(1f, 1f, 1f, 0.2f, 0.1f, 0.35f, x, y, z, d, alpha, 0, 0, 0);
            billboard(ground, x, y, z, d, rad * 1.8f, rad * 1.1f, (hash(seed, 20 + k) - 0.5f) * 0.4f, C_PUFF + k,
                    hash(seed, 30 + k) < 0.5f, ct, cb);
        }
    }

    /** Low dust and ash haze over deserts and ashlands, and towering billowy haboob dust walls (Ref 4) / ash walls. */
    private void dustAndAsh() {
        int[] cells = {110, 220};
        float[] outer = {720f, R};
        float inner = 0f;
        for (int ring = 0; ring < 2; ring++) {
            int S = cells[ring];
            float ro = Math.min(outer[ring], R);
            int i0 = (int) Math.floor((job.camX() - ro) / S), i1 = (int) Math.floor((job.camX() + ro) / S);
            int j0 = (int) Math.floor((job.camZ() - ro) / S), j1 = (int) Math.floor((job.camZ() + ro) / S);
            for (int i = i0; i <= i1; i++) {
                for (int j = j0; j <= j1; j++) {
                    int cx = i * S + S / 2, cz = j * S + S / 2;
                    float dx = (float) (cx - job.camX()), dz = (float) (cz - job.camZ());
                    float hd = (float) Math.sqrt(dx * dx + dz * dz);
                    if (hd < inner || hd >= ro) continue;
                    weather(cx, cz, p);
                    float sand = p[CloudClimate.SAND], ash = p[CloudClimate.ASH];
                    float dusty = Math.max(sand, ash * 0.85f);
                    if (dusty < 0.12f) continue;
                    long seed = seed(i, j, 43 + ring);
                    float g = terrain(cx, cz)[0];
                    float blight = p[CloudClimate.BLIGHT];
                    float ar, ag, ab;
                    if (sand >= ash) {
                        ar = 0.85f;
                        ag = 0.66f;
                        ab = 0.45f;
                    } else {
                        ar = lerp(blight, 0.44f, 0.58f);
                        ag = lerp(blight, 0.39f, 0.31f);
                        ab = lerp(blight, 0.36f, 0.25f);
                    }
                    float x = dx + (hash(seed, 1) - 0.5f) * S * 0.5f, z = dz + (hash(seed, 2) - 0.5f) * S * 0.5f;
                    // Low horizon dust/ash veil
                    if (hd > 75f) {
                        float y = g + 14f + hash(seed, 3) * 10f - (float) job.camY();
                        float d = (float) Math.sqrt(x * x + y * y + z * z);
                        float alpha = (0.14f + 0.34f * dusty) * smooth(75f, 230f, hd);
                        float c = color(ar, ag, ab, 0.65f, 0.25f, 0.15f, x, y, z, d, alpha, 0, 0, 0);
                        upright(ground, x, z, y - 16f, y + 26f + 36f * dusty, d, S * 0.90f, 0, 0, C_FOG, hash(seed, 4) < 0.5f, c, c);
                    }
                    // Towering billowy Haboob sandstorm / ash-storm wall rolling across the floor (Ref 4)
                    float storm = smooth(0.45f, 0.92f, Math.max(sand, ash * 0.95f));
                    if (storm > 0.03f && hd > 60f) {
                        float hh = (52f + 68f * hash(seed, 5)) * (0.55f + 0.45f * storm);
                        float y = g - 6f - (float) job.camY();
                        float d = (float) Math.sqrt(x * x + (y + hh) * (y + hh) + z * z);
                        float alpha = 0.92f * storm * smooth(60f, 195f, hd);
                        nWalls++;
                        float ct = color(ar * 1.05f, ag * 1.02f, ab * 0.98f, 0.92f, 0.38f, 0.32f, x, y + hh, z, d, alpha, 0, 0, 0);
                        float cb = color(ar * 0.68f, ag * 0.64f, ab * 0.60f, 0.15f, -0.15f, 0.48f, x, y, z, d, alpha, 0, 0, 0);
                        upright(ground, x, z, y, y + hh * 2.05f, d, S * 0.98f, 0, 0, C_WALL, hash(seed, 6) < 0.5f, ct, cb);
                        // Sculpted cauliflower dust-wall lobes along the leading front and crest of the haboob
                        for (int lobe = 0; lobe < 2; lobe++) {
                            float lx = x + (hash(seed, 10 + lobe) - 0.5f) * S * 0.55f;
                            float lz = z + (hash(seed, 12 + lobe) - 0.5f) * S * 0.55f;
                            float ly = y + hh * (0.55f + 0.55f * lobe + 0.25f * hash(seed, 14 + lobe));
                            float lrad = S * (0.48f + 0.24f * hash(seed, 16 + lobe)) * (0.7f + 0.3f * storm);
                            float ld = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
                            float la = alpha * 0.90f * smooth(lrad * 0.7f, lrad * 2.2f, ld);
                            if (la > 0.02f) {
                                float lct = color(ar * 1.08f, ag * 1.04f, ab, 0.95f, 0.45f, 0.35f, lx, ly, lz, ld, la, 0, 0, 0);
                                float lcb = color(ar * 0.62f, ag * 0.58f, ab * 0.54f, 0.12f, -0.2f, 0.55f, lx, ly, lz, ld, la, 0, 0, 0);
                                billboard(ground, lx, ly, lz, ld, lrad * 1.65f, lrad * 1.25f,
                                        (hash(seed, 18 + lobe) - 0.5f) * 0.35f, lobe == 0 ? C_BILLOW : C_PUFF + (lobe & 3),
                                        hash(seed, 20 + lobe) < 0.5f, lct, lcb);
                            }
                        }
                    }
                }
            }
            inner = ro;
        }
    }

    /**
     * Renders distant approaching {@link WeatherFront}s (200..1,600 blocks from the camera) in the exact direction
     * {@code (fx - camX, fz - camZ)}: towering haboob sandstorm walls in Alik'r/Anequina, volcanic ash/blight fronts
     * off Vvardenfell/Stonefalls, and dark anvil storm towers with rain/blizzard squall curtains.
     */
    private void approachingFronts() {
        List<WeatherFront> fronts = job.fronts();
        if (fronts == null || fronts.isEmpty()) return;
        float H = job.height();
        for (WeatherFront front : fronts) {
            if (front == null || front.expired()) continue;
            double vdx = front.x - job.camX();
            double vdz = front.z - job.camZ();
            double centerDist = Math.sqrt(vdx * vdx + vdz * vdz);
            if (centerDist < 1.0) continue;
            double edgeDist = centerDist - front.radius;
            if (edgeDist > 1600.0 || edgeDist < -front.radius * 0.5) continue;

            float dirX = (float) (vdx / centerDist);
            float dirZ = (float) (vdz / centerDist);
            float perpX = -dirZ;
            float perpZ = dirX;
            float baseEdge = (float) Math.max(200.0, Math.min(R - 90.0, Math.max(200.0, edgeDist)));
            float visibility = edgeDist <= 200.0
                    ? 1.0f
                    : clamp01(1.0f - (float) ((edgeDist - 200.0) / 1400.0));
            float strength = clamp01(front.intensity * (0.45f + 0.55f * visibility));
            if (strength < 0.04f) continue;

            WeatherKind kind = front.kind;
            float[] ft = CloudClimate.target(kind);
            int arcSegments = job.fancy() ? 9 : 5;
            float arcHalfSpan = Math.min(front.radius * 0.75f, 680f);

            for (int s = 0; s < arcSegments; s++) {
                long seed = seed(front.id, s, 79);
                float u = (arcSegments == 1) ? 0f : ((float) s / (arcSegments - 1) * 2f - 1f);
                float lateral = u * arcHalfSpan + (hash(seed, 1) - 0.5f) * 70f;
                float curveBack = u * u * 140f + hash(seed, 2) * 55f;
                float dist = Math.min(R - 45f, baseEdge + curveBack);
                float dx = dirX * dist + perpX * lateral;
                float dz = dirZ * dist + perpZ * lateral;
                double wx = job.camX() + dx;
                double wz = job.camZ() + dz;
                float g = groundAt(wx, wz);
                float taper = (1f - 0.45f * u * u) * strength;

                if (kind == WeatherKind.SANDSTORM || kind == WeatherKind.DUST
                        || kind == WeatherKind.ASH_STORM || kind == WeatherKind.ASHFALL || kind == WeatherKind.BLIGHT) {
                    // Towering haboob sand wall or volcanic ash/blight wall advancing along (fx - camX, fz - camZ)
                    boolean isSand = (kind == WeatherKind.SANDSTORM || kind == WeatherKind.DUST);
                    float blight = (kind == WeatherKind.BLIGHT) ? 1f : 0f;
                    float ar = isSand ? 0.88f : lerp(blight, 0.46f, 0.60f);
                    float ag = isSand ? 0.72f : lerp(blight, 0.38f, 0.30f);
                    float ab = isSand ? 0.50f : lerp(blight, 0.35f, 0.24f);
                    float hh = (65f + 50f * hash(seed, 3)) * (0.65f + 0.35f * taper);
                    float y = g - 6f - (float) job.camY();
                    float d = (float) Math.sqrt(dx * dx + (y + hh) * (y + hh) + dz * dz);
                    float alpha = 0.88f * taper * smooth(90f, 240f, dist);
                    if (alpha > 0.02f) {
                        nWalls++;
                        float ct = color(ar, ag, ab, 0.9f, 0.35f, 0.25f, dx, y + hh, dz, d, alpha, 0, 0, 0);
                        float cb = color(ar * 0.82f, ag * 0.82f, ab * 0.82f, 0.18f, 0.1f, 0.3f, dx, y, dz, d, alpha, 0, 0, 0);
                        upright(ground, dx, dz, y, y + hh * 2.1f, d, 135f + 45f * hash(seed, 4), 0, 0,
                                C_WALL, hash(seed, 5) < 0.5f, ct, cb);
                    }
                }

                if (kind == WeatherKind.STORM || kind == WeatherKind.RAIN || kind == WeatherKind.BLIZZARD
                        || kind == WeatherKind.SNOW || kind == WeatherKind.ASH_STORM || kind == WeatherKind.BLIGHT) {
                    // Advancing anvil storm tower and precipitation curtain
                    nTowers++;
                    float base = H - 24f;
                    float top = H + (210f + 130f * hash(seed, 6)) * (0.65f + 0.35f * taper);
                    float hgt = top - base;
                    float rad = (75f + 40f * hash(seed, 7)) * (0.75f + 0.25f * taper);
                    float[] alb = albedo(ft, 0.92f);
                    float shadow = 0.65f + 0.25f * ft[CloudClimate.DARK];
                    for (int l = 0; l < 4; l++) {
                        float lt = (l + 0.4f) / 4f;
                        float py = base + hgt * lt;
                        float y = py - (float) job.camY();
                        float d = (float) Math.sqrt(dx * dx + y * y + dz * dz);
                        float lrad = rad * (l == 3 ? 1.35f : (1.0f - 0.15f * lt));
                        float squash = l == 3 ? 0.5f : 0.85f;
                        float alpha = 0.92f * taper * smooth(lrad * 0.8f, lrad * 2.2f, d);
                        if (alpha < 0.02f) continue;
                        float facing = facing(dx, y, dz);
                        float ct = color(alb[0], alb[1], alb[2], clamp01(lt + 0.2f), facing, shadow, dx, y, dz, d, alpha, 0, 0, 0);
                        float cb = color(alb[0], alb[1], alb[2], clamp01(lt * lt), facing, shadow, dx, y, dz, d, alpha, 0, 0, 0);
                        billboard(sky, dx, y, dz, d, lrad * 1.8f, lrad * 1.8f * squash,
                                (hash(seed, 20 + l) - 0.5f) * 0.35f, l == 0 ? C_FLAT : C_PUFF + (l & 3),
                                hash(seed, 30 + l) < 0.5f, ct, cb);
                    }
                    boolean snow = kind == WeatherKind.BLIZZARD || kind == WeatherKind.SNOW;
                    if (snow || kind == WeatherKind.STORM || kind == WeatherKind.RAIN) {
                        curtain(sky, dx, dz, base + 8f, g - 4f, rad * 1.15f,
                                (0.45f + 0.35f * taper) * smooth(150f, 340f, dist), snow, ft, seed);
                    }
                    if (ft[CloudClimate.THUNDER] > 0.15f) {
                        addStorm((float) wx, base, (float) wz, top, rad, ft[CloudClimate.THUNDER] * taper);
                    }
                }
            }
        }
    }

    /** Red Mountain's ash plume: rising from the caldera and bending downwind, its foot lit by the fires below. */
    private void plume() {
        float px = (float) (job.plumeX() - job.camX()), pz = (float) (job.plumeZ() - job.camZ());
        float hd = (float) Math.sqrt(px * px + pz * pz);
        if (hd > R + 400f) return;
        float e = 0f;
        for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) e = Math.max(e, terrain((int) job.plumeX() + a * 32, (int) job.plumeZ() + b * 32)[0]);
        weather(job.plumeX(), job.plumeZ(), p);
        int full = job.fancy() ? 40 : 20;
        float count = lerp(smooth(300f, 1500f, hd), full, 16f);
        float top = job.height() + 170f;
        float heavy = 0.65f + 0.35f * p[CloudClimate.ASH];
        float blight = p[CloudClimate.BLIGHT];
        long seed = 0x5ED_AA5L;
        for (int k = 0; k < full && k < count + 1; k++) {
            float lod = clamp01(count - k);
            float t = (k + 0.5f) / full;
            float churn = hash(seed, k) * 6.2832f + job.seconds() * 0.02f * (1 + (k % 3));
            float rad = (22f + 90f * t) * (1f + 0.35f * (1f - count / full));
            float bend = t * t * 320f;
            float x = px + job.windX() * bend + (float) Math.cos(churn) * rad * 0.3f;
            float z = pz + job.windZ() * bend + (float) Math.sin(churn) * rad * 0.3f;
            float y = e + 8f + t * (top - e) - (float) job.camY();
            float d = (float) Math.sqrt(x * x + y * y + z * z);
            float alpha = 0.9f * heavy * lod * (1f - 0.45f * t * t) * smooth(rad, rad * 2.5f, d);
            if (alpha < 0.01f) continue;
            float glow = (1f - t) * (1f - t) * (1f - t);
            float ar = lerp(blight, 0.32f, 0.45f), ag = lerp(blight, 0.28f, 0.24f), ab = lerp(blight, 0.26f, 0.2f);
            float ct = color(ar, ag, ab, Math.min(1f, t + 0.2f), 0.3f, 0.5f, x, y, z, d, alpha, 0.30f * glow, 0.10f * glow, 0.02f * glow);
            float cb = color(ar, ag, ab, t, 0.1f, 0.5f, x, y, z, d, alpha, 0.55f * glow, 0.18f * glow, 0.04f * glow);
            billboard(ground, x, y, z, d, rad * 1.8f, rad * 1.8f, (hash(seed, 100 + k) - 0.5f) * 0.6f, C_BILLOW,
                    hash(seed, 200 + k) < 0.5f, ct, cb);
        }
        if (p[CloudClimate.THUNDER] > 0.15f) addStorm((float) job.plumeX(), e + 60f, (float) job.plumeZ(), top, 90f, p[CloudClimate.THUNDER]);
    }

    // ================================================================== helpers

    /** Cloud colour of the weather: white, greyed by storms, stained by ash, blight and dust. */
    private float[] albedo(float[] w, float base) {
        float dark = 1f - 0.45f * w[CloudClimate.DARK];
        float ash = clamp01(w[CloudClimate.ASH]);
        float bl = clamp01(w[CloudClimate.BLIGHT]);
        float sand = clamp01(w[CloudClimate.SAND]) * 0.5f;
        float r = base * dark * lerp(ash, 1f, 0.5f) * lerp(bl, 1f, 1.05f) * lerp(sand, 1f, 0.97f);
        float g = base * dark * lerp(ash, 1f, 0.44f) * lerp(bl, 1f, 0.72f) * lerp(sand, 1f, 0.88f);
        float b = base * dark * lerp(ash, 1f, 0.4f) * lerp(bl, 1f, 0.62f) * lerp(sand, 1f, 0.76f);
        q[0] = r;
        q[1] = g;
        q[2] = b;
        return q;
    }

    private float facing(float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        if (l < 1e-3f) return job.sunY() * 0.5f;
        return (x * job.sunX() + y * job.sunY() + z * job.sunZ()) / l;
    }

    private void addStorm(float x, float base, float z, float top, float r, float strength) {
        if (strength < 0.05f) return;
        if ((stormCount + 1) * DistantStorms.STRIDE > storms.length) storms = Arrays.copyOf(storms, storms.length * 2);
        int o = stormCount++ * DistantStorms.STRIDE;
        storms[o] = x;
        storms[o + 1] = base;
        storms[o + 2] = z;
        storms[o + 3] = top;
        storms[o + 4] = r;
        storms[o + 5] = strength;
    }

    /** A camera-facing quad centred at (x, y, z) relative to the camera. */
    private void billboard(QuadList list, float x, float y, float z, float dist, float hw, float hh, float roll, int cell,
                           boolean flip, float colTop, float colBottom) {
        float il = 1f / Math.max(1e-3f, dist);
        float tx = -x * il, ty = -y * il, tz = -z * il;
        float rx = tz, rz = -tx;
        float rl = (float) Math.sqrt(rx * rx + rz * rz);
        if (rl < 1e-3f) {
            rx = 1f;
            rz = 0f;
        } else {
            rx /= rl;
            rz /= rl;
        }
        // up = toCamera x right (right has no y)
        float ux = ty * rz, uy = tz * rx - tx * rz, uz = -ty * rx;
        float c = (float) Math.cos(roll), s = (float) Math.sin(roll);
        float Rx = rx * c + ux * s, Ry = uy * s, Rz = rz * c + uz * s;
        float Ux = ux * c - rx * s, Uy = uy * c, Uz = uz * c - rz * s;
        Rx *= hw;
        Ry *= hw;
        Rz *= hw;
        Ux *= hh;
        Uy *= hh;
        Uz *= hh;
        float u0 = cellU(cell), v0 = cellV(cell), u1 = u0 + CELL, v1 = v0 + CELL;
        if (flip) {
            float t = u0;
            u0 = u1;
            u1 = t;
        }
        list.add(dist,
                x - Rx - Ux, y - Ry - Uy, z - Rz - Uz, u0, v1, colBottom,
                x + Rx - Ux, y + Ry - Uy, z + Rz - Uz, u1, v1, colBottom,
                x + Rx + Ux, y + Ry + Uy, z + Rz + Uz, u1, v0, colTop,
                x - Rx + Ux, y - Ry + Uy, z - Rz + Uz, u0, v0, colTop);
    }

    /** An upright quad turning about the vertical to face the camera; the bottom edge may be offset (leaning shafts). */
    private void upright(QuadList list, float x, float z, float yBottom, float yTop, float dist, float hw, float leanX, float leanZ,
                         int cell, boolean flip, float colTop, float colBottom) {
        float hl = (float) Math.sqrt(x * x + z * z);
        float rx, rz;
        if (hl < 1e-3f) {
            rx = 1f;
            rz = 0f;
        } else {
            rx = -z / hl;
            rz = x / hl;
        }
        rx *= hw;
        rz *= hw;
        float u0 = cellU(cell), v0 = cellV(cell), u1 = u0 + CELL, v1 = v0 + CELL;
        if (flip) {
            float t = u0;
            u0 = u1;
            u1 = t;
        }
        float bx = x + leanX, bz = z + leanZ;
        list.add(dist,
                bx - rx, yBottom, bz - rz, u0, v1, colBottom,
                bx + rx, yBottom, bz + rz, u1, v1, colBottom,
                x + rx, yTop, z + rz, u1, v0, colTop,
                x - rx, yTop, z - rz, u0, v0, colTop);
    }

    /** A flat horizontal quad, rotated by {@code angle} about the vertical. */
    private void sheet(QuadList list, float x, float y, float z, float dist, float halfA, float halfB, float angle, int cell, float color) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        float ax = c * halfA, az = s * halfA;
        float bx = -s * halfB, bz = c * halfB;
        float u0 = cellU(cell), v0 = cellV(cell), u1 = u0 + CELL, v1 = v0 + CELL;
        list.add(dist,
                x - ax - bx, y, z - az - bz, u0, v1, color,
                x + ax - bx, y, z + az - bz, u1, v1, color,
                x + ax + bx, y, z + az + bz, u1, v0, color,
                x - ax + bx, y, z - az + bz, u0, v0, color);
    }

    private static final float INSET = 2f / 1024f;
    private static final float CELL = 0.25f - 2 * INSET;

    private static float cellU(int cell) {
        return (cell & 3) * 0.25f + INSET;
    }

    private static float cellV(int cell) {
        return (cell >> 2) * 0.25f + INSET;
    }

    // ---- math

    static float smooth(float a, float b, float x) {
        float t = clamp01((x - a) / (b - a));
        return t * t * (3f - 2f * t);
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(1f, v);
    }

    /** {@code a + (b - a) * t} with t first, like {@code Mth.lerp}. */
    static float lerp(float t, float a, float b) {
        return a + (b - a) * t;
    }

    static long seed(int i, int j, int salt) {
        long h = i * 0x9E3779B97F4A7C15L ^ j * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        return mix(h);
    }

    /** Hash of (seed, k) in [0, 1). */
    static float hash(long seed, int k) {
        return (mix(seed + k * 0x9E3779B97F4A7C15L) >>> 40) / 16777216f;
    }

    private static long mix(long h) {
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }

    /** Smooth value noise in [0, 1]. */
    static float vnoise(double x, double z, int salt) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        float fx = (float) (x - xi), fz = (float) (z - zi);
        fx = fx * fx * (3f - 2f * fx);
        fz = fz * fz * (3f - 2f * fz);
        float a = hash(seed(xi, zi, salt), 0), b = hash(seed(xi + 1, zi, salt), 0);
        float c = hash(seed(xi, zi + 1, salt), 0), d = hash(seed(xi + 1, zi + 1, salt), 0);
        float ab = a + (b - a) * fx, cd = c + (d - c) * fx;
        return ab + (cd - ab) * fz;
    }

    // ================================================================== quad store

    /** Quads of one mesh: 4 vertices of (x, y, z, u, v, packed colour), plus a distance sort key each. */
    private static final class QuadList {
        static final int STRIDE = 24;
        float[] data = new float[STRIDE * 2048];
        long[] keys = new long[2048];
        int n;

        void clear() {
            n = 0;
        }

        void add(float dist, float... v) {
            if (n >= keys.length) {
                keys = Arrays.copyOf(keys, keys.length * 2);
                data = Arrays.copyOf(data, data.length * 2);
            }
            System.arraycopy(v, 0, data, n * STRIDE, STRIDE);
            long key = (long) Math.min(0x7FFFFF, (int) (dist * 8f));
            keys[n] = key << 24 | n;
            n++;
        }

        void sort() {
            Arrays.sort(keys, 0, n);
        }
    }
}
