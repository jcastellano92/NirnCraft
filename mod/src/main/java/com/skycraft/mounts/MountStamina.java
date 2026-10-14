package com.skycraft.mounts;

import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.network.SkyNetwork;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mount stamina and the gallop (Skyrim's sprint on horseback), on the rider's own stamina bar.
 *
 * <p><b>The swap.</b> While you steer one of your mounts, the stamina bar IS the mount's: when you take the reins the
 * player's own value is parked in the mount record ({@link #PARKED}) and the bar is set to the mount's stamina as the
 * same fraction of your bar; when you get off (dismount, death, teleport, logout and back, the mount dying) the bar
 * goes back to the parked value and the mount keeps what the bar held. Everything lives on the server and the bar
 * reaches the client through the vitals packet like any stamina, so there is no second HUD and nothing to desync. The
 * vitals module neither drains nor regenerates the bar while swapped ({@link #swapped}); swings and spells you spend
 * on horseback still come out of it, as they would out of the mount's wind.</p>
 *
 * <p><b>The mount's side.</b> Holding sprint and moving gallops ({@link MountKind#gallop} faster) and burns a point of
 * stamina a tick; a jump costs a slice; resting regains it (faster when standing still). An exhausted mount can't
 * gallop again until it has a quarter back. The maximum is the kind's, scaled by the saddle
 * ({@link MountGear#maxStamina}).</p>
 */
public final class MountStamina {
    public static final String TAG = "skycraft_mount_stamina";
    private static final String EXHAUSTED = "skycraft_mount_exhausted";
    /** In the mount record: the player's own stamina, parked while the bar shows the mount's. */
    static final String PARKED = "parked_stamina";
    private static final String PARK_MOUNT = "parked_mount";
    private static final String PARK_MAX = "parked_max";
    /** In the mount record: the active mount's stamina points when it was last left (it may be unloaded). */
    static final String SAVED = "stamina_pts";
    private static final UUID GALLOP = UUID.fromString("0f6b9c2a-71de-4f0b-8a73-2c9b0b5c4e11");
    private static final UUID LOAD = UUID.fromString("5d1b7e34-9a2c-4c6f-b3f1-6e0a8d2c7b19");
    /** A jump costs this fraction of the mount's stamina. */
    private static final float JUMP_COST = 0.06f;

    /** Riders holding the gallop (sprint) key, from {@link MountPackets.Gallop}. */
    private static final Map<UUID, Boolean> WANTS = new ConcurrentHashMap<>();
    /** The mount's last x, z, y and vertical step, per rider. */
    private static final Map<UUID, double[]> LAST = new ConcurrentHashMap<>();
    /** Horizontal blocks per tick the rider's mount covered last tick (the server can't read a ridden mount's motion). */
    private static final Map<UUID, Double> SPEED = new ConcurrentHashMap<>();
    private static final Map<UUID, Float> LAST_HEALTH = new ConcurrentHashMap<>();

    private MountStamina() {}

    public static void setGallop(ServerPlayer player, boolean on) {
        if (on) WANTS.put(player.getUUID(), true);
        else WANTS.remove(player.getUUID());
    }

    public static void forget(ServerPlayer player) {
        WANTS.remove(player.getUUID());
        LAST.remove(player.getUUID());
        SPEED.remove(player.getUUID());
        LAST_HEALTH.remove(player.getUUID());
        MountGrowth.forgetRider(player);
    }

    public static double speed(ServerPlayer player) {
        return SPEED.getOrDefault(player.getUUID(), 0.0);
    }

    /** The mount's stamina points (its tag, else full), never above its current maximum. */
    public static float stamina(Entity mount, MountKind kind) {
        int max = MountGear.maxStamina(mount, kind);
        return mount.getPersistentData().contains(TAG) ? Math.min(max, mount.getPersistentData().getFloat(TAG)) : max;
    }

    /** True while the player's bar shows their mount's stamina (server side). */
    public static boolean swapped(net.minecraft.world.entity.player.Player player) {
        return !player.level().isClientSide && SkyData.get(player).module(Mounts.MODULE).contains(PARKED);
    }

    /** True while the mount you steer is exhausted: the vitals module reports it so you can't run either. */
    public static boolean exhausted(net.minecraft.world.entity.player.Player player) {
        if (!(player instanceof ServerPlayer sp) || !swapped(sp)) return false;
        LivingEntity mount = steered(sp);
        return mount != null && mount.getPersistentData().getBoolean(EXHAUSTED);
    }

    /** The mount the player steers (their own, or any when creative), or null. */
    @Nullable
    static LivingEntity steered(ServerPlayer player) {
        if (player.getVehicle() instanceof LivingEntity m && m.getControllingPassenger() == player
                && Mounts.kindOf(m) != null && Mounts.mayControl(player, m)) {
            return m;
        }
        return null;
    }

    /** Every tick, for every player: takes the bar when a mount is taken, runs the ride, gives the bar back. */
    static void tick(ServerPlayer player) {
        CompoundTag rec = Mounts.data(player);
        LivingEntity mount = steered(player);
        boolean parked = rec.contains(PARKED);
        if (mount == null) {
            if (parked) leave(player);
            if (LAST.remove(player.getUUID()) != null) {
                SPEED.remove(player.getUUID());
                LAST_HEALTH.remove(player.getUUID());
            }
            return;
        }
        MountKind kind = Mounts.kindOf(mount);
        PlayerData data = SkyData.get(player);
        int max = MountGear.maxStamina(mount, kind);
        float pm = Math.max(1f, data.maxStamina());
        if (!parked) {
            // taking the reins: park the player's own stamina, show the mount's
            rec.putFloat(PARKED, data.getStamina());
            rec.putUUID(PARK_MOUNT, mount.getUUID());
            data.setStamina(stamina(mount, kind) / max * pm);
            data.markDirty();
            LAST.remove(player.getUUID());
        }
        rec.putInt(PARK_MAX, max);

        double[] last = LAST.computeIfAbsent(player.getUUID(), k -> new double[]{mount.getX(), mount.getZ(), mount.getY(), 0});
        double dx = mount.getX() - last[0], dz = mount.getZ() - last[1], dy = mount.getY() - last[2];
        boolean jumped = dy > 0.3 && last[3] <= 0.1 && !mount.onGround();
        last[0] = mount.getX();
        last[1] = mount.getZ();
        last[2] = mount.getY();
        last[3] = dy;
        double speed = Math.sqrt(dx * dx + dz * dz);
        if (speed > 3 || Math.abs(dy) > 8) speed = 0; // teleported
        SPEED.put(player.getUUID(), speed);
        boolean moving = speed > 0.08;

        // the bar is the truth: whatever else spent or restored it since last tick is the mount's wind too
        float st = Math.max(0f, Math.min(max, data.getStamina() / pm * max));
        boolean exhausted = mount.getPersistentData().getBoolean(EXHAUSTED);
        boolean gallop = moving && !exhausted && st > 0 && WANTS.containsKey(player.getUUID());
        if (gallop) {
            // Endurance (perk) slows the burn
            st = Math.max(0, st - MountGrowth.drainMult(player));
        } else {
            // a full bar comes back in 12 s standing (24 s moving); a bonded, calm mount gets its wind back sooner
            float rest = max / 240f * MountStats.restMult(MountStats.of(mount, kind).trust);
            st = Math.min(max, st + (moving ? rest * 0.5f : rest));
        }
        if (jumped) st = Math.max(0f, st - max * JUMP_COST * MountGrowth.trickJumpCost(player));
        if (st <= 0) exhausted = true;
        else if (exhausted && st >= max * 0.25f) exhausted = false;
        mount.getPersistentData().putFloat(TAG, st);
        mount.getPersistentData().putBoolean(EXHAUSTED, exhausted);
        data.setStamina(st / max * pm);
        if (rec.hasUUID("uuid") && rec.getUUID("uuid").equals(mount.getUUID())) rec.putFloat(SAVED, st);

        MountGrowth.ride(player, mount, kind, speed, gallop, jumped); // distance, jumps and loads grow the mount
        AttributeInstance move = mount.getAttribute(Attributes.MOVEMENT_SPEED);
        if (move != null) {
            boolean has = move.getModifier(GALLOP) != null;
            if (gallop && !has) {
                move.addTransientModifier(new AttributeModifier(GALLOP, "Gallop", kind.gallop, AttributeModifier.Operation.MULTIPLY_TOTAL));
            } else if (!gallop && has) {
                move.removeModifier(GALLOP);
            }
        }
        // an over-encumbered rider slows the mount too: a milder version of their own slowdown, so the mount still
        // outpaces them on foot but never runs at full speed with a loaded-down rider
        if (move != null) {
            float over = com.skycraft.inventory.CarryWeight.overweightRatio(player);
            // the mount slows with the rider's load more gently than a person does, and stops outright at +150%
            double penalty = over <= 0f ? 0 : -Math.min(1.0, Math.round(over / 1.5f * 50f) / 50.0);
            AttributeModifier cur = move.getModifier(LOAD);
            if (penalty < 0 && (cur == null || cur.getAmount() != penalty)) {
                if (cur != null) move.removeModifier(LOAD);
                move.addTransientModifier(new AttributeModifier(LOAD, "Rider load", penalty, AttributeModifier.Operation.MULTIPLY_TOTAL));
            } else if (penalty == 0 && cur != null) {
                move.removeModifier(LOAD);
            }
        }
        if (gallop && player.tickCount % 3 == 0 && mount.level() instanceof ServerLevel sl && mount.onGround()) {
            BlockState below = sl.getBlockState(mount.blockPosition().below());
            if (!below.isAir()) {
                sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, below), mount.getX(), mount.getY() + 0.1, mount.getZ(),
                        4, mount.getBbWidth() * 0.4, 0.05, mount.getBbWidth() * 0.4, 0.1);
            }
        }
        if (kind == MountKind.ARVAK && player.tickCount % 4 == 0 && mount.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, mount.getX(), mount.getY() + 0.3, mount.getZ(), 2, 0.3, 0.1, 0.3, 0.01);
        }
        if (player.tickCount % 40 == 0) Mounts.syncBarding(player, mount);
        if (player.tickCount % 10 == 0) {
            // the mount's health is the one thing the bars don't show
            Float before = LAST_HEALTH.get(player.getUUID());
            if (before == null || before != mount.getHealth()) {
                LAST_HEALTH.put(player.getUUID(), mount.getHealth());
                SkyNetwork.sendToPlayer(player, new MountPackets.MountHud(mount.getHealth(), mount.getMaxHealth()));
            }
        }
    }

    /**
     * Gives the player's own stamina back and leaves the mount what the bar held. Safe to call at any time (no-op
     * when not swapped); runs on dismount, from the tick when the mount is gone, and from the selftest.
     */
    static void leave(ServerPlayer player) {
        CompoundTag rec = Mounts.data(player);
        if (!rec.contains(PARKED)) return;
        PlayerData data = SkyData.get(player);
        float parked = rec.getFloat(PARKED);
        int max = Math.max(1, rec.getInt(PARK_MAX));
        float pm = Math.max(1f, data.maxStamina());
        float pts = Math.max(0f, Math.min(max, data.getStamina() / pm * max));
        if (rec.hasUUID(PARK_MOUNT)) {
            UUID id = rec.getUUID(PARK_MOUNT);
            if (rec.hasUUID("uuid") && rec.getUUID("uuid").equals(id)) rec.putFloat(SAVED, pts);
            for (ServerLevel level : player.server.getAllLevels()) {
                Entity m = level.getEntity(id);
                if (m != null) {
                    m.getPersistentData().putFloat(TAG, pts);
                    if (pts >= max * 0.25f) m.getPersistentData().putBoolean(EXHAUSTED, false);
                    dismounted(m);
                    break;
                }
            }
        }
        data.setStamina(parked);
        rec.remove(PARKED);
        rec.remove(PARK_MOUNT);
        rec.remove(PARK_MAX);
        data.markDirty();
        LAST_HEALTH.remove(player.getUUID());
    }

    /** Forgets a parked bar without restoring it (the player died: the respawn already refilled it). */
    static void discard(net.minecraft.world.entity.player.Player player) {
        CompoundTag rec = SkyData.get(player).module(Mounts.MODULE);
        rec.remove(PARKED);
        rec.remove(PARK_MOUNT);
        rec.remove(PARK_MAX);
    }

    /** Clears the gallop boost when the rider gets off. */
    static void dismounted(Entity mount) {
        if (mount instanceof LivingEntity l) {
            AttributeInstance move = l.getAttribute(Attributes.MOVEMENT_SPEED);
            if (move != null) {
                move.removeModifier(GALLOP);
                move.removeModifier(LOAD);
            }
        }
    }
}
