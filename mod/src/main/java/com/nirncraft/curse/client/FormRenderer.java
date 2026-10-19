package com.nirncraft.curse.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nirncraft.curse.Form;
import com.nirncraft.curse.client.model.BeastModel;
import com.nirncraft.curse.client.model.VampireLordModel;
import com.nirncraft.curse.client.model.WerewolfModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import com.nirncraft.client.PoseRenderState;
import com.nirncraft.client.CapturedPartModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

/**
 * Draws a transformed player as the werewolf or the Vampire Lord in place of the player model (third person and
 * everyone else's view), and the claws in first person. Mirrors what {@code LivingEntityRenderer} does for a mob.
 */
public final class FormRenderer {
    public static final float BEAST_SCALE = 1.15f;
    public static final float LORD_SCALE = 1.1f;

    private static WerewolfModel<Player> werewolf;
    private static VampireLordModel<Player> lord;

    private FormRenderer() {}

    static void bake(EntityModelSet models) {
        werewolf = new WerewolfModel<>(models.bakeLayer(CurseClientSetup.WEREWOLF));
        lord = new VampireLordModel<>(models.bakeLayer(CurseClientSetup.VAMPIRE_LORD));
    }

    private static BeastModel<Player> model(Form form) {
        return form == Form.BEAST ? werewolf : lord;
    }

    private static Identifier texture(Form form) {
        return form == Form.BEAST ? CurseClientSetup.WEREWOLF_TEX : CurseClientSetup.LORD_TEX;
    }

    private static Identifier eyes(Form form) {
        return form == Form.BEAST ? CurseClientSetup.WEREWOLF_EYES : CurseClientSetup.LORD_EYES;
    }

    private record Snapshot(Form form,PoseRenderState pose) {}
    private static final net.minecraft.util.context.ContextKey<Snapshot> SNAPSHOT=new net.minecraft.util.context.ContextKey<>(Identifier.fromNamespaceAndPath("nirncraft","curse_form"));
    public static void extract(Player player,AvatarRenderState state) {
        Form form=com.nirncraft.curse.Curses.form(player);
        BeastModel<Player> model=model(form);
        if (form==Form.NONE||model==null) { state.setRenderData(SNAPSHOT,null); return; }
        PoseRenderState pose=new PoseRenderState();
        pose.ageInTicks=state.ageInTicks; pose.walkAnimationPos=state.walkAnimationPos; pose.walkAnimationSpeed=Math.min(1,state.walkAnimationSpeed);
        pose.yRot=state.yRot; pose.xRot=state.xRot;
        model.extractPose(player,pose,state.partialTick);
        state.setRenderData(SNAPSHOT,new Snapshot(form,pose));
    }
    /** Draws the extracted transformed body without consulting live world/player data. */
    static boolean render(AvatarRenderState state,PoseStack pose,SubmitNodeCollector collector) {
        Snapshot snapshot=state.getRenderData(SNAPSHOT);
        if (snapshot==null) return false;
        boolean ghost=state.isInvisible&&!state.isInvisibleToPlayer;
        if (state.isInvisible&&!ghost) return true;
        Form form=snapshot.form();
        BeastModel<Player> model=model(form);
        pose.pushPose();
        pose.rotateDegrees(Axis.YP,180-state.bodyRot);
        if (state.deathTime>0) pose.rotateDegrees(Axis.ZP,Math.min(1,Mth.sqrt((state.deathTime-1)/20*1.6f))*90);
        else if (state.hasPose(Pose.SLEEPING)) pose.rotateDegrees(Axis.XP,-90);
        float scale=form==Form.BEAST?BEAST_SCALE:LORD_SCALE;
        if (state.isCrouching) pose.translate(0,.1,0);
        pose.scale(-scale,-scale,scale);
        pose.translate(0,-1.501,0);
        RenderType type=ghost?RenderTypes.entityTranslucentCull(texture(form)):model.renderType(texture(form));
        collector.submitModel(model,snapshot.pose(),pose,type,state.lightCoords,LivingEntityRenderer.getOverlayCoords(state,0),ghost?0x33ffffff:-1,null,state.outlineColor);
        if (!ghost) collector.order(1).submitModel(model,snapshot.pose(),pose,RenderTypes.eyes(eyes(form)),0xf00000,OverlayTexture.NO_OVERLAY,-1,null,state.outlineColor);
        pose.popPose();
        return true;
    }

    /** First person: the beast's forearm and claws instead of the player's hand. */
    static boolean renderArm(Player player, Form form, boolean right, float swing, float equip, PoseStack pose, SubmitNodeCollector collector, int light) {
        BeastModel<Player> model = model(form);
        if (model == null) return false;
        String side0 = right ? "right" : "left";
        ModelPart forearm = model.root().getChild("body").getChild(side0 + "_arm").getChild(side0 + "_forearm");
        forearm.getAllParts().forEach(ModelPart::resetPose);
        // drawn from the elbow: the forearm reaches forward and a little up into the frame, claws first
        forearm.x = 0f;
        forearm.y = 0f;
        forearm.z = 0f;
        forearm.xRot = 0f;
        float side = right ? 1f : -1f;
        float s = Mth.sin(Mth.sqrt(swing) * Mth.PI);
        float s2 = Mth.sin(swing * Mth.PI);
        pose.pushPose();
        pose.translate(side * (0.48f - 0.22f * s), -0.62f - equip * 0.6f + 0.12f * s2, -0.32f - 0.3f * s);
        pose.rotateDegrees(Axis.YP,side * (-12f + 30f * s));
        pose.rotateDegrees(Axis.ZP,side * (-10f - 25f * s));
        pose.rotateDegrees(Axis.XP,-72f - 35f * s2);
        float k = form == Form.BEAST ? 0.95f : 0.85f;
        pose.scale(k, k, k);
        new CapturedPartModel(forearm).submit(collector,pose,model.renderType(texture(form)),light,OverlayTexture.NO_OVERLAY,-1);
        pose.popPose();
        return true;
    }
}
