package com.skycraft.world;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The pure decisions of the rest system (no Minecraft types, so the headless selftest can drive them with fake
 * players): who must be resting before time may pass, who is the initiator and how that hands over, what a cancel does,
 * which switches forbid a skip, how fast the rest need recovers and how a fast-forward is paced. {@link RestManager}
 * applies them to real players. Documented in {@code docs/REST.md}.
 */
public final class RestLogic {
    public static final int NONE = 0;
    public static final int SLEEP = 1;
    public static final int WAIT = 2;
    /** A prisoner sleeping off the sentence in the cell bed: the same cinematic, but personal and without hours. */
    public static final int JAIL = 3;

    public static final int TICKS_PER_HOUR = 1000;

    private RestLogic() {}

    /** One player as the group rule sees them. {@code excluded}: spectator, jailed, dead or (when not resting) AFK. */
    public record Participant(UUID id, boolean resting, boolean excluded) {}

    public record Tally(int resting, int required, boolean allResting) {}

    /** How many of the group must rest, how many do, and whether the time skip may start. */
    public static Tally tally(List<Participant> players) {
        int required = 0, resting = 0;
        for (Participant p : players) {
            if (p.excluded()) continue;
            required++;
            if (p.resting()) resting++;
        }
        return new Tally(resting, required, required > 0 && resting == required);
    }

    /**
     * The player who picks the amount of time. The current initiator keeps it while still resting; when they leave (or
     * stop counting) it passes to the player who has been resting longest ({@code startOrder} is oldest first).
     */
    public static UUID initiator(UUID current, List<Participant> startOrder) {
        UUID first = null;
        for (Participant p : startOrder) {
            if (!p.resting() || p.excluded()) continue;
            if (p.id().equals(current)) return current;
            if (first == null) first = p.id();
        }
        return first;
    }

    public enum Cancel {
        /** Not skipping: only this player gets up. */
        LEAVE_ONLY,
        /** The initiator cancelled a running skip: it stops for everybody and everybody gets up. */
        STOP_ALL_AND_WAKE,
        /** Somebody else got up during a skip: that player leaves; the skip stops (not everyone rests) but the rest stay. */
        LEAVER_ONLY_STOP_SKIP
    }

    public static Cancel cancel(boolean skipRunning, boolean isInitiator) {
        if (!skipRunning) return Cancel.LEAVE_ONLY;
        return isInitiator ? Cancel.STOP_ALL_AND_WAKE : Cancel.LEAVER_ONLY_STOP_SKIP;
    }

    /**
     * A player was disturbed (damage, a hostile hunting them, pushed away): they get up to defend themselves and a
     * running skip stops for everybody, whoever was hit, and everybody gets up.
     */
    public static Cancel disturb(boolean skipRunning) {
        return skipRunning ? Cancel.STOP_ALL_AND_WAKE : Cancel.LEAVE_ONLY;
    }

    public enum Block {
        NONE, JAIL, MODE_DISABLED, MULTIPLAYER_OFF, NOT_ALL_RESTING, NOT_INITIATOR
    }

    /**
     * Why a skip cannot start now (or {@link Block#NONE}). {@code onlinePlayers} counts every player on the server:
     * with {@code syncMultiplayer} off a server with more than one player never skips world time; the player then
     * gets a personal fast-forward ({@link #personal}) that restores needs only.
     */
    public static Block startBlock(int mode, boolean isInitiator, Tally tally, int onlinePlayers, boolean syncMultiplayer,
                                   boolean allowWait, boolean allowSleepSkip) {
        if (mode == JAIL) return Block.JAIL;
        if (mode == WAIT && !allowWait) return Block.MODE_DISABLED;
        if (mode == SLEEP && !allowSleepSkip) return Block.MODE_DISABLED;
        if (personal(onlinePlayers, syncMultiplayer)) return Block.NONE; // personal: nobody else has to rest
        if (!tally.allResting()) return Block.NOT_ALL_RESTING;
        if (!isInitiator) return Block.NOT_INITIATOR;
        return Block.NONE;
    }

    /** The skip is personal (no world time, no group) because multiplayer sync is off on a server with several players. */
    public static boolean personal(int onlinePlayers, boolean syncMultiplayer) {
        return onlinePlayers > 1 && !syncMultiplayer;
    }

    /** A player who has not acted for {@code timeoutSeconds} (0 disables) is AFK and no longer holds the others back. */
    public static boolean afk(long nowTick, long lastActiveTick, int timeoutSeconds) {
        return timeoutSeconds > 0 && nowTick - lastActiveTick >= timeoutSeconds * 20L;
    }

    // ------------------------------------------------------------------ rest need

    /**
     * Rest-need points (of {@code max}) recovered per second: a bed refills a drained bar in {@code fullSeconds}, a
     * bedroll in {@code bedrollPercent} of that speed. Waiting (sitting) recovers nothing: only a bed rests you.
     */
    public static float recoveryPerSecond(int mode, boolean bedroll, float max, int fullSeconds, int bedrollPercent) {
        float base = max / Math.max(1, fullSeconds);
        return switch (mode) {
            case SLEEP -> bedroll ? base * bedrollPercent / 100f : base;
            case JAIL -> base;
            default -> 0f;
        };
    }

    // ------------------------------------------------------------------ catching the world up

    /** A status effect's duration after {@code skipped} ticks passed (infinite stays infinite; 0 means it ran out). */
    public static int effectLeft(int duration, long skipped) {
        if (duration < 0) return duration;
        return (int) Math.max(0L, duration - Math.max(0L, skipped));
    }

    /** A jail sentence's remaining seconds after {@code skippedTicks} of world time passed. */
    public static int jailLeft(int remainingSeconds, long skippedTicks) {
        return (int) Math.max(0L, remainingSeconds - Math.max(0L, skippedTicks) / 20L);
    }

    /**
     * Extra random-tick samples per section for one skip tick that moved the clock {@code step} ticks: the vanilla
     * {@code randomTickSpeed} per skipped tick (one tick already happened naturally), capped for performance.
     */
    public static int growthSamples(long step, int randomTickSpeed, int cap) {
        long n = Math.max(0L, step - 1) * Math.max(0, randomTickSpeed);
        return (int) Math.min(cap, n);
    }

    /** An animal's age counter after {@code step} ticks: babies grow towards 0, breeding cooldowns count down. */
    public static int ageAfter(int age, long step) {
        if (age < 0) return (int) Math.min(0L, age + step);
        if (age > 0) return (int) Math.max(0L, age - step);
        return 0;
    }

    /**
     * Whether a hostile spawn is refused: dimension-wide while a time skip runs, and near (within {@code radius}
     * blocks of) a resting player otherwise ({@code nearestRestSq}: squared distance, or a negative number for none).
     */
    public static boolean spawnBlocked(boolean skipRunningInDimension, double nearestRestSq, double radius) {
        if (skipRunningInDimension) return true;
        return nearestRestSq >= 0 && nearestRestSq <= radius * radius;
    }

    /** Seconds to refill a bar from {@code value} (0 = rested) with the given speed per second. */
    public static float secondsToRecover(float value, float perSecond) {
        return perSecond <= 0f ? Float.POSITIVE_INFINITY : Math.max(0f, value) / perSecond;
    }

    // ------------------------------------------------------------------ time

    /** Skyrim clock hour (0-23); world day time 0 is 6 am. */
    public static int hourOfDay(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        return (int) ((t / 1000L + 6) % 24);
    }

    /** Whole hours (1-24) until the clock next reads {@code targetHour}:00, rounded up; a full day if it already does. */
    public static int hoursUntil(long dayTime, int targetHour) {
        long s = (Math.floorMod(dayTime, 24000L) + 6000L) % 24000L; // ticks since midnight
        long delta = Math.floorMod(targetHour * 1000L - s, 24000L);
        if (delta == 0) return 24;
        return (int) Math.max(1, Math.min(24, (delta + TICKS_PER_HOUR - 1) / TICKS_PER_HOUR));
    }

    /** Real ticks the fast-forward of {@code hours} takes (between 3 s and {@code maxSeconds}). */
    public static int durationTicks(int hours, int maxSeconds) {
        int cap = Math.max(60, maxSeconds * 20);
        return Math.max(60, Math.min(cap, 60 + hours * 10));
    }

    /** World ticks that must have passed after {@code elapsed} of {@code duration} real ticks (eased in and out). */
    public static long worldTicksAt(long total, int duration, int elapsed) {
        if (elapsed >= duration) return total;
        if (elapsed <= 0) return 0;
        double x = elapsed / (double) duration;
        double eased = x * x * (3 - 2 * x); // smoothstep: speeds up, then slows to a stop
        return Math.min(total, Math.round(total * eased));
    }

    /** Whole hours crossed when the done counter moves from {@code before} to {@code after}. */
    public static int hoursCrossed(long before, long after) {
        return (int) (after / TICKS_PER_HOUR - before / TICKS_PER_HOUR);
    }

    /** The share of full health, magicka and stamina that {@code hours} of rest give back. */
    public static float restFraction(boolean sleep, int hours) {
        return sleep && hours >= 8 ? 1f : Math.min(1f, Math.max(0, hours) / 8f);
    }

    // ------------------------------------------------------------------ selftest

    private static UUID id(int n) {
        return new UUID(0xABCDL, n);
    }

    /** The headless checks ({@code /skycraft-crime selftest} runs them): returns one line per failed check. */
    public static List<String> selfTest(int[] checks) {
        List<String> fails = new ArrayList<>();
        Check c = new Check(fails, checks);

        // group requirement: everyone must rest, spectators and jailed players do not count
        UUID a = id(1), b = id(2), jailed = id(3), spec = id(4), afker = id(5);
        List<Participant> g = List.of(new Participant(a, true, false), new Participant(b, false, false));
        c.eq(!tally(g).allResting(), "one of two resting is not enough");
        c.eq(tally(g).resting() == 1 && tally(g).required() == 2, "tally 1/2");
        g = List.of(new Participant(a, true, false), new Participant(b, true, false));
        c.eq(tally(g).allResting(), "both resting");
        g = List.of(new Participant(a, true, false), new Participant(jailed, false, true), new Participant(spec, false, true));
        c.eq(tally(g).allResting() && tally(g).required() == 1, "jailed and spectators excluded from the requirement");
        g = List.of(new Participant(jailed, false, true));
        c.eq(!tally(g).allResting(), "nobody required means no group skip");
        // AFK: a non-resting player idle past the timeout is excluded by the caller; the timeout maths
        c.eq(!afk(100, 0, 300), "not AFK after 5 s");
        c.eq(afk(6000, 0, 300), "AFK after 5 minutes");
        c.eq(!afk(100000, 0, 0), "AFK timeout 0 disables");
        g = List.of(new Participant(a, true, false), new Participant(afker, false, afk(7000, 0, 300)));
        c.eq(tally(g).allResting(), "AFK player does not hold the group back");

        // initiator handover
        List<Participant> order = List.of(new Participant(a, true, false), new Participant(b, true, false));
        c.eq(a.equals(initiator(null, order)), "first resting is the initiator");
        c.eq(b.equals(initiator(b, order)), "current initiator is kept");
        order = List.of(new Participant(a, false, false), new Participant(b, true, false));
        c.eq(b.equals(initiator(a, order)), "initiator left: hands over to the next resting");
        c.eq(initiator(a, List.of(new Participant(a, false, false))) == null, "nobody resting: no initiator");
        c.eq(b.equals(initiator(a, List.of(new Participant(a, true, true), new Participant(b, true, false)))),
                "an excluded initiator hands over");

        // cancel semantics
        c.eq(cancel(false, false) == Cancel.LEAVE_ONLY, "cancel without skip leaves alone");
        c.eq(cancel(true, true) == Cancel.STOP_ALL_AND_WAKE, "initiator cancel stops all");
        c.eq(cancel(true, false) == Cancel.LEAVER_ONLY_STOP_SKIP, "other cancel removes them and stops the skip");

        // config switches
        Tally all = new Tally(2, 2, true), some = new Tally(1, 2, false);
        c.eq(startBlock(SLEEP, true, all, 2, true, true, true) == Block.NONE, "all resting initiator may start");
        c.eq(startBlock(SLEEP, true, some, 2, true, true, true) == Block.NOT_ALL_RESTING, "not all resting");
        c.eq(startBlock(SLEEP, false, all, 2, true, true, true) == Block.NOT_INITIATOR, "only the initiator starts");
        c.eq(startBlock(WAIT, true, all, 2, true, false, true) == Block.MODE_DISABLED, "allowWait off");
        c.eq(startBlock(SLEEP, true, all, 2, true, true, false) == Block.MODE_DISABLED, "allowSleepSkip off");
        c.eq(startBlock(SLEEP, true, some, 2, false, true, true) == Block.NONE && personal(2, false),
                "sync off in multiplayer: personal skip, nobody else needed");
        c.eq(startBlock(SLEEP, true, new Tally(1, 1, true), 1, false, true, true) == Block.NONE
                && !personal(1, false), "sync off alone: normal");
        c.eq(startBlock(JAIL, true, all, 1, true, true, true) == Block.JAIL, "jail sleep is not a world skip");

        // need recovery: bed refills 240 in 120 s, bedroll and waiting slower
        float bed = recoveryPerSecond(SLEEP, false, 240f, 120, 75);
        c.eq(Math.abs(bed - 2f) < 1e-4, "bed 2 points/s");
        c.eq(Math.abs(secondsToRecover(240f, bed) - 120f) < 0.01f, "drained bar full in 120 s");
        c.eq(recoveryPerSecond(SLEEP, true, 240f, 120, 75) < bed, "bedroll slower than bed");
        c.eq(recoveryPerSecond(WAIT, false, 240f, 120, 75) == 0f, "waiting does not rest you: only a bed does");
        c.eq(secondsToRecover(120f, bed) < 61f, "half drained takes half the time");

        // the world catches up with the skipped time
        c.eq(effectLeft(1000, 400) == 600 && effectLeft(300, 8000) == 0 && effectLeft(-1, 8000) == -1, "effect durations shrink, expire, infinite stays");
        c.eq(jailLeft(300, 4000) == 100 && jailLeft(60, 8000) == 0, "jail sentence counts down by skipped time");
        c.eq(growthSamples(1, 3, 384) == 0 && growthSamples(57, 3, 384) == 168 && growthSamples(500, 3, 384) == 384, "growth samples scale and cap");
        c.eq(ageAfter(-6000, 1000) == -5000 && ageAfter(-500, 1000) == 0 && ageAfter(300, 1000) == 0 && ageAfter(5000, 1000) == 4000, "animal ages");
        c.eq(spawnBlocked(true, -1, 96) && spawnBlocked(false, 50 * 50, 96) && !spawnBlocked(false, 200 * 200, 96) && !spawnBlocked(false, -1, 96),
                "no hostile spawns during a skip or near a resting player");
        c.eq(cancel(true, false) == Cancel.LEAVER_ONLY_STOP_SKIP && disturb(true) == Cancel.STOP_ALL_AND_WAKE && disturb(false) == Cancel.LEAVE_ONLY,
                "a disturbance (damage, hunter) cancels the skip for all whoever it hits");

        // time maths
        c.eq(hourOfDay(0) == 6 && hourOfDay(12000) == 18 && hourOfDay(18000) == 0, "clock hours");
        c.eq(hoursUntil(0, 18) == 12, "from 6:00 until 18:00 is 12 h");
        c.eq(hoursUntil(0, 6) == 24, "until the hour it already is: a day");
        c.eq(hoursUntil(18000, 6) == 6, "from midnight until 6:00 is 6 h");
        c.eq(hoursUntil(4500, 18) == 8, "10:30 until 18:00 rounds up to 8 h");
        c.eq(durationTicks(1, 12) == 70 && durationTicks(24, 12) == 240 && durationTicks(24, 3) == 60, "durations clamp");
        long total = 8000;
        int dur = durationTicks(8, 12);
        long prev = 0;
        boolean monotone = true;
        for (int e = 1; e <= dur; e++) {
            long w = worldTicksAt(total, dur, e);
            if (w < prev) monotone = false;
            prev = w;
        }
        c.eq(monotone && worldTicksAt(total, dur, dur) == total && worldTicksAt(total, dur, 0) == 0, "fast-forward ends exactly on target");
        c.eq(hoursCrossed(900, 2100) == 2 && hoursCrossed(0, 999) == 0, "hours crossed");
        c.eq(restFraction(true, 8) == 1f && restFraction(false, 8) == 1f && restFraction(true, 4) == 0.5f, "rest fraction");
        return fails;
    }

    private static final class Check {
        private final List<String> fails;
        private final int[] counter;

        Check(List<String> fails, int[] counter) {
            this.fails = fails;
            this.counter = counter;
        }

        void eq(boolean ok, String what) {
            counter[0]++;
            if (!ok) fails.add("rest: " + what);
        }
    }
}
