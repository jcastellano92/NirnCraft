package com.skycraft.curse;

import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.creatures.entity.CorpseEntity;
import com.skycraft.crime.Bounty;
import com.skycraft.crime.Crimes;
import com.skycraft.network.NotifyKind;
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

    static final UUID SPEED = UUID.fromString("0b6f2b8e-3c52-4c0f-9a1e-6f1a7c2b5d01");
    static final UUID ARMOR = UUID.fromString("0b6f2b8e-3c52-4c0f-9a1e-6f1a7c2b5d02");
    static final UUID KNOCKBACK = UUID.fromString("0b6f2b8e-3c52-4c0f-9a1e-6f1a7c2b5d03");
    /** Persistent-data flag on a corpse someone already fed on. */
    public static final String FED_TAG = "skycraft_fed_on";

    private Lycanthropy() {}

    /** Contracts Stage 1 Sanies Lupinus (incubating lycanthropy, curable for 3 days by potion, shrine, or healer). */
    static boolean infect(ServerPlayer player) {
        if (player.isCreative() || player.isSpectator()) return false;
        CompoundTag t = Curses.tag(player);
        if (t.getBoolean(Curses.WEREWOLF) || t.getBoolean(Curses.VAMPIRE) || t.getLong(Curses.SANIES_LUPINUS) > 0) return false;
        com.skycraft.core.Race race = SkyData.get(player).getRace();
        if ((race == com.skycraft.core.Race.ARGONIAN || race == com.skycraft.core.Race.BOSMER) && player.getRandom().nextBoolean()) return false;
        t.putLong(Curses.SANIES_LUPINUS, Math.max(1, player.level().getGameTime()));
        t.putInt(Curses.LUPINUS_DAYS, 0);
        player.addEffect(new MobEffectInstance(CurseRegistry.SANIES_LUPINUS.get(), MobEffectInstance.INFINITE_DURATION, 0, false, false, true));
        Curses.dirty(player);
        Notifier.message(player, Component.translatable("message.skycraft.survival.contracted", CurseRegistry.SANIES_LUPINUS.get().getDisplayName()));
        return true;
    }

    /** Once a second for all players: advances Sanies Lupinus incubation (3 days until full lycanthropy). */
    static void tickDisease(ServerPlayer player, long now) {
        CompoundTag t = Curses.tag(player);
        long lup = t.getLong(Curses.SANIES_LUPINUS);
        if (lup <= 0) return;
        if (!player.hasEffect(CurseRegistry.SANIES_LUPINUS.get())) {
            t.remove(Curses.SANIES_LUPINUS);
            t.remove(Curses.LUPINUS_DAYS);
            Curses.dirty(player);
        } else if (now - lup >= INCUBATION) {
            Curses.grantBeastBlood(player);
        } else {
            int days = (int) ((now - lup) / DAY);
            if (days > t.getInt(Curses.LUPINUS_DAYS)) {
                t.putInt(Curses.LUPINUS_DAYS, days);
                Curses.dirty(player);
                Notifier.message(player, Component.translatable("curse.skycraft.sanies_lupinus.day" + Math.min(2, days)));
            }
        }
    }

    /** Beast Form, if the power has recharged. Returns whether the player changed. */
    static boolean transform(ServerPlayer player) {
        if (!Curses.isWerewolf(player) || Curses.transformed(player) || !player.isAlive()) return false;
        CompoundTag t = Curses.tag(player);
        long now = player.level().getGameTime();
        if (!player.isCreative() && now < t.getLong(Curses.BEAST_READY)) {
            String hours = com.skycraft.SkyConfig.formatTicks(t.getLong(Curses.BEAST_READY) - now);
            Notifier.message(player, Component.translatable("message.skycraft.power_cooldown", CursePowers.name(CursePowers.BEAST_FORM), hours));
            return false;
        }
        int duration = DURATION;
        if (CursePerks.has(player, CursePerks.HIRCINES_FAVOR)) duration = duration * 3 / 2;
        t.putString(Curses.FORM, Form.BEAST.id);
        t.putLong(Curses.FORM_UNTIL, now + duration);
        t.putLong(Curses.BEAST_READY, now + com.skycraft.SkyConfig.POWER_COOLDOWN_MAX_SECONDS.get() * 20L);
        Curses.dirty(player);
        applyAttributes(player, true);
        Vampirism.refreshBonuses(player);
        player.setHealth(player.getMaxHealth());
        PlayerData data = SkyData.get(player);
        data.setStamina(data.maxStamina());
        player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, duration + 100, 0, false, false, false));
        data.addStat("beast_form", 1);
        CursePackets.sync(player);
        CursePackets.anim(player, CurseAnims.Anim.TRANSFORM);
        ServerLevel level = player.serverLevel();
        level.playSound(null, player.blockPosition(), SoundEvents.WOLF_HOWL, SoundSource.PLAYERS, 1.4f, 0.55f);
        level.playSound(null, player.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.PLAYERS, 0.6f, 1.3f);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, player.getX(), player.getY() + 1, player.getZ(), 10, 0.4, 0.6, 0.4, 0.01);
        Notifier.send(player, NotifyKind.MESSAGE, Component.translatable("curse.skycraft.beast.transformed"), Component.empty());
        if (Crimes.canCommitCrime(player)) Crimes.report(player, player.blockPosition(), TRANSFORM_BOUNTY, false, null);
        return true;
    }

    /** Back to the player's own form ({@code natural}: the time ran out, not death or logout). */
    static void revert(ServerPlayer player, boolean natural) {
        CompoundTag t = Curses.tag(player);
        if (Form.byId(t.getString(Curses.FORM)) != Form.BEAST) return;
        t.remove(Curses.FORM);
        t.remove(Curses.FORM_UNTIL);
        Curses.dirty(player);
        applyAttributes(player, false);
        Vampirism.refreshBonuses(player);
        player.removeEffect(MobEffects.NIGHT_VISION);
        CursePackets.sync(player);
        CurseEvents.noFall(player, 100);
        if (natural && player.isAlive()) {
            CursePackets.anim(player, CurseAnims.Anim.TRANSFORM);
            player.level().playSound(null, player.blockPosition(), SoundEvents.WOLF_WHINE, SoundSource.PLAYERS, 1f, 0.6f);
            Notifier.message(player, Component.translatable("curse.skycraft.beast.reverted"));
        }
    }

    static void applyAttributes(ServerPlayer player, boolean on) {
        modifier(player.getAttribute(Attributes.MOVEMENT_SPEED), SPEED, "Skycraft Beast Form speed", on ? 0.3 : 0,
                AttributeModifier.Operation.MULTIPLY_TOTAL);
        double armor = on ? (CursePerks.has(player, CursePerks.THICK_HIDE) ? 10 : 6) : 0;
        modifier(player.getAttribute(Attributes.ARMOR), ARMOR, "Skycraft Beast Form hide", armor, AttributeModifier.Operation.ADDITION);
        modifier(player.getAttribute(Attributes.KNOCKBACK_RESISTANCE), KNOCKBACK, "Skycraft Beast Form", on ? 0.5 : 0,
                AttributeModifier.Operation.ADDITION);
    }

    static void modifier(AttributeInstance inst, UUID id, String name, double amount, AttributeModifier.Operation op) {
        if (inst == null) return;
        AttributeModifier existing = inst.getModifier(id);
        if (existing != null && existing.getAmount() == amount) return;
        if (existing != null) inst.removeModifier(id);
        if (amount != 0) inst.addTransientModifier(new AttributeModifier(id, name, amount, op));
    }

    /** Once a second while transformed. */
    static void tick(ServerPlayer player, long now) {
        CompoundTag t = Curses.tag(player);
        if (now >= t.getLong(Curses.FORM_UNTIL)) {
            revert(player, true);
            return;
        }
        // transient modifiers don't survive a dimension change or respawn: keep them in place
        applyAttributes(player, true);
        if (player.tickCount % 200 == 0 && player.getRandom().nextInt(3) == 0) {
            player.level().playSound(null, player.blockPosition(), SoundEvents.WOLF_GROWL, SoundSource.PLAYERS, 0.8f, 0.6f);
        }
    }

    // ------------------------------------------------------------------ feeding

    /**
     * Feeds on a corpse: more time in Beast Form, health back. Only people (and, with Savage Feeding, beasts) will do;
     * each body only once.
     */
    static boolean feed(ServerPlayer player, CorpseEntity corpse) {
        if (Curses.form(player) != Form.BEAST || corpse.isRemoved() || player.distanceToSqr(corpse) > 4 * 4) return false;
        if (corpse.getPersistentData().getBoolean(FED_TAG)) {
            Notifier.message(player, Component.translatable("curse.skycraft.beast.already_fed"));
            return true;
        }
        String bodyType = corpse.saveWithoutId(new CompoundTag()).getString("BodyType");
        Entity probe = EntityType.byString(bodyType).map(type -> type == EntityType.PLAYER ? null : type.create(player.level())).orElse(null);
        boolean person = "minecraft:player".equals(bodyType) || CurseCombat.isPerson(probe);
        boolean undead = probe != null && CurseCombat.isUndead(probe);
        if (probe != null) probe.discard();
        if (undead || !person && !CursePerks.has(player, CursePerks.SAVAGE_FEEDING)) {
            Notifier.message(player, Component.translatable("curse.skycraft.beast.cannot_feed"));
            return true;
        }
        corpse.getPersistentData().putBoolean(FED_TAG, true);
        CompoundTag t = Curses.tag(player);
        t.putLong(Curses.FORM_UNTIL, t.getLong(Curses.FORM_UNTIL) + FEED_EXTENSION);
        float heal = player.getMaxHealth() * (CursePerks.has(player, CursePerks.GORGING) ? 0.5f : 0.25f);
        player.heal(heal);
        player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, (int) (t.getLong(Curses.FORM_UNTIL) - player.level().getGameTime()) + 100, 0, false, false, false));
        CursePerks.addFeed(player, CursePerks.Tree.WEREWOLF);
        Curses.dirty(player);
        CursePackets.anim(player, CurseAnims.Anim.FEED);
        ServerLevel level = player.serverLevel();
        level.playSound(null, corpse.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.PLAYERS, 1f, 0.6f);
        level.playSound(null, corpse.blockPosition(), SoundEvents.WOLF_GROWL, SoundSource.PLAYERS, 0.8f, 0.5f);
        level.sendParticles(new DustParticleOptions(new Vector3f(0.5f, 0.03f, 0.03f), 1.3f), corpse.getX(), corpse.getY() + 0.4,
                corpse.getZ(), 24, 0.35, 0.15, 0.35, 0.02);
        Notifier.message(player, Component.translatable("curse.skycraft.beast.fed"));
        // feeding on a person in front of witnesses: on top of everything else, a crime
        if (person && Crimes.canCommitCrime(player)) Crimes.report(player, corpse.blockPosition(), Bounty.ASSAULT, false, null);
        return true;
    }
}
