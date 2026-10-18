package com.skycraft.aether;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Encoded far tiles on disk (AetherTileCodec bytes, one file per tile). A tile is the generator's surface, never the
 * edited world, so it is computed once per world and kept: the tile server, the single-player exact source and a LAN
 * host's own client all share one cache under the world, and multiplayer clients keep theirs per world id.
 */
final class AetherTileCache {
    private final Path dir;

    AetherTileCache(Path dir) {
        this.dir = dir;
    }

    /** The world's cache (dedicated, integrated and LAN-hosted servers alike). */
    static AetherTileCache forServer(MinecraftServer server) {
        return new AetherTileCache(server.getWorldPath(LevelResource.DATA).resolve("skycraft_aether")
                .resolve("tiles-v" + AetherTileCodec.VERSION));
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
            return Files.isRegularFile(f) && Files.size(f) <= AetherTileCodec.MAX_BYTES ? Files.readAllBytes(f) : null;
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
