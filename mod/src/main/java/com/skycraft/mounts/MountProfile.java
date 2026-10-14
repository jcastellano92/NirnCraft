package com.skycraft.mounts;

/**
 * The stat profile of a mount KIND: what an average animal of the kind is (the base the individual's rolls and growth
 * work on), what the kind is good at (the training cap bonuses, so a guar gets tougher and a camel more enduring than a
 * pony ever can), how placid it is, and which names its stats go by on cards and sheets.
 *
 * <p>The six stats of every kind: Speed, Stamina, Health, Jump, Carry (the first five roll an individual variance and
 * grow with use, {@link MountStats}) and Trust (0..100, earned). The averages themselves live where they always did:
 * {@link MountKind} (stamina in gallop ticks, bag rows), {@link com.skycraft.fauna.HorseBreed} (health, speed, jump of
 * the horses), {@link MountGear#baseCarry} and the creature specs of {@link MountEntities}; this class adds the
 * flavour and exposes the averages in one place.</p>
 *
 * <p>Stamina is in gallop ticks: a gallop burns one point a tick, so 700 points is 35 seconds against a runner's 14
 * (7 a second off a base 100 bar).</p>
 */
public final class MountProfile {
    public static final int SPEED = 0, STAMINA = 1, HEALTH = 2, JUMP = 3, CARRY = 4, TEMPER = 5;
    /** Rolled and trained stats (Temperament is rolled but not trained, Trust is earned). */
    public static final int N = 5;
    public static final String[] STAT_IDS = {"speed", "stamina", "health", "jump", "carry", "temper"};

    /** Speed rolls +-6%, stamina, health and carry +-20%, jump +-10%: the variance at a roll of +-100. */
    static final float[] VARIANCE = {0.06f, 0.20f, 0.20f, 0.10f, 0.20f};
    /** What one growth point is worth (fraction of the stat). */
    static final float[] POINT = {0.004f, 0.015f, 0.015f, 0.010f, 0.015f};

    public final MountKind kind;
    /** What the kind trains well (added to a stat's training cap): speed, stamina, health, jump, carry. */
    public final int[] bonus;
    /** Trust of an average specimen out of the stable (a wild one starts lower, see {@link MountStats#roll}). */
    public final int trustBase;
    /** Which set of stat names the cards use ({@code mounts.skycraft.stat.<set>.<stat>}). */
    public final String labels;
    /** The kind in one phrase (lang key suffix under {@code mounts.skycraft.nature.}). */
    public final String nature;

    private MountProfile(MountKind kind, int trustBase, String labels, String nature, int... bonus) {
        this.kind = kind;
        this.trustBase = trustBase;
        this.labels = labels;
        this.nature = nature;
        this.bonus = bonus;
    }

    private static final MountProfile[] TABLE = new MountProfile[MountKind.VALUES.length];

    static {
        //  kind                        trust labels    nature      cap bonus: speed stamina health jump carry
        put(MountKind.WHITERUN_BAY, 45, "horse", "rider", 1, 0, 0, 1, 0);
        put(MountKind.SOLITUDE_GREY, 45, "horse", "court", 2, 0, 0, 1, 0);
        put(MountKind.WINDHELM_PINTO, 50, "horse", "hardy", 0, 1, 2, 0, 1);
        put(MountKind.RIFTEN_CHESTNUT, 45, "horse", "rider", 2, 0, 0, 1, 0);
        put(MountKind.MARKARTH_BLACK, 50, "horse", "hardy", 0, 1, 2, 1, 1);
        put(MountKind.SHADOWMERE, 80, "horse", "legend", 2, 2, 2, 2, 2);
        put(MountKind.CYRODILIC_BAY, 45, "horse", "allround", 1, 1, 1, 1, 1);
        put(MountKind.COLOVIAN_PAINT, 45, "horse", "draught", 0, 1, 1, 0, 2);
        put(MountKind.NIBENESE_GREY, 40, "horse", "swift", 2, 0, 0, 2, 0);
        put(MountKind.BRETON_DESTRIER, 40, "horse", "war", 0, 0, 2, 0, 1);
        put(MountKind.ALIKR_STEED, 45, "horse", "desert", 1, 2, 0, 1, 0);
        put(MountKind.AURIDON_WHITE, 50, "horse", "swift", 2, 0, 0, 2, 0);
        put(MountKind.ARVAK, 70, "horse", "legend", 2, 2, 2, 2, 2);
        put(MountKind.CAMEL, 40, "camel", "camel", 0, 2, 1, 0, 2);
        put(MountKind.GUAR, 55, "guar", "guar", 0, 1, 2, 0, 2);
        put(MountKind.SENCHE, 25, "cat", "cat", 2, 0, 1, 1, -1);
        put(MountKind.GIANT_WOLF, 30, "wolf", "wolf", 1, 2, 0, 1, 0);
        put(MountKind.INDRIK, 50, "indrik", "indrik", 2, 0, 0, 2, 0);
        put(MountKind.MAMMOTH, 45, "mammoth", "mammoth", -1, 1, 3, 0, 3);
        put(MountKind.AKAVIRI_TIGER, 20, "cat", "cat", 2, 0, 1, 1, -1);
        put(MountKind.REALM_GRAZER, 50, "grazer", "grazer", 0, 1, 1, 0, 1);
    }

    private static void put(MountKind kind, int trust, String labels, String nature, int... bonus) {
        TABLE[kind.ordinal()] = new MountProfile(kind, trust, labels, nature, bonus);
    }

    public static MountProfile of(MountKind kind) {
        return TABLE[kind.ordinal()];
    }

    // ------------------------------------------------------------------ base numbers of an average animal

    /** Movement speed attribute of the average animal (the number the stable's price list has always used). */
    public static double speedAttr(MountKind kind) {
        return switch (kind.backing) {
            case HORSE, SKELETON -> kind.breed.movementSpeed;
            case CAMEL -> 0.26;
            case GUAR -> 0.23;
            case BEAST -> {
                var t = MountEntities.type(kind);
                yield t == null ? 0.3 : MountEntities.spec(t).speed;
            }
        };
    }

    /** Blocks a second (roughly) at a trot of the given speed multiplier, for display. */
    public static double speedBps(MountKind kind, double mult) {
        return speedAttr(kind) * mult * 43.0;
    }

    public static double baseHealth(MountKind kind) {
        return switch (kind.backing) {
            case HORSE, SKELETON -> kind.breed.maxHealth;
            case CAMEL -> 40;
            case GUAR -> 45;
            case BEAST -> {
                var t = MountEntities.type(kind);
                yield t == null ? 40 : MountEntities.spec(t).health;
            }
        };
    }

    /** Initial upward speed of a full jump (the unit of the horse's jump strength attribute). */
    public static double baseJump(MountKind kind) {
        return switch (kind.backing) {
            case HORSE, SKELETON -> kind.breed.jumpStrength;
            case CAMEL -> 0.5;
            case GUAR -> 0.45;
            case BEAST -> beastJump(kind);
        };
    }

    /** Upward speed of a full jump of a ridden beast (horse-like for the light beasts, a hop for the mammoth). */
    static double beastJump(MountKind kind) {
        return switch (kind) {
            case INDRIK -> 1.05;
            case SENCHE, AKAVIRI_TIGER -> 0.95;
            case GIANT_WOLF -> 0.9;
            case MAMMOTH -> 0.5;
            default -> 0.75;
        };
    }

    /** Blocks cleared by a jump of the given launch speed (vanilla gravity and drag). */
    public static double jumpHeight(double v) {
        double y = 0;
        for (int i = 0; i < 80 && v > 0; i++) {
            y += v;
            v = (v - 0.08) * 0.98;
        }
        return y;
    }

    /** Lang key of this kind's name for a stat. */
    public String labelKey(int stat) {
        return "mounts.skycraft.stat." + labels + "." + STAT_IDS[stat];
    }
}
