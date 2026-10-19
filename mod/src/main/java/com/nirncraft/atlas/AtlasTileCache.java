package com.nirncraft.atlas;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Encoded far tiles on disk (AtlasTileCodec bytes, one file per tile). A tile is the generator's surface, never the
 * edited world, so it is computed once per world and kept: the tile server, the single-player exact source and a LAN
 * host's own client all share one cache under the world, and multiplayer clients keep theirs per world id.
 */
final class AtlasTileCache {
    private final Path dir;

    AtlasTileCache(Path dir) {
        this.dir = dir;
    }

    /** The world's cache (dedicated, integrated and LAN-hosted servers alike). */
    static AtlasTileCache forServer(MinecraftServer server) {
        java.nio.file.Path root = server.getWorldPath(LevelResource.DATA).resolve("nirncraft_atlas");
        String name = "tiles-v" + AtlasTileCodec.VERSION + "-" + AtlasTileCodec.identity();
        pruneOthers(root, "tiles-", name);
        return new AtlasTileCache(root.resolve(name));
    }

    /**
     * Deletes the caches of other generations next to this one (`prefix`*, not `keep`): tiles of an older world
     * generation or painting code are never read again, and a world's data folder should not grow with every build.
     * In the background (a big cache takes a moment to delete).
     */
    static void pruneOthers(java.nio.file.Path root, String prefix, String keep) {
        if (!Files.isDirectory(root)) return;
        try (var dirs = Files.list(root)) {
            List<java.nio.file.Path> old = dirs.filter(d -> Files.isDirectory(d) && d.getFileName().toString().startsWith(prefix)
                    && !d.getFileName().toString().equals(keep)).toList();
            if (old.isEmpty()) return;
            Thread t = new Thread(() -> {
                for (java.nio.file.Path d : old) {
                    try (var walk = Files.walk(d)) {
                        walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
                    } catch (IOException | RuntimeException e) {
                        // left for the next start
                    }
                }
                com.nirncraft.NirnCraft.LOGGER.info("atlas: removed {} far tile cache(s) of older generations", old.size());
            }, "nirncraft-atlas-cache-prune");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        } catch (IOException e) {
            // nothing to prune
        }
    }

    Path file(int lod, int tx, int tz) {
        return dir.resolve("l" + lod).resolve(tx + "_" + tz + ".bin");
    }

    boolean has(int lod, int tx, int tz) {
        return Files.isRegularFile(file(lod, tx, tz));
    }

    /** The tile's bytes, or null (absent, unreadable or oversized). */
    byte[] read(int lod, int tx, int tz) {
        Path f = file(lod, tx, tz);
        try {
            return Files.isRegularFile(f) && Files.size(f) <= AtlasTileCodec.MAX_BYTES ? Files.readAllBytes(f) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Writes atomically (readers never see half a tile; concurrent writers of the same tile write the same bytes). */
    void write(int lod, int tx, int tz, byte[] data) {
        Path f = file(lod, tx, tz);
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
            Files.write(tmp, data);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // the cache is an optimisation: the tile is computed again next time
        }
    }

    void delete(int lod, int tx, int tz) {
        try {
            Files.deleteIfExists(file(lod, tx, tz));
        } catch (IOException ignored) {
            // best effort
        }
    }
}
