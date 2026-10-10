package com.skycraft.tamriel;

import net.minecraft.world.level.ChunkPos;

import java.util.List;
import java.util.Optional;

/**
 * Named places of Tamriel ({@code skycraft/tamriel/places.json}): capitals, towns, standing stones, word walls,
 * named dungeons. Thread-safe and usable on both sides.
 *
 * <p>CONTRACT STUB: the Phase 2 regions workstream loads the file and builds a chunk index; signatures are fixed.</p>
 */
public final class Places {
    private Places() {}

    public static List<Place> all() {
        return List.of();
    }

    public static Optional<Place> byId(String id) {
        return Optional.empty();
    }

    /** Nearest place of {@code type} (any type if null) within {@code maxDist} blocks of (x, z). */
    public static Optional<Place> nearest(PlaceType type, int x, int z, int maxDist) {
        return Optional.empty();
    }

    /** Places whose anchor (x, z) lies in this chunk. */
    public static List<Place> inChunk(ChunkPos pos) {
        return List.of();
    }
}
