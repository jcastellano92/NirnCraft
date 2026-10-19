package com.nirncraft.fauna;

/**
 * The physics of the horse-breaking balance minigame, with no Minecraft dependency (so it is selftested and tunable
 * in isolation). The server runs one of these per breaking ({@link WildHorseTaming}); the client only draws its state.
 *
 * <p>A marker sits on a bar from -1 (left) to +1 (right). The animal's bucking pushes it with a force that is the sum
 * of two waves whose strength and speed grow over the whole breaking ("the longer you hold the harder it gets"); the
 * rider counter-steers with left and right. Time with the marker inside the safe zone fills the progress bar, time
 * outside drains it; touching an edge for longer than the grace throws the rider. Full progress wins.</p>
 *
 * <p>Difficulty 1..5 is the kind's {@code tameDifficulty}; {@code calm} (0..1) is the animal's trust and temperament;
 * {@code seat} is the rider's Steady Seat rank (slower growth of the bucking, a wider zone, a longer grace).</p>
 */
public final class TameBalance {
    public enum Result { RUNNING, WON, THROWN }

    /** Per tick the marker keeps this fraction of its velocity. */
    public static final float DAMPING = 0.90f;
    /** Velocity the rider's steer adds per tick (terminal speed {@code PUSH / (1 - DAMPING)} bar-widths per tick). */
    public static final float PUSH = 0.0125f;
    /** Velocity the bucking adds per tick at full amplitude. */
    static final float BUCK = 0.0085f;

    public final int difficulty;
    public final int duration;
    public final float zone;
    public final int grace;
    private final float a0, a1;
    private final float periodStart, periodEnd;

    public float marker;
    public float vel;
    public float progress;
    public int tick;
    public int edgeTicks;
    private int slack;
    private float phase1, phase2, phase3;

    public TameBalance(int difficulty, float calm, int seat, long seed) {
        this.difficulty = Math.max(1, Math.min(5, difficulty));
        calm = Math.max(0f, Math.min(1f, calm));
        int d = this.difficulty;
        // 20 s at difficulty 1 up to 35 s at 5; a placid, trusting animal is done with it sooner
        float seconds = (17f + 3.6f * d) * (1.12f - 0.24f * calm);
        this.duration = Math.round(seconds * 20f);
        float amp = 1.15f - 0.30f * calm;
        float growth = 1f - 0.20f * seat;
        this.a0 = (0.30f + 0.06f * d) * amp;
        this.a1 = this.a0 + ((0.55f + 0.10f * d) * amp - this.a0) * growth;
        this.periodStart = 64f;
        this.periodEnd = 34f - 1.5f * d;
        this.zone = 0.40f - 0.02f * d + 0.05f * seat;
        this.grace = 30 + 10 * seat;
        // each animal starts its bucking at its own point of the wave
        java.util.SplittableRandom r = new java.util.SplittableRandom(seed);
        this.phase1 = (float) (r.nextDouble() * Math.PI * 2);
        this.phase2 = (float) (r.nextDouble() * Math.PI * 2);
        this.phase3 = (float) (r.nextDouble() * Math.PI * 2);
    }

    /** 0 at the start, 1 at the end of the breaking. */
    public float fraction() {
        return Math.min(1f, tick / (float) duration);
    }

    /** The bucking strength now, 0..~1 (grows over the breaking). */
    public float amplitude() {
        return a0 + (a1 - a0) * fraction();
    }

    /** The bucking force right now, -1..1 of full amplitude (also what the horse's visible bucking follows). */
    public float buck() {
        return (float) (0.45 * Math.sin(phase1) + 0.25 * Math.sin(phase2) + 0.45 * Math.sin(phase3));
    }

    /** One tick; {@code steer} is -1 (left), 0 or +1 (right). */
    public Result step(int steer) {
        float f = fraction();
        float period = periodStart + (periodEnd - periodStart) * f;
        phase1 += (float) (Math.PI * 2 / period);
        phase2 += (float) (Math.PI * 2 / (period * 0.58f));
        phase3 += (float) (Math.PI * 2 / (period * 3.7f));
        vel = vel * DAMPING + buck() * amplitude() * BUCK + Math.max(-1, Math.min(1, steer)) * PUSH;
        marker += vel;
        if (marker > 1f) {
            marker = 1f;
            vel = Math.min(0f, vel);
        } else if (marker < -1f) {
            marker = -1f;
            vel = Math.max(0f, vel);
        }
        tick++;
        edgeTicks = Math.abs(marker) >= 0.995f ? edgeTicks + 1 : Math.max(0, edgeTicks - 2);
        if (tick > 30 && edgeTicks > grace) return Result.THROWN;
        if (Math.abs(marker) <= zone) progress += 1f / duration;
        else progress = Math.max(0f, progress - 2.0f / duration);
        // a rider who never settles, or sits still, is shaken off
        slack = progress <= 0.0005f && tick > 40 ? slack + 1 : 0;
        if (slack > 220 || tick > duration * 3) return Result.THROWN;
        return progress >= 1f ? Result.WON : Result.RUNNING;
    }
}
