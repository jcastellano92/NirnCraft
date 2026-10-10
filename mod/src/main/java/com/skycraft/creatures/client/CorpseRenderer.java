package com.skycraft.creatures.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.skycraft.creatures.entity.CorpseEntity;
import com.skycraft.creatures.entity.DragonEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * Renders a corpse as the dead creature lying on its side (exactly the pose of the vanilla death animation), using
 * a cached client-side dummy of the creature. Shows the name only when looked at.
 */
public class CorpseRenderer extends EntityRenderer<CorpseEntity> {
    public CorpseRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0f;
    }

    @Override
    public void render(CorpseEntity corpse, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        if (corpse.isVisibleYet()) {
            Entity dummy = corpse.getRenderDummy();
            if (dummy != null) {
                // No try/catch around the draw: an exception half-way through would leave the vertex buffers broken.
                // Bodies whose dummy can't even be created are skipped (CorpseEntity#getRenderDummy).
                EntityRenderer<? super Entity> renderer = this.entityRenderDispatcher.getRenderer(dummy);
                poseStack.pushPose();
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - entityYaw));
                float roll = corpse.getRoll();
                float headLift = 0.0f;
                if (corpse.isDragged()) {
                    // the body sways and its head end is lifted while being dragged
                    float t = corpse.tickCount + partialTick;
                    roll += Mth.sin(t * 0.35f) * 6.0f;
                    headLift = -10.0f + Mth.sin(t * 0.5f) * 2.0f;
                }
                if (dummy instanceof DragonEntity) {
                    poseStack.mulPose(Axis.ZP.rotationDegrees(10.0f + roll * 0.3f));
                } else {
                    float half = corpse.isCentered() ? corpse.getBodyHeight() * 0.5f : 0.0f;
                    float lift = Math.min(dummy.getBbWidth() * 0.5f, 0.75f);
                    poseStack.translate(0.0f, lift, 0.0f);
                    // head end is local -X: a negative Z rotation lifts it; X rotation rolls around the long axis
                    if (headLift != 0.0f) poseStack.mulPose(Axis.ZP.rotationDegrees(headLift));
                    poseStack.mulPose(Axis.XP.rotationDegrees(roll));
                    poseStack.translate(half, 0.0f, 0.0f);
                    poseStack.mulPose(Axis.ZP.rotationDegrees(90.0f));
                }
                renderer.render(dummy, 0.0f, 0.0f, poseStack, buffers, packedLight);
                poseStack.popPose();
            }
        }
        super.render(corpse, entityYaw, partialTick, poseStack, buffers, packedLight);
    }

    @Override
    protected boolean shouldShowName(CorpseEntity corpse) {
        return corpse.hasCustomName() && corpse.isVisibleYet() && corpse == this.entityRenderDispatcher.crosshairPickEntity;
    }

    @Override
    public ResourceLocation getTextureLocation(CorpseEntity corpse) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
