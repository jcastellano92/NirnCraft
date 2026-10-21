package com.nirncraft.atmosphere;

import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Nirn's sky beyond the air and the moons, as one deterministic model: the celestial sphere seen from a place in
 * Tamriel at a moment, its fixed stars (the thirteen birthsigns as star figures, the named stars), the planets on
 * their orbits about the sun, and the rare comet. shaders/include/atlas_celestial.glsl draws it; this class computes
 * what it is given (CelestialInfo each frame, NightSigns once). Pure: no Minecraft classes, any thread; {@code main}
 * prints a frame for the offline sky (tools/dev/sky_offline.py).
 *
 * <p><b>The frame.</b> Equatorial coordinates: x the sun's place on the first day of the year (Morning Star 1), z the
 * north celestial pole. Nirn's equator lies in its orbit's plane (the sky shows no seasons in the sun's height: the
 * game's sun keeps one path all year), so the ecliptic is the celestial equator and the planets, the sun and the
 * birthsigns keep to a band along it. The observer's latitude puts the pole at that height over the northern horizon
 * ({@link #latitudeDeg}: high in Skyrim, low in the far south, Atmora near the pole). The hour angle is the game's sun's
 * (sunAngle), so the stars rise and set with the sun's clock, and they gain a turn a year on it (sidereal time): the
 * year is the calendar's, 360 days (calendar.FestivalCalendar), so each birthsign sits where the sun stands in its own
 * month and culminates at midnight half a year later. Longitude: the game's clock is everyone's local solar time, so
 * moving east or west changes nothing above (the sky is the sun's, and the sun is the same everywhere); it is carried
 * in the observer for what may depend on it later.</p>
 *
 * <p><b>Units.</b> The air's (atlas_air.glsl: the sun's irradiance above the air ~ 11.8, 1 unit ~ 10.8 klx): a star's
 * light is its irradiance above the air, {@link NightSkyMath#STAR_E0} at magnitude 0.</p>
 */
public final class NirnCelestial {
    private NirnCelestial() {}

    /** The calendar's year (calendar.FestivalCalendar.DAYS_PER_YEAR: 12 months of 30 days). */
    public static final double YEAR = 360.0;
    /** Tamriel's latitude: Whiterun (z 0) at 50 degrees north, this many degrees a block southward (the far south
     *  coast near 8 degrees, the Imperial City near 28, Winterhold 59, Atmora's coast towards 80). */
    public static final double LAT_WHITERUN = 50.0, DEG_PER_BLOCK = 0.0038;

    /** The observer's latitude (degrees north) at map z (blocks; north is -z). */
    public static double latitudeDeg(double z) {
        return Math.max(-60.0, Math.min(89.0, LAT_WHITERUN - z * DEG_PER_BLOCK));
    }

    /** The observer's longitude (degrees east of Whiterun) at map x (see the class: it moves nothing in the sky). */
    public static double longitudeDeg(double x) {
        return x * DEG_PER_BLOCK;
    }

    /** The sun's longitude along the equator (radians) on day {@code days} (0: Morning Star 1). */
    public static double sunLongitude(double days) {
        return 2.0 * Math.PI * days / YEAR;
    }

    /**
     * Equatorial -> world (row-major 3 x 3; world x east, y up, z south) for the game's sun at hour angle sunAngle
     * (radians: 0 at noon, pi at midnight, the sun westward as it grows: AtlasClient.sunDirection's) on day days, seen
     * from latitude lat (radians). Local sidereal time is the sun's hour angle plus its longitude.
     */
    public static double[] equatorToWorld(double sunAngle, double days, double lat) {
        double th = sunAngle + sunLongitude(days);
        double ct = Math.cos(th), st = Math.sin(th), cl = Math.cos(lat), sl = Math.sin(lat);
        double[] m = {0, cl, sl};       // the equator on the meridian (south, 90 - lat high)
        double[] w = {-1, 0, 0};        // west
        double[] p = {0, sl, -cl};      // the north celestial pole
        double[] c0 = new double[3], c1 = new double[3];
        for (int i = 0; i < 3; i++) {
            c0[i] = ct * m[i] + st * w[i];
            c1[i] = st * m[i] - ct * w[i];
        }
        return new double[] {c0[0], c1[0], p[0], c0[1], c1[1], p[1], c0[2], c1[2], p[2]};
    }

    // ============================================================================================ the fixed stars

    /** A star of a figure: x right (west, as the figure culminates in the south), y up (north), degrees; its
     *  magnitude; its temperature (K, 0: from the figure's seed). */
    record Pt(double x, double y, double mag, double kelvin) {}

    private static Pt p(double x, double y, double mag) { return new Pt(x, y, mag, 0); }
    private static Pt p(double x, double y, double mag, double kelvin) { return new Pt(x, y, mag, kelvin); }

    /** A birthsign: its month (1..12, the sun stands in it then; 0 the Serpent), its latitude off the equator and
     *  tilt (degrees), its stars. The Mage's and the Thief's are placed by their eyes (Julianos's and Arkay's paths). */
    record Sign(String name, int month, double beta, double tilt, Pt[] stars) {}

    /** Where the Mage's and the Thief's eyes are in their figures (no star: the planet's place at its season). */
    static final double[] MAGE_EYE = {0.0, 6.6}, THIEF_EYE = {0.2, 6.2};

    /**
     * The thirteen birthsigns as original star figures, designed here from who each sign is in the lore (no star map
     * copied): a few bright anchors make each one's shape (the Warrior's belt and raised blade, the Tower's two walls
     * and beacon, the Atronach's ring round its core, the Shadow's pair of red eyes), fainter stars complete it.
     */
    static final Sign[] SIGNS = {
            new Sign("ritual", 1, 18, -6, new Pt[] {  // arms raised under a crescent
                    p(-4.4, 8.0, 2.7), p(-2.6, 9.6, 2.2), p(0, 10.2, 1.9), p(2.6, 9.6, 2.3), p(4.4, 8.0, 2.8),
                    p(-3.4, 5.4, 2.4), p(3.4, 5.4, 2.5), p(0, 4.2, 1.3, 9800), p(-1.4, 2.8, 2.9), p(1.4, 2.8, 3.0),
                    p(0, -1.0, 2.8), p(-1.4, -5.0, 2.3), p(1.4, -5.0, 2.6)}),
            new Sign("lover", 2, -12, 4, new Pt[] {  // two figures face to face, hands joined
                    p(-1.4, 6.2, 1.4, 4300), p(-2.4, 3.8, 2.8), p(-3.4, 0.4, 2.6), p(-2.8, -2.8, 2.9), p(-3.4, -6.4, 2.4),
                    p(1.4, 6.6, 1.6, 5600), p(2.4, 4.0, 2.9), p(3.4, 0.6, 2.7), p(2.8, -2.6, 3.0), p(3.2, -6.2, 2.5),
                    p(0, 1.8, 2.2)}),
            new Sign("lord", 3, 6, 8, new Pt[] {  // an armoured lord: a broad square of a body, arms out, helm
                    p(0, 9.4, 2.0), p(-3.6, 6.0, 1.2), p(3.6, 6.0, 1.6, 4100), p(-3.2, 0.0, 1.8), p(3.2, 0.0, 1.5),
                    p(0, 3.2, 3.0), p(-5.8, 3.0, 2.8), p(5.8, 3.2, 2.9), p(-2.0, -4.2, 2.7), p(-2.4, -7.6, 2.3),
                    p(2.0, -4.2, 2.8), p(2.4, -7.6, 2.2)}),
            new Sign("mage", 4, 0, -4, new Pt[] {  // hooded, robed, a tall staff; Julianos her eye
                    p(0, 9.2, 2.2), p(-1.6, 7.4, 2.5), p(1.6, 7.2, 2.6), p(-2.6, 3.8, 2.0), p(2.6, 3.6, 2.3),
                    p(-3.2, -1.0, 2.9), p(3.2, -1.2, 2.8), p(-4.4, -6.0, 1.9), p(-1.4, -6.6, 2.7), p(1.6, -6.6, 2.8),
                    p(4.4, -6.0, 1.6), p(-6.0, 10.0, 1.0, 14000), p(-6.0, 4.0, 3.0), p(-6.0, -6.8, 2.9)}),
            new Sign("shadow", 5, -20, 10, new Pt[] {  // a tall shrouded figure, faint, a pair of red eyes in the hood
                    p(0, 9.0, 2.9), p(-1.8, 6.4, 3.2), p(1.8, 6.2, 3.3), p(-0.5, 5.2, 1.9, 3500), p(0.5, 5.2, 2.0, 3500),
                    p(-2.6, 1.0, 3.3), p(2.6, 0.8, 3.4), p(-3.6, -4.4, 3.0), p(3.6, -4.6, 3.1), p(-1.6, -7.2, 3.2),
                    p(1.6, -7.2, 3.3)}),
            new Sign("steed", 6, 16, 6, new Pt[] {  // a horse running west, its forelegs reaching
                    p(8.0, 4.0, 2.4), p(6.6, 7.0, 2.7), p(6.2, 4.6, 2.9), p(3.6, 3.4, 1.7), p(0, 3.0, 2.6),
                    p(-4.0, 3.0, 1.5, 11000), p(-6.2, 4.6, 2.9), p(-7.6, 6.4, 3.0), p(0, 0.0, 3.1), p(5.2, -2.6, 2.4),
                    p(6.6, -4.8, 2.8), p(-4.4, -1.8, 2.3), p(-6.2, -4.2, 2.7)}),
            new Sign("apprentice", 7, -8, -10, new Pt[] {  // a small kneeling figure holding out a lamp
                    p(0, 5.0, 2.6), p(-1.2, 3.4, 2.9), p(1.2, 3.4, 3.0), p(-1.8, 0.6, 3.1), p(1.4, -1.6, 2.8),
                    p(-1.6, -2.6, 3.0), p(2.8, -2.4, 3.2), p(2.6, 2.8, 3.1), p(4.0, 3.4, 1.2, 5200), p(4.4, 2.6, 2.8)}),
            new Sign("warrior", 8, 12, 4, new Pt[] {  // a swordsman: three stars of his belt, the blade raised west, a shield
                    p(0, 8.0, 2.4), p(-2.4, 5.6, 1.4), p(2.4, 5.4, 1.8), p(-1.0, 1.0, 2.2), p(0, 0.9, 2.0),
                    p(1.0, 0.8, 2.3), p(-2.8, -7.5, 1.7), p(3.0, -7.4, 2.2), p(3.6, 6.6, 2.9), p(4.8, 8.6, 2.6),
                    p(6.0, 10.6, 2.4), p(7.2, 12.6, 1.1, 3500), p(-4.6, 4.0, 2.8), p(-5.2, 1.6, 3.0)}),
            new Sign("lady", 9, -16, -6, new Pt[] {  // a lady in a bell of a gown, hands at her waist
                    p(0, 9.0, 1.8), p(-1.8, 6.6, 2.6), p(1.8, 6.6, 2.5), p(0, 3.6, 1.4, 7600), p(-2.4, 0.0, 2.9),
                    p(2.4, 0.0, 2.8), p(-5.0, -5.6, 1.9), p(-2.6, -6.6, 2.7), p(0, -7.0, 2.5), p(2.6, -6.6, 2.8),
                    p(5.0, -5.6, 2.0)}),
            new Sign("tower", 10, 20, 2, new Pt[] {  // a tower: two walls, battlements, its beacon at the top
                    p(-1.8, -8.0, 2.2), p(-1.8, -3.4, 2.8), p(-1.8, 1.2, 2.9), p(-1.8, 5.6, 2.5), p(1.8, -8.0, 2.4),
                    p(1.8, -3.6, 2.9), p(1.8, 1.0, 3.0), p(1.8, 5.4, 2.6), p(-2.8, 7.4, 3.0), p(2.8, 7.4, 3.1),
                    p(0, 9.6, 0.8, 6800), p(0, -6.8, 3.2)}),
            new Sign("atronach", 11, -14, 12, new Pt[] {  // the golem: a heavy ring round a burning core
                    p(0, 5.6, 2.4), p(3.4, 4.4, 2.0), p(5.2, 1.2, 2.7), p(4.6, -2.6, 2.3), p(2.0, -5.0, 2.9),
                    p(-1.6, -5.4, 2.6), p(-4.6, -3.0, 2.2), p(-5.4, 0.6, 2.8), p(-3.6, 4.0, 2.5), p(0.2, 0.2, 0.9, 3300)}),
            new Sign("thief", 12, 0, -8, new Pt[] {  // hooded and crouched, a dagger out west; Arkay his eye
                    p(-1.8, 6.6, 2.4), p(-0.6, 8.0, 1.9), p(1.0, 8.2, 2.6), p(2.2, 7.0, 2.9), p(-3.4, 4.2, 1.6),
                    p(-4.4, 1.0, 2.5), p(-3.8, -2.4, 2.2), p(-1.0, -4.2, 2.8), p(1.6, -5.4, 1.9), p(2.6, 3.4, 2.7),
                    p(4.2, 2.4, 3.0), p(5.6, 3.4, 2.6), p(7.0, 4.6, 1.4, 16000)}),
            new Sign("serpent", 0, 0, 0, new Pt[] {  // it wanders: a long winding body, the head west, a red eye
                    p(8.2, 2.0, 1.5, 3400), p(9.4, 1.0, 2.8), p(8.0, 0.4, 2.9), p(6.2, 2.4, 2.5), p(4.4, 1.6, 2.3),
                    p(2.8, 0.0, 2.7), p(1.0, -1.4, 2.4), p(-1.0, -1.8, 2.6), p(-3.0, -1.0, 2.8), p(-4.8, 0.6, 2.5),
                    p(-6.6, 1.6, 2.9), p(-8.4, 1.4, 3.1), p(-10.0, 0.4, 3.3)}),
    };
    public static final int SIGN_STARS = 14;
    /** The figures' scale (their coordinates in these units of a degree: the larger ones ~ 22 degrees tall) and their
     *  stars' brightening over the design's magnitudes (they stand out of the field, still a real sky's bright stars). */
    public static final double SIGN_SCALE = 1.15, SIGN_LIFT = 0.5;

    /** The Serpent's wander: once round the sky westward among the stars in this many days, its latitude swinging
     *  over the birthsigns' band. */
    public static final double SERPENT_PERIOD = 4.5 * YEAR, SERPENT_SWING = 2.2 * YEAR, SERPENT_BETA = 16.0;

    /** A named star: equatorial place (degrees), magnitude, colour (linear rgb, or null: by its temperature), kelvin;
     *  dark: Xero-Lyg, a black point of this angular radius (degrees) hiding what lies behind it. */
    record Named(String name, double ra, double dec, double mag, float[] rgb, double kelvin, double darkRadius) {}

    private static Named n(String name, double ra, double dec, double mag, float r, float g, float b) {
        return new Named(name, ra, dec, mag, new float[] {r, g, b}, 0, 0);
    }

    private static Named nk(String name, double ra, double dec, double mag, double kelvin) {
        return new Named(name, ra, dec, mag, null, kelvin, 0);
    }

    static final Named[] NAMED = {
            // the Nine Coruscations, each its own colour (Xero-Lyg black: placed on the galaxy band, where it shows)
            n("iana-lor", 22, 38, 0.9, 0.62f, 0.38f, 1.0f),
            n("londa-vera", 62, -24, 1.1, 0.35f, 1.0f, 0.45f),
            n("merid-nunda", 98, 52, 0.6, 1.0f, 0.26f, 0.18f),
            n("mnemo-li", 141, -8, 1.0, 0.32f, 0.52f, 1.0f),
            n("prime-archon", 183, 28, 0.3, 1.0f, 1.0f, 1.0f),
            n("sheza-rana", 222, -34, 1.2, 0.28f, 0.95f, 1.0f),
            n("unala-se", 262, 44, 0.8, 1.0f, 0.88f, 0.32f),
            n("valia-sha", 301, -12, 0.9, 1.0f, 0.52f, 0.18f),
            new Named("xero-lyg", Double.NaN, Double.NaN, 0, null, 0, 0.22),
            // Azura's Star, the brightest of the fixed stars, rose-violet
            n("azura", 128, 36, -0.7, 0.92f, 0.76f, 1.0f),
            // the Atmorans' favourites, high in the north
            nk("darumzu", 40, 68, 1.3, 11000),
            nk("harakk", 250, 63, 1.5, 3900),
            // the Northern Sisters, a close pair near the pole
            nk("sister-elder", 160, 81, 2.0, 9500),
            nk("sister-younger", 171, 82.2, 2.2, 9000),
            // the Southron Pole Star: low over the southern horizon from the far south only
            nk("southron-pole", 200, -72, 0.2, 8200),
            // the Star of the East: on the equator, it rises due east
            nk("star-of-the-east", 75, 2, 0.2, 5900),
            // the Three Stars of Locanda, a short curved line
            nk("locanda-1", 330.0, 24.0, 2.6, 12000),
            nk("locanda-2", 331.3, 24.6, 2.4, 13000),
            nk("locanda-3", 332.6, 24.4, 2.7, 11500),
    };
    public static final int NAMED_SLOTS = 20;

    /** The galaxy's pole (equatorial): the band crosses the equator steeply, rising into the north. */
    public static final double[] GALAXY_N = norm(new double[] {0.4193, 0.8635, 0.2795});

    /** NightSigns' size in floats (atlas_celestial.glsl): 13 centres, 13 x 14 stars + tints, 20 named + tints. */
    public static final int CATALOGUE_FLOATS = (13 + 13 * SIGN_STARS * 2 + NAMED_SLOTS * 2) * 4;

    /** A figure's star -> equatorial: the figure at (lambda, beta) degrees, tilted, x west, y north. */
    static double[] place(double lambdaDeg, double betaDeg, double tiltDeg, double x, double y) {
        double[] d = norm(new double[] {1.0, -Math.toRadians(x), Math.toRadians(y)});  // gnomonic about (1, 0, 0)
        d = rotX(d, Math.toRadians(tiltDeg));
        d = rotY(d, -Math.toRadians(betaDeg));
        return rotZ(d, Math.toRadians(lambdaDeg));
    }

    /** Equatorial unit vector -> (ra, dec) degrees. */
    static double[] raDec(double[] v) {
        double ra = Math.toDegrees(Math.atan2(v[1], v[0]));
        return new double[] {ra < 0 ? ra + 360 : ra, Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, v[2]))))};
    }

    static double[] fromRaDec(double ra, double dec) {
        double a = Math.toRadians(ra), d = Math.toRadians(dec);
        return new double[] {Math.cos(d) * Math.cos(a), Math.cos(d) * Math.sin(a), Math.sin(d)};
    }

    /** A sign's centre (lambda, beta degrees): its month's sun; the Mage and the Thief round their eyes. */
    static double[] signCentre(Sign s) {
        if (s.name.equals("mage") || s.name.equals("thief")) {
            double[] eye = s.name.equals("mage") ? MAGE_EYE : THIEF_EYE;
            double[] want = raDec(s.name.equals("mage") ? mageEye() : thiefEye());
            // the centre that puts the figure's eye on the planet's place (a few corrections of the small-angle guess)
            double[] c = {want[0], want[1] - eye[1] * SIGN_SCALE};
            for (int i = 0; i < 6; i++) {
                double[] got = raDec(place(c[0], c[1], s.tilt, eye[0] * SIGN_SCALE, eye[1] * SIGN_SCALE));
                double dl = want[0] - got[0];
                dl -= 360.0 * Math.round(dl / 360.0);
                c[0] += dl;
                c[1] += want[1] - got[1];
            }
            return c;
        }
        return new double[] {(s.month - 1) * 30.0 + 15.0, s.beta};
    }

    /** NightSigns (std140): SignCentre[13], SignStar[182], SignTint[182], NamedStar[20], NamedTint[20]. */
    public static float[] catalogue() {
        float[] o = new float[CATALOGUE_FLOATS];
        int n = 13 * SIGN_STARS;
        int centres = 0, stars = 13 * 4, tints = (13 + n) * 4, named = (13 + 2 * n) * 4, ntints = (13 + 2 * n + NAMED_SLOTS) * 4;
        for (int f = 0; f < SIGNS.length; f++) {
            Sign s = SIGNS[f];
            boolean serpent = s.month == 0;
            double[] c = serpent ? new double[] {0, 0} : signCentre(s);
            double[] centre = place(c[0], c[1], s.tilt, 0, 0);
            double maxAng = 0;
            SplittableRandom r = new SplittableRandom(0x5349474EL + f * 7919L);
            for (int i = 0; i < SIGN_STARS; i++) {
                int q = stars + (f * SIGN_STARS + i) * 4, t = tints + (f * SIGN_STARS + i) * 4;
                if (i >= s.stars.length) {
                    o[q + 3] = 99f;
                    continue;
                }
                Pt pt = s.stars[i];
                double[] d = place(c[0], c[1], s.tilt, pt.x * SIGN_SCALE, pt.y * SIGN_SCALE);
                maxAng = Math.max(maxAng, Math.acos(Math.min(1.0, dot(centre, d))));
                put(o, q, d[0], d[1], d[2], pt.mag - SIGN_LIFT);
                double k = pt.kelvin > 0 ? pt.kelvin : temperature(r.nextDouble());
                float[] rgb = blackbody(k);
                put(o, t, rgb[0], rgb[1], rgb[2], r.nextDouble());
            }
            put(o, centres + f * 4, centre[0], centre[1], centre[2], Math.cos(maxAng + Math.toRadians(1.0)));
        }
        for (int i = 0; i < NAMED_SLOTS; i++) {
            int q = named + i * 4, t = ntints + i * 4;
            if (i >= NAMED.length) {
                o[t + 3] = -1f;
                continue;
            }
            Named s = NAMED[i];
            if (s.darkRadius > 0) {
                double[] d = xeroLyg();
                put(o, q, d[0], d[1], d[2], Math.toRadians(s.darkRadius));
                put(o, t, 0, 0, 0, 1);
                continue;
            }
            double[] d = fromRaDec(s.ra, s.dec);
            put(o, q, d[0], d[1], d[2], s.mag);
            float[] rgb = s.rgb != null ? lumNorm(s.rgb) : blackbody(s.kelvin);
            put(o, t, rgb[0], rgb[1], rgb[2], 0);
        }
        return o;
    }

    /** Xero-Lyg: on the galaxy's band (where the dark point shows against its light: beside the central rift), north of
     *  the equator. */
    static double[] xeroLyg() {
        double[] a = norm(cross(GALAXY_N, new double[] {0, 0, 1}));
        double[] b = norm(cross(GALAXY_N, a));
        double t = 0.45;
        double[] v = norm(new double[] {a[0] * Math.cos(t) + b[0] * Math.sin(t), a[1] * Math.cos(t) + b[1] * Math.sin(t),
                a[2] * Math.cos(t) + b[2] * Math.sin(t)});
        if (v[2] < 0) v = scale(v, -1);
        double o = Math.toRadians(5.0);
        return norm(new double[] {v[0] * Math.cos(o) + GALAXY_N[0] * Math.sin(o), v[1] * Math.cos(o) + GALAXY_N[1] * Math.sin(o),
                v[2] * Math.cos(o) + GALAXY_N[2] * Math.sin(o)});
    }

    /** The Serpent's figure frame -> equatorial now (row-major): its centre wandering, its body along its path. */
    public static double[] serpentFrame(double days) {
        double lam = 200.0 - 360.0 * days / SERPENT_PERIOD;
        double ph = 2.0 * Math.PI * days / SERPENT_SWING;
        double beta = SERPENT_BETA * Math.sin(ph);
        // its path's slope: d beta / d lambda (both in degrees a day)
        double dl = -360.0 / SERPENT_PERIOD, db = SERPENT_BETA * Math.cos(ph) * 2.0 * Math.PI / SERPENT_SWING;
        // (heading west along its path: the head first; the figure's +x is west)
        double tilt = Math.toDegrees(Math.atan2(-db, -dl * Math.cos(Math.toRadians(beta))));
        // columns: where the reference frame's axes go (x the figure's centre), place()'s rotation
        double[][] e = new double[3][];
        for (int i = 0; i < 3; i++) {
            double[] v = new double[3];
            v[i] = 1;
            e[i] = rotZ(rotY(rotX(v, Math.toRadians(tilt)), -Math.toRadians(beta)), Math.toRadians(lam));
        }
        return new double[] {e[0][0], e[1][0], e[2][0], e[0][1], e[1][1], e[2][1], e[0][2], e[1][2], e[2][2]};
    }

    // ============================================================================================ the planets

    /** A body on a circular orbit about the sun (or about its parent): radius (AU), inclination, node, longitude at
     *  day 0 (radians), period (days), absolute magnitude, the disc's radius (AU: a little large, to show), tint. */
    record Orbit(String name, int parent, double a, double incl, double node, double l0, double period, double absMag,
                 double discAu, float[] tint) {}

    public static final int AKATOSH = 0, ARKAY = 1, JULIANOS = 2, STENDARR = 3, KYNARETH = 4, ZENITHAR = 5, MARA = 6,
            DIBELLA = 7, PLANETS = 8;
    /** The day Arkay stands in the Thief's eye (Mid Year 16th of year 0, midnight: at opposition; again every second
     *  Mid Year, its period two years), and Julianos in the Mage's (Frostfall 16th of year 1; every third year). */
    public static final double ARKAY_EYE_DAY = 165.75, JULIANOS_EYE_DAY = YEAR + 285.75;
    static final double ARKAY_PERIOD = 2.0 * YEAR, JULIANOS_PERIOD = 3.0 * YEAR;

    static final Orbit[] ORBITS = orbits();

    private static Orbit[] orbits() {
        // the phases and nodes seeded once (the same sky for every world); Arkay's and Julianos's set so that they
        // pass through the Thief's and the Mage's eyes, at opposition (the planet's brightest), on their days
        SplittableRandom r = new SplittableRandom(0x4E49524E_43454CL);
        Orbit[] o = new Orbit[PLANETS];
        double aA = Math.pow(ARKAY_PERIOD / YEAR, 2.0 / 3.0), aJ = Math.pow(JULIANOS_PERIOD / YEAR, 2.0 / 3.0);
        double lA = sunLongitude(ARKAY_EYE_DAY) + Math.PI, lJ = sunLongitude(JULIANOS_EYE_DAY) + Math.PI;
        o[AKATOSH] = new Orbit("akatosh", -1, 0.72, Math.toRadians(3.1), r.nextDouble() * 2 * Math.PI,
                r.nextDouble() * 2 * Math.PI, Math.pow(0.72, 1.5) * YEAR, -4.7, 1.7e-4, new float[] {1.0f, 0.90f, 0.68f});
        o[ARKAY] = new Orbit("arkay", -1, aA, Math.toRadians(1.9), lA - Math.PI / 2,
                lA - 2 * Math.PI * ARKAY_EYE_DAY / ARKAY_PERIOD, ARKAY_PERIOD, -1.8, 1.1e-4, new float[] {0.86f, 0.84f, 1.0f});
        o[JULIANOS] = new Orbit("julianos", -1, aJ, Math.toRadians(2.4), lJ - Math.PI / 2,
                lJ - 2 * Math.PI * JULIANOS_EYE_DAY / JULIANOS_PERIOD, JULIANOS_PERIOD, -2.6, 2.6e-4, new float[] {0.74f, 0.86f, 1.0f});
        o[STENDARR] = new Orbit("stendarr", JULIANOS, 0.0028, Math.toRadians(24), r.nextDouble() * 2 * Math.PI,
                r.nextDouble() * 2 * Math.PI, 13.0, 1.9, 0, new float[] {1.0f, 0.95f, 0.84f});
        o[KYNARETH] = new Orbit("kynareth", -1, 0.39, Math.toRadians(5.0), r.nextDouble() * 2 * Math.PI,
                r.nextDouble() * 2 * Math.PI, Math.pow(0.39, 1.5) * YEAR, -0.6, 0.8e-4, new float[] {0.70f, 1.0f, 0.88f});
        o[ZENITHAR] = new Orbit("zenithar", -1, 5.1, Math.toRadians(1.3), r.nextDouble() * 2 * Math.PI,
                r.nextDouble() * 2 * Math.PI, Math.pow(5.1, 1.5) * YEAR, -9.4, 1.3e-3, new float[] {1.0f, 0.80f, 0.56f});
        o[MARA] = new Orbit("mara", ZENITHAR, 0.014, Math.toRadians(8), r.nextDouble() * 2 * Math.PI,
                r.nextDouble() * 2 * Math.PI, 29.0, -5.6, 0, new float[] {1.0f, 0.74f, 0.80f});
        o[DIBELLA] = new Orbit("dibella", MARA, 0.0035, Math.toRadians(38), r.nextDouble() * 2 * Math.PI,
                r.nextDouble() * 2 * Math.PI, 5.5, -3.6, 0, new float[] {1.0f, 0.70f, 0.95f});
        return o;
    }

    /** Nirn's own place about the sun (AU, equatorial axes): opposite the sun's longitude. */
    public static double[] nirn(double days) {
        double l = sunLongitude(days) + Math.PI;
        return new double[] {Math.cos(l), Math.sin(l), 0};
    }

    /** A body's place about the sun (AU, equatorial axes) on a day (satellites: their parents' plus their own). */
    public static double[] helio(int i, double days) {
        Orbit o = ORBITS[i];
        double u = o.l0 - o.node + 2.0 * Math.PI * days / o.period;
        double[] v = {o.a * Math.cos(u), o.a * Math.sin(u) * Math.cos(o.incl), o.a * Math.sin(u) * Math.sin(o.incl)};
        v = rotZ(v, o.node);
        if (o.parent >= 0) {
            double[] p = helio(o.parent, days);
            v = new double[] {v[0] + p[0], v[1] + p[1], v[2] + p[2]};
        }
        return v;
    }

    /** A planet as seen from Nirn: equatorial direction, distance (AU), magnitude, angular radius, the direction from
     *  it towards the sun (equatorial), the lit share of its disc's light (2/3 x Lambert's phase law: the disc's mean). */
    public record Planet(double[] dir, double distance, double mag, double radius, double[] toSun, double litShare) {}

    public static Planet planet(int i, double days) {
        double[] h = helio(i, days), e = nirn(days);
        double[] g = sub(h, e);
        double delta = Math.sqrt(dot(g, g)), r = Math.sqrt(dot(h, h));
        double cosA = Math.max(-1, Math.min(1, dot(h, g) / (r * delta)));
        double alpha = Math.acos(cosA);
        double phase = (Math.sin(alpha) + (Math.PI - alpha) * Math.cos(alpha)) / Math.PI;  // Lambert's
        double mag = ORBITS[i].absMag + 5 * Math.log10(r * delta) - 2.5 * Math.log10(Math.max(phase, 1e-4));
        double rad = ORBITS[i].discAu / delta;
        return new Planet(scale(g, 1 / delta), delta, mag, rad, scale(h, -1 / r), Math.max(phase * 2.0 / 3.0, 1e-4));
    }

    /** Arkay's direction (equatorial) on a day: the Necromancer's Moon's target (moons: their eclipse of it). */
    public static double[] arkay(double days) {
        return planet(ARKAY, days).dir;
    }

    static double[] mageEye() {
        return planet(JULIANOS, JULIANOS_EYE_DAY).dir;
    }

    static double[] thiefEye() {
        return planet(ARKAY, ARKAY_EYE_DAY).dir;
    }

    /** How much of each planet's light shows (the moons' agent: the Necromancer's Moon over Arkay); null: all. */
    public interface PlanetShade {
        double factor(int planet, double days);
    }

    public static volatile PlanetShade shade;

    // ============================================================================================ the comet

    /** A comet on a parabolic orbit: perihelion day, its distance (AU), inclination, node, argument of perihelion
     *  (radians), absolute magnitude (at 1 AU from both; brightening as r^-4). */
    public record Comet(double perihelion, double q, double incl, double node, double peri, double absMag) {}

    /** One year in this many brings a comet (bright enough to see, weeks long). */
    public static final double COMET_CHANCE = 0.16;

    /** The comet of a world's year, or null (most years). */
    public static Comet yearComet(long world, long year) {
        SplittableRandom r = new SplittableRandom(mix(world * 0x9E3779B97F4A7C15L + year * 0xC2B2AE3D27D4EB4FL + 0x434F4DL));
        if (r.nextDouble() >= COMET_CHANCE) return null;
        double t = year * YEAR + 20.0 + r.nextDouble() * (YEAR - 40.0);
        return new Comet(t, 0.30 + 0.65 * r.nextDouble(), Math.acos(1 - 2 * r.nextDouble()), r.nextDouble() * 2 * Math.PI,
                r.nextDouble() * 2 * Math.PI, 1.5 + 4.0 * r.nextDouble());
    }

    /** The comet in the sky on a day for a world (its orbit's years round the day), or null. */
    public static Comet cometFor(long world, double days) {
        long y = (long) Math.floor(days / YEAR);
        Comet best = null;
        for (long k = y - 1; k <= y + 1; k++) {
            Comet c = yearComet(world, k);
            if (c != null && Math.abs(days - c.perihelion) < 120.0
                    && (best == null || Math.abs(days - c.perihelion) < Math.abs(days - best.perihelion))) best = c;
        }
        return best;
    }

    /**
     * A great comet for the dev hook, at its brightest on day peak (its nearest pass), placed to be the nights' showpiece
     * from Tamriel: well clear of the sun and far north (up all night) for the weeks round it, its tails tens of
     * degrees long. The orbit is searched with its perihelion on that day, then turned with Nirn's year (about the
     * pole: the same view of it from Nirn, its declination kept) and shifted in time so its brightest night falls there.
     */
    public static Comet forcedComet(double peak) {
        Comet best = null;
        double bestScore = -1, bestShift = 0;
        for (int k = 0; k < 4096; k++) {
            SplittableRandom r = new SplittableRandom(0x464F524345L + k);
            Comet c = new Comet(peak, 0.9, Math.acos(1 - 2 * r.nextDouble()), r.nextDouble() * 2 * Math.PI,
                    r.nextDouble() * 2 * Math.PI, GREAT_COMET);
            double brightest = 0, m = 99;
            for (int dt = -30; dt <= 30; dt++) {
                double mag = comet(c, peak + dt).mag;
                if (mag < m) {
                    m = mag;
                    brightest = dt;
                }
            }
            double score = 1e9;
            for (double dt : new double[] {-7, 0, 7}) {
                double t = peak + brightest + dt;
                CometState st = comet(c, t);
                double[] sun = scale(nirn(t), -1);
                double elong = Math.toDegrees(Math.acos(dot(st.dir, sun)));
                double tail = Math.toDegrees(Math.acos(Math.min(1, dot(st.dust[TAIL_POINTS - 1], st.dir))));
                double dec = Math.toDegrees(Math.asin(st.dir[2]));
                if (elong < 60 || dec < 30 || st.delta < 0.3 || tail > 65) score = -1;
                else score = Math.min(score, Math.min(tail, 40.0));
            }
            if (score > bestScore) {
                bestScore = score;
                best = c;
                bestShift = brightest;
            }
            if (bestScore >= 30) break;
        }
        if (best == null) return new Comet(peak, 0.9, 1.2, 0.5, 0.3, GREAT_COMET);
        return new Comet(peak - bestShift, best.q, best.incl, best.node - 2.0 * Math.PI * bestShift / YEAR, best.peri,
                best.absMag);
    }

    /** The dev hook's great comet's absolute magnitude (a once-in-a-century comet's: the brightest stars' match). */
    public static final double GREAT_COMET = -2.0;

    /**
     * A comet now: its direction (equatorial), distances from the sun and Nirn (AU), magnitude, the coma's angular
     * radius, its tails' axes (points: equatorial directions, with their half-widths, radians), the anti-tail's (the
     * large dust left along the orbit, seen sunward when Nirn crosses the orbit's plane) and its strength (0..1), the
     * days from perihelion (the structure's evolution).
     */
    public record CometState(double[] dir, double r, double delta, double mag, double comaRadius, double[][] dust,
                             double[] dustWidth, double[][] ion, double[] ionWidth, double[][] anti, double antiStrength,
                             double age) {}

    public static final int TAIL_POINTS = 8, ANTI_POINTS = 4;
    /** The tails' light against the coma's (its magnitude's): the dust's scattered sunlight (a great comet's dust tail
     *  outshines its head several times over), the ions' blue glow. */
    public static final double DUST_SHARE = 4.0, ION_SHARE = 1.0, ANTI_SHARE = 0.15;

    public static CometState comet(Comet c, double days) {
        // Barker's equation: a parabola about the sun (GM = 4 pi^2 AU^3 a year^2, the calendar's year)
        double k = 2.0 * Math.PI / YEAR;
        double w = 3.0 * k * Math.sqrt(1.0 / (2.0 * c.q * c.q * c.q)) * (days - c.perihelion);
        double y = Math.cbrt(w / 2.0 + Math.sqrt(w * w / 4.0 + 1.0));
        double s = y - 1.0 / y;
        double nu = 2.0 * Math.atan(s);
        double r = c.q * (1.0 + s * s);
        double[] pos = {r * Math.cos(nu), r * Math.sin(nu), 0};
        double[] vel = {-Math.sin(nu), 1.0 + Math.cos(nu), 0};  // (its direction only)
        double[] normal = orient(new double[] {0, 0, 1}, c);
        pos = orient(pos, c);
        vel = norm(orient(vel, c));
        double[] e = nirn(days);
        double[] g = sub(pos, e);
        double delta = Math.sqrt(dot(g, g));
        double mag = c.absMag + 5 * Math.log10(delta) + 10 * Math.log10(r);
        // activity: brighter comets, nearer the sun: longer tails, larger comae
        double act = Math.pow(10, -0.2 * (c.absMag - 4.0)) * Math.pow(r, -1.5);
        double coma = 0.0006 * Math.sqrt(act) / delta;
        double[] out = norm(pos);
        // a great comet's tails reach a large part of an AU (tens of degrees on the sky)
        double lDust = Math.min(0.04 * act, 0.5), lIon = Math.min(0.07 * act, 0.8);
        double[][] dust = new double[TAIL_POINTS][], ion = new double[TAIL_POINTS][];
        double[] dw = new double[TAIL_POINTS], iw = new double[TAIL_POINTS];
        for (int i = 0; i < TAIL_POINTS; i++) {
            double f = (double) i / (TAIL_POINTS - 1);
            // the dust: pushed out by the sunlight, lagging behind the comet's motion as it goes (a curve), fanning
            double sd = lDust * f;
            double[] xd = {pos[0] + out[0] * sd - vel[0] * 0.9 * sd * f, pos[1] + out[1] * sd - vel[1] * 0.9 * sd * f,
                    pos[2] + out[2] * sd - vel[2] * 0.9 * sd * f};
            double[] gd = sub(xd, e);
            double dd = Math.sqrt(dot(gd, gd));
            dust[i] = scale(gd, 1 / dd);
            dw[i] = (0.0008 * Math.sqrt(act) + 0.22 * sd) / dd;
            // the ions: straight out from the sun on the solar wind
            double si = lIon * f;
            double[] gi = sub(new double[] {pos[0] + out[0] * si, pos[1] + out[1] * si, pos[2] + out[2] * si}, e);
            double di = Math.sqrt(dot(gi, gi));
            ion[i] = scale(gi, 1 / di);
            iw[i] = (0.0004 * Math.sqrt(act) + 0.025 * si) / di;
        }
        // the anti-tail: the large grains strewn behind along the orbit, a thin sunward spike while Nirn is near the
        // orbit's plane (seen edge-on)
        double[][] anti = new double[ANTI_POINTS][];
        double lAnti = Math.min(0.02 * act, 0.15);
        for (int i = 0; i < ANTI_POINTS; i++) {
            double sa = lAnti * i / (ANTI_POINTS - 1);
            double[] ga = sub(new double[] {pos[0] - vel[0] * sa, pos[1] - vel[1] * sa, pos[2] - vel[2] * sa}, e);
            anti[i] = norm(ga);
        }
        double edge = Math.abs(dot(norm(g), normal));
        double antiStrength = Math.max(0.0, 1.0 - edge / Math.sin(Math.toRadians(6.0)));
        return new CometState(scale(g, 1 / delta), r, delta, mag, coma, dust, dw, ion, iw, anti,
                antiStrength * antiStrength, days - c.perihelion);
    }

    private static double[] orient(double[] v, Comet c) {
        return rotZ(rotX(rotZ(v, c.peri), c.incl), c.node);
    }

    // ============================================================================================ the frame

    /** CelestialInfo's size in floats (atlas_celestial.glsl): 2 mat4, observer, 3 x 8 planets, 3 comet, 2 x 8 tail,
     *  the comet's time, 4 anti-tail. */
    public static final int BLOCK_FLOATS = 32 + 4 + 3 * PLANETS * 4 + 3 * 4 + 2 * TAIL_POINTS * 4 + 4 + ANTI_POINTS * 4;

    /** A frame: the block, the planets' and the comet's light for the land (SH L1 as NightSky.meteorLight: rgb + pad x
     *  4, irradiance above the air, before the night's lift), Arkay's world direction, the comet's magnitude (NaN: none). */
    public record Frame(float[] block, float[] light, double[] arkayWorld, double cometMag, double[] eqToWorld,
                        Planet[] planets, CometState comet) {}

    /**
     * The sky's bodies for a frame: sunAngle (the game's, radians), days (the clock: day time / 24000), the camera's map
     * x, z (blocks), the comet (or null), the scintillation's strength (1 typical).
     */
    public static Frame frame(double sunAngle, double days, double x, double z, Comet comet, double seeing) {
        double lat = Math.toRadians(latitudeDeg(z)), lon = Math.toRadians(longitudeDeg(x));
        double[] m = equatorToWorld(sunAngle, days, lat);
        float[] o = new float[BLOCK_FLOATS];
        float[] light = new float[16];
        putWorldTo(o, 0, m);
        putWorldTo(o, 16, mul(m, serpentFrame(days)));
        put(o, 32, lat, lon, seeing, 0);
        Planet[] ps = new Planet[PLANETS];
        PlanetShade sh = shade;
        for (int i = 0; i < PLANETS; i++) {
            Planet p = planet(i, days);
            ps[i] = p;
            double[] d = apply(m, p.dir), s = apply(m, p.toSun);
            double e = NightSkyMath.STAR_E0 * Math.pow(10, -0.4 * p.mag);
            if (sh != null) e *= Math.max(0.0, sh.factor(i, days));
            float[] t = lumNorm(ORBITS[i].tint);
            put(o, 36 + i * 4, d[0], d[1], d[2], e);
            put(o, 68 + i * 4, t[0], t[1], t[2], p.radius);
            put(o, 100 + i * 4, s[0], s[1], s[2], p.litShare);
            addLight(light, d, e, t);
        }
        CometState cs = null;
        double cometMag = Double.NaN;
        if (comet != null) {
            cs = comet(comet, days);
            cometMag = cs.mag;
            double e = NightSkyMath.STAR_E0 * Math.pow(10, -0.4 * cs.mag);
            double[] h = apply(m, cs.dir);
            put(o, 132, h[0], h[1], h[2], e);
            // the tails' light against the coma's (the dust's sunlight, the ions' faint glow)
            put(o, 136, cs.comaRadius, e * DUST_SHARE, e * ION_SHARE, e * ANTI_SHARE * cs.antiStrength);
            // its time (days from perihelion: the tails' structure evolves night to night), a seed of its own
            put(o, 208, cs.age, (comet.node * 7.31 + comet.peri * 3.17) % 1.0, cs.r, 0);
            for (int i = 0; i < ANTI_POINTS; i++) {
                double[] a = apply(m, cs.anti[i]);
                put(o, 212 + i * 4, a[0], a[1], a[2], cs.comaRadius * (0.25 + 0.15 * i));
            }
            double[] centre = {0, 0, 0};
            double[][] dw = new double[TAIL_POINTS][], iw = new double[TAIL_POINTS][];
            for (int i = 0; i < TAIL_POINTS; i++) {
                dw[i] = apply(m, cs.dust[i]);
                iw[i] = apply(m, cs.ion[i]);
                put(o, 144 + i * 4, dw[i][0], dw[i][1], dw[i][2], cs.dustWidth[i]);
                put(o, 176 + i * 4, iw[i][0], iw[i][1], iw[i][2], cs.ionWidth[i]);
                for (int c = 0; c < 3; c++) centre[c] += dw[i][c] + iw[i][c];
            }
            centre = norm(centre);
            double reach = Math.max(cs.comaRadius * 10.0, 0.002);
            double cosR = Math.cos(Math.acos(Math.min(1, dot(centre, h))) + reach);
            for (int i = 0; i < TAIL_POINTS; i++) {
                cosR = Math.min(cosR, Math.cos(Math.acos(Math.min(1, dot(centre, dw[i]))) + cs.dustWidth[i] * 3.5));
                cosR = Math.min(cosR, Math.cos(Math.acos(Math.min(1, dot(centre, iw[i]))) + cs.ionWidth[i] * 3.5));
            }
            put(o, 140, centre[0], centre[1], centre[2], cosR);
            addLight(light, h, e * (1.0 + DUST_SHARE + ION_SHARE), new float[] {0.97f, 0.99f, 1.0f});
        }
        return new Frame(o, light, apply(m, ps[ARKAY].dir), cometMag, m, ps, cs);
    }

    /** A point of light above the horizon into the land's SH (faded through the air near the horizon). */
    private static void addLight(float[] sh, double[] d, double e, float[] tint) {
        if (d[1] <= 0) return;
        double w = e * Math.min(1.0, d[1] * 8.0);
        for (int c = 0; c < 3; c++) {
            double wc = w * tint[c];
            sh[c] += (float) (wc * 0.282095);
            sh[4 + c] += (float) (wc * 0.488603 * d[0]);
            sh[8 + c] += (float) (wc * 0.488603 * d[1]);
            sh[12 + c] += (float) (wc * 0.488603 * d[2]);
        }
    }

    // ============================================================================================ colour

    /** A star's temperature from a uniform number (the naked eye's mix: many orange giants, some blue-white). */
    static double temperature(double u) {
        if (u < 0.12) return 2900 + (3700 - 2900) * u / 0.12;
        if (u < 0.45) return 3700 + (5200 - 3700) * (u - 0.12) / 0.33;
        if (u < 0.60) return 5200 + (6000 - 5200) * (u - 0.45) / 0.15;
        if (u < 0.75) return 6000 + (7500 - 6000) * (u - 0.60) / 0.15;
        if (u < 0.90) return 7500 + (10000 - 7500) * (u - 0.75) / 0.15;
        return 10000 + (25000 - 10000) * (u - 0.90) / 0.10;
    }

    /** A blackbody's colour (linear sRGB, luminance 1; atlas_celestial.glsl celBlackbody's: Planck at three
     *  wavelengths against 6500 K), a little richer than the eye sees it. */
    public static float[] blackbody(double kelvin) {
        double[] lam = {0.610, 0.550, 0.465};
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double b = 1.0 / (Math.pow(lam[i], 5) * (Math.exp(14388.0 / (lam[i] * kelvin)) - 1.0));
            double w = 1.0 / (Math.pow(lam[i], 5) * (Math.exp(14388.0 / (lam[i] * 6500.0)) - 1.0));
            c[i] = b / w;
        }
        double l = NightSkyMath.lum(c[0], c[1], c[2]);
        float[] o = new float[3];
        for (int i = 0; i < 3; i++) o[i] = (float) Math.max(0.0, 1.0 + (c[i] / l - 1.0) * SATURATION);
        return lumNorm(o);
    }

    /** The stars' colours: a little more saturated than the eye's (Aetherius's light, the brief's richer sparkle). */
    public static final double SATURATION = 1.35;

    static float[] lumNorm(float[] c) {
        double l = NightSkyMath.lum(c[0], c[1], c[2]);
        return new float[] {(float) (c[0] / l), (float) (c[1] / l), (float) (c[2] / l)};
    }

    // ============================================================================================ the CLI

    /**
     * The offline sky's inputs: one line of JSON with CelestialInfo ("celestial"), NightSigns ("catalogue") and a
     * summary (latitude, the planets' places and magnitudes, the comet). key=value: dayTime (ticks), sunAngle (radians,
     * else vanilla's from dayTime), x, z (blocks), comet (a perihelion day for the dev hook's comet; or world=N for a
     * world's own), seeing.
     */
    public static void main(String[] args) {
        java.util.Map<String, String> a = new java.util.HashMap<>();
        for (String s : args) {
            int i = s.indexOf('=');
            if (i > 0) a.put(s.substring(0, i), s.substring(i + 1));
        }
        double dayTime = Double.parseDouble(a.getOrDefault("dayTime", "18000"));
        double days = dayTime / 24000.0;
        double sunAngle = a.containsKey("sunAngle") ? Double.parseDouble(a.get("sunAngle")) : NightSkyMath.vanillaSunAngle(dayTime);
        double x = Double.parseDouble(a.getOrDefault("x", "0")), z = Double.parseDouble(a.getOrDefault("z", "0"));
        Comet c = a.containsKey("comet") ? forcedComet(Double.parseDouble(a.get("comet")))
                : a.containsKey("world") ? cometFor(Long.parseLong(a.get("world")), days) : null;
        Frame f = frame(sunAngle, days, x, z, c, Double.parseDouble(a.getOrDefault("seeing", "1")));
        StringBuilder b = new StringBuilder("{");
        b.append("\"celestial\":").append(Arrays.toString(f.block));
        b.append(",\"catalogue\":").append(Arrays.toString(catalogue()));
        b.append(",\"light\":").append(Arrays.toString(f.light));
        b.append(String.format(Locale.ROOT, ",\"latitude\":%f", latitudeDeg(z)));
        b.append(",\"planets\":{");
        for (int i = 0; i < PLANETS; i++) {
            double[] w = apply(f.eqToWorld, f.planets[i].dir);
            b.append(String.format(Locale.ROOT, "%s\"%s\":{\"mag\":%.2f,\"alt\":%.2f,\"az\":%.2f,\"dist\":%.3f,\"radius\":%.3e}",
                    i == 0 ? "" : ",", ORBITS[i].name, f.planets[i].mag, Math.toDegrees(Math.asin(w[1])),
                    azimuth(w), f.planets[i].distance, f.planets[i].radius));
        }
        b.append("}");
        // labels for a chart (world directions): the poles, the named stars, the figures' centres, the planets
        b.append(",\"labels\":[");
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.function.BiConsumer<String, double[]> label = (name, w) -> labels.add(String.format(Locale.ROOT,
                "{\"name\":\"%s\",\"dir\":[%.5f,%.5f,%.5f]}", name, w[0], w[1], w[2]));
        label.accept("north pole", apply(f.eqToWorld, new double[] {0, 0, 1}));
        label.accept("south pole", apply(f.eqToWorld, new double[] {0, 0, -1}));
        for (Named s : NAMED)
            label.accept(s.name, apply(f.eqToWorld, s.darkRadius > 0 ? xeroLyg() : fromRaDec(s.ra, s.dec)));
        for (Sign s : SIGNS) {
            double[] at = s.month == 0 ? new double[] {0, 0} : signCentre(s);
            double[] eq = place(at[0], at[1], s.tilt, 0, 0);
            if (s.month == 0) eq = apply(serpentFrame(days), eq);
            label.accept(s.name.toUpperCase(Locale.ROOT), apply(f.eqToWorld, eq));
        }
        for (int i = 0; i < PLANETS; i++) label.accept(ORBITS[i].name, apply(f.eqToWorld, f.planets[i].dir));
        b.append(String.join(",", labels)).append("]");
        if (f.comet != null) {
            double[] w = apply(f.eqToWorld, f.comet.dir);
            b.append(String.format(Locale.ROOT, ",\"comet\":{\"mag\":%.2f,\"r\":%.3f,\"delta\":%.3f,\"alt\":%.2f,\"az\":%.2f}",
                    f.comet.mag, f.comet.r, f.comet.delta, Math.toDegrees(Math.asin(w[1])), azimuth(w)));
        }
        double[] eye = apply(f.eqToWorld, thiefEye()), mage = apply(f.eqToWorld, mageEye());
        b.append(String.format(Locale.ROOT, ",\"thiefEye\":{\"alt\":%.2f,\"az\":%.2f},\"mageEye\":{\"alt\":%.2f,\"az\":%.2f}",
                Math.toDegrees(Math.asin(eye[1])), azimuth(eye), Math.toDegrees(Math.asin(mage[1])), azimuth(mage)));
        b.append("}");
        System.out.println(b);
    }

    /** Degrees from north through east. */
    static double azimuth(double[] w) {
        double az = Math.toDegrees(Math.atan2(w[0], -w[2]));
        return az < 0 ? az + 360 : az;
    }

    // ============================================================================================ helpers

    private static void put(float[] o, int p, double a, double b, double c, double d) {
        o[p] = (float) a;
        o[p + 1] = (float) b;
        o[p + 2] = (float) c;
        o[p + 3] = (float) d;
    }

    /** std140 mat4 (column-major) of world -> frame for a row-major frame -> world rotation: GLSL mat3(m) * v. */
    private static void putWorldTo(float[] o, int at, double[] frameToWorld) {
        // world -> frame is the transpose: element (r, c) = frameToWorld (c, r); column c holds rows r
        for (int c = 0; c < 3; c++)
            for (int r = 0; r < 3; r++) o[at + c * 4 + r] = (float) frameToWorld[c * 3 + r];
        o[at + 15] = 1f;
    }

    static double[] apply(double[] m, double[] v) {
        return new double[] {m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    static double[] mul(double[] a, double[] b) {
        double[] o = new double[9];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
        return o;
    }

    static double[] rotX(double[] v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[] {v[0], c * v[1] - s * v[2], s * v[1] + c * v[2]};
    }

    static double[] rotY(double[] v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[] {c * v[0] + s * v[2], v[1], -s * v[0] + c * v[2]};
    }

    static double[] rotZ(double[] v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[] {c * v[0] - s * v[1], s * v[0] + c * v[1], v[2]};
    }

    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] sub(double[] a, double[] b) { return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }

    static double[] scale(double[] a, double s) { return new double[] {a[0] * s, a[1] * s, a[2] * s}; }

    static double[] norm(double[] a) {
        double l = Math.sqrt(dot(a, a));
        return l < 1e-12 ? new double[] {0, 0, 1} : scale(a, 1.0 / l);
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
