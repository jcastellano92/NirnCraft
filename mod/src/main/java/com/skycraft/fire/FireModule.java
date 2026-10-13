package com.skycraft.fire;

import com.skycraft.fire.aftermath.AftermathPainters;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Fire and the aftermath of fire: the big fire block family and charred building set ({@link FireBlocks}), fire sites
 * drawn from afar ({@link FireSites}, {@code client.FarFireRenderer}), burnt ruins and the scenes of a town that was just
 * attacked ({@code aftermath}), and the sounds and music that go with them ({@link FireSounds}). docs/FEATURES.md has
 * the API other systems use.
 */
public final class FireModule {
    private FireModule() {}

    /** Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        FireBlocks.init(modBus);
        FireSounds.init(modBus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, FireConfig.CLIENT_SPEC, "skycraft-fire-client.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, FireConfig.COMMON_SPEC, "skycraft-fire.toml");
        AftermathPainters.register();
    }

    /** Called from {@code SkyNetwork.init} (packet ids must match on both sides). */
    public static void registerPackets() {
        FirePackets.register();
    }
}
