package com.skycraft.fauna;

import com.skycraft.core.Notifier;
import com.skycraft.core.Skill;
import com.skycraft.mounts.MountGear;
import com.skycraft.mounts.MountGrowth;
import com.skycraft.mounts.MountKind;
import com.skycraft.mounts.MountStats;
import com.skycraft.mounts.MountTaming;
import com.skycraft.mounts.SaddleType;
import com.skycraft.network.SkyNetwork;
import com.skycraft.skills.Progression;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Breaking in a wild mount (horses, camels, guar, senche-tigers, indriks, mammoths, realm grazers; see
 * {@link MountTaming#wildKind}) is a balance minigame:
 * <ol>
 *     <li>Sneak up with a saddle in hand (any {@link SaddleType} the breed accepts). A mount that is not calm spooks and
 *     bolts if you are not sneaking (one that has been fed and trusts you tolerates a walk), are sprinting, or have
 *     hurt it in the last five seconds. Only wild mounts: an owned mount is managed in its menu.</li>
 *     <li>Use the saddle: it is thrown on, you mount, and the animal bucks. A marker on the bar at the bottom of the
 *     screen is pushed left and right by the bucking, which grows in strength and speed the longer you hold on; you
 *     counter-steer with A and D to keep it in the safe zone while the camera sways with the animal.
 *     {@link TameBalance} is the physics; this class runs it on the server a tick at a time (the client sends only its
 *     steer input and draws the state it is sent).</li>
 *     <li>Keep the marker in the zone long enough (20 to 35 seconds by the kind's difficulty, shorter for a trusting
 *     animal) and it is yours ({@link MountTaming#complete}, wearing that saddle; HUNTING and ATHLETICS XP). Touch the
 *     edge for too long and you are thrown: the saddle comes back to your bags, as it does when the breaking is
 *     interrupted (logout, death, the beast dying, getting off).</li>
 * </ol>
 * While it is being broken the animal's AI is off ({@link #NOAI_TAG} guards against it staying off) so vanilla's own
 * rearing and rider-throwing, its temper and its steering cannot interfere, and the vanilla saddle is only put on at
 * the end (a saddled horse would be steered by its rider's own keys).
 */
public final class WildHorseTaming {
    /** Persistent-data flag: the animal's AI was switched off for a breaking (cleared when it ends, or when it loads). */
    public static final String NOAI_TAG = "skycraft_breaking";

    private static final Map<UUID, TamingSession> ACTIVE_SESSIONS = new ConcurrentHashMap<>();
    /** Steer packets a player may send in a second. */
    private static final int MAX_INPUTS = 30;

    public static class TamingSession {
        public final UUID playerId;
        public final int horseId;
        public final MountKind kind;
        public final int difficulty;
        /** The saddle thrown on (its item was used up; it is returned when the taming fails). */
        public final SaddleType saddle;
        public final TameBalance balance;
        public boolean refunded;
        public int steer;
        long windowStart;
        int inputs;
        final float yaw;
        int sessionTicks;

        public TamingSession(UUID playerId, int horseId, MountKind kind, SaddleType saddle, TameBalance balance, float yaw) {
            this.playerId = playerId;
            this.horseId = horseId;
            this.kind = kind;
            this.saddle = saddle;
            this.difficulty = Math.max(1, Math.min(5, kind.tameDifficulty));
            this.balance = balance;
            this.yaw = yaw;
        }
    }

    private WildHorseTaming() {}

    /** Starts breaking in a wild creature (true when the saddle use was handled). */
    public static boolean tryStart(ServerPlayer player, Mob mob, ItemStack stack, MountKind kind, SaddleType saddle) {
        if (!mob.isAlive() || SaddleType.of(stack) != saddle || kind.tameDifficulty <= 0) return false;
        if (!MountGear.accepts(kind, saddle)) return false;
        if (ACTIVE_SESSIONS.containsKey(player.getUUID())) return true;

        MountStats stats = MountStats.of(mob, kind);
        // a mount that is not calm spooks and runs; one that has been fed and trusts you lets you walk up
        boolean hurt = mob.hurtTime > 0 || mob.getLastHurtByMob() != null && mob.tickCount - mob.getLastHurtByMobTimestamp() < 100;
        boolean trusting = stats.trust >= 50f;
        if (player.isSprinting() || hurt || !player.isCrouching() && !trusting) {
            Notifier.message(player, Component.translatable(player.isCrouching() || trusting ? "fauna.skycraft.tame.spooked" : "fauna.skycraft.tame.must_sneak"));
            mob.setDeltaMovement(mob.getDeltaMovement().add(
                    (mob.getX() - player.getX()) * 0.4,
                    0.2,
                    (mob.getZ() - player.getZ()) * 0.4
            ));
            return true;
        }
        if (mob.isVehicle()) return true;

        // Consume saddle if not creative
        if (!player.isCreative()) {
            stack.shrink(1);
        }

        // the bucking: harder for a hard kind and a wary animal, gentler for a rider with a Steady Seat
        float calm = Math.max(0f, Math.min(1f, stats.trust / 100f * 0.7f + (stats.q[5] + 100) / 200f * 0.3f));
        int seat = MountGrowth.rank(player, "steady_seat");
        TameBalance balance = new TameBalance(kind.tameDifficulty, calm, seat, mob.getUUID().getLeastSignificantBits() ^ player.getUUID().getMostSignificantBits());

        mob.setTarget(null);
        mob.getNavigation().stop();
        mob.setNoAi(true);
        mob.getPersistentData().putBoolean(NOAI_TAG, true);
        player.startRiding(mob, true);

        TamingSession session = new TamingSession(player.getUUID(), mob.getId(), kind, saddle, balance, mob.getYRot());
        ACTIVE_SESSIONS.put(player.getUUID(), session);

        Notifier.message(player, Component.translatable("fauna.skycraft.tame.started", kind.displayName()));
        sendState(player, session, true);
        return true;
    }

    /** C2S: the rider's held steer, -1 (A), 0 or +1 (D). Rate-limited; anything else is ignored. */
    public static void onClientSteer(ServerPlayer player, int direction) {
        TamingSession s = ACTIVE_SESSIONS.get(player.getUUID());
        if (s == null || direction < -1 || direction > 1) return;
        long now = player.level().getGameTime();
        if (now - s.windowStart >= 20 || now < s.windowStart) {
            s.windowStart = now;
            s.inputs = 0;
        }
        if (++s.inputs > MAX_INPUTS) return;
        s.steer = direction;
    }

    private static void sendState(ServerPlayer player, TamingSession s, boolean active) {
        TameBalance b = s.balance;
        SkyNetwork.sendToPlayer(player, new FaunaPackets.TameState(active,
                Math.round(Math.max(-1f, Math.min(1f, b.marker)) * 100f),
                Math.round(b.zone * 100f),
                Math.round(Math.max(0f, Math.min(1f, b.progress)) * 100f),
                Math.round(Math.max(0f, Math.min(1.2f, b.amplitude())) * 100f),
                Math.round(Math.max(-1f, Math.min(1f, b.vel / 0.2f)) * 100f),
                Math.round(Math.min(1f, b.edgeTicks / (float) b.grace) * 100f)));
    }

    public static void tickServer(ServerPlayer player) {
        TamingSession session = ACTIVE_SESSIONS.get(player.getUUID());
        if (session == null) return;

        if (!(player.getVehicle() instanceof Mob mob) || mob.getId() != session.horseId || !mob.isAlive()) {
            ACTIVE_SESSIONS.remove(player.getUUID());
            if (player.serverLevel().getEntity(session.horseId) instanceof Mob m) release(m);
            refund(player, session);
            SkyNetwork.sendToPlayer(player, new FaunaPackets.TameState(false, 0, 0, 0, 0, 0, 0));
            return;
        }
        // the beast bucks in place rather than wandering off (or biting its rider)
        mob.setTarget(null);
        mob.getNavigation().stop();
        if (mob instanceof AbstractHorse h) h.setTemper(0);
        session.sessionTicks++;

        TameBalance.Result r = session.balance.step(session.steer);
        buck(player, mob, session);
        if (session.sessionTicks % 2 == 0) sendState(player, session, true);
        if (r == TameBalance.Result.WON) completeTaming(player, session, true);
        else if (r == TameBalance.Result.THROWN) completeTaming(player, session, false);
    }

    /** The animal's side of it: it rocks, hops and snorts with the force that is pushing the marker. */
    private static void buck(ServerPlayer player, Mob mob, TamingSession s) {
        TameBalance b = s.balance;
        float force = b.buck() * b.amplitude();
        float yaw = s.yaw + b.marker * 24f;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;
        mob.setXRot(Math.max(-12f, Math.min(12f, force * 14f)));
        if (!(player.level() instanceof ServerLevel sl)) return;
        int t = s.sessionTicks;
        if (t % 9 == 0 && mob.onGround() && Math.abs(force) > 0.18f) {
            Vec3 dm = mob.getDeltaMovement();
            mob.setDeltaMovement(dm.x * 0.3, 0.20 + 0.16 * Math.min(1.2f, b.amplitude()), dm.z * 0.3);
            sl.sendParticles(ParticleTypes.POOF, mob.getX(), mob.getY() + 0.1, mob.getZ(), 3, 0.35, 0.05, 0.35, 0.02);
        }
        if (t % 24 == 0) {
            sl.playSound(null, mob.blockPosition(), s.kind.horseLike() ? SoundEvents.HORSE_ANGRY : SoundEvents.RAVAGER_STUNNED,
                    SoundSource.NEUTRAL, 0.6f + 0.5f * Math.min(1f, b.amplitude()), 0.9f + 0.2f * b.fraction());
        }
        if (b.edgeTicks > 0 && t % 6 == 0) {
            sl.playSound(null, player.blockPosition(), SoundEvents.HORSE_HURT, SoundSource.NEUTRAL, 0.5f, 1.3f);
        }
    }

    public static void onLogout(ServerPlayer player) {
        TamingSession session = ACTIVE_SESSIONS.remove(player.getUUID());
        if (session != null) {
            if (player.serverLevel().getEntity(session.horseId) instanceof Mob m) release(m);
            refund(player, session);
        }
    }

    /** True while the player is in the middle of breaking a mount in. */
    public static boolean isTaming(ServerPlayer player) {
        return ACTIVE_SESSIONS.containsKey(player.getUUID());
    }

    /** The running session of a player (selftests). */
    public static TamingSession session(ServerPlayer player) {
        return ACTIVE_SESSIONS.get(player.getUUID());
    }

    /** Ends the player's session as if they had fallen off (selftests and interruptions); the saddle comes back. */
    public static void abort(ServerPlayer player) {
        onLogout(player);
    }

    /** Gives the animal its own mind back after a breaking. */
    public static void release(Mob mob) {
        if (mob.getPersistentData().getBoolean(NOAI_TAG)) {
            mob.getPersistentData().remove(NOAI_TAG);
            mob.setNoAi(false);
        }
        mob.setXRot(0f);
    }

    /** The saddle of a taming that did not succeed goes back to the player's bags; the beast is bare again. */
    private static void refund(ServerPlayer player, TamingSession session) {
        if (session.refunded) return;
        session.refunded = true;
        if (player.serverLevel().getEntity(session.horseId) instanceof Mob mob && mob.isAlive() && !MountGear.isOwned(mob)) {
            MountGear.clearSaddle(mob);
        }
        if (!player.isCreative()) {
            ItemStack saddleStack = session.saddle.stack();
            ItemStack held = player.getMainHandItem();
            if (held.isEmpty()) {
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, saddleStack);
                player.containerMenu.broadcastChanges();
            } else if (ItemStack.isSameItemSameTags(held, saddleStack) && held.getCount() < held.getMaxStackSize()) {
                held.grow(1);
                player.containerMenu.broadcastChanges();
            } else {
                MountGear.give(player, saddleStack);
            }
        }
    }

    private static void completeTaming(ServerPlayer player, TamingSession session, boolean success) {
        ACTIVE_SESSIONS.remove(player.getUUID());
        if (!(player.getVehicle() instanceof Mob mob)) {
            refund(player, session);
            SkyNetwork.sendToPlayer(player, new FaunaPackets.TameState(false, 0, 0, 0, 0, 0, 0));
            return;
        }
        release(mob);

        if (success) {
            session.refunded = true; // the saddle stays on the mount
            if (MountTaming.complete(player, mob, session.kind, session.saddle) == null) {
                session.refunded = false;
                refund(player, session);
                return;
            }

            // Award skills: HUNTING and ATHLETICS
            Progression.addSkillXp(player, Skill.HUNTING, 25.0f * session.difficulty);
            Progression.addSkillXp(player, Skill.ATHLETICS, 20.0f * session.difficulty);

            Notifier.message(player, Component.translatable("fauna.skycraft.tame.success", session.kind.displayName()));
        } else {
            // Bucked off! (the saddle comes back)
            player.stopRiding();
            refund(player, session);
            mob.setDeltaMovement(mob.getDeltaMovement().add(
                    (player.getRandom().nextDouble() - 0.5) * 0.5,
                    0.4,
                    (player.getRandom().nextDouble() - 0.5) * 0.5
            ));
            if (!(mob instanceof AbstractHorse) && session.kind.tameDifficulty >= 4) mob.setTarget(player);
            Notifier.message(player, Component.translatable("fauna.skycraft.tame.bucked_off"));
        }
        SkyNetwork.sendToPlayer(player, new FaunaPackets.TameState(false, 0, 0, success ? 100 : 0, 0, 0, 0));
    }
}
