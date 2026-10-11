package com.skycraft.seas;

import com.skycraft.creatures.entity.CreatureBolt.Kind;
import com.skycraft.creatures.entity.anim.AnimSlot;
import com.skycraft.creatures.spec.Attacks;
import com.skycraft.creatures.spec.CreatureSpec;
import com.skycraft.creatures.spec.CreatureSpec.Move;
import com.skycraft.creatures.spec.CreatureSpec.Temper;
import com.skycraft.creatures.spec.HitEffect;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;

/**
 * The creatures of Nirn's seas, coasts and skies (bestiary specs, see {@link com.skycraft.creatures.spec.CreatureSpec};
 * the generic {@code SkyMonster} / {@code SkyBeast} classes run them). Lore notes:
 * <ul>
 *     <li>Dreugh: the shelled, many-legged sea folk of Morrowind's coasts and the Inner Sea, hunted for their wax; a
 *     queen broods in the deepest lairs.</li>
 *     <li>Sea serpents are rare monsters of the open ocean (the creatures module's {@code sea_serpent}); the sea
 *     leviathan, a kraken of the Maormer waters off Pyandonea, is rarer still.</li>
 *     <li>Giant mudcrabs scuttle on every coast, coral crabs on the reefs of Thras; sea netch drift in warm waters;
 *     seals haul out on the ice of the Sea of Ghosts; whales sing far out in the Padomaic and Eltheric oceans.</li>
 *     <li>Hawks circle over Skyrim's tundra and crags (Skyrim's hawk feathers, beaks and eggs).</li>
 * </ul>
 */
final class SeaSpecs {
    private SeaSpecs() {}

    private static String id(LivingEntity e) {
        return EntityType.getKey(e.getType()).getPath();
    }

    /** What the sea's hunters take: fish, seals, swimmers in their water. */
    static boolean seaPrey(LivingEntity e) {
        String id = id(e);
        return id.equals("seal") || id.equals("river_betty") || id.equals("slaughterfish") || id.equals("horker")
                || e instanceof net.minecraft.world.entity.animal.AbstractFish;
    }

    // ================================================================================================ the deep

    /** Dreugh: shelled sea folk, two great claws and a skirt of tentacles; lairs of them guard the Inner Sea. */
    static CreatureSpec dreugh() {
        CreatureSpec s = new CreatureSpec("dreugh").size(1.3f, 1.5f).stats(60, 7, 0.9).armor(10).xp(28).egg(0x6A3A28, 0xC89060)
                .move(Move.SWIM)
                .attack(Attacks.melee(1.0f).timing(8, 16, 18).range(0, 2.0f))
                .attack(Attacks.melee(1.3f, HitEffect.stagger(15), HitEffect.knock(0.8, 0.1)).anim(AnimSlot.ATTACK2).timing(10, 20, 44).range(0, 2.2f))
                .attack(Attacks.roar(14, 50).anim(AnimSlot.SPECIAL).chance(0.12f))
                .variants(2, (level, pos, r) -> r.nextInt(6) == 0 ? 1 : 0, "", "elder")
                .voice("land_dreugh", 0.82f);
        s.variantPower = new float[]{1f, 1.6f};
        s.waterBreathing = true;
        s.aggroRange = 14;
        s.chaseSpeed = 1.4;
        s.kbResist = 0.4;
        s.frostMult = 0.75f;
        s.tracking(10);
        return s;
    }

    /** The dreugh queen: a vast brood-mother in the deepest lairs, calling her warriors around her. */
    static CreatureSpec dreughQueen() {
        CreatureSpec s = new CreatureSpec("dreugh_queen").size(2.6f, 2.8f).stats(320, 13, 0.85).armor(16).xp(260).egg(0x4A1A30, 0xE0A070)
                .move(Move.SWIM)
                .attack(Attacks.melee(1.0f, HitEffect.stagger(15)).timing(10, 20, 22).range(0, 3.2f))
                .attack(Attacks.sweep(1.2f, 4.5f, -0.3f, HitEffect.knock(1.4, 0.3)).anim(AnimSlot.ATTACK2).timing(12, 24, 70))
                .attack(Attacks.summon(SeasRegistry.DREUGH, 2, 4).anim(AnimSlot.SUMMON))
                .attack(Attacks.roar(24, 100).anim(AnimSlot.SPECIAL).chance(0.25f))
                .tickClient(mob -> {
                    if (mob.tickCount % 3 == 0 && mob.isInWater()) {
                        mob.level().addParticle(ParticleTypes.BUBBLE, mob.getRandomX(0.8), mob.getRandomY(), mob.getRandomZ(0.8), 0, 0.05, 0);
                    }
                })
                .voice("land_dreugh", 0.55f);
        s.waterBreathing = true;
        s.boss = true;
        s.persistent = true;
        s.aggroRange = 22;
        s.kbResist = 1.0;
        s.soundVolume = 2.5f;
        s.tracking(12);
        return s;
    }

    /** A kraken of the Maormer seas: a great mantle and eight arms that drag ships' crews under. */
    static CreatureSpec seaLeviathan() {
        CreatureSpec s = new CreatureSpec("sea_leviathan").size(4.0f, 3.2f).stats(520, 16, 0.85).armor(10).xp(420).egg(0x2A1A3A, 0x9A50C0)
                .move(Move.SWIM)
                .attack(Attacks.sweep(1.0f, 6.5f, -0.6f, HitEffect.knock(1.8, 0.5), HitEffect.slow(60, 1)).timing(14, 26, 40))
                .attack(Attacks.melee(1.5f, HitEffect.stagger(25)).anim(AnimSlot.ATTACK2).timing(12, 22, 50).range(0, 4.5f))
                .attack(Attacks.breath(Kind.WATER, 9f, 1.3f).anim(AnimSlot.SPECIAL2).range(2f, 9f).timing(14, 40, 180))
                .attack(Attacks.roar(30, 120).anim(AnimSlot.SPECIAL).chance(0.3f))
                .prey(SeaSpecs::seaPrey)
                .voice("swamp_leviathan", 0.6f);
        s.waterBreathing = true;
        s.boss = true;
        s.persistent = true;
        s.aggroRange = 32;
        s.kbResist = 1.0;
        s.chaseSpeed = 1.5;
        s.soundVolume = 3f;
        s.tracking(16);
        return s;
    }

    /** Giant slaughterfish: an old, scarred brute of the cold seas. */
    static CreatureSpec giantSlaughterfish() {
        CreatureSpec s = new CreatureSpec("giant_slaughterfish").size(1.6f, 0.9f).stats(44, 7, 1.1).armor(2).xp(20).egg(0x3A4A40, 0xC0A060)
                .move(Move.SWIM)
                .attack(Attacks.melee(1.0f, HitEffect.chance(0.35f, HitEffect.slow(40, 0))).timing(6, 12, 16).range(0, 1.8f))
                .attack(Attacks.melee(1.4f, HitEffect.stagger(10)).anim(AnimSlot.ATTACK2).timing(8, 14, 40).range(0, 2.0f))
                .prey(SeaSpecs::seaPrey)
                .voice("sea_serpent", 1.6f);
        s.aggroRange = 18;
        s.chaseSpeed = 1.7;
        s.tracking(8);
        return s;
    }

    /** Sea netch: a drifting bell of jelly trailing stinging threads; glows faintly at night. */
    static CreatureSpec seaNetch() {
        CreatureSpec s = new CreatureSpec("sea_netch").size(1.2f, 1.4f).stats(18, 4, 0.35).xp(6).egg(0x6AA0C0, 0xE080D0)
                .move(Move.SWIM).temper(Temper.NEUTRAL)
                .attack(Attacks.melee(1.0f, HitEffect.poison(80, 0), HitEffect.slow(60, 0)).timing(8, 16, 26).range(0, 1.8f))
                .variants(2, (level, pos, r) -> TamrielMap.sampleScratch(pos.getX(), pos.getZ()).inTamriel && r.nextBoolean() ? 0 : 1, "", "blue")
                .voice("netch", 1.9f);
        s.category = MobCategory.WATER_AMBIENT;
        s.poisonMult = 0;
        s.ambientInterval = 400;
        s.soundVolume = 0.5f;
        s.tracking(6);
        return s;
    }

    /** A whale: huge, slow and harmless unless harried; sings under the open ocean. */
    static CreatureSpec whale() {
        CreatureSpec s = new CreatureSpec("whale").size(4.5f, 2.6f).stats(260, 10, 0.55).armor(4).xp(30).egg(0x2A3440, 0x9AA8B0)
                .move(Move.SWIM).temper(Temper.NEUTRAL)
                .attack(Attacks.sweep(1.0f, 5f, -0.2f, HitEffect.knock(2.2, 0.6)).timing(16, 28, 60))
                .voice("sea_serpent", 0.4f);
        s.ambientSound = () -> com.skycraft.audio.AudioSounds.get("ambient.whale");
        s.ambientInterval = 600;
        s.soundVolume = 4f;
        s.kbResist = 1.0;
        s.tracking(16);
        return s;
    }

    // ================================================================================================ coasts and ice

    /** Giant mudcrabs of the shores, and the bright coral crabs of the reefs of Thras. */
    static CreatureSpec seaCrab() {
        CreatureSpec s = new CreatureSpec("sea_crab").size(1.4f, 0.9f).stats(34, 6, 0.24).armor(12).xp(12).egg(0x6A5A40, 0xE0603A)
                .temper(Temper.TERRITORIAL)
                .attack(Attacks.melee(1.0f).timing(7, 14, 18).range(0, 1.8f))
                .attack(Attacks.melee(1.3f, HitEffect.chance(0.4f, HitEffect.slow(40, 1))).anim(AnimSlot.ATTACK2).timing(8, 16, 36).range(0, 1.9f))
                .variants(2, (level, pos, r) -> {
                    var region = TamrielMap.region(pos.getX(), pos.getZ());
                    return "thras".equals(region.province()) || Math.hypot(pos.getX() + 15000, pos.getZ() - 16500) < 6000 ? 1 : 0;
                }, "", "coral")
                .voice("mudcrab", 0.6f);
        s.variantPower = new float[]{1f, 1.4f};
        s.waterBreathing = true;
        s.aggroRange = 5;
        s.kbResist = 0.6;
        s.stepHeight = 1.0f;
        return s;
    }

    /** Seals of the Sea of Ghosts: shy, sleek, at home on the ice floes. */
    static CreatureSpec seal() {
        CreatureSpec s = new CreatureSpec("seal").size(1.1f, 0.7f).stats(14, 2, 0.18).xp(3).egg(0x7A7A80, 0xC8C8C0)
                .temper(Temper.PASSIVE)
                .voice("horker", 1.6f);
        s.waterBreathing = true;
        s.category = MobCategory.CREATURE;
        s.soundVolume = 0.6f;
        return s;
    }

    // ================================================================================================ skies

    /** Hawks: wheel high over the tundra and crags, flee from people (feathers, beak and eggs for alchemy). */
    static CreatureSpec hawk() {
        CreatureSpec s = new CreatureSpec("hawk").size(0.6f, 0.5f).stats(6, 1, 0.3).xp(2).egg(0x6A4A2A, 0xE0D0B0)
                .move(Move.FLY).temper(Temper.PASSIVE);
        s.ambientSound = () -> com.skycraft.audio.AudioSounds.get("ambient.hawk");
        s.hurtSound = () -> net.minecraft.sounds.SoundEvents.PARROT_HURT;
        s.deathSound = () -> net.minecraft.sounds.SoundEvents.PARROT_DEATH;
        s.ambientInterval = 260;
        s.voicePitch = 1.0f;
        s.flySpeed = 0.55;
        s.category = MobCategory.AMBIENT;
        s.prey = e -> e instanceof net.minecraft.world.entity.animal.Rabbit || id(e).equals("river_betty");
        s.tracking(8);
        return s;
    }

    /** True for players a passive creature should not fear (creative, spectators). */
    static boolean harmless(LivingEntity e) {
        return e instanceof Player p && (p.isCreative() || p.isSpectator());
    }
}
