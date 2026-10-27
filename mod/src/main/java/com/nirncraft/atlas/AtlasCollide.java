package com.nirncraft.atlas;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * Collision agreement (docs/ATLAS-GPU-LOOP.md section 4, step 12): the blocks atlas holds in level 0 against the blocks the
 * client level (the server's chunks, which collisions read) holds, around the player, as the walk goes. Test only: the
 * grid is read back from the GPU ({@code atlas_debug_read_blocks}). Both sides are compared as near ids (the renderer's
 * state identity: look-alike states share one), the grid's material ids mapped back to the state they were made for.
 */
public final class AtlasCollide {
    private AtlasCollide() {}

    /** The client level's near ids around (px, py, pz), taken on the client thread: {x, y, z, nearId} per position. */
    public static int[] expected(Minecraft mc, int px, int py, int pz, int radius, int below, int above) {
        ClientLevel level = mc.level;
        int side = 2 * radius + 1, n = side * side * (below + above + 1);
        int[] out = new int[n * 4];
        int i = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = -below; dy <= above; dy++)
            for (int dz = -radius; dz <= radius; dz++)
                for (int dx = -radius; dx <= radius; dx++) {
                    p.set(px + dx, py + dy, pz + dz);
                    boolean loaded = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) != null;
                    out[i++] = p.getX();
                    out[i++] = p.getY();
                    out[i++] = p.getZ();
                    out[i++] = loaded ? AtlasClientSections.nearIdOf(level.getBlockState(p)) : -1;
                }
        return out;
    }

    /**
     * Reads the grid at the positions {@link #expected} took and compares. Any thread (the GPU readback waits). Result:
     * checked, mismatches, unloaded (the client chunk or atlas's cell missing), and up to 12 mismatches described.
     */
    public static JsonObject compare(int[] expected) {
        JsonObject o = new JsonObject();
        var lease = AtlasClient.acquireInfoContext();
        AtlasMaterials mats = lease == null ? null : lease.materials();
        if (lease == null || mats == null) {
            if (lease != null) lease.close();
            o.addProperty("error", "atlas is not running");
            return o;
        }
        try (lease) {
        AtlasNative.Context ctx = lease.context();
        int n = expected.length / 4;
        int[] xyz = new int[n * 3];
        for (int i = 0; i < n; i++) {
            xyz[i * 3] = expected[i * 4];
            xyz[i * 3 + 1] = expected[i * 4 + 1];
            xyz[i * 3 + 2] = expected[i * 4 + 2];
        }
        int[] grid = ctx.readBlocks(xyz);
        if (grid == null) {
            o.addProperty("error", "the core cannot read the grid back");
            return o;
        }
        Map<Integer, Integer> index = mats.stateIndex();
        int checked = 0, bad = 0, unloaded = 0;
        JsonArray samples = new JsonArray();
        for (int i = 0; i < n; i++) {
            int want = expected[i * 4 + 3];
            int g = grid[i];
            if (want < 0 || g == 0xFFFE) {
                unloaded++;
                continue;
            }
            checked++;
            if (same(want, g, index)) continue;
            bad++;
            if (samples.size() < 12) {
                JsonObject s = new JsonObject();
                s.addProperty("pos", expected[i * 4] + "," + expected[i * 4 + 1] + "," + expected[i * 4 + 2]);
                s.addProperty("client", want == 0 ? "air/unkept" : String.valueOf(Block.stateById(want)));
                Integer gs = index.get(g);
                s.addProperty("atlas", g == 0 ? "air" : gs == null ? "material " + g : gs >= 0 ? String.valueOf(Block.stateById(gs))
                        : String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.byId(-(gs + 1))));
                samples.add(s);
            }
        }
        o.addProperty("checked", checked);
        o.addProperty("mismatches", bad);
        o.addProperty("unloaded", unloaded);
        o.add("samples", samples);
        return o;
        }
    }

    private static boolean same(int wantNearId, int mat, Map<Integer, Integer> index) {
        if (wantNearId == 0) return mat == 0;
        if (mat == 0) return false;
        Integer s = index.get(mat);
        if (s == null) return false;
        if (s >= 0) return s == wantNearId;
        BlockState want = Block.stateById(wantNearId);
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(want.getBlock()) == -(s + 1);
    }
}
