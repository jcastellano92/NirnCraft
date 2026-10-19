package com.nirncraft.fire.client;

import com.nirncraft.fire.FireSites;
import net.minecraft.resources.Identifier;

import java.util.List;

/** Client handlers of the fire packets (kept out of the common package so a dedicated server never loads them). */
public final class FireClientPackets {
    private FireClientPackets() {}

    public static void sync(Identifier dimension, List<FireSites.Site> sites) {
        ClientFire.setServer(dimension, sites);
    }

    public static void burst(double x, double y, double z, float intensity, int radius) {
        ClientFire.burst(x, y, z, intensity, radius);
    }
}
