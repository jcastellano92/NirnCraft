package com.skycraft.creatures.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.skycraft.client.PoseRenderState;
import com.skycraft.creatures.client.kit.RigRenderer;
import com.skycraft.creatures.client.rig.defs.Defs;
import com.skycraft.creatures.entity.anim.AnimSlot;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;

/** Alduin's authored flight, perched, attack and banishment animations on the native submission pipeline. */
public class AlduinRenderer extends RigRenderer<EnderDragon> {
    private static final Identifier TEXTURE=BestiaryClient.tex("dragon_alduin");
    private static final Identifier GLOW=BestiaryClient.tex("dragon_alduin_glow");
    public AlduinRenderer(EntityRendererProvider.Context ctx) {
        super(ctx,BestiaryClient.layer("dragon"),Defs.get("dragon"),.5f,e->TEXTURE);
        scale(3.5f).glow(e->GLOW);
        withExtra((model,dragon,walk,speed,age,yaw,pitch)->{
            float partialTicks=age-dragon.tickCount;
        float seconds = age / 20f;
        model.resetAll();
        boolean sitting = dragon.getPhaseManager().getCurrentPhase().isSitting();

        if (dragon.dragonDeathTime > 0) {
            float t = (dragon.dragonDeathTime + partialTicks) / 20f;
            model.applySlot(AnimSlot.HOVER, seconds, 1f);
            model.applySlot(AnimSlot.ROAR, Math.min(t, 1.6f), 1f);

        } else if (sitting) {
            model.applySlot(AnimSlot.IDLE, seconds, 1f);
            if (dragon.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.SITTING_FLAMING) {
                model.applySlot(AnimSlot.SPECIAL, 1.0f, 1f);
            } else if (dragon.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.SITTING_SCANNING) {
                model.applySlot(AnimSlot.ROAR, (seconds % 2.0f), 0.6f);
            }
        } else {
            float flightSpeed = (float) dragon.getDeltaMovement().length();
            float glide = Mth.clamp(flightSpeed * 2.5f, 0f, 1f);
            model.applySlot(AnimSlot.FLY, seconds, glide);
            model.applySlot(AnimSlot.HOVER, seconds, 1f - glide);
        }
        if (dragon.hurtTime > 0) model.applySlot(AnimSlot.HURT, (10 - dragon.hurtTime + partialTicks) / 20f, 1f);

        });
    }
    @Override public void extractRenderState(EnderDragon dragon,PoseRenderState state,float partialTicks) {
        super.extractRenderState(dragon,state,partialTicks);
        state.bodyRot=dragon.flightHistory.get(7,partialTicks).yRot();
        state.bankPitch=(float)(dragon.flightHistory.get(5,partialTicks).y()-dragon.flightHistory.get(10,partialTicks).y())*10;
        float alpha=dragon.dragonDeathTime>0?Mth.clamp(1f-(dragon.dragonDeathTime+partialTicks)/200f,.05f,1f):1f;
        state.modelColor=((int)(alpha*255)<<24)|0xffffff;
        state.translucentBody=alpha<1;
    }
    @Override protected void setupRotations(PoseRenderState state,PoseStack pose,float bodyRot,float entityScale) {
        pose.rotateDegrees(Axis.YP,-state.bodyRot);
        pose.rotateDegrees(Axis.XP,state.bankPitch);
        pose.rotateDegrees(Axis.YP,180);
    }
    @Override protected RenderType getRenderType(PoseRenderState state,boolean visible,boolean transparent,boolean glowing) {
        if (visible && state.translucentBody) return RenderTypes.entityTranslucent(TEXTURE);
        return super.getRenderType(state,visible,transparent,glowing);
    }
}
