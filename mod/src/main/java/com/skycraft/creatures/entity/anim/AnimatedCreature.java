package com.skycraft.creatures.entity.anim;

/**
 * A creature drawn by the keyframe-animated rig models ({@code creatures.client.kit.RigModel}). The model reads
 * these to choose loops; one-shots come from {@link #anims()}.
 */
public interface AnimatedCreature {
    CreatureAnims anims();

    /** Flying (fly loop instead of walk). */
    default boolean animFlying() {
        return false;
    }

    /** Swimming (swim loop). */
    default boolean animSwimming() {
        return false;
    }

    /** Asleep / dormant (sleep loop, e.g. a closed sphere or a resting dragon). */
    default boolean animSleeping() {
        return false;
    }

    /** Variant index for textures and optional geometry. */
    default int animVariant() {
        return 0;
    }
}
