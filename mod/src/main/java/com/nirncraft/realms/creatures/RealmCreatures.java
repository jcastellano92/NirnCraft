package com.nirncraft.realms.creatures;

import com.nirncraft.creatures.Bestiary;
import com.nirncraft.creatures.entity.CreatureBolt.Kind;
import com.nirncraft.creatures.entity.SkyCreature;
import com.nirncraft.creatures.entity.SkyMonster;
import com.nirncraft.creatures.entity.anim.AnimSlot;
import com.nirncraft.creatures.spec.Attack;
import com.nirncraft.creatures.spec.Attacks;
import com.nirncraft.creatures.spec.CreatureSpec;
import com.nirncraft.creatures.spec.CreatureSpec.Move;
import com.nirncraft.creatures.spec.CreatureSpec.Temper;
import com.nirncraft.creatures.spec.HitEffect;
import com.nirncraft.magic.MagicDamage;
import com.nirncraft.magic.spell.Summons;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;
import java.util.function.Supplier;

import java.util.List;
import java.util.function.Consumer;

/**
 * The creatures of the realms beyond Tamriel, registered through the bestiary ({@link Bestiary}: registry ids = spec
 * ids, spawn eggs, attributes and spawn placements come with it):
 * <ul>
 *     <li>the Deadlands: clannfear, daedroth, spider daedra;</li>
 *     <li>the Shivering Isles: grummite (and magus), baliwog, gnarl (elder, ancient), elytra (hatchling, noble), flesh
 *     atronach;</li>
 *     <li>Apocrypha: seeker (also conjured by the magic module's {@code seeker} summon), lurker;</li>
 *     <li>the Soul Cairn: keeper, boneman (archer), mistman, wrathman, wandering soul (passive);</li>
 *     <li>Sovngarde: hero of Sovngarde (neutral; the {@code sovngarde_hero} summon of Call of Valor) and Tsun
 *     (neutral, persistent);</li>
 *     <li>Coldharbour: harvester; and Miraak, a boss.</li>
 * </ul>
 * Stats follow the bestiary's scale (a player has 20 health); hostiles are leveled by {@code Leveling} like the rest.
 * {@link #init()} only forces class loading: call it during mod construction, before the registry events.
 */
public final class RealmCreatures {
    private RealmCreatures() {}

    // ---------------------------------------------------------------- the Deadlands
    public static final Supplier<EntityType<SkyMonster>> CLANNFEAR = Bestiary.monster(clannfear());
    public static final Supplier<EntityType<SkyMonster>> DAEDROTH = Bestiary.monster(daedroth());
    public static final Supplier<EntityType<SkyMonster>> SPIDER_DAEDRA = Bestiary.monster(spiderDaedra());

    // ---------------------------------------------------------------- the Shivering Isles
    public static final Supplier<EntityType<SkyMonster>> GRUMMITE = Bestiary.monster(grummite());
    public static final Supplier<EntityType<SkyMonster>> BALIWOG = Bestiary.monster(baliwog());
    public static final Supplier<EntityType<SkyMonster>> GNARL = Bestiary.monster(gnarl());
    public static final Supplier<EntityType<SkyMonster>> ELYTRA = Bestiary.monster(elytra());
    public static final Supplier<EntityType<SkyMonster>> FLESH_ATRONACH = Bestiary.monster(fleshAtronach());

    // ---------------------------------------------------------------- Apocrypha
    public static final Supplier<EntityType<SkyMonster>> SEEKER = Bestiary.monster(seeker());
    public static final Supplier<EntityType<SkyMonster>> LURKER = Bestiary.monster(lurker());

    // ---------------------------------------------------------------- the Soul Cairn
    public static final Supplier<EntityType<SkyMonster>> KEEPER = Bestiary.monster(keeper());
    public static final Supplier<EntityType<SkyMonster>> BONEMAN = Bestiary.monster(boneman());
    public static final Supplier<EntityType<SkyMonster>> MISTMAN = Bestiary.monster(mistman());
    public static final Supplier<EntityType<SkyMonster>> WRATHMAN = Bestiary.monster(wrathman());
    public static final Supplier<EntityType<RealmSpirit>> WANDERING_SOUL = Bestiary.register(wanderingSoul(), RealmSpirit::new);

    // ---------------------------------------------------------------- Sovngarde
    public static final Supplier<EntityType<RealmSpirit>> SOVNGARDE_HERO = Bestiary.register(sovngardeHero(), RealmSpirit::new);
    public static final Supplier<EntityType<RealmSpirit>> TSUN = Bestiary.register(tsun(), RealmSpirit::new);

    // ---------------------------------------------------------------- Coldharbour, and the First Dragonborn
    public static final Supplier<EntityType<SkyMonster>> HARVESTER = Bestiary.monster(harvester());
    public static final Supplier<EntityType<SkyMonster>> MIRAAK = Bestiary.monster(miraak());

    /** Every registered realm creature id (namespace {@code nirncraft}). */
    public static final List<String> IDS = List.of("clannfear", "daedroth", "spider_daedra", "grummite", "baliwog", "gnarl",
            "elytra", "flesh_atronach", "seeker", "lurker", "keeper", "boneman", "mistman", "wrathman", "wandering_soul",
            "sovngarde_hero", "tsun", "harvester", "miraak");

    /** Loads the class (registering every creature with the bestiary's deferred registers). */
    public static void init() {}

    // ================================================================================================ helpers

    private static Consumer<Mob> particles(ParticleOptions p, int every, float spread) {
        return mob -> {
            if (mob.tickCount % every != 0) return;
            mob.level().addParticle(p, mob.getX() + (mob.getRandom().nextDouble() - 0.5) * mob.getBbWidth() * spread,
                    mob.getY() + mob.getRandom().nextDouble() * mob.getBbHeight(),
                    mob.getZ() + (mob.getRandom().nextDouble() - 0.5) * mob.getBbWidth() * spread, 0, 0.02, 0);
        };
    }

    /** A move only some variants use (the magus's spells, the archer's bow): same timing, range and cry as {@code inner}. */
    static Attack forVariants(Attack inner, int... variants) {
        return new VariantAttack(inner, variants);
    }

    private static final class VariantAttack extends Attack {
        private final Attack inner;
        private final int[] variants;

        VariantAttack(Attack inner, int[] variants) {
            this.inner = inner;
            this.variants = variants;
            this.anim = inner.anim;
            this.windup = inner.windup;
            this.duration = inner.duration;
            this.cooldown = inner.cooldown;
            this.minRange = inner.minRange;
            this.maxRange = inner.maxRange;
            this.weight = inner.weight;
            this.roots = inner.roots;
            this.needsSight = inner.needsSight;
            this.chance = inner.chance;
            this.cry = inner.cry;
        }

        @Override
        public boolean canUse(Mob mob, LivingEntity target, double gap) {
            if (!(mob instanceof SkyCreature sc)) return false;
            int v = sc.getVariant();
            for (int x : variants) {
                if (x == v) return inner.canUse(mob, target, gap);
            }
            return false;
        }

        @Override
        public void start(Mob mob, LivingEntity target) {
            inner.start(mob, target);
        }

        @Override
        public void tick(Mob mob, LivingEntity target, int t) {
            inner.tick(mob, target, t);
        }

        @Override
        protected void perform(Mob mob, LivingEntity target) {}
    }

    /**
     * Soul drain: a beam of torn soul-stuff from the caster to its target (spell damage, wards apply); the caster heals
     * by a share of the damage. Harvesters of Coldharbour.
     */
    static Attack soulDrain(float damage, float healShare) {
        Attack a = new Attack() {
            {
                cry = "cast";
            }

            @Override
            protected void perform(Mob mob, LivingEntity target) {
                if (!mob.hasLineOfSight(target)) return;
                Vec3 from = mob.getEyePosition();
                Vec3 to = target.getBoundingBox().getCenter();
                if (mob.level() instanceof ServerLevel sl) {
                    Vec3 d = to.subtract(from);
                    int n = Math.max(4, (int) (d.length() * 3));
                    for (int i = 0; i <= n; i++) {
                        Vec3 p = from.add(d.scale(i / (double) n));
                        sl.sendParticles(ParticleTypes.SOUL, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.01);
                    }
                    sl.sendParticles(ParticleTypes.SCULK_SOUL, to.x, to.y, to.z, 6, 0.3, 0.4, 0.3, 0.02);
                }
                if (com.nirncraft.platform.Effects.hurt(target, MagicDamage.source(mob.level(), MagicDamage.SPELL, null, mob), damage)) {
                    mob.heal(damage * healShare);
                }
                mob.playSound(com.nirncraft.audio.AudioSounds.spell("conjure"), 1.0f, 0.6f);
            }
        };
        a.anim(AnimSlot.CAST).range(3f, 18f).timing(10, 20, 60);
        return a;
    }

    /** Sovngarde's heroes take up arms against monsters and daedra nearby (never creepers, never anyone's summons). */
    private static boolean heroFoe(LivingEntity e) {
        return e instanceof Enemy && !(e instanceof Creeper) && !Summons.isCompanion(e) && e.isAlive();
    }

    // ================================================================================================ the Deadlands

    /** Clannfear: beaked, frilled daedric reptiles, Dagon's hunting beasts. Runts are smaller and weaker. */
    static CreatureSpec clannfear() {
        CreatureSpec s = new CreatureSpec("clannfear").size(0.9f, 1.6f).stats(40, 6, 0.32).armor(4).xp(18).egg(0x6A2414, 0xD8C49A)
                .attack(Attacks.melee(1.0f).timing(7, 14, 18).range(0, 1.6f))
                .attack(Attacks.pounce(1.3f, 1.3f, HitEffect.stagger(15)).anim(AnimSlot.ATTACK2).weight(0.6f))
                .attack(Attacks.melee(1.2f, HitEffect.stagger(10)).anim(AnimSlot.ATTACK3).timing(7, 14, 40).range(0, 1.6f))
                .attack(Attacks.roar(16, 60).anim(AnimSlot.SPECIAL).chance(0.15f))
                .variants(2, (level, pos, r) -> r.nextInt(4) == 0 ? 1 : 0, "", "runt")
                .voice("alit", 0.8f);
        s.variantPower = new float[]{1f, 0.6f};
        s.daedra = true;
        s.aggroRange = 20;
        s.chaseSpeed = 1.35;
        return s;
    }

    /** Daedroth: crocodile-headed brutes that bite, claw, breathe fire and spit it. */
    static CreatureSpec daedroth() {
        CreatureSpec s = new CreatureSpec("daedroth").size(1.2f, 2.5f).stats(110, 10, 0.26).armor(8).xp(50).egg(0x8A2A0E, 0xF0A040)
                .attack(Attacks.melee(1.1f, HitEffect.stagger(12)).timing(8, 16, 20).range(0, 2.4f))
                .attack(Attacks.melee(1.0f, HitEffect.knock(0.9, 0.25)).anim(AnimSlot.ATTACK2).timing(7, 13, 30).range(0, 2.4f))
                .attack(Attacks.breath(Kind.FIRE, 7f, 1.0f).range(1f, 6f).timing(10, 40, 160))
                .attack(Attacks.bolt(Kind.FIRE, 8f, 1.3f, 1, 0.05f).range(5f, 20f).timing(10, 20, 80))
                .attack(Attacks.roar(18, 60).chance(0.12f))
                .resist(0.25f, 1.25f, 1f, 0f)
                .tickClient(particles(ParticleTypes.SMOKE, 6, 0.6f))
                .voice("wamasu", 0.7f);
        s.daedra = true;
        s.kbResist = 0.7;
        s.stepHeight = 1.0f;
        return s;
    }

    /** Spider daedra: Mephala's weavers. Poisoned claws and fangs, shock from the hands, webs of venom. */
    static CreatureSpec spiderDaedra() {
        CreatureSpec s = new CreatureSpec("spider_daedra").size(1.6f, 2.1f).stats(80, 8, 0.27).armor(6).xp(45).egg(0x2A1438, 0xD8CCE0)
                .attack(Attacks.melee(1.0f, HitEffect.poison(80, 0)).timing(8, 15, 18).range(0, 2.2f))
                .attack(Attacks.melee(1.2f, HitEffect.poison(120, 1)).anim(AnimSlot.ATTACK2).timing(7, 14, 50).range(0, 2.0f))
                .attack(Attacks.bolt(Kind.SHOCK, 7f, 1.5f, 1, 0.03f).range(4f, 20f).timing(10, 20, 50))
                .attack(Attacks.bolt(Kind.POISON, 4f, 1.1f, 3, 0.15f).anim(AnimSlot.SPECIAL).range(3f, 12f).timing(9, 18, 120))
                .resist(1f, 1f, 0.25f, 0f)
                .voice("hagraven", 0.85f)
                .step(() -> SoundEvents.SPIDER_STEP);
        s.daedra = true;
        s.keepDistance = 5;
        s.kbResist = 0.5;
        s.stepHeight = 1.0f;
        return s;
    }

    // ================================================================================================ the Shivering Isles

    /** Grummites: club-swinging fish-folk of the Isles' swamps; a magus casts frost and shock instead. */
    static CreatureSpec grummite() {
        CreatureSpec s = new CreatureSpec("grummite").size(0.7f, 1.4f).stats(24, 4.5, 0.28).armor(2).xp(10).egg(0x3A4A30, 0xC8B880)
                .attack(forVariants(Attacks.melee(1.1f).timing(8, 15, 18).range(0, 1.6f), 0))
                .attack(Attacks.melee(0.9f).anim(AnimSlot.ATTACK2).timing(7, 13, 25).range(0, 1.5f))
                .attack(forVariants(Attacks.bolt(Kind.FROST, 5f, 1.3f, 1, 0.05f).range(3f, 18f).timing(10, 20, 45), 1))
                .attack(forVariants(Attacks.bolt(Kind.SHOCK, 5f, 1.5f, 1, 0.05f).range(3f, 18f).timing(10, 20, 60).weight(0.6f), 1))
                .attack(Attacks.roar(14, 40).anim(AnimSlot.SPECIAL).chance(0.12f))
                .variants(2, (level, pos, r) -> r.nextInt(4) == 0 ? 1 : 0, "", "magus")
                .resist(1.25f, 0.75f, 1f, 1f)
                .voice("riekling", 0.75f);
        s.variantPower = new float[]{1f, 1.2f};
        s.daedra = true;
        s.waterBreathing = true;
        return s;
    }

    /** Baliwogs: the grummites' larval brood, snapping amphibians of the Isles' bogs. */
    static CreatureSpec baliwog() {
        CreatureSpec s = new CreatureSpec("baliwog").size(0.9f, 0.6f).stats(14, 3.5, 0.3).xp(5).egg(0x384428, 0xD07A7C)
                .attack(Attacks.melee(1.0f, HitEffect.chance(0.3f, HitEffect.weakness(100))).timing(6, 13, 16).range(0, 1.2f))
                .attack(Attacks.melee(1.2f).anim(AnimSlot.ATTACK2).timing(7, 16, 40).range(0, 1.8f).moving())
                .voice("guar", 1.5f);
        s.daedra = true;
        s.waterBreathing = true;
        s.aggroRange = 12;
        return s;
    }

    /** Gnarls: walking trees of the Isles. Slow, regrowing (but not while burning), and they burn well. */
    static CreatureSpec gnarl() {
        CreatureSpec s = new CreatureSpec("gnarl").size(1.0f, 2.3f).stats(60, 7, 0.22).armor(6).xp(28).egg(0x4E3828, 0x6A9A32)
                .attack(Attacks.melee(1.0f, HitEffect.knock(0.8, 0.2)).timing(8, 14, 20).range(0, 2.2f))
                .attack(Attacks.melee(1.1f).anim(AnimSlot.ATTACK2).timing(7, 13, 30).range(0, 2.2f))
                .attack(Attacks.slam(1.2f, 2.5f, 0.4, HitEffect.slow(60, 1)).anim(AnimSlot.ATTACK3).timing(12, 22, 100).range(0, 2.5f))
                .attack(Attacks.roar(14, 60).anim(AnimSlot.SPECIAL).chance(0.1f))
                .variants(3, (level, pos, r) -> {
                    float f = r.nextFloat();
                    return f < 0.6f ? 0 : f < 0.9f ? 1 : 2;
                }, "", "elder", "ancient")
                .resist(2f, 0.75f, 1f, 0f)
                .voice("spriggan", 0.7f)
                .step(() -> SoundEvents.WOOD_STEP);
        s.variantPower = new float[]{1f, 1.6f, 2.4f};
        s.regen = 1.0f;
        s.fireStopsRegen = true;
        s.kbResist = 0.6;
        return s;
    }

    /** Elytra: the Isles' iridescent giant insects, scything and biting. Hatchlings and nobles by size. */
    static CreatureSpec elytra() {
        CreatureSpec s = new CreatureSpec("elytra").size(1.1f, 0.9f).stats(28, 5, 0.31).armor(6).xp(14).egg(0x1E6A5A, 0x7A3AA0)
                .attack(Attacks.melee(1.0f).timing(7, 14, 16).range(0, 1.6f))
                .attack(Attacks.melee(0.9f, HitEffect.poison(60, 0)).anim(AnimSlot.ATTACK2).timing(7, 14, 24).range(0, 1.4f))
                .attack(Attacks.roar(12, 40).anim(AnimSlot.SPECIAL).chance(0.1f))
                .variants(3, (level, pos, r) -> {
                    float f = r.nextFloat();
                    return f < 0.2f ? 1 : f > 0.88f ? 2 : 0;
                }, "", "hatchling", "noble")
                .resist(1f, 1f, 1f, 0.5f)
                .voice("chaurus", 1.3f)
                .step(() -> SoundEvents.SPIDER_STEP);
        s.variantPower = new float[]{1f, 0.5f, 1.8f};
        return s;
    }

    /** Flesh atronach: Sheogorath's stitched golem. Heavy fists, a ground pound, and it knits itself back together. */
    static CreatureSpec fleshAtronach() {
        CreatureSpec s = new CreatureSpec("flesh_atronach").size(1.3f, 2.4f).stats(100, 9, 0.22).armor(4).xp(40).egg(0xC49C90, 0xFF46C8)
                .attack(Attacks.melee(1.0f, HitEffect.knock(1.0, 0.3)).timing(8, 14, 20).range(0, 2.3f))
                .attack(Attacks.melee(1.1f, HitEffect.stagger(12)).anim(AnimSlot.ATTACK2).timing(7, 13, 35).range(0, 2.3f))
                .attack(Attacks.slam(1.3f, 3f, 0.5, HitEffect.stagger(20)).anim(AnimSlot.ATTACK3).timing(12, 22, 100).range(0, 2.6f))
                .attack(Attacks.roar(14, 60).anim(AnimSlot.SPECIAL).chance(0.1f))
                .resist(1f, 1f, 1.25f, 0f)
                .voice("ogre", 0.85f);
        s.daedra = true;
        s.regen = 1.0f;
        s.kbResist = 0.8;
        return s;
    }

    // ================================================================================================ Apocrypha

    /** Seekers: Hermaeus Mora's hovering servants. They lash, cast draining bolts and blink about. Also a conjured ally. */
    static CreatureSpec seeker() {
        CreatureSpec s = new CreatureSpec("seeker").size(0.8f, 2.0f).stats(40, 6, 0.25).armor(2).xp(30).egg(0x18241C, 0x78FF8C)
                .move(Move.HOVER)
                .attack(Attacks.melee(1.0f, HitEffect.weakness(60)).timing(8, 16, 20).range(0, 1.8f))
                .attack(Attacks.bolt(Kind.SHADOW, 6f, 1.4f, 1, 0.04f).range(3f, 20f).timing(10, 20, 40))
                .attack(Attacks.blink(true).anim(AnimSlot.SPECIAL).chance(0.3f))
                .attack(Attacks.blink(false).anim(AnimSlot.SPECIAL).chance(0.12f))
                .resist(1f, 1f, 1f, 0f)
                .tickClient(particles(ParticleTypes.SQUID_INK, 8, 0.6f))
                .voice("ice_wraith", 0.6f);
        s.keepDistance = 6;
        s.daedra = true;
        s.flySpeed = 0.35;
        return s;
    }

    /** Lurkers: Apocrypha's tentacled hulks. Claws, a staggering ground slam and spat ink. */
    static CreatureSpec lurker() {
        CreatureSpec s = new CreatureSpec("lurker").size(1.6f, 3.4f).stats(200, 14, 0.25).armor(8).xp(120).egg(0x122218, 0x6EFF78)
                .attack(Attacks.melee(1.0f, HitEffect.knock(1.0, 0.3)).timing(8, 14, 22).range(0, 3f))
                .attack(Attacks.melee(1.1f).anim(AnimSlot.ATTACK2).timing(7, 13, 30).range(0, 3f))
                .attack(Attacks.slam(1.4f, 4f, 0.6, HitEffect.stagger(30)).anim(AnimSlot.ATTACK3).timing(12, 22, 110).range(0, 3.5f))
                .attack(Attacks.bolt(Kind.POISON, 7f, 1.2f, 1, 0.08f).range(4f, 18f).timing(10, 20, 80))
                .attack(Attacks.roar(20, 80).anim(AnimSlot.SPECIAL).chance(0.15f))
                .resist(1f, 1f, 1f, 0f)
                .tickClient(particles(ParticleTypes.SQUID_INK, 10, 0.8f))
                .voice("swamp_leviathan", 1.0f);
        s.daedra = true;
        s.kbResist = 1.0;
        s.stepHeight = 1.5f;
        s.tracking(10);
        return s;
    }

    // ================================================================================================ the Soul Cairn

    /** Keepers: the Soul Cairn's armoured skeletal guardians with great war axes. A mini-boss. */
    static CreatureSpec keeper() {
        CreatureSpec s = new CreatureSpec("keeper").size(0.9f, 2.6f).stats(160, 12, 0.27).armor(14).xp(120).egg(0x241E30, 0xC078FF)
                .attack(Attacks.melee(1.4f, HitEffect.stagger(25), HitEffect.knock(1.0, 0.3)).timing(13, 24, 30).range(0, 3.2f))
                .attack(Attacks.sweep(1.0f, 3.5f, -0.2f, HitEffect.knock(0.8, 0.2)).anim(AnimSlot.ATTACK2).timing(10, 20, 60))
                .attack(Attacks.roar(18, 80).anim(AnimSlot.SPECIAL).chance(0.12f))
                .resist(1f, 0.5f, 1f, 0f)
                .tickClient(particles(ParticleTypes.SOUL, 10, 0.6f))
                .voice("draugr", 0.65f);
        s.undead = true;
        s.kbResist = 0.8;
        s.tracking(10);
        return s;
    }

    /** Bonemen: the Soul Cairn's skeleton soldiers, with sword and shield, or bow. */
    static CreatureSpec boneman() {
        CreatureSpec s = new CreatureSpec("boneman").size(0.6f, 1.95f).stats(36, 6, 0.27).armor(6).xp(18).egg(0xBEB4C6, 0x4A2E3E)
                .attack(forVariants(Attacks.melee(1.0f).timing(8, 15, 18).range(0, 1.9f), 0))
                .attack(Attacks.melee(0.7f, HitEffect.knock(0.6, 0.1)).anim(AnimSlot.ATTACK2).timing(6, 13, 40).range(0, 1.6f))
                .attack(forVariants(Attacks.bolt(Kind.BOLT, 6f, 1.8f, 1, 0.03f).range(3f, 22f).timing(12, 22, 40), 1))
                .variants(2, (level, pos, r) -> r.nextInt(3) == 0 ? 1 : 0, "", "archer")
                .resist(1f, 0.75f, 1f, 0f)
                .voice("draugr", 1.15f)
                .step(() -> SoundEvents.SKELETON_STEP);
        s.undead = true;
        return s;
    }

    /** Mistmen: wraiths of the Cairn's mists. Freezing claws, frost bolts, a chilling wail. */
    static CreatureSpec mistman() {
        CreatureSpec s = new CreatureSpec("mistman").size(0.7f, 1.9f).stats(45, 6, 0.25).xp(25).egg(0x869AB0, 0xDAF4FF)
                .move(Move.HOVER)
                .attack(Attacks.melee(1.0f, HitEffect.frost(60), HitEffect.chill(2f)).timing(8, 15, 18).range(0, 1.8f))
                .attack(Attacks.bolt(Kind.FROST, 6f, 1.3f, 1, 0.05f).range(4f, 18f).timing(10, 20, 50))
                .attack(Attacks.roar(14, 60).anim(AnimSlot.SPECIAL).chance(0.1f))
                .resist(1.25f, 0f, 1f, 0f)
                .tickClient(particles(ParticleTypes.SNOWFLAKE, 4, 0.8f))
                .voice("will_o_wisp", 0.6f);
        s.undead = true;
        s.flySpeed = 0.3;
        return s;
    }

    /** Wrathmen: towering spectral knights burning with wrath, wielding greatswords. */
    static CreatureSpec wrathman() {
        CreatureSpec s = new CreatureSpec("wrathman").size(0.9f, 2.6f).stats(140, 13, 0.28).armor(10).xp(90).egg(0x221C28, 0xFF326E)
                .attack(Attacks.melee(1.1f).timing(8, 16, 18).range(0, 2.6f))
                .attack(Attacks.sweep(1.0f, 3.2f, -0.2f, HitEffect.knock(0.8, 0.2)).anim(AnimSlot.ATTACK2).timing(10, 20, 60))
                .attack(Attacks.melee(1.6f, HitEffect.stagger(25)).anim(AnimSlot.ATTACK3).timing(13, 22, 80).range(0, 2.6f))
                .attack(Attacks.roar(18, 80).anim(AnimSlot.SPECIAL).chance(0.15f))
                .resist(1f, 0.5f, 1f, 0f)
                .tickClient(particles(ParticleTypes.SOUL, 8, 0.6f))
                .voice("draugr", 0.6f);
        s.undead = true;
        s.kbResist = 0.7;
        s.tracking(10);
        return s;
    }

    /** Wandering souls: the harmless lost dead drifting through the Soul Cairn. */
    static CreatureSpec wanderingSoul() {
        CreatureSpec s = new CreatureSpec("wandering_soul").size(0.6f, 1.6f).stats(10, 0, 0.18).xp(0).egg(0x8682BC, 0xDCDAF6)
                .move(Move.HOVER)
                .temper(Temper.PASSIVE)
                .tickClient(particles(ParticleTypes.SOUL, 12, 0.5f))
                .voice("wisp", 0.7f);
        s.category = MobCategory.AMBIENT;
        s.waterBreathing = true;
        s.flySpeed = 0.2;
        s.ambientInterval = 200;
        s.soundVolume = 0.6f;
        return s;
    }

    // ================================================================================================ Sovngarde

    /**
     * Heroes of Sovngarde: the honoured Nord dead. Neutral to the living, they turn on monsters and daedra nearby.
     * Warrior (axe), shieldmaiden (sword and shield) or archer (bow).
     */
    static CreatureSpec sovngardeHero() {
        CreatureSpec s = new CreatureSpec("sovngarde_hero").size(0.6f, 1.95f).stats(60, 7, 0.3).armor(8).xp(0).egg(0x98B0CC, 0xDCC070)
                .temper(Temper.NEUTRAL)
                .attack(forVariants(Attacks.melee(1.0f).timing(8, 15, 16).range(0, 2.0f), 0, 1))
                .attack(Attacks.melee(0.8f, HitEffect.knock(0.9, 0.2), HitEffect.stagger(10)).anim(AnimSlot.ATTACK2).timing(6, 13, 40).range(0, 1.8f))
                .attack(forVariants(Attacks.bolt(Kind.BOLT, 7f, 1.9f, 1, 0.02f).range(3f, 22f).timing(12, 22, 35), 2))
                .variants(3, (level, pos, r) -> r.nextInt(3), "", "shieldmaiden", "archer")
                .prey(RealmCreatures::heroFoe)
                .tickClient(particles(ParticleTypes.END_ROD, 14, 0.6f))
                .sounds(null, () -> SoundEvents.PLAYER_HURT, () -> SoundEvents.PLAYER_DEATH, 0.9f);
        s.waterBreathing = true;
        s.regen = 0.5f;
        s.aggroRange = 16;
        return s;
    }

    /** Tsun, Shield-Thane of Shor, keeper of the Whalebone Bridge. Neutral, persistent, mighty. */
    static CreatureSpec tsun() {
        CreatureSpec s = new CreatureSpec("tsun").size(1.1f, 3.2f).stats(500, 16, 0.27).armor(18).xp(0).egg(0xC8A048, 0x8A94A0)
                .temper(Temper.NEUTRAL)
                .attack(Attacks.melee(1.2f, HitEffect.stagger(25), HitEffect.knock(1.2, 0.4)).timing(13, 24, 28).range(0, 3.2f))
                .attack(Attacks.melee(1.0f, HitEffect.knock(1.4, 0.3)).anim(AnimSlot.ATTACK2).timing(11, 22, 50).range(0, 3.2f))
                .sounds(null, () -> SoundEvents.PLAYER_HURT, () -> SoundEvents.PLAYER_DEATH, 0.6f);
        s.persistent = true;
        s.kbResist = 1.0;
        s.regen = 2.0f;
        s.stepHeight = 1.5f;
        s.waterBreathing = true;
        s.soundVolume = 1.5f;
        s.tracking(12);
        return s;
    }

    // ================================================================================================ Coldharbour and Miraak

    /** Harvesters: Molag Bal's serpent-bodied soul reapers. Four-armed rakes and a soul-draining beam. */
    static CreatureSpec harvester() {
        CreatureSpec s = new CreatureSpec("harvester").size(1.0f, 2.4f).stats(90, 9, 0.27).armor(6).xp(60).egg(0x22283E, 0xFF3228)
                .attack(Attacks.melee(1.1f, HitEffect.drain(0.4f)).timing(8, 16, 18).range(0, 2.2f))
                .attack(soulDrain(7f, 0.6f))
                .attack(Attacks.bolt(Kind.SHADOW, 6f, 1.4f, 1, 0.04f).range(5f, 20f).timing(10, 20, 70).weight(0.6f))
                .attack(Attacks.roar(16, 60).anim(AnimSlot.SPECIAL).chance(0.15f))
                .resist(1f, 1f, 1f, 0f)
                .voice("tsaesci", 1.15f);
        s.daedra = true;
        s.kbResist = 0.6;
        s.keepDistance = 4;
        return s;
    }

    /** Miraak, the First Dragonborn: blade, spells, the Thu'um, blinks, and seekers called to his side. A boss. */
    static CreatureSpec miraak() {
        CreatureSpec s = new CreatureSpec("miraak").size(0.6f, 2.0f).stats(300, 12, 0.28).armor(12).xp(500).egg(0x2A301A, 0xE2C462)
                .attack(Attacks.melee(1.0f).timing(8, 15, 16).range(0, 2.2f))
                .attack(Attacks.melee(1.2f, HitEffect.stagger(15)).anim(AnimSlot.ATTACK2).timing(8, 14, 40).range(0, 2.4f))
                .attack(Attacks.bolt(Kind.SHADOW, 9f, 1.6f, 1, 0.03f).range(4f, 24f).timing(10, 20, 40))
                .attack(Attacks.bolt(Kind.SHOCK, 8f, 2.0f, 1, 0.02f).range(4f, 24f).timing(10, 20, 60).weight(0.7f))
                .attack(Attacks.bolt(Kind.FIRE, 7f, 1.6f, 3, 0.12f).range(4f, 20f).timing(10, 20, 80).weight(0.6f))
                .attack(Attacks.summon(() -> SEEKER.get(), 2, 3))
                .attack(Attacks.blink(true).anim(AnimSlot.SPECIAL).chance(0.2f))
                .attack(Attacks.shout(7f, 2.6, 14f))
                .resist(1f, 1f, 1f, 0.5f)
                .voice("dragon_priest", 1.2f);
        s.boss = true;
        s.persistent = true;
        s.kbResist = 0.8;
        s.tracking(12);
        return s;
    }
}
