package com.nirncraft.crime;

import com.nirncraft.dialogue.Dialogue;
import net.neoforged.bus.api.IEventBus;

/**
 * Holds, bounties, guards, arrest, jail, stolen goods, pickpocketing and lockpicking.
 *
 * <ul>
 *     <li>{@link Bounty}: public API over {@code data.module("crime")} (bounties per hold, crime counters)</li>
 *     <li>{@link Crimes}: witnesses, assault and murder</li>
 *     <li>{@link Theft}: stealing from owned containers</li>
 *     <li>{@link Pickpocket}: sneak + use on townsfolk</li>
 *     <li>{@link Locks}: locked loot containers and the lockpicking minigame</li>
 *     <li>{@link Guards}: guard behaviour and the arrest dialogue</li>
 *     <li>{@link Jail}: the {@code nirncraft:jail} dimension and sentences</li>
 * </ul>
 */
public final class CrimeModule {
    private CrimeModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        CrimeItems.init(modBus);
        Dialogue.registerProvider(Guards::addOptions);
        Dialogue.registerProvider(JailVisitors::addOptions);
    }

    /** Registers this module's packets via {@link com.nirncraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        CrimePackets.register();
    }
}
