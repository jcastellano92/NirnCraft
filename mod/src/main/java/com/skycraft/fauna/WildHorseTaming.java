package com.skycraft.fauna;

import com.skycraft.core.Notifier;
import com.skycraft.core.Skill;
import com.skycraft.mounts.MountGear;
import com.skycraft.mounts.MountKind;
import com.skycraft.mounts.SaddleType;
import com.skycraft.mounts.MountTaming;
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
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wild mount taming minigame (horses, camels, guar, senche-tigers, indriks, mammoths, realm grazers; see
 * {@link MountTaming#wildKind}):
 * 1. Player sneaks up with a saddle in hand (any {@link SaddleType} the breed accepts); the beast spooks and bolts if
 *    they are not sneaking, are sprinting, or have hurt it in the last five seconds. Only wild mounts: an owned mount
 *    is managed in its menu.
 * 2. Player uses the saddle on it -> saddle thrown on and the player mounts.
 * 3. It begins bucking: balance minigame starts. Player must counter-steer (press A/D or steer keys)
 *    sent via packet. Higher difficulty makes balance windows shorter and penalties harsher.
 * 4. Success tames it into the player's mount ({@link MountTaming#complete}, wearing that saddle) and awards HUNTING and
 *    ATHLETICS XP. Failure throws the rider (a predator turns on them) and the saddle comes back to the rider's bags;
 *    so does a saddle whose taming is interrupted (logout, the beast dying, getting off).
 */
public final class WildHorseTaming {
    private static final Map<UUID, TamingSession> ACTIVE_SESSIONS = new ConcurrentHashMap<>();

    public static class TamingSession {
        public final UUID playerId;
        public final int horseId;
        public final MountKind kind;
        public final int difficulty;
        /** The saddle thrown on (its item was used up; it is returned when the taming fails). */
        public final SaddleType saddle;
        public boolean refunded;
        public int progress = 0; // 0 to 100
        public int required = 100;
        public int balanceDirection = 0; // -1 (left), 1 (right)
        public int ticksRemainingInPrompt = 0;
        public int sessionTicks = 0;

        public TamingSession(UUID playerId, int horseId, MountKind kind, SaddleType saddle) {
            this.playerId = playerId;
            this.horseId = horseId;
            this.kind = kind;
            this.saddle = saddle;
            this.difficulty = Math.max(1, Math.min(5, kind.tameDifficulty));
        }
    }

    private WildHorseTaming() {}

    /** Starts taming a wild creature (true when the saddle use was handled). */
    public static boolean tryStart(ServerPlayer player, Mob mob, ItemStack stack, MountKind kind, SaddleType saddle) {
        if (!mob.isAlive() || SaddleType.of(stack) != saddle || kind.tameDifficulty <= 0) return false;
        if (!MountGear.accepts(kind, saddle)) return false;
        if (ACTIVE_SESSIONS.containsKey(player.getUUID())) return true;

        // Check if undetected / sneaking: a mount that is not calm spooks and runs
        boolean hurt = mob.hurtTime > 0 || mob.getLastHurtByMob() != null && mob.tickCount - mob.getLastHurtByMobTimestamp() < 100;
        if (!player.isCrouching() || player.isSprinting() || hurt) {
            Notifier.message(player, Component.translatable(player.isCrouching() ? "fauna.skycraft.tame.spooked" : "fauna.skycraft.tame.must_sneak"));
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

        MountGear.setSaddle(mob, saddle);

        // Mount player
        mob.setTarget(null);
        player.startRiding(mob, true);

        TamingSession session = new TamingSession(player.getUUID(), mob.getId(), kind, saddle);
        ACTIVE_SESSIONS.put(player.getUUID(), session);

        Notifier.message(player, Component.translatable("fauna.skycraft.tame.started", kind.displayName()));
        promptNextBalance(player, session);
        return true;
    }

    public static void promptNextBalance(ServerPlayer player, TamingSession session) {
        session.balanceDirection = player.getRandom().nextBoolean() ? -1 : 1;
        int promptTicks = Math.max(15, 40 - session.difficulty * 5);
        session.ticksRemainingInPrompt = promptTicks;

        // Send packet or notification to client
        SkyNetwork.sendToPlayer(player, new FaunaPackets.TamePrompt(
                session.balanceDirection,
                promptTicks,
                session.progress
        ));

        // bucking effect
        if (player.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.SMOKE, player.getX(), player.getY() + 0.5, player.getZ(), 4, 0.2, 0.2, 0.2, 0.02);
            sl.playSound(null, player.blockPosition(), session.kind.horseLike() ? SoundEvents.HORSE_ANGRY : SoundEvents.RAVAGER_STUNNED,
                    SoundSource.NEUTRAL, 1.0f, 1.0f);
        }
        if (player.getVehicle() instanceof Mob m) {
            m.setDeltaMovement(m.getDeltaMovement().add(0, m.onGround() ? 0.25 : 0, 0));
            m.setYRot(m.getYRot() + session.balanceDirection * 25f);
        }
    }

    public static void onClientSteer(ServerPlayer player, int direction) {
        TamingSession session = ACTIVE_SESSIONS.get(player.getUUID());
        if (session == null || (direction != -1 && direction != 1)) return;

        if (direction == session.balanceDirection) {
            // Correct counter-steer!
            session.progress += 25 - session.difficulty * 2;
            player.level().playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5f, 1.2f);
            if (session.progress >= session.required) {
                completeTaming(player, session, true);
                return;
            }
        } else {
            // Failed balance steer
            session.progress = Math.max(0, session.progress - 20);
            player.level().playSound(null, player.blockPosition(), SoundEvents.HORSE_HURT, SoundSource.NEUTRAL, 0.6f, 1.0f);
        }

        promptNextBalance(player, session);
    }

    public static void tickServer(ServerPlayer player) {
        TamingSession session = ACTIVE_SESSIONS.get(player.getUUID());
        if (session == null) return;

        if (!(player.getVehicle() instanceof Mob mob) || mob.getId() != session.horseId || !mob.isAlive()) {
            ACTIVE_SESSIONS.remove(player.getUUID());
            refund(player, session);
            SkyNetwork.sendToPlayer(player, new FaunaPackets.TamePrompt(0, 0, 0));
            return;
        }
        // the beast bucks in place rather than wandering off (or biting its rider)
        mob.setTarget(null);
        mob.getNavigation().stop();

        session.sessionTicks++;
        session.ticksRemainingInPrompt--;

        if (session.ticksRemainingInPrompt <= 0) {
            // Player timed out answering bucking prompt: bucked off!
            completeTaming(player, session, false);
        }
    }

    public static void onLogout(ServerPlayer player) {
        TamingSession session = ACTIVE_SESSIONS.remove(player.getUUID());
        if (session != null) refund(player, session);
    }

    /** True while the player is in the middle of breaking a mount in. */
    public static boolean isTaming(ServerPlayer player) {
        return ACTIVE_SESSIONS.containsKey(player.getUUID());
    }

    /** Ends the player's session as if they had fallen off (selftests and interruptions); the saddle comes back. */
    public static void abort(ServerPlayer player) {
        onLogout(player);
    }

    /** The saddle of a taming that did not succeed goes back to the player's bags; the beast is bare again. */
    private static void refund(ServerPlayer player, TamingSession session) {
        if (session.refunded) return;
        session.refunded = true;
        if (player.serverLevel().getEntity(session.horseId) instanceof Mob mob && mob.isAlive() && !MountGear.isOwned(mob)) {
            MountGear.clearSaddle(mob);
        }
        if (!player.isCreative()) MountGear.give(player, session.saddle.stack());
    }

    private static void completeTaming(ServerPlayer player, TamingSession session, boolean success) {
        ACTIVE_SESSIONS.remove(player.getUUID());
        if (!(player.getVehicle() instanceof Mob mob)) {
            refund(player, session);
            return;
        }

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
            SkyNetwork.sendToPlayer(player, new FaunaPackets.TamePrompt(0, 0, 100));
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
            SkyNetwork.sendToPlayer(player, new FaunaPackets.TamePrompt(0, 0, 0));
        }
    }
}
