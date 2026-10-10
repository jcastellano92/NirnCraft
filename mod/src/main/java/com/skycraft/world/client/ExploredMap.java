package com.skycraft.world.client;

import com.skycraft.Skycraft;
import com.skycraft.core.SkyData;
import com.skycraft.world.WorldData;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fog of war of the world map: which 64-block cells this player has seen, per world and dimension. Cells within
 * sight of the player are revealed as they travel, and every discovered location reveals its surroundings.
 * Kept on the client (the server never needs it) in {@code <game dir>/skycraft/explored/<world>/<dimension>.bin},
 * with coarser copies of the set for the zoomed-out map.
 */
@OnlyIn(Dist.CLIENT)
public final class ExploredMap {
    public static final int CELL_SHIFT = 6;
    /** Coarser levels: level k has cells of {@code 64 << k} blocks. */
    public static final int LEVELS = 6;
    private static final int SIGHT = 176;
    private static final int LOCATION_REVEAL = 250;
    private static final int SAVE_INTERVAL = 20 * 120;

    private static final LongOpenHashSet[] CELLS = new LongOpenHashSet[LEVELS];
    private static String key;
    private static boolean dirty;
    private static long version;
    private static long locationsStamp = Long.MIN_VALUE;
    private static int ticks;

    static {
        for (int i = 0; i < LEVELS; i++) CELLS[i] = new LongOpenHashSet();
    }

    private ExploredMap() {}

    /** Bumped whenever a cell is revealed (the fog texture rebuilds on change). */
    public static long version() {
        return version;
    }

    public static boolean explored(int level, int cx, int cz) {
        return CELLS[level].contains(ChunkPos.asLong(cx, cz));
    }

    /** Client tick: reveal around the player, around discovered locations, and save now and then. */
    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        String k = worldKey(mc);
        if (!k.equals(key)) {
            save();
            load(k);
        }
        if (++ticks % 10 == 0) {
            revealCircle(mc.player.getBlockX(), mc.player.getBlockZ(), SIGHT);
            revealLocations(mc);
        }
        if (dirty && ticks % SAVE_INTERVAL == 0) save();
    }

    /** Saves and forgets the current world (logout). */
    public static void reset() {
        save();
        key = null;
        for (LongOpenHashSet s : CELLS) s.clear();
        locationsStamp = Long.MIN_VALUE;
        version++;
    }

    public static void revealCircle(int x, int z, int radius) {
        int cell = 1 << CELL_SHIFT;
        int cx = x >> CELL_SHIFT;
        int cz = z >> CELL_SHIFT;
        int r = radius / cell + 1;
        long r2 = (long) radius * radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                long ox = ((long) (cx + dx) << CELL_SHIFT) + cell / 2 - x;
                long oz = ((long) (cz + dz) << CELL_SHIFT) + cell / 2 - z;
                if (ox * ox + oz * oz <= r2) add(cx + dx, cz + dz);
            }
        }
    }

    private static void add(int cx, int cz) {
        if (!CELLS[0].add(ChunkPos.asLong(cx, cz))) return;
        for (int k = 1; k < LEVELS; k++) CELLS[k].add(ChunkPos.asLong(cx >> k, cz >> k));
        dirty = true;
        version++;
    }

    private static void revealLocations(Minecraft mc) {
        ListTag list = SkyData.get(mc.player).module(WorldData.MODULE).getList("discovered", Tag.TAG_COMPOUND);
        String dim = mc.level.dimension().location().toString();
        long stamp = list.size() * 31L + dim.hashCode();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag loc = list.getCompound(i);
            stamp = stamp * 31 + loc.getInt("x") * 7919L + loc.getInt("z") + (loc.getBoolean("known") ? 1 : 0);
        }
        if (stamp == locationsStamp) return;
        locationsStamp = stamp;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag loc = list.getCompound(i);
            if (!loc.getString("dim").equals(dim) || loc.getBoolean("known")) continue;
            revealCircle(loc.getInt("x"), loc.getInt("z"), LOCATION_REVEAL);
        }
    }

    // ------------------------------------------------------------------ persistence

    private static String worldKey(Minecraft mc) {
        String world;
        if (mc.getSingleplayerServer() != null) {
            Path root = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            world = "sp_" + (root.getFileName() != null ? root.getFileName().toString() : "world");
        } else {
            ServerData server = mc.getCurrentServer();
            world = "mp_" + (server != null ? server.ip : "unknown");
        }
        return sanitize(world) + "/" + sanitize(mc.level.dimension().location().toString());
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Path file(String k) {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("skycraft").resolve("explored").resolve(k + ".bin");
    }

    private static void load(String k) {
        key = k;
        for (LongOpenHashSet s : CELLS) s.clear();
        locationsStamp = Long.MIN_VALUE;
        dirty = false;
        version++;
        Path f = file(k);
        if (!Files.isRegularFile(f)) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long c = in.readLong();
                add(ChunkPos.getX(c), ChunkPos.getZ(c));
            }
            dirty = false;
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Could not read explored map {}", f, e);
        }
    }

    private static void save() {
        if (key == null || !dirty) return;
        dirty = false;
        Path f = file(key);
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(CELLS[0].size());
                for (LongIterator it = CELLS[0].iterator(); it.hasNext(); ) out.writeLong(it.nextLong());
            }
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Could not save explored map {}", f, e);
        }
    }
}
