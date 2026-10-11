package com.skycraft.creatures.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.skycraft.creatures.client.kit.RigModel;
import com.skycraft.creatures.client.rig.defs.Defs;
import com.skycraft.creatures.entity.anim.AnimSlot;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;

/**
 * Alduin, the World-Eater: the End's dragon (renamed Alduin by the main quest) drawn as a huge black Skyrim dragon on
 * the animated dragon model: gliding and beating his wings in flight, perched with jaws working on the portal, and
 * fading into light as he is banished.
 */
public class AlduinRenderer extends EntityRenderer<EnderDragon> {
    private static final ResourceLocation TEXTURE = BestiaryClient.tex("dragon_alduin");
    private static final ResourceLocation GLOW = BestiaryClient.tex("dragon_alduin_glow");
    private static final float SCALE = 3.5f;
    private final RigModel<EnderDragon> model;

    public AlduinRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.5f;
        this.model = new RigModel<>(ctx.bakeLayer(BestiaryClient.layer("dragon")), Defs.get("dragon"));
    }

    @Override
    public void render(EnderDragon dragon, float yaw, float partialTicks, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        float heading = (float) dragon.getLatencyPos(7, partialTicks)[0];
        float climb = (float) (dragon.getLatencyPos(5, partialTicks)[1] - dragon.getLatencyPos(10, partialTicks)[1]);
        pose.mulPose(Axis.YP.rotationDegrees(-heading));
        pose.mulPose(Axis.XP.rotationDegrees(climb * 10.0f));
        pose.mulPose(Axis.YP.rotationDegrees(180.0f));
        pose.scale(-SCALE, -SCALE, SCALE);
        pose.translate(0.0f, -1.501f, 0.0f);

        float seconds = (dragon.tickCount + partialTicks) / 20f;
        model.resetAll();
        boolean sitting = dragon.getPhaseManager().getCurrentPhase().isSitting();
        float alpha = 1f;
        if (dragon.dragonDeathTime > 0) {
            float t = (dragon.dragonDeathTime + partialTicks) / 20f;
            model.applySlot(AnimSlot.HOVER, seconds, 1f);
            model.applySlot(AnimSlot.ROAR, Math.min(t, 1.6f), 1f);
            alpha = Mth.clamp(1f - (dragon.dragonDeathTime + partialTicks) / 200f, 0.05f, 1f);
        } else if (sitting) {
            model.applySlot(AnimSlot.IDLE, seconds, 1f);
            if (dragon.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.SITTING_FLAMING) {
                model.applySlot(AnimSlot.SPECIAL, 1.0f, 1f);
            } else if (dragon.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.SITTING_SCANNING) {
                model.applySlot(AnimSlot.ROAR, (seconds % 2.0f), 0.6f);
            }
        } else {
            float speed = (float) dragon.getDeltaMovement().length();
            float glide = Mth.clamp(speed * 2.5f, 0f, 1f);
            model.applySlot(AnimSlot.FLY, seconds, glide);
            model.applySlot(AnimSlot.HOVER, seconds, 1f - glide);
        }
        if (dragon.hurtTime > 0) model.applySlot(AnimSlot.HURT, (10 - dragon.hurtTime + partialTicks) / 20f, 1f);

        int overlay = OverlayTexture.pack(0.0f, dragon.hurtTime > 0);
        RenderType type = alpha < 1f ? RenderType.entityTranslucent(TEXTURE) : RenderType.entityCutoutNoCull(TEXTURE);
        model.renderToBuffer(pose, buffers.getBuffer(type), light, overlay, 1f, 1f, 1f, alpha);
        model.renderToBuffer(pose, buffers.getBuffer(RenderType.eyes(GLOW)), 15728640, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f);
        pose.popPose();
        super.render(dragon, yaw, partialTicks, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(EnderDragon dragon) {
        return TEXTURE;
    }
}
