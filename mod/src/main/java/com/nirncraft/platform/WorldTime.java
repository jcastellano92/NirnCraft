package com.nirncraft.platform;

/** Public native world-clock updates, preserving each dimension's authored clock identity. */
public final class WorldTime {
    private WorldTime() {}
    public static void set(net.minecraft.server.level.ServerLevel level, long ticks) {
        var clock = level.dimensionType().defaultClock().orElseThrow(() -> new IllegalStateException("Dimension has no advancing clock: " + level.dimension()));
        level.clockManager().setTotalTicks(clock, ticks);
    }
}
