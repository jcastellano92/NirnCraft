package com.nirncraft.curse;

import com.nirncraft.core.Notifier;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.creatures.entity.CorpseEntity;
import com.nirncraft.crime.Bounty;
import com.nirncraft.crime.Crimes;
import com.nirncraft.network.NotifyKind;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * Lycanthropy, the Companions' beast blood. Beast Form is a greater power (once a day): two and a half minutes as a
 * werewolf, extended by feeding on the dead. In Beast Form you are fast and strong, fight with claws, howl instead of
 * shouting, see in the dark and heal only by feeding; weapons, spells and items are out of reach, and everyone who
 * sees you is your enemy. Werewolves never catch diseases (Sanguinare Vampiris included) and can't rest well.
 */
public final class Lycanthropy {
    /** Beast Form lasts this long (Skyrim: 150 s). */
    public static final int DURATION = 20 * 150;
    /** Each feeding adds this much (Skyrim: 30 s). */
    public static final int FEED_EXTENSION = 20 * 30;
    public static final int DAY = 24000;
    /** Sanies Lupinus turns its carrier into a werewolf after three in-game days unless cured. */
    public static final int INCUBATION = 3 * DAY;
    /** Chance per hit that a werewolf's attack passes on Stage 1 Sanies Lupinus. */
    public static final float INFECTION_CHANCE = 0.25f;
    /** Being seen changing shape is a crime (Skyrim's 1000 gold bounty). */
    public static final int TRANSFORM_BOUNTY = 1000;

    static final net.minecraft.resources.Identifier SPEED = net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", "modifier_0b6f2b8e-3c52-4c0f-9a1e-6f1a7c2b5d01");
    static final net.minecraft.resources.Identifier ARMOR = net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", "modifier_0b6f2b8e-3c52-4c0f-9a1e-6f1a7c2b5d02");
    static final net.minecraft.resources.Identifier KNOCKBACK = net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", "modifier_0b6f2b8e-3c52-4c0f-9a1e-6f1a7c2b5d03");
    /** Persistent-data flag on a corpse someone already fed on. */
    public static final String FED_TAG = "nirncraft_fed_on";

    private Lycanthropy() {}

    /** Contracts Stage 1 Sanies Lupinus (incubating lycanthropy, curable for 3 days by potion, shrine, or healer). */
    static boolean infect(ServerPlayer player) {
        if (player.isCreative() || player.isSpectator()) return false;
        CompoundTag t = Curses.tag(player);
        if (t.getBooleanOr(Curses.WEREWOLF, false) || t.getBooleanOr(Curses.VAMPIRE, false) || t.getLongOr(Curses.SANIES_LUPINUS, 0L) > 0) return false;
        com.nirncraft.core.Race race = SkyData.get(player).getRace();
        if ((race == com.nirncraft.core.Race.ARGONIAN || race == com.nirncraft.core.Race.BOSMER) && player.getRandom().nextBoolean()) return false;
        t.putLong(Curses.SANIES_LUPINUS, Math.max(1, player.level().getGameTime()));
        t.putInt(Curses.LUPINUS_DAYS, 0);
        player.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(CurseRegistry.SANIES_LUPINUS.get()), MobEffectInstance.INFINITE_DURATION, 0, false, false, true));
        Curses.dirty(player);
        Notifier.message(player, Component.translatable("message.nirncraft.survival.contracted", CurseRegistry.SANIES_LUPINUS.get().getDisplayName()));
        return true;
    }

    /** Once a second for all players: advances Sanies Lupinus incubation (3 days until full lycanthropy). */
    static void tickDisease(ServerPlayer player, long now) {
        CompoundTag t = Curses.tag(player);
        long lup = t.getLongOr(Curses.SANIES_LUPINUS, 0L);
        if (lup <= 0) return;
        if (!player.hasEffect(com.nirncraft.platform.Effects.holder(CurseRegistry.SANIES_LUPINUS.get()))) {
            t.remove(Curses.SANIES_LUPINUS);
            t.remove(Curses.LUPINUS_DAYS);
            Curses.dirty(player);
        } else if (now - lup >= INCUBATION) {
            Curses.grantBeastBlood(player);
        } else {
            int days = (int) ((now - lup) / DAY);
            if (days > t.getIntOr(Curses.LUPINUS_DAYS, 0)) {
                t.putInt(Curses.LUPINUS_DAYS, days);
                Curses.dirty(player);
                Notifier.message(player, Component.translatable("curse.nirncraft.sanies_lupinus.day" + Math.min(2, days)));
            }
        }
    }

    /** Beast Form, if the power has recharged. Returns whether the player changed. */
    static boolean transform(ServerPlayer player) {
        if (!Curses.isWerewolf(player) || Curses.transformed(player) || !player.isAlive()) return false;
        CompoundTag t = Curses.tag(player);
        long now = player.level().getGameTime();
        if (!player.isCreative() && now < t.getLongOr(Curses.BEAST_READY, 0L)) {
            String hours = com.nirncraft.SkyConfig.formatTicks(t.getLongOr(Curses.BEAST_READY, 0L) - now);
            Notifier.message(player, Component.translatable("message.nirncraft.power_cooldown", CursePowers.name(CursePowers.BEAST_FORM), hours));
            return false;
        }
        int duration = DURATION;
        if (CursePerks.has(player, CursePerks.HIRCINES_FAVOR)) duration = duration * 3 / 2;
        t.putString(Curses.FORM, Form.BEAST.id);
        t.putLong(Curses.FORM_UNTIL, now + duration);
        t.putLong(Curses.BEAST_READY, now + com.nirncraft.SkyConfig.POWER_COOLDOWN_MAX_SECONDS.get() * 20L);
        Curses.dirty(player);
        applyAttributes(player, true);
        Vampirism.refreshBonuses(player);
        player.setHealth(player.getMaxHealth());
        PlayerData data = SkyData.get(player);
        data.setStamina(data.maxStamina());
        player.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.NIGHT_VISION), duration + 100, 0, false, false, false));
        data.addStat("beast_form", 1);
        CursePackets.sync(player);
        CursePackets.anim(player, CurseAnims.Anim.TRANSFORM);
        ServerLevel level = player.level();
        level.playSound(null, player.blockPosition(), com.nirncraft.audio.AudioSounds.creature("wolf", "howl"), SoundSource.PLAYERS, 1.4f, 0.55f);
        level.playSound(null, player.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.PLAYERS, 0.6f, 1.3f);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, player.getX(), player.getY() + 1, player.getZ(), 10, 0.4, 0.6, 0.4, 0.01);
        Notifier.send(player, NotifyKind.MESSAGE, Component.translatable("curse.nirncraft.beast.transformed"), Component.empty());
        if (Crimes.canCommitCrime(player)) Crimes.report(player, player.blockPosition(), TRANSFORM_BOUNTY, false, null);
        return true;
    }

    /** Back to the player's own form ({@code natural}: the time ran out, not death or logout). */
    static void revert(ServerPlayer player, boolean natural) {
        CompoundTag t = Curses.tag(player);
        if (Form.byId(t.getStringOr(Curses.FORM, "")) != Form.BEAST) return;
        t.remove(Curses.FORM);
        t.remove(Curses.FORM_UNTIL);
        Curses.dirty(player);
        applyAttributes(player, false);
        Vampirism.refreshBonuses(player);
        player.removeEffect(com.nirncraft.platform.Effects.holder(MobEffects.NIGHT_VISION));
        CursePackets.sync(player);
        CurseEvents.noFall(player, 100);
        if (natural && player.isAlive()) {
            CursePackets.anim(player, CurseAnims.Anim.TRANSFORM);
            player.level().playSound(null, player.blockPosition(), SoundEvents.WOLF_SOUNDS.get(net.minecraft.world.entity.animal.wolf.WolfSoundVariants.SoundSet.CLASSIC).adultSounds().whineSound().value(), SoundSource.PLAYERS, 1f, 0.6f);
            Notifier.message(player, Component.translatable("curse.nirncraft.beast.reverted"));
        }
    }

    static void applyAttributes(ServerPlayer player, boolean on) {
        modifier(player.getAttribute(Attributes.MOVEMENT_SPEED), SPEED, "NirnCraft Beast Form speed", on ? 0.3 : 0,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        double armor = on ? (CursePerks.has(player, CursePerks.THICK_HIDE) ? 10 : 6) : 0;
        modifier(player.getAttribute(Attributes.ARMOR), ARMOR, "NirnCraft Beast Form hide", armor, AttributeModifier.Operation.ADD_VALUE);
        modifier(player.getAttribute(Attributes.KNOCKBACK_RESISTANCE), KNOCKBACK, "NirnCraft Beast Form", on ? 0.5 : 0,
                AttributeModifier.Operation.ADD_VALUE);
    }

    static void modifier(AttributeInstance inst, net.minecraft.resources.Identifier id, String name, double amount, AttributeModifier.Operation op) {
        if (inst == null) return;
        AttributeModifier existing = inst.getModifier(id);
        if (existing != null && existing.amount() == amount) return;
        if (existing != null) inst.removeModifier(id);
        if (amount != 0) inst.addTransientModifier(new AttributeModifier(id, amount, op));
    }

    /** Once a second while transformed. */
    static void tick(ServerPlayer player, long now) {
        CompoundTag t = Curses.tag(player);
        if (now >= t.getLongOr(Curses.FORM_UNTIL, 0L)) {
            revert(player, true);
            return;
        }
        // transient modifiers don't survive a dimension change or respawn: keep them in place
        applyAttributes(player, true);
        if (player.tickCount % 200 == 0 && player.getRandom().nextInt(3) == 0) {
            player.level().playSound(null, player.blockPosition(), SoundEvents.WOLF_SOUNDS.get(net.minecraft.world.entity.animal.wolf.WolfSoundVariants.SoundSet.CLASSIC).adultSounds().growlSound().value(), SoundSource.PLAYERS, 0.8f, 0.6f);
        }
    }

    // ------------------------------------------------------------------ feeding

    /**
     * Feeds on a corpse: more time in Beast Form, health back. Only people (and, with Savage Feeding, beasts) will do;
     * each body only once.
     */
    static boolean feed(ServerPlayer player, CorpseEntity corpse) {
        if (Curses.form(player) != Form.BEAST || corpse.isRemoved() || player.distanceToSqr(corpse) > 4 * 4) return false;
        if (corpse.getPersistentData().getBooleanOr(FED_TAG, false)) {
            Notifier.message(player, Component.translatable("curse.nirncraft.beast.already_fed"));
            return true;
        }
        String bodyType = com.nirncraft.platform.EntityData.saveWithoutId(corpse).getStringOr("BodyType","");
        Entity probe = com.nirncraft.platform.EntityData.type(bodyType).map(type -> type == net.minecraft.world.entity.EntityTypes.PLAYER ? null : type.create(player.level(), net.minecraft.world.entity.EntitySpawnReason.EVENT)).orElse(null);
        boolean person = "minecraft:player".equals(bodyType) || CurseCombat.isPerson(probe);
        boolean undead = probe != null && CurseCombat.isUndead(probe);
        if (probe != null) probe.discard();
        if (undead || !person && !CursePerks.has(player, CursePerks.SAVAGE_FEEDING)) {
            Notifier.message(player, Component.translatable("curse.nirncraft.beast.cannot_feed"));
            return true;
        }
        corpse.getPersistentData().putBoolean(FED_TAG, true);
        CompoundTag t = Curses.tag(player);
        t.putLong(Curses.FORM_UNTIL, t.getLongOr(Curses.FORM_UNTIL, 0L) + FEED_EXTENSION);
        float heal = player.getMaxHealth() * (CursePerks.has(player, CursePerks.GORGING) ? 0.5f : 0.25f);
        player.heal(heal);
        player.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.NIGHT_VISION), (int) (t.getLongOr(Curses.FORM_UNTIL, 0L) - player.level().getGameTime()) + 100, 0, false, false, false));
        CursePerks.addFeed(player, CursePerks.Tree.WEREWOLF);
        Curses.dirty(player);
        CursePackets.anim(player, CurseAnims.Anim.FEED);
        ServerLevel level = player.level();
        level.playSound(null, corpse.blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.PLAYERS, 1f, 0.6f);
        level.playSound(null, corpse.blockPosition(), SoundEvents.WOLF_SOUNDS.get(net.minecraft.world.entity.animal.wolf.WolfSoundVariants.SoundSet.CLASSIC).adultSounds().growlSound().value(), SoundSource.PLAYERS, 0.8f, 0.5f);
        level.sendParticles(new DustParticleOptions(net.minecraft.util.ARGB.colorFromFloat(1f, 0.5f, 0.03f, 0.03f), 1.3f), corpse.getX(), corpse.getY() + 0.4,
                corpse.getZ(), 24, 0.35, 0.15, 0.35, 0.02);
        Notifier.message(player, Component.translatable("curse.nirncraft.beast.fed"));
        // feeding on a person in front of witnesses: on top of everything else, a crime
        if (person && Crimes.canCommitCrime(player)) Crimes.report(player, corpse.blockPosition(), Bounty.ASSAULT, false, null);
        return true;
    }
}
