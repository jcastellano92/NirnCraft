package com.nirncraft.dungeons.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nirncraft.NirnCraft;
import com.nirncraft.dungeons.DungeonsRegistry;
import com.nirncraft.dungeons.entity.DwarvenCenturion;
import com.nirncraft.dungeons.entity.DwarvenSphere;
import com.nirncraft.dungeons.entity.DwarvenSpider;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Mod-bus client registration for the dungeons module: automaton model layers and renderers. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class DungeonsClient {
    public static final ModelLayerLocation SPIDER = layer("dwarven_spider");
    public static final ModelLayerLocation SPHERE = layer("dwarven_sphere");
    public static final ModelLayerLocation CENTURION = layer("dwarven_centurion");

    private static final Identifier SPIDER_TEX = tex("dwarven_spider");
    private static final Identifier SPHERE_TEX = tex("dwarven_sphere");
    private static final Identifier CENTURION_TEX = tex("dwarven_centurion");

    private DungeonsClient() {}

    private static ModelLayerLocation layer(String name) {
        return new ModelLayerLocation(Identifier.fromNamespaceAndPath(NirnCraft.MODID, name), "main");
    }

    private static Identifier tex(String name) {
        return Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/entity/dwemer/" + name + ".png");
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(SPIDER, DwarvenSpiderModel::createLayer);
        event.registerLayerDefinition(SPHERE, DwarvenSphereModel::createLayer);
        event.registerLayerDefinition(CENTURION, DwarvenCenturionModel::createLayer);
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(DungeonsRegistry.DWARVEN_SPIDER.get(), SpiderRenderer::new);
        event.registerEntityRenderer(DungeonsRegistry.DWARVEN_SPHERE.get(), SphereRenderer::new);
        event.registerEntityRenderer(DungeonsRegistry.DWARVEN_CENTURION.get(), CenturionRenderer::new);
    }

    static final class SpiderRenderer extends com.nirncraft.fauna.client.FaunaRenderer<DwarvenSpider,DwarvenSpiderModel<DwarvenSpider>> {
        SpiderRenderer(EntityRendererProvider.Context ctx){super(ctx,new DwarvenSpiderModel<>(ctx.bakeLayer(SPIDER)),.5f,1,e->SPIDER_TEX);}
    }
    static final class SphereRenderer extends com.nirncraft.fauna.client.FaunaRenderer<DwarvenSphere,DwarvenSphereModel> {
        SphereRenderer(EntityRendererProvider.Context ctx){super(ctx,new DwarvenSphereModel(ctx.bakeLayer(SPHERE)),.5f,1,e->SPHERE_TEX);}
    }
    static final class CenturionRenderer extends com.nirncraft.fauna.client.FaunaRenderer<DwarvenCenturion,DwarvenCenturionModel> {
        CenturionRenderer(EntityRendererProvider.Context ctx){super(ctx,new DwarvenCenturionModel(ctx.bakeLayer(CENTURION)),1.2f/1.75f,1.75f,e->CENTURION_TEX);}
    }
}
