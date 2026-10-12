package com.skycraft.perf;

/**
 * A small per-thread, direct-mapped memo for 2D column functions (value of (x, z) for a given owner instance).
 *
 * <p>Minecraft's {@code cache_2d} remembers only the last column per density function, which is fine for the noise
 * fill (it walks columns one after another) but misses on every call when the climate sampler fills a chunk's biomes
 * (quart x, y and z change in the innermost loops) or when structure probes alternate between points. The benchmark
 * showed the Tamriel height function being recomputed thousands of times per chunk that way. Values depend only on
 * (owner, x, z), so a hit returns exactly what the function would compute.</p>
 *
 * <p>Use: {@code Table t = ColumnCache.table(); int s = t.slot(x, z); if (t.hit(s, this, x, z)) return t.value(s);}
 * ... compute ... {@code t.put(s, this, x, z, v)}. Allocation-free after the first call on a thread.</p>
 */
public final class ColumnCache {
    private static final int BITS = 10, SIZE = 1 << BITS, MASK = SIZE - 1;
    private static final ThreadLocal<Table> TABLES = ThreadLocal.withInitial(Table::new);

    private ColumnCache() {}

    public static final boolean OFF = Boolean.getBoolean("skycraft.perf.off");

    public static Table table() {
        return TABLES.get();
    }

    public static final class Table {
        private final long[] keys = new long[SIZE];
        private final Object[] owners = new Object[SIZE];
        private final double[] values = new double[SIZE];

        public int slot(int x, int z) {
            int h = x * 0x9E3779B1 + z * 0x85EBCA77;
            return (h ^ (h >>> 15)) & MASK;
        }

        public boolean hit(int slot, Object owner, int x, int z) {
            return owners[slot] == owner && keys[slot] == key(x, z);
        }

        public double value(int slot) {
            return values[slot];
        }

        public void put(int slot, Object owner, int x, int z, double v) {
            owners[slot] = owner;
            keys[slot] = key(x, z);
            values[slot] = v;
        }

        private static long key(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }
    }
}
