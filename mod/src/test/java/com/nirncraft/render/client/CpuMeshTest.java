package com.nirncraft.render.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CpuMeshTest {
    @Test
    void workerMeshKeepsItsVertexDataUntilTheConsumerReleasesIt() {
        CpuMesh mesh;
        try (var builder = CpuMesh.builder(PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR)) {
            var vertices = builder.vertices();
            vertices.addVertex(1, 2, 3).setColor(255, 128, 64, 32);
            vertices.addVertex(4, 5, 6).setColor(255, 128, 64, 32);
            vertices.addVertex(7, 8, 9).setColor(255, 128, 64, 32);
            vertices.addVertex(10, 11, 12).setColor(255, 128, 64, 32);
            mesh = builder.build();
            assertThrows(IllegalStateException.class, builder::build);
        }
        try (mesh) {
            assertEquals(4, mesh.data().drawState().vertexCount());
            assertEquals(6, mesh.data().drawState().indexCount());
            assertEquals(PrimitiveTopology.QUADS, mesh.data().drawState().primitiveTopology());
            var bytes = mesh.data().vertexBuffer().order(ByteOrder.nativeOrder());
            assertEquals(4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize(), bytes.remaining());
            assertEquals(1f, bytes.getFloat(0));
            assertEquals(2f, bytes.getFloat(4));
            assertEquals(3f, bytes.getFloat(8));
            assertEquals(255, Byte.toUnsignedInt(bytes.get(12)));
            assertEquals(128, Byte.toUnsignedInt(bytes.get(13)));
            assertEquals(64, Byte.toUnsignedInt(bytes.get(14)));
            assertEquals(32, Byte.toUnsignedInt(bytes.get(15)));
        }
        assertThrows(IllegalStateException.class, mesh::data);
        mesh.close();
    }

    @Test
    void aCancelledBuilderCannotPublishAReleasedMesh() {
        var builder = CpuMesh.builder(PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);
        builder.close();
        builder.close();
        assertThrows(IllegalStateException.class, builder::build);
    }
}
