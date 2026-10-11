package com.skycraft.sailing.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.skycraft.Skycraft;
import com.skycraft.sailing.ShipEntity;
import com.skycraft.sailing.ShipLivery;
import com.skycraft.sailing.ShipType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Draws a ship from its block-built model ({@link ShipModels}): the hull rocks on the waves (more in a strong wind),
 * heels under a full sail, shakes when hit and rolls over as it sinks; oars sweep while rowing, the rudder follows
 * the helm, the yard is braced round to the wind, the sail fills and billows (or flogs when the wind is wrong) as it
 * is set, and the masthead pennant streams downwind.
 */
public class ShipRenderer extends EntityRenderer<ShipEntity> {
    public static final ResourceLocation ATLAS = new ResourceLocation(Skycraft.MODID, "textures/entity/sailing/ship_atlas.png");
    /** The liveries' sails (pirate, navy, Maormer, Sload, ghost, merchant): 64 px pictures in a 4 x 4 grid. */
    public static final ResourceLocation LIVERY_SAILS = new ResourceLocation(Skycraft.MODID, "textures/entity/sailing/livery_sails.png");
    private final ShipMesh dynamic = new ShipMesh();
    private final ShipMesh cloth = new ShipMesh();

    public ShipRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0f;
    }

    @Override
    public ResourceLocation getTextureLocation(ShipEntity ship) {
        return ATLAS;
    }

    @Override
    public void render(ShipEntity ship, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        ShipType type = ship.shipType;
        ShipModels.Model model = ShipModels.get(type);
        float t = ship.tickCount + partial + ship.getId() * 13.7f;
        float wind = ship.getWind();
        float shipYaw = Mth.rotLerp(partial, ship.yRotO, ship.getYRot());
        float rel = Mth.wrapDegrees(ship.getWindDir() - shipYaw);
        float deploy = Mth.lerp(partial, ship.sailDeployO, ship.sailDeploy);
        float eff = type.sailEfficiency(rel);

        pose.pushPose();
        float size = Math.max(1f, type.length / 5f);
        float amp = (0.5f + wind * 1.6f) / (float) Math.sqrt(size);
        float heave = Mth.sin(t * 0.08f) * 0.05f * (0.5f + wind);
        float pitch = Mth.sin(t * 0.061f) * amp * 0.6f;
        float roll = Mth.sin(t * 0.047f + 1.3f) * amp;
        roll += -Mth.sin(rel * Mth.DEG_TO_RAD) * deploy * wind * eff * 5f; // heel to leeward
        int hurt = ship.getHurtTime();
        if (hurt > 0) roll += Mth.sin(hurt - partial) * hurt * 0.25f;
        int sinking = ship.getSinking();
        if (sinking > 0) {
            float s = Math.min(1f, (sinking + partial) / 160f);
            roll += s * 28f;
            pitch -= s * 14f;
        }
        pose.translate(0, heave, 0);
        pose.mulPose(Axis.YP.rotationDegrees(-shipYaw));
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.ZP.rotationDegrees(roll));

        ShipLivery livery = ship.getLivery();
        boolean ghost = livery.a < 1f;
        float lr = livery.r, lg = livery.g, lb = livery.b, la = livery.a;
        if (ghost) {
            // a ghost ship: pale and see-through, glowing faintly, its outline wavering
            light = LightTexture.pack(12, 12);
            la *= 0.85f + 0.15f * Mth.sin(t * 0.07f);
        }
        VertexConsumer vc = buffers.getBuffer(ghost ? RenderType.entityTranslucent(ATLAS) : RenderType.entityCutoutNoCull(ATLAS));
        int overlay = OverlayTexture.NO_OVERLAY;
        model.hull.render(pose.last(), vc, light, overlay, lr, lg, lb, la);

        // oars
        float phase = Mth.lerp(partial, ship.rowPhaseO, ship.rowPhase);
        boolean rowing = (ship.getInput() & (ShipEntity.IN_FORWARD | ShipEntity.IN_BACK)) != 0;
        for (float[] o : model.oars) {
            float side = o[3];
            float sweep = rowing ? Mth.sin(phase) * 28f : 0f;
            float dip = rowing ? 24f + 9f * Mth.cos(phase) : 14f;
            pose.pushPose();
            pose.translate(o[0], o[1], o[2]);
            pose.mulPose(Axis.YP.rotationDegrees(side > 0 ? 90f + sweep : -90f - sweep));
            pose.mulPose(Axis.XP.rotationDegrees(dip));
            model.oar.render(pose.last(), vc, light, overlay, lr, lg, lb, la);
            pose.popPose();
        }

        // rudder
        if (model.rudderPivot != null) {
            float rudder = Mth.lerp(partial, ship.rudderO, ship.rudder);
            pose.pushPose();
            pose.translate(model.rudderPivot[0], model.rudderPivot[1], model.rudderPivot[2]);
            pose.mulPose(Axis.YP.rotationDegrees(rudder * 28f));
            model.rudder.render(pose.last(), vc, light, overlay, lr, lg, lb, la);
            pose.popPose();
        }

        // yard, sail and pennant
        if (type.hasSail()) {
            dynamic.clear();
            cloth.clear();
            float brace = Mth.clamp(rel * 0.35f, -35f, 35f);
            boolean own = livery.sail < 0;
            int[] tex = own ? model.sailTex : new int[]{(livery.sail % 4) * 64, (livery.sail / 4) * 64, 64, 64};
            dynamic.push().translate(0, 0, model.mastZ).rotate(0, brace, 0);
            cloth.push().translate(0, 0, model.mastZ).rotate(0, brace, 0);
            buildSail(dynamic, own ? dynamic : cloth, model, tex, deploy, wind, eff, t);
            dynamic.pop();
            cloth.pop();
            buildPennant(dynamic, model, rel, wind, t);
            dynamic.render(pose.last(), vc, light, overlay, lr, lg, lb, la);
            if (cloth.quadCount() > 0) {
                VertexConsumer sv = buffers.getBuffer(ghost ? RenderType.entityTranslucent(LIVERY_SAILS) : RenderType.entityCutoutNoCull(LIVERY_SAILS));
                cloth.render(pose.last(), sv, light, overlay, ghost ? 0.9f : 1f, ghost ? 1f : 1f, 1f, ghost ? la : 1f);
            }
        }

        // stern lantern glows at night
        if (model.lantern.quadCount() > 0) {
            pose.pushPose();
            pose.translate(0, 3.85f, -6.35f);
            model.lantern.render(pose.last(), vc, LightTexture.FULL_BRIGHT, overlay, 1f, 1f, 1f, 1f);
            pose.popPose();
        }
        pose.popPose();
        super.render(ship, yaw, partial, pose, buffers, light);
    }

    /** Yard plus the sail: furled under the yard, or set as a billowing cloth grid to the deployed depth. */
    private static void buildSail(ShipMesh m, ShipMesh cloth, ShipModels.Model md, int[] tex, float deploy, float wind, float eff, float t) {
        float half = md.yardHalf;
        float yt = md.yardY;
        m.box(-half, yt - 0.1f, 0.14f, half, yt + 0.12f, 0.36f, ShipModels.YARD);
        m.box(-half - 0.05f, yt - 0.06f, 0.18f, -half + 0.12f, yt + 0.08f, 0.32f, ShipModels.IRON);
        m.box(half - 0.12f, yt - 0.06f, 0.18f, half + 0.05f, yt + 0.08f, 0.32f, ShipModels.IRON);
        float width = half * 1.9f;
        float furled = 1f - deploy;
        if (furled > 0.02f) {
            float r = 0.12f + 0.16f * furled;
            m.box(-width / 2f, yt - 0.1f - r * 1.6f, 0.36f, width / 2f, yt - 0.1f, 0.36f + r * 1.4f, ShipModels.CANVAS);
        }
        if (deploy < 0.04f) return;
        float top = yt - 0.12f;
        float bottom = top - (top - md.sailBottom) * deploy;
        int cols = 8, rows = 6;
        float fill = Mth.clamp(wind * (0.25f + eff), 0.08f, 1.1f);
        float flog = (1f - Math.min(1f, eff * 1.4f)) * Math.min(1f, wind + 0.2f);
        float[][][] p = new float[cols + 1][rows + 1][];
        for (int i = 0; i <= cols; i++) {
            float u = i / (float) cols;
            for (int j = 0; j <= rows; j++) {
                float v = j / (float) rows;
                float belly = fill * 0.95f * Mth.sin(u * Mth.PI) * (0.3f + 0.7f * Mth.sin(v * Mth.PI * 0.85f + 0.15f));
                float wave = flog * 0.22f * Mth.sin(t * 0.55f + i * 1.3f + j * 0.7f) * Mth.sin(u * Mth.PI) * v;
                float x = -width / 2f + width * u;
                float y = top - (top - bottom) * v;
                p[i][j] = new float[]{x, y, 0.42f + belly * deploy + wave};
            }
        }
        for (int i = 0; i < cols; i++) {
            for (int j = 0; j < rows; j++) {
                float u0 = tex[0] + tex[2] * (1f - i / (float) cols), u1 = tex[0] + tex[2] * (1f - (i + 1) / (float) cols);
                float v0 = tex[1] + tex[3] * deploy * (j / (float) rows), v1 = tex[1] + tex[3] * deploy * ((j + 1) / (float) rows);
                cloth.rawQuad(new float[][]{p[i][j + 1], p[i + 1][j + 1], p[i + 1][j], p[i][j]},
                        new float[][]{{u0, v1}, {u1, v1}, {u1, v0}, {u0, v0}});
            }
        }
        // sheets from the clews down to the gunwales
        m.line(-width / 2f, bottom, 0.42f, -width / 2f - 0.2f, md.mastBase + 0.9f, -1.6f, 0.04f, ShipModels.ROPE);
        m.line(width / 2f, bottom, 0.42f, width / 2f + 0.2f, md.mastBase + 0.9f, -1.6f, 0.04f, ShipModels.ROPE);
    }

    /** A red pennant at the masthead streaming in the direction the wind blows. */
    private static void buildPennant(ShipMesh m, ShipModels.Model md, float rel, float wind, float t) {
        m.push().translate(0, md.mastTop, md.mastZ).rotate(0, -rel, 0);
        int segs = 5;
        float len = 1.6f;
        float h = 0.32f;
        for (int k = 0; k < segs; k++) {
            float z0 = len * k / segs, z1 = len * (k + 1) / segs;
            float s0 = Mth.sin(t * 0.4f - k * 0.9f) * 0.12f * (k / (float) segs) * (0.3f + wind);
            float s1 = Mth.sin(t * 0.4f - (k + 1) * 0.9f) * 0.12f * ((k + 1) / (float) segs) * (0.3f + wind);
            float h0 = h * (1f - k / (float) segs), h1 = h * (1f - (k + 1) / (float) segs);
            float droop0 = (1f - Math.min(1f, wind)) * 0.25f * k / segs, droop1 = (1f - Math.min(1f, wind)) * 0.25f * (k + 1) / segs;
            float u0 = 21 % 16 * 16 + 16f * k / segs, u1 = 21 % 16 * 16 + 16f * (k + 1) / segs;
            float v0 = 21 / 16 * 16, v1 = v0 + 16;
            m.rawQuad(new float[][]{{s0, -h0 - droop0, z0}, {s1, -h1 - droop1, z1}, {s1, -droop1, z1}, {s0, -droop0, z0}},
                    new float[][]{{u0, v1}, {u1, v1}, {u1, v0}, {u0, v0}});
        }
        m.pop();
    }
}
