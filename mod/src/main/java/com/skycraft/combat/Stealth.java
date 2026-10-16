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

    /** Consecutive 10-tick checks a chasing mob has had no line of sight to its target. */
    private static final java.util.Map<java.util.UUID, Integer> UNSEEN_STREAK = new java.util.HashMap<>();

    /** Distance at which a mob notices a sneaking player (factors in eyesight, light, armor noise, and Sneak skill). */
    private static double noticeRange(Mob mob, Player player, PlayerData data) {
        int light = player.level().getMaxLocalRawBrightness(player.blockPosition());
        double follow = mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
        double eyeBonus = Math.max(0.0, (follow - 16.0) * 0.35);
        int heavy = ArmorClass.countHeavy(player);
        double noiseBonus = (heavy > 0 && !Perks.has(data, "sneak.muffled_movement")) ? heavy * 1.2 : 0.0;
        return Math.max(4.5, 18.0 + eyeBonus + noiseBonus - data.getSkill(Skill.SNEAK) * 0.11 - (light < 8 ? 5.0 : 0.0));
    }

    /** Whether a mob may start targeting a sneaking player (by sight or close-range sound). */
    private static boolean canNotice(Mob mob, Player player, PlayerData data) {
        if (provoked(mob, player)) return true;
        double dist = mob.distanceTo(player);
        int heavy = ArmorClass.countHeavy(player);
        double hearDist = Perks.has(data, "sneak.silence") ? 2.0 : (3.5 + (heavy > 0 && !Perks.has(data, "sneak.muffled_movement") ? heavy * 1.0 : 0.0));
        if (!mob.hasLineOfSight(player)) {
            return dist <= hearDist;
        }
        return dist <= noticeRange(mob, player, data);
    }

    /** Whether a mob already chasing a sneaking player keeps track of them (requires sustained concealment to break chase). */
    private static boolean keepsTrack(Mob mob, Player player, PlayerData data) {
        if (provoked(mob, player)) {
            UNSEEN_STREAK.remove(mob.getUUID());
            return true;
        }
        boolean los = mob.hasLineOfSight(player);
        double dist = mob.distanceTo(player);
        if (!los) {
            if (dist <= 9.0) return true; // close enough to hear footsteps/breathing
            int streak = UNSEEN_STREAK.getOrDefault(mob.getUUID(), 0) + 1;
            if (UNSEEN_STREAK.size() > 512) UNSEEN_STREAK.clear();
            UNSEEN_STREAK.put(mob.getUUID(), streak);
            // Require at least ~4 seconds (4 checks) out of line-of-sight before losing track
            return streak < 4;
        }
        UNSEEN_STREAK.remove(mob.getUUID());
        int light = player.level().getMaxLocalRawBrightness(player.blockPosition());
        return light >= 7 || dist <= Math.max(7.0, 18.0 - data.getSkill(Skill.SNEAK) * 0.1);
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
            return;
        }
        // remember the post a hostile leaves when it starts a chase, so it can walk back after losing the player
        if (entity instanceof Mob mob && mob instanceof Enemy && !mob.hasRestriction() && !SEARCHES.containsKey(mob.getUUID())
                && !mob.getPersistentData().contains(POST_KEY)) {
            mob.getPersistentData().putLong(POST_KEY, mob.blockPosition().asLong());
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
                    // lost them: go to where they were last seen, look around, return to the post
                    beginSearch(mob, SEEN.getOrDefault(mob.getUUID(), player.position()), player.getUUID());
                } else {
                    if (SEEN.size() > 512) SEEN.clear();
                    if (mob.hasLineOfSight(player)) SEEN.put(mob.getUUID(), player.position());
                    if (enemy) detected = true;
                }
            } else if (train && enemy && mob.distanceToSqr(player) <= trainSq) {
                nearHiddenFrom = true;
            }
        }
        data.module("vitals").putBoolean("detected", detected);
        // Sneak trains while you are hidden near enemies.
        if (nearHiddenFrom) Progression.addSkillXp(player, Skill.SNEAK, 0.75f);
    }

    // ------------------------------------------------------------------ searching the last known position

    /** Persistent-data key: where a hostile stood before it started chasing (its post), as a packed block position. */
    private static final String POST_KEY = "skycraft_post";
    private static final java.util.Map<java.util.UUID, Search> SEARCHES = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, net.minecraft.world.phys.Vec3> SEEN = new java.util.HashMap<>();

    private static final class Search {
        final Mob mob;
        final net.minecraft.world.phys.Vec3 spot;
        /** The player it lost, or null when unknown (the search then counts for everyone nearby). */
        @org.jetbrains.annotations.Nullable
        java.util.UUID player;
        int phase; // 0 go to the last known spot, 1 look around, 2 return to the post
        long until;
        long started;

        Search(Mob mob, net.minecraft.world.phys.Vec3 spot, long now) {
            this.mob = mob;
            this.spot = spot;
            this.started = now;
        }
    }

    /** Sneaking out of sight: this mob would not notice the player right now. */
    public static boolean hiddenFrom(Mob mob, Player player) {
        return player.isCrouching() && !canNotice(mob, player, SkyData.get(player));
    }

    /**
     * A mob that lost its target (the player is hidden) stops chasing the live position: it walks to the last place
     * it saw them, looks around for a few seconds, then returns to its post. A new target interrupts the search.
     */
    public static void beginSearch(Mob mob, net.minecraft.world.phys.Vec3 spot) {
        beginSearch(mob, spot, null);
    }

    /** As {@link #beginSearch(Mob, net.minecraft.world.phys.Vec3)}, remembering the player it lost (for the compass). */
    public static void beginSearch(Mob mob, net.minecraft.world.phys.Vec3 spot, @org.jetbrains.annotations.Nullable java.util.UUID player) {
        if (mob.level().isClientSide) return;
        mob.setTarget(null);
        mob.getNavigation().stop();
        Search search = new Search(mob, spot, mob.level().getGameTime());
        search.player = player;
        SEARCHES.put(mob.getUUID(), search);
        SEEN.remove(mob.getUUID());
    }

    /**
     * Whether this mob lost the player and is looking for them: walking to the last place it saw them or looking
     * around there (not once it heads back to its post, and never while it has a target again).
     */
    public static boolean isSearchingFor(Mob mob, Player player) {
        Search s = SEARCHES.get(mob.getUUID());
        if (s == null || s.phase >= 2 || mob.getTarget() != null || !mob.isAlive()) return false;
        return s.player == null || s.player.equals(player.getUUID());
    }

    private static net.minecraft.world.phys.Vec3 postOf(Mob mob) {
        if (mob.hasRestriction()) return net.minecraft.world.phys.Vec3.atBottomCenterOf(mob.getRestrictCenter());
        if (!mob.getPersistentData().contains(POST_KEY)) return null;
        return net.minecraft.world.phys.Vec3.atBottomCenterOf(net.minecraft.core.BlockPos.of(mob.getPersistentData().getLong(POST_KEY)));
    }

    @SubscribeEvent
    public static void onServerStopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        SEARCHES.clear();
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.level.isClientSide || SEARCHES.isEmpty()) return;
        long now = event.level.getGameTime();
        if (now % 10 != 3) return;
        java.util.Iterator<Search> it = SEARCHES.values().iterator();
        while (it.hasNext()) {
            Search s = it.next();
            Mob mob = s.mob;
            if (mob.isRemoved() || !mob.isAlive()) { // before the level check: a removed mob in another level would leak
                it.remove();
                continue;
            }
            if (mob.level() != event.level) continue;
            if (mob.getTarget() != null) {
                it.remove();
                continue;
            }
            switch (s.phase) {
                case 0 -> {
                    if (mob.distanceToSqr(s.spot) < 6.0 || now - s.started > 160) {
                        s.phase = 1;
                        s.until = now + 60 + mob.getRandom().nextInt(60);
                        mob.getNavigation().stop();
                    } else if (mob.getNavigation().isDone()) {
                        mob.getNavigation().moveTo(s.spot.x, s.spot.y, s.spot.z, 1.0D);
                    }
                }
                case 1 -> {
                    if (now > s.until) {
                        s.phase = 2;
                        s.started = now;
                    }
                }
                default -> {
                    net.minecraft.world.phys.Vec3 post = postOf(mob);
                    if (post == null || mob.distanceToSqr(post) < 9.0 || now - s.started > 400) {
                        mob.getPersistentData().remove(POST_KEY);
                        mob.getNavigation().stop();
                        it.remove();
                    } else if (mob.getNavigation().isDone()) {
                        mob.getNavigation().moveTo(post.x, post.y, post.z, 0.9D);
                    }
                }
            }
        }
    }
}
