package com.skycraft.arsenal.client;

import com.skycraft.Skycraft;
import com.skycraft.arsenal.ArsenalEntities;
import com.skycraft.arsenal.ArsenalItems;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import java.util.function.Supplier;

/** Mod-bus client setup of the arsenal: projectile renderers and bow/crossbow model properties. */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class ArsenalClient {
    private static final Identifier PULL = Identifier.parse("pull");
    private static final Identifier PULLING = Identifier.parse("pulling");
    private static final Identifier CHARGED = Identifier.parse("charged");
    private static final Identifier FIREWORK = Identifier.parse("firework");

    private ArsenalClient() {}

    @SubscribeEvent public static void itemExtensions(net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent event){
        for(var item:ArsenalItems.CROSSBOWS)event.registerItem(ArsenalItemExtensions.CROSSBOW,item.get());
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ArsenalEntities.SKY_ARROW.get(), SkyArrowRenderer::new);
        event.registerEntityRenderer(ArsenalEntities.STAFF_BOLT.get(), NoopRenderer::new);
    }


}
