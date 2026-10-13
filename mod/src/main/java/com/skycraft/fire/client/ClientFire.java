package com.skycraft.fire.client;

import com.skycraft.fire.FireSites;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Client side of the fire sites, and the API client-only systems (the intro cutscene's dragon-breath impacts, a
 * spell effect) call to put fire in the distance without any server round trip:
 *
 * <pre>{@code
 * ClientFire.addTemporary(x, y, z, 1.4f, 9, 3, 20 * 8);   // a fire that burns for 8 s, with a heavy smoke column
 * ClientFire.burst(x, y, z, 2.0f, 8);                     // a one-off blast: flash, fireball and a puff of smoke
 * }</pre>
 * Everything here is drawn by {@link FarFireRenderer}; nothing touches blocks or the server.
 */
public final class ClientFire {
    /** A client-side site (server or temporary), with its own clock. */
    static final class View {
        int id;
        double x, y, z;
        float intensity;
        int height, smoke, flags;
        /** Game time (ticks, fractional) at which it appeared and at which it ends (0 = no end). */
        double born, ends;
        /** Fade in/out time in ticks. */
        int fade = 20;
        boolean temporary;
        /** A blast: a short bright flash that expands and dies in {@code life} ticks. */
        boolean blast;
        float radius;
        /** Cooldown for ambient sounds near it. */
        long nextSound;
    }

    static final List<View> SERVER = new ArrayList<>();
    static final List<View> TEMP = new ArrayList<>();
    private static ResourceLocation serverDim;

    private ClientFire() {}

    // ------------------------------------------------------------------ API

    /**
     * A fire that burns for {@code lifeTicks} ticks (fading in and out) at a world position.
     *
     * @param intensity 0..2 (1 = a burning house)
     * @param height    blocks of flame
     * @param smoke     0 none .. 3 heavy
     */
    public static void addTemporary(double x, double y, double z, float intensity, int height, int smoke, int lifeTicks) {
        long now = now();
        View v = new View();
        v.x = x;
        v.y = y;
        v.z = z;
        v.intensity = Math.max(0.05f, Math.min(2.5f, intensity));
        v.height = Math.max(1, Math.min(48, height));
        v.smoke = Math.max(0, Math.min(3, smoke));
        v.born = now;
        v.ends = now + Math.max(10, lifeTicks);
        v.fade = Math.min(40, Math.max(6, lifeTicks / 4));
        v.temporary = true;
        synchronized (TEMP) {
            if (TEMP.size() > 128) TEMP.remove(0);
            TEMP.add(v);
        }
    }

    /** A one-off blast: a fireball that flares and fades in about 1.5 s, leaving a short smoke puff. */
    public static void burst(double x, double y, double z, float intensity, float radius) {
        long now = now();
        View v = new View();
        v.x = x;
        v.y = y;
        v.z = z;
        v.intensity = Math.max(0.2f, Math.min(3.0f, intensity));
        v.height = Math.max(2, Math.round(radius * 1.5f));
        v.radius = Math.max(1f, radius);
        v.smoke = 2;
        v.born = now;
        v.ends = now + 32 + Math.round(radius * 2);
        v.fade = 8;
        v.temporary = true;
        v.blast = true;
        synchronized (TEMP) {
            if (TEMP.size() > 128) TEMP.remove(0);
            TEMP.add(v);
        }
    }

    /** Forgets every temporary site (a cutscene ended). */
    public static void clearTemporary() {
        synchronized (TEMP) {
            TEMP.clear();
        }
    }

    // ------------------------------------------------------------------ internals

    static long now() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0L : mc.level.getGameTime();
    }

    static void setServer(ResourceLocation dim, List<FireSites.Site> sites) {
        synchronized (SERVER) {
            serverDim = dim;
            SERVER.clear();
            long now = now();
            for (FireSites.Site s : sites) {
                View v = new View();
                v.id = s.id();
                v.x = s.x() + 0.5;
                v.y = s.y();
                v.z = s.z() + 0.5;
                v.intensity = s.intensity();
                v.height = s.height();
                v.smoke = s.smoke();
                v.flags = s.flags();
                v.born = now - 100;
                SERVER.add(v);
            }
        }
    }

    /** The server sites if they belong to the level the player is in. */
    static boolean serverCurrent() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension().location().equals(serverDim);
    }

    static void tick() {
        long now = now();
        synchronized (TEMP) {
            TEMP.removeIf(v -> v.ends > 0 && now > v.ends);
        }
    }

    public static void clear() {
        synchronized (SERVER) {
            SERVER.clear();
            serverDim = null;
        }
        clearTemporary();
    }

    /** Sites currently known (server + temporary), for diagnostics. */
    public static int count() {
        synchronized (SERVER) {
            synchronized (TEMP) {
                return SERVER.size() + TEMP.size();
            }
        }
    }
}
