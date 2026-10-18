package com.skycraft.creatures.entity.anim;

import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;

/**
 * The animation states of one creature (vanilla {@link AnimationState}s, one per {@link AnimSlot}). The server
 * starts one-shots with {@link #play}, which sends an entity event; the creature's {@code handleEntityEvent} hands it
 * to {@link #onEvent}, which starts the state on the client. Starting a one-shot stops the other one-shots, so a new
 * attack always reads cleanly.
 */
public final class CreatureAnims {
    private final AnimationState[] states = new AnimationState[AnimSlot.count()];

    public CreatureAnims() {
        for (int i = 0; i < states.length; i++) states[i] = new AnimationState();
    }

    public AnimationState state(AnimSlot slot) {
        return states[slot.ordinal()];
    }

    /** Server side: plays a one-shot animation for everyone tracking the creature. */
    public static void play(Entity entity, AnimSlot slot) {
        if (!entity.level().isClientSide()) {
            entity.level().broadcastEntityEvent(entity, (byte) (AnimSlot.EVENT_BASE + slot.ordinal()));
        }
    }

    /** Client side: true if the event was an animation event (then it was handled). */
    public boolean onEvent(Entity entity, byte id) {
        AnimSlot slot = AnimSlot.byEvent(id);
        if (slot == null) return false;
        if (slot.oneShot) {
            for (AnimSlot s : AnimSlot.values()) {
                if (s.oneShot && s != slot) state(s).stop();
            }
        }
        state(slot).start(entity.tickCount);
        return true;
    }

    /** Stops every running one-shot (e.g. when the creature dies). */
    public void stopOneShots() {
        for (AnimSlot s : AnimSlot.values()) {
            if (s.oneShot) state(s).stop();
        }
    }
}
