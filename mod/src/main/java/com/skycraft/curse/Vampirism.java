package com.skycraft.curse;

import com.skycraft.atmosphere.weather.RegionalWeather;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.Race;
import com.skycraft.core.SkyData;
import com.skycraft.crime.Bounty;
import com.skycraft.crime.Crimes;
import com.skycraft.network.NotifyKind;
import com.skycraft.vitals.Vitals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Skyrim's vampirism. Sanguinare Vampiris (from vampires and death hounds) turns its carrier after three days unless
 * cured. A vampire's hunger grows a stage every day without feeding (1 Sated .. 4 Blood-Starved): each stage takes
 * more Health, Magicka and Stamina in direct sunlight (where nothing regenerates), makes fire hurt more and frost
 * less, and unlocks stronger powers; at stage 4 townsfolk recognise what you are. Vampires never catch diseases and
 * shrug off poison. Feeding (on a sleeping person, or as a Vampire Lord) brings the stage back to 1.
 */
public final class Vampirism {
    public static final int DAY = 24000;
    /** Sanguinare Vampiris turns its carrier after three days. */
    public static final int INCUBATION = 3 * DAY;
    /** Health/Magicka/Stamina lost in sunlight, per stage. */
    public static final int SUN_PENALTY = 15;
    /** Chance per hit that a vampire's (or death hound's) attack passes on Sanguinare Vampiris. */
    public static final float INFECTION_CHANCE = 0.1f;

    private static final String APPLIED = "applied_bonus";
    private static final String[] POOLS = {"health", "magicka", "stamina"};
    /** Server: players standing in direct sunlight (refreshed once a second). */
    private static final Set<UUID> IN_SUN = new HashSet<>();

    private Vampirism() {}

    // ------------------------------------------------------------------ stages

    /** The stage after {@code sinceFed} ticks without feeding. */
    public static int stageFor(long sinceFed) {
        if (sinceFed < 0) return 1;
        return (int) Math.min(4, 1 + sinceFed / DAY);
    }

    public static Component stageName(int stage) {
        return Component.translatable("curse.skycraft.stage." + Math.max(1, Math.min(4, stage)));
    }

    /** Extra damage taken from fire: 25% per stage. */
    public static float fireWeakness(int stage) {
        return 0.25f * stage;
    }

    /** Frost resistance: 20% per stage. */
    public static float frostResistance(int stage) {
        return 0.2f * stage;
    }

    // ------------------------------------------------------------------ the sun

    /** Whether the player stands in direct sunlight right now (server: cached each second; client: synced flag). */
    public static boolean inSun(Player player) {
        if (player.level().isClientSide()) return (Curses.ClientView.flags(player) & CursePackets.Sync.IN_SUN) != 0;
        return IN_SUN.contains(player.getUUID());
    }

    /** Daytime, overworld, open sky above the eyes, and no storm thick enough to hide the sun. */
    static boolean computeSun(ServerPlayer player) {
        Level level = player.level();
        if (level.dimension() != Level.OVERWORLD || player.isSpectator()) return false;
        long t = level.getOverworldClockTime() % DAY;
        if (t > 12600 && t < 23400) return false;
        BlockPos eye = BlockPos.containing(player.getX(), player.getEyeY(), player.getZ());
        if (!level.canSeeSky(eye)) return false;
        return RegionalWeather.kindAt(player.level(), eye).clouds < 0.9f;
    }

    static void forget(UUID player) {
        IN_SUN.remove(player);
    }

    // ------------------------------------------------------------------ Sanguinare Vampiris

    static boolean infect(ServerPlayer player) {
        if (player.isCreative() || player.isSpectator()) return false;
        CompoundTag t = Curses.tag(player);
        if (t.getBooleanOr(Curses.VAMPIRE, false) || t.getBooleanOr(Curses.WEREWOLF, false) || t.getLongOr(Curses.SANGUINARE, 0L) > 0) return false;
        // Argonians and Bosmer resist disease (as in Skyrim's disease resistance)
        Race race = SkyData.get(player).getRace();
        if ((race == Race.ARGONIAN || race == Race.BOSMER) && player.getRandom().nextBoolean()) return false;
        t.putLong(Curses.SANGUINARE, Math.max(1, player.level().getGameTime()));
        t.putInt(Curses.SANG_DAYS, 0);
        player.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(CurseRegistry.SANGUINARE.get()), MobEffectInstance.INFINITE_DURATION, 0, false, false, true));
        Curses.dirty(player);
        Notifier.message(player, Component.translatable("message.skycraft.survival.contracted", CurseRegistry.SANGUINARE.get().getDisplayName()));
        return true;
    }

    /** Turns the player into a vampire at stage 1. Beast blood and the vampire's hunger don't mix: it is cured. */
    static void turn(ServerPlayer player) {
        CompoundTag t = Curses.tag(player);
        if (t.getBooleanOr(Curses.WEREWOLF, false)) Curses.cure(player, CurseType.LYCANTHROPY, false);
        Curses.cureSanguinare(player);
        t.putBoolean(Curses.VAMPIRE, true);
        t.putLong(Curses.FED, player.level().getGameTime());
        t.putInt(Curses.STAGE_SEEN, 1);
        Curses.dirty(player);
        refreshBonuses(player);
        CursePackets.sync(player);
        Notifier.title(player, Component.translatable("curse.skycraft.vampire.turned"), Component.translatable("curse.skycraft.vampire.turned.sub"));
        player.level().playSound(null, player.blockPosition(), SoundEvents.ZOMBIE_VILLAGER_CONVERTED, SoundSource.PLAYERS, 0.8f, 0.6f);
    }

    /** A feeding: the hunger falls back to stage 1, wounds close. */
    public static void fed(ServerPlayer player) {
        CompoundTag t = Curses.tag(player);
        if (!t.getBooleanOr(Curses.VAMPIRE, false)) return;
        int before = Curses.stage(player);
        t.putLong(Curses.FED, player.level().getGameTime());
        t.putInt(Curses.STAGE_SEEN, 1);
        Curses.dirty(player);
        SkyData.get(player).addStat("vampire_feedings", 1);
        player.heal(player.getMaxHealth() * 0.25f);
        refreshBonuses(player);
        CursePackets.sync(player);
        if (before > 1) Notifier.message(player, Component.translatable("curse.skycraft.vampire.sated", stageName(1)));
    }

    // ------------------------------------------------------------------ feeding on sleepers

    /** Feeds on a sleeping person (use key, not sneaking). Returns whether it happened. */
    static boolean feedOnSleeper(ServerPlayer player, LivingEntity victim) {
        if (!Curses.isVampire(player) || Curses.transformed(player) || !victim.isSleeping() || !victim.isAlive()) return false;
        if (player.distanceToSqr(victim) > 3.5 * 3.5) return false;
        ServerLevel level = player.level();
        level.playSound(null, victim.blockPosition(), SoundEvents.HONEY_DRINK.value(), SoundSource.PLAYERS, 0.9f, 0.6f);
        level.sendParticles(new DustParticleOptions(net.minecraft.util.ARGB.colorFromFloat(1f, 0.55f, 0.02f, 0.05f), 1.0f), victim.getX(), victim.getY() + 0.5,
                victim.getZ(), 14, 0.25, 0.15, 0.25, 0.01);
        // the victim wakes up weak in the morning, none the wiser
        victim.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.WEAKNESS), 20 * 60 * 5, 0));
        CursePackets.anim(player, CurseAnims.Anim.FEED);
        fed(player);
        Notifier.message(player, Component.translatable("curse.skycraft.vampire.fed"));
        // feeding in front of witnesses is a crime (the sleeper doesn't see it)
        if (Crimes.isCivilian(victim)) Crimes.report(player, victim.blockPosition(), Bounty.ASSAULT, false, victim);
        return true;
    }

    // ------------------------------------------------------------------ ticking (once a second)

    static void tick(ServerPlayer player, long now) {
        CompoundTag t = Curses.tag(player);
        // Sanguinare Vampiris runs its course
        long sang = t.getLongOr(Curses.SANGUINARE, 0L);
        if (sang > 0) {
            if (!player.hasEffect(com.skycraft.platform.Effects.holder(CurseRegistry.SANGUINARE.get()))) {
                // cured by a potion, a shrine or death (those remove the effect)
                t.remove(Curses.SANGUINARE);
                t.remove(Curses.SANG_DAYS);
                Curses.dirty(player);
            } else if (now - sang >= INCUBATION) {
                turn(player);
            } else {
                int days = (int) ((now - sang) / DAY);
                if (days > t.getIntOr(Curses.SANG_DAYS, 0)) {
                    t.putInt(Curses.SANG_DAYS, days);
                    Curses.dirty(player);
                    Notifier.message(player, Component.translatable("curse.skycraft.sanguinare.day" + Math.min(2, days)));
                }
            }
        }

        boolean vampire = t.getBooleanOr(Curses.VAMPIRE, false);
        boolean sun = vampire && computeSun(player);
        boolean wasSun = IN_SUN.contains(player.getUUID());
        if (sun != wasSun) {
            if (sun) IN_SUN.add(player.getUUID());
            else IN_SUN.remove(player.getUUID());
            refreshBonuses(player);
            CursePackets.sync(player);
            if (sun) {
                player.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(CurseRegistry.SUN_DAMAGE.get()), MobEffectInstance.INFINITE_DURATION, 0, false, false, true));
            } else {
                player.removeEffect(com.skycraft.platform.Effects.holder(CurseRegistry.SUN_DAMAGE.get()));
            }
        }
        if (!vampire) return;

        int stage = Curses.stage(player);
        if (stage != t.getIntOr(Curses.STAGE_SEEN, 0)) {
            int previous = t.getIntOr(Curses.STAGE_SEEN, 0);
            t.putInt(Curses.STAGE_SEEN, stage);
            Curses.dirty(player);
            refreshBonuses(player);
            CursePackets.sync(player);
            if (stage > previous) {
                Notifier.send(player, NotifyKind.MESSAGE, Component.translatable("curse.skycraft.vampire.hunger", stageName(stage)), Component.empty());
                if (stage == 4) Notifier.message(player, Component.translatable("curse.skycraft.vampire.stage4"));
                player.level().playSound(null, player.blockPosition(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 1f, 0.8f);
            }
        }
        if (sun && player.tickCount % 40 < 20) {
            player.level().sendParticles(ParticleTypes.SMOKE, player.getX(), player.getY() + 1.2, player.getZ(), 3 + stage, 0.25, 0.4, 0.25, 0.005);
        }
        // Vampire's Sight is a toggle: keep the night eye topped up while it's on
        if (t.getBooleanOr(Curses.SIGHT, false)) {
            MobEffectInstance nv = player.getEffect(com.skycraft.platform.Effects.holder(MobEffects.NIGHT_VISION));
            if (nv == null || nv.getDuration() < 300) player.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.NIGHT_VISION), 20 * 30, 0, false, false, false));
        }
    }

    /** Removes the vampire's lasting effects (on cure). */
    static void clearEffects(ServerPlayer player) {
        player.removeEffect(com.skycraft.platform.Effects.holder(CurseRegistry.SUN_DAMAGE.get()));
        IN_SUN.remove(player.getUUID());
    }

    // ------------------------------------------------------------------ pools (sun penalty and the beast forms)

    /**
     * Keeps this module's share of the core's {@code module("bonus")} Health/Magicka/Stamina equal to what the sun and
     * the current form call for. Only the difference to what was applied before is added, so other modules' bonuses
     * in the same keys are left alone (same pattern as survival and the standing stones).
     */
    public static void refreshBonuses(ServerPlayer player) {
        CompoundTag t = Curses.tag(player);
        float[] want = new float[3];
        if (t.getBooleanOr(Curses.VAMPIRE, false) && IN_SUN.contains(player.getUUID())) {
            float pen = SUN_PENALTY * Curses.stage(player);
            for (int i = 0; i < 3; i++) want[i] -= pen;
        }
        Form form = Form.byId(t.getStringOr(Curses.FORM, ""));
        if (form == Form.BEAST) {
            want[0] += 50;
            want[2] += 50;
            if (CursePerks.has(player, CursePerks.ANIMAL_VIGOR)) {
                want[0] += 50;
                want[2] += 50;
            }
        } else if (form == Form.LORD) {
            want[0] += 50;
            want[1] += 50;
            if (CursePerks.has(player, CursePerks.POWER_OF_THE_GRAVE)) {
                for (int i = 0; i < 3; i++) want[i] += 50;
            }
        }
        PlayerData data = SkyData.get(player);
        CompoundTag mine = t.getCompoundOrEmpty(APPLIED);
        CompoundTag bonus = data.module("bonus");
        boolean changed = false;
        for (int i = 0; i < 3; i++) {
            float diff = want[i] - mine.getFloatOr(POOLS[i], 0f);
            if (diff == 0f) continue;
            bonus.putFloat(POOLS[i], bonus.getFloatOr(POOLS[i], 0f) + diff);
            mine.putFloat(POOLS[i], want[i]);
            changed = true;
        }
        if (!changed) return;
        t.put(APPLIED, mine);
        data.markDirty();
        data.markVitalsDirty();
        Vitals.refreshAttributes(player);
    }

    // ------------------------------------------------------------------ damage taken

    /** Multiplier on damage a vampire takes: fire hurts more, frost less, poison not at all. */
    static float damageTaken(ServerPlayer player, DamageSource source) {
        int stage = Curses.stage(player);
        if (stage <= 0) return 1f;
        if (source.is(DamageTypeTags.IS_FIRE)) return 1f + fireWeakness(stage);
        if (source.is(DamageTypeTags.IS_FREEZING)) return 1f - frostResistance(stage);
        return 1f;
    }
}
