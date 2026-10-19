package com.skycraft.scatter;

import com.skycraft.Skycraft;
import com.skycraft.vitals.ActionHandler;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.List;

/**
 * Interactive 3D clutter & physics props: lying items placed via {@link com.skycraft.explore.build.SiteBuilder#lying}
 * rest as zero-cost sleeping {@link ItemFrame}s until interacted with.
 * <ul>
 *     <li>Right-clicking picks the clutter item directly into the player's bags.</li>
 *     <li>Striking, shooting, exploding, or sprinting through clutter wakes it into a live 3D physical
 *     {@link ItemEntity} that tumbles and bounces across tables and floors.</li>
 * </ul>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class PhysicsClutter {
    public static final String CLUTTER_TAG = "skycraft_clutter";
    public static final String PHYSICS_TAG = "skycraft_physics_clutter";

    private PhysicsClutter() {}

    public static boolean isClutterFrame(Entity entity) {
        if (!(entity instanceof ItemFrame frame) || !frame.isAlive()) return false;
        if (frame.getItem().isEmpty()) return false;
        return frame.entityTags().contains(CLUTTER_TAG)
                || (frame.isInvisible() && frame.getDirection() == Direction.UP);
    }

    /** Wakes a sleeping clutter {@link ItemFrame} into a live physics-simulated {@link ItemEntity} with an impulse. */
    public static ItemEntity wakeToPhysics(ItemFrame frame, Vec3 impulse) {
        Level level = frame.level();
        ItemStack stack = frame.getItem().copy();
        if (stack.isEmpty() || level.isClientSide()) return null;
        frame.setItem(ItemStack.EMPTY, false);
        frame.discard();
        ItemEntity drop = new ItemEntity(level, frame.getX(), frame.getY() + 0.12, frame.getZ(), stack);
        drop.setDeltaMovement(impulse);
        drop.setPickUpDelay(20);
        drop.setUnlimitedLifetime();
        drop.addTag(PHYSICS_TAG);
        level.addFreshEntity(drop);
        level.playSound(null, frame.blockPosition(), SoundEvents.LANTERN_STEP, SoundSource.BLOCKS, 0.65f,
                0.9f + level.getRandom().nextFloat() * 0.3f);
        return drop;
    }

    @SubscribeEvent
    public static void onRightClickClutter(PlayerInteractEvent.EntityInteract event) {
        if (!isClutterFrame(event.getTarget())) return;
        ItemFrame frame = (ItemFrame) event.getTarget();
        Player player = event.getEntity();
        Level level = event.getLevel();
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (level.isClientSide()) return;

        if (player.isShiftKeyDown()) {
            // Sneak + right-click nudges/tosses the clutter prop physically across the surface
            Vec3 look = player.getLookAngle();
            Vec3 nudge = new Vec3(look.x * 0.22, 0.16, look.z * 0.22);
            wakeToPhysics(frame, nudge);
            return;
        }

        ItemStack stack = frame.getItem().copy();
        frame.setItem(ItemStack.EMPTY, false);
        frame.discard();
        if (player instanceof ServerPlayer sp) {
            if (!ActionHandler.addToBags(sp, stack)) {
                sp.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
            }
        }
        level.playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5f,
                1.1f + level.getRandom().nextFloat() * 0.2f);
    }

    @SubscribeEvent
    public static void onAttackClutter(AttackEntityEvent event) {
        if (!isClutterFrame(event.getTarget())) return;
        ItemFrame frame = (ItemFrame) event.getTarget();
        Player player = event.getEntity();
        event.setCanceled(true);
        if (player.level().isClientSide()) return;
        Vec3 look = player.getLookAngle();
        Vec3 impulse = new Vec3(look.x * 0.38, 0.22, look.z * 0.38);
        wakeToPhysics(frame, impulse);
    }

    /** Also when protection cancelled the hit (1.20.1's SKIP_ENTITY did not cancel, so this still ran). */
    @SubscribeEvent(receiveCanceled = true)
    public static void onProjectileImpactClutter(ProjectileImpactEvent event) {
        Entity proj = event.getProjectile();
        Level level = proj.level();
        if (level.isClientSide()) return;
        AABB box = proj.getBoundingBox().inflate(0.85);
        List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, box, PhysicsClutter::isClutterFrame);
        for (ItemFrame frame : frames) {
            Vec3 vel = proj.getDeltaMovement();
            Vec3 impulse = new Vec3(vel.x * 0.35, Math.max(0.18, Math.abs(vel.y) * 0.25), vel.z * 0.35);
            wakeToPhysics(frame, impulse);
        }
    }

    @SubscribeEvent
    public static void onExplosionClutter(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        Vec3 center = event.getExplosion().center();
        AABB box = new AABB(center, center).inflate(6.0);
        List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, box, PhysicsClutter::isClutterFrame);
        event.getAffectedEntities().removeIf(PhysicsClutter::isClutterFrame);
        for (ItemFrame frame : frames) {
            Vec3 diff = frame.position().subtract(center);
            double dist = Math.max(0.5, diff.length());
            double strength = Math.max(0.15, (6.0 - dist) / 6.0) * 0.55;
            Vec3 dir = diff.normalize();
            wakeToPhysics(frame, new Vec3(dir.x * strength, 0.25 + strength * 0.5, dir.z * strength));
        }
    }

    @SubscribeEvent
    public static void onPlayerSprintCollideClutter(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || !player.isSprinting() || (player.tickCount & 3) != 0) return;
        AABB footBox = player.getBoundingBox().inflate(0.25, 0.1, 0.25);
        List<ItemFrame> frames = player.level().getEntitiesOfClass(ItemFrame.class, footBox, PhysicsClutter::isClutterFrame);
        for (ItemFrame frame : frames) {
            Vec3 move = player.getDeltaMovement();
            Vec3 diff = frame.position().subtract(player.position()).normalize();
            Vec3 impulse = new Vec3(diff.x * 0.24 + move.x * 0.4, 0.16, diff.z * 0.24 + move.z * 0.4);
            wakeToPhysics(frame, impulse);
        }
    }
}
