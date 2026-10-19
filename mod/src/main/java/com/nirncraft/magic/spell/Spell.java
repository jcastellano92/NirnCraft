package com.nirncraft.magic.spell;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * One spell: pure data plus the lambdas that implement it. Instances live in {@link Spells}.
 *
 * <p>Costs and magnitudes are in Skyrim units except {@link #magnitude}, which is already Minecraft-scaled
 * (Skyrim damage / 5; 1 Minecraft health point = 5 Skyrim health).</p>
 */
public final class Spell {
    public enum Type { FIRE_AND_FORGET, CONCENTRATION }

    /** Outcome of a cast: {@code FAILED} costs nothing, {@code CAST} costs magicka, {@code EFFECT} also trains the skill. */
    public enum Result { FAILED, CAST, EFFECT }

    /** Called once for fire-and-forget spells (tick 0) and every tick while a concentration spell is held. */
    @FunctionalInterface
    public interface Action {
        Result cast(ServerPlayer player, Spell spell, int tick);
    }

    /** Called when a spell projectile hits an entity ({@code target} non-null) or a block. */
    @FunctionalInterface
    public interface Impact {
        void hit(SpellProjectile projectile, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos);
    }

    /** Called every server tick while a spell projectile flies. */
    @FunctionalInterface
    public interface Flight {
        void tick(SpellProjectile projectile, ServerPlayer caster, Spell spell);
    }

    public final String id;
    public final School school;
    public final Tier tier;
    public final Type type;
    /** Base magicka cost (per second for concentration spells), Skyrim units. */
    public final float cost;
    /** Damage / healing (Minecraft health points; per second for concentration) or armor points etc. */
    public final float magnitude;
    /** Effect duration in ticks (0 if none). */
    public final int duration;
    /** Range (beams, targeting) or area radius in blocks. */
    public final float radius;
    /** Ticks the cast key must be held before a fire-and-forget spell is released (master spells). */
    public final int chargeTicks;
    public final Element element;
    public final Action action;
    @Nullable
    public final Impact impact;
    @Nullable
    public final Flight flight;
    /** Projectile speed in blocks/tick. */
    public final float speed;
    /** Projectiles that pass through creatures (Ice Storm). */
    public final boolean piercing;
    /** Hostile spells mark the caster in combat and respect PvP rules. */
    public final boolean hostile;
    /** Whether casting it with both hands at once gives a dual-cast (utility spells like summons can't). */
    public final boolean dualCastable;
    /**
     * True for the dual-cast variant handed to actions/impacts by {@link #dualCast()}: its magnitude is already
     * multiplied by {@link #DUAL_MAGNITUDE}. Illusion spells affect stronger targets and Impact staggers.
     */
    public final boolean dual;
    /** Name and description of a spell made at a Spellmaking Altar (null for tome spells, which use lang keys). */
    @Nullable
    public final Component customName;
    @Nullable
    public final Component customDescription;

    /** Skyrim dual casting: 2.8x the magicka for 2.2x the effect with the Dual Casting perk. */
    public static final float DUAL_COST = 2.8f;
    public static final float DUAL_MAGNITUDE = 2.2f;

    private Spell(Builder b) {
        this.id = b.id;
        this.school = b.school;
        this.tier = b.tier;
        this.type = b.type;
        this.cost = b.cost;
        this.magnitude = b.magnitude;
        this.duration = b.duration;
        this.radius = b.radius;
        this.chargeTicks = b.chargeTicks;
        this.element = b.element;
        this.action = b.action;
        this.impact = b.impact;
        this.flight = b.flight;
        this.speed = b.speed;
        this.piercing = b.piercing;
        this.hostile = b.hostile;
        this.dualCastable = b.dualCastable;
        this.dual = false;
        this.customName = b.customName;
        this.customDescription = b.customDescription;
    }

    private Spell(Spell base, boolean dual) {
        this.id = base.id;
        this.school = base.school;
        this.tier = base.tier;
        this.type = base.type;
        this.cost = base.cost;
        this.magnitude = dual ? base.magnitude * DUAL_MAGNITUDE : base.magnitude;
        this.duration = base.duration;
        this.radius = base.radius;
        this.chargeTicks = base.chargeTicks;
        this.element = base.element;
        this.action = base.action;
        this.impact = base.impact;
        this.flight = base.flight;
        this.speed = base.speed;
        this.piercing = base.piercing;
        this.hostile = base.hostile;
        this.dualCastable = base.dualCastable;
        this.dual = dual;
        this.customName = base.customName;
        this.customDescription = base.customDescription;
    }

    /** The dual-cast variant of this spell (2.5x magnitude). */
    public Spell dualCast() {
        return dual ? this : new Spell(this, true);
    }

    public boolean isConcentration() {
        return type == Type.CONCENTRATION;
    }

    public Component displayName() {
        return customName != null ? customName : Component.translatable("spell.nirncraft." + id);
    }

    public Component description() {
        return customDescription != null ? customDescription : Component.translatable("spell.nirncraft." + id + ".desc");
    }

    /** A spell made at a Spellmaking Altar. */
    public boolean isCustom() {
        return customName != null;
    }

    public static Builder builder(String id, School school, Tier tier) {
        return new Builder(id, school, tier);
    }

    public static final class Builder {
        private final String id;
        private final School school;
        private final Tier tier;
        private Type type = Type.FIRE_AND_FORGET;
        private float cost;
        private float magnitude;
        private int duration;
        private float radius = 12f;
        private int chargeTicks;
        private Element element = Element.NONE;
        private Action action = (p, s, t) -> Result.FAILED;
        private Impact impact;
        private Flight flight;
        private float speed = 1.8f;
        private boolean piercing;
        private boolean hostile;
        private boolean dualCastable = true;
        private Component customName;
        private Component customDescription;

        private Builder(String id, School school, Tier tier) {
            this.id = id;
            this.school = school;
            this.tier = tier;
        }

        /** Fire-and-forget spell with the given base cost. */
        public Builder ff(float cost) {
            this.type = Type.FIRE_AND_FORGET;
            this.cost = cost;
            return this;
        }

        /** Concentration spell costing {@code costPerSecond} while held. */
        public Builder conc(float costPerSecond) {
            this.type = Type.CONCENTRATION;
            this.cost = costPerSecond;
            return this;
        }

        public Builder mag(float magnitude) {
            this.magnitude = magnitude;
            return this;
        }

        public Builder seconds(float seconds) {
            this.duration = Math.round(seconds * 20);
            return this;
        }

        public Builder radius(float radius) {
            this.radius = radius;
            return this;
        }

        public Builder charge(int ticks) {
            this.chargeTicks = ticks;
            return this;
        }

        public Builder element(Element element) {
            this.element = element;
            return this;
        }

        public Builder hostile() {
            this.hostile = true;
            return this;
        }

        public Builder action(Action action) {
            this.action = action;
            return this;
        }

        /** Fires a {@link SpellProjectile}; {@code impact} runs where it lands. */
        public Builder projectile(float speed, Impact impact) {
            this.speed = speed;
            this.impact = impact;
            this.action = SpellEffects::launch;
            return this;
        }

        public Builder flight(Flight flight) {
            this.flight = flight;
            return this;
        }

        /** Utility spells (summons, bound weapons, light...) gain nothing from dual casting. */
        public Builder noDual() {
            this.dualCastable = false;
            return this;
        }

        public Builder piercing() {
            this.piercing = true;
            return this;
        }

        /** Literal name and description (spells made at a Spellmaking Altar). */
        public Builder custom(Component name, Component description) {
            this.customName = name;
            this.customDescription = description;
            return this;
        }

        public Spell build() {
            return new Spell(this);
        }
    }
}
