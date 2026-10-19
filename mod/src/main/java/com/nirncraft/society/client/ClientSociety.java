package com.nirncraft.society.client;

import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Client-side state of the society module: active overhead barks and the cosmetics players wear. */
final class ClientSociety {
    record Bark(Component text, long start, int ticks) {
    }

    static final Map<Integer, Bark> BARKS = new HashMap<>();
    static final Map<UUID, String> COSMETICS = new HashMap<>();
    static long ticks;

    private ClientSociety() {}

    static void tick() {
        ticks++;
        if (ticks % 20 == 0 && !BARKS.isEmpty()) {
            Iterator<Bark> it = BARKS.values().iterator();
            while (it.hasNext()) {
                Bark b = it.next();
                if (ticks - b.start() > b.ticks()) it.remove();
            }
        }
    }

    static void clear() {
        BARKS.clear();
        COSMETICS.clear();
    }
}
