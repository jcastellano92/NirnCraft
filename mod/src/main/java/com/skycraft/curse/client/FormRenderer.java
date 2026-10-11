package com.skycraft.curse.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.skycraft.curse.Form;
import com.skycraft.curse.client.model.BeastModel;
import com.skycraft.curse.client.model.VampireLordModel;
import com.skycraft.curse.client.model.WerewolfModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
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

    private static ResourceLocation texture(Form form) {
        return form == Form.BEAST ? CurseClientSetup.WEREWOLF_TEX : CurseClientSetup.LORD_TEX;
    }

    private static ResourceLocation eyes(Form form) {
        return form == Form.BEAST ? CurseClientSetup.WEREWOLF_EYES : CurseClientSetup.LORD_EYES;
    }

    /** Renders the player's beast body at the entity origin. Returns false if the models aren't baked yet. */
    static boolean render(Player player, Form form, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        BeastModel<Player> model = model(form);
        if (model == null) return false;
        Minecraft mc = Minecraft.getInstance();
        boolean invisible = player.isInvisible();
        boolean ghost = invisible && !player.isInvisibleTo(mc.player);
        if (invisible && !ghost) return true;

        pose.pushPose();
        float bodyRot = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        float headRot = Mth.rotLerp(partialTick, player.yHeadRotO, player.yHeadRot);
        float netHeadYaw = Mth.wrapDegrees(headRot - bodyRot);
        float headPitch = Mth.lerp(partialTick, player.xRotO, player.getXRot());
        float age = player.tickCount + partialTick;
        pose.mulPose(Axis.YP.rotationDegrees(180f - bodyRot));
        if (player.deathTime > 0) {
            float f = ((float) player.deathTime + partialTick - 1f) / 20f * 1.6f;
            pose.mulPose(Axis.ZP.rotationDegrees(Math.min(1f, Mth.sqrt(f)) * 90f));
        } else if (player.hasPose(Pose.SLEEPING)) {
            pose.mulPose(Axis.XP.rotationDegrees(-90f));
        }
        float scale = form == Form.BEAST ? BEAST_SCALE : LORD_SCALE;
        if (player.isCrouching()) pose.translate(0, 0.1, 0);
        pose.scale(-scale, -scale, scale);
        pose.translate(0f, -1.501f, 0f);
        float speed = Math.min(1f, player.walkAnimation.speed(partialTick));
        float position = player.walkAnimation.position(partialTick);
        model.setupAnim(player, position, speed, age, netHeadYaw, headPitch);
        int overlay = LivingEntityRenderer.getOverlayCoords(player, 0f);
        RenderType type = ghost ? RenderType.itemEntityTranslucentCull(texture(form)) : model.renderType(texture(form));
        VertexConsumer vc = buffers.getBuffer(type);
        model.renderToBuffer(pose, vc, light, overlay, 1f, 1f, 1f, ghost ? 0.2f : 1f);
        if (!ghost) model.renderToBuffer(pose, buffers.getBuffer(RenderType.eyes(eyes(form))), 0xF00000, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f);
        pose.popPose();
        return true;
    }

    /** First person: the beast's forearm and claws instead of the player's hand. */
    static boolean renderArm(Player player, Form form, boolean right, float swing, float equip, PoseStack pose, MultiBufferSource buffers, int light) {
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
        pose.mulPose(Axis.YP.rotationDegrees(side * (-12f + 30f * s)));
        pose.mulPose(Axis.ZP.rotationDegrees(side * (-10f - 25f * s)));
        pose.mulPose(Axis.XP.rotationDegrees(-72f - 35f * s2));
        float k = form == Form.BEAST ? 0.95f : 0.85f;
        pose.scale(k, k, k);
        forearm.render(pose, buffers.getBuffer(model.renderType(texture(form))), light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
        return true;
    }
}
