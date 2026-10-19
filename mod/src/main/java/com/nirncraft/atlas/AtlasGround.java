package com.nirncraft.atlas;

import com.nirncraft.qa.CameraPlanner;
import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.TamrielMap;
import com.nirncraft.tamriel.terrain.TamrielHeight;
import net.minecraft.server.MinecraftServer;

/**
 * The world's exact terrain columns without chunks (the seeded TamrielHeight + the map's water, as the far field and
 * world generation see them), for planners such as the camera tour. Thread-safe.
 */
public final class AtlasGround implements CameraPlanner.Ground {
    private final TamrielHeight height;

    public AtlasGround(TamrielHeight height) {
        this.height = height;
    }

    /** This server's world, or null when its overworld is not the Tamriel generator. */
    public static AtlasGround forServer(MinecraftServer server) {
        TamrielHeight h = AtlasExactSource.heightOf(server);
        return h == null ? null : new AtlasGround(h);
    }

    @Override
    public double terrain(int x, int z) {
        MapSample m = new MapSample();
        TamrielMap.sample(x, z, m);
        return height.height(x, z, m);
    }

    @Override
    public double surface(int x, int z) {
        MapSample m = new MapSample();
        TamrielMap.sample(x, z, m);
        double y = height.height(x, z, m);
        float w = AtlasExactSource.waterAt(y, m);
        return Float.isNaN(w) ? y : Math.max(y, w);
    }
}
