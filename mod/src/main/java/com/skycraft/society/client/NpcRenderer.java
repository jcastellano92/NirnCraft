package com.skycraft.society.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.skycraft.Skycraft;
import com.skycraft.society.NpcLook;
import com.skycraft.society.NpcNames;
import com.skycraft.society.NpcRole;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;

/**
 * Renders society NPCs: their composited race/outfit skin ({@link NpcSkins}, cached per look), race features (ears,
 * tails, horns, tusks), vanilla armor and held items. Children are drawn at their smaller scale; Altmer stand a little
 * taller and Bosmer a little shorter. NPCs without a look yet use the old per-role skins.
 */
public class NpcRenderer extends HumanoidMobRenderer<NpcEntity, NpcModel> {
    /** Old per-role skins; roles added later fall back to a similar one. */
    private static final ResourceLocation[][] TEXTURES = new ResourceLocation[NpcRole.VALUES.length][NpcRole.SKINS];

    static {
        for (NpcRole role : NpcRole.VALUES) {
            String id = switch (role) {
                case FARMER, FISHER, CITIZEN, CHILD -> "hunter";
                case BLACKSMITH -> "miner";
                case MERCHANT, APOTHECARY, BUTCHER, CARAVAN_TRADER -> "innkeeper";
                case SCHOLAR -> "mage";
                case STEWARD -> "jarl";
                default -> role.id;
            };
            for (int i = 0; i < NpcRole.SKINS; i++) {
                TEXTURES[role.ordinal()][i] = new ResourceLocation(Skycraft.MODID, "textures/entity/society/" + id + "_" + i + ".png");
            }
        }
    }

    public NpcRenderer(EntityRendererProvider.Context context) {
        super(context, new NpcModel(context.bakeLayer(SocietyClient.NPC_LAYER)), 0.5f);
        this.addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
        this.addLayer(new RaceFeatureLayer<>(this, context.bakeLayer(SocietyClient.RACE_FEATURES_LAYER), NpcRenderer::features));
    }

    private static RaceFeatureLayer.Features features(NpcEntity npc) {
        int look = npc.getLook();
        return look < 0 ? null : new RaceFeatureLayer.Features(NpcLook.race(look), NpcLook.toneColor(look));
    }

    @Override
    public ResourceLocation getTextureLocation(NpcEntity entity) {
        ResourceLocation skin = NpcSkins.get(entity.getLook());
        if (skin != null) return skin;
        return TEXTURES[entity.role().ordinal()][Math.floorMod(entity.getSkin(), NpcRole.SKINS)];
    }

    @Override
    protected void scale(NpcEntity entity, PoseStack poseStack, float partialTick) {
        float s = entity.getScale();
        int look = entity.getLook();
        if (look >= 0 && !entity.isChild()) {
            NpcNames.Race race = NpcLook.race(look);
            if (race == NpcNames.Race.ALTMER) s *= 1.05f;
            else if (race == NpcNames.Race.BOSMER) s *= 0.94f;
            else if (race == NpcNames.Race.ORC || race == NpcNames.Race.NORD && !NpcLook.female(look)) s *= 1.02f;
            if (NpcLook.female(look)) s *= 0.97f;
        }
        if (s != 1.0f) poseStack.scale(s, s, s);
        this.shadowRadius = 0.5f * entity.getScale();
    }
}
