package com.skycraft.creatures.spec;

import com.skycraft.creatures.entity.anim.AnimSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * One move of a creature's repertoire (bite, claw, slam, fireball, breath, summon...). The attack goal picks a ready
 * move whose range fits, plays its animation (a one-shot synced to clients) and calls {@link #perform} on the hit
 * frame ({@link #windup} ticks in), so damage lands when the animation strikes. Attack objects are shared between
 * creatures: per-creature state (cooldowns) lives in the goal.
 */
public abstract class Attack {
    public AnimSlot anim = AnimSlot.ATTACK;
    /** Ticks from the start of the animation to the hit. */
    public int windup = 8;
    /** Total ticks the move occupies the creature. */
    public int duration = 16;
    /** Ticks before this move can be used again. */
    public int cooldown = 20;
    /** Usable when the gap between the two bodies is within [minRange, maxRange] blocks. */
    public float minRange = 0;
    public float maxRange = 1.8f;
    public float weight = 1;
    /** The creature stands still (and keeps facing its target) while the move plays. */
    public boolean roots = true;
    /** Needs a clear line of sight to start (ranged moves). */
    public boolean needsSight = true;
    /** Chance per decision tick to use it when ready (rare specials). */
    public float chance = 1f;
    /** The creature's cry when the move starts ({@link CreatureSpec#cry(String)}): attack, roar, cast, breath; null = none. */
    public String cry;

    public boolean canUse(Mob mob, LivingEntity target, double gap) {
        return gap >= minRange && gap <= maxRange;
    }

    /** Called when the move starts (after the animation was sent). */
    public void start(Mob mob, LivingEntity target) {}

    /** Called every tick of the move; performs on the hit frame by default. */
    public void tick(Mob mob, LivingEntity target, int t) {
        if (t == windup) perform(mob, target);
    }

    protected abstract void perform(Mob mob, LivingEntity target);

    // fluent configuration ----------------------------------------------------------------------

    public Attack anim(AnimSlot slot) {
        this.anim = slot;
        return this;
    }

    public Attack timing(int windup, int duration, int cooldown) {
        this.windup = windup;
        this.duration = duration;
        this.cooldown = cooldown;
        return this;
    }

    public Attack range(float min, float max) {
        this.minRange = min;
        this.maxRange = max;
        return this;
    }

    public Attack weight(float weight) {
        this.weight = weight;
        return this;
    }

    public Attack moving() {
        this.roots = false;
        return this;
    }

    public Attack chance(float chance) {
        this.chance = chance;
        return this;
    }
}
