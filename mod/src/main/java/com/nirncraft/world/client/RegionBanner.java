package com.nirncraft.world.client;

import com.nirncraft.core.SkyData;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.Regions;
import com.nirncraft.world.Provinces;
import com.nirncraft.world.WorldData;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.util.Locale;

/**
 * Skyrim-style border text: a small "Entering Whiterun Hold" that fades in and out under the compass when you cross
 * into another region you have been to before, and the province's name in large spaced capitals ("C Y R O D I I L")
 * when you cross a province border. First visits get the server's "DISCOVERED" banner instead. Nothing shows on
 * login or after teleports within the first moments, so loading into the world stays quiet.
 */
public final class RegionBanner {
    private static final long REGION_MS = 3500;
    private static final long PROVINCE_MS = 5000;
    /** A border must hold for this many checks before it counts (no flicker walking along a border). */
    private static final int STABLE_CHECKS = 2;

    private static String lastRegion;
    private static String lastProvince;
    private static String candidate;
    private static int candidateChecks;
    private static int quietTicks;
    private static int ticks;

    private static Component regionText;
    private static long regionStart;
    private static Component provinceText;
    private static Component provinceSub;
    private static long provinceStart;

    private RegionBanner() {}

    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (quietTicks > 0) quietTicks--;
        if (++ticks % 10 != 0) return;
        if (mc.level.dimension() != Level.OVERWORLD) {
            lastRegion = null;
            lastProvince = null;
            return;
        }
        Region region = Regions.at(mc.level, mc.player.blockPosition());
        if (lastRegion == null) {
            lastRegion = region.id();
            lastProvince = region.province();
            quietTicks = 60;
            return;
        }
        if (region.id().equals(lastRegion)) {
            candidate = null;
            return;
        }
        if (!region.id().equals(candidate)) {
            candidate = region.id();
            candidateChecks = 0;
        }
        if (++candidateChecks < STABLE_CHECKS) return;
        candidate = null;
        boolean provinceChanged = !region.province().equals(lastProvince) && !Provinces.isSea(region);
        lastRegion = region.id();
        if (!Provinces.isSea(region)) lastProvince = region.province();
        if (quietTicks > 0 || Provinces.isSea(region)) return;

        long now = Util.getMillis();
        boolean visited = visited(mc, region.id());
        if (provinceChanged) {
            provinceText = spaced(Provinces.name(region.province()).getString());
            provinceSub = visited ? region.name() : Component.empty();
            provinceStart = now;
        } else if (visited) {
            regionText = Component.translatable("world.nirncraft.hold.entering", region.name());
            regionStart = now;
        }
    }

    /** Teleports, respawns and logins: the next region change is not a border crossing. */
    public static void reset() {
        lastRegion = null;
        lastProvince = null;
        candidate = null;
        regionText = null;
        provinceText = null;
    }

    private static boolean visited(Minecraft mc, String regionId) {
        ListTag holds = SkyData.get(mc.player).module(WorldData.MODULE).getListOrEmpty("holds");
        for (int i = 0; i < holds.size(); i++) if (holds.getStringOr(i, "").equals(regionId)) return true;
        return false;
    }

    private static Component spaced(String s) {
        StringBuilder sb = new StringBuilder();
        String up = s.toUpperCase(Locale.ROOT);
        for (int i = 0; i < up.length(); i++) {
            if (i > 0) sb.append(up.charAt(i) == ' ' ? "  " : " ");
            if (up.charAt(i) != ' ') sb.append(up.charAt(i));
        }
        return Component.literal(sb.toString());
    }

    public static void render(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.hud.isHidden() || mc.player == null) return;
        Font font = mc.font;
        long now = Util.getMillis();
        int y = Math.max(104, height / 2 - 60);
        if (provinceText != null) {
            float a = fade(now - provinceStart, PROVINCE_MS);
            if (now - provinceStart > PROVINCE_MS) {
                provinceText = null;
            } else if (a >= 0.03f) {
                int alpha = (int) (a * 255) << 24;
                g.pose().pushMatrix();
                g.pose().translate(width / 2f,  y);
                g.pose().scale(1.8f,  1.8f);
                g.centeredText(font, provinceText, 0, 0, alpha | 0xF5EBC8);
                g.pose().popMatrix();
                int half = Math.min(width / 2 - 8, (int) (font.width(provinceText) * 0.9f) + 12);
                g.fill(width / 2 - half, y + 18, width / 2 + half, y + 19, alpha | 0x8A7F66);
                if (!provinceSub.getString().isEmpty()) g.centeredText(font, provinceSub, width / 2, y + 23, alpha | 0xBDB59E);
                return; // the province banner already names the region
            }
        }
        if (regionText != null) {
            float a = fade(now - regionStart, REGION_MS);
            if (now - regionStart > REGION_MS) {
                regionText = null;
            } else if (a >= 0.03f) {
                g.centeredText(font, regionText, width / 2, y + 4, (int) (a * 230) << 24 | 0xE8E2D0);
            }
        }
    }

    /** 0..1: half a second in, hold, a second out. */
    private static float fade(long age, long total) {
        if (age < 0 || age > total) return 0;
        float in = age / 500f;
        float out = (total - age) / 1000f;
        return Mth.clamp(Math.min(in, out), 0f, 1f);
    }
}
