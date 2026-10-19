package com.nirncraft.arsenal.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nirncraft.NirnCraft;
import com.nirncraft.arsenal.Sheathe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Third-person render layer that draws sheathed / holstered weapons on the hip (1H swords, daggers, war axes, maces)
 * and back (2H greatswords, battleaxes, warhammers, polearms, staves, bows, crossbows, and shields) for players and
 * humanoid NPCs.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public class SheathedWeaponLayer<S extends HumanoidRenderState,M extends HumanoidModel<S>> extends RenderLayer<S,M> {
    private record Appearance(ItemStackRenderState hip,ItemStackRenderState back,ItemStackRenderState shield,boolean ranged) {}
    private static final net.minecraft.util.context.ContextKey<Appearance> APPEARANCE=new net.minecraft.util.context.ContextKey<>(net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft","sheathed_items"));
    public SheathedWeaponLayer(RenderLayerParent<S,M> parent) { super(parent); }
    public static void extract(LivingEntity entity,HumanoidRenderState state) {
        var resolver=Minecraft.getInstance().getItemModelResolver();
        var hip=new ItemStackRenderState(); var back=new ItemStackRenderState(); var shield=new ItemStackRenderState();
        ItemStack backItem=Sheathe.findBackWeapon(entity);
        resolver.updateForLiving(hip,Sheathe.findHipWeapon(entity),ItemDisplayContext.FIXED,entity);
        resolver.updateForLiving(back,backItem,ItemDisplayContext.FIXED,entity);
        resolver.updateForLiving(shield,Sheathe.findBackShield(entity),ItemDisplayContext.FIXED,entity);
        state.setRenderData(APPEARANCE,new Appearance(hip,back,shield,backItem.getItem() instanceof BowItem||backItem.getItem() instanceof CrossbowItem));
    }
    @Override public void submit(PoseStack poseStack,SubmitNodeCollector collector,int packedLight,S state,float yaw,float pitch) {
        Appearance appearance=state.getRenderData(APPEARANCE);
        if(state.isInvisible||appearance==null) return;
        var hipWeapon=appearance.hip();var backWeapon=appearance.back();var backShield=appearance.shield();
        if (!hipWeapon.isEmpty()) {
            poseStack.pushPose();
            this.getParentModel().body.translateAndRotate(poseStack);
            poseStack.translate(0.24F, 0.62F, 0.04F);
            poseStack.rotateDegrees(Axis.YP,90.0F);
            poseStack.rotateDegrees(Axis.ZP,-38.0F);
            poseStack.scale(0.75F, 0.75F, 0.75F);
            hipWeapon.submit(poseStack,collector,packedLight,OverlayTexture.NO_OVERLAY,state.outlineColor);
            poseStack.popPose();
        }

        if (!backWeapon.isEmpty()) {
            poseStack.pushPose();
            this.getParentModel().body.translateAndRotate(poseStack);
            boolean ranged = appearance.ranged();
            poseStack.translate(0.0F, 0.28F, 0.17F);
            poseStack.rotateDegrees(Axis.YP,180.0F);
            poseStack.rotateDegrees(Axis.ZP,ranged ? -40.0F : 42.0F);
            float scale = ranged ? 0.85F : 0.92F;
            poseStack.scale(scale, scale, scale);
            backWeapon.submit(poseStack,collector,packedLight,OverlayTexture.NO_OVERLAY,state.outlineColor);
            poseStack.popPose();
        }

        if (!backShield.isEmpty()) {
            poseStack.pushPose();
            this.getParentModel().body.translateAndRotate(poseStack);
            poseStack.translate(0.0F, 0.32F, 0.22F);
            poseStack.rotateDegrees(Axis.YP,180.0F);
            poseStack.scale(0.80F, 0.80F, 0.80F);
            backShield.submit(poseStack,collector,packedLight,OverlayTexture.NO_OVERLAY,state.outlineColor);
            poseStack.popPose();
        }
    }


    @SubscribeEvent
    public static void modifiers(net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent event) {
        event.registerEntityModifier(new com.google.common.reflect.TypeToken<LivingEntityRenderer<LivingEntity,net.minecraft.client.renderer.entity.state.LivingEntityRenderState,?>>() {},(entity,state)->{
            if(state instanceof HumanoidRenderState humanoid) extract(entity,humanoid);
        });
    }
    @SubscribeEvent @SuppressWarnings({"unchecked","rawtypes"})
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for(var skin:event.getSkins()) {
            var renderer=event.getPlayerRenderer(skin);
            if(renderer!=null) renderer.addLayer(new SheathedWeaponLayer(renderer));
        }
        for(EntityType<?> type:BuiltInRegistries.ENTITY_TYPE) {
            var renderer=event.getRenderer((EntityType)type);
            if(renderer instanceof LivingEntityRenderer living && living.getModel() instanceof HumanoidModel)
                living.addLayer(new SheathedWeaponLayer(living));
        }
    }
}
