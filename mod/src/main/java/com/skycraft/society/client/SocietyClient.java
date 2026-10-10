package com.skycraft.society.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.skycraft.Skycraft;
import com.skycraft.client.SkyKeys;
import com.skycraft.society.NpcEntities;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Mod-bus client registration for the society module: model layers, NPC renderers, cosmetic layers, key. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SocietyClient {
    public static final ModelLayerLocation NPC_LAYER = new ModelLayerLocation(new ResourceLocation(Skycraft.MODID, "npc"), "main");
    public static final ModelLayerLocation COSMETIC_LAYER = new ModelLayerLocation(new ResourceLocation(Skycraft.MODID, "society_cosmetic"), "main");
    public static final ModelLayerLocation COSMETIC_ARMORED_LAYER = new ModelLayerLocation(new ResourceLocation(Skycraft.MODID, "society_cosmetic"), "armored");
    public static final ModelLayerLocation JEWELRY_LAYER = new ModelLayerLocation(new ResourceLocation(Skycraft.MODID, "jewelry"), "main");
    public static final ModelLayerLocation RACE_FEATURES_LAYER = new ModelLayerLocation(new ResourceLocation(Skycraft.MODID, "race_features"), "main");

    /** Opens the reputation screen (owned by the society module). */
    public static final KeyMapping REPUTATION = new KeyMapping("key.skycraft.reputation", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, SkyKeys.CATEGORY);

    private SocietyClient() {}

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(NPC_LAYER, NpcModel::createLayer);
        event.registerLayerDefinition(COSMETIC_LAYER, () -> CosmeticLayer.createLayer(false));
        event.registerLayerDefinition(COSMETIC_ARMORED_LAYER, () -> CosmeticLayer.createLayer(true));
        event.registerLayerDefinition(JEWELRY_LAYER, JewelryLayer::createLayer);
        event.registerLayerDefinition(RACE_FEATURES_LAYER, RaceFeatureLayer::createLayer);
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(NpcEntities.NPC.get(), NpcRenderer::new);
        event.registerEntityRenderer(NpcEntities.NPC_FIGHTER.get(), NpcRenderer::new);
    }

    @SubscribeEvent
    public static void addLayers(EntityRenderersEvent.AddLayers event) {
        for (String skin : event.getSkins()) {
            PlayerRenderer renderer = event.getSkin(skin);
            if (renderer != null) {
                renderer.addLayer(new com.skycraft.client.RaceSkins.Layer(renderer));
                renderer.addLayer(new RaceFeatureLayer<>(renderer, event.getEntityModels().bakeLayer(RACE_FEATURES_LAYER), RaceFeatureLayer::forPlayer));
                renderer.addLayer(new CosmeticLayer(renderer, event.getEntityModels()));
                renderer.addLayer(new JewelryLayer(renderer, event.getEntityModels()));
            }
        }
    }

    /** Composited NPC skins are rebuilt from the (possibly new) layer textures after a resource reload. */
    @SubscribeEvent
    public static void reloadListeners(net.minecraftforge.client.event.RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> NpcSkins.clear());
    }

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) {
        event.register(REPUTATION);
    }
}
