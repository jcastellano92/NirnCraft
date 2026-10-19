package com.nirncraft.society.client;

import net.minecraft.network.chat.Component;

import java.util.UUID;

/** Client handlers for society S2C packets (only ever class-loaded on the client). */
public final class SocietyClientPackets {
    private SocietyClientPackets() {}

    public static void bark(int entityId, Component text, int ticks) {
        ClientSociety.BARKS.put(entityId, new ClientSociety.Bark(text, ClientSociety.ticks, Math.max(20, ticks)));
    }

    public static void cosmetic(UUID player, String cosmetic) {
        ClientCosmetics.set(player, cosmetic);
    }
}
