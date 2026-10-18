package com.skycraft.roads;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Optional routing corridors: traced road polylines (block coordinates) the planner strongly prefers, so the named
 * highways follow the historic routes (e.g. Cyrodiil's roads as Oblivion's map has them). Read once from the class-path
 * resource {@code skycraft/roads/corridors.json}; without it the planner relies on the Tamriel map's road hints alone.
 *
 * <p>Format (geography only: route shapes, no game data): either a JSON array of polylines, or an object with
 * {@code routes} and/or {@code polylines} arrays (optionally wrapped in a {@code Tamriel} object). A polyline is a list
 * of {@code [x, z]} points or an object holding one under {@code mc}, {@code pts} or {@code points}; extra coordinates
 * after x and z are ignored. The shipped file is Cyrodiil's road network as Oblivion's map has it, warped onto our
 * city anchors by the provinces workstream ({@code tools/provinces.py warp_roads}, its {@code roads_mc.json}); replace
 * the file to retrace, and existing worlds pick it up with {@code /skycraft-roads replan}.</p>
 */
final class Corridors {
    /** Corridor raster cell (blocks) and half width (blocks) around each polyline. */
    private static final int CELL = 16, HALF_WIDTH = 24;

    private static final class Holder {
        static final LongOpenHashSet CELLS = load();
    }

    private Corridors() {}

    /** True if (x, z) lies on a traced corridor. Thread-safe (immutable after loading). */
    static boolean on(int x, int z) {
        LongOpenHashSet cells = Holder.CELLS;
        return !cells.isEmpty() && cells.contains(ChunkPos.pack(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL)));
    }

    static int size() {
        return Holder.CELLS.size();
    }

    private static LongOpenHashSet load() {
        LongOpenHashSet cells = new LongOpenHashSet();
        try (InputStream in = Corridors.class.getResourceAsStream("/skycraft/roads/corridors.json")) {
            if (in == null) return cells;
            JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            int lines = 0;
            if (root.isJsonArray()) {
                for (JsonElement e : root.getAsJsonArray()) lines += add(e, cells);
            } else {
                JsonObject o = root.getAsJsonObject();
                if (o.has("Tamriel")) o = o.getAsJsonObject("Tamriel"); // the warped Oblivion export
                for (String key : new String[]{"routes", "polylines"}) {
                    if (!o.has(key)) continue;
                    for (JsonElement e : o.getAsJsonArray(key)) lines += add(e, cells);
                }
            }
            Skycraft.LOGGER.info("Skycraft roads: {} routing corridors ({} cells)", lines, cells.size());
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Skycraft roads: could not read corridors.json", e);
        }
        return cells;
    }

    private static int add(JsonElement e, LongOpenHashSet cells) {
        JsonArray pts;
        if (e.isJsonArray()) {
            pts = e.getAsJsonArray(); // a bare list of points
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            pts = o.has("mc") ? o.getAsJsonArray("mc") : o.has("pts") ? o.getAsJsonArray("pts")
                    : o.has("points") ? o.getAsJsonArray("points") : null;
        } else {
            return 0;
        }
        if (pts == null || pts.size() < 2) return 0;
        double px = Double.NaN, pz = Double.NaN;
        for (JsonElement p : pts) {
            JsonArray a = p.getAsJsonArray();
            double x = a.get(0).getAsDouble(), z = a.get(1).getAsDouble();
            if (!Double.isNaN(px)) stamp(px, pz, x, z, cells);
            px = x;
            pz = z;
        }
        return 1;
    }

    /** Marks every cell within {@link #HALF_WIDTH} of the segment. */
    private static void stamp(double x0, double z0, double x1, double z1, LongOpenHashSet cells) {
        double len = Math.hypot(x1 - x0, z1 - z0);
        int steps = Math.max(1, (int) Math.ceil(len / (CELL / 2.0)));
        int r = (HALF_WIDTH + CELL - 1) / CELL;
        for (int s = 0; s <= steps; s++) {
            double t = s / (double) steps;
            int cx = (int) Math.floor((x0 + (x1 - x0) * t) / CELL), cz = (int) Math.floor((z0 + (z1 - z0) * t) / CELL);
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) cells.add(ChunkPos.pack(cx + dx, cz + dz));
        }
    }
}
