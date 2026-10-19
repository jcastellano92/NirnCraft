package com.skycraft.seas;

import com.skycraft.Skycraft;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.fish.Salmon;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.List;

/**
 * The autumn salmon run: for a third of the year (days 48..71 of a 96-day cycle) the salmon that {@link SeaLife}
 * places in rivers are more numerous and swim upstream, up the slope of the map's water level, and linger where the
 * river runs out of fall (the spawning pools at the heads). Cheap on purpose: a nudge to fish near players every
 * half second, no new AI.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class RiverRun {
    private static final int YEAR = 96, RUN_FROM = 48, RUN_TO = 72;
    private static final int INTERVAL = 10;
    private static final double PUSH = 0.07;

    private RiverRun() {}

    /** True during the run. */
    public static boolean running(ServerLevel level) {
        long day = level.getOverworldClockTime() / 24000L;
        long d = Math.floorMod(day, (long) YEAR);
        return d >= RUN_FROM && d < RUN_TO;
    }

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = player.level();
        if (level.dimension() != Level.OVERWORLD || (level.getGameTime() + player.getId()) % INTERVAL != 0) return;
        if (!running(level)) return;
        List<Salmon> fish = level.getEntitiesOfClass(Salmon.class, player.getBoundingBox().inflate(40), f -> f.isInWater());
        if (fish.isEmpty()) return;
        MapSample s = new MapSample();
        MapSample a = new MapSample();
        MapSample b = new MapSample();
        for (Salmon f : fish) {
            int x = f.getBlockX(), z = f.getBlockZ();
            TamrielMap.sample(x, z, s);
            if (!s.inWater() || s.riverHalfWidth < 2f || !level.getFluidState(f.blockPosition()).is(FluidTags.WATER)) continue;
            // upstream = up the water level: compare the level a few blocks either side along each axis
            TamrielMap.sample(x + 10, z, a);
            TamrielMap.sample(x - 10, z, b);
            double gx = level(a) - level(b);
            TamrielMap.sample(x, z + 10, a);
            TamrielMap.sample(x, z - 10, b);
            double gz = level(a) - level(b);
            double n = Math.hypot(gx, gz);
            if (n < 0.15) continue; // a pool at the head: the fish stay
            Vec3 v = f.getDeltaMovement();
            f.setDeltaMovement(v.x + gx / n * PUSH, v.y, v.z + gz / n * PUSH);
            f.setYRot((float) (Math.toDegrees(Math.atan2(-gx, gz))));
        }
    }

    private static double level(MapSample s) {
        return Float.isNaN(s.waterLevel) ? 0 : s.waterLevel;
    }
}
