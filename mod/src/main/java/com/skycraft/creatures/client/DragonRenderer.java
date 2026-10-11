package com.skycraft.creatures.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.skycraft.creatures.client.kit.RigModel;
import com.skycraft.creatures.client.kit.RigRenderer;
import com.skycraft.creatures.client.rig.defs.Defs;
import com.skycraft.creatures.entity.DragonEntity;
import com.skycraft.creatures.entity.anim.AnimSlot;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * Dragons on the animated rig model, at twice the model size: variant skins (Alduin, Paarthurnax and Odahviing have
 * their own), glowing eyes, a translucent shimmer while Become Ethereal holds, flight banking, and the corpse pose.
 * A dead dragon's body burns away to its skeleton after its soul is absorbed: the flesh fades over the bones.
 */
public class DragonRenderer extends RigRenderer<DragonEntity> {
    private static final ResourceLocation[] TEXTURES = new ResourceLocation[DragonEntity.VARIANTS.length];
    private static final ResourceLocation[] GLOW = new ResourceLocation[DragonEntity.VARIANTS.length];
    private static final ResourceLocation SKELETON = BestiaryClient.tex("dragon_skeletal");

    static {
        for (int i = 0; i < TEXTURES.length; i++) {
            String v = DragonEntity.VARIANTS[i];
            String name = v.equals("dragon") ? "dragon" : "dragon_" + v;
            TEXTURES[i] = BestiaryClient.tex(name);
            GLOW[i] = BestiaryClient.tex(name + "_glow");
        }
    }

    public DragonRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, BestiaryClient.layer("dragon"), Defs.get("dragon"), 3.0f, DragonRenderer::texture);
        this.scale(2.0f).flip(0f);
        this.glow(d -> burn(d, 0f) >= 1f ? null : GLOW[d.getVariant()]);
        this.addLayer(new SkeletonLayer(this));
        this.withExtra((model, dragon, limbSwing, amount, age, yaw, pitch) -> {
            if (dragon.isCorpsePose()) {
                model.resetAll();
                model.applySlot(AnimSlot.DEATH, 10f, 1f);
            }
        });
    }

    private static ResourceLocation texture(DragonEntity dragon) {
        return TEXTURES[dragon.getVariant()];
    }

    @Override
    public void render(DragonEntity dragon, float yaw, float partialTicks, PoseStack pose, MultiBufferSource buffers, int light) {
        RigModel<DragonEntity> m = this.getModel();
        float burn = burn(dragon, partialTicks);
        if (dragon.isEthereal()) {
            m.alpha = 0.35f + 0.1f * Mth.sin((dragon.tickCount + partialTicks) * 0.3f);
            m.tintR = 0.75f;
            m.tintG = 0.9f;
            m.tintB = 1.0f;
        } else if (burn > 0f) {
            m.alpha = Mth.clamp(1f - burn, 0f, 1f);
            float char01 = Mth.clamp(burn * 1.6f, 0f, 1f);
            m.tintR = 1f - 0.55f * char01;
            m.tintG = 1f - 0.65f * char01;
            m.tintB = 1f - 0.7f * char01;
        } else {
            m.alpha = 1f;
            m.tintR = m.tintG = m.tintB = 1f;
        }
        super.render(dragon, yaw, partialTicks, pose, buffers, light);
        m.alpha = 1f;
        m.tintR = m.tintG = m.tintB = 1f;
    }

    /** How much of the flesh is gone: burning corpses (soul absorbed) and dragons rising from their mounds. */
    static float burn(DragonEntity dragon, float partialTicks) {
        if (dragon.isCorpsePose()) return dragon.burnProgress();
        int rising = dragon.getRising();
        if (rising <= 0) return 0f;
        // bones only for the first third of the rise, then the flesh grows back
        return Mth.clamp((rising - partialTicks) / (DragonEntity.RISE_TICKS * 0.66f), 0f, 1f);
    }

    @Nullable
    @Override
    protected RenderType getRenderType(DragonEntity dragon, boolean bodyVisible, boolean translucentVisible, boolean glowing) {
        float burn = burn(dragon, 0f);
        if (bodyVisible && (dragon.isEthereal() || burn > 0f)) {
            if (burn >= 1f) return null; // only the skeleton is left
            return RenderType.entityTranslucent(getTextureLocation(dragon));
        }
        return super.getRenderType(dragon, bodyVisible, translucentVisible, glowing);
    }

    @Override
    protected void setupRotations(DragonEntity dragon, PoseStack pose, float ageInTicks, float rotationYaw, float partialTicks) {
        super.setupRotations(dragon, pose, ageInTicks, rotationYaw, partialTicks);
        if (dragon.isFlying() && dragon.deathTime <= 0 && !dragon.isCorpsePose()) {
            float pitch = Mth.lerp(partialTicks, dragon.xRotO, dragon.getXRot());
            float turn = Mth.wrapDegrees(dragon.yBodyRot - dragon.yBodyRotO);
            float roll = Mth.clamp(turn * 4.0f, -35.0f, 35.0f);
            pose.translate(0.0, 1.75, 0.0);
            pose.mulPose(Axis.XP.rotationDegrees(-pitch));
            pose.mulPose(Axis.ZP.rotationDegrees(roll));
            pose.translate(0.0, -1.75, 0.0);
        }
    }

    /** The bones under a burning corpse's fading flesh (and all that remains afterwards). */
    static final class SkeletonLayer extends RenderLayer<DragonEntity, RigModel<DragonEntity>> {
        SkeletonLayer(RenderLayerParent<DragonEntity, RigModel<DragonEntity>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, DragonEntity dragon, float limbSwing, float amount,
                           float partialTick, float age, float yaw, float pitch) {
            if (dragon.getVariant() == DragonEntity.SKELETAL) return; // the main pass already draws the bones
            if (burn(dragon, partialTick) <= 0f) return;
            RigModel<DragonEntity> m = this.getParentModel();
            float a = m.alpha;
            float r = m.tintR;
            float g = m.tintG;
            float b = m.tintB;
            m.alpha = 1f;
            m.tintR = m.tintG = m.tintB = 1f;
            m.renderToBuffer(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(SKELETON)), light, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f);
            m.alpha = a;
            m.tintR = r;
            m.tintG = g;
            m.tintB = b;
        }
    }
}
