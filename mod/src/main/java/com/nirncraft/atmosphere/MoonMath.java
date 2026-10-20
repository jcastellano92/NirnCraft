package com.nirncraft.atmosphere;

import java.util.Locale;

/**
 * The moons as bodies on orbits, and what they hide (shaders/include/atlas_moons.glsl draws them; {@link NightSkyMath}
 * places them). Pure: no Minecraft classes, any thread; {@code main} lists the eclipses (tools/dev/sky_offline.py).
 *
 * <ul>
 *   <li><b>Orbits.</b> Masser and Secunda go round on the sun's path in their months (8 and 9 days: NightSkyMath's
 *   moonCycle), each orbit inclined to the sun's path ({@link #latitude}): the ascending node regresses slowly and the sun
 *   goes round the year, so a new moon passes over or under the sun except in two eclipse seasons a year.</li>
 *   <li><b>Eclipses</b> ("Vampire Days"): a moon's disc over the sun's ({@link #covered}: the discs' real overlap). Masser
 *   (21 degrees across) hides the sun for hours several times a year, Secunda a few times; the land darkens through the
 *   same light (AtlasLook), the air's sky with it (atlas_air.glsl), the corona and a ring of light round the occulting
 *   limb show (atlas_moons.glsl).</li>
 *   <li><b>The Dark Moon</b> (the Hidden Moon, Lorkhaj's corpse): only in a dark eclipse, when Masser and Secunda both
 *   cover the sun, a third dark disc stands over it, its rim lit by the corona. Rare: both new moons near the same hour
 *   (every 72 days) and both near their nodes.</li>
 *   <li><b>The Necromancer's Moon</b> (the Revenant): a small dark body that hides the planet Arkay for a day every eight
 *   days ({@link #revenant}); the planets give Arkay's place (CelestialSky.arkayDirection). Behind
 *   AtmosphereConfig.NECROMANCER_MOON (off).</li>
 * </ul>
 *
 * <p>For the other sky bodies: {@link #occulters} lists every disc that hides what is behind it (direction, angular
 * radius), atlas_moons.glsl's moonCover(dir, pix) is the same per direction in the shader.</p>
 */
public final class MoonMath {
    private MoonMath() {}

    /** The sun's angular radius (atlas_sky.fsh's disc, high in the sky). */
    public static final double SUN_RADIUS = 0.0089;
    /** The orbits' inclinations to the sun's path. */
    public static final double MASSER_INCLINATION = Math.toRadians(50.0), SECUNDA_INCLINATION = Math.toRadians(21.0);
    /** The ascending nodes' longitude from the sun's at day 0 (rad), and their regression periods (days). */
    private static final double MASSER_NODE0 = 0.55, SECUNDA_NODE0 = 0.62;
    private static final double MASSER_NODE_DAYS = 3310.0, SECUNDA_NODE_DAYS = 3310.0;
    /** The Dark Moon's angular radius. */
    public static final double DARK_MOON_RADIUS = Math.toRadians(6.5);
    /** The Necromancer's Moon: its angular radius, its period (days) and the first day it covers Arkay. */
    public static final double REVENANT_RADIUS = Math.toRadians(0.6), REVENANT_PERIOD = 8.0, REVENANT_EPOCH = 3.0;
    /** MoonInfo's size in floats (atlas_moons.glsl). */
    public static final int BLOCK_FLOATS = 16;

    /** A moon's latitude above the sun's path (rad) at its elongation from the sun along the path (rad). */
    public static double latitude(boolean secunda, double days, double elongation) {
        double i = secunda ? SECUNDA_INCLINATION : MASSER_INCLINATION;
        // the node's longitude from the sun: the node regresses, the sun goes round in a year
        double node = (secunda ? SECUNDA_NODE0 : MASSER_NODE0)
                - 2.0 * Math.PI * days / (secunda ? SECUNDA_NODE_DAYS : MASSER_NODE_DAYS)
                - 2.0 * Math.PI * days / NightSkyMath.YEAR_DAYS;
        return Math.asin(Math.sin(i) * Math.sin(elongation - node));
    }

    /** The share of the sun's disc (0..1) a disc of angular radius r towards dir covers: the discs' overlap. */
    public static double covered(double[] sun, double[] dir, double r) {
        double rs = SUN_RADIUS;
        double d = Math.acos(Math.max(-1.0, Math.min(1.0, NightSkyMath.dot(sun, dir))));
        if (d >= rs + r) return 0.0;
        if (d <= r - rs) return 1.0;
        if (d <= rs - r) return (r * r) / (rs * rs);
        double a = rs * rs * Math.acos((d * d + rs * rs - r * r) / (2 * d * rs));
        double b = r * r * Math.acos((d * d + r * r - rs * rs) / (2 * d * r));
        double c = 0.5 * Math.sqrt(Math.max(0.0, (-d + rs + r) * (d + rs - r) * (d - rs + r) * (d + rs + r)));
        return Math.min(1.0, (a + b - c) / (Math.PI * rs * rs));
    }

    /** The sun hidden now: its share hidden (0..1), the dark eclipse (0..1: both moons over it), the Dark Moon's place. */
    public record Eclipse(double hidden, double dark, double[] darkDir) {}

    public static Eclipse eclipse(double[] sun, NightSkyMath.Moon masser, NightSkyMath.Moon secunda, boolean secundaOn) {
        double fm = covered(sun, masser.dir(), masser.radius());
        double fs = secundaOn ? covered(sun, secunda.dir(), secunda.radius()) : 0.0;
        double hidden = 1.0 - (1.0 - fm) * (1.0 - fs);
        double dark = NightSkyMath.smooth(0.98, 1.0, fm) * NightSkyMath.smooth(0.98, 1.0, fs);
        // the Dark Moon over the sun, a little off it towards Secunda (its corona rim lopsided, as a real one)
        double[] toward = NightSkyMath.sub(secunda.dir(), sun);
        double[] darkDir = NightSkyMath.norm(new double[] {sun[0] + toward[0] * 0.15, sun[1] + toward[1] * 0.15,
                sun[2] + toward[2] * 0.15});
        return new Eclipse(hidden, dark, darkDir);
    }

    /** How much of the sun's light reaches the land now (1 - its hidden share), for the light (AtlasLook). */
    public static double sunVisible(double days, double[] sun, double pathDeg, boolean secundaOn) {
        var m = NightSkyMath.moon(false, days, sun, pathDeg);
        var s = NightSkyMath.moon(true, days, sun, pathDeg);
        return 1.0 - eclipse(sun, m, s, secundaOn).hidden();
    }

    /**
     * The Necromancer's Moon over Arkay: {dir xyz, angular radius, strength 0..1} while it hides the planet (a day every
     * {@link #REVENANT_PERIOD} days, crossing it from one side to the other, the planet hidden the whole day), else null.
     * arkay: towards the planet (the planets' ephemeris), null when there is none.
     */
    public static double[] revenant(double days, double[] arkay) {
        if (arkay == null) return null;
        double ph = days - REVENANT_EPOCH;
        ph -= Math.floor(ph / REVENANT_PERIOD) * REVENANT_PERIOD;
        if (ph >= 1.0) return null;
        double[] a = NightSkyMath.norm(arkay);
        double[] ref = Math.abs(a[1]) < 0.95 ? new double[] {0, 1, 0} : new double[] {1, 0, 0};
        double[] t = NightSkyMath.norm(NightSkyMath.cross(ref, a));
        double off = (ph - 0.5) * 1.2 * REVENANT_RADIUS;
        double[] d = NightSkyMath.norm(new double[] {a[0] + t[0] * off, a[1] + t[1] * off, a[2] + t[2] * off});
        double strength = NightSkyMath.smooth(0.0, 0.04, ph) * NightSkyMath.smooth(1.0, 0.96, ph);
        return new double[] {d[0], d[1], d[2], REVENANT_RADIUS, strength};
    }

    /** Every disc that hides what is behind it now: {dir xyz, angular radius} each (Masser, Secunda when shown, the
     *  Dark Moon in a dark eclipse, the Revenant over Arkay): for the stars' and planets' occultation. */
    public static double[][] occulters(NightSkyMath.Moon masser, NightSkyMath.Moon secunda, boolean secundaOn,
                                       Eclipse e, double[] revenant) {
        java.util.List<double[]> o = new java.util.ArrayList<>();
        o.add(new double[] {masser.dir()[0], masser.dir()[1], masser.dir()[2], masser.radius()});
        if (secundaOn) o.add(new double[] {secunda.dir()[0], secunda.dir()[1], secunda.dir()[2], secunda.radius()});
        if (e != null && e.dark() > 0) o.add(new double[] {e.darkDir()[0], e.darkDir()[1], e.darkDir()[2], DARK_MOON_RADIUS});
        if (revenant != null) o.add(new double[] {revenant[0], revenant[1], revenant[2], revenant[3]});
        return o.toArray(new double[0][]);
    }

    /** MoonInfo (atlas_moons.glsl): the eclipse, the Dark Moon, the Revenant. */
    public static float[] block(Eclipse e, double[] revenant) {
        float[] o = new float[BLOCK_FLOATS];
        o[0] = (float) e.hidden();
        o[1] = (float) e.dark();
        o[2] = (float) SUN_RADIUS;
        o[3] = (float) NightSkyMath.smooth(0.5, 1.0, e.hidden());  // the ring round the occulting limb
        if (e.dark() > 0) {
            o[4] = (float) e.darkDir()[0];
            o[5] = (float) e.darkDir()[1];
            o[6] = (float) e.darkDir()[2];
            o[7] = (float) DARK_MOON_RADIUS;
        }
        if (revenant != null) {
            o[8] = (float) revenant[0];
            o[9] = (float) revenant[1];
            o[10] = (float) revenant[2];
            o[11] = (float) revenant[3];
            o[12] = (float) revenant[4];
        }
        return o;
    }

    /**
     * The offline sky's scene finder (tools/dev/sky_offline.py). {@code eclipses from to [path]}: one JSON object a line
     * per eclipse over the span (days): {day, sunAngle, hidden, dark, masser: covered, secunda: covered, sunY}.
     * {@code moons from to step [path]}: the moons every step (days): {day, sunY, masser [x, y, z, lit], secunda [...]}.
     */
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("moons")) {
            double from = Double.parseDouble(args[1]), to = Double.parseDouble(args[2]), step = Double.parseDouble(args[3]);
            double path = args.length > 4 ? Double.parseDouble(args[4]) : -25.0;
            StringBuilder b = new StringBuilder();
            for (double day = from; day < to; day += step) {
                double[] sun = NightSkyMath.sunDirection(NightSkyMath.vanillaSunAngle(day * 24000.0), path);
                var m = NightSkyMath.moon(false, day, sun, path);
                var s = NightSkyMath.moon(true, day, sun, path);
                b.append(String.format(Locale.ROOT, "{\"day\":%.5f,\"sunY\":%.4f,\"masser\":[%.4f,%.4f,%.4f,%.4f],"
                        + "\"secunda\":[%.4f,%.4f,%.4f,%.4f]}%n", day, sun[1], m.dir()[0], m.dir()[1], m.dir()[2], m.lit(),
                        s.dir()[0], s.dir()[1], s.dir()[2], s.lit()));
            }
            System.out.print(b);
            return;
        }
        if (args.length > 0 && args[0].equals("eclipses")) args = java.util.Arrays.copyOfRange(args, 1, args.length);
        double from = args.length > 0 ? Double.parseDouble(args[0]) : 0.0;
        double to = args.length > 1 ? Double.parseDouble(args[1]) : NightSkyMath.YEAR_DAYS;
        double path = args.length > 2 ? Double.parseDouble(args[2]) : -25.0;
        double step = 1.0 / 480.0;  // 3 minutes
        boolean in = false;
        double best = 0, bestDay = 0, bestDark = 0;
        for (double day = from; day < to; day += step) {
            double sunAngle = NightSkyMath.vanillaSunAngle(day * 24000.0);
            double[] sun = NightSkyMath.sunDirection(sunAngle, path);
            var m = NightSkyMath.moon(false, day, sun, path);
            var s = NightSkyMath.moon(true, day, sun, path);
            Eclipse e = eclipse(sun, m, s, true);
            if (e.hidden() > 0.0) {
                if (!in || e.hidden() + e.dark() > best) {  // (the deepest moment: a dark eclipse's when both cover it)
                    best = e.hidden() + e.dark();
                    bestDay = day;
                }
                bestDark = Math.max(bestDark, e.dark());
                in = true;
            } else if (in) {
                double sa = NightSkyMath.vanillaSunAngle(bestDay * 24000.0);
                double[] sn = NightSkyMath.sunDirection(sa, path);
                var mm = NightSkyMath.moon(false, bestDay, sn, path);
                var ss = NightSkyMath.moon(true, bestDay, sn, path);
                System.out.println(String.format(Locale.ROOT,
                        "{\"day\":%.4f,\"sunAngle\":%.5f,\"hidden\":%.4f,\"dark\":%.3f,\"masser\":%.4f,\"secunda\":%.4f,\"sunY\":%.3f}",
                        bestDay, sa, Math.min(best, 1.0), bestDark, covered(sn, mm.dir(), mm.radius()), covered(sn, ss.dir(), ss.radius()), sn[1]));
                in = false;
                best = 0;
                bestDark = 0;
            }
        }
    }
}
