package com.nirncraft.atlas.server;

/**
 * Integrated-server trim for GPU-owned worlds (docs/ATLAS-GPU-LOOP.md section 4): when Infinite ATLAS draws the world on the
 * client from its own GPU generation ({@code -Dnirncraft.atlas.unified=true -Dnirncraft.atlas.clientGen=true}), the integrated
 * server exists only for gameplay within interaction reach (collisions, block edits, entities, containers, quests). Its view
 * and simulation distances are capped to {@link #SERVER_VIEW} chunks so the CPU chunk pipeline does not generate or light a
 * 12-chunk ring the renderer never reads.
 *
 * <p>{@code -Dnirncraft.atlas.serverCap=false} leaves the distances as the player's options set them; {@code .serverView=N}
 * sets the cap (chunks, 2..16, default 8).</p>
 */
public final class AtlasServerMode {
    private AtlasServerMode() {}

    public static final int SERVER_VIEW = Math.max(2, Math.min(16, Integer.getInteger("nirncraft.atlas.serverView", 8)));

    /** Whether the client draws the world from its own GPU generation in this process. */
    public static boolean gpuWorld() {
        return Boolean.getBoolean("nirncraft.atlas.unified") && Boolean.getBoolean("nirncraft.atlas.clientGen");
    }

    /** Whether the integrated server's chunk and simulation distances are capped to {@link #SERVER_VIEW}. */
    public static boolean capView() {
        return gpuWorld() && !"false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.serverCap"));
    }

    /**
     * Whether the integrated server takes its Overworld chunks from the client generator's GPU chunk store and skips its own
     * surface and decoration passes on them ({@code -Dnirncraft.atlas.serverGen=true}).
     */
    public static boolean gpuChunks() {
        return gpuWorld() && Boolean.getBoolean("nirncraft.atlas.serverGen");
    }

    public static int clampView(int requested) {
        return capView() ? Math.min(requested, SERVER_VIEW) : requested;
    }
}

