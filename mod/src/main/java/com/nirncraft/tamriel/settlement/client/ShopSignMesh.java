package com.nirncraft.tamriel.settlement.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight 3D quad mesh in pixel coordinates (16 units = 1 block) for procedural
 * 3D regional shop signs and sculpted trade emblems.
 *
 * <p>Uses a 16x16 material tile grid in {@code textures/entity/settlement/shop_signs.png}.
 * Every cuboid face maps across the full material tile so each 3D element automatically
 * receives a crisp dark border and inner highlight bevel.
 */
public final class ShopSignMesh {
    private static final float INV_16 = 1.0f / 16.0f;
    private static final float TILE_STEP = 1.0f / 16.0f;
    private static final float UV_PAD = 0.25f / 256.0f;

    private final List<Quad> quads = new ArrayList<>();

    public record Quad(
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float nx, float ny, float nz,
            float u0, float v0, float u1, float v1
    ) {}

    public List<Quad> quads() {
        return quads;
    }

    /** Adds an axis-aligned 3D cuboid in pixel coordinates ([-8..8] = 1 block). */
    public ShopSignMesh box(float x0, float y0, float z0, float x1, float y1, float z1, int mat) {
        return angledBox(
                (x0 + x1) * 0.5f, (y0 + y1) * 0.5f, (z0 + z1) * 0.5f,
                Math.abs(x1 - x0), Math.abs(y1 - y0), Math.abs(z1 - z0),
                0f, 0f, 0f, mat);
    }

    /** Adds a 3D cuboid centered at (cx, cy, cz) with size (sx, sy, sz) and Euler rotations in degrees. */
    public ShopSignMesh angledBox(
            float cx, float cy, float cz,
            float sx, float sy, float sz,
            float rotXDeg, float rotYDeg, float rotZDeg,
            int mat
    ) {
        float hx = sx * 0.5f;
        float hy = sy * 0.5f;
        float hz = sz * 0.5f;

        Quaternionf q = new Quaternionf()
                .rotateX((float) Math.toRadians(rotXDeg))
                .rotateY((float) Math.toRadians(rotYDeg))
                .rotateZ((float) Math.toRadians(rotZDeg));

        int col = mat & 15;
        int row = (mat >> 4) & 15;
        float u0 = col * TILE_STEP + UV_PAD;
        float v0 = row * TILE_STEP + UV_PAD;
        float u1 = (col + 1) * TILE_STEP - UV_PAD;
        float v1 = (row + 1) * TILE_STEP - UV_PAD;

        // +X face
        addFace(cx, cy, cz, q,
                hx, -hy, -hz,
                hx, -hy,  hz,
                hx,  hy,  hz,
                hx,  hy, -hz,
                1, 0, 0, u0, v1, u1, v0);
        // -X face
        addFace(cx, cy, cz, q,
                -hx, -hy,  hz,
                -hx, -hy, -hz,
                -hx,  hy, -hz,
                -hx,  hy,  hz,
                -1, 0, 0, u0, v1, u1, v0);
        // +Y face
        addFace(cx, cy, cz, q,
                -hx,  hy, -hz,
                 hx,  hy, -hz,
                 hx,  hy,  hz,
                -hx,  hy,  hz,
                0, 1, 0, u0, v0, u1, v1);
        // -Y face
        addFace(cx, cy, cz, q,
                -hx, -hy,  hz,
                 hx, -hy,  hz,
                 hx, -hy, -hz,
                -hx, -hy, -hz,
                0, -1, 0, u0, v0, u1, v1);
        // +Z face
        addFace(cx, cy, cz, q,
                 hx, -hy,  hz,
                -hx, -hy,  hz,
                -hx,  hy,  hz,
                 hx,  hy,  hz,
                0, 0, 1, u0, v1, u1, v0);
        // -Z face
        addFace(cx, cy, cz, q,
                -hx, -hy, -hz,
                 hx, -hy, -hz,
                 hx,  hy, -hz,
                -hx,  hy, -hz,
                0, 0, -1, u0, v1, u1, v0);
        return this;
    }

    /** Connects two points in the Y-Z plane (at X=0) with a 3D beam of square cross-section. */
    public ShopSignMesh strutYZ(float y0, float z0, float y1, float z1, float thicknessX, float thicknessYZ, int mat) {
        float dy = y1 - y0;
        float dz = z1 - z0;
        float len = (float) Math.sqrt(dy * dy + dz * dz);
        if (len < 0.01f) return this;
        float angleX = (float) Math.toDegrees(Math.atan2(dy, dz));
        return angledBox(0f, (y0 + y1) * 0.5f, (z0 + z1) * 0.5f, thicknessX, thicknessYZ, len, -angleX, 0f, 0f, mat);
    }

    /** Adds a 3D relief box on BOTH sides (+X and -X) of the hanging plaque. */
    public ShopSignMesh reliefBox(float y0, float z0, float y1, float z1, float depth, float plaqueHalfThick, int mat) {
        box(plaqueHalfThick, y0, z0, plaqueHalfThick + depth, y1, z1, mat);
        box(-plaqueHalfThick - depth, y0, z0, -plaqueHalfThick, y1, z1, mat);
        return this;
    }

    /** Adds a rotated 3D relief element in the Y-Z plane on BOTH sides (+X and -X) of the hanging plaque. */
    public ShopSignMesh reliefAngled(
            float cy, float cz, float sy, float sz,
            float depth, float plaqueHalfThick, float rotXDeg, int mat
    ) {
        float cxPos = plaqueHalfThick + depth * 0.5f;
        angledBox(cxPos, cy, cz, depth, sy, sz, rotXDeg, 0f, 0f, mat);
        angledBox(-cxPos, cy, cz, depth, sy, sz, rotXDeg, 0f, 0f, mat);
        return this;
    }

    /** Connects (y0, z0) to (y1, z1) with a 3D relief bar on BOTH sides (+X and -X) of the plaque. */
    public ShopSignMesh reliefLine(
            float y0, float z0, float y1, float z1,
            float width, float depth, float plaqueHalfThick, int mat
    ) {
        float dy = y1 - y0;
        float dz = z1 - z0;
        float len = (float) Math.sqrt(dy * dy + dz * dz);
        if (len < 0.01f) return this;
        float angleX = (float) Math.toDegrees(Math.atan2(dy, dz));
        return reliefAngled((y0 + y1) * 0.5f, (z0 + z1) * 0.5f, width, len, depth, plaqueHalfThick, -angleX, mat);
    }

    private void addFace(
            float cx, float cy, float cz, Quaternionf q,
            float vx0, float vy0, float vz0,
            float vx1, float vy1, float vz1,
            float vx2, float vy2, float vz2,
            float vx3, float vy3, float vz3,
            float nx, float ny, float nz,
            float u0, float v0, float u1, float v1
    ) {
        Vector3f p0 = q.transform(new Vector3f(vx0, vy0, vz0)).add(cx, cy, cz).mul(INV_16);
        Vector3f p1 = q.transform(new Vector3f(vx1, vy1, vz1)).add(cx, cy, cz).mul(INV_16);
        Vector3f p2 = q.transform(new Vector3f(vx2, vy2, vz2)).add(cx, cy, cz).mul(INV_16);
        Vector3f p3 = q.transform(new Vector3f(vx3, vy3, vz3)).add(cx, cy, cz).mul(INV_16);
        Vector3f n = q.transform(new Vector3f(nx, ny, nz)).normalize();
        quads.add(new Quad(
                p0.x, p0.y, p0.z,
                p1.x, p1.y, p1.z,
                p2.x, p2.y, p2.z,
                p3.x, p3.y, p3.z,
                n.x, n.y, n.z,
                u0, v0, u1, v1
        ));
    }

    public void render(PoseStack poseStack, VertexConsumer vc, int packedLight) {
        render(poseStack.last(),vc,packedLight);
    }
    public void render(PoseStack.Pose last,VertexConsumer vc,int packedLight) {
        Matrix4f pose = last.pose();
        Matrix3f normal = last.normal();
        for (Quad q : quads) {
            vc.addVertex(pose, q.x0, q.y0, q.z0).setColor(255, 255, 255, 255).setUv(q.u0, q.v0)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(last, q.nx, q.ny, q.nz);
            vc.addVertex(pose, q.x1, q.y1, q.z1).setColor(255, 255, 255, 255).setUv(q.u1, q.v0)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(last, q.nx, q.ny, q.nz);
            vc.addVertex(pose, q.x2, q.y2, q.z2).setColor(255, 255, 255, 255).setUv(q.u1, q.v1)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(last, q.nx, q.ny, q.nz);
            vc.addVertex(pose, q.x3, q.y3, q.z3).setColor(255, 255, 255, 255).setUv(q.u0, q.v1)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(last, q.nx, q.ny, q.nz);
        }
    }
}

