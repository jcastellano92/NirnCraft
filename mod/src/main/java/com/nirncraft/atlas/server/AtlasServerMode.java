package com.nirncraft.atlas.server;

/**
 * Whether the integrated server runs beside the GPU world (docs/ATLAS-GPU-LOOP.md section 4): Infinite ATLAS draws every
 * pixel ({@code -Dnirncraft.atlas.unified=true}) from the client's own GPU generation
 * ({@code -Dnirncraft.atlas.clientGen=true}), so the server no longer has to load or make what is only drawn. Read from
 * system properties (the integrated server shares the client's JVM); a dedicated server never has them.
 */
public final class AtlasServerMode {
    private AtlasServerMode() {}

    /** Unified + client generation: the GPU world is the drawn world. */
    public static boolean gpuWorld() {
        return Boolean.getBoolean("nirncraft.atlas.unified") && Boolean.getBoolean("nirncraft.atlas.clientGen");
    }

    /** The server's chunk loading is capped to the simulation radius ({@code -Dnirncraft.atlas.serverView=-1} turns it off). */
    public static boolean capView() {
        return gpuWorld() && Integer.getInteger("nirncraft.atlas.serverView", 0) >= 0;
    }

    /**
     * The server's chunks come from the GPU store ({@code -Dnirncraft.atlas.serverGen=false} keeps Java generation, for the
     * before/after measures); the AtlasChunkGenerator checks the GPU is really there before each chunk.
     */
    public static boolean gpuChunks() {
        return gpuWorld() && Boolean.getBoolean("nirncraft.atlas.serverGen");  // off by default until the generator keeps up (owner, 2026-10-25): -Dnirncraft.atlas.serverGen=true
    }
}
