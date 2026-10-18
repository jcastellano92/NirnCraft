package com.skycraft.creatures;

import com.skycraft.creatures.entity.CreatureBolt.Kind;
import com.skycraft.creatures.entity.anim.AnimSlot;
import com.skycraft.creatures.spec.Attacks;
import com.skycraft.creatures.spec.CreatureSpec;
import com.skycraft.creatures.spec.CreatureSpec.Move;
import com.skycraft.creatures.spec.CreatureSpec.Temper;
import com.skycraft.creatures.spec.HitEffect;
import com.skycraft.magic.MagicDamage;
import com.skycraft.tamriel.Regions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ServerLevelAccessor;

/**
 * The bestiary's creature data: stats, temper, moves, resistances, ranks and variants, following Skyrim and the
 * wider Elder Scrolls lore. Damage is in Minecraft units (a player has 20 health); Leveling then scales creatures by
 * their level.
 */
final class BestiarySpecs {
    private BestiarySpecs() {}

    // ================================================================================================ helpers

    static boolean cold(ServerLevelAccessor level, BlockPos pos) {
        return level.getBiome(pos).value().coldEnoughToSnow(pos, level.getSeaLevel()) || level.getBiome(pos).value().getBaseTemperature() < 0.15f;
    }

    private static String id(LivingEntity e) {
        return EntityType.getKey(e.getType()).getPath();
    }

    /** Game the predators hunt. */
    static boolean smallGame(LivingEntity e) {
        String id = id(e);
        return e instanceof Rabbit || e instanceof Sheep || e instanceof Goat || id.equals("deer") || id.equals("goat")
                || id.equals("guar") || id.equals("realm_grazer");
    }

    /** Big game for the great cats and leviathans. */
    static boolean bigGame(LivingEntity e) {
        String id = id(e);
        return smallGame(e) || id.equals("elk") || id.equals("horker") || id.equals("echatere") || id.equals("kagouti");
    }

    /** An element aura: hurts and afflicts everything hostile within {@code radius} every second. */
    static java.util.function.Consumer<Mob> aura(net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> type,
                                                 float radius, float damage) {
        return mob -> {
            if (mob.tickCount % 20 != 0 || mob.getTarget() == null) return;
            for (LivingEntity e : mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(radius),
                    e -> Attacks.validVictim(mob, e) && (e == mob.getTarget() || e instanceof Player))) {
                com.skycraft.platform.Effects.hurt(e, MagicDamage.source(mob.level(), type, null, mob), damage);
                if (type == MagicDamage.FROST) e.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.SLOWNESS), 40, 0), mob);
            }
        };
    }

    static java.util.function.Consumer<Mob> particles(net.minecraft.core.particles.ParticleOptions p, int every, float spread) {
        return mob -> {
            if (mob.tickCount % every != 0) return;
            mob.level().addParticle(p, mob.getX() + (mob.getRandom().nextDouble() - 0.5) * mob.getBbWidth() * spread,
                    mob.getY() + mob.getRandom().nextDouble() * mob.getBbHeight(),
                    mob.getZ() + (mob.getRandom().nextDouble() - 0.5) * mob.getBbWidth() * spread, 0, 0.02, 0);
        };
    }

    /** A pleasant, region-stable colour for the endless lands' beasts. */
    static int regionTint(ServerLevelAccessor level, BlockPos pos, net.minecraft.util.RandomSource random) {
        String region = level instanceof ServerLevel sl ? Regions.at(sl, pos).id() : "realm";
        int h = region.hashCode() ^ 0x5bd1e995;
        float hue = ((h >>> 8) & 1023) / 1024f;
        float sat = 0.35f + ((h >>> 18) & 255) / 255f * 0.4f;
        float val = 0.75f + random.nextFloat() * 0.2f;
        return Mth.hsvToRgb(hue, sat, val);
    }

    // ================================================================================================ Skyrim

    /** Wolves hunt in packs; ice wolves roam the snow. Their howl calls the pack. */
    static CreatureSpec wolf() {
        CreatureSpec s = new CreatureSpec("wolf").size(0.8f, 1.1f).stats(16, 3.5, 0.32).xp(6).egg(0x4A4038, 0xB4A68E)
                .attack(Attacks.melee(1.0f).timing(6, 13, 18).range(0, 1.4f))
                .attack(Attacks.pounce(1.3f, 1.2f, HitEffect.stagger(10)).anim(AnimSlot.ATTACK2).weight(0.6f))
                .attack(Attacks.roar(20, 60).anim(AnimSlot.SPECIAL).chance(0.15f))
                .prey(BestiarySpecs::smallGame)
                .variants(2, (level, pos, r) -> cold(level, pos) ? 1 : 0, "", "ice")
                .voice("wolf", 1.0f)
                .step(() -> SoundEvents.WOLF_STEP.value());
        s.category = MobCategory.MONSTER;
        s.aggroRange = 22;
        s.fleeBelow = 0.2f;
        s.variantPower = new float[]{1f, 1.5f};
        s.frostMult = 1f;
        return s;
    }

    /** Falmer-farmed giant insects; they spit poison and bite with venomous mandibles. */
    static CreatureSpec chaurus() {
        CreatureSpec s = new CreatureSpec("chaurus").size(1.4f, 1.0f).stats(30, 5, 0.27).armor(6).xp(14).egg(0x3A3A20, 0xB08A3A)
                .attack(Attacks.melee(1.0f, HitEffect.poison(80, 0)).timing(8, 16, 20))
                .attack(Attacks.bolt(Kind.POISON, 4f, 1.1f, 1, 0.1f).anim(AnimSlot.SPECIAL).range(3f, 14f).timing(10, 22, 80))
                .ranks(1, "chaurus", 18, "reaper")
                .voice("chaurus", 1.0f)
                .step(() -> SoundEvents.SPIDER_STEP);
        s.poisonMult = 0;
        return s;
    }

    /** Winged chaurus, hunting from the air. */
    static CreatureSpec chaurusHunter() {
        CreatureSpec s = new CreatureSpec("chaurus_hunter").size(1.0f, 1.6f).stats(28, 5, 0.25).armor(4).xp(18).egg(0x3A3A20, 0x8AB060)
                .move(Move.FLY)
                .attack(Attacks.melee(1.0f, HitEffect.poison(80, 0)).timing(8, 16, 20))
                .attack(Attacks.melee(1.3f).anim(AnimSlot.ATTACK2).timing(10, 18, 40))
                .attack(Attacks.bolt(Kind.POISON, 4f, 1.2f, 2, 0.15f).anim(AnimSlot.SPECIAL).range(3f, 16f).timing(10, 22, 90))
                .voice("chaurus", 0.85f);
        s.category = MobCategory.MONSTER;
        s.ambientSound = () -> com.skycraft.audio.AudioSounds.creature("chaurus", "wings");
        s.poisonMult = 0;
        s.flySpeed = 0.5;
        return s;
    }

    /** Solstheim's goblin-folk: spear thrusts, thrown spears, war cries. */
    static CreatureSpec riekling() {
        return new CreatureSpec("riekling").size(0.6f, 1.2f).stats(14, 3.5, 0.3).armor(2).xp(6).egg(0x5A7A8A, 0xC8B08A)
                .attack(Attacks.melee(1.0f).timing(7, 14, 16).range(0, 1.8f))
                .attack(Attacks.bolt(Kind.BOLT, 5f, 1.6f, 1, 0.05f).range(4f, 16f).timing(12, 22, 100))
                .attack(Attacks.roar(14, 60).anim(AnimSlot.SPECIAL).chance(0.2f))
                .ranks(1, "riekling", 8, "hunter", 14, "scout", 22, "chief")
                .resist(1f, 0.5f, 1f, 1f)
                .voice("riekling", 1.0f);
    }

    /** Ash spawn rise out of Solstheim's ash and throw searing cinders. */
    static CreatureSpec ashSpawn() {
        CreatureSpec s = new CreatureSpec("ash_spawn").size(0.6f, 1.95f).stats(30, 5, 0.26).armor(4).xp(14).egg(0x3A3634, 0xE0702A)
                .attack(Attacks.melee(1.0f, HitEffect.burn(3)).timing(8, 16, 20))
                .attack(Attacks.melee(1.2f).anim(AnimSlot.ATTACK2).timing(10, 18, 40))
                .attack(Attacks.bolt(Kind.ASH, 4f, 1.0f, 1, 0.1f).range(4f, 16f).timing(12, 24, 70))
                .resist(0.5f, 1.5f, 1f, 0f)
                .tickClient(particles(ParticleTypes.SMOKE, 3, 0.8f))
                .voice("ash_spawn", 1.0f);
        s.emergeOnSpawn = true;
        s.undead = true;
        return s;
    }

    /** Vampire lords' stone guardians: statues until someone walks close. */
    static CreatureSpec gargoyle() {
        CreatureSpec s = new CreatureSpec("gargoyle").size(1.0f, 2.2f).stats(60, 7, 0.27).armor(10).xp(24).egg(0x6A6A68, 0xA02020)
                .attack(Attacks.melee(1.0f).timing(8, 16, 20).range(0, 2f))
                .attack(Attacks.melee(1.3f, HitEffect.stagger(15)).anim(AnimSlot.ATTACK2).timing(10, 20, 50).range(0, 2f))
                .attack(Attacks.roar(16, 80).anim(AnimSlot.SPECIAL).chance(0.15f))
                .resist(1f, 1f, 1.25f, 0f)
                .ranks(1, "gargoyle", 20, "brute", 32, "sentinel")
                .voice("gargoyle", 1.0f);
        s.ambush = 7;
        s.dormantChance = 0.6f;
        s.kbResist = 0.6;
        return s;
    }

    /** Small will-o'-the-wisps that guard their wispmother. */
    static CreatureSpec wisp() {
        CreatureSpec s = new CreatureSpec("wisp").size(0.5f, 0.5f).stats(10, 3, 0.3).xp(4).egg(0x9AD0FF, 0xFFFFFF)
                .move(Move.HOVER)
                .attack(Attacks.melee(1.0f, HitEffect.chill(2f)).timing(5, 10, 18).range(0, 1.2f))
                .resist(1f, 0f, 1f, 0f)
                .tickClient(particles(ParticleTypes.SOUL_FIRE_FLAME, 4, 0.6f))
                .voice("wisp", 1.0f);
        s.flySpeed = 0.45;
        return s;
    }

    /** Cyrodiil's will-o'-the-wisp: drifts, blinks away, and strikes with sparks. */
    static CreatureSpec willOWisp() {
        CreatureSpec s = new CreatureSpec("will_o_wisp").size(0.6f, 1.2f).stats(25, 4, 0.28).xp(12).egg(0x88CCAA, 0xE0FFF0)
                .move(Move.HOVER)
                .attack(Attacks.melee(1.0f, HitEffect.shock(2f)).timing(6, 12, 20))
                .attack(Attacks.bolt(Kind.SHOCK, 5f, 1.3f, 1, 0.05f).range(4f, 18f).timing(10, 20, 60))
                .attack(Attacks.blink(true).chance(0.3f))
                .resist(1f, 0.5f, 0f, 0f)
                .tickClient(particles(ParticleTypes.END_ROD, 5, 0.8f))
                .voice("will_o_wisp", 1.0f);
        s.keepDistance = 6;
        s.flySpeed = 0.4;
        return s;
    }

    /** Vampires' death hounds: frost-biting, gaunt, red-eyed. */
    static CreatureSpec deathHound() {
        CreatureSpec s = new CreatureSpec("death_hound").size(0.9f, 1.1f).stats(24, 4.5, 0.34).xp(10).egg(0x1A1818, 0xC02020)
                .attack(Attacks.melee(1.0f, HitEffect.chill(1.5f)).timing(6, 13, 18).range(0, 1.4f))
                .attack(Attacks.pounce(1.3f, 1.3f, HitEffect.chill(2f)).anim(AnimSlot.ATTACK2).weight(0.5f))
                .attack(Attacks.roar(20, 60).anim(AnimSlot.SPECIAL).chance(0.15f))
                .voice("death_hound", 1.0f);
        s.undead = true;
        s.frostMult = 0.5f;
        s.aggroRange = 20;
        return s;
    }

    /** Dwemer siege automaton: keeps its distance and shoots heavy bolts. */
    static CreatureSpec dwarvenBallista() {
        CreatureSpec s = new CreatureSpec("dwarven_ballista").size(1.2f, 1.4f).stats(50, 6, 0.2).armor(12).xp(26).egg(0xB08A3A, 0x60E0FF)
                .attack(Attacks.bolt(Kind.BOLT, 8f, 2.2f, 1, 0.02f).range(3f, 24f).timing(14, 26, 40))
                .attack(Attacks.melee(1.0f, HitEffect.knock(0.8, 0.2)).timing(8, 16, 30))
                .resist(1f, 0.75f, 1.25f, 0f)
                .sounds(() -> com.skycraft.audio.AudioSounds.creature("dwarven", "clank"), () -> com.skycraft.audio.AudioSounds.creature("dwarven", "hurt"),
                        () -> com.skycraft.audio.AudioSounds.creature("dwarven", "death"), 1.0f)
                .cry("cast", () -> com.skycraft.audio.AudioSounds.creature("dwarven_ballista", "cast"))
                .step(() -> SoundEvents.IRON_GOLEM_STEP);
        s.keepDistance = 10;
        s.kbResist = 0.7;
        s.waterBreathing = true;
        return s;
    }

    // ================================================================================================ daedra

    /** Dremora: warrior daedra of Mehrunes Dagon's legions; also conjured as the Dremora Lord. */
    static CreatureSpec dremora() {
        CreatureSpec s = new CreatureSpec("dremora").size(0.6f, 2.1f).stats(50, 7, 0.28).armor(10).xp(30).egg(0x1A1010, 0xC02010)
                .attack(Attacks.melee(1.0f).timing(9, 18, 18).range(0, 2.2f))
                .attack(Attacks.melee(1.1f).anim(AnimSlot.ATTACK2).timing(8, 16, 30).range(0, 2.4f))
                .attack(Attacks.melee(1.6f, HitEffect.stagger(20)).anim(AnimSlot.ATTACK3).timing(14, 26, 80).range(0, 2.2f))
                .attack(Attacks.bolt(Kind.FIRE, 6f, 1.4f, 1, 0.05f).range(5f, 20f).timing(12, 22, 90))
                .attack(Attacks.roar(16, 60).anim(AnimSlot.SPECIAL).chance(0.15f))
                .resist(0.5f, 1f, 1f, 1f)
                .ranks(1, "churl", 8, "caitiff", 15, "kynval", 22, "kynreeve", 30, "kynmarcher", 40, "markynaz", 50, "valkynaz")
                .voice("dremora", 1.0f);
        s.daedra = true;
        return s;
    }

    /** Flame atronach: hurls fire, bursts into flame when slain. */
    static CreatureSpec flameAtronach() {
        CreatureSpec s = new CreatureSpec("flame_atronach").size(0.7f, 1.95f).stats(30, 5, 0.28).xp(20).egg(0x501008, 0xFFA020)
                .move(Move.HOVER)
                .attack(Attacks.bolt(Kind.FIRE, 6f, 1.5f, 1, 0.03f).range(3f, 20f).timing(10, 20, 30))
                .attack(Attacks.melee(1.0f, HitEffect.burn(4)).timing(7, 14, 20))
                .resist(0f, 1.5f, 1f, 0f)
                .tickClient(particles(ParticleTypes.FLAME, 1, 0.7f))
                .voice("flame_atronach", 1.0f);
        s.keepDistance = 8;
        s.daedra = true;
        s.flySpeed = 0.35;
        // a slain flame atronach bursts into flame
        s.onDeath = mob -> {
            mob.level().explode(mob, mob.getX(), mob.getY() + 1, mob.getZ(), 1.5f, true, net.minecraft.world.level.Level.ExplosionInteraction.NONE);
            for (LivingEntity e : mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(3), e -> e != mob)) {
                e.igniteForSeconds(4);
            }
        };
        return s;
    }

    /** Frost atronach: a walking glacier with a numbing aura. */
    static CreatureSpec frostAtronach() {
        CreatureSpec s = new CreatureSpec("frost_atronach").size(1.6f, 2.6f).stats(90, 9, 0.24).armor(8).xp(30).egg(0x5A8AB0, 0xE0F4FF)
                .attack(Attacks.melee(1.0f, HitEffect.chill(2f)).timing(10, 20, 20).range(0, 2.4f))
                .attack(Attacks.melee(1.2f, HitEffect.knock(1.0, 0.3)).anim(AnimSlot.ATTACK2).timing(10, 20, 40).range(0, 2.4f))
                .attack(Attacks.slam(1.4f, 3f, 0.5, HitEffect.chill(3f)).anim(AnimSlot.ATTACK3).timing(16, 30, 120).range(0, 3f))
                .resist(1.5f, 0f, 1f, 0f)
                .tickServer(aura(MagicDamage.FROST, 2.5f, 1f))
                .tickClient(particles(ParticleTypes.SNOWFLAKE, 2, 1f))
                .voice("frost_atronach", 1.0f);
        s.kbResist = 1.0;
        s.daedra = true;
        return s;
    }

    /** Storm atronach: a whirl of stones around a lightning heart. */
    static CreatureSpec stormAtronach() {
        CreatureSpec s = new CreatureSpec("storm_atronach").size(1.0f, 2.6f).stats(70, 8, 0.26).armor(6).xp(40).egg(0x3A3A40, 0x80D0FF)
                .move(Move.HOVER)
                .attack(Attacks.melee(1.0f, HitEffect.shock(3f)).timing(10, 20, 22).range(0, 2.4f))
                .attack(Attacks.melee(1.3f, HitEffect.knock(1.2, 0.4)).anim(AnimSlot.ATTACK2).timing(12, 22, 50).range(0, 2.4f))
                .attack(Attacks.bolt(Kind.SHOCK, 7f, 1.8f, 1, 0.02f).range(4f, 22f).timing(12, 22, 50))
                .resist(1f, 1f, 0f, 0f)
                .tickServer(aura(MagicDamage.SHOCK, 3f, 1.5f))
                .tickClient(particles(ParticleTypes.ELECTRIC_SPARK, 1, 1.2f))
                .voice("storm_atronach", 1.0f);
        s.daedra = true;
        s.kbResist = 0.8;
        s.flySpeed = 0.3;
        s.ambientInterval = 240;
        return s;
    }

    /** Scamp: a cackling little daedra that throws fire. */
    static CreatureSpec scamp() {
        CreatureSpec s = new CreatureSpec("scamp").size(0.6f, 1.3f).stats(18, 3.5, 0.3).xp(8).egg(0xB04A20, 0x401008)
                .attack(Attacks.melee(1.0f).timing(6, 12, 16))
                .attack(Attacks.bolt(Kind.FIRE, 4f, 1.2f, 1, 0.05f).range(4f, 16f).timing(10, 20, 60))
                .attack(Attacks.roar(12, 40).anim(AnimSlot.SPECIAL).chance(0.15f))
                .resist(0.5f, 1f, 1f, 1f)
                .voice("scamp", 1.0f);
        s.daedra = true;
        return s;
    }

    /** Imp: a winged daedra of Cyrodiil, shocking from the air. */
    static CreatureSpec imp() {
        CreatureSpec s = new CreatureSpec("imp").size(0.6f, 1.0f).stats(14, 3, 0.28).xp(8).egg(0x5A6A4A, 0xD0E060)
                .move(Move.FLY)
                .attack(Attacks.melee(1.0f).timing(6, 12, 18))
                .attack(Attacks.bolt(Kind.SHOCK, 4f, 1.3f, 1, 0.05f).range(4f, 16f).timing(10, 20, 50))
                .resist(1f, 1f, 0.5f, 1f)
                .voice("imp", 1.0f);
        s.category = MobCategory.MONSTER;
        s.keepDistance = 6;
        s.daedra = true;
        s.flySpeed = 0.5;
        return s;
    }

    /** Dragon priests: masked liches of the old Dragon Cult. Boss. */
    static CreatureSpec dragonPriest() {
        CreatureSpec s = new CreatureSpec("dragon_priest").size(0.8f, 2.2f).stats(220, 10, 0.25).armor(12).xp(300).egg(0x2A2018, 0xC8A040)
                .move(Move.HOVER)
                .attack(Attacks.melee(1.0f, HitEffect.knock(1.0, 0.2)).timing(9, 18, 20).range(0, 2.4f))
                .attack(Attacks.bolt(Kind.FIRE, 9f, 1.6f, 1, 0.02f).range(4f, 26f).timing(12, 24, 40))
                .attack(Attacks.bolt(Kind.FROST, 8f, 1.5f, 3, 0.12f).range(4f, 22f).timing(12, 24, 70).weight(0.7f))
                .attack(Attacks.bolt(Kind.SHOCK, 9f, 2.0f, 1, 0.02f).range(4f, 26f).timing(12, 24, 60).weight(0.7f))
                .attack(Attacks.summon(() -> Bestiary.FLAME_ATRONACH.get(), 1, 2))
                .attack(Attacks.blink(true).anim(AnimSlot.SPECIAL).chance(0.25f))
                .attack(Attacks.shout(6f, 2.4, 14f))
                .resist(1f, 0.5f, 1f, 0f)
                .variants(10, (level, pos, r) -> r.nextFloat() < 0.6f ? 0 : 1 + r.nextInt(9), "", "morokei", "rahgot", "vokun", "volsung", "nahkriin", "otar", "krosis", "hevnoraak", "konahrik")
                .tickClient(particles(ParticleTypes.SOUL, 6, 0.7f))
                .voice("dragon_priest", 1.0f);
        s.keepDistance = 8;
        s.undead = true;
        s.boss = true;
        s.persistent = true;
        s.kbResist = 0.8;
        s.flySpeed = 0.35;
        s.tracking(10);
        return s;
    }

    // ================================================================================================ Morrowind

    /** The cliff racer: Vvardenfell's screeching plague of the skies. */
    static CreatureSpec cliffRacer() {
        CreatureSpec s = new CreatureSpec("cliff_racer").size(1.0f, 0.9f).stats(12, 3, 0.3).xp(5).egg(0x5A6A7A, 0xB0A08A)
                .move(Move.FLY)
                .attack(Attacks.melee(1.0f, HitEffect.chance(0.3f, HitEffect.poison(60, 0))).timing(6, 12, 20).range(0, 1.4f))
                .attack(Attacks.melee(1.1f).anim(AnimSlot.ATTACK2).timing(6, 12, 30).range(0, 1.4f))
                .attack(Attacks.roar(16, 40).anim(AnimSlot.SPECIAL).chance(0.1f))
                .voice("cliff_racer", 1.0f);
        s.category = MobCategory.MONSTER;
        s.aggroRange = 24;
        s.flySpeed = 0.6;
        return s;
    }

    /** Guar: Morrowind's patient pack and riding lizard. */
    static CreatureSpec guar() {
        CreatureSpec s = new CreatureSpec("guar").size(1.2f, 1.6f).stats(30, 4, 0.25).xp(5).egg(0x6A7058, 0xC8B890)
                .temper(Temper.NEUTRAL)
                .attack(Attacks.melee(1.0f, HitEffect.knock(0.8, 0.2)).timing(8, 16, 30))
                .variants(2, (level, pos, r) -> r.nextInt(5) == 0 ? 1 : 0, "", "pack")
                .voice("guar", 1.0f);
        s.stepHeight = 1.0f;
        return s;
    }

    /** Kagouti: tusked brutes of the Ashlands that charge on sight. */
    static CreatureSpec kagouti() {
        return new CreatureSpec("kagouti").size(1.2f, 1.2f).stats(30, 5, 0.3).armor(4).xp(10).egg(0x7A5A3A, 0xE0D0B0)
                .attack(Attacks.melee(1.0f).timing(7, 14, 18))
                .attack(Attacks.charge(1.5f, 0.55, HitEffect.knock(1.4, 0.4)).anim(AnimSlot.ATTACK2))
                .attack(Attacks.roar(16, 60).anim(AnimSlot.SPECIAL).chance(0.15f))
                .prey(BestiarySpecs::smallGame)
                .voice("kagouti", 1.0f);
    }

    /** Alit: all jaws, two legs and a temper. */
    static CreatureSpec alit() {
        return new CreatureSpec("alit").size(1.0f, 1.2f).stats(26, 5, 0.31).xp(8).egg(0xB07A40, 0x5A3A1A)
                .attack(Attacks.melee(1.1f).timing(7, 14, 18))
                .attack(Attacks.pounce(1.3f, 1.1f).anim(AnimSlot.ATTACK2).weight(0.5f))
                .attack(Attacks.roar(14, 40).anim(AnimSlot.SPECIAL).chance(0.15f))
                .prey(BestiarySpecs::smallGame)
                .voice("alit", 1.0f);
    }

    /** Nix-hounds hunt the Ascadian Isles in packs. */
    static CreatureSpec nixHound() {
        CreatureSpec s = new CreatureSpec("nix_hound").size(0.9f, 1.0f).stats(18, 3.5, 0.33).xp(6).egg(0x4A3048, 0xC8B0A0)
                .attack(Attacks.melee(1.0f).timing(6, 12, 16))
                .attack(Attacks.pounce(1.2f, 1.2f).anim(AnimSlot.ATTACK2).weight(0.5f))
                .attack(Attacks.roar(16, 40).anim(AnimSlot.SPECIAL).chance(0.1f))
                .prey(BestiarySpecs::smallGame)
                .voice("nix_hound", 1.0f);
        s.fleeBelow = 0.15f;
        return s;
    }

    /** Netch: drifting gasbags of the Ashlands; bulls are territorial, betties gentle until hurt. */
    static CreatureSpec netch() {
        CreatureSpec s = new CreatureSpec("netch").size(2.0f, 2.4f).stats(40, 5, 0.15).xp(10).egg(0x8A6A4A, 0xC08AB0)
                .move(Move.HOVER).temper(Temper.NEUTRAL)
                .attack(Attacks.melee(1.0f, HitEffect.poison(100, 0)).timing(10, 20, 24).range(0, 2.5f))
                .attack(Attacks.breath(Kind.POISON, 6f, 1f).range(1f, 5f).timing(10, 40, 160))
                .variants(2, (level, pos, r) -> r.nextBoolean() ? 1 : 0, "bull", "betty")
                .voice("netch", 1.0f);
        s.poisonMult = 0;
        s.flySpeed = 0.15;
        s.kbResist = 0.5;
        s.tracking(10);
        return s;
    }

    /** Silt striders: Morrowind's living travel caravans. */
    static CreatureSpec siltStrider() {
        CreatureSpec s = new CreatureSpec("silt_strider").size(4.0f, 8.0f).stats(400, 0, 0.12).armor(10).xp(0).egg(0x8A7A6A, 0xD8C8A8)
                .temper(Temper.NEUTRAL)
                .voice("silt_strider", 1.0f);
        s.kbResist = 1.0;
        s.persistent = true;
        s.stepHeight = 2.0f;
        s.ambientInterval = 400;
        s.soundVolume = 3f;
        s.tracking(16);
        return s;
    }

    // ================================================================================================ Cyrodiil, Black Marsh, Elsweyr

    /** Minotaurs: bull-headed giants of Cyrodiil's wilds, swinging warhammers. */
    static CreatureSpec minotaur() {
        CreatureSpec s = new CreatureSpec("minotaur").size(1.2f, 2.8f).stats(90, 10, 0.27).armor(6).xp(40).egg(0x4A3020, 0xC8B090)
                .attack(Attacks.melee(1.2f, HitEffect.stagger(20), HitEffect.knock(1.0, 0.3)).timing(14, 26, 30).range(0, 2.6f))
                .attack(Attacks.charge(1.4f, 0.6, HitEffect.knock(1.6, 0.5)).anim(AnimSlot.ATTACK2))
                .attack(Attacks.slam(1.1f, 3f, 0.5).anim(AnimSlot.ATTACK3).timing(14, 26, 100).range(0, 2.5f))
                .attack(Attacks.roar(18, 80).anim(AnimSlot.SPECIAL).chance(0.2f))
                .ranks(1, "minotaur", 25, "lord")
                .voice("minotaur", 1.0f);
        s.kbResist = 0.8;
        return s;
    }

    /** Ogres: hulking cave brutes. */
    static CreatureSpec ogre() {
        CreatureSpec s = new CreatureSpec("ogre").size(1.4f, 2.6f).stats(80, 9, 0.24).armor(4).xp(30).egg(0x5A6A4A, 0x3A2A1A)
                .attack(Attacks.melee(1.0f, HitEffect.knock(1.0, 0.3)).timing(10, 20, 20).range(0, 2.4f))
                .attack(Attacks.slam(1.3f, 3f, 0.6).anim(AnimSlot.ATTACK3).timing(16, 28, 100).range(0, 2.8f))
                .attack(Attacks.roar(16, 60).anim(AnimSlot.SPECIAL).chance(0.15f))
                .ranks(1, "ogre", 22, "elder")
                .voice("ogre", 1.0f);
        s.kbResist = 0.9;
        return s;
    }

    /** Land dreugh: shelled coast-walkers with crushing claws. */
    static CreatureSpec landDreugh() {
        CreatureSpec s = new CreatureSpec("land_dreugh").size(1.0f, 1.9f).stats(50, 6, 0.24).armor(10).xp(20).egg(0x8A3A20, 0xD8B090)
                .attack(Attacks.melee(1.0f).timing(8, 16, 18).range(0, 1.8f))
                .attack(Attacks.melee(1.2f, HitEffect.stagger(12)).anim(AnimSlot.ATTACK2).timing(10, 18, 40).range(0, 1.8f))
                .attack(Attacks.roar(12, 40).anim(AnimSlot.SPECIAL).chance(0.1f))
                .voice("land_dreugh", 1.0f);
        s.waterBreathing = true;
        return s;
    }

    /** Wamasu: Black Marsh's storm-lizards, breathing lightning. */
    static CreatureSpec wamasu() {
        CreatureSpec s = new CreatureSpec("wamasu").size(1.4f, 1.9f).stats(70, 8, 0.3).armor(6).xp(36).egg(0x1A4A4A, 0xA0E0FF)
                .attack(Attacks.melee(1.0f, HitEffect.shock(2f)).timing(8, 16, 18).range(0, 2f))
                .attack(Attacks.sweep(1.2f, 3.5f, -0.4f, HitEffect.knock(1.0, 0.2)).anim(AnimSlot.ATTACK2).timing(12, 20, 60))
                .attack(Attacks.breath(Kind.SHOCK, 11f, 1.2f).timing(10, 40, 160))
                .resist(1f, 1f, 0f, 1f)
                .tickClient(particles(ParticleTypes.ELECTRIC_SPARK, 6, 1f))
                .voice("wamasu", 1.0f);
        s.waterBreathing = true;
        return s;
    }

    /** Swamp leviathans lurk in the deep mire. */
    static CreatureSpec swampLeviathan() {
        CreatureSpec s = new CreatureSpec("swamp_leviathan").size(3.0f, 2.2f).stats(160, 12, 0.22).armor(8).xp(60).egg(0x3A4A2A, 0x8A9A5A)
                .attack(Attacks.melee(1.2f, HitEffect.stagger(20)).timing(12, 24, 24).range(0, 2.6f))
                .attack(Attacks.sweep(1.0f, 4.5f, -0.3f, HitEffect.knock(1.5, 0.4)).anim(AnimSlot.ATTACK2).timing(14, 26, 70))
                .attack(Attacks.roar(24, 100).anim(AnimSlot.SPECIAL).chance(0.15f))
                .prey(BestiarySpecs::bigGame)
                .voice("swamp_leviathan", 1.0f);
        s.waterBreathing = true;
        s.kbResist = 1.0;
        s.tracking(12);
        return s;
    }

    /** Hackwings: Black Marsh's swooping raptors. */
    static CreatureSpec hackwing() {
        CreatureSpec s = new CreatureSpec("hackwing").size(0.9f, 0.9f).stats(12, 3, 0.3).xp(5).egg(0x1A4A3A, 0xC02020)
                .move(Move.FLY)
                .attack(Attacks.melee(1.0f).timing(6, 12, 18).range(0, 1.4f))
                .attack(Attacks.melee(1.2f).anim(AnimSlot.ATTACK2).timing(6, 12, 30).range(0, 1.4f))
                .prey(BestiarySpecs::smallGame)
                .voice("hackwing", 1.0f);
        s.category = MobCategory.MONSTER;
        s.flySpeed = 0.6;
        return s;
    }

    /** Senche-tigers: Elsweyr's great cats (Khajiit ride the tamed ones). */
    static CreatureSpec sencheTiger() {
        CreatureSpec s = new CreatureSpec("senche_tiger").size(1.3f, 1.4f).stats(40, 7, 0.33).xp(14).egg(0xD07020, 0x1A1A1A)
                .temper(Temper.TERRITORIAL)
                .attack(Attacks.melee(1.0f).timing(7, 14, 18).range(0, 1.6f))
                .attack(Attacks.melee(1.2f, HitEffect.stagger(10)).anim(AnimSlot.ATTACK2).timing(8, 16, 30).range(0, 1.6f))
                .attack(Attacks.pounce(1.5f, 1.4f, HitEffect.stagger(15)).anim(AnimSlot.ATTACK3))
                .attack(Attacks.roar(18, 60).anim(AnimSlot.SPECIAL).chance(0.15f))
                .prey(BestiarySpecs::bigGame)
                .voice("senche_tiger", 1.0f);
        s.aggroRange = 18;
        s.category = MobCategory.MONSTER;
        return s;
    }

    /** Alfiq: cat-formed Khajiit mages; leave them be. */
    static CreatureSpec alfiq() {
        return new CreatureSpec("alfiq").size(0.5f, 0.6f).stats(10, 2, 0.32).xp(4).egg(0xD8B070, 0x60C0FF)
                .temper(Temper.NEUTRAL)
                .attack(Attacks.melee(1.0f).timing(5, 10, 14).range(0, 1.0f))
                .attack(Attacks.bolt(Kind.SHOCK, 3f, 1.3f, 1, 0.05f).range(3f, 14f).timing(10, 20, 80))
                .voice("alfiq", 1.0f);
    }

    // ================================================================================================ the west

    /** Sand serpents swim through the Alik'r's dunes and burst out beneath their prey. */
    static CreatureSpec sandSerpent() {
        CreatureSpec s = new CreatureSpec("sand_serpent").size(1.5f, 2.5f).stats(90, 9, 0.28).armor(8).xp(40).egg(0xC8A060, 0x6A4A2A)
                .attack(Attacks.melee(1.2f, HitEffect.stagger(15)).timing(12, 22, 24).range(0, 2.6f))
                .attack(Attacks.melee(1.0f, HitEffect.poison(80, 0)).anim(AnimSlot.ATTACK2).timing(8, 16, 30).range(0, 2.2f))
                .voice("sand_serpent", 1.0f);
        s.kbResist = 1.0;
        return s;
    }

    /** Giant scorpions of the Alik'r and Elsweyr's badlands. */
    static CreatureSpec giantScorpion() {
        return new CreatureSpec("giant_scorpion").size(1.6f, 1.1f).stats(45, 6, 0.27).armor(8).xp(18).egg(0xC8A040, 0x3A2A10)
                .attack(Attacks.melee(1.0f).timing(7, 14, 16).range(0, 1.8f))
                .attack(Attacks.melee(1.0f).anim(AnimSlot.ATTACK2).timing(7, 14, 16).range(0, 1.8f))
                .attack(Attacks.melee(1.3f, HitEffect.poison(120, 1)).anim(AnimSlot.ATTACK3).timing(10, 20, 60).range(0, 2.2f))
                .voice("giant_scorpion", 1.0f)
                .step(() -> SoundEvents.SPIDER_STEP);
    }

    /** Harpies of High Rock and Hammerfell: talons from the sky and a deafening shriek. */
    static CreatureSpec harpy() {
        CreatureSpec s = new CreatureSpec("harpy").size(0.8f, 1.8f).stats(26, 5, 0.28).xp(14).egg(0x6A5A4A, 0xD8C0A0)
                .move(Move.FLY)
                .attack(Attacks.melee(1.0f).timing(7, 14, 18).range(0, 1.6f))
                .attack(Attacks.melee(0.8f, HitEffect.knock(1.2, 0.5)).anim(AnimSlot.ATTACK2).timing(8, 16, 40).range(0, 1.8f))
                .attack(Attacks.roar(14, 80).anim(AnimSlot.SPECIAL).chance(0.2f))
                .voice("harpy", 1.0f);
        s.category = MobCategory.MONSTER;
        s.flySpeed = 0.55;
        return s;
    }

    /** Indriks: gentle, feathered beasts of Summerset and Valenwood. */
    static CreatureSpec indrik() {
        return new CreatureSpec("indrik").size(1.2f, 1.9f).stats(40, 0, 0.32).xp(6).egg(0xF0E8D0, 0x80B0E0)
                .temper(Temper.PASSIVE)
                .voice("indrik", 1.0f)
                .step(() -> SoundEvents.HORSE_STEP);
    }

    /** Sea serpents of the Abecean and the Pyandonean seas. */
    static CreatureSpec seaSerpent() {
        CreatureSpec s = new CreatureSpec("sea_serpent").size(3.0f, 2.0f).stats(140, 11, 1.0).armor(6).xp(60).egg(0x1A4A5A, 0xB0D0C0)
                .move(Move.SWIM)
                .attack(Attacks.melee(1.0f, HitEffect.stagger(15)).timing(10, 20, 24).range(0, 2.6f))
                .attack(Attacks.melee(1.3f, HitEffect.knock(1.5, 0.4)).anim(AnimSlot.ATTACK2).timing(12, 24, 60).range(0, 3f))
                .voice("sea_serpent", 1.0f);
        s.kbResist = 1.0;
        s.chaseSpeed = 1.6;
        s.tracking(12);
        return s;
    }

    /** Echatere: the Orcs' shaggy herd beasts. */
    static CreatureSpec echatere() {
        CreatureSpec s = new CreatureSpec("echatere").size(1.8f, 1.8f).stats(60, 7, 0.22).armor(4).xp(10).egg(0x4A4038, 0x9A8A70)
                .temper(Temper.NEUTRAL)
                .attack(Attacks.melee(1.0f, HitEffect.knock(1.2, 0.3)).timing(10, 20, 30).range(0, 2f))
                .voice("echatere", 1.0f);
        s.kbResist = 0.6;
        return s;
    }

    /** Durzogs: the Orcs' vicious war beasts, wild in Wrothgar. */
    static CreatureSpec durzog() {
        return new CreatureSpec("durzog").size(1.0f, 1.1f).stats(24, 5, 0.33).xp(10).egg(0x8A8A40, 0x3A2A1A)
                .attack(Attacks.melee(1.0f).timing(6, 12, 16).range(0, 1.4f))
                .attack(Attacks.pounce(1.3f, 1.2f).anim(AnimSlot.ATTACK2).weight(0.5f))
                .attack(Attacks.roar(14, 40).anim(AnimSlot.SPECIAL).chance(0.15f))
                .prey(BestiarySpecs::smallGame)
                .voice("durzog", 1.0f);
    }

    /** Atmora's frost giants: colossal, ice-armoured, hurling boulders of ice. */
    static CreatureSpec frostGiant() {
        CreatureSpec s = new CreatureSpec("frost_giant").size(1.6f, 5.2f).stats(260, 16, 0.26).armor(6).xp(120).egg(0xA0C0E0, 0x3A4A5A)
                .temper(Temper.TERRITORIAL)
                .attack(Attacks.melee(1.0f, HitEffect.chill(3f), HitEffect.knock(1.2, 1.0)).timing(14, 26, 30).range(0, 3.2f))
                .attack(Attacks.slam(1.1f, 4f, 0.8, HitEffect.chill(3f)).anim(AnimSlot.ATTACK3).timing(14, 26, 90).range(0, 3.5f))
                .attack(Attacks.bolt(Kind.FROST, 10f, 1.1f, 1, 0.05f).range(6f, 24f).timing(16, 30, 120))
                .attack(Attacks.roar(24, 80).anim(AnimSlot.SPECIAL).chance(0.15f))
                .resist(1.5f, 0f, 1f, 1f)
                .voice("frost_giant", 1.0f);
        s.aggroRange = 16;
        s.kbResist = 1.0;
        s.stepHeight = 1.5f;
        s.tracking(12);
        return s;
    }

    /** Kamal: Akavir's snow demons, breathing killing frost. */
    static CreatureSpec kamal() {
        return new CreatureSpec("kamal").size(0.9f, 2.3f).stats(50, 7, 0.3).armor(4).xp(24).egg(0xC0D8F0, 0x2A3A60)
                .attack(Attacks.melee(1.0f, HitEffect.chill(2f)).timing(8, 16, 18).range(0, 2f))
                .attack(Attacks.melee(1.2f).anim(AnimSlot.ATTACK2).timing(9, 18, 30).range(0, 2f))
                .attack(Attacks.breath(Kind.FROST, 8f, 1f).timing(10, 40, 150))
                .attack(Attacks.bolt(Kind.FROST, 6f, 1.4f, 2, 0.1f).range(4f, 18f).timing(12, 22, 80).weight(0.6f))
                .resist(1.5f, 0f, 1f, 1f)
                .voice("kamal", 1.0f);
    }

    /** Tsaesci: serpent-folk warriors of Akavir. */
    static CreatureSpec tsaesci() {
        return new CreatureSpec("tsaesci").size(0.8f, 2.2f).stats(55, 8, 0.29).armor(8).xp(30).egg(0xC8A040, 0x3A2A10)
                .attack(Attacks.melee(1.0f).timing(8, 16, 16).range(0, 2.2f))
                .attack(Attacks.melee(1.1f, HitEffect.poison(100, 1)).anim(AnimSlot.ATTACK2).timing(9, 18, 40).range(0, 2f))
                .attack(Attacks.bolt(Kind.SHADOW, 6f, 1.4f, 1, 0.05f).range(4f, 18f).timing(12, 22, 90))
                .attack(Attacks.roar(14, 60).anim(AnimSlot.SPECIAL).chance(0.1f))
                .ranks(1, "tsaesci", 20, "warden", 35, "lord")
                .voice("tsaesci", 1.0f);
    }

    // ================================================================================================ the endless lands

    static CreatureSpec realmStalker() {
        CreatureSpec s = new CreatureSpec("realm_stalker").size(1.0f, 1.1f).stats(28, 5, 0.33).xp(10).egg(0x8A8A8A, 0xE0E0E0)
                .attack(Attacks.melee(1.0f).timing(6, 12, 16).range(0, 1.4f))
                .attack(Attacks.pounce(1.3f, 1.3f).anim(AnimSlot.ATTACK2).weight(0.5f))
                .attack(Attacks.roar(16, 40).anim(AnimSlot.SPECIAL).chance(0.1f))
                .prey(BestiarySpecs::smallGame)
                .variants(1, BestiarySpecs::regionTint)
                .voice("realm_stalker", 1.0f);
        s.tinted = true;
        return s;
    }

    static CreatureSpec realmGrazer() {
        CreatureSpec s = new CreatureSpec("realm_grazer").size(1.3f, 1.7f).stats(30, 4, 0.24).xp(4).egg(0x9A9A9A, 0xD0D0D0)
                .temper(Temper.NEUTRAL)
                .attack(Attacks.melee(1.0f, HitEffect.knock(1.0, 0.2)).timing(8, 16, 30))
                .variants(1, BestiarySpecs::regionTint)
                .voice("realm_grazer", 1.0f);
        s.tinted = true;
        s.fleeBelow = 0.4f;
        return s;
    }

    static CreatureSpec realmWyrm() {
        CreatureSpec s = new CreatureSpec("realm_wyrm").size(1.2f, 1.2f).stats(40, 6, 0.28).armor(4).xp(16).egg(0x7A7A7A, 0xF0F0F0)
                .attack(Attacks.melee(1.0f).timing(8, 16, 18).range(0, 1.8f))
                .attack(Attacks.melee(1.2f, HitEffect.poison(80, 0)).anim(AnimSlot.ATTACK2).timing(10, 18, 40).range(0, 1.8f))
                .attack(Attacks.roar(14, 40).anim(AnimSlot.SPECIAL).chance(0.1f))
                .variants(1, BestiarySpecs::regionTint)
                .voice("realm_wyrm", 1.0f);
        s.tinted = true;
        return s;
    }
}
