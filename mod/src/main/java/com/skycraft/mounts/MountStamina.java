package com.skycraft.mounts;

import com.skycraft.network.SkyNetwork;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mount stamina and the gallop (Skyrim's sprint on horseback): while the rider holds sprint and the mount moves, it
 * gallops ({@link MountKind#gallop} faster) and burns a point of stamina a tick; resting regains it (faster when
 * standing still). An exhausted mount can't gallop again until it has a quarter back. Stamina lives on the mount
 * ({@link #TAG}); the rider's HUD gets it through {@link MountPackets.MountHud}.
 */
public final class MountStamina {
    public static final String TAG = "skycraft_mount_stamina";
    private static final String EXHAUSTED = "skycraft_mount_exhausted";
    private static final UUID GALLOP = UUID.fromString("0f6b9c2a-71de-4f0b-8a73-2c9b0b5c4e11");

    /** Riders holding the gallop (sprint) key, from {@link MountPackets.Gallop}. */
    private static final Map<UUID, Boolean> WANTS = new ConcurrentHashMap<>();
    private static final Map<UUID, double[]> LAST = new ConcurrentHashMap<>();
    /** Horizontal blocks per tick the rider's mount covered last tick (the server can't read a ridden mount's motion). */
    private static final Map<UUID, Double> SPEED = new ConcurrentHashMap<>();

    private MountStamina() {}

    public static void setGallop(ServerPlayer player, boolean on) {
        if (on) WANTS.put(player.getUUID(), true);
        else WANTS.remove(player.getUUID());
    }

    public static void forget(ServerPlayer player) {
        WANTS.remove(player.getUUID());
        LAST.remove(player.getUUID());
        SPEED.remove(player.getUUID());
    }

    public static double speed(ServerPlayer player) {
        return SPEED.getOrDefault(player.getUUID(), 0.0);
    }

    public static float stamina(Entity mount, MountKind kind) {
        return mount.getPersistentData().contains(TAG) ? mount.getPersistentData().getFloat(TAG) : kind.stamina;
    }

    /** Every tick, for every player riding something. */
    static void tick(ServerPlayer player) {
        Entity vehicle = player.getVehicle();
        if (!(vehicle instanceof LivingEntity mount) || mount.getControllingPassenger() != player) {
            LAST.remove(player.getUUID());
            SPEED.remove(player.getUUID());
            return;
        }
        MountKind kind = Mounts.kindOf(mount);
        if (kind == null) return;
        double[] last = LAST.computeIfAbsent(player.getUUID(), k -> new double[]{mount.getX(), mount.getZ()});
        double dx = mount.getX() - last[0], dz = mount.getZ() - last[1];
        last[0] = mount.getX();
        last[1] = mount.getZ();
        double speed = Math.sqrt(dx * dx + dz * dz);
        if (speed > 3) speed = 0; // teleported
        SPEED.put(player.getUUID(), speed);
        boolean moving = speed > 0.08;

        float st = stamina(mount, kind);
        boolean exhausted = mount.getPersistentData().getBoolean(EXHAUSTED);
        boolean gallop = moving && !exhausted && st > 0 && WANTS.containsKey(player.getUUID());
        if (gallop) {
            st = Math.max(0, st - 1f);
            if (st <= 0) exhausted = true;
        } else {
            st = Math.min(kind.stamina, st + (moving ? 0.6f : 1.2f));
            if (exhausted && st >= kind.stamina * 0.25f) exhausted = false;
        }
        mount.getPersistentData().putFloat(TAG, st);
        mount.getPersistentData().putBoolean(EXHAUSTED, exhausted);

        AttributeInstance move = mount.getAttribute(Attributes.MOVEMENT_SPEED);
        if (move != null) {
            boolean has = move.getModifier(GALLOP) != null;
            if (gallop && !has) {
                move.addTransientModifier(new AttributeModifier(GALLOP, "Gallop", kind.gallop, AttributeModifier.Operation.MULTIPLY_TOTAL));
            } else if (!gallop && has) {
                move.removeModifier(GALLOP);
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
        if (player.tickCount % 4 == 0) {
            SkyNetwork.sendToPlayer(player, new MountPackets.MountHud(st, kind.stamina, gallop, exhausted,
                    mount.getHealth(), mount.getMaxHealth(), kind.id));
        }
    }

    /** Clears the gallop boost when the rider gets off. */
    static void dismounted(Entity mount) {
        if (mount instanceof LivingEntity l) {
            AttributeInstance move = l.getAttribute(Attributes.MOVEMENT_SPEED);
            if (move != null) move.removeModifier(GALLOP);
        }
    }
}
