package com.nirncraft.atmosphere;

import java.util.Arrays;
import java.util.Locale;

/**
 * The sky's own lights as one model (shaders/include/atlas_night.glsl evaluates them per direction; this class computes
 * what it is given): the celestial frame (stars, galaxy, birthsigns: turning with the sun's path, plus the sidereal drift
 * of a turn a year), Masser and Secunda (their directions on the sun's path, their phases and light, the brighter one the
 * night's key light, the other a fill in its own colour), the auroral oval over Tamriel's map (its pole north of Atmora,
 * the sky's geography at {@link #AURORA_KM_PER_BLOCK}; strength and reach from the night's activity: the space weather
 * hook), and the night's lift (the emitters' scale against the host's moon). Pure: no Minecraft classes, any thread,
 * Tier 0 tests; {@code main} prints a frame's blocks for the offline sky (tools/dev/sky_offline.py).
 *
 * <p>Units: the air's (atlas_air.glsl: the sun's irradiance above the air ~ 11.8, so 1 unit ~ 10.8 klx). The moons'
 * light is the host's (LunarKey's scale, kept: the far field and the near pass light with it); every other emitter is
 * physical times {@link #nightGain}: the moon's light over a real full moon's, so their ratios to the moon are real.</p>
 */
public final class NightSkyMath {
    private NightSkyMath() {}

    /** 1 lux in the air's units (the sun's 128 klx above the air is its irradiance ~ 11.8). */
    public static final double LUX = 1.0 / 10830.0;
    /** A real full moon's illuminance above the air (0.267 lx). */
    public static final double REAL_FULL_MOON = 0.267 * LUX;
    /** A magnitude-0 star's irradiance. */
    public static final double STAR_E0 = 2.54e-6 * LUX;
    /** The sky's geography: Tamriel's map at about its lore scale (Atmora to Elsweyr ~ 1250 km), for what stands far
     *  above it (the aurora): not the far field's metre blocks, whose curvature is the planet's. */
    public static final double AURORA_KM_PER_BLOCK = 0.1;
    /** The oval's radius round its pole (km): ~ 22 degrees of a real planet's latitude. */
    public static final double AURORA_OVAL_RADIUS = 2400.0;

    // the moons: angular radii of their discs (the old quads: 20 and 7 at 100 blocks, the disc 0.916 of the quad: Masser
    // ~21 degrees across, Secunda ~7, three times its size), their full light (LunarKey's scale: pi x 1e-5 x the share,
    // physical: Masser full ~ a real full moon), colours, and their albedo maps' mean (linear, over the face towards
    // Nirn: masser_albedo.png / secunda_albedo.png, tools/dev/moon_gen.py bakes them to these)
    public static final double MASSER_RADIUS = Math.atan(0.916 * 20.0 / 100.0);
    public static final double SECUNDA_RADIUS = Math.atan(0.91 * 7.0 / 100.0);
    public static final float[] MASSER_COLOUR = {1.0f, 0.70f, 0.62f};
    public static final float[] SECUNDA_COLOUR = {0.93f, 0.96f, 1.0f};
    public static final double MASSER_SHARE = 0.80, SECUNDA_SHARE = 0.20, MOON_SCALE = 1.0e-5;
    /** By day a moon's disc shows its sunlit regolith, faintly (this share of a real surface's brightness, an
     *  albedo-1 surface under the sun ~ 3.74): a pale disc on the blue. By night its light is its key light's. */
    private static final double DAY_DISC = 0.05, SUNLIT_SURFACE = 3.74;
    private static final double[] MASSER_ALBEDO = {0.1708, 0.1304, 0.1494};
    private static final double[] SECUNDA_ALBEDO = {0.1077, 0.1110, 0.1063};
    /** The Serpent drifts once round the sky in this many days, out of step with the other stars. */
    public static final double SERPENT_PERIOD_DAYS = 27.0;
    public static final double YEAR_DAYS = 365.25;

    /** NightInfo's size in floats (atlas_night.glsl): 2 mat4 + 11 vec4 + 8 meteor vec4 + NightTarget. */
    public static final int BLOCK_FLOATS = 32 + 11 * 4 + 8 * 4 + 4;
    /** NightSigns' size in floats: 14 centres + 112 stars + 56 line quads. */
    public static final int SIGNS_FLOATS = (14 + 112 + 56) * 4;

    /**
     * The thirteen birthsigns as original star figures. Each row: name, centre angle around the celestial equator
     * (degrees), offset off the equator (-1..1, along the celestial pole axis), star points (x, y in units of
     * {@link #SIGN_UNIT} degrees), then index pairs joined by faint lines. The Serpent last (it wanders).
     */
    public static final Object[][] SIGNS = {
            {"warrior", 10f, 0.30f, new float[]{0, -3, 0, -1, 0, 1, 0, 3.5f, -1.5f, 1, 1.5f, 1, 0, 4.5f}, new int[]{0, 1, 1, 2, 2, 3, 4, 2, 2, 5, 3, 6}},
            {"mage", 38f, -0.25f, new float[]{0, -3.5f, 0, -1, 0, 1.5f, -1, 2.5f, 0, 3.5f, 1, 2.5f}, new int[]{0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 2}},
            {"thief", 66f, 0.40f, new float[]{-2.5f, -1, -1, 1, 0, -0.5f, 1, 1.5f, 2.5f, 0}, new int[]{0, 1, 1, 2, 2, 3, 3, 4}},
            {"lady", 94f, -0.10f, new float[]{0, 3, -1, 1.5f, 1, 1.5f, -2, -2, 2, -2, 0, 0}, new int[]{0, 1, 0, 2, 1, 5, 2, 5, 5, 3, 5, 4, 3, 4}},
            {"steed", 122f, 0.35f, new float[]{-3, -1, -1.5f, 0, 0, 0.5f, 1.5f, 2, 2.5f, 1, 1, -1, -1, -2}, new int[]{0, 1, 1, 2, 2, 3, 3, 4, 2, 5, 1, 6}},
            {"lord", 150f, -0.35f, new float[]{-2, 0, -1, 1.5f, 0, 0.5f, 1, 1.5f, 2, 0, 0, -2}, new int[]{0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0}},
            {"apprentice", 178f, 0.15f, new float[]{0, 0, 0, 2, 1.5f, -1, -1.5f, -1, 0, -2.5f}, new int[]{0, 1, 0, 2, 0, 3, 0, 4}},
            {"atronach", 206f, -0.30f, new float[]{0, 2, 1.4f, 1.4f, 2, 0, 1.4f, -1.4f, 0, -2, -1.4f, -1.4f, -2, 0, -1.4f, 1.4f}, new int[]{0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 0}},
            {"ritual", 234f, 0.30f, new float[]{-2, 2, -2.5f, 0, -1.5f, -2, 0.5f, -2.5f, 2, -1.5f, 0, 0}, new int[]{0, 1, 1, 2, 2, 3, 3, 4, 2, 5}},
            {"lover", 262f, -0.15f, new float[]{-2, 1, -1, 2, 0, 1, 1, 2, 2, 1, 0, -2}, new int[]{0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0}},
            {"shadow", 290f, 0.40f, new float[]{0, 2.5f, -2, -2, 2, -2, 0, -0.5f}, new int[]{0, 1, 1, 3, 3, 2, 2, 0}},
            {"tower", 318f, -0.25f, new float[]{-1, -3, -1, 2, 1, 2, 1, -3, -1, 3, 0, 2.5f, 1, 3}, new int[]{0, 1, 1, 2, 2, 3, 1, 4, 4, 5, 5, 6, 6, 2}},
            {"serpent", 0f, 0f, new float[]{-4, 0, -3, 1, -1.5f, 0.5f, 0, -0.5f, 1.5f, -0.5f, 3, 0.5f, 4, 1.5f}, new int[]{0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6}},
    };
    public static final float SIGN_UNIT = 1.9F;

    // ------------------------------------------------------------------------------------------------ the frame

    /** World direction towards the sun: Ry(-90) Rz(path) Rx(sunAngle) +Y (AtlasClient.sunDirection). */
    public static double[] sunDirection(double sunAngle, double pathDeg) {
        double r = Math.toRadians(pathDeg);
        return new double[] {-Math.sin(sunAngle), Math.cos(r) * Math.cos(sunAngle), -Math.sin(r) * Math.cos(sunAngle)};
    }

    /** Ry(-90) Rz(path) Rx(angle) as a row-major 3 x 3 (celestial -> world): the sun's frame, any angle. */
    public static double[] skyFrame(double angle, double pathDeg) {
        double t = Math.toRadians(pathDeg), ca = Math.cos(angle), sa = Math.sin(angle), ct = Math.cos(t), st = Math.sin(t);
        // Rz(t) Rx(a): columns e_x -> (ct, st, 0), e_y -> (-st ca, ct ca, sa), e_z -> (st sa, -ct sa, ca)
        double[][] m = {{ct, -st * ca, st * sa}, {st, ct * ca, -ct * sa}, {0, sa, ca}};
        // Ry(-90): (x, y, z) -> (-z, y, x)
        return new double[] {-m[2][0], -m[2][1], -m[2][2], m[1][0], m[1][1], m[1][2], m[0][0], m[0][1], m[0][2]};
    }

    /** The celestial frame (stars, galaxy, birthsigns) -> world: the sun's frame turned on by the sidereal drift. */
    public static double[] celestial(double sunAngle, double pathDeg, double days) {
        return skyFrame(sunAngle + 2.0 * Math.PI * days / YEAR_DAYS, pathDeg);
    }

    /** The Serpent's frame -> world: the celestial frame, then its own wander (SkyRenderer's: Rx(days / 27 turns), Rz). */
    public static double[] serpent(double sunAngle, double pathDeg, double days) {
        double[] c = celestial(sunAngle, pathDeg, days);
        double a = 2.0 * Math.PI * (days / SERPENT_PERIOD_DAYS % 1.0), b = Math.toRadians(Math.sin(days * 0.7) * 18.0);
        double[] rx = {1, 0, 0, 0, Math.cos(a), -Math.sin(a), 0, Math.sin(a), Math.cos(a)};
        double[] rz = {Math.cos(b), -Math.sin(b), 0, Math.sin(b), Math.cos(b), 0, 0, 0, 1};
        return mul(mul(c, rx), rz);
    }

    // ------------------------------------------------------------------------------------------------ the moons

    /** A moon now: towards it, its disc's up, its angular radius, the lit fraction, its light (rgb irradiance above the
     *  air, LunarKey's scale, faded at its horizon as LunarKey's), the disc's radiance per unit albedo at full, its
     *  colour over its albedo map's (so the disc's light is its light). */
    public record Moon(double[] dir, double[] up, double radius, double lit, double[] light, double discScale, double[] tint) {}

    /** The moons' months (days): about eight and nine, a little over so their phases drift through the hours of the
     *  day (a new moon at noon some months, an eclipse then: MoonMath), full near midnight on day 0. */
    public static final double MASSER_MONTH = 8.2, SECUNDA_MONTH = 9.15;

    public static double moonCycle(double days, boolean secunda) {
        double c = (days - 0.75) / (secunda ? SECUNDA_MONTH : MASSER_MONTH) + (secunda ? 0.375 : 0.0);
        return c - Math.floor(c);
    }

    public static Moon moon(boolean secunda, double days, double[] sun, double pathDeg) {
        double tilt = Math.toRadians(pathDeg);
        double[] axis = {0.0, Math.sin(tilt), Math.cos(tilt)};
        double[] anti = {-sun[0], -sun[1], -sun[2]};
        double cycle = moonCycle(days, secunda);
        double[] d = rotate(anti, axis, cycle * Math.PI * 2);
        // its orbit's inclination (MoonMath: the node, the year): over or under the sun's path, eclipses in their seasons
        double beta = MoonMath.latitude(secunda, days, Math.PI + cycle * Math.PI * 2);
        d = norm(new double[] {d[0] * Math.cos(beta) + axis[0] * Math.sin(beta), d[1] * Math.cos(beta) + axis[1] * Math.sin(beta),
                d[2] * Math.cos(beta) + axis[2] * Math.sin(beta)});
        double[] up = sub(axis, scale(d, dot(axis, d)));
        up = norm(up);
        double lit = 0.5 - 0.5 * dot(sun, d);
        double horizon = smooth(-0.035, 0.10, d[1]);
        float[] col = secunda ? SECUNDA_COLOUR : MASSER_COLOUR;
        double share = secunda ? SECUNDA_SHARE : MASSER_SHARE;
        double full = Math.PI * MOON_SCALE * share;  // irradiance per colour at full
        double w = lit * lit * horizon;  // (LunarKey's phase law: the lit fraction, squared: a quarter moon a quarter)
        double[] light = {full * col[0] * w, full * col[1] * w, full * col[2] * w};
        double radius = secunda ? SECUNDA_RADIUS : MASSER_RADIUS;
        double omega = 2.0 * Math.PI * (1.0 - Math.cos(radius));
        double[] alb = secunda ? SECUNDA_ALBEDO : MASSER_ALBEDO;
        double albLum = lum(alb[0], alb[1], alb[2]);
        double colLum = lum(col[0], col[1], col[2]);
        double discScale = full * colLum / (omega * albLum);
        double[] tint = {col[0] / colLum / (alb[0] / albLum), col[1] / colLum / (alb[1] / albLum), col[2] / colLum / (alb[2] / albLum)};
        return new Moon(d, up, radius, lit, light, discScale, tint);
    }

    /** The night's two moonlights: the brighter moon the key (shadows), the other the fill, each in its own colour
     *  (a smooth hand-over where they are nearly equal). {key dir xyz, key irradiance rgb, fill dir xyz, fill rgb}. */
    public static double[] moonlights(Moon masser, Moon secunda, boolean secundaOn) {
        double wm = lum(masser.light[0], masser.light[1], masser.light[2]);
        double ws = secundaOn ? lum(secunda.light[0], secunda.light[1], secunda.light[2]) : 0.0;
        double s = smooth(0.8, 1.25, ws / Math.max(wm, 1e-9));
        double[] o = new double[12];
        for (int i = 0; i < 3; i++) {
            o[i] = masser.dir[i] + (secunda.dir[i] - masser.dir[i]) * s;
            o[6 + i] = secunda.dir[i] + (masser.dir[i] - secunda.dir[i]) * s;
            double lm = masser.light[i], ls = secundaOn ? secunda.light[i] : 0.0;
            o[3 + i] = lm + (ls - lm) * s;
            o[9 + i] = ls + (lm - ls) * s;
        }
        double[] k = norm(new double[] {o[0], o[1], o[2]}), f = norm(new double[] {o[6], o[7], o[8]});
        System.arraycopy(k, 0, o, 0, 3);
        System.arraycopy(f, 0, o, 6, 3);
        return o;
    }

    // ------------------------------------------------------------------------------------------------ the night's lift

    /** The emitters' scale: 1 by day (physical against the sun), the moon's lift by night (its light over a real full
     *  moon's: the emitters keep their real ratios to the host's moon), eased in log while the sun goes down. With the
     *  physical moon (LunarKey x 1e-5, pre-exposure carrying the range) it is ~0.8: the emitters at their real levels. */
    public static double nightGain(double sunY) {
        double masserFull = Math.PI * MOON_SCALE * MASSER_SHARE * lum(MASSER_COLOUR[0], MASSER_COLOUR[1], MASSER_COLOUR[2]);
        double g = masserFull / REAL_FULL_MOON;
        return Math.exp(Math.log(g) * smooth(0.05, -0.18, sunY));
    }

    // ------------------------------------------------------------------------------------------------ the aurora

    /** The oval tonight: its pole (east, south km), radius, half-width (km), strength (1 ~ 100 kR green at the
     *  brightest curtain, IBC III), redness, the nitrogen fringe, its drift (km/s). */
    public record Aurora(double poleX, double poleZ, double radius, double width, double strength, double red, double fringe,
                         double drift) {}

    /**
     * The aurora from the space weather: kp 0..9 (quiet .. a great storm; the hook: NightSky derives it from the
     * night's activity until weather drives it). The oval lies over Atmora when quiet and reaches south with the
     * storm (kp 6: northern Skyrim overhead); everywhere: overhead wherever the eye is (the option).
     */
    public static Aurora aurora(double kp, double brightness, double red, boolean everywhere, double camZ) {
        double overheadZ = everywhere ? camZ : -7600.0 + 620.0 * kp;  // blocks: where the band stands overhead
        double strength = brightness * (0.06 + 0.16 * kp);
        double width = 140.0 + 22.0 * kp;
        double r = Math.max(red, smooth(5.0, 8.5, kp));
        return new Aurora(0.0, overheadZ * AURORA_KM_PER_BLOCK - AURORA_OVAL_RADIUS, AURORA_OVAL_RADIUS, width, strength, r,
                smooth(3.0, 7.0, kp), 0.4 + 0.15 * kp);
    }

    /** The night's activity (0.5 .. 1, SkyRenderer.activity) as kp 0 .. 7. */
    public static double kpOf(double activity) {
        return Math.max(0.0, Math.min(1.0, (activity - 0.5) / 0.5)) * 7.0;
    }

    // ------------------------------------------------------------------------------------------------ the blocks

    /** Everything NightInfo carries but the meteors (NightSky's simulation fills their slots). */
    public record Frame(double sunAngle, double pathDeg, double days, double seconds, int frameIndex, double camX,
                        double camZ, double eyeKm, double clear, double stars, double galaxy, double signs, double twinkle,
                        boolean secundaOn, Aurora aurora) {}

    /** NightInfo (std140: columns of the matrices, then the vec4s), meteors: 4 x {head xyz, E, tail xyz, mix} or null. */
    public static float[] block(Frame f, float[] meteors) {
        double[] sun = sunDirection(f.sunAngle, f.pathDeg);
        float[] o = new float[BLOCK_FLOATS];
        // world -> frame: the transpose of frame -> world (a rotation); GLSL's mat3(m) * v with m given by columns:
        // column j of world->frame = row j of frame->world transposed... i.e. the element (r, c) of world->frame is
        // (c, r) of frame->world, stored column-major: o[c * 4 + r] = frameToWorld[c * 3 + r]
        putInverse(o, 0, celestial(f.sunAngle, f.pathDeg, f.days));
        putInverse(o, 16, serpent(f.sunAngle, f.pathDeg, f.days));
        int p = 32;
        p = put(o, p, nightGain(sun[1]), f.clear, f.seconds % 3600.0, f.frameIndex % 64);
        p = put(o, p, f.stars, f.galaxy, f.signs, f.twinkle);
        Moon m = moon(false, f.days, sun, f.pathDeg), s = moon(true, f.days, sun, f.pathDeg);
        for (Moon moon : new Moon[] {m, s}) {
            boolean on = moon == m || f.secundaOn;
            p = put(o, p, moon.dir[0], moon.dir[1], moon.dir[2], moon.radius);
            double day = DAY_DISC * SUNLIT_SURFACE * smooth(-0.10, 0.15, sun[1]);
            p = put(o, p, moon.up[0], moon.up[1], moon.up[2], moon.discScale + day);
            p = put(o, p, moon.tint[0], moon.tint[1], moon.tint[2], on ? 1.0 : 0.0);
        }
        double k = AURORA_KM_PER_BLOCK;
        Aurora a = f.aurora;
        p = put(o, p, f.camX * k, f.eyeKm, f.camZ * k, k);
        p = put(o, p, a.poleX, a.poleZ, a.radius, a.width);
        p = put(o, p, a.strength, a.red, a.fringe, a.drift);
        if (meteors != null) System.arraycopy(meteors, 0, o, p, Math.min(meteors.length, 32));
        return o;
    }

    /** NightTarget in a block: the pre-exposure a half-float target stores, its texel angle (rad). */
    public static void target(float[] block, double preExposure, double texelAngle) {
        block[BLOCK_FLOATS - 4] = (float) preExposure;
        block[BLOCK_FLOATS - 3] = (float) texelAngle;
    }

    /** NightSigns: the figures' centres, stars (magnitudes: each figure's first stars brightest) and lines. */
    public static float[] signs() {
        float[] o = new float[SIGNS_FLOATS];
        Arrays.fill(o, 0f);
        int centres = 0, stars = 14 * 4, lines = (14 + 112) * 4;
        for (int f = 0; f < SIGNS.length; f++) {
            Object[] sign = SIGNS[f];
            double ang = Math.toRadians((Float) sign[1]), off = (Float) sign[2];
            double[] c = norm(new double[] {off, Math.cos(ang), Math.sin(ang)});
            double[] u = norm(cross(c, norm(cross(new double[] {1, 0, 0}, c))));
            double[] v = norm(cross(c, u));
            float[] pts = (float[]) sign[3];
            int[] ln = (int[]) sign[4];
            double unit = Math.toRadians(SIGN_UNIT), maxAng = 0;
            for (int i = 0; i < 8; i++) {
                int q = stars + (f * 8 + i) * 4;
                if (i >= pts.length / 2) {
                    o[q + 3] = 99f;
                    continue;
                }
                double x = pts[i * 2] * unit, y = pts[i * 2 + 1] * unit;
                double[] d = norm(new double[] {c[0] + u[0] * y + v[0] * x, c[1] + u[1] * y + v[1] * x, c[2] + u[2] * y + v[2] * x});
                maxAng = Math.max(maxAng, Math.acos(Math.min(1.0, dot(c, d))));
                o[q] = (float) d[0];
                o[q + 1] = (float) d[1];
                o[q + 2] = (float) d[2];
                o[q + 3] = (float) (1.3 + 0.32 * i + 0.4 * ((f * 7 + i * 3) % 5) / 4.0);  // magnitudes 1.3 .. ~4
            }
            o[centres + f * 4] = (float) c[0];
            o[centres + f * 4 + 1] = (float) c[1];
            o[centres + f * 4 + 2] = (float) c[2];
            o[centres + f * 4 + 3] = (float) Math.cos(maxAng + Math.toRadians(1.5));
            for (int i = 0; i < 16; i++) {
                int q = lines + f * 16 + i;
                o[q] = i < ln.length ? ln[i] : -1;
            }
        }
        return o;
    }

    // ------------------------------------------------------------------------------------------------ the CLI

    /**
     * The offline sky's inputs (tools/dev/sky_offline.py): prints one line of JSON with NightInfo, NightSigns, the sun,
     * the moons' lights and the night's lift for a scene. Arguments, key=value: dayTime (ticks), sunAngle (radians, else
     * from dayTime as vanilla), path (degrees, -25), x, z (blocks), eyeKm, kp, brightness, red, everywhere, clear, secunda.
     */
    public static void main(String[] args) {
        java.util.Map<String, String> a = new java.util.HashMap<>();
        for (String s : args) {
            int i = s.indexOf('=');
            if (i > 0) a.put(s.substring(0, i), s.substring(i + 1));
        }
        double dayTime = Double.parseDouble(a.getOrDefault("dayTime", "18000"));
        double days = dayTime / 24000.0;
        double sunAngle = a.containsKey("sunAngle") ? Double.parseDouble(a.get("sunAngle")) : vanillaSunAngle(dayTime);
        double path = Double.parseDouble(a.getOrDefault("path", "-25"));
        double x = Double.parseDouble(a.getOrDefault("x", "0")), z = Double.parseDouble(a.getOrDefault("z", "0"));
        double kp = Double.parseDouble(a.getOrDefault("kp", "4"));
        boolean everywhere = Boolean.parseBoolean(a.getOrDefault("everywhere", "false"));
        boolean secundaOn = Boolean.parseBoolean(a.getOrDefault("secunda", "true"));
        Aurora au = aurora(kp, Double.parseDouble(a.getOrDefault("brightness", "1")),
                Double.parseDouble(a.getOrDefault("red", "0")), everywhere, z);
        Frame f = new Frame(sunAngle, path, days, Double.parseDouble(a.getOrDefault("seconds", "100")), 0, x, z,
                Double.parseDouble(a.getOrDefault("eyeKm", "0.15")), Double.parseDouble(a.getOrDefault("clear", "1")),
                1, 1, 1, 1, secundaOn, au);
        double[] sun = sunDirection(sunAngle, path);
        Moon m = moon(false, days, sun, path), s = moon(true, days, sun, path);
        StringBuilder b = new StringBuilder("{");
        b.append("\"night\":").append(Arrays.toString(block(f, null)));
        b.append(",\"signs\":").append(Arrays.toString(signs()));
        b.append(String.format(Locale.ROOT, ",\"sun\":[%f,%f,%f]", sun[0], sun[1], sun[2]));
        b.append(",\"moonlights\":").append(Arrays.toString(moonlights(m, s, secundaOn)));
        b.append(",\"moons\":").append(Arrays.toString(MoonMath.block(MoonMath.eclipse(sun, m, s, secundaOn), null)));
        b.append(String.format(Locale.ROOT, ",\"masserDir\":[%f,%f,%f],\"secundaDir\":[%f,%f,%f]", m.dir[0], m.dir[1],
                m.dir[2], s.dir[0], s.dir[1], s.dir[2]));
        b.append(String.format(Locale.ROOT, ",\"masserLit\":%f,\"secundaLit\":%f,\"gain\":%f}", m.lit, s.lit, nightGain(sun[1])));
        System.out.println(b);
    }

    /** Vanilla's sun angle for a day time (the overworld's: noon at 6000, the eased cycle), radians. */
    public static double vanillaSunAngle(double dayTime) {
        double d = (dayTime / 24000.0 - 0.25);
        d -= Math.floor(d);
        double e = 0.5 - Math.cos(d * Math.PI) / 2.0;
        return (d * 2.0 + e) / 3.0 * Math.PI * 2.0;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static int put(float[] o, int p, double a, double b, double c, double d) {
        o[p] = (float) a;
        o[p + 1] = (float) b;
        o[p + 2] = (float) c;
        o[p + 3] = (float) d;
        return p + 4;
    }

    /** A mat4 (column-major, std140) of the inverse (transpose) of a row-major frame -> world rotation. */
    private static void putInverse(float[] o, int at, double[] frameToWorld) {
        for (int c = 0; c < 3; c++)
            for (int r = 0; r < 3; r++) o[at + c * 4 + r] = (float) frameToWorld[c * 3 + r];
        o[at + 15] = 1f;
    }

    static double[] mul(double[] a, double[] b) {
        double[] o = new double[9];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
        return o;
    }

    public static double[] rotate(double[] p, double[] k, double a) {
        double c = Math.cos(a), s = Math.sin(a), kd = dot(k, p);
        double[] kx = cross(k, p);
        return new double[] {p[0] * c + kx[0] * s + k[0] * kd * (1 - c), p[1] * c + kx[1] * s + k[1] * kd * (1 - c),
                p[2] * c + kx[2] * s + k[2] * kd * (1 - c)};
    }

    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] sub(double[] a, double[] b) { return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }

    static double[] scale(double[] a, double s) { return new double[] {a[0] * s, a[1] * s, a[2] * s}; }

    static double[] norm(double[] a) {
        double l = Math.sqrt(dot(a, a));
        return l < 1e-12 ? new double[] {0, 1, 0} : scale(a, 1.0 / l);
    }

    public static double lum(double r, double g, double b) { return 0.2126 * r + 0.7152 * g + 0.0722 * b; }

    static double smooth(double e0, double e1, double x) {
        double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
}
