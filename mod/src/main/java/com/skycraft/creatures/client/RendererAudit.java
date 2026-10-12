package com.skycraft.creatures.client;

import com.skycraft.Skycraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Debug listing (once per session, on joining a world): every registered Skycraft entity type whose renderer is
 * missing or only a {@link NoopRenderer}. Playtest 3: spiders were "invisible"; this names such types in the log
 * ("Entity types without a visible renderer: ...") instead of leaving it to a playtest. Intentionally invisible
 * projectiles are expected in the list.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class RendererAudit {
    private static boolean done;

    private RendererAudit() {}

    @SubscribeEvent
    public static void onJoin(ClientPlayerNetworkEvent.LoggingIn event) {
        if (done) return;
        done = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        List<String> missing = new ArrayList<>();
        List<String> noop = new ArrayList<>();
        for (EntityType<?> type : ForgeRegistries.ENTITY_TYPES) {
            var key = ForgeRegistries.ENTITY_TYPES.getKey(type);
            if (key == null || !Skycraft.MODID.equals(key.getNamespace())) continue;
            Entity probe = null;
            try {
                probe = type.create(mc.level);
                if (probe == null) continue;
                EntityRenderer<? super Entity> r = mc.getEntityRenderDispatcher().getRenderer(probe);
                if (r == null) missing.add(key.getPath());
                else if (r instanceof NoopRenderer) noop.add(key.getPath());
            } catch (Throwable t) {
                missing.add(key.getPath() + " (" + t.getClass().getSimpleName() + ")");
            } finally {
                if (probe != null) probe.discard();
            }
        }
        if (!missing.isEmpty()) Skycraft.LOGGER.warn("Entity types without a renderer: {}", missing);
        if (!noop.isEmpty()) Skycraft.LOGGER.info("Entity types drawn as nothing (NoopRenderer): {}", noop);
    }
}
