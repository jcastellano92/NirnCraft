package com.nirncraft.atmosphere.client;

import com.nirncraft.atmosphere.AtmosphereConfig;
import com.nirncraft.atmosphere.NightSkyMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;

import java.util.Random;

/**
 * The unified sky's state each frame (com.nirncraft.atlas.AtlasSky draws it: shaders/include/atlas_night.glsl), from
 * the world: the clock (the frame of the stars, the moons' places and phases), the camera's real position (the aurora's
 * oval seen from there), the options, the night's activity (the space weather's hook: {@link #spaceWeatherKp}), the
 * overcast, and the meteors (simulated here: sporadic ones, showers on the configured nights, now and then a fireball
 * that lights the land). Also the night's light for the land that is known without the GPU: Masser and Secunda (the
 * brighter the key light, the other a fill), the meteors' flashes; the diffuse emitters' share (the aurora, the airglow,
 * the stars) is integrated on the GPU (atlas_sky_light.fsh) and read back by AtlasSky.
 */
public final class NightSky {
    private NightSky() {}

    /** Space weather (kp 0..9) when set (a later weather system, a command); NaN: from the night's activity. */
    public static volatile float spaceWeatherKp = Float.NaN;

    private static final Random RNG = new Random();
    private static final int SLOTS = 4;
    private static final double[][] meteor = new double[SLOTS][];  // start s, duration, speed, train, start dir, travel dir, E, mix
    private static double nextMeteorAt = -1.0;
    private static int frameIndex;
    private static float[] moonlights = new float[12];
    private static final float[] meteorLight = new float[16];  // SH L1 of the meteors' irradiance (rgb x 4)
    private static boolean drawn;

    /** Whether the sky's lights show at all for this camera (the old sky's rules: not under water, not blinded). */
    static boolean visible(Minecraft mc) {
        ClientLevel level = mc.level;
        if (mc.player == null || level == null || level.dimension() != Level.OVERWORLD || !AtmosphereConfig.SKY.get())
            return false;
        var camera = mc.gameRenderer.mainCamera();
        if (camera != null && camera.getFluidInCamera() != FogType.NONE) return false;
        return !mc.player.hasEffect(com.nirncraft.platform.Effects.holder(MobEffects.BLINDNESS))
                && !mc.player.hasEffect(com.nirncraft.platform.Effects.holder(MobEffects.DARKNESS));
    }

    /**
     * NightInfo for this frame (atlas_night.glsl), sunAngle the sky's (radians, as the sun's direction is made),
     * pathDeg the sun path's tilt, camera in world blocks, eyeKm the eye's altitude in the air's km.
     */
    public static float[] frame(Minecraft mc, float sunAngle, float pathDeg, double camX, double camZ, float eyeKm,
                                float partialTick) {
        ClientLevel level = mc.level;
        boolean show = level != null && visible(mc);
        double dayTime = level != null ? level.getOverworldClockTime() + partialTick : 18000.0;
        double days = dayTime / 24000.0;
        double seconds = level != null ? (level.getGameTime() + partialTick) / 20.0 : 0.0;
        // overcast hides the sky's lights (the clouds are atlas's; until they are in this sky: the weather's cover)
        float overcast = ClientWeather.active() && AtmosphereConfig.WEATHER_SKY.get() ? ClientWeather.clouds() : 0f;
        float rain = level != null ? level.getRainLevel(partialTick) : 0f;
        float clear = show ? Mth.clamp(1f - overcast * 0.85f - rain * 0.6f, 0f, 1f) : 0f;
        double kp = !Float.isNaN(spaceWeatherKp) ? spaceWeatherKp
                : level != null ? NightSkyMath.kpOf(SkyRenderer.activity(level)) : 0.0;
        float brightness = AtmosphereConfig.AURORA.get() ? AtmosphereConfig.AURORA_BRIGHTNESS.get().floatValue() : 0f;
        // by region: the oval's own geography decides (from the south it is a glow low in the north, then gone); off:
        // the old rule, cold biomes only
        if (!AtmosphereConfig.AURORA_EVERYWHERE.get() && !AtmosphereConfig.AURORA_BY_REGION.get())
            brightness *= Mth.clamp(SkyRenderer.coldness, 0f, 1f);
        float red = level != null ? SkyRenderer.auroraRedMix(level) : 0f;
        var aurora = NightSkyMath.aurora(kp, brightness, red, AtmosphereConfig.AURORA_EVERYWHERE.get(), camZ);
        boolean stars = AtmosphereConfig.STARS.get();
        var f = new NightSkyMath.Frame(sunAngle, pathDeg, days, seconds, frameIndex++, camX, camZ, eyeKm, clear,
                stars ? 1 : 0, AtmosphereConfig.GALAXY.get() ? 1 : 0, AtmosphereConfig.CONSTELLATIONS.get() ? 1 : 0,
                1.0, AtmosphereConfig.SECUNDA.get(), aurora);
        double[] sun = NightSkyMath.sunDirection(sunAngle, pathDeg);
        float[] meteors = meteors(seconds, days, sun, show && AtmosphereConfig.SHOOTING_STARS.get(), f);
        float[] block = NightSkyMath.block(f, meteors);
        var m = NightSkyMath.moon(false, days, sun, pathDeg);
        var s = NightSkyMath.moon(true, days, sun, pathDeg);
        double[] ml = NightSkyMath.moonlights(m, s, AtmosphereConfig.SECUNDA.get());
        for (int i = 0; i < 12; i++) moonlights[i] = (float) ml[i];
        drawn = show;
        return block;
    }

    /** {key dir xyz, key irradiance rgb, fill dir xyz, fill irradiance rgb} (above the air) of the last frame. */
    public static float[] moonlights() {
        return moonlights.clone();
    }

    /** The meteors' light this frame as SH L1 (4 x rgb: the constant band, then x, y, z; atlas_sky_light.fsh's). */
    public static float[] meteorLight() {
        return meteorLight.clone();
    }

    /** Whether the last frame's sky showed its lights. */
    public static boolean drawn() {
        return drawn;
    }

    // ---- meteors: sporadic ones every 15..60 s of a dark sky; on a shower's night (AtmosphereConfig.METEOR_SHOWERS,
    // days of the year) many more, from the shower's radiant; a few fireballs that light the land for a moment

    private static float[] meteors(double seconds, double days, double[] sun, boolean on, NightSkyMath.Frame f) {
        float[] out = new float[32];
        java.util.Arrays.fill(meteorLight, 0f);
        double gain = NightSkyMath.nightGain(sun[1]);
        if (!on || sun[1] > 0.05) {
            nextMeteorAt = -1;
            for (int i = 0; i < SLOTS; i++) meteor[i] = null;
            return out;
        }
        boolean shower = showerNight(days);
        double rate = shower ? Math.max(1.0, AtmosphereConfig.METEOR_SHOWER_RATE.get()) : 1.0;
        if (nextMeteorAt < 0 || nextMeteorAt > seconds + 120) nextMeteorAt = seconds + (10 + RNG.nextDouble() * 40) / rate;
        if (seconds >= nextMeteorAt) {
            nextMeteorAt = seconds + (15 + RNG.nextDouble() * 45) / rate;
            for (int i = 0; i < SLOTS; i++)
                if (meteor[i] == null) {
                    meteor[i] = spawn(seconds, days, f, shower);
                    break;
                }
        }
        double gx = 0, gy = 0, gz = 0;
        for (int i = 0; i < SLOTS; i++) {
            double[] m = meteor[i];
            if (m == null) continue;
            double t = seconds - m[0];
            if (t < 0 || t > m[1]) {
                meteor[i] = null;
                continue;
            }
            double fade = Math.sin(Math.PI * t / m[1]);
            double head = m[2] * t, tail = Math.max(0.0, head - m[3] * Math.min(1.0, t / (m[1] * 0.4)));
            double[] h = arc(m, head), tl = arc(m, tail);
            float e = (float) (m[10] * fade);
            out[i * 4] = (float) h[0];
            out[i * 4 + 1] = (float) h[1];
            out[i * 4 + 2] = (float) h[2];
            out[i * 4 + 3] = e;
            out[16 + i * 4] = (float) tl[0];
            out[16 + i * 4 + 1] = (float) tl[1];
            out[16 + i * 4 + 2] = (float) tl[2];
            out[16 + i * 4 + 3] = (float) m[11];
            // its light on the land (the head's: a point), through the air (above the horizon only), lifted as the sky's
            if (h[1] > 0) {
                double w = e * gain * Math.min(1.0, h[1] * 8.0);
                gx += w * h[0];
                gy += w * h[1];
                gz += w * h[2];
                for (int c = 0; c < 3; c++) {
                    meteorLight[c] += (float) (w * 0.282095);
                }
            }
        }
        for (int c = 0; c < 3; c++) {
            meteorLight[4 + c] = (float) (gx * 0.488603);
            meteorLight[8 + c] = (float) (gy * 0.488603);
            meteorLight[12 + c] = (float) (gz * 0.488603);
        }
        return out;
    }

    private static double[] arc(double[] m, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[] {m[4] * c + m[7] * s, m[5] * c + m[8] * s, m[6] * c + m[9] * s};
    }

    private static double[] spawn(double seconds, double days, NightSkyMath.Frame f, boolean shower) {
        double[] m = new double[12];
        // magnitudes: sporadic ones mostly 3 .. -1; one in thirty a fireball (-4 .. -8), one in three hundred a bolide
        // (to -12: the land lit as by a moon for a moment); showers brighter on average
        double u = RNG.nextDouble();
        double mag = shower ? 3.5 - 5.5 * Math.pow(u, 0.6) : 3.5 - 4.5 * Math.pow(u, 0.8);
        double r = RNG.nextDouble();
        if (r < (shower ? 0.06 : 0.03)) mag = -4 - 4 * RNG.nextDouble();
        if (r < (shower ? 0.008 : 0.003)) mag = -8 - 4 * RNG.nextDouble();
        boolean fireball = mag < -3.5;
        m[0] = seconds;
        m[1] = fireball ? 1.8 + RNG.nextDouble() * 2.2 : 0.45 + RNG.nextDouble() * 0.6;
        m[2] = Math.toRadians(fireball ? 8 + RNG.nextDouble() * 10 : 22 + RNG.nextDouble() * 26);
        m[3] = Math.toRadians(fireball ? 12 + RNG.nextDouble() * 14 : 6 + RNG.nextDouble() * 8);
        double az = RNG.nextDouble() * Math.PI * 2, el = Math.toRadians(25 + RNG.nextDouble() * 50);
        double[] start = {Math.sin(az) * Math.cos(el), Math.sin(el), Math.cos(az) * Math.cos(el)};
        double[] travel;
        if (shower) {
            // away from the radiant (fixed among the stars: north-east, high), as a shower's meteors are
            double[] cel = NightSkyMath.celestial(f.sunAngle(), f.pathDeg(), days);
            double[] rc = {0.35, 0.55, -0.76};
            double[] rad = {cel[0] * rc[0] + cel[1] * rc[1] + cel[2] * rc[2], cel[3] * rc[0] + cel[4] * rc[1] + cel[5] * rc[2],
                    cel[6] * rc[0] + cel[7] * rc[1] + cel[8] * rc[2]};
            double k = rad[0] * start[0] + rad[1] * start[1] + rad[2] * start[2];
            travel = new double[] {start[0] * k - rad[0], start[1] * k - rad[1], start[2] * k - rad[2]};
        } else {
            double side = RNG.nextBoolean() ? 1 : -1;
            double hx = Math.cos(az) * side, hz = -Math.sin(az) * side;
            double dx = -Math.sin(az) * Math.sin(el), dy = Math.cos(el), dz = -Math.cos(az) * Math.sin(el);
            double drop = 0.35 + RNG.nextDouble() * 0.4;
            travel = new double[] {hx - dx * drop, -dy * drop, hz - dz * drop};
        }
        // the travel tangent at the start, perpendicular to it
        double k = travel[0] * start[0] + travel[1] * start[1] + travel[2] * start[2];
        travel = new double[] {travel[0] - start[0] * k, travel[1] - start[1] * k, travel[2] - start[2] * k};
        double l = Math.sqrt(travel[0] * travel[0] + travel[1] * travel[1] + travel[2] * travel[2]);
        if (l < 1e-6) travel = new double[] {1, 0, 0};
        else for (int i = 0; i < 3; i++) travel[i] /= l;
        System.arraycopy(start, 0, m, 4, 3);
        System.arraycopy(travel, 0, m, 7, 3);
        m[10] = NightSkyMath.STAR_E0 * Math.pow(10, -0.4 * mag);
        m[11] = fireball ? 0.35 * RNG.nextDouble() : 0.1 * RNG.nextDouble();  // green from magnesium in fireballs
        return m;
    }

    /** A shower's night: within a day of one of the configured days of the year. */
    static boolean showerNight(double days) {
        double year = days % NightSkyMath.YEAR_DAYS;
        for (String s : AtmosphereConfig.METEOR_SHOWERS.get().split(",")) {
            try {
                double d = Double.parseDouble(s.trim());
                if (Math.abs(year - d) < 1.0) return true;
            } catch (NumberFormatException ignored) {
                // (an unreadable entry: no shower)
            }
        }
        return false;
    }

}
