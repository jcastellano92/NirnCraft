package com.skycraft.tamriel.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.tamriel.MapSample;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The baked hydrology data ({@code hydrology.json}, written by {@code tools/worldgen/tamriel_map.py}): waterfalls,
 * springs and the river-aware points of interest. Immutable after loading, so every read is thread-safe and
 * deterministic. Waterfalls are applied to {@link MapSample}s as a sharp step in the water level (the 32-block
 * raster can only carry the smoothed profile), which the height function and the water feature then turn into a real
 * cliff and a falling curtain, identically in every chunk.
 */
public final class Hydrology {
    private static final String FILE = "/skycraft/tamriel/hydrology.json";
    /** Half length (blocks) along the flow of a waterfall's influence, and its blend zone. */
    private static final float REACH = 44f, FLAT = 20f, POOL = 14f;
    private static final int GRID = 64;

    /** A waterfall: lip centre, unit flow direction, the water levels above and below, channel half width. */
    public record Fall(int x, int z, float ux, float uz, float up, float down, float hw, boolean big, boolean secret) {
        public float drop() {
            return up - down;
        }
    }

    /** A river point of interest: kind name, position, facing (0 S, 1 W, 2 N, 3 E; towards the water), parameter. */
    public record Site(String kind, int x, int z, int facing, float param) {
        public int chunkX() {
            return x >> 4;
        }

        public int chunkZ() {
            return z >> 4;
        }
    }

    private static final Hydrology INSTANCE = load();

    private final Fall[] falls;
    private final Long2ObjectOpenHashMap<int[]> fallGrid = new Long2ObjectOpenHashMap<>();
    private final Site[] sites;
    private final Long2ObjectOpenHashMap<Site> siteByChunk = new Long2ObjectOpenHashMap<>();
    private final int[][] springs;

    private Hydrology(Fall[] falls, Site[] sites, int[][] springs) {
        this.falls = falls;
        this.sites = sites;
        this.springs = springs;
        Long2ObjectOpenHashMap<List<Integer>> tmp = new Long2ObjectOpenHashMap<>();
        for (int i = 0; i < falls.length; i++) {
            Fall f = falls[i];
            int c0x = Math.floorDiv((int) (f.x - REACH - f.hw - 20), GRID), c1x = Math.floorDiv((int) (f.x + REACH + f.hw + 20), GRID);
            int c0z = Math.floorDiv((int) (f.z - REACH - f.hw - 20), GRID), c1z = Math.floorDiv((int) (f.z + REACH + f.hw + 20), GRID);
            for (int cx = c0x; cx <= c1x; cx++) {
                for (int cz = c0z; cz <= c1z; cz++) {
                    tmp.computeIfAbsent(ChunkPos.asLong(cx, cz), k -> new ArrayList<>()).add(i);
                }
            }
        }
        for (var e : tmp.long2ObjectEntrySet()) {
            int[] a = new int[e.getValue().size()];
            for (int i = 0; i < a.length; i++) a[i] = e.getValue().get(i);
            fallGrid.put(e.getLongKey(), a);
        }
        for (Site s : sites) siteByChunk.putIfAbsent(ChunkPos.asLong(s.chunkX(), s.chunkZ()), s);
    }

    public static Hydrology get() {
        return INSTANCE;
    }

    private static Hydrology load() {
        try (InputStream in = Hydrology.class.getResourceAsStream(FILE)) {
            if (in == null) throw new IOException("missing " + FILE);
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray kinds = root.getAsJsonArray("kinds");
            JsonArray fa = root.getAsJsonArray("waterfalls");
            Fall[] falls = new Fall[fa.size()];
            for (int i = 0; i < falls.length; i++) {
                JsonArray a = fa.get(i).getAsJsonArray();
                int[] w = unwarp(a.get(0).getAsInt(), a.get(1).getAsInt());
                falls[i] = new Fall(w[0], w[1], a.get(2).getAsFloat(), a.get(3).getAsFloat(),
                        a.get(4).getAsFloat(), a.get(5).getAsFloat(), a.get(6).getAsFloat(), a.get(7).getAsInt() != 0,
                        a.get(8).getAsInt() != 0);
            }
            JsonArray sa = root.getAsJsonArray("sites");
            Site[] sites = new Site[sa.size()];
            for (int i = 0; i < sites.length; i++) {
                JsonArray a = sa.get(i).getAsJsonArray();
                int[] w = unwarp(a.get(1).getAsInt(), a.get(2).getAsInt());
                sites[i] = new Site(kinds.get(a.get(0).getAsInt()).getAsString(), w[0], w[1],
                        a.get(3).getAsInt(), a.get(4).getAsFloat());
            }
            JsonArray sp = root.getAsJsonArray("springs");
            int[][] springs = new int[sp.size()][];
            for (int i = 0; i < springs.length; i++) {
                JsonArray a = sp.get(i).getAsJsonArray();
                springs[i] = new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), Math.round(a.get(2).getAsFloat()), a.get(3).getAsInt()};
            }
            Skycraft.LOGGER.info("Loaded the Tamriel hydrology: {} waterfalls, {} river sites, {} springs", falls.length,
                    sites.length, springs.length);
            return new Hydrology(falls, sites, springs);
        } catch (IOException | RuntimeException e) {
            Skycraft.LOGGER.error("Could not load the Tamriel hydrology data; no waterfalls or river sites", e);
            return new Hydrology(new Fall[0], new Site[0], new int[0][]);
        }
    }

    /**
     * The world column whose raster position (after {@link MapRaster}'s domain warp) is (x, z): features baked at
     * raster coordinates appear at their raster position minus the warp.
     */
    static int[] unwarp(int x, int z) {
        double px = x, pz = z;
        for (int i = 0; i < 4; i++) {
            double nx = x - MapRaster.warpX((int) Math.round(px), (int) Math.round(pz));
            double nz = z - MapRaster.warpZ((int) Math.round(px), (int) Math.round(pz));
            px = nx;
            pz = nz;
        }
        return new int[]{(int) Math.round(px), (int) Math.round(pz)};
    }

    // ------------------------------------------------------------------ queries

    public Fall[] falls() {
        return falls;
    }

    public Site[] sites() {
        return sites;
    }

    public int[][] springs() {
        return springs;
    }

    /** The river site whose centre lies in chunk (cx, cz), or null. */
    @Nullable
    public Site siteInChunk(int cx, int cz) {
        return siteByChunk.get(ChunkPos.asLong(cx, cz));
    }

    /** Waterfalls whose influence covers the block, as indices into {@link #falls()} (null when none). */
    @Nullable
    private int[] near(int x, int z) {
        return fallGrid.get(ChunkPos.asLong(Math.floorDiv(x, GRID), Math.floorDiv(z, GRID)));
    }

    /** The nearest waterfall within {@code radius} blocks of (x, z), or null. */
    @Nullable
    public Fall nearestFall(int x, int z, int radius) {
        Fall best = null;
        long bd = (long) radius * radius;
        for (Fall f : falls) {
            long dx = f.x - x, dz = f.z - z, d = dx * dx + dz * dz;
            if (d <= bd) {
                bd = d;
                best = f;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the sample hook

    /**
     * Squares the water level of a sample that lies in a waterfall's reach: the raster's smooth profile is replaced
     * by {@code up} upstream of the lip and {@code down} below it (blended back to the raster over the outer part of
     * the reach), and the channel widens into a plunge pool just below the fall.
     */
    public void apply(int x, int z, MapSample out) {
        if (falls.length == 0 || Float.isNaN(out.waterLevel) || out.lakeDistance > 0) return;
        int[] idx = near(x, z);
        if (idx == null) return;
        for (int i : idx) {
            Fall f = falls[i];
            float dx = x - f.x, dz = z - f.z;
            float u = dx * f.ux + dz * f.uz;
            if (u < -REACH || u > REACH) continue;
            float v = -dx * f.uz + dz * f.ux;
            float lim = f.hw + 18f;
            if (Math.abs(v) > lim) continue;
            float raster = out.waterLevel;
            // only the water body this fall belongs to: its level must fit between the two sides
            if (raster > f.up + 6f || raster < f.down - 4f) continue;
            float edge = Math.abs(u) <= FLAT ? 0f : Math.min(1f, (Math.abs(u) - FLAT) / (REACH - FLAT));
            float side = u < 0 ? f.up : f.down;
            out.waterLevel = side + (raster - side) * edge * edge * (3 - 2 * edge);
            if (u > 0 && u < POOL) out.riverHalfWidth += 2f * (1f - u / POOL);
            return;
        }
    }
}
