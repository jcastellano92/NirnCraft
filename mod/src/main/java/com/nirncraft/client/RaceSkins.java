package com.nirncraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nirncraft.NirnCraft;
import com.nirncraft.core.Race;
import com.nirncraft.core.SkyData;
import com.nirncraft.society.NpcLook;
import com.nirncraft.society.client.NpcSkins;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;


/**
 * Players wear their race. The look chosen in the character creator (an {@link NpcLook}: race, sub-people, sex, skin
 * tone, hair, beard, clothes) is synced to everyone tracking the player ({@code PlayerData#appearance()}) and
 * composited by {@link NpcSkins} like a townsperson's, so players and NPCs of a race look alike. The skin replaces
 * the player's own (also in first person) by swapping it in the player's {@link PlayerInfo} (and forcing the wide-arm
 * model the layers are painted for); {@link Layer} draws it again for clients where another mod paints the skin.
 * The body is drawn at the race's height ({@link NpcLook#bodyScale}); hitboxes stay vanilla. The race's ears, tails
 * and horns come from {@code society.client.RaceFeatureLayer}.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class RaceSkins {
    /** The look shown on the local player while the character creator is open (UNSET otherwise). */
    private static int previewLook = NpcLook.UNSET;
    public static final net.minecraft.util.context.ContextKey<Integer> LOOK =
            new net.minecraft.util.context.ContextKey<>(Identifier.fromNamespaceAndPath(NirnCraft.MODID, "player_look"));

    private RaceSkins() {}

    /** The composited skin for a look (null if its layers are missing). */
    public static Identifier getTexture(int look) {
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
            int look = tag.getIntOr("look", NpcLook.UNSET);
            if (look >= 0 && NpcLook.race(look).core() == race) return look;
        }
        return NpcLook.defaultFor(race, player.getUUID().getLeastSignificantBits());
    }

    /** Warm the composited texture; appearance is applied to every extracted avatar render state. */
    public static void applyToPlayer(Player player, int look) {
        if (player != null && look >= 0) getTexture(look);
    }

    public static void extractAppearance(Player player, net.minecraft.client.renderer.entity.state.AvatarRenderState state) {
        int look = getEffectiveLook(player);
        state.setRenderData(LOOK, look);
        if (look < 0) return;
        Identifier texture = getTexture(look);
        if (texture != null) {
            var previous = state.skin;
            state.skin = net.minecraft.world.entity.player.PlayerSkin.insecure(
                    new net.minecraft.core.ClientAsset.ResourceTexture(texture, texture), previous.cape(), previous.elytra(),
                    net.minecraft.world.entity.player.PlayerModelType.WIDE);
        }
        state.scale *= NpcLook.bodyScale(look);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.level.getGameTime() % 10 != 0 && previewLook < 0) return;
        for (Player p : mc.level.players()) {
            int look = getEffectiveLook(p);
            if (look >= 0) applyToPlayer(p, look);
        }
    }

    /** Draw our skin if another integration replaces it after extraction. */
    public static class Layer extends RenderLayer<net.minecraft.client.renderer.entity.state.AvatarRenderState, PlayerModel> {
        public Layer(RenderLayerParent<net.minecraft.client.renderer.entity.state.AvatarRenderState, PlayerModel> parent) {
            super(parent);
        }

        @Override
        public void submit(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector, int packedLight,
                           net.minecraft.client.renderer.entity.state.AvatarRenderState state, float yRot, float xRot) {
            if (state.isInvisible) return;
            Integer look = state.getRenderData(LOOK);
            if (look == null || look < 0) return;
            Identifier texture = getTexture(look);
            if (texture == null || texture.equals(state.skin.body().texturePath())) return;
            collector.submitModel(getParentModel(), state, pose,
                    net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(texture), packedLight,
                    LivingEntityRenderer.getOverlayCoords(state, 0.0f), -1, null, state.outlineColor);
        }
    }
}
