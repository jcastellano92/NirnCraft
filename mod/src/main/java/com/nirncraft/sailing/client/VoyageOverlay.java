package com.nirncraft.sailing.client;

import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The voyage screen, Skyrim's travel fade dressed for the sea: the view fades into a dusk seascape where a ship
 * rides the swell across the horizon, with where you are bound, aboard which ship, and how long the crossing takes.
 * It holds until the server has moved the player (detected by the jump in position) and then fades back in on the
 * pier.
 */
public final class VoyageOverlay {
    private static final long FADE_IN = 700, MIN_SHOW = 3800, FADE_OUT = 1100, TIMEOUT = 45000;

    private static long start = -1;
    private static long arrived = -1;
    private static Vec3 origin;
    private static Component title = Component.empty(), subtitle = Component.empty(), time = Component.empty();
    private static Component from = Component.empty(), to = Component.empty();
    private static boolean expedition;

    private VoyageOverlay() {}

    public static void start(Component fromPort, Component toPort, Component ship, int hours, boolean exp) {
        Minecraft mc = Minecraft.getInstance();
        start = Util.getMillis();
        arrived = -1;
        origin = mc.player != null ? mc.player.position() : null;
        expedition = exp;
        from = fromPort;
        to = toPort;
        title = Component.translatable(exp ? "sailing.nirncraft.voyage.expedition" : "sailing.nirncraft.voyage.title", toPort);
        subtitle = Component.translatable("sailing.nirncraft.voyage.aboard", ship);
        time = hours <= 0 ? Component.empty() : hours < 48
                ? Component.translatable("sailing.nirncraft.voyage.hours", hours)
                : Component.translatable("sailing.nirncraft.voyage.days", Math.round(hours / 24f));
    }

    public static void stop() {
        start = -1;
    }

    public static boolean active() {
        return start >= 0;
    }

    private static float alpha(long now) {
        long el = now - start;
        if (el > TIMEOUT) return -1;
        if (arrived < 0) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && origin != null && mc.player.position().distanceToSqr(origin) > 48 * 48) arrived = now;
        }
        long outStart = arrived < 0 ? Long.MAX_VALUE : Math.max(start + MIN_SHOW, arrived + 900);
        if (now >= outStart) {
            float t = (now - outStart) / (float) FADE_OUT;
            return t >= 1 ? -1 : 1f - t;
        }
        return Math.min(1f, el / (float) FADE_IN);
    }

    public static void render(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partial = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        if (start < 0) return;
        long now = Util.getMillis();
        float a = alpha(now);
        if (a < 0) {
            start = -1;
            return;
        }
        float t = (now - start) / 1000f;
        int ai = Mth.clamp((int) (a * 255), 0, 255);
        // black under everything, the scene fades in over it a little later
        g.fill(0, 0, width, height, ai << 24);
        float sa = Mth.clamp(a * 1.3f - 0.3f, 0f, 1f);
        if (sa <= 0) return;
        int horizon = (int) (height * 0.56f);
        // dusk sky
        int skyTop = expedition ? 0x141A2C : 0x1E2844;
        int skyLow = expedition ? 0x7A5A48 : 0xC88A5A;
        for (int y = 0; y < horizon; y += 2) {
            float f = y / (float) horizon;
            g.fill(0, y, width, y + 2, argb(sa, mix(skyTop, skyLow, f * f)));
        }
        // the sun low on the horizon
        int sunX = (int) (width * 0.72f), sunY = horizon - 6;
        for (int r = 18; r > 0; r -= 3) {
            g.fill(sunX - r, sunY - r / 2, sunX + r, sunY + r / 3, argb(sa * (0.12f + 0.05f * (18 - r) / 3f), 0xF8D898));
        }
        // sea
        for (int y = horizon; y < height; y += 2) {
            float f = (y - horizon) / (float) (height - horizon);
            g.fill(0, y, width, y + 2, argb(sa, mix(0x2A3C50, 0x0C141E, f)));
        }
        // swell lines drifting past
        for (int row = 0; row < 14; row++) {
            float f = row / 14f;
            int y = horizon + 3 + (int) (f * f * (height - horizon - 6));
            int dash = 6 + (int) (f * 26);
            int gap = dash * 3;
            float speed = 12 + f * 70;
            int offset = (int) ((t * speed + row * 37) % (dash + gap));
            int col = argb(sa * (0.25f + 0.35f * f), f < 0.3f ? 0xE0B888 : 0x6A8AA8);
            for (int x = -dash - gap + offset; x < width; x += dash + gap) {
                g.fill(x, y, x + dash, y + 1 + (int) (f * 2), col);
            }
        }
        // the ship rides the swell across the view
        float px = (0.18f + 0.08f * (t / 6f)) * width;
        float bob = Mth.sin(t * 1.7f) * 2.5f;
        float rock = Mth.sin(t * 1.3f) * 0.08f;
        drawShip(g, (int) px, (int) (horizon + 4 + bob), rock, sa);

        Font font = Minecraft.getInstance().font;
        int ta = Math.max(4, (int) (sa * 255)) << 24;
        g.pose().pushMatrix();
        g.pose().translate(width / 2f,  height * 0.16f);
        g.pose().scale(1.6f,  1.6f);
        g.centeredText(font, title, 0, 0, ta | 0xF0E6CC);
        g.pose().popMatrix();
        g.centeredText(font, subtitle, width / 2, (int) (height * 0.16f) + 20, ta | 0xC8BC9C);
        // route line with the ship's progress
        int ly = (int) (height * 0.86f);
        int lx0 = width / 2 - 90, lx1 = width / 2 + 90;
        g.fill(lx0, ly, lx1, ly + 1, ta | 0x8A7A60);
        float prog = Mth.clamp((now - start) / (float) Math.max(MIN_SHOW, arrived < 0 ? MIN_SHOW + 2000 : arrived - start + 900), 0f, 1f);
        int dot = lx0 + (int) ((lx1 - lx0) * prog);
        g.fill(dot - 2, ly - 2, dot + 3, ly + 3, ta | 0xF0E6CC);
        g.text(font, from, lx0 - font.width(from) - 6, ly - 4, ta | 0xA89C80, false);
        g.text(font, to, lx1 + 6, ly - 4, ta | 0xA89C80, false);
        if (!time.getString().isEmpty()) g.centeredText(font, time, width / 2, ly + 8, ta | 0xA89C80);
    }

    /** A cog in silhouette: hull, castles, mast and a filled sail. */
    private static void drawShip(GuiGraphicsExtractor g, int x, int y, float rock, float a) {
        int hull = argb(a, 0x1A120C), sail = argb(a, 0xD8C8A8), sailShade = argb(a, 0xB8A888), mast = argb(a, 0x22180F);
        g.pose().pushMatrix();
        g.pose().translate(x,  y);
        g.pose().rotate(rock);
        // hull: rows narrowing to the keel, castles at both ends
        for (int r = 0; r < 9; r++) {
            int half = 34 - r * 2 - (r > 5 ? (r - 5) * 3 : 0);
            g.fill(-half, -r - 1, half + 4, -r, hull);
        }
        g.fill(-36, -14, -20, -8, hull);
        g.fill(22, -12, 38, -8, hull);
        g.fill(36, -12, 52, -10, hull); // bowsprit
        g.fill(-1, -60, 2, -8, mast);
        g.fill(-18, -56, 20, -54, mast);
        for (int r = 0; r < 38; r++) {
            int bulge = (int) (Mth.sin(r / 38f * Mth.PI) * 5);
            g.fill(-17 + bulge, -54 + r, 19 + bulge, -53 + r, r % 9 < 2 ? sailShade : sail);
        }
        g.fill(-2, -64, 10, -61, argb(a, 0xA02820)); // pennant
        g.pose().popMatrix();
    }

    private static int argb(float a, int rgb) {
        return (Mth.clamp((int) (a * 255), 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static int mix(int c0, int c1, float t) {
        int r = (int) Mth.lerp(t, (c0 >> 16) & 255, (c1 >> 16) & 255);
        int gg = (int) Mth.lerp(t, (c0 >> 8) & 255, (c1 >> 8) & 255);
        int b = (int) Mth.lerp(t, c0 & 255, c1 & 255);
        return (r << 16) | (gg << 8) | b;
    }
}
