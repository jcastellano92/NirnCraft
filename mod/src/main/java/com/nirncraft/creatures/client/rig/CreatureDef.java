package com.nirncraft.creatures.client.rig;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything the client needs to draw one creature: its {@link Rig} and its animation set, keyed by slot name
 * ({@code idle, walk, run, fly, swim, attack, attack2, attack3, special, special2, special3, death, hurt, ...}; see
 * {@code creatures.entity.anim.AnimSlot}), plus how the head follows the look direction.
 */
public final class CreatureDef {
    public final Rig rig;
    public final Map<String, Anim> anims = new LinkedHashMap<>();
    /** Bones turned by the look direction, with the share of the yaw/pitch each takes. */
    public final Map<String, Float> look = new LinkedHashMap<>();
    /** Gait: animation seconds per block walked (walk/run loops are driven by distance walked, not time). */
    public float walkSecondsPerBlock = 0.5f;
    public float runSecondsPerBlock = 0.35f;
    /** Limb swing amount (0..1) above which the run loop replaces the walk loop. */
    public float runThreshold = 0.55f;
    /** Extra per-creature numbers the client model may read (e.g. "wingSpan"). */
    public final Map<String, Float> params = new HashMap<>();

    public CreatureDef(Rig rig) {
        this.rig = rig;
    }

    public CreatureDef anim(String slot, Anim anim) {
        anims.put(slot, anim);
        return this;
    }

    public CreatureDef look(String bone, float share) {
        look.put(bone, share);
        return this;
    }

    public CreatureDef gait(float walkSecondsPerBlock, float runSecondsPerBlock, float runThreshold) {
        this.walkSecondsPerBlock = walkSecondsPerBlock;
        this.runSecondsPerBlock = runSecondsPerBlock;
        this.runThreshold = runThreshold;
        return this;
    }

    public CreatureDef param(String key, float value) {
        params.put(key, value);
        return this;
    }

    public Anim get(String slot) {
        return anims.get(slot);
    }
}
