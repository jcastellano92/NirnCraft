package com.skycraft.mounts;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * What a stable sells and what an animal is worth. Pure arithmetic: the stock of a stable is the same for everyone
 * who asks in the same in-game week (a seed of the stable's key, the week and the slot), and a bought animal is the
 * very individual that was shown.
 *
 * <ul>
 *     <li><b>Stock.</b> Each kind the region sells ({@link MountKind#forSale}) has one or two animals; the stable's own
 *     quality (fixed per stable, some are simply better) and the week's luck shift the whole bell.</li>
 *     <li><b>Price.</b> The kind's list price times a factor of the animal's rolls and growth, so a Superb, well-trained
 *     one costs several times a Poor one. A stable pays {@link #TRADE_IN} of that for yours.</li>
 *     <li><b>Training.</b> A stable hand puts an animal through one growth point of a stat for a fee, twice a week at
 *     most, never past the stat's soft cap.</li>
 * </ul>
 */
public final class MountStock {
    public static final long WEEK = 7 * 24000L;
    public static final float TRADE_IN = 0.6f;
    public static final int TRAINING_PER_WEEK = 2;
    /** Trust one training session of Trust gives. */
    public static final float TRUST_TRAINING = 10f;

    private MountStock() {}

    /** One animal for sale. {@code id}: {@code <kind>#<slot>} for generated stock (stable within the week), {@code t:<animal id>} for one a player sold here, {@code p:<animal id>} from the pool. */
    public record Offer(String id, MountKind kind, MountStats stats, int price, @Nullable CompoundTag token) {
        /** The animal as a token: a player's animal brings its own, a generated one is born (a new animal id) when it is bought. */
        public CompoundTag tokenOrNew() {
            return token != null ? token.copy() : MountToken.create(kind, stats, null, java.util.UUID.randomUUID());
        }
    }

    public static long weekOf(long gameTime) {
        return Math.max(0, gameTime) / WEEK;
    }

    /** 0..1, fixed for a stable: some are simply better. */
    public static float stableQuality(String stableKey) {
        return new SplittableRandom(stableKey.hashCode() * 0x9E3779B97F4A7C15L + 11).nextFloat();
    }

    /** The stock of a stable for a week. {@code sold} are the offer ids this buyer already took. */
    public static List<Offer> offers(String stableKey, long week, List<MountKind> kinds, Set<String> sold) {
        float quality = stableQuality(stableKey);
        float luck = new SplittableRandom(stableKey.hashCode() * 31L + week * 0x2545F4914F6CDD1DL).nextFloat();
        float shift = (quality - 0.5f) * 0.5f + (luck - 0.5f) * 0.3f;
        List<Offer> out = new ArrayList<>();
        int first = 0;
        for (MountKind kind : kinds) {
            if (kind.unique()) continue;
            int n = first++ == 0 ? 2 : 1;
            for (int slot = 0; slot < n; slot++) {
                String id = kind.id + "#" + slot;
                if (sold.contains(id)) continue;
                long seed = (stableKey + "|" + week + "|" + id).hashCode() * 0x9E3779B97F4A7C15L + slot * 7919L;
                MountStats s = MountStats.rollStock(kind, seed, shift, quality);
                out.add(new Offer(id, kind, s, value(kind, s), null));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ prices

    /** The factor of the list price: 0.75 for an average animal, up to about 2 for a Superb, trained one. */
    public static float priceFactor(MountStats s) {
        float f = 0.75f + 0.45f * s.quality();
        f += 0.05f * (s.level() - 1);
        return Math.max(0.5f, Math.min(2.6f, f));
    }

    /** What a stable asks for the animal (to the nearest 5). */
    public static int value(MountKind kind, MountStats s) {
        return Math.max(5, Math.round(kind.cost * priceFactor(s) / 5f) * 5);
    }

    /** What a stable pays for yours. */
    public static int tradeIn(MountKind kind, MountStats s) {
        if (kind.unique()) return 0;
        return Math.max(0, Math.round(value(kind, s) * TRADE_IN / 5f) * 5);
    }

    // ------------------------------------------------------------------ training

    public enum Train {
        OK, CAP, WEEK_LIMIT, MAXED
    }

    /** Whether a stable hand will train the stat (0..4, or {@link MountProfile#TEMPER} for Trust) this week. */
    public static Train canTrain(MountStats s, int stat, long week) {
        if (s.kind.unique()) return Train.CAP; // the legends do not need a stable hand
        int used = s.trainWeek == week ? s.trainCount : 0;
        if (used >= TRAINING_PER_WEEK) return Train.WEEK_LIMIT;
        if (stat == MountProfile.TEMPER) return s.trust >= 99.5f ? Train.MAXED : Train.OK;
        return s.grow[stat] >= s.cap(stat) ? Train.CAP : Train.OK;
    }

    /** The fee: it scales with the stat's training so far, the animal's level and the grade it started at. */
    public static int trainFee(MountStats s, int stat) {
        if (stat == MountProfile.TEMPER) return 40 + 2 * Math.round(s.trust) + 10 * s.level();
        return 50 + 40 * s.grow[stat] + 20 * s.level() + 15 * s.grade(stat) * (1 + s.grow[stat] / 3);
    }

    /** Applies one paid session (the caller took the gold and checked {@link #canTrain}). */
    public static void applyTraining(MountStats s, int stat, long week) {
        if (stat == MountProfile.TEMPER) s.addTrust(TRUST_TRAINING);
        else s.grow[stat]++;
        if (s.trainWeek != week) {
            s.trainWeek = (int) week;
            s.trainCount = 0;
        }
        s.trainCount++;
    }
}
