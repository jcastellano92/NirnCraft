package com.skycraft.creatures.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.skycraft.creatures.entity.CorpseEntity;
import com.skycraft.creatures.entity.DragonEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

/** Captures the dummy's native render state before submitting a dragged or resting corpse. */
public class CorpseRenderer extends EntityRenderer<CorpseEntity,CorpseRenderer.State> {
    public static class State extends EntityRenderState {
        EntityRenderer<Entity,EntityRenderState> renderer;
        EntityRenderState dummy;
        float yaw,roll,headLift,half,lift;
        boolean dragon;
    }
    public CorpseRenderer(EntityRendererProvider.Context context) { super(context); shadowRadius=0; }
    @Override protected net.minecraft.world.phys.AABB getBoundingBoxForCulling(CorpseEntity corpse,float partialTicks){return corpse.getBoundingBoxForCulling();}
    @Override public State createRenderState() { return new State(); }
    @Override @SuppressWarnings({"unchecked","rawtypes"})
    public void extractRenderState(CorpseEntity corpse,State state,float partialTick) {
        super.extractRenderState(corpse,state,partialTick);
        state.renderer=null; state.dummy=null;
        if(!corpse.isVisibleYet()) return;
        Entity dummy=corpse.getRenderDummy();
        if(dummy==null) return;
        state.yaw=corpse.getYRot(partialTick);
        state.roll=corpse.getRoll(); state.headLift=0;
        if(corpse.isDragged()) {
            float t=corpse.tickCount+partialTick;
            state.roll+=Mth.sin(t*.35f)*6;
            state.headLift=-10+Mth.sin(t*.5f)*2;
        }
        state.dragon=dummy instanceof DragonEntity;
        if(dummy instanceof DragonEntity dragon) dragon.setBurn(Mth.clamp((corpse.tickCount+partialTick-50)/120,0,1));
        state.half=corpse.isCentered()?corpse.getBodyHeight()*.5f:0;
        state.lift=Math.min(dummy.getBbWidth()*.5f,.75f);
        state.renderer=(EntityRenderer)Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(dummy);
        state.dummy=state.renderer.createRenderState(dummy,0);
    }
    @Override public void submit(State state,PoseStack pose,SubmitNodeCollector collector,CameraRenderState camera) {
        if(state.renderer!=null && state.dummy!=null) {
            pose.pushPose();
            pose.rotateDegrees(Axis.YP,180-state.yaw);
            if(state.dragon) pose.rotateDegrees(Axis.ZP,10+state.roll*.3f);
            else {
                pose.translate(0,state.lift,0);
                if(state.headLift!=0) pose.rotateDegrees(Axis.ZP,state.headLift);
                pose.rotateDegrees(Axis.XP,state.roll);
                pose.translate(state.half,0,0);
                pose.rotateDegrees(Axis.ZP,90);
            }
            state.renderer.submit(state.dummy,pose,collector,camera);
            pose.popPose();
        }
        super.submit(state,pose,collector,camera);
    }
    @Override protected boolean shouldShowName(CorpseEntity corpse,double distanceSquared) {
        return corpse.hasCustomName()&&corpse.isVisibleYet()&&corpse==Minecraft.getInstance().crosshairPickEntity;
    }
}
