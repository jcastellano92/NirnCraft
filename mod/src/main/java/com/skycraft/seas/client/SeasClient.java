package com.skycraft.seas.client;

import com.skycraft.Skycraft;
import com.skycraft.creatures.client.BestiaryClient;
import com.skycraft.creatures.client.kit.RigBaker;
import com.skycraft.creatures.client.kit.RigRenderer;
import com.skycraft.creatures.client.rig.defs.Defs;
import com.skycraft.seas.SchoolFishEntity;
import com.skycraft.seas.SeasRegistry;
import com.skycraft.seas.client.rig.SeaDefs;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Mod-bus client registration of the seas module: its rig models join the bestiary's model definitions, and its
 * creatures get rig renderers with their textures ({@code tools/textures/seas.py}).
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SeasClient {
    static {
        // before any layer or renderer registration: the bestiary looks models up by name in Defs.ALL
        SeaDefs.ALL.forEach(Defs.ALL::putIfAbsent);
    }

    private SeasClient() {}

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        for (String name : SeaDefs.ALL.keySet()) {
            event.registerLayerDefinition(BestiaryClient.layer(name), () -> RigBaker.layer(Defs.get(name).rig));
        }
    }

    private static String[] v(String... names) {
        return names;
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        BestiaryClient.rig(event, SeasRegistry.DREUGH, "dreugh", 0.7f, 1.25f, v("dreugh", "dreugh_elder"), v("dreugh_glow", "dreugh_elder_glow"),
                r -> r.scaleBy(e -> e.animVariant() == 1 ? 1.15f : 1.0f));
        BestiaryClient.rig(event, SeasRegistry.DREUGH_QUEEN, "dreugh", 1.5f, 2.4f, v("dreugh_queen"), v("dreugh_queen_glow"), r -> {});
        BestiaryClient.rig(event, SeasRegistry.SEA_LEVIATHAN, "sea_leviathan", 2.0f, 2.2f, null, v("sea_leviathan_glow"), r -> {});
        BestiaryClient.rig(event, SeasRegistry.GIANT_SLAUGHTERFISH, "giant_slaughterfish", 0.6f, 1.4f, null, null, r -> {});
        BestiaryClient.rig(event, SeasRegistry.SEA_NETCH, "sea_netch", 0.0f, 1.0f, v("sea_netch", "sea_netch_blue"),
                v("sea_netch_glow", "sea_netch_blue_glow"), RigRenderer::translucent);
        BestiaryClient.rig(event, SeasRegistry.WHALE, "whale", 2.0f, 1.6f, null, null, r -> {});
        BestiaryClient.rig(event, SeasRegistry.SEA_CRAB, "sea_crab", 0.8f, 1.2f, v("sea_crab", "sea_crab_coral"), null, r -> {});
        BestiaryClient.rig(event, SeasRegistry.SEAL, "seal", 0.5f, 1.2f, null, null, r -> {});
        BestiaryClient.rig(event, SeasRegistry.HAWK, "hawk", 0.2f, 1.0f, null, null, RigRenderer::bank);
        fish(event, SeasRegistry.RIVER_BETTY.get(), "river_betty", 0.9f);
        fish(event, SeasRegistry.ABECEAN_LONGFIN.get(), "abecean_longfin", 1.05f);
    }

    private static void fish(EntityRenderersEvent.RegisterRenderers event, net.minecraft.world.entity.EntityType<SchoolFishEntity> type,
                             String texture, float scale) {
        ResourceLocation tex = BestiaryClient.tex(texture);
        event.registerEntityRenderer(type, ctx -> new RigRenderer<SchoolFishEntity>(ctx, BestiaryClient.layer("school_fish"),
                Defs.get("school_fish"), 0.15f, e -> tex).scale(scale));
    }
}
