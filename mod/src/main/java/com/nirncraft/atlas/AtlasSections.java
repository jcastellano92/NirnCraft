package com.nirncraft.atlas;

import com.nirncraft.network.SkyNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Real blocks for the far field's finest levels (docs/ATLAS-LOD.md): the sections around each chunk's surface as the
 * server has them (from 16 blocks under its lowest ground to its highest roof or crown), so a tree is that tree, a
 * house that house, at level 0, and the far field generates its coarser levels from them. Kept per chunk as a small
 * compressed blob (a palette and indices per section, the chunk's grass and foliage colours), stored per 256-block
 * tile next to the change journal, and pushed to each player for the chunks in their fine window they have not had.
 * Sub-block things (grass tufts, flowers, torches, fences) are left out: in the far field they are the ground's colour
 * and far lights, not cubes.
 */
public final class AtlasSections {
    private AtlasSections() {}

    /** Window half-size in LOD-0 tiles for sections: the LOD 4 regions' reach is wider, but a section is 16 blocks. */
    static final int RADIUS_TILES = 6;
    private static final int PER_PACKET = 64;

    static final class TileSections {
        long version;
        final Map<Long, Entry> chunks = new HashMap<>();  // ChunkPos.pack -> blob
        boolean loaded;  // read from disk (lazily)
    }

    record Entry(byte[] blob, long version) {}

    private static final Map<Long, TileSections> TILES = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<Long, Long>> SENT = new ConcurrentHashMap<>();
    private static Path dir;
    private static final java.util.Set<Long> DIRTY_TILES = ConcurrentHashMap.newKeySet();

    static void open(MinecraftServer server) {
        dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/nirncraft_atlas/sections");
        TILES.clear();
        SENT.clear();
        DIRTY_TILES.clear();
    }

    /** A block the far field keeps as a voxel (solid, leaves, slabs and stairs, water and lava, fire and lights). */
    static boolean kept(BlockState s) {
        if (s.isAir()) return false;
        if (s.isSolidRender() || s.is(BlockTags.LEAVES) || !s.getFluidState().isEmpty()) return true;
        if (s.getLightEmission() >= 10) return true;
        return s.getBlock() instanceof SlabBlock || s.getBlock() instanceof StairBlock;
    }

    /** Chunk c's surface sections (lowest ground - 16 .. highest top), recorded when they changed. Server thread. */
    static void record(ServerLevel level, LevelChunk c, int lowY, int highY) {
        if (dir == null) open(level.getServer());
        int sy0 = Math.floorDiv(lowY - 16, 16), sy1 = Math.floorDiv(highY, 16);
        int minSec = c.getMinSectionY();
        sy0 = Math.max(sy0, minSec);
        sy1 = Math.min(sy1, c.getMaxSectionY());
        if (sy1 < sy0) return;
        int cx = c.getPos().x(), cz = c.getPos().z();
        BlockPos centre = new BlockPos((cx << 4) + 8, highY, (cz << 4) + 8);
        var biome = level.getBiome(centre).value();
        int grass = biome.getGrassColor(centre.getX(), centre.getZ()) & 0xFFFFFF, foliage = biome.getFoliageColor() & 0xFFFFFF;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            out.writeInt(cx);
            out.writeInt(cz);
            out.writeInt(sy0);
            out.writeByte(sy1 - sy0 + 1);
            out.writeInt(grass);
            out.writeInt(foliage);
            for (int sy = sy0; sy <= sy1; sy++) {
                LevelChunkSection sec = c.getSection(sy - minSec);
                Map<Integer, Integer> pal = new HashMap<>();
                List<Integer> ids = new ArrayList<>();
                ids.add(0);
                pal.put(0, 0);
                short[] idx = new short[4096];
                if (sec != null && !sec.hasOnlyAir()) {
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                BlockState s = sec.getBlockState(x, y, z);
                                int id = kept(s) ? AtlasFarState.farId(s) : 0;
                                Integer k = pal.get(id);
                                if (k == null) {
                                    k = ids.size();
                                    ids.add(id);
                                    pal.put(id, k);
                                }
                                idx[x + 16 * (z + 16 * y)] = (short) (int) k;
                            }
                }
                out.writeShort(ids.size());
                for (int id : ids) out.writeInt(id);
                if (ids.size() == 1) continue;  // all air
                boolean narrow = ids.size() <= 256;
                for (short v : idx) {
                    if (narrow) out.writeByte(v);
                    else out.writeShort(v);
                }
            }
        } catch (IOException e) {
            return;
        }
        byte[] blob = bytes.toByteArray();
        long tk = AtlasEdits.tileKey(Math.floorDiv(cx << 4, AtlasEdits.TILE), Math.floorDiv(cz << 4, AtlasEdits.TILE));
        TileSections t = tile(tk);
        synchronized (t) {
            Entry old = t.chunks.get(net.minecraft.world.level.ChunkPos.pack(cx, cz));
            if (old != null && java.util.Arrays.equals(old.blob(), blob)) return;
            t.version++;
            t.chunks.put(net.minecraft.world.level.ChunkPos.pack(cx, cz), new Entry(blob, t.version));
        }
        DIRTY_TILES.add(tk);
    }

    private static TileSections tile(long tk) {
        TileSections t = TILES.computeIfAbsent(tk, k -> new TileSections());
        synchronized (t) {
            if (!t.loaded) {
                t.loaded = true;
                load(tk, t);
            }
        }
        return t;
    }

    /** Each player's fine window: the chunks' sections newer than what that player has. */
    static void push(MinecraftServer server, ServerLevel level) {
        if (dir == null) open(server);
        for (ServerPlayer p : level.players()) {
            Map<Long, Long> sent = SENT.computeIfAbsent(p.getUUID(), u -> new HashMap<>());
            int ptx = Math.floorDiv((int) Math.floor(p.getX()), AtlasEdits.TILE), ptz = Math.floorDiv((int) Math.floor(p.getZ()), AtlasEdits.TILE);
            for (int tz = ptz - RADIUS_TILES; tz <= ptz + RADIUS_TILES; tz++)
                for (int tx = ptx - RADIUS_TILES; tx <= ptx + RADIUS_TILES; tx++) {
                    long tk = AtlasEdits.tileKey(tx, tz);
                    if (!TILES.containsKey(tk) && !onDisk(tk)) continue;
                    TileSections t = tile(tk);
                    long have = sent.getOrDefault(tk, 0L);
                    List<byte[]> blobs = new ArrayList<>();
                    synchronized (t) {
                        if (t.version <= have) continue;
                        for (Entry e : t.chunks.values()) if (e.version() > have) blobs.add(e.blob());
                        sent.put(tk, t.version);
                    }
                    for (int i = 0; i < blobs.size(); i += PER_PACKET)
                        SkyNetwork.sendToPlayer(p, new AtlasNet.FarSections(blobs.subList(i, Math.min(blobs.size(), i + PER_PACKET)).toArray(new byte[0][])));
                }
        }
        SENT.keySet().removeIf(u -> server.getPlayerList().getPlayer(u) == null);
    }

    // ---- persistence: one file per tile -----------------------------------------------------------------------

    private static Path file(long tk) {
        return dir.resolve((int) (tk >> 32) + "_" + (int) tk + ".bin");
    }

    private static boolean onDisk(long tk) {
        return dir != null && Files.isRegularFile(file(tk));
    }

    private static void load(long tk, TileSections t) {
        if (dir == null || !Files.isRegularFile(file(tk))) return;
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(Files.newInputStream(file(tk))))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long k = in.readLong();
                byte[] b = new byte[in.readInt()];
                in.readFully(b);
                t.version++;
                t.chunks.put(k, new Entry(b, t.version));
            }
        } catch (IOException e) {
            com.nirncraft.NirnCraft.LOGGER.warn("atlas: far sections of tile {} unreadable ({})", tk, e.toString());
        }
    }

    /** Writes the tiles whose sections changed (every minute and at stop). */
    static void save() {
        if (dir == null) return;
        for (Long tk : new ArrayList<>(DIRTY_TILES)) {
            DIRTY_TILES.remove(tk);
            TileSections t = TILES.get(tk);
            if (t == null) continue;
            try {
                Files.createDirectories(dir);
                Path tmp = file(tk).resolveSibling(file(tk).getFileName() + ".tmp");
                try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(Files.newOutputStream(tmp)))) {
                    synchronized (t) {
                        out.writeInt(t.chunks.size());
                        for (Map.Entry<Long, Entry> e : t.chunks.entrySet()) {
                            out.writeLong(e.getKey());
                            out.writeInt(e.getValue().blob().length);
                            out.write(e.getValue().blob());
                        }
                    }
                }
                Files.move(tmp, file(tk), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                com.nirncraft.NirnCraft.LOGGER.warn("atlas: far sections of tile {} not saved ({})", tk, e.toString());
            }
        }
    }

    static void stop() {
        save();
        TILES.clear();
        SENT.clear();
        dir = null;
    }

    // ---- decoding (client) --------------------------------------------------------------------------------------

    /** A decoded chunk: its first section y, per section the block state ids (4096, x + 16 * (z + 16 * y)), tints. */
    record Decoded(int cx, int cz, int sy0, int[][] sections, int grass, int foliage) {}

    static Decoded decode(byte[] blob) throws IOException {
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new java.io.ByteArrayInputStream(blob)))) {
            int cx = in.readInt(), cz = in.readInt(), sy0 = in.readInt(), n = in.readUnsignedByte();
            int grass = in.readInt(), foliage = in.readInt();
            int[][] secs = new int[n][];
            for (int s = 0; s < n; s++) {
                int pn = in.readUnsignedShort();
                int[] pal = new int[pn];
                for (int i = 0; i < pn; i++) pal[i] = in.readInt();
                int[] ids = new int[4096];
                if (pn > 1) {
                    boolean narrow = pn <= 256;
                    for (int i = 0; i < 4096; i++) {
                        int k = narrow ? in.readUnsignedByte() : in.readUnsignedShort();
                        ids[i] = k < pn ? pal[k] : 0;
                    }
                }
                secs[s] = ids;
            }
            return new Decoded(cx, cz, sy0, secs, grass, foliage);
        }
    }
}
