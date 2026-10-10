package com.skycraft.combat;

import com.skycraft.Skycraft;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.perk.Perks;
import com.skycraft.skills.Progression;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.PolarBear;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Sneaking and detection in one place: how visible a crouching player is, whether a mob may notice (acquire) a
 * sneaking player, when a chasing mob loses them, the HUD "detected" eye, Sneak training and Hunting's Beast Lore.
 * One mob scan per player feeds the losing-track check, the detected flag and the training.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Stealth {
    /** Radius of the per-player mob scan. */
    private static final double SCAN_RADIUS = 24;
    /** Enemies within this range train Sneak while you stay hidden. */
    private static final double TRAIN_RADIUS = 12;
    /** A mob the player hurt this recently (ticks) always knows where they are. */
    private static final int PROVOKED_TICKS = 200;

    private Stealth() {}

    // ------------------------------------------------------------------ detection model

    /** The player hurt this mob in the last ~10 seconds: it is aware of them no matter what. */
    public static boolean provoked(LivingEntity mob, Player player) {
        return mob.getLastHurtByMob() == player && mob.tickCount - mob.getLastHurtByMobTimestamp() < PROVOKED_TICKS;
    }

    /** Distance at which a mob with line of sight notices a sneaking player (shorter in the dark and with skill). */
    private static double noticeRange(Player player, PlayerData data) {
        int light = player.level().getMaxLocalRawBrightness(player.blockPosition());
        return Math.max(3.0, 16.0 - data.getSkill(Skill.SNEAK) * 0.12 - (light < 8 ? 6.0 : 0.0));
    }

    /** Whether a mob may start targeting a sneaking player. */
    private static boolean canNotice(Mob mob, Player player, PlayerData data) {
        if (provoked(mob, player)) return true;
        if (!mob.hasLineOfSight(player)) return false;
        return mob.distanceTo(player) <= noticeRange(player, data);
    }

    /** Whether a mob already chasing a sneaking player keeps track of them (more forgiving than noticing). */
    private static boolean keepsTrack(Mob mob, Player player, PlayerData data) {
        if (provoked(mob, player)) return true;
        boolean los = mob.hasLineOfSight(player);
        double dist = mob.distanceTo(player);
        if (!los) return dist <= 5.0;
        int light = player.level().getMaxLocalRawBrightness(player.blockPosition());
        return light >= 7 || dist <= Math.max(4.0, 14.0 - data.getSkill(Skill.SNEAK) * 0.1);
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void onVisibility(LivingEvent.LivingVisibilityEvent event) {
        if (!(event.getEntity() instanceof Player player) || !player.isCrouching()) return;
        PlayerData data = SkyData.get(player);
        double mult = 0.8 - data.getSkill(Skill.SNEAK) * 0.004 - 0.05 * Perks.rank(data, "sneak.stealth");
        int heavy = ArmorClass.countHeavy(player);
        if (heavy > 0 && !Perks.has(data, "sneak.muffled_movement")) mult += 0.05 * heavy;
        if (Perks.has(data, "sneak.silence")) mult -= 0.1;
        if (Perks.has(data, "sneak.shadow_warrior")) mult *= 0.75;
        event.modifyVisibility(Math.max(0.1, Math.min(1.0, mult)));
    }

    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent event) {
        if (!(event.getNewTarget() instanceof Player player) || player.level().isClientSide) return;
        LivingEntity entity = event.getEntity();
        PlayerData data = SkyData.get(player);
        // Hunting "Beast Lore": wild predators leave you alone unless you attack them first.
        if (Perks.has(data, "hunting.beast_lore")) {
            boolean beast = entity instanceof Animal || entity instanceof Wolf || entity instanceof PolarBear;
            if (beast && entity.getLastHurtByMob() != player) {
                event.setCanceled(true);
                return;
            }
        }
        if (player.isCrouching() && entity instanceof Mob mob && !canNotice(mob, player, data)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        boolean crouching = player.isCrouching();
        // Crouching: every 10 ticks (losing track must feel responsive). Otherwise only the HUD flag, every 20.
        if (player.tickCount % (crouching ? 10 : 20) != 0) return;
        PlayerData data = SkyData.get(player);
        boolean train = crouching && player.tickCount % 20 == 0;
        boolean detected = false;
        boolean nearHiddenFrom = false;
        double trainSq = TRAIN_RADIUS * TRAIN_RADIUS;
        for (Mob mob : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(SCAN_RADIUS), Mob::isAlive)) {
            boolean enemy = mob instanceof Enemy;
            if (mob.getTarget() == player) {
                if (crouching && !keepsTrack(mob, player, data)) {
                    mob.setTarget(null);
                    mob.getNavigation().stop();
                } else if (enemy) {
                    detected = true;
                }
            } else if (train && enemy && mob.distanceToSqr(player) <= trainSq) {
                nearHiddenFrom = true;
            }
        }
        data.module("vitals").putBoolean("detected", detected);
        // Sneak trains while you are hidden near enemies.
        if (nearHiddenFrom) Progression.addSkillXp(player, Skill.SNEAK, 0.75f);
    }
}
