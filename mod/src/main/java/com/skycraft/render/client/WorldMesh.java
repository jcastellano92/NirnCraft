package com.skycraft.render.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;

/** Persistent native vertex/index buffers; drawing never re-uploads unchanged authored geometry. */
public final class WorldMesh implements AutoCloseable {
    private final GpuBuffer vertices;
    private final GpuBuffer indices;
    private final MeshData.DrawState state;
    private final long vertexBytes;
    private boolean closed;

    public WorldMesh(CpuMesh source) {
        try (source) {
            MeshData mesh = source.data();
            state = mesh.drawState();
            vertexBytes = mesh.vertexBuffer().remaining();
            GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "Skycraft world mesh vertices", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            try {
                indices = mesh.indexBuffer() == null ? null : RenderSystem.getDevice().createBuffer(
                        () -> "Skycraft world mesh indices", GpuBuffer.USAGE_INDEX, mesh.indexBuffer());
            } catch (RuntimeException | Error error) {
                vertexBuffer.close();
                throw error;
            }
            vertices = vertexBuffer;
            // May allocate/resize the shared index buffer. Uploads happen before an active render pass.
            try {
                prepare();
            } catch (RuntimeException | Error error) {
                close();
                throw error;
            }
        }
    }
    public VertexFormat format() { return state.format(); }
    public PrimitiveTopology topology() { return state.primitiveTopology(); }
    public long vertexBytes() { return vertexBytes; }
    public boolean isClosed() { return closed; }
    public void prepare() {
        if (closed) throw new IllegalStateException("GPU mesh already released");
        if (indices == null) RenderSystem.getSequentialBuffer(state.primitiveTopology()).getBuffer(state.indexCount());
    }
    void draw(RenderPass pass) {
        if (closed) throw new IllegalStateException("GPU mesh already released");
        GpuBuffer indexBuffer;
        IndexType indexType;
        if (indices != null) { indexBuffer = indices; indexType = state.indexType(); }
        else {
            var sequential = RenderSystem.getSequentialBuffer(state.primitiveTopology());
            indexBuffer = sequential.getBuffer(state.indexCount());
            indexType = sequential.type();
        }
        pass.setVertexBuffer(0, vertices.slice());
        pass.setIndexBuffer(indexBuffer, indexType);
        pass.drawIndexed(state.indexCount(), 1, 0, 0, 0);
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        try { vertices.close(); } finally { if (indices != null) indices.close(); }
    }
}
