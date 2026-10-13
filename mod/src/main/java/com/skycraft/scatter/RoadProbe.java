package com.skycraft.scatter;

import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;

/**
 * "Is there a road here?" for the scatter system, from the best source available.
 *
 * <ul>
 *     <li>On the server thread, with the road module's read-only {@code com.skycraft.roads.RoadCells} present, the
 *     live network answers (paved band plus margin).</li>
 *     <li>Everywhere else (worldgen threads, a level whose roads are not planned yet) the Tamriel map's road corridor
 *     channel answers: the planner routes along those corridors, so it is a good proxy and is fully deterministic.</li>
 * </ul>
 *
 * <p>Worldgen never touches the live network: it is server-thread data, and chunks are often generated before the
 * planner reaches them. Roadside dressing of paved chunks belongs to the road module; it can reuse this system's
 * props through {@link Scatter#dress}.</p>
 */
public final class RoadProbe {
    private static final Method IS_ROAD = find();

    private RoadProbe() {}

    private static Method find() {
        try {
            Class<?> c = Class.forName("com.skycraft.roads.RoadCells");
            return c.getMethod("isRoad", Level.class, int.class, int.class, int.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** True when the live road network is available to this process. */
    public static boolean live() {
        return IS_ROAD != null;
    }

    /** The map's road corridor strength (0..1) at a column. */
    public static float hint(int x, int z) {
        MapSample s = TamrielMap.sampleScratch(x, z);
        return s.road;
    }

    /**
     * True when a road's paved band is within {@code margin} blocks of (x, z). Server thread only for the live answer;
     * the map hint (strength at least 0.5) is used when the live network cannot answer.
     */
    public static boolean nearRoad(Level level, int x, int z, int margin, boolean serverThread) {
        if (IS_ROAD != null && !level.isClientSide) { // plan-backed: answers on any thread
            try {
                return (Boolean) IS_ROAD.invoke(null, level, x, z, margin);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // fall through to the map hint
            }
        }
        return hint(x, z) >= 0.5f;
    }
}
