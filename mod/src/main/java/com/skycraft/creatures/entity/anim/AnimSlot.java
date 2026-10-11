package com.skycraft.creatures.entity.anim;

import java.util.Locale;

/**
 * Animation slots shared by every animated creature. Loops are chosen by the client model from the creature's state
 * (moving, flying, swimming...); one-shots are started by the server through {@link CreatureAnims#play} (an entity
 * event), so the client plays them in sync with the attack's hit frame.
 */
public enum AnimSlot {
    IDLE(false), WALK(false), RUN(false), FLY(false), HOVER(false), SWIM(false), SLEEP(false),
    ATTACK(true), ATTACK2(true), ATTACK3(true),
    SPECIAL(true), SPECIAL2(true), SPECIAL3(true),
    ROAR(true), CAST(true), SUMMON(true), EMERGE(true), LAND(true), TAKEOFF(true), SHOUT(true),
    DEATH(false), HURT(false);

    /** Entity event ids {@code EVENT_BASE + ordinal} start one-shots on the client. Vanilla uses ids below 64. */
    public static final int EVENT_BASE = 90;
    private static final AnimSlot[] VALUES = values();

    public final boolean oneShot;
    private final String key;

    AnimSlot(boolean oneShot) {
        this.oneShot = oneShot;
        this.key = name().toLowerCase(Locale.ROOT);
    }

    /** The slot name used by creature definitions ({@code "attack2"}). */
    public String key() {
        return key;
    }

    public static AnimSlot byEvent(byte id) {
        int i = id - EVENT_BASE;
        return i >= 0 && i < VALUES.length ? VALUES[i] : null;
    }

    public static int count() {
        return VALUES.length;
    }
}
