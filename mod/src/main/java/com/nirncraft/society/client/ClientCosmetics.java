package com.nirncraft.society.client;

import java.util.UUID;

/** Which faction regalia each known player wears (synced by the server, "" or absent = none). */
public final class ClientCosmetics {
    private ClientCosmetics() {}

    public static String get(UUID player) {
        return ClientSociety.COSMETICS.getOrDefault(player, "");
    }

    static void set(UUID player, String cosmetic) {
        if (cosmetic == null || cosmetic.isEmpty()) ClientSociety.COSMETICS.remove(player);
        else ClientSociety.COSMETICS.put(player, cosmetic);
    }
}
