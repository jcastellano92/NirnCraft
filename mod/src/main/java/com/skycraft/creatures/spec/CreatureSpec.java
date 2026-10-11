package com.skycraft.creatures.spec;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ServerLevelAccessor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Data of one bestiary creature: stats, temper, movement, attacks, resistances, ranks, variants and sounds. The
 * generic creature classes ({@link com.skycraft.creatures.entity.SkyMonster}, {@link com.skycraft.creatures.entity.SkyBeast})
 * read everything from here, so most creatures need no class of their own.
 */
public final class CreatureSpec {
    /** How the creature treats players. */
    public enum Temper {
        /** Attacks players on sight (within {@link #aggroRange}). */
        HOSTILE,
        /** Attacks players who come close ({@link #aggroRange}, usually small) and anyone who hurts it. */
        TERRITORIAL,
        /** Only fights back when hurt. */
        NEUTRAL,
        /** Flees from players and predators. */
        PASSIVE
    }

    public enum Move { WALK, FLY, HOVER, SWIM }

    @FunctionalInterface
    public interface VariantPicker {
        int pick(ServerLevelAccessor level, BlockPos pos, RandomSource random);
    }

    public final String id;
    public Temper temper = Temper.HOSTILE;
    public Move move = Move.WALK;
    public MobCategory category = MobCategory.MONSTER;
    public float width = 0.9f;
    public float height = 1.0f;
    public int trackingRange = 8;

    public double health = 20;
    public double damage = 3;
    public double speed = 0.25;
    public double flySpeed = 0.5;
    public double armor = 0;
    public double toughness = 0;
    public double follow = 24;
    public double kbResist = 0;
    public double attackKnockback = 0;
    public int xp = 5;
    public float stepHeight = 0.6f;

    /** Chase speed multiplier while attacking. */
    public double chaseSpeed = 1.25;
    /** Ranged creatures keep this distance (0 = close in). */
    public float keepDistance = 0;
    public float aggroRange = 16;
    /** Health fraction below which the creature flees (0 = never). */
    public float fleeBelow = 0;
    public final List<Attack> attacks = new ArrayList<>();
    /** Creatures it hunts (besides players). */
    @Nullable
    public Predicate<LivingEntity> prey;

    public boolean fireImmune;
    public boolean undead;
    public boolean daedra;
    public boolean waterBreathing;
    /** Damage taken multipliers by element (0 = immune). */
    public float fireMult = 1;
    public float frostMult = 1;
    public float shockMult = 1;
    public float poisonMult = 1;
    /** Health regained per second (trolls); stopped for 5 s by fire when {@link #fireStopsRegen}. */
    public float regen = 0;
    public boolean fireStopsRegen;

    /** Rank name keys {@code entity.skycraft.<id>.<rank>} with the level each starts at (ascending). */
    public String[] ranks = new String[0];
    public int[] rankLevels = new int[0];
    public int variants = 1;
    @Nullable
    public VariantPicker variantPicker;
    /** Display name per variant ({@code entity.skycraft.<id>.<variantName>}), null entries keep the type name. */
    public String[] variantNames = new String[0];

    @Nullable
    public Supplier<SoundEvent> ambientSound;
    @Nullable
    public Supplier<SoundEvent> hurtSound;
    @Nullable
    public Supplier<SoundEvent> deathSound;
    @Nullable
    public Supplier<SoundEvent> stepSound;
    public float voicePitch = 1f;
    public float soundVolume = 1f;
    public int ambientInterval = 120;
    /** Cries played when a move starts, by the move's {@link Attack#cry} key ({@code attack}, {@code roar}, {@code cast}, {@code breath}). */
    public final java.util.Map<String, Supplier<SoundEvent>> cries = new java.util.HashMap<>();

    /** Server tick hook (every tick; keep it cheap), e.g. auras. */
    @Nullable
    public Consumer<Mob> serverTick;
    /** Called on the server when the creature dies (death bursts and the like). */
    @Nullable
    public Consumer<Mob> onDeath;
    /** Client tick hook, e.g. particles. */
    @Nullable
    public Consumer<Mob> clientTick;

    public int eggBg = 0x444444;
    public int eggFg = 0xAAAAAA;
    /** Lies dormant (a statue, a sleeper) until a player comes within this range; 0 = never dormant. */
    public float ambush = 0;
    /** Chance a natural spawn starts dormant when {@link #ambush} is set. */
    public float dormantChance = 1f;
    /** Plays its emerge animation when it appears (ash spawn rising from the ash). */
    public boolean emergeOnSpawn;
    /** Health and damage multipliers per variant (null = all equal). */
    public float[] variantPower;
    /** Tinted per region (endless lands' beasts): the variant number holds an RGB colour. */
    public boolean tinted;
    /** Never despawns (bosses, travel creatures). */
    public boolean persistent;
    public boolean boss;

    public CreatureSpec(String id) {
        this.id = id;
    }

    // ------------------------------------------------------------------ fluent setters

    public CreatureSpec size(float width, float height) {
        this.width = width;
        this.height = height;
        return this;
    }

    public CreatureSpec stats(double health, double damage, double speed) {
        this.health = health;
        this.damage = damage;
        this.speed = speed;
        return this;
    }

    public CreatureSpec armor(double armor) {
        this.armor = armor;
        return this;
    }

    public CreatureSpec temper(Temper temper) {
        this.temper = temper;
        if (temper == Temper.PASSIVE || temper == Temper.NEUTRAL) {
            if (category == MobCategory.MONSTER) category = MobCategory.CREATURE;
        }
        return this;
    }

    public CreatureSpec move(Move move) {
        this.move = move;
        if (move == Move.SWIM) category = MobCategory.WATER_CREATURE;
        return this;
    }

    public CreatureSpec category(MobCategory category) {
        this.category = category;
        return this;
    }

    public CreatureSpec xp(int xp) {
        this.xp = xp;
        return this;
    }

    public CreatureSpec attack(Attack attack) {
        attacks.add(attack);
        return this;
    }

    public CreatureSpec prey(Predicate<LivingEntity> prey) {
        this.prey = prey;
        return this;
    }

    public CreatureSpec ranks(Object... levelThenKey) {
        int n = levelThenKey.length / 2;
        ranks = new String[n];
        rankLevels = new int[n];
        for (int i = 0; i < n; i++) {
            rankLevels[i] = (Integer) levelThenKey[i * 2];
            ranks[i] = (String) levelThenKey[i * 2 + 1];
        }
        return this;
    }

    public CreatureSpec variants(int count, VariantPicker picker, String... names) {
        this.variants = count;
        this.variantPicker = picker;
        this.variantNames = names;
        return this;
    }

    public CreatureSpec sounds(@Nullable Supplier<SoundEvent> ambient, @Nullable Supplier<SoundEvent> hurt,
                               @Nullable Supplier<SoundEvent> death, float pitch) {
        this.ambientSound = ambient;
        this.hurtSound = hurt;
        this.deathSound = death;
        this.voicePitch = pitch;
        return this;
    }

    /**
     * Skycraft's own voice from the audio module: ambient, hurt and death from {@code entity.<creature>.<kind>}, plus every
     * registered cry ({@code attack}, {@code roar}, {@code cast}, {@code breath}) and footstep ({@code step}).
     */
    public CreatureSpec voice(String creature, float pitch) {
        this.ambientSound = () -> com.skycraft.audio.AudioSounds.creature(creature, "ambient");
        this.hurtSound = () -> com.skycraft.audio.AudioSounds.creature(creature, "hurt");
        this.deathSound = () -> com.skycraft.audio.AudioSounds.creature(creature, "death");
        this.voicePitch = pitch;
        for (String kind : new String[]{"attack", "roar", "cast", "breath"}) {
            if (com.skycraft.audio.AudioSounds.has("entity." + creature + "." + kind)) {
                cries.put(kind, () -> com.skycraft.audio.AudioSounds.creature(creature, kind));
            }
        }
        if (com.skycraft.audio.AudioSounds.has("entity." + creature + ".step")) {
            this.stepSound = () -> com.skycraft.audio.AudioSounds.creature(creature, "step");
        }
        return this;
    }

    /** Adds (or replaces) the cry played when a move with this {@link Attack#cry} key starts. */
    public CreatureSpec cry(String kind, Supplier<SoundEvent> sound) {
        cries.put(kind, sound);
        return this;
    }

    /** The cry for a move key, or null. */
    @Nullable
    public SoundEvent cry(@Nullable String kind) {
        Supplier<SoundEvent> s = kind == null ? null : cries.get(kind);
        return s == null ? null : s.get();
    }

    public CreatureSpec step(Supplier<SoundEvent> step) {
        this.stepSound = step;
        return this;
    }

    public CreatureSpec egg(int bg, int fg) {
        this.eggBg = bg;
        this.eggFg = fg;
        return this;
    }

    public CreatureSpec resist(float fire, float frost, float shock, float poison) {
        this.fireMult = fire;
        this.frostMult = frost;
        this.shockMult = shock;
        this.poisonMult = poison;
        if (fire == 0) fireImmune = true;
        return this;
    }

    public CreatureSpec tickServer(Consumer<Mob> tick) {
        this.serverTick = tick;
        return this;
    }

    public CreatureSpec tickClient(Consumer<Mob> tick) {
        this.clientTick = tick;
        return this;
    }

    public CreatureSpec tracking(int chunks) {
        this.trackingRange = chunks;
        return this;
    }

    /** Vanilla attribute set for this creature. */
    public AttributeSupplier.Builder attributes() {
        AttributeSupplier.Builder b = net.minecraft.world.entity.monster.Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, health)
                .add(Attributes.ATTACK_DAMAGE, damage)
                .add(Attributes.MOVEMENT_SPEED, speed)
                .add(Attributes.FOLLOW_RANGE, follow)
                .add(Attributes.ARMOR, armor)
                .add(Attributes.ARMOR_TOUGHNESS, toughness)
                .add(Attributes.KNOCKBACK_RESISTANCE, kbResist)
                .add(Attributes.ATTACK_KNOCKBACK, attackKnockback);
        if (move == Move.FLY || move == Move.HOVER) b.add(Attributes.FLYING_SPEED, flySpeed);
        return b;
    }

    /** The rank key for a level, or null. */
    @Nullable
    public String rankFor(int level) {
        String best = null;
        for (int i = 0; i < ranks.length; i++) {
            if (level >= rankLevels[i]) best = ranks[i];
        }
        return best;
    }
}
