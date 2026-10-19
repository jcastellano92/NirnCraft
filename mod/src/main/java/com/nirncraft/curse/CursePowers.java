package com.nirncraft.curse;

import com.nirncraft.core.Notifier;
import com.nirncraft.core.PlayerData;
import com.nirncraft.magic.spell.Summons;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The curses' powers in the magic menu's Powers tab and the Voice slot ({@code "power:curse:<id>"}): Beast Form,
 * the Vampire Lord, Vampire's Sight, Vampire's Servant and Embrace of Shadows. While transformed, the Voice key
 * instead uses the form's own selected ability (a howl, or a Vampire Lord power); sneak + Voice reverts.
 */
public final class CursePowers {
    public static final String PREFIX = "power:curse:";

    public static final String BEAST_FORM = "beast_form";
    public static final String VAMPIRE_LORD = "vampire_lord";
    public static final String VAMPIRES_SIGHT = "vampires_sight";
    public static final String VAMPIRES_SERVANT = "vampires_servant";
    public static final String EMBRACE_OF_SHADOWS = "embrace_of_shadows";

    // ------------------------------------------------------------------ form abilities (chosen in the form's menu)
    public static final String HOWL_OF_TERROR = "howl_of_terror";
    public static final String HOWL_ICE_BROTHERS = "totem_of_ice_brothers";
    public static final String HOWL_MOON = "totem_of_the_moon";
    public static final String HOWL_PREDATOR = "totem_of_the_predator";
    public static final List<String> HOWLS = List.of(HOWL_OF_TERROR, HOWL_ICE_BROTHERS, HOWL_MOON, HOWL_PREDATOR);

    /** Vampire Lord spells, cast with the use key while levitating (Drain Life is always on the attack key). */
    public static final String RAISE_DEAD = "raise_dead";
    public static final String VAMPIRIC_GRIP = "vampiric_grip";
    public static final String CORPSE_CURSE = "corpse_curse";
    public static final List<String> LORD_SPELLS = List.of(RAISE_DEAD, VAMPIRIC_GRIP, CORPSE_CURSE);

    /** Vampire Lord powers, on the Voice key. */
    public static final String BATS = "bats";
    public static final String MIST_FORM = "mist_form";
    public static final String SUPERNATURAL_REFLEXES = "supernatural_reflexes";
    public static final String DETECT_LIFE = "detect_life";
    public static final String SUMMON_GARGOYLE = "summon_gargoyle";
    public static final String NIGHT_CLOAK = "night_cloak";
    public static final String REVERT = "revert_form";
    public static final List<String> LORD_POWERS = List.of(BATS, MIST_FORM, SUPERNATURAL_REFLEXES, DETECT_LIFE, SUMMON_GARGOYLE, NIGHT_CLOAK, REVERT);

    private static final int DAY = 24000;

    private CursePowers() {}

    // ------------------------------------------------------------------ which powers a player has (both sides)

    /** The curse powers for the Powers tab, in display order. */
    public static List<String> powers(Player player) {
        List<String> out = new ArrayList<>();
        CompoundTag t = Curses.tag(player);
        if (t.getBooleanOr(Curses.WEREWOLF, false)) out.add(BEAST_FORM);
        if (t.getBooleanOr(Curses.VAMPIRE, false)) {
            if (t.getBooleanOr(Curses.LORD, false)) out.add(VAMPIRE_LORD);
            out.add(VAMPIRES_SIGHT);
            out.add(VAMPIRES_SERVANT);
            if (Curses.stage(player) >= 3) out.add(EMBRACE_OF_SHADOWS);
        }
        return out;
    }

    public static boolean has(Player player, String id) {
        return powers(player).contains(id);
    }

    /** Whether a form ability (howl, Vampire Lord spell or power) is available: base ones always, the rest by perk. */
    public static boolean hasAbility(Player player, String id) {
        String perk = switch (id) {
            case HOWL_ICE_BROTHERS -> CursePerks.TOTEM_OF_ICE_BROTHERS;
            case HOWL_MOON -> CursePerks.TOTEM_OF_THE_MOON;
            case HOWL_PREDATOR -> CursePerks.TOTEM_OF_THE_PREDATOR;
            case VAMPIRIC_GRIP -> CursePerks.VAMPIRIC_GRIP;
            case CORPSE_CURSE -> CursePerks.CORPSE_CURSE;
            case MIST_FORM -> CursePerks.MIST_FORM;
            case SUPERNATURAL_REFLEXES -> CursePerks.SUPERNATURAL_REFLEXES;
            case DETECT_LIFE -> CursePerks.DETECT_LIFE;
            case SUMMON_GARGOYLE -> CursePerks.SUMMON_GARGOYLE;
            case NIGHT_CLOAK -> CursePerks.NIGHT_CLOAK;
            default -> null;
        };
        return perk == null || CursePerks.has(player, perk);
    }

    public static Component name(String id) {
        return Component.translatable("curse.nirncraft.power." + id);
    }

    public static Component description(String id) {
        return Component.translatable("curse.nirncraft.power." + id + ".desc");
    }

    /** Lesser powers recharge in moments; greater powers once a day. */
    public static boolean greater(String id) {
        return id.equals(BEAST_FORM) || id.equals(VAMPIRES_SERVANT) || id.equals(EMBRACE_OF_SHADOWS);
    }

    /** Recharge time of a power or form ability, in ticks. */
    public static long cooldown(String id) {
        return switch (id) {
            case BEAST_FORM, VAMPIRES_SERVANT, EMBRACE_OF_SHADOWS -> com.nirncraft.SkyConfig.POWER_COOLDOWN_MAX_SECONDS.get() * 20L;
            case VAMPIRE_LORD -> 20 * 10;
            case VAMPIRES_SIGHT -> 20;
            case HOWL_OF_TERROR, HOWL_PREDATOR -> 20 * 20;
            case HOWL_ICE_BROTHERS, HOWL_MOON -> 20 * 60;
            case BATS -> 20 * 3;
            case MIST_FORM, SUPERNATURAL_REFLEXES, NIGHT_CLOAK -> 20 * 60;
            case DETECT_LIFE -> 20 * 30;
            case SUMMON_GARGOYLE -> 20 * 120;
            default -> 0;
        };
    }

    /** Game time at which the power is ready again. */
    public static long readyAt(Player player, String id) {
        if (id.equals(BEAST_FORM)) return Curses.tag(player).getLongOr(Curses.BEAST_READY, 0L);
        return Curses.tag(player).getCompoundOrEmpty(Curses.COOLDOWNS).getLongOr(id, 0L);
    }

    public static long readyAt(PlayerData data, String id) {
        CompoundTag t = data.module(Curses.MODULE);
        if (id.equals(BEAST_FORM)) return t.getLongOr(Curses.BEAST_READY, 0L);
        return t.getCompoundOrEmpty(Curses.COOLDOWNS).getLongOr(id, 0L);
    }

    static boolean ready(ServerPlayer player, String id) {
        long now = player.level().getGameTime();
        long at = readyAt(player, id);
        if (player.isCreative() || now >= at) return true;
        long left = at - now;
        if (left > 2000) Notifier.message(player, Component.translatable("message.nirncraft.power_cooldown", name(id), com.nirncraft.SkyConfig.formatTicks(left)));
        else Notifier.message(player, Component.translatable("curse.nirncraft.power.recharging", name(id), left / 20 + 1));
        return false;
    }

    static void startCooldown(ServerPlayer player, String id) {
        if (id.equals(BEAST_FORM)) return; // Lycanthropy stores its own
        CompoundTag t = Curses.tag(player);
        CompoundTag cd = t.getCompoundOrEmpty(Curses.COOLDOWNS);
        cd.putLong(id, player.level().getGameTime() + cooldown(id));
        t.put(Curses.COOLDOWNS, cd);
        Curses.dirty(player);
    }

    // ------------------------------------------------------------------ the Voice key

    /**
     * Magic's Voice handler (the Z key) calls this first. Transformed: the form's ability, or sneak to revert.
     * Otherwise a selected curse power. Returns true if the key press was handled here.
     */
    public static boolean onVoice(ServerPlayer player, String voice) {
        Form form = Curses.form(player);
        if (form != Form.NONE) {
            if (player.isShiftKeyDown()) {
                if (form == Form.BEAST) Lycanthropy.revert(player, true);
                else VampireLord.revert(player, true);
                return true;
            }
            if (form == Form.BEAST) Howls.howl(player, selected(player, Curses.SELECTED_HOWL, HOWL_OF_TERROR));
            else VampireLord.power(player, selected(player, Curses.SELECTED_POWER, BATS));
            return true;
        }
        if (voice.startsWith(PREFIX)) {
            use(player, voice.substring(PREFIX.length()));
            return true;
        }
        return false;
    }

    static String selected(Player player, String key, String fallback) {
        String id = Curses.tag(player).getStringOr(key, "");
        return id.isEmpty() || !hasAbility(player, id) ? fallback : id;
    }

    /** Uses a curse power from the Voice slot. */
    public static void use(ServerPlayer player, String id) {
        if (!has(player, id) || !player.isAlive()) return;
        if (!ready(player, id)) return;
        boolean used = switch (id) {
            case BEAST_FORM -> Lycanthropy.transform(player);
            case VAMPIRE_LORD -> VampireLord.transform(player);
            case VAMPIRES_SIGHT -> toggleSight(player);
            case VAMPIRES_SERVANT -> servant(player);
            case EMBRACE_OF_SHADOWS -> embrace(player);
            default -> false;
        };
        if (used) startCooldown(player, id);
    }

    private static boolean toggleSight(ServerPlayer player) {
        CompoundTag t = Curses.tag(player);
        boolean on = !t.getBooleanOr(Curses.SIGHT, false);
        if (on) {
            t.putBoolean(Curses.SIGHT, true);
            player.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.NIGHT_VISION), 20 * 30, 0, false, false, false));
        } else {
            t.remove(Curses.SIGHT);
            player.removeEffect(com.nirncraft.platform.Effects.holder(MobEffects.NIGHT_VISION));
        }
        Curses.dirty(player);
        CursePackets.sync(player);
        player.level().playSound(null, player.blockPosition(), SoundEvents.ENDER_EYE_DEATH, SoundSource.PLAYERS, 0.5f, on ? 1.4f : 0.8f);
        Notifier.message(player, Component.translatable(on ? "curse.nirncraft.sight.on" : "curse.nirncraft.sight.off"));
        return true;
    }

    /** Vampire's Servant: a dead thrall for a minute, stronger the hungrier the vampire. */
    private static boolean servant(ServerPlayer player) {
        int stage = Math.max(1, Curses.stage(player));
        Mob thrall = Summons.conjureAlly(player, "revenant", "vampires_servant", 10, 20 * 60, mob -> {
            AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(hp.getBaseValue() * (1 + 0.25 * stage));
            AttributeInstance atk = mob.getAttribute(Attributes.ATTACK_DAMAGE);
            if (atk != null) atk.setBaseValue(atk.getBaseValue() * (1 + 0.2 * stage));
            mob.setHealth(mob.getMaxHealth());
            mob.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.FIRE_RESISTANCE), 20 * 60, 0, false, false));
        });
        if (thrall == null) return false;
        player.level().playSound(null, thrall.blockPosition(), SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.PLAYERS, 0.8f, 0.5f);
        return true;
    }

    /** Embrace of Shadows: invisible, seeing in the dark; longer at stage 4. */
    private static boolean embrace(ServerPlayer player) {
        int secs = Curses.stage(player) >= 4 ? 240 : 180;
        player.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.INVISIBILITY), 20 * secs, 0, false, false, true));
        player.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.NIGHT_VISION), 20 * secs, 0, false, false, false));
        player.level().playSound(null, player.blockPosition(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 0.8f, 0.6f);
        CursePackets.anim(player, CurseAnims.Anim.CAST);
        return true;
    }

    /** Chooses a form ability (from the form menu). */
    static void select(ServerPlayer player, String id) {
        if (!hasAbility(player, id)) return;
        String key = HOWLS.contains(id) ? Curses.SELECTED_HOWL : LORD_SPELLS.contains(id) ? Curses.SELECTED_SPELL
                : LORD_POWERS.contains(id) ? Curses.SELECTED_POWER : null;
        if (key == null) return;
        Curses.tag(player).putString(key, id);
        Curses.dirty(player);
    }
}
