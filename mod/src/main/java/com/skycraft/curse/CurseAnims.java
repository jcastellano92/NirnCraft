package com.skycraft.curse;

import net.minecraft.world.entity.AnimationState;

/**
 * One-shot keyframe animations shared by the beast models (werewolf, Vampire Lord, death hound, gargoyle), for both
 * the creatures and transformed players. Holds one {@link AnimationState} per {@link Anim}; the server starts them
 * with an entity event (creatures) or a {@link CursePackets.Anim} packet (players).
 */
public final class CurseAnims {
    public enum Anim {
        /** Claw swipe (right/left alternate on the client). */
        ATTACK(14),
        /** Howl (werewolf) or roar (Vampire Lord, gargoyle). */
        HOWL(40),
        /** Crouched over a corpse (werewolf) or biting a victim (Vampire Lord). */
        FEED(50),
        /** The change itself: hunched, arms thrown back. */
        TRANSFORM(40),
        /** Casting: Drain Life, Raise Dead, a night power. */
        CAST(16),
        /** Gargoyle waking from stone. */
        AWAKEN(30);

        public static final Anim[] VALUES = values();
        /** How long the animation runs, in ticks (after this it is stopped). */
        public final int ticks;

        Anim(int ticks) {
            this.ticks = ticks;
        }

        public static Anim byOrdinal(int i) {
            return i >= 0 && i < VALUES.length ? VALUES[i] : ATTACK;
        }
    }

    /** Entity event ids (see {@link net.minecraft.world.entity.Entity#handleEntityEvent}) used by the creatures. */
    public static final byte EVENT_BASE = 70;

    private final AnimationState[] states = new AnimationState[Anim.VALUES.length];
    private final int[] started = new int[Anim.VALUES.length];
    /** Which arm swings next (alternating claws). */
    public boolean leftSwing;

    public CurseAnims() {
        for (int i = 0; i < states.length; i++) states[i] = new AnimationState();
    }

    public AnimationState get(Anim anim) {
        return states[anim.ordinal()];
    }

    public void start(Anim anim, int tickCount) {
        if (anim == Anim.ATTACK) leftSwing = !leftSwing;
        states[anim.ordinal()].start(tickCount);
        started[anim.ordinal()] = tickCount;
    }

    /** Stops animations that have run their course; call once per client tick. */
    public void tick(int tickCount) {
        for (Anim a : Anim.VALUES) {
            AnimationState s = states[a.ordinal()];
            if (s.isStarted() && tickCount - started[a.ordinal()] > a.ticks) s.stop();
        }
    }

    public boolean playing(Anim anim) {
        return states[anim.ordinal()].isStarted();
    }

    public static byte event(Anim anim) {
        return (byte) (EVENT_BASE + anim.ordinal());
    }

    /** The animation an entity event id stands for, or null if it isn't one of ours. */
    public static Anim fromEvent(byte id) {
        int i = id - EVENT_BASE;
        return i >= 0 && i < Anim.VALUES.length ? Anim.VALUES[i] : null;
    }

    /** Creatures with these animations. */
    public interface Animated {
        CurseAnims anims();
    }
}
