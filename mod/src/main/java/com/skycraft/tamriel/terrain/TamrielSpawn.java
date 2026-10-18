package com.skycraft.tamriel.terrain;

import com.skycraft.Skycraft;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.neoforged.neoforge.event.level.LevelEvent;

/** New Tamriel worlds start where Skyrim does: on the road outside Helgen. */
public final class TamrielSpawn {
    public static final ResourceKey<NoiseGeneratorSettings> TAMRIEL_SETTINGS =
            ResourceKey.create(Registries.NOISE_SETTINGS, Identifier.fromNamespaceAndPath(Skycraft.MODID, "tamriel"));
    private static final int HELGEN_X = -24, HELGEN_Z = 1143;

    private TamrielSpawn() {}

    /** The generator is available before levels, so startup can warm only Tamriel plans. */
    public static boolean isTamriel(net.minecraft.server.MinecraftServer server) {
        var stem = server.getWorldGenSettings().dimensions().dimensions().get(net.minecraft.world.level.dimension.LevelStem.OVERWORLD);
        return stem != null && stem.generator() instanceof NoiseBasedChunkGenerator generator
                && generator.generatorSettings().is(TAMRIEL_SETTINGS);
    }

    public static boolean isTamriel(ServerLevel level) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        return level.dimension() == Level.OVERWORLD && gen instanceof NoiseBasedChunkGenerator ng
                && ng.generatorSettings().is(TAMRIEL_SETTINGS);
    }

    public static void onCreateSpawn(LevelEvent.CreateSpawnPosition event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !isTamriel(level)) return;
        int cx = HELGEN_X, cz = HELGEN_Z, radius = 120;
        Place helgen = Places.byId("helgen").orElse(null);
        if (helgen != null) {
            cx = helgen.x();
            cz = helgen.z();
            radius = helgen.radius();
        }
        // just outside the town, on the north road towards Riverwood
        BlockPos spawn = findDry(level, cx, cz - radius - 16);
        event.getSettings().setSpawn(net.minecraft.world.level.storage.LevelData.RespawnData.of(level.dimension(), spawn, 0f, 0f));
        event.setCanceled(true);
        Skycraft.LOGGER.info("Tamriel world spawn set near Helgen at {}", spawn);
    }

    private static BlockPos findDry(ServerLevel level, int x0, int z0) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        MapSample s = new MapSample();
        for (int r = 0; r <= 96; r += 8) {
            for (int a = 0; a < Math.max(1, r); a += 8) {
                double ang = a / (double) Math.max(1, r) * Math.PI * 2;
                int x = x0 + (int) Math.round(Math.cos(ang) * r), z = z0 + (int) Math.round(Math.sin(ang) * r);
                TamrielMap.sample(x, z, s);
                if (s.coastDistance < 16 || s.river > 0.2f || s.lake > 0.2f) continue;
                int y = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState());
                // open ground, not the floor of a cave mouth or ravine: near the map's surface and level around
                if (y > TamrielMap.SEA_LEVEL + 1 && Math.abs(y - s.elevation) < 12 && level(gen, level, x, z, y)) return new BlockPos(x, y, z);
            }
        }
        int y = gen.getBaseHeight(x0, z0, Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState());
        return new BlockPos(x0, Math.max(y, TamrielMap.SEA_LEVEL + 1), z0);
    }

    /** True when the ground 6 blocks around (x, z) is within 3 blocks of y. */
    private static boolean level(ChunkGenerator gen, ServerLevel level, int x, int z, int y) {
        int[][] around = {{6, 0}, {-6, 0}, {0, 6}, {0, -6}};
        for (int[] d : around) {
            int h = gen.getBaseHeight(x + d[0], z + d[1], Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState());
            if (Math.abs(h - y) > 3) return false;
        }
        return true;
    }
}
