package com.nirncraft.atlas.server;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.concurrent.CompletableFuture;

/**
 * The seam between the server's chunk generator and the client's GPU world (docs/ATLAS-GPU-LOOP.md section 4). The client
 * generator ({@code com.nirncraft.atlas.AtlasClientGen}, which lives with the renderer) binds a {@link Provider} to the
 * integrated server's overworld generator once its chunk store is up; the {@link AtlasChunkGenerator} asks it for each
 * chunk it is to fill. Common code only: a dedicated server never has a provider, so the generator makes its chunks in Java.
 */
public final class AtlasServerFeed {
    private AtlasServerFeed() {}

    /**
     * A chunk as the GPU store holds it once every stage has run (terrain, surface, map water, the decoration, the
     * settlements and the road paint): per section 4096 ids ({@code x + 16 * (z + 16 * y)}) into {@link #palette}, null for
     * an all-air section; sections 0..39 are y -64..575 (the sections above are air). {@code side}: the block entities
     * ("nirnKind" be) and mobs ("entity") of the structures in the chunk, as the proto chunk takes them (the tapes carry
     * blocks only).
     */
    public record Chunk(int cx, int cz, short[][] sections, BlockState[] palette, java.util.List<net.minecraft.nbt.CompoundTag> side) {}

    public interface Provider {
        /**
         * The decorated chunk for the server, or null (now, or in the future) when the GPU world cannot give it exactly
         * (the kernels flagged it, the store was reset under it, no GPU): the caller makes it in Java. Never blocks.
         * Any thread.
         */
        CompletableFuture<Chunk> request(int cx, int cz);

        /** The request is no longer wanted (the caller timed out). */
        void cancel(int cx, int cz);

        /** Whether the provider can serve now (its chunk store is up). */
        boolean ready();

        /** Whether the GPU world has this structure's pieces (the recorded settlements and dungeons); else Java places them. */
        boolean covers(net.minecraft.world.level.levelgen.structure.Structure structure);
    }

    private static volatile ChunkGenerator boundTo;
    private static volatile Provider provider;

    /** Binds the provider to the generator instance the integrated server's overworld uses (client generator, on start). */
    public static void bind(ChunkGenerator generator, Provider p) {
        boundTo = generator;
        provider = p;
    }

    /** Unbinds if still bound to p (client generator, on close). */
    public static void unbind(Provider p) {
        if (provider == p) {
            provider = null;
            boundTo = null;
        }
    }

    /** The provider for this generator, or null (a dedicated server, another world's generator, the GPU world off). */
    public static Provider forGenerator(ChunkGenerator g) {
        Provider p = provider;
        return p != null && boundTo == g ? p : null;
    }

    /** Whether a provider is bound to g (it may still be starting up). */
    public static boolean bound(ChunkGenerator g) {
        return provider != null && boundTo == g;
    }
}
