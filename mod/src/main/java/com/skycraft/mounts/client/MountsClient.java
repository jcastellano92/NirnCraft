package com.skycraft.mounts.client;

import com.skycraft.Skycraft;
import com.skycraft.creatures.client.BestiaryClient;
import com.skycraft.mounts.MountEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Renderers of the ridden beasts: the bestiary's rig models with the mounts' skins
 * ({@code textures/entity/creatures/<name>.png}; variant 0 bare, 1 saddled, 2 barded, painted by
 * {@code tools/textures/mounts.py}).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class MountsClient {
    private MountsClient() {}

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        BestiaryClient.rig(event, MountEntities.SENCHE, "senche_tiger", 0.7f, 1.0f,
                new String[]{"senche_tiger", "senche_mount_saddled", "senche_mount_barded"}, null, r -> {});
        BestiaryClient.rig(event, MountEntities.GIANT_WOLF, "wolf", 0.8f, 1.55f,
                new String[]{"giant_wolf", "giant_wolf_saddled", "giant_wolf_barded"}, null, r -> {});
        BestiaryClient.rig(event, MountEntities.INDRIK, "indrik", 0.6f, 1.0f,
                new String[]{"indrik", "indrik_saddled", "indrik_barded"}, null, r -> {});
        BestiaryClient.rig(event, MountEntities.MAMMOTH, "mammoth", 1.4f, 1.9f,
                new String[]{"mammoth", "mammoth_saddled", "mammoth_barded"}, null, r -> {});
        BestiaryClient.rig(event, MountEntities.AKAVIRI_TIGER, "senche_tiger", 0.8f, 1.12f,
                new String[]{"akaviri_tiger", "akaviri_tiger_saddled", "akaviri_tiger_barded"}, null, r -> {});
        BestiaryClient.rig(event, MountEntities.REALM_GRAZER, "realm_grazer", 0.7f, 1.0f,
                new String[]{"realm_grazer_mount", "realm_grazer_mount_saddled", "realm_grazer_mount_barded"}, null, r -> {});
    }
}
