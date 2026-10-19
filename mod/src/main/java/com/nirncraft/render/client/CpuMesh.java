package com.nirncraft.render.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;

/** A transferable mesh owns its native allocation until GPU upload or cancellation. */
public final class CpuMesh implements AutoCloseable {
    private final ByteBufferBuilder storage;
    private final MeshData mesh;
    private boolean closed;

    private CpuMesh(ByteBufferBuilder storage, MeshData mesh) {
        this.storage = storage;
        this.mesh = mesh;
    }
    public MeshData data() {
        if (closed) throw new IllegalStateException("CPU mesh already released");
        return mesh;
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        try { mesh.close(); } finally { storage.close(); }
    }
    public static Builder builder(PrimitiveTopology topology, VertexFormat format) {
        return new Builder(topology, format);
    }
    public static final class Builder implements AutoCloseable {
        private final ByteBufferBuilder storage = new ByteBufferBuilder(65536);
        private final BufferBuilder vertices;
        private boolean transferred;
        private Builder(PrimitiveTopology topology, VertexFormat format) {
            vertices = new BufferBuilder(storage, topology, format);
        }
        public BufferBuilder vertices() { return vertices; }
        public CpuMesh build() {
            if (transferred) throw new IllegalStateException("Mesh builder already consumed");
            MeshData mesh = vertices.buildOrThrow();
            transferred = true;
            return new CpuMesh(storage, mesh);
        }
        @Override public void close() {
            if (!transferred) { transferred = true; storage.close(); }
        }
    }
}
