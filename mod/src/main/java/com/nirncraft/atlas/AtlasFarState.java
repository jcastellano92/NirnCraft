package com.nirncraft.atlas;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Set;

/**
 * A block state as the far field sees it: the same state with its appearance-neutral properties at their defaults
 * (a fire's age, a crop's growth stage, leaves' distance, redstone power, a campfire's signal, ...). Those tick on
 * their own, so comparing real state ids made every burning or growing chunk count as changed again and again: the
 * change feed pushed it forever, the far field rebuilt forever and never went idle. Compare and journal by far()
 * instead. Matched by property name, so mods' own "age"-style properties count too.
 */
public final class AtlasFarState {
    private AtlasFarState() {}

    private static final Set<String> NEUTRAL = Set.of("age", "distance", "persistent", "power", "powered", "moisture", "note",
            "stage", "triggered", "enabled", "delay", "has_book", "occupied", "in_wall", "attached", "disarmed", "bloom",
            "shrieking", "can_summon", "signal_fire", "unstable", "sculk_sensor_phase", "honey_level", "hatch", "dusted",
            "cracked", "bites", "eggs", "pickles", "candles", "level_composter");

    private static volatile int[] cache = new int[0];

    /** The far field's id for state id `id` (cached). */
    public static int far(int id) {
        int[] c = cache;
        if (id < c.length && c[id] != 0) return c[id] - 1;
        int f = Block.getId(far(Block.stateById(id)));
        synchronized (AtlasFarState.class) {
            int[] cc = cache;
            if (id >= cc.length) cc = java.util.Arrays.copyOf(cc, Math.max(id + 1, cc.length * 2 + 1024));
            cc[id] = f + 1;
            cache = cc;
        }
        return f;
    }

    /** The far field's state for s. */
    public static BlockState far(BlockState s) {
        BlockState out = s;
        BlockState def = s.getBlock().defaultBlockState();
        for (Property<?> p : s.getProperties())
            if (NEUTRAL.contains(p.getName())) out = copy(out, def, p);
        return out;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState to, BlockState from, Property<T> p) {
        return to.setValue(p, from.getValue(p));
    }

    /** far() of a state's id. */
    public static int farId(BlockState s) {
        return far(Block.getId(s));
    }
}
