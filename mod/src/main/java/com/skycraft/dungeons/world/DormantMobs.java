package com.skycraft.dungeons.world;

import com.skycraft.Skycraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Draugr asleep in their alcoves and sarcophagi. A dormant mob has no AI (it lies still where the barrow put it)
 * until a player walks up to it (closer when sneaking) or anything hurts it; then it rises with a groan and a puff of
 * grave dust, and wakes the sleepers around it.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class DormantMobs {
    public static final String TAG = "skycraft_dormant";
    private static final double WAKE = 4.5;
    private static final double WAKE_SNEAKING = 2.5;
    private static final double CHAIN = 6.0;

    private DormantMobs() {}

    /** Marks a freshly created mob dormant (call before it is added to the world). */
    public static void makeDormant(Mob mob) {
        mob.setNoAi(true);
        mob.getPersistentData().putBoolean(TAG, true);
    }

    public static boolean isDormant(LivingEntity e) {
        return e.getPersistentData().getBoolean(TAG);
    }

    @SubscribeEvent
    public static void onTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) return;
        if ((mob.tickCount + mob.getId()) % 10 != 0 || !isDormant(mob)) return;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
            double r = p.isCrouching() ? WAKE_SNEAKING : WAKE;
            if (p.distanceToSqr(mob) <= r * r) {
                wake(level, mob, true);
                return;
            }
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (event.getEntity() instanceof Mob mob && mob.level() instanceof ServerLevel level && isDormant(mob)) {
            wake(level, mob, true);
        }
    }

    private static void wake(ServerLevel level, Mob mob, boolean chain) {
        CompoundTag data = mob.getPersistentData();
        data.remove(TAG);
        mob.setNoAi(false);
        level.sendParticles(ParticleTypes.ASH, mob.getX(), mob.getY() + 1.0, mob.getZ(), 24, 0.35, 0.6, 0.35, 0.01);
        level.sendParticles(ParticleTypes.SOUL, mob.getX(), mob.getY() + 1.4, mob.getZ(), 4, 0.2, 0.3, 0.2, 0.01);
        level.playSound(null, mob.blockPosition(), SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE, 1.0f, 0.55f);
        if (!chain) return;
        // the dead stir together
        for (Mob other : level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(CHAIN), DormantMobs::isDormant)) {
            if (other != mob && level.random.nextInt(3) != 0) wake(level, other, false);
        }
    }
}
