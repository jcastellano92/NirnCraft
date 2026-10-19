package com.nirncraft.mounts;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.SplittableRandom;
import java.util.UUID;

/**
 * The individual: what makes one mount better than another of its kind. Pure data and arithmetic (no world access), so
 * it is deterministic and selftested.
 *
 * <ul>
 *     <li><b>Rolls</b> ({@link #q}, -100..100 per stat): the variance the animal was born with (Speed, Stamina, Health,
 *     Jump, Carry, Temperament). Drawn once, triangular (average animals are common, a Superb one is about one in
 *     twenty-two per stat), from a seed: a wild animal's seed is its UUID, so it is the same animal on every look, a
 *     stable's stock comes from the stable's key and week. The legends are fixed high.</li>
 *     <li><b>Grades</b> ({@link #grade}): Poor, Fair, Good, Fine, Superb, by roll.</li>
 *     <li><b>Growth</b> ({@link #xp}, {@link #level}, {@link #grow}): mount experience from riding, jumping, hauling
 *     and fighting; every level (cap {@value #MAX_LEVEL}) is one growth point in the stat that use trained most, up to
 *     the soft cap of that stat ({@link #cap}: the kind's strengths plus its grade).</li>
 *     <li><b>Trust</b> ({@link #trust}, 0..100): earned by care, lost by neglect and abuse; a trusting mount is a
 *     little faster and recovers its wind sooner, and is easier to break in.</li>
 * </ul>
 */
public final class MountStats {
    /** The entity's persistent-data compound. */
    public static final String TAG = "nirncraft_mstats";
    public static final int MAX_LEVEL = 20;
    /** Poor, Fair, Good, Fine, Superb. */
    public static final int GRADES = 5;

    public final MountKind kind;
    /** Rolls of Speed, Stamina, Health, Jump, Carry and Temperament, -100..100. */
    public final byte[] q = new byte[MountProfile.N + 1];
    /** Growth points per trained stat. */
    public final byte[] grow = new byte[MountProfile.N];
    /** What this level's use has trained so far, per stat (reset at each level). */
    public final float[] train = new float[MountProfile.N];
    public float xp;
    public float trust;
    /** Game time of the last feeding that counted (a stomach is only so big). */
    public long fedAt = Long.MIN_VALUE / 2;
    /** Game time the animal was last left at a stable (it is rested a day later). */
    public long stabledAt = -1;
    /** The in-game week of the last training session and how many were paid that week. */
    public int trainWeek = -1;
    public int trainCount;
    /** Bumped when the stats are changed away from the animal (trained while it was not loaded): the newer copy wins. */
    public int rev;

    public MountStats(MountKind kind) {
        this.kind = kind;
    }

    // ------------------------------------------------------------------ rolling

    /**
     * Rolls an individual. {@code shift} (-0.3..0.3) moves the whole bell: a good stable's stock is better, a poor
     * one's worse. {@code wild} animals start warier.
     */
    public static MountStats roll(MountKind kind, long seed, float shift, boolean wild) {
        MountStats s = new MountStats(kind);
        MountProfile p = MountProfile.of(kind);
        if (kind.unique()) {
            int v = kind == MountKind.ARVAK ? 90 : 85;
            for (int i = 0; i < s.q.length; i++) s.q[i] = (byte) v;
            s.q[MountProfile.TEMPER] = (byte) (kind == MountKind.ARVAK ? 60 : 70);
            s.trust = p.trustBase;
            s.xp = xpFor(10);
            return s;
        }
        SplittableRandom r = new SplittableRandom(seed ^ 0x9E3779B97F4A7C15L);
        for (int i = 0; i < s.q.length; i++) {
            double tri = r.nextDouble() + r.nextDouble() - 1.0;
            s.q[i] = (byte) Math.max(-100, Math.min(100, Math.round((tri + shift) * 100.0)));
        }
        s.trust = clampTrust(p.trustBase + (wild ? -15 : 0) + s.q[MountProfile.TEMPER] * 0.2f);
        return s;
    }

    /** The seed of a creature in the world: its UUID, so a wild animal is the same animal on every look. */
    public static long seedOf(UUID id, MountKind kind) {
        return id.getMostSignificantBits() * 31L + id.getLeastSignificantBits() + kind.id.hashCode() * 0x5DEECE66DL;
    }

    /** A stable's stock animal: rolled, then lived in for a few levels (a good stable keeps better trained stock). */
    public static MountStats rollStock(MountKind kind, long seed, float shift, float quality) {
        MountStats s = roll(kind, seed, shift, false);
        if (kind.unique()) return s;
        SplittableRandom r = new SplittableRandom(seed * 31 + 7);
        int levels = (int) Math.floor(r.nextDouble() * (1 + quality * 8));
        s.xp = xpFor(1 + levels);
        MountProfile p = MountProfile.of(kind);
        for (int l = 0; l < levels; l++) {
            // stock was ridden the way its kind is: its strengths trained most
            for (int i = 0; i < MountProfile.N; i++) s.train[i] = (float) (r.nextDouble() * (1 + Math.max(0, p.bonus[i])));
            s.awardPoint(l);
        }
        s.trust = clampTrust(s.trust + r.nextInt(25));
        return s;
    }

    // ------------------------------------------------------------------ grades and numbers

    /** Poor 0, Fair 1, Good 2, Fine 3, Superb 4 of a roll of -100..100. */
    public static int gradeOf(int roll) {
        if (roll < -55) return 0;
        if (roll < -20) return 1;
        if (roll <= 30) return 2;
        if (roll <= 70) return 3;
        return 4;
    }

    /** Grade of a stat 0..5 (5 is Temperament). */
    public int grade(int stat) {
        return gradeOf(q[stat]);
    }

    /** Multiplier of the kind's average for a stat: the roll, then the growth points. */
    public float mult(int stat) {
        float roll = 1f + q[stat] / 100f * MountProfile.VARIANCE[stat];
        return roll * (1f + grow[stat] * MountProfile.POINT[stat]);
    }

    /** Multiplier of the roll alone (what the animal was born with). */
    public float rollMult(int stat) {
        return 1f + q[stat] / 100f * MountProfile.VARIANCE[stat];
    }

    /** Speed also depends on how willing the animal is: 0.97 at no trust, 1.03 at full trust. */
    public float speedMult() {
        return mult(MountProfile.SPEED) * trustSpeed(trust);
    }

    public static float trustSpeed(float trust) {
        return 0.97f + 0.06f * Math.max(0f, Math.min(100f, trust)) / 100f;
    }

    /** Stamina recovery multiplier from trust: a calm, bonded mount gets its wind back sooner (0.85..1.15). */
    public static float restMult(float trust) {
        return 0.85f + 0.3f * Math.max(0f, Math.min(100f, trust)) / 100f;
    }

    /** The soft cap of growth points in a stat: 3 + grade + what its kind trains well. */
    public int cap(int stat) {
        return Math.max(1, 3 + grade(stat) + MountProfile.of(kind).bonus[stat]);
    }

    /** A rough measure of how good the animal is, -1..1 (the mean of its five rolls, growth counted). */
    public float quality() {
        float sum = 0;
        for (int i = 0; i < MountProfile.N; i++) sum += q[i] / 100f + grow[i] * 0.04f;
        return Math.max(-1f, Math.min(1.5f, sum / MountProfile.N));
    }

    public int growthPoints() {
        int n = 0;
        for (byte b : grow) n += b;
        return n;
    }

    // ------------------------------------------------------------------ growth

    /** Experience a level takes, from level {@code level} to the next. */
    public static float xpToNext(int level) {
        return 40f + 30f * (level - 1);
    }

    /** Total experience at which a level is reached (level 1 is 0). */
    public static float xpFor(int level) {
        int n = Math.max(0, Math.min(MAX_LEVEL, level) - 1);
        return 40f * n + 15f * n * (n - 1);
    }

    public static int levelFor(float xp) {
        int l = 1;
        while (l < MAX_LEVEL && xp >= xpFor(l + 1)) l++;
        return l;
    }

    public int level() {
        return levelFor(xp);
    }

    /** Progress to the next level, 0..1 (1 at the cap). */
    public float levelProgress() {
        int l = level();
        if (l >= MAX_LEVEL) return 1f;
        return Math.max(0f, Math.min(1f, (xp - xpFor(l)) / xpToNext(l)));
    }

    /** Training weight for a stat (what this ride or fight trained). */
    public void credit(int stat, float weight) {
        train[stat] += weight;
    }

    /**
     * Adds experience; every level crossed turns the training of that level into one growth point. Returns the stat
     * indices that grew (empty when no level was gained; a stat already at its cap passes the point on).
     */
    public int[] addXp(float amount) {
        if (amount <= 0 || level() >= MAX_LEVEL) return new int[0];
        int before = level();
        xp = Math.min(xpFor(MAX_LEVEL), xp + amount);
        int after = level();
        int[] grew = new int[Math.max(0, after - before)];
        for (int i = 0; i < grew.length; i++) grew[i] = awardPoint(before + i);
        return grew;
    }

    /** Gives one growth point to the most trained stat that is below its cap; -1 when every stat is at its cap. */
    int awardPoint(int salt) {
        int best = -1;
        float bestTrain = -1f;
        for (int k = 0; k < MountProfile.N; k++) {
            int i = (k + salt) % MountProfile.N; // ties rotate with the level
            if (grow[i] >= cap(i)) continue;
            if (train[i] > bestTrain) {
                bestTrain = train[i];
                best = i;
            }
        }
        java.util.Arrays.fill(train, 0f);
        if (best >= 0) grow[best]++;
        return best;
    }

    public void addTrust(float delta) {
        trust = clampTrust(trust + delta);
    }

    static float clampTrust(float v) {
        return Math.max(0f, Math.min(100f, v));
    }

    // ------------------------------------------------------------------ persistence

    public CompoundTag toTag() {
        CompoundTag t = new CompoundTag();
        t.putByteArray("q", q.clone());
        t.putByteArray("g", grow.clone());
        CompoundTag tr = new CompoundTag();
        for (int i = 0; i < train.length; i++) if (train[i] != 0f) tr.putFloat(Integer.toString(i), train[i]);
        if (!tr.isEmpty()) t.put("t", tr);
        t.putFloat("xp", xp);
        t.putFloat("trust", trust);
        if (fedAt > Long.MIN_VALUE / 4) t.putLong("fed", fedAt);
        if (stabledAt >= 0) t.putLong("stabled", stabledAt);
        if (rev != 0) t.putInt("rev", rev);
        if (trainWeek >= 0) {
            t.putInt("tw", trainWeek);
            t.putInt("tn", trainCount);
        }
        return t;
    }

    public static MountStats fromTag(MountKind kind, CompoundTag t) {
        MountStats s = new MountStats(kind);
        byte[] q = t.getByteArray("q").orElseGet(() -> new byte[0]);
        for (int i = 0; i < s.q.length && i < q.length; i++) s.q[i] = q[i];
        byte[] g = t.getByteArray("g").orElseGet(() -> new byte[0]);
        for (int i = 0; i < s.grow.length && i < g.length; i++) s.grow[i] = g[i];
        CompoundTag tr = t.getCompoundOrEmpty("t");
        for (int i = 0; i < s.train.length; i++) s.train[i] = tr.getFloatOr(Integer.toString(i), 0f);
        s.xp = Math.max(0f, t.getFloatOr("xp",0F));
        s.trust = clampTrust(t.getFloatOr("trust",0F));
        if (t.contains("fed")) s.fedAt = t.getLongOr("fed",0L);
        if (t.contains("stabled")) s.stabledAt = t.getLongOr("stabled",0L);
        s.rev = t.getIntOr("rev",0);
        if (t.contains("tw")) {
            s.trainWeek = t.getIntOr("tw",0);
            s.trainCount = t.getIntOr("tn",0);
        }
        return s;
    }

    public MountStats copy() {
        return fromTag(kind, toTag());
    }

    // ------------------------------------------------------------------ on entities

    /** True when a mount entity carries its stats (owned mounts always do; an untouched wild animal does not). */
    public static boolean stored(Entity e) {
        return com.nirncraft.platform.NbtData.hasType(e.getPersistentData(), TAG, 10);
    }

    /**
     * The stats of a mount in the world: the stored ones, else rolled from the animal's UUID (the same animal on every
     * call, and the same one that is stored when it is tamed).
     */
    public static MountStats of(Entity e, MountKind kind) {
        CompoundTag t = e.getPersistentData();
        if (com.nirncraft.platform.NbtData.hasType(t, TAG, 10)) return fromTag(kind, t.getCompoundOrEmpty(TAG));
        return roll(kind, seedOf(e.getUUID(), kind), 0f, Mounts.ownerOf(e) == null);
    }

    @Nullable
    public static MountStats of(Entity e) {
        MountKind kind = Mounts.kindOf(e);
        return kind == null ? null : of(e, kind);
    }

    public static void store(Entity e, MountStats s) {
        e.getPersistentData().put(TAG, s.toTag());
    }
}
