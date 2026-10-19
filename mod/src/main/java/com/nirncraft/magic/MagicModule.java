package com.nirncraft.magic;

import net.neoforged.bus.api.IEventBus;

/**
 * Spells (learned from tomes), casting, magicka costs, Dragon Shouts, Words of Power and Word Walls.
 *
 * <ul>
 *     <li>{@link MagicRegistry}: tomes, bound weapons, the Word Wall block, the spell projectile, effects, worldgen</li>
 *     <li>{@link com.nirncraft.magic.spell.Spells}: every spell as data + lambdas; casting in
 *     {@link com.nirncraft.magic.spell.SpellCasting}</li>
 *     <li>{@link com.nirncraft.magic.shout.Shout} / {@link com.nirncraft.magic.shout.Shouting}: the Thu'um</li>
 *     <li>{@link MagicEvents}: server rules (damage, summons, souls, cleanup); client code in {@code magic.client}</li>
 * </ul>
 */
public final class MagicModule {
    private MagicModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        MagicRegistry.init(modBus);
    }

    /** Registers this module's packets via {@link com.nirncraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        MagicPackets.register();
    }
}
