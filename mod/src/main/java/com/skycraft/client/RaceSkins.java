package com.skycraft.client;

import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.skycraft.Skycraft;
import com.skycraft.core.Race;
import com.skycraft.core.SkyData;
import com.skycraft.society.NpcLook;
import com.skycraft.society.client.NpcSkins;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Players wear their race. The look chosen in the character creator (an {@link NpcLook}: race, sub-people, sex, skin
 * tone, hair, beard, clothes) is synced to everyone tracking the player ({@code PlayerData#appearance()}) and
 * composited by {@link NpcSkins} like a townsperson's, so players and NPCs of a race look alike. The skin replaces
 * the player's own (also in first person) by swapping it in the player's {@link PlayerInfo} (and forcing the wide-arm
 * model the layers are painted for); {@link Layer} draws it again for clients where another mod paints the skin.
 * The body is drawn at the race's height ({@link NpcLook#bodyScale}); hitboxes stay vanilla. The race's ears, tails
 * and horns come from {@code society.client.RaceFeatureLayer}.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class RaceSkins {
    /** The look shown on the local player while the character creator is open (UNSET otherwise). */
    private static int previewLook = NpcLook.UNSET;
    private static Field textureLocationsField = null;
    private static Field skinModelField = null;
    private static boolean reflectionFailed = false;

    private RaceSkins() {}

    /** The composited skin for a look (null if its layers are missing). */
    public static ResourceLocation getTexture(int look) {
        return look < 0 ? null : NpcSkins.get(look);
    }

    /** Character creator: show {@code race} in its default look on the local player (null ends the preview). */
    public static void setPreviewRace(Race race) {
        Minecraft mc = Minecraft.getInstance();
        long seed = mc.player != null ? mc.player.getUUID().getLeastSignificantBits() : 0L;
        setPreviewLook(race == null ? NpcLook.UNSET : NpcLook.defaultFor(race, seed));
    }

    /** Character creator: show this look on the local player (UNSET ends the preview). */
    public static void setPreviewLook(int look) {
        previewLook = look;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && look >= 0) applyToPlayer(mc.player, look);
    }

    public static int getPreviewLook() {
        return previewLook;
    }

    /** The race shown on a player: the preview for the local player in the character creator, else their own. */
    public static Race getEffectiveRace(Player player) {
        if (player == null) return null;
        int look = getEffectiveLook(player);
        return look >= 0 ? NpcLook.race(look).core() : null;
    }

    /** The look shown on a player, or UNSET if they have no race yet. */
    public static int getEffectiveLook(Player player) {
        if (player == null) return NpcLook.UNSET;
        if (player == Minecraft.getInstance().player && previewLook >= 0) return previewLook;
        return lookOf(player);
    }

    /** A player's chosen look; players from before looks existed get their race's default one. */
    public static int lookOf(Player player) {
        var data = SkyData.get(player);
        Race race = data.getRace();
        if (race == null) return NpcLook.UNSET;
        CompoundTag tag = data.module("race");
        if (tag.contains("look")) {
            int look = tag.getInt("look");
            if (look >= 0 && NpcLook.race(look).core() == race) return look;
        }
        return NpcLook.defaultFor(race, player.getUUID().getLeastSignificantBits());
    }

    public static void applyToPlayer(Player player, int look) {
        if (player == null || look < 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(player.getUUID());
            ResourceLocation tex = getTexture(look);
            if (info != null && tex != null) applyToPlayerInfo(info, tex);
        }
    }

    @SuppressWarnings("unchecked")
    private static void applyToPlayerInfo(PlayerInfo info, ResourceLocation skin) {
        if (reflectionFailed) return;
        try {
            if (textureLocationsField == null) {
                for (Field f : PlayerInfo.class.getDeclaredFields()) {
                    if (Map.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        textureLocationsField = f;
                    } else if (f.getType() == String.class) {
                        // the only String field: the skin model ("default" or "slim")
                        f.setAccessible(true);
                        skinModelField = f;
                    }
                }
            }
            if (textureLocationsField != null) {
                Map<MinecraftProfileTexture.Type, ResourceLocation> map =
                        (Map<MinecraftProfileTexture.Type, ResourceLocation>) textureLocationsField.get(info);
                if (map != null && !skin.equals(map.get(MinecraftProfileTexture.Type.SKIN))) map.put(MinecraftProfileTexture.Type.SKIN, skin);
            }
            if (skinModelField != null && !"default".equals(skinModelField.get(info))) skinModelField.set(info, "default");
        } catch (Throwable t) {
            reflectionFailed = true;
            Skycraft.LOGGER.warn("Skycraft: could not apply race skins to player info", t);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.level.getGameTime() % 10 != 0 && previewLook < 0) return;
        for (Player p : mc.level.players()) {
            int look = getEffectiveLook(p);
            if (look >= 0) applyToPlayer(p, look);
        }
    }

    /** Players are drawn at their race's height (the render only; the hitbox stays vanilla). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderPre(RenderPlayerEvent.Pre event) {
        int look = getEffectiveLook(event.getEntity());
        float s = look >= 0 ? NpcLook.bodyScale(look) : 1f;
        event.getPoseStack().pushPose();
        if (s != 1f) event.getPoseStack().scale(s, s, s);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderPost(RenderPlayerEvent.Post event) {
        event.getPoseStack().popPose();
    }

    /** Draws the race skin over the player body (for clients where another mod supplies the skin). */
    public static class Layer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        public Layer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                           AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                           float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
            if (player.isInvisible()) return;
            int look = getEffectiveLook(player);
            ResourceLocation skin = getTexture(look);
            if (skin == null || skin.equals(player.getSkinTextureLocation())) return;
            VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityCutoutNoCull(skin));
            this.getParentModel().renderToBuffer(poseStack, vertexConsumer, packedLight,
                    LivingEntityRenderer.getOverlayCoords(player, 0.0F), 1.0F, 1.0F, 1.0F, 1.0F);
        }
    }
}
