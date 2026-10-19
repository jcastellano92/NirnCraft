package com.nirncraft.fauna.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.creatures.client.BestiaryClient;
import com.nirncraft.fauna.FaunaEntities;
import com.nirncraft.fauna.entity.BearEntity;
import com.nirncraft.fauna.entity.DeerEntity;
import com.nirncraft.fauna.entity.ElkEntity;
import com.nirncraft.fauna.entity.HorkerEntity;
import com.nirncraft.fauna.entity.InsectEntity;
import com.nirncraft.fauna.entity.MammothEntity;
import com.nirncraft.fauna.entity.MudcrabEntity;
import com.nirncraft.fauna.entity.SabreCatEntity;
import com.nirncraft.fauna.entity.SlaughterfishEntity;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Mod-bus client registration for the fauna module: model layers and entity renderers. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class FaunaClient {
    public static final ModelLayerLocation BUTTERFLY = layer("butterfly");
    public static final ModelLayerLocation DRAGONFLY = layer("dragonfly");
    public static final ModelLayerLocation TORCHBUG = layer("torchbug");

    private static final Identifier[] BUTTERFLY_TEX = {tex("butterfly_monarch"), tex("butterfly_blue")};
    private static final Identifier MOTH_TEX = tex("luna_moth");
    private static final Identifier DRAGONFLY_TEX = tex("dragonfly");
    private static final Identifier TORCHBUG_TEX = tex("torchbug");
    private static final Identifier TORCHBUG_GLOW = tex("torchbug_glow");

    private FaunaClient() {}

    private static ModelLayerLocation layer(String name) {
        return new ModelLayerLocation(Identifier.fromNamespaceAndPath(NirnCraft.MODID, name), "main");
    }

    private static Identifier tex(String name) {
        return Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/entity/fauna/" + name + ".png");
    }

    private static Identifier pick(Identifier[] options, int variant) {
        return options[Math.floorMod(variant, options.length)];
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(BUTTERFLY, InsectModels.Butterfly::createLayer);
        event.registerLayerDefinition(DRAGONFLY, InsectModels.Dragonfly::createLayer);
        event.registerLayerDefinition(TORCHBUG, InsectModels.Torchbug::createLayer);
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        BestiaryClient.rig(event, FaunaEntities.DEER, "deer", 0.5f, 1.0f, null, null, r -> {});
        BestiaryClient.rig(event, FaunaEntities.ELK, "elk", 0.6f, 1.0f, null, null, r -> {});
        BestiaryClient.rig(event, FaunaEntities.SABRE_CAT, "sabre_cat", 0.6f, 1.0f, new String[]{"sabre_cat", "sabre_cat_snowy"}, null, r -> {});
        BestiaryClient.rig(event, FaunaEntities.HORKER, "horker", 0.8f, 1.0f, null, null, r -> {});
        BestiaryClient.rig(event, FaunaEntities.MUDCRAB, "mudcrab", 0.45f, 1.0f, new String[]{"mudcrab", "mudcrab_giant"}, null,
                r -> r.scaleBy(e -> e.getVariant() == 1 ? 2.2f : 1.0f));
        BestiaryClient.rig(event, FaunaEntities.MAMMOTH, "mammoth", 0.75f, 2.0f, null, null, r -> {});
        BestiaryClient.rig(event, FaunaEntities.BEAR, "bear", 0.7f, 1.0f, new String[]{"bear", "bear_cave", "bear_snow"}, null, r -> {});
        BestiaryClient.rig(event, FaunaEntities.SLAUGHTERFISH, "slaughterfish", 0.3f, 1.0f, null, null, r -> {});

        event.registerEntityRenderer(FaunaEntities.BUTTERFLY.get(), ctx -> new FaunaRenderer<InsectEntity, InsectModels.Butterfly<InsectEntity>>(
                ctx, new InsectModels.Butterfly<>(ctx.bakeLayer(BUTTERFLY)), 0.0f, 0.5f, e -> pick(BUTTERFLY_TEX, e.getVariant())));
        event.registerEntityRenderer(FaunaEntities.MOTH.get(), ctx -> new FaunaRenderer<InsectEntity, InsectModels.Butterfly<InsectEntity>>(
                ctx, new InsectModels.Butterfly<>(ctx.bakeLayer(BUTTERFLY)), 0.0f, 0.65f, e -> MOTH_TEX));
        event.registerEntityRenderer(FaunaEntities.DRAGONFLY.get(), ctx -> new FaunaRenderer<InsectEntity, InsectModels.Dragonfly<InsectEntity>>(
                ctx, new InsectModels.Dragonfly<>(ctx.bakeLayer(DRAGONFLY)), 0.0f, 0.55f, e -> DRAGONFLY_TEX));
        event.registerEntityRenderer(FaunaEntities.TORCHBUG.get(), ctx -> new FaunaRenderer<InsectEntity, InsectModels.Torchbug<InsectEntity>>(
                ctx, new InsectModels.Torchbug<>(ctx.bakeLayer(TORCHBUG)), 0.0f, 0.6f, e -> TORCHBUG_TEX).withGlow(TORCHBUG_GLOW));
    }
}
