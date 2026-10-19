package com.nirncraft.protect;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;

/**
 * Skyrim-style world protection ("you can't pick up the world"): outside a homestead nobody breaks or places blocks,
 * except the designated harvests; griefing (explosions, fire, mob block-breaking, fluids, pistons) is off.
 *
 * <ul>
 *     <li>{@link Protect}: the decision (break / place) for a player and a block;</li>
 *     <li>{@link Footprints}: where settlements, roads, structures and sites are (cached per chunk);</li>
 *     <li>{@link ProtectEvents}: the Forge event handlers;</li>
 *     <li>{@link ProtectCommands}: {@code /nirncraft-protect status|bypass|check|selftest};</li>
 *     <li>{@link ProtectSelfTest}: the headless self-test (autorun task {@code protect}).</li>
 * </ul>
 */
public final class ProtectModule {
    private ProtectModule() {}

    /** Registers the client-prediction packets via {@link com.nirncraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        ProtectPackets.register();
    }

    /** Registers this module's config and autorun task. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        com.nirncraft.NirnCraft.config(ModConfig.Type.LOCAL, ProtectConfig.SPEC, "nirncraft-protect.toml");
        ProtectSelfTest.registerAutorun();
    }
}
