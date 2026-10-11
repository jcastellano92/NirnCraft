package com.skycraft.curse.client;

import com.skycraft.Skycraft;
import com.skycraft.curse.CurseRegistry;
import com.skycraft.curse.client.model.DeathHoundModel;
import com.skycraft.curse.client.model.GargoyleModel;
import com.skycraft.curse.client.model.VampireLordModel;
import com.skycraft.curse.client.model.WerewolfModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Mod-bus client registration of the curse module: model layers, creature renderers, the player forms, HUD overlays. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CurseClientSetup {
    public static final ModelLayerLocation WEREWOLF = layer("werewolf");
    public static final ModelLayerLocation VAMPIRE_LORD = layer("vampire_lord");
    public static final ModelLayerLocation DEATH_HOUND = layer("death_hound");
    public static final ModelLayerLocation GARGOYLE = layer("gargoyle");

    public static final ResourceLocation WEREWOLF_TEX = tex("werewolf");
    public static final ResourceLocation WEREWOLF_EYES = tex("werewolf_eyes");
    public static final ResourceLocation LORD_TEX = tex("vampire_lord");
    public static final ResourceLocation LORD_EYES = tex("vampire_lord_eyes");
    static final ResourceLocation HOUND_TEX = tex("death_hound");
    static final ResourceLocation HOUND_EYES = tex("death_hound_eyes");
    static final ResourceLocation GARGOYLE_TEX = tex("gargoyle");
    static final ResourceLocation GARGOYLE_EYES = tex("gargoyle_eyes");

    private CurseClientSetup() {}

    private static ModelLayerLocation layer(String name) {
        return new ModelLayerLocation(new ResourceLocation(Skycraft.MODID, name), "main");
    }

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(Skycraft.MODID, "textures/entity/curse/" + name + ".png");
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(WEREWOLF, WerewolfModel::createLayer);
        event.registerLayerDefinition(VAMPIRE_LORD, VampireLordModel::createLayer);
        event.registerLayerDefinition(DEATH_HOUND, DeathHoundModel::createLayer);
        event.registerLayerDefinition(GARGOYLE, GargoyleModel::createLayer);
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(CurseRegistry.WEREWOLF.get(),
                ctx -> new BeastRenderer<>(ctx, new WerewolfModel<>(ctx.bakeLayer(WEREWOLF)), WEREWOLF_TEX, WEREWOLF_EYES, FormRenderer.BEAST_SCALE, 0.7f));
        event.registerEntityRenderer(CurseRegistry.VAMPIRE_LORD.get(),
                ctx -> new BeastRenderer<>(ctx, new VampireLordModel<>(ctx.bakeLayer(VAMPIRE_LORD)), LORD_TEX, LORD_EYES, FormRenderer.LORD_SCALE, 0.7f));
        event.registerEntityRenderer(CurseRegistry.DEATH_HOUND.get(),
                ctx -> new BeastRenderer<>(ctx, new DeathHoundModel<>(ctx.bakeLayer(DEATH_HOUND)), HOUND_TEX, HOUND_EYES, 1.1f, 0.5f));
        event.registerEntityRenderer(CurseRegistry.GARGOYLE.get(),
                ctx -> new BeastRenderer<>(ctx, new GargoyleModel<>(ctx.bakeLayer(GARGOYLE)), GARGOYLE_TEX, GARGOYLE_EYES, 1.15f, 0.8f));
    }

    /** The transformed players' models are baked here, after every resource reload. */
    @SubscribeEvent
    public static void addLayers(EntityRenderersEvent.AddLayers event) {
        FormRenderer.bake(event.getEntityModels());
    }

    @SubscribeEvent
    public static void overlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.VIGNETTE.id(), "curse_tint", CurseHud::renderTint);
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "curse", CurseHud::render);
    }
}
