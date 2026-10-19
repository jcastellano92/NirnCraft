package com.nirncraft.fire;

import com.nirncraft.fire.aftermath.AftermathPainters;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;

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
        com.nirncraft.NirnCraft.config(ModConfig.Type.CLIENT, FireConfig.CLIENT_SPEC, "nirncraft-fire-client.toml");
        com.nirncraft.NirnCraft.config(ModConfig.Type.LOCAL, FireConfig.COMMON_SPEC, "nirncraft-fire.toml");
        AftermathPainters.register();
    }

    /** Called from {@code SkyNetwork.init} (packet ids must match on both sides). */
    public static void registerPackets() {
        FirePackets.register();
    }
}
