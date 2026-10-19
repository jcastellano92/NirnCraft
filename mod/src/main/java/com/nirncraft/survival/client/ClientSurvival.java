package com.nirncraft.survival.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.survival.SurvivalPackets;
import com.nirncraft.survival.fishing.FishingSpots;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;

import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Client copy of the Survival Mode meters ({@link SurvivalPackets.Meters}) for the HUD, plus the jumping fish at
 * fishing spots ({@link FishingSpots}: the spots are fixed, so the client finds them itself).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class ClientSurvival {
    public static int hunger, fatigue, cold, warmth, trend;
    public static int flags;
    /** When each meter last changed stage (ms), for the stage label. */
    public static long hungerChanged, fatigueChanged, coldChanged;
    private static int lastHungerStage = -1, lastFatigueStage = -1, lastColdStage = -1;

    private ClientSurvival() {}

    public static void receive(SurvivalPackets.Meters m) {
        hunger = m.hunger();
        fatigue = m.fatigue();
        cold = m.cold();
        warmth = m.warmth();
        trend = m.trend();
        flags = m.flags();
        long now = Util.getMillis();
        int hs = com.nirncraft.survival.mode.SurvivalMode.hungerStage(hunger);
        int fs = com.nirncraft.survival.mode.SurvivalMode.fatigueStage(fatigue);
        int cs = com.nirncraft.survival.mode.SurvivalMode.coldStage(cold);
        if (hs != lastHungerStage) hungerChanged = lastHungerStage < 0 ? 0 : now;
        if (fs != lastFatigueStage) fatigueChanged = lastFatigueStage < 0 ? 0 : now;
        if (cs != lastColdStage) coldChanged = lastColdStage < 0 ? 0 : now;
        lastHungerStage = hs;
        lastFatigueStage = fs;
        lastColdStage = cs;
    }

    public static boolean active() {
        return (flags & SurvivalPackets.Meters.ACTIVE) != 0;
    }

    public static boolean has(int flag) {
        return (flags & flag) != 0;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        flags = 0;
        hunger = fatigue = cold = warmth = trend = 0;
        lastHungerStage = lastFatigueStage = lastColdStage = -1;
        FishingHud.reset();
    }

    // ------------------------------------------------------------------ fishing spots

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.isPaused()) return;
        FishingHud.clientTick(mc);
        if (level.getGameTime() % 3 != 0) return;
        RandomSource r = level.getRandom();
        int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
        int cx = Math.floorDiv(px, FishingSpots.CELL), cz = Math.floorDiv(pz, FishingSpots.CELL);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos spot = FishingSpots.spotInCell(cx + dx, cz + dz);
                if (spot == null) continue;
                int ox = spot.getX() - px, oz = spot.getZ() - pz;
                if (ox * ox + oz * oz > 48 * 48 || !level.isLoaded(spot)) continue;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, spot.getX(), spot.getZ());
                BlockPos water = new BlockPos(spot.getX(), top - 1, spot.getZ());
                if (!level.getFluidState(water).is(FluidTags.WATER)) continue;
                double x = spot.getX() + 0.5 + (r.nextDouble() - 0.5) * FishingSpots.RADIUS;
                double z = spot.getZ() + 0.5 + (r.nextDouble() - 0.5) * FishingSpots.RADIUS;
                double y = top + 0.05;
                if (!level.getFluidState(BlockPos.containing(x, top - 1, z)).is(FluidTags.WATER)) continue;
                if (r.nextFloat() < 0.35f) level.addParticle(ParticleTypes.FISHING, x, y, z, 0, 0.02, 0);
                if (r.nextFloat() < 0.04f) {
                    // a fish jumps
                    for (int i = 0; i < 8; i++) {
                        level.addParticle(ParticleTypes.SPLASH, x + (r.nextDouble() - 0.5) * 0.4, y, z + (r.nextDouble() - 0.5) * 0.4,
                                (r.nextDouble() - 0.5) * 0.2, 0.25, (r.nextDouble() - 0.5) * 0.2);
                    }
                    level.playLocalSound(x, y, z, net.minecraft.sounds.SoundEvents.FISH_SWIM, net.minecraft.sounds.SoundSource.AMBIENT,
                            0.25f, 0.9f + r.nextFloat() * 0.3f, false);
                }
            }
        }
    }
}
