package com.skycraft.society.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.skycraft.Skycraft;
import com.skycraft.society.NpcLook;
import com.skycraft.society.NpcRole;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.Identifier;

/**
 * Renders society NPCs: their composited race/outfit skin ({@link NpcSkins}, cached per look), race features (ears,
 * tails, horns, tusks), vanilla armor and held items. Children are drawn at their smaller scale; Altmer stand a little
 * taller and Bosmer a little shorter. NPCs without a look yet use the old per-role skins.
 */
public class NpcRenderer extends HumanoidMobRenderer<NpcEntity, net.minecraft.client.renderer.entity.state.HumanoidRenderState, NpcModel> {
    /** Old per-role skins; roles added later fall back to a similar one. */
    private static final Identifier[][] TEXTURES = new Identifier[NpcRole.VALUES.length][NpcRole.SKINS];

    static {
        for (NpcRole role : NpcRole.VALUES) {
            String id = switch (role) {
                case FARMER, FISHER, CITIZEN, CHILD -> "hunter";
                case BLACKSMITH -> "miner";
                case MERCHANT, APOTHECARY, BUTCHER, CARAVAN_TRADER -> "innkeeper";
                case SCHOLAR, TRAINER -> "mage";
                case STEWARD -> "jarl";
                default -> role.id;
            };
            for (int i = 0; i < NpcRole.SKINS; i++) {
                TEXTURES[role.ordinal()][i] = Identifier.fromNamespaceAndPath(Skycraft.MODID, "textures/entity/society/" + id + "_" + i + ".png");
            }
        }
    }

    public NpcRenderer(EntityRendererProvider.Context context) {
        super(context, new NpcModel(context.bakeLayer(SocietyClient.NPC_LAYER)), 0.5f);
        this.addLayer(new HumanoidArmorLayer<>(this,
                net.minecraft.client.renderer.entity.ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(),
                        part -> new HumanoidModel<net.minecraft.client.renderer.entity.state.HumanoidRenderState>(part)), context.getEquipmentRenderer()));
        this.addLayer(new RaceFeatureLayer<>(this, context.bakeLayer(SocietyClient.RACE_FEATURES_LAYER), NpcRenderer::features));
    }

    private static RaceFeatureLayer.Features features(net.minecraft.client.renderer.entity.state.HumanoidRenderState state) {
        NpcRenderData npc = NpcRenderData.get(state);
        return npc == null || npc.look() < 0 ? null : RaceFeatureLayer.Features.of(npc.look());
    }

    @Override
    public net.minecraft.client.renderer.entity.state.HumanoidRenderState createRenderState() {
        return new net.minecraft.client.renderer.entity.state.HumanoidRenderState();
    }

    @Override
    public void extractRenderState(NpcEntity entity, net.minecraft.client.renderer.entity.state.HumanoidRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        if (entity.getLook() >= 0 && !entity.isChild()) state.scale *= NpcLook.bodyScale(entity.getLook());
    }

    @Override
    public Identifier getTextureLocation(net.minecraft.client.renderer.entity.state.HumanoidRenderState state) {
        NpcRenderData npc = NpcRenderData.get(state);
        if (npc == null) return TEXTURES[0][0];
        Identifier skin = NpcSkins.get(npc.look());
        return skin != null ? skin : TEXTURES[npc.role()][Math.floorMod(npc.skin(), NpcRole.SKINS)];
    }

    @Override
    protected void scale(net.minecraft.client.renderer.entity.state.HumanoidRenderState state, PoseStack pose) {
        NpcRenderData npc = NpcRenderData.get(state);
        if (npc == null) return;
        if (npc.isChild()) pose.scale(state.ageScale, state.ageScale, state.ageScale);
        if (npc.isHuddled()) pose.translate(0, 0.62f, 0);
        else if (npc.isKneeling()) pose.translate(0, 0.34f, 0);
        else if (npc.isTending()) pose.translate(0, 0.12f, 0);
    }
}
