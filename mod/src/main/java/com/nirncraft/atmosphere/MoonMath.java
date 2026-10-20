package com.nirncraft.atmosphere;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The moons as bodies on orbits, and what they hide and shadow (shaders/include/atlas_moons.glsl draws them;
 * {@link NightSkyMath} places them). Pure: no Minecraft classes, any thread; {@code main} lists the events
 * (tools/dev/sky_offline.py). Everything comes from the geometry, continuous in time (no step a day).
 *
 * <ul>
 *   <li><b>Orbits.</b> Masser and Secunda go round in their months (about 8 and 9 days: NightSkyMath's moonCycle), each
 *   orbit inclined to the sun's path ({@link #latitude}): the ascending node regresses slowly and the sun goes round the
 *   year. Each moves against the stars at its own rate, rises and sets later every night, and their latitudes swing at
 *   different rates, so their paths cross. Masser keeps its distance (the unit here); Secunda's orbit is eccentric
 *   ({@link #distance}): nearer than Masser for part of its anomalistic month (in front of it when they meet, a little
 *   larger), farther the rest (behind it).</li>
 *   <li><b>Solar eclipses</b> ("Vampire Days"): a moon's disc over the sun's ({@link #covered}: the discs' real
 *   overlap); the land darkens through the same light (AtlasLook, the core's sun: AtlasFrame.sunDir's length), the
 *   air's sky with it, the corona and a ring of light round the occulting limb show.</li>
 *   <li><b>The Dark Moon</b> (the Hidden Moon, Lorkhaj's corpse): only in a dark eclipse, when Masser and Secunda both
 *   cover the sun, a third dark disc stands over it, its rim lit by the corona.</li>
 *   <li><b>Lunar eclipses</b>: a moon through Nirn's shadow ({@link #lunarShare}): the penumbra, then the umbra, where
 *   only the sunlight bent through Nirn's air reaches it, copper-red. Each moon's from its own orbit; its light on the
 *   land dims with it.</li>
 *   <li><b>Occultations and transit shadows</b>: the nearer moon passes in front of the farther; when the sun lines up,
 *   one's shadow crosses the other's face.</li>
 *   <li><b>Nirnshine</b>: Nirn's day side lights a moon's night side, most at a new moon (Nirn full from there).</li>
 *   <li><b>The Necromancer's Moon</b> (the Revenant): a small dark body that hides the planet Arkay for a day every eight
 *   days ({@link #revenant}); the planets give Arkay's place (CelestialSky.arkayDirection). Behind
 *   AtmosphereConfig.NECROMANCER_MOON (off).</li>
 * </ul>
 *
 * <p>For the other sky bodies: {@link #occulters} lists every disc that hides what is behind it (direction, angular
 * radius), atlas_moons.glsl's moonCover(dir, pix) is the same per direction in the shader. For gameplay and lore:
 * {@link #events} and {@link #next} (the coming eclipses, occultations, transits, conjunctions, double-full nights).</p>
 */
public final class MoonMath {
    private MoonMath() {}

    /** The sun's angular radius (atlas_sky.fsh's disc, high in the sky; from the moons the same). */
    public static final double SUN_RADIUS = 0.0089;
    /** The orbits' inclinations to the sun's path. */
    public static final double MASSER_INCLINATION = Math.toRadians(50.0), SECUNDA_INCLINATION = Math.toRadians(21.0);
    /** The ascending nodes' longitude from the sun's at day 0 (rad), and their regression periods (days). */
    private static final double MASSER_NODE0 = 0.55, SECUNDA_NODE0 = 0.62;
    private static final double MASSER_NODE_DAYS = 3310.0, SECUNDA_NODE_DAYS = 3310.0;
    /** Nirn's radius (km): the air's planet (atlas_air.glsl R_GROUND), so the air's paths and the moons' shadows are
     *  one geometry. */
    public static final double NIRN_RADIUS_KM = 6360.0;
    /** The sky's geography: km a world block (Tamriel at its lore scale, NightSkyMath.AURORA_KM_PER_BLOCK): where on
     *  Nirn a place is, for the moons' parallax and their shadows' footprints. */
    public static final double KM_PER_BLOCK = NightSkyMath.AURORA_KM_PER_BLOCK;
    /** Masser's distance (km, from Nirn's centre): ten of Nirn's radii. Its radius follows from its size on the sky
     *  (about 21 degrees across: ~11,800 km, nearly twice Nirn's; Nirn's umbra there, ~5,800 km, never covers it all:
     *  its lunar eclipses are a copper shadow crossing its face). */
    public static final double MASSER_DISTANCE_KM = 10.0 * NIRN_RADIUS_KM;
    /** Secunda's mean distance (km) and its eccentric orbit: eccentricity, anomalistic month (days), mean anomaly at
     *  day 0. Always nearer than Masser (in front of it when they meet); its size on the sky ~7 degrees at the mean,
     *  ~5.8 .. 9 round its orbit. */
    public static final double SECUNDA_DISTANCE_KM = 0.65 * MASSER_DISTANCE_KM;
    public static final double SECUNDA_ECCENTRICITY = 0.2, SECUNDA_ANOMALY_DAYS = 9.62, SECUNDA_ANOMALY0 = 1.1;
    /** Nirn's albedo (its Nirnshine on the moons). */
    public static final double NIRN_ALBEDO = 0.30;
    /** The sunlight bent through Nirn's air into its umbra (a share of full sunlight, copper-red). */
    public static final double UMBRA_GLOW = 0.006;
    /** The Dark Moon's angular radius. */
    public static final double DARK_MOON_RADIUS = Math.toRadians(6.5);
    /** The Necromancer's Moon: its angular radius, its period (days) and the first day it covers Arkay. */
    public static final double REVENANT_RADIUS = Math.toRadians(0.6), REVENANT_PERIOD = 8.0, REVENANT_EPOCH = 3.0;
    /** MoonInfo's size in floats (atlas_moons.glsl). */
    public static final int BLOCK_FLOATS = 32;

    // ------------------------------------------------------------------------------------------------ orbits

    /** A moon's latitude above the sun's path (rad) at its elongation from the sun along the path (rad). */
    public static double latitude(boolean secunda, double days, double elongation) {
        double i = secunda ? SECUNDA_INCLINATION : MASSER_INCLINATION;
        // the node's longitude from the sun: the node regresses, the sun goes round in a year
        double node = (secunda ? SECUNDA_NODE0 : MASSER_NODE0)
                - 2.0 * Math.PI * days / (secunda ? SECUNDA_NODE_DAYS : MASSER_NODE_DAYS)
                - 2.0 * Math.PI * days / NightSkyMath.YEAR_DAYS;
        return Math.asin(Math.sin(i) * Math.sin(elongation - node));
    }

    /** A moon's distance from Nirn's centre now (km). */
    public static double distanceKm(boolean secunda, double days) {
        if (!secunda) return MASSER_DISTANCE_KM;
        double m = 2.0 * Math.PI * days / SECUNDA_ANOMALY_DAYS + SECUNDA_ANOMALY0;
        return SECUNDA_DISTANCE_KM * (1.0 - SECUNDA_ECCENTRICITY * Math.cos(m));
    }

    /** Its mean distance (km): where its size on the sky is NightSkyMath's MASSER_RADIUS / SECUNDA_RADIUS. */
    public static double meanDistanceKm(boolean secunda) {
        return secunda ? SECUNDA_DISTANCE_KM : MASSER_DISTANCE_KM;
    }

    /** A moon's radius (km). */
    public static double bodyKm(boolean secunda) {
        return Math.tan(secunda ? NightSkyMath.SECUNDA_RADIUS : NightSkyMath.MASSER_RADIUS) * meanDistanceKm(secunda);
    }

    /** An observer at world (x, z) blocks, altKm above the ground: km from Nirn's centre in the sky's frame (y up at
     *  the world's origin; the world flat over Nirn's top, as the sun and the stars take it). */
    public static double[] observer(double x, double z, double altKm) {
        return new double[] {x * KM_PER_BLOCK, NIRN_RADIUS_KM + altKm, z * KM_PER_BLOCK};
    }

    // ------------------------------------------------------------------------------------------------ what is hidden

    /** The share of the sun's disc (0..1) a disc of angular radius r towards dir covers: the discs' overlap. */
    public static double covered(double[] sun, double[] dir, double r) {
        double d = Math.acos(Math.max(-1.0, Math.min(1.0, NightSkyMath.dot(sun, dir))));
        return coveredAt(d, r);
    }

    /** The share of the sun's disc covered by a disc of angular radius r whose centre is d (rad) from the sun's. */
    static double coveredAt(double d, double r) {
        double rs = SUN_RADIUS;
        if (d >= rs + r) return 0.0;
        if (d <= r - rs) return 1.0;
        if (d <= rs - r) return (r * r) / (rs * rs);
        double a = rs * rs * Math.acos((d * d + rs * rs - r * r) / (2 * d * rs));
        double b = r * r * Math.acos((d * d + r * r - rs * rs) / (2 * d * r));
        double c = 0.5 * Math.sqrt(Math.max(0.0, (-d + rs + r) * (d + rs - r) * (d - rs + r) * (d + rs + r)));
        return Math.min(1.0, (a + b - c) / (Math.PI * rs * rs));
    }

    /** The share of the sun seen from point p past a sphere (centre c, radius r), all in km. */
    static double sunPast(double[] p, double[] c, double r, double[] sun) {
        double[] v = NightSkyMath.sub(c, p);
        double l = Math.sqrt(NightSkyMath.dot(v, v));
        if (l <= r) return 0.0;
        if (NightSkyMath.dot(v, sun) <= 0) return 1.0;  // (behind the point: no shadow)
        double ang = Math.asin(r / l);
        double sep = Math.acos(Math.max(-1.0, Math.min(1.0, NightSkyMath.dot(v, sun) / l)));
        return 1.0 - coveredAt(sep, ang);
    }

    /**
     * The share of sunlight a moon's face towards the observer gets (0..1) past Nirn's shadow and the other moon's (a
     * lunar eclipse, a transit shadow): its disc sampled (13 points, area-weighted). c its centre (km from Nirn's),
     * r its radius (km), dir from the observer towards it; other: the other moon's centre (km) and radius, or null.
     */
    public static double lunarShare(double[] c, double r, double[] dir, double[] sun, double[] otherCentre,
                                    double otherRadius) {
        double[] ref = Math.abs(dir[1]) < 0.9 ? new double[] {0, 1, 0} : new double[] {1, 0, 0};
        double[] e1 = NightSkyMath.norm(NightSkyMath.cross(ref, dir)), e2 = NightSkyMath.cross(dir, e1);
        double sum = 0;
        int n = 0;
        for (int ring = 0; ring < 3; ring++) {
            int k = ring == 0 ? 1 : 6;
            double rho = ring == 0 ? 0 : ring == 1 ? 0.45 : 0.85;
            for (int j = 0; j < k; j++) {
                double a = 2 * Math.PI * (j + 0.5 * ring) / k;
                double x = rho * Math.cos(a), y = rho * Math.sin(a), z = Math.sqrt(Math.max(0, 1 - rho * rho));
                double[] nrm = {e1[0] * x + e2[0] * y - dir[0] * z, e1[1] * x + e2[1] * y - dir[1] * z,
                        e1[2] * x + e2[2] * y - dir[2] * z};
                double[] p = {c[0] + nrm[0] * r, c[1] + nrm[1] * r, c[2] + nrm[2] * r};
                double s = sunPast(p, new double[] {0, 0, 0}, NIRN_RADIUS_KM, sun);
                if (otherCentre != null) s *= sunPast(p, otherCentre, otherRadius, sun);
                sum += s;
                n++;
            }
        }
        return sum / n;
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
    public static double sunVisible(double days, double[] sun, double pathDeg, boolean secundaOn, double[] observer) {
        var m = NightSkyMath.moon(false, days, sun, pathDeg, observer);
        var s = NightSkyMath.moon(true, days, sun, pathDeg, observer);
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
        List<double[]> o = new ArrayList<>();
        o.add(new double[] {masser.dir()[0], masser.dir()[1], masser.dir()[2], masser.radius()});
        if (secundaOn) o.add(new double[] {secunda.dir()[0], secunda.dir()[1], secunda.dir()[2], secunda.radius()});
        if (e != null && e.dark() > 0) o.add(new double[] {e.darkDir()[0], e.darkDir()[1], e.darkDir()[2], DARK_MOON_RADIUS});
        if (revenant != null) o.add(new double[] {revenant[0], revenant[1], revenant[2], revenant[3]});
        return o.toArray(new double[0][]);
    }

    /** MoonInfo (atlas_moons.glsl): the eclipse, the Dark Moon, the Revenant, the bodies in space from the observer. */
    public static float[] block(Eclipse e, double[] revenant, NightSkyMath.Moon masser, NightSkyMath.Moon secunda,
                                double[] observer) {
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
        // MoonGeom: Secunda in front (the nearer), the shadows on, km a block
        o[16] = secunda.dist() < masser.dist() ? 1f : 0f;
        o[17] = 1f;
        o[18] = (float) KM_PER_BLOCK;
        // the bodies from the observer (km, world axes): Masser, Secunda (centre, radius), Nirn (centre, radius)
        putBody(o, 20, masser.centre(), masser.body(), observer);
        putBody(o, 24, secunda.centre(), secunda.body(), observer);
        putBody(o, 28, new double[] {0, 0, 0}, NIRN_RADIUS_KM, observer);
        return o;
    }

    private static void putBody(float[] o, int at, double[] centre, double r, double[] observer) {
        for (int i = 0; i < 3; i++) o[at + i] = (float) (centre[i] - observer[i]);
        o[at + 3] = (float) r;
    }

    /**
     * The occulters for the air's and the far field's eclipse (AirInfo.AirOcculter, atlas_set_eclipse): each moon near
     * the sun (within its radius and 4 degrees of it from here), {centre from the observer xyz (km), radius (km)},
     * radius 0 for one too far from the sun to shadow anything in sight.
     */
    public static float[] occulterBlock(NightSkyMath.Moon masser, NightSkyMath.Moon secunda, boolean secundaOn,
                                        double[] sun, double[] observer) {
        float[] o = new float[8];
        NightSkyMath.Moon[] ms = {masser, secunda};
        for (int k = 0; k < 2; k++) {
            NightSkyMath.Moon m = ms[k];
            if (k == 1 && !secundaOn) continue;
            double sep = Math.acos(Math.max(-1, Math.min(1, NightSkyMath.dot(m.dir(), sun))));
            if (sep > m.radius() + Math.toRadians(4)) continue;
            putBody(o, k * 4, m.centre(), m.body(), observer);
        }
        return o;
    }

    // ------------------------------------------------------------------------------------------------ events

    /** What can happen in the moons' sky. */
    public enum Kind {
        SOLAR_ECLIPSE_MASSER, SOLAR_ECLIPSE_SECUNDA, DARK_ECLIPSE, LUNAR_ECLIPSE_MASSER, LUNAR_ECLIPSE_SECUNDA,
        SECUNDA_OCCULTS_MASSER, MASSER_OCCULTS_SECUNDA, SECUNDA_SHADOW_ON_MASSER, MASSER_SHADOW_ON_SECUNDA, CONJUNCTION,
        DOUBLE_FULL
    }

    /** An event: its kind, when it starts, peaks and ends (days: day time / 24000), how deep at its peak (0..1: the
     *  share hidden or shadowed; a conjunction's closeness; a double-full night's fullness), the sun's height then. */
    public record Event(Kind kind, double start, double peak, double end, double depth, double sunY) {}

    /** The depth of each kind at one moment (0: not happening). */
    static double[] measure(double day, double pathDeg, double[] observer) {
        double[] sun = NightSkyMath.sunDirection(NightSkyMath.vanillaSunAngle(day * 24000.0), pathDeg);
        var m = NightSkyMath.moon(false, day, sun, pathDeg, observer);
        var s = NightSkyMath.moon(true, day, sun, pathDeg, observer);
        double[] o = new double[Kind.values().length + 1];
        double fm = covered(sun, m.dir(), m.radius()), fs = covered(sun, s.dir(), s.radius());
        o[Kind.SOLAR_ECLIPSE_MASSER.ordinal()] = fm;
        o[Kind.SOLAR_ECLIPSE_SECUNDA.ordinal()] = fs;
        o[Kind.DARK_ECLIPSE.ordinal()] = NightSkyMath.smooth(0.98, 1.0, fm) * NightSkyMath.smooth(0.98, 1.0, fs);
        double rm = m.body(), rs = s.body();
        double[] cm = m.centre(), cs = s.centre();
        o[Kind.LUNAR_ECLIPSE_MASSER.ordinal()] = 1.0 - m.sunlit();
        o[Kind.LUNAR_ECLIPSE_SECUNDA.ordinal()] = 1.0 - s.sunlit();
        double sep = Math.acos(Math.max(-1, Math.min(1, NightSkyMath.dot(m.dir(), s.dir()))));
        double touch = m.radius() + s.radius();
        double overlap = Math.max(0, Math.min(1, (touch - sep) / (2 * s.radius())));
        o[(s.dist() < m.dist() ? Kind.SECUNDA_OCCULTS_MASSER : Kind.MASSER_OCCULTS_SECUNDA).ordinal()] = overlap;
        // a transit shadow: the nearer-to-the-sun moon's shadow axis through the other's body
        o[Kind.SECUNDA_SHADOW_ON_MASSER.ordinal()] = shadowOn(cs, rs, cm, rm, sun);
        o[Kind.MASSER_SHADOW_ON_SECUNDA.ordinal()] = shadowOn(cm, rm, cs, rs, sun);
        o[Kind.CONJUNCTION.ordinal()] = sep < touch + Math.toRadians(8) && overlap <= 0
                ? 1.0 - (sep - touch) / Math.toRadians(8) : 0.0;
        boolean night = sun[1] < -0.1;
        o[Kind.DOUBLE_FULL.ordinal()] = night && m.lit() > 0.95 && s.lit() > 0.95 && m.dir()[1] > 0.05 && s.dir()[1] > 0.05
                ? Math.min(m.lit(), s.lit()) : 0.0;
        o[o.length - 1] = sun[1];
        return o;
    }

    /** How deep the shadow of a body (centre a, radius ra) falls on another (centre b, radius rb): 0 none .. 1 its
     *  axis through the middle of the other's face towards the sun. */
    static double shadowOn(double[] a, double ra, double[] b, double rb, double[] sun) {
        double[] v = NightSkyMath.sub(b, a);
        double t = -NightSkyMath.dot(v, sun);  // along the shadow (away from the sun)
        if (t <= 0) return 0.0;
        double[] perp = {v[0] + sun[0] * t, v[1] + sun[1] * t, v[2] + sun[2] * t};
        double d = Math.sqrt(NightSkyMath.dot(perp, perp));
        double umbra = Math.max(ra - t * SUN_RADIUS, 0.0), pen = ra + t * SUN_RADIUS;
        return Math.max(0.0, Math.min(1.0, (rb + pen - d) / (rb + pen))) * (umbra > 0 || d < rb ? 1.0 : 0.5);
    }

    /** The events over [from, to) days seen from the reference observer (the world's origin). */
    public static List<Event> events(double from, double to, double pathDeg) {
        return events(from, to, pathDeg, observer(0, 0, 0));
    }

    /** The events over [from, to) days (pathDeg the sun path's tilt, AtlasClient.sunPathRotationDeg) seen from an
     *  observer (MoonMath.observer: a solar eclipse is total only inside its umbra's footprint), sampled every three
     *  minutes of game time. */
    public static List<Event> events(double from, double to, double pathDeg, double[] observer) {
        List<Event> out = new ArrayList<>();
        int kinds = Kind.values().length;
        double step = 1.0 / 480.0;
        double[] start = new double[kinds], peak = new double[kinds], depth = new double[kinds], sunY = new double[kinds];
        boolean[] on = new boolean[kinds];
        for (double day = from; day < to + step; day += step) {
            double[] m = day < to ? measure(day, pathDeg, observer) : new double[kinds + 1];
            for (int k = 0; k < kinds; k++) {
                if (m[k] > 0) {
                    if (!on[k]) {
                        on[k] = true;
                        start[k] = day;
                        depth[k] = -1;
                    }
                    if (m[k] > depth[k]) {
                        depth[k] = m[k];
                        peak[k] = day;
                        sunY[k] = m[kinds];
                    }
                } else if (on[k]) {
                    on[k] = false;
                    out.add(new Event(Kind.values()[k], start[k], peak[k], day, depth[k], sunY[k]));
                }
            }
        }
        out.sort((a, b) -> Double.compare(a.start(), b.start()));
        return out;
    }

    /** The next event of a kind from a day (searching up to eight years ahead), or null. */
    public static Event next(Kind kind, double from, double pathDeg) {
        for (double a = from; a < from + 8 * NightSkyMath.YEAR_DAYS; a += 30.0) {
            for (Event e : events(a, a + 30.0, pathDeg)) if (e.kind() == kind && e.start() >= from) return e;
        }
        return null;
    }

    /**
     * The offline sky's scene finder (tools/dev/sky_offline.py). {@code events from to [path]}: one JSON object a line
     * per event over the span (days). {@code eclipses from to [path]}: the solar eclipses only (the older listing:
     * {day, sunAngle, hidden, dark, masser, secunda, sunY}). {@code moons from to step [path]}: the moons every step
     * (days): {day, sunY, masser [x, y, z, lit], secunda [x, y, z, lit, dist]}.
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
                        + "\"secunda\":[%.4f,%.4f,%.4f,%.4f,%.4f]}%n", day, sun[1], m.dir()[0], m.dir()[1], m.dir()[2],
                        m.lit(), s.dir()[0], s.dir()[1], s.dir()[2], s.lit(), s.dist()));
            }
            System.out.print(b);
            return;
        }
        if (args.length > 0 && args[0].equals("track")) {
            // track from to step [x z]: the sky every step (days) from a place (world blocks): the sun and its hidden
            // share, the dark eclipse, each moon {dir xyz, angular radius, lit, sunlit, centre xyz km, radius km}
            double from = Double.parseDouble(args[1]), to = Double.parseDouble(args[2]), step = Double.parseDouble(args[3]);
            double x = args.length > 5 ? Double.parseDouble(args[4]) : 0, z = args.length > 5 ? Double.parseDouble(args[5]) : 0;
            double[] obs = observer(x, z, 0.15);
            StringBuilder b = new StringBuilder();
            for (double day = from; day < to + step * 0.5; day += step) {
                double[] sun = NightSkyMath.sunDirection(NightSkyMath.vanillaSunAngle(day * 24000.0), -25.0);
                var m = NightSkyMath.moon(false, day, sun, -25.0, obs);
                var s = NightSkyMath.moon(true, day, sun, -25.0, obs);
                Eclipse e = eclipse(sun, m, s, true);
                b.append(String.format(Locale.ROOT, "{\"day\":%.6f,\"sun\":[%.5f,%.5f,%.5f],\"hidden\":%.5f,\"dark\":%.4f",
                        day, sun[0], sun[1], sun[2], e.hidden(), e.dark()));
                for (var mm : new NightSkyMath.Moon[] {m, s})
                    b.append(String.format(Locale.ROOT, ",\"%s\":[%.5f,%.5f,%.5f,%.6f,%.4f,%.4f,%.1f,%.1f,%.1f,%.1f]",
                            mm == m ? "masser" : "secunda", mm.dir()[0], mm.dir()[1], mm.dir()[2], mm.radius(), mm.lit(),
                            mm.sunlit(), mm.centre()[0], mm.centre()[1], mm.centre()[2], mm.body()));
                b.append("}\n");
            }
            System.out.print(b);
            return;
        }
        if (args.length > 0 && args[0].equals("events")) {
            double from = Double.parseDouble(args[1]), to = Double.parseDouble(args[2]);
            double path = args.length > 3 ? Double.parseDouble(args[3]) : -25.0;
            for (Event e : events(from, to, path))
                System.out.println(String.format(Locale.ROOT,
                        "{\"kind\":\"%s\",\"start\":%.5f,\"peak\":%.5f,\"end\":%.5f,\"depth\":%.4f,\"sunY\":%.3f}",
                        e.kind(), e.start(), e.peak(), e.end(), e.depth(), e.sunY()));
            return;
        }
        if (args.length > 0 && args[0].equals("eclipses")) args = java.util.Arrays.copyOfRange(args, 1, args.length);
        double from = args.length > 0 ? Double.parseDouble(args[0]) : 0.0;
        double to = args.length > 1 ? Double.parseDouble(args[1]) : NightSkyMath.YEAR_DAYS;
        double path = args.length > 2 ? Double.parseDouble(args[2]) : -25.0;
        for (Event e : events(from, to, path)) {
            if (e.kind() != Kind.SOLAR_ECLIPSE_MASSER && e.kind() != Kind.SOLAR_ECLIPSE_SECUNDA
                    && e.kind() != Kind.DARK_ECLIPSE) continue;
            double day = e.peak();
            double sa = NightSkyMath.vanillaSunAngle(day * 24000.0);
            double[] sn = NightSkyMath.sunDirection(sa, path);
            var mm = NightSkyMath.moon(false, day, sn, path);
            var ss = NightSkyMath.moon(true, day, sn, path);
            Eclipse ec = eclipse(sn, mm, ss, true);
            System.out.println(String.format(Locale.ROOT,
                    "{\"kind\":\"%s\",\"day\":%.4f,\"sunAngle\":%.5f,\"hidden\":%.4f,\"dark\":%.3f,\"masser\":%.4f,"
                            + "\"secunda\":%.4f,\"sunY\":%.3f}", e.kind(), day, sa, ec.hidden(), ec.dark(),
                    covered(sn, mm.dir(), mm.radius()), covered(sn, ss.dir(), ss.radius()), sn[1]));
        }
    }
}
