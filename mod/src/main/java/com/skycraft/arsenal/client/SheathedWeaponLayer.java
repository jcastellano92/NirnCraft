package com.skycraft.arsenal.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.skycraft.Skycraft;
import com.skycraft.arsenal.Sheathe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Third-person render layer that draws sheathed / holstered weapons on the hip (1H swords, daggers, war axes, maces)
 * and back (2H greatswords, battleaxes, warhammers, polearms, staves, bows, crossbows, and shields) for players and
 * humanoid NPCs.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class SheathedWeaponLayer<T extends LivingEntity, M extends HumanoidModel<T>> extends RenderLayer<T, M> {
    public SheathedWeaponLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (entity.isInvisible()) return;

        ItemStack hipWeapon = Sheathe.findHipWeapon(entity);
        ItemStack backWeapon = Sheathe.findBackWeapon(entity);
        ItemStack backShield = Sheathe.findBackShield(entity);
        if (hipWeapon.isEmpty() && backWeapon.isEmpty() && backShield.isEmpty()) return;

        var itemRenderer = Minecraft.getInstance().getItemRenderer();

        if (!hipWeapon.isEmpty()) {
            poseStack.pushPose();
            this.getParentModel().body.translateAndRotate(poseStack);
            poseStack.translate(0.24F, 0.62F, 0.04F);
            poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
            poseStack.mulPose(Axis.ZP.rotationDegrees(-38.0F));
            poseStack.scale(0.75F, 0.75F, 0.75F);
            itemRenderer.renderStatic(entity, hipWeapon, ItemDisplayContext.FIXED, false,
                    poseStack, buffer, entity.level(), packedLight, OverlayTexture.NO_OVERLAY, entity.getId());
            poseStack.popPose();
        }

        if (!backWeapon.isEmpty()) {
            poseStack.pushPose();
            this.getParentModel().body.translateAndRotate(poseStack);
            boolean ranged = backWeapon.getItem() instanceof BowItem || backWeapon.getItem() instanceof CrossbowItem;
            poseStack.translate(0.0F, 0.28F, 0.17F);
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
            poseStack.mulPose(Axis.ZP.rotationDegrees(ranged ? -40.0F : 42.0F));
            float scale = ranged ? 0.85F : 0.92F;
            poseStack.scale(scale, scale, scale);
            itemRenderer.renderStatic(entity, backWeapon, ItemDisplayContext.FIXED, false,
                    poseStack, buffer, entity.level(), packedLight, OverlayTexture.NO_OVERLAY, entity.getId() + 1);
            poseStack.popPose();
        }

        if (!backShield.isEmpty()) {
            poseStack.pushPose();
            this.getParentModel().body.translateAndRotate(poseStack);
            poseStack.translate(0.0F, 0.32F, 0.22F);
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
            poseStack.scale(0.80F, 0.80F, 0.80F);
            itemRenderer.renderStatic(entity, backShield, ItemDisplayContext.FIXED, false,
                    poseStack, buffer, entity.level(), packedLight, OverlayTexture.NO_OVERLAY, entity.getId() + 2);
            poseStack.popPose();
        }
    }

    @SubscribeEvent
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for (String skin : event.getSkins()) {
            LivingEntityRenderer renderer = event.getSkin(skin);
            if (renderer != null && renderer.getModel() instanceof HumanoidModel) {
                renderer.addLayer(new SheathedWeaponLayer(renderer));
            }
        }
        for (EntityType<?> type : ForgeRegistries.ENTITY_TYPES.getValues()) {
            try {
                EntityRenderer<?> r = event.getRenderer((EntityType) type);
                if (r instanceof LivingEntityRenderer livingRenderer && livingRenderer.getModel() instanceof HumanoidModel) {
                    livingRenderer.addLayer(new SheathedWeaponLayer(livingRenderer));
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
