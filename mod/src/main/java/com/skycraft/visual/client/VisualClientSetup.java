package com.skycraft.visual.client;

import com.skycraft.Skycraft;
import com.skycraft.client.SkyBossHud;
import com.skycraft.client.hud.SkyCrosshair;
import com.skycraft.client.hud.TargetHud;
import com.skycraft.visual.VisualParticles;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegistryObject;

/** Mod-bus client registration of the visual package: particle sprites and the extra HUD overlays. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class VisualClientSetup {
    private VisualClientSetup() {}

    @SubscribeEvent
    public static void particles(RegisterParticleProvidersEvent event) {
        register(event, VisualParticles.SNOWFLAKE, AtmosParticle.Kind.SNOW);
        register(event, VisualParticles.ASH_FLAKE, AtmosParticle.Kind.ASH);
        register(event, VisualParticles.EMBER, AtmosParticle.Kind.EMBER);
        register(event, VisualParticles.SAND, AtmosParticle.Kind.SAND);
        register(event, VisualParticles.LEAF, AtmosParticle.Kind.LEAF);
        register(event, VisualParticles.FIREFLY, AtmosParticle.Kind.FIREFLY);
        register(event, VisualParticles.FROST_BREATH, AtmosParticle.Kind.BREATH);
        register(event, VisualParticles.MARSH_WISP, AtmosParticle.Kind.WISP);
        register(event, VisualParticles.DUST_MOTE, AtmosParticle.Kind.MOTE);
    }

    private static void register(RegisterParticleProvidersEvent event, RegistryObject<SimpleParticleType> type, AtmosParticle.Kind kind) {
        event.registerSpriteSet(type.get(), sprites -> {
            AmbientParticles.SPRITES[kind.ordinal()] = sprites;
            return new AtmosParticle.Provider(kind, sprites);
        });
    }

    @SubscribeEvent
    public static void overlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "sky_crosshair", SkyCrosshair::render);
        event.registerAbove(VanillaGuiOverlay.BOSS_EVENT_PROGRESS.id(), "target_bar", TargetHud::render);
        event.registerAbove(VanillaGuiOverlay.BOSS_EVENT_PROGRESS.id(), "sky_boss_bar", SkyBossHud::render);
    }
}
