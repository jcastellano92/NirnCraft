package com.nirncraft.visual.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.client.SkyBossHud;
import com.nirncraft.client.hud.SkyCrosshair;
import com.nirncraft.client.hud.TargetHud;
import com.nirncraft.visual.VisualParticles;
import net.minecraft.core.particles.SimpleParticleType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import java.util.function.Supplier;

/** Mod-bus client registration of the visual package: particle sprites and the extra HUD overlays. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
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
        register(event, VisualParticles.SPELL_FROST, AtmosParticle.Kind.SPELL_FROST);
        register(event, VisualParticles.SPELL_SPARK, AtmosParticle.Kind.SPELL_SPARK);
    }

    private static void register(RegisterParticleProvidersEvent event, Supplier<SimpleParticleType> type, AtmosParticle.Kind kind) {
        event.registerSpriteSet(type.get(), sprites -> {
            AmbientParticles.SPRITES[kind.ordinal()] = sprites;
            return new AtmosParticle.Provider(kind, sprites);
        });
    }

    @SubscribeEvent
    public static void overlays(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, net.minecraft.resources.Identifier.fromNamespaceAndPath(NirnCraft.MODID, "sky_crosshair"), SkyCrosshair::render);
        event.registerAbove(VanillaGuiLayers.BOSS_OVERLAY, net.minecraft.resources.Identifier.fromNamespaceAndPath(NirnCraft.MODID, "target_bar"), TargetHud::render);
        event.registerAbove(VanillaGuiLayers.BOSS_OVERLAY, net.minecraft.resources.Identifier.fromNamespaceAndPath(NirnCraft.MODID, "sky_boss_bar"), SkyBossHud::render);
    }
}
