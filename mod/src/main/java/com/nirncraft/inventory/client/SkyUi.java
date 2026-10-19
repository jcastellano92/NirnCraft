package com.nirncraft.inventory.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nirncraft.NirnCraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.Locale;

/**
 * Skyrim menu drawing helpers shared by every NirnCraft screen and HUD element: dark translucent panels, thin gold
 * lines, ornaments, text buttons and ornate bars. Public so the HUD, title/pause/loading screens and other modules'
 * menus look alike.
 */
public final class SkyUi {
    public static final Identifier ORNAMENT = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/gui/hud/ornament.png");
    public static final Identifier BAR_CAPS = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/gui/hud/bar_caps.png");
    public static final Identifier SMOKE = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/gui/loading/smoke.png");
    public static final int TEXT = 0xFFE8E0C8;
    public static final int BRIGHT = 0xFFFFFFFF;
    public static final int DIM = 0xFF8A8478;
    public static final int HEADER = 0xFFA89F86;
    public static final int GOLD = 0xFFE8C060;
    public static final int LINE = 0xFFC8BC9A;
    public static final int TRIM = 0xFF8A7F66;
    public static final int STOLEN = 0xFFD06050;
    public static final int BAD = 0xFFE05050;
    public static final int LEFT_HAND = 0xFF9FC4E8;

    private SkyUi() {}

    /** A dark translucent panel with faint gold lines on top and bottom. */
    public static void panel(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        g.fillGradient(x1, y1, x2, y2, 0x90000000, 0xA8000000);
        hline(g, x1, x2, y1, 0x70);
        hline(g, x1, x2, y2 - 1, 0x70);
    }

    /** A thin gold line that fades out at both ends. */
    public static void hline(GuiGraphicsExtractor g, int x1, int x2, int y, int alpha) {
        int w = x2 - x1;
        if (w <= 0) return;
        int fade = Math.min(24, w / 4);
        int rgb = LINE & 0xFFFFFF;
        for (int i = 0; i < fade; i++) {
            int a = alpha * (i + 1) / (fade + 1);
            g.fill(x1 + i, y, x1 + i + 1, y + 1, a << 24 | rgb);
            g.fill(x2 - i - 1, y, x2 - i, y + 1, a << 24 | rgb);
        }
        g.fill(x1 + fade, y, x2 - fade, y + 1, alpha << 24 | rgb);
    }

    /** Divider with a small diamond in the middle (Skyrim menu ornament). */
    public static void divider(GuiGraphicsExtractor g, int cx, int y, int half) {
        hline(g, cx - half, cx + half, y, 0xB0);
        diamond(g, cx, y, 2, 0xFFC8BC9A);
    }

    public static void diamond(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        for (int i = -r; i <= r; i++) {
            int w = r - Math.abs(i);
            g.fill(cx - w, cy + i, cx + w + 1, cy + i + 1, color);
        }
    }

    /** Hollow diamond (left-hand marker). */
    public static void diamondOutline(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        for (int i = -r; i <= r; i++) {
            int w = r - Math.abs(i);
            g.fill(cx - w, cy + i, cx - w + 1, cy + i + 1, color);
            g.fill(cx + w, cy + i, cx + w + 1, cy + i + 1, color);
        }
    }

    private static final String[] STAR = {"..#..", "#####", ".###.", ".#.#.", "#...#"};

    /** 5x5 pixel star (favorite marker). */
    public static void star(GuiGraphicsExtractor g, int x, int y, int color) {
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) {
                if (STAR[r].charAt(c) == '#') g.fill(x + c, y + r, x + c + 1, y + r + 1, color);
            }
        }
    }

    /** Small triangle pointing up or down (sort direction). */
    public static void triangle(GuiGraphicsExtractor g, int cx, int y, boolean up, int color) {
        for (int i = 0; i < 3; i++) {
            int row = up ? i : 2 - i;
            g.fill(cx - row, y + i, cx + row + 1, y + i + 1, color);
        }
    }

    /** Upper-case header text ("small caps" look). */
    public static String caps(Component c) {
        return c.getString().toUpperCase(Locale.ROOT);
    }

    public static void caps(GuiGraphicsExtractor g, Font font, Component c, int x, int y, int color) {
        g.text(font, caps(c), x, y, color, false);
    }

    public static void capsCentered(GuiGraphicsExtractor g, Font font, Component c, int cx, int y, int color) {
        String s = caps(c);
        g.text(font, s, cx - font.width(s) / 2, y, color, false);
    }

    /** Thin Skyrim vitals bar (fill shrinks toward the center). */
    public static void bar(GuiGraphicsExtractor g, int x, int y, int w, float fill, int color) {
        fill = Mth.clamp(fill, 0f, 1f);
        g.fill(x - 1, y - 1, x + w + 1, y + 4, 0xFF141414);
        g.fill(x, y, x + w, y + 3, 0xFF2A2A2A);
        int filled = Math.round(w * fill);
        int left = x + (w - filled) / 2;
        g.fill(left, y, left + filled, y + 3, 0xFF000000 | color);
        g.fill(x - 3, y, x - 1, y + 3, TRIM);
        g.fill(x + w + 1, y, x + w + 3, y + 3, TRIM);
    }

    // ------------------------------------------------------------------ shared Skyrim widgets

    /** {@code argb} with its alpha multiplied by {@code alpha} (0..1). */
    public static int fade(int argb, float alpha) {
        int a = (int) (((argb >>> 24) & 0xFF) * Mth.clamp(alpha, 0f, 1f));
        return a << 24 | (argb & 0xFFFFFF);
    }

    /**
     * A Skyrim menu text button: upper-case label; when hovered or focused a dark band with fading gold lines above
     * and below and a diamond on each side. Disabled buttons are dimmed.
     */
    public static void button(GuiGraphicsExtractor g, Font font, Component label, int x, int y, int w, int h, boolean hovered, boolean active, float alpha) {
        String text = caps(label);
        int cx = x + w / 2;
        int ty = y + (h - 8) / 2;
        if (hovered && active) {
            g.fillGradient(x, y, x + w, y + h, fade(0x70000000, alpha), fade(0x90000000, alpha));
            hline(g, x, x + w, y, (int) (0xB0 * alpha));
            hline(g, x, x + w, y + h - 1, (int) (0xB0 * alpha));
            int tw = Math.min(w - 16, font.width(text));
            diamond(g, cx - tw / 2 - 8, y + h / 2, 2, fade(GOLD, alpha));
            diamond(g, cx + tw / 2 + 7, y + h / 2, 2, fade(GOLD, alpha));
        }
        int color = !active ? fade(0xFF5C574E, alpha) : hovered ? fade(BRIGHT, alpha) : fade(0xFFBDB5A0, alpha);
        String shown = ellipsize(font, text, w - 20);
        g.text(font, shown, cx - font.width(shown) / 2, ty, color, hovered && active);
    }

    /** The banner divider ornament (line with a central knot), centred, {@code half} wide each side. */
    public static void ornament(GuiGraphicsExtractor g, int cx, int y, int half, int argb) {
        blit(g, ORNAMENT, cx - half, y - 4, half * 2, 8, 0, 0, 128, 16, 128, 16, argb);
    }

    /**
     * An ornate Skyrim bar: dark trough, fill shrinking toward the centre ({@code centered}) or from the right,
     * a bright upper edge, and carved end caps ({@code boss} picks the winged pair).
     */
    public static void ornateBar(GuiGraphicsExtractor g, int x, int y, int w, int h, float fill, int rgb, float alpha, boolean centered, boolean boss) {
        if (alpha < 0.02f) return;
        int a = (int) (alpha * 255) << 24;
        fill = Mth.clamp(fill, 0f, 1f);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, a | 0x0C0B0A);
        g.fill(x, y, x + w, y + h, (int) (alpha * 0xC0) << 24 | 0x1C1A18);
        int filled = Math.round(w * fill);
        int left = centered ? x + (w - filled) / 2 : x;
        g.fill(left, y, left + filled, y + h, a | rgb);
        g.fill(left, y, left + filled, y + 1, a | brighten(rgb, 70));
        if (h > 3) g.fill(left, y + h - 1, left + filled, y + h, a | darken(rgb, 0.6f));
        // carved end caps from the bar_caps sheet (each cap 24x8 on screen)
        int cy = y + h / 2 - 4;
        int v = boss ? 16 : 0;
        blit(g, BAR_CAPS, x - 25, cy, 24, 8, 0, v, 48, 16, 96, 32, a | 0xFFFFFF);
        blit(g, BAR_CAPS, x + w + 1, cy, 24, 8, 48, v, 48, 16, 96, 32, a | 0xFFFFFF);
    }

    public static int brighten(int rgb, int by) {
        int r = Math.min(255, ((rgb >> 16) & 0xFF) + by);
        int gr = Math.min(255, ((rgb >> 8) & 0xFF) + by);
        int b = Math.min(255, (rgb & 0xFF) + by);
        return r << 16 | gr << 8 | b;
    }

    public static int darken(int rgb, float f) {
        int r = (int) (((rgb >> 16) & 0xFF) * f);
        int gr = (int) (((rgb >> 8) & 0xFF) * f);
        int b = (int) ((rgb & 0xFF) * f);
        return r << 16 | gr << 8 | b;
    }

    /** Slowly drifting smoke over the whole area (menu and loading backgrounds); {@code time} in seconds. */
    public static void smoke(GuiGraphicsExtractor g, int w, int h, float time, float alpha) {
        for (int layer = 0; layer < 2; layer++) {
            float speed = layer == 0 ? 6f : -4f;
            int u = (int) (time * speed) & 255;
            int v = (int) (time * (layer == 0 ? 1.5f : 2.5f)) & 255;
            int scale = layer == 0 ? 2 : 3;
            int tint = (int) (255 * alpha * (layer == 0 ? 0.22f : 0.14f)) << 24 | 0x8C877F;
            blit(g, SMOKE, 0, 0, w, h, u, v, w / scale, h / scale, 256, 256, tint);
        }
    }

    /**
     * Renders an item as a solid 3D model (the item-frame display: flat sprites get their thickness) centred at
     * (cx, cy), {@code scale} GUI pixels per block, turned by the given angles. Lighting is restored afterwards.
     */
    public static void item3d(GuiGraphicsExtractor g, net.minecraft.world.item.ItemStack stack, float cx, float cy, float scale, float rotX, float rotY) {
        SkyItemRender.extract(g, stack, cx, cy, scale, rotX, rotY);
    }

    /** Texture extraction with explicit tint; no global render state survives between widgets. */
    public static void blit(GuiGraphicsExtractor g, Identifier texture, int x, int y,
                            int width, int height, float u, float v, int sourceWidth, int sourceHeight,
                            int textureWidth, int textureHeight, int argb) {
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, texture, x, y, u, v,
                width, height, sourceWidth, sourceHeight, textureWidth, textureHeight, argb);
    }

    public static void blit(GuiGraphicsExtractor g, Identifier texture, int x, int y,
                            int width, int height, float u, float v, int sourceWidth, int sourceHeight,
                            int textureWidth, int textureHeight) {
        blit(g, texture, x, y, width, height, u, v, sourceWidth, sourceHeight, textureWidth, textureHeight, -1);
    }

    public static void blit(GuiGraphicsExtractor g, Identifier texture, int x, int y,
                            float u, float v, int width, int height, int textureWidth, int textureHeight) {
        blit(g, texture, x, y, width, height, u, v, width, height, textureWidth, textureHeight, -1);
    }

    public static void blit(GuiGraphicsExtractor g,Identifier texture,int x,int y,float u,float v,int width,int height,int textureWidth,int textureHeight,int argb) {
        blit(g,texture,x,y,width,height,u,v,width,height,textureWidth,textureHeight,argb);
    }

    public static void blit(GuiGraphicsExtractor g, int x, int y, int ignoredDepth, int width, int height,
                            net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height);
    }

    /** Upper-case text scaled about its centre. */
    public static void scaledCentered(GuiGraphicsExtractor g, Font font, String text, float cx, float y, float scale, int argb, boolean shadow) {
        g.pose().pushMatrix();
        g.pose().translate(cx, y);
        g.pose().scale(scale, scale);
        g.text(font, text, -font.width(text) / 2, 0, argb, shadow);
        g.pose().popMatrix();
    }

    /**
     * "Weight 120/300" right-aligned to {@code rightX}, red when over capacity: the carry state every container and
     * workstation menu shows (the client's live sum of slots and pack against the server's capacity).
     */
    public static void carryLine(GuiGraphicsExtractor g, Font font, int leftX, int y, int rightX) {
        net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) return;
        float cur = ClientInventoryHandlers.liveCurrent();
        float cap = ClientInventoryHandlers.liveCapacity();
        boolean carryOn = ClientInventoryHandlers.enabled;
        boolean over = carryOn && !player.isCreative() && cur > cap;
        String label = caps(Component.translatable("inventory.nirncraft.stat.weight"));
        String value = carryOn ? com.nirncraft.inventory.ItemWeights.format(cur) + "/" + com.nirncraft.inventory.ItemWeights.format(cap)
                : com.nirncraft.inventory.ItemWeights.format(cur);
        int vx = rightX - font.width(value);
        int lx = vx - 4 - font.width(label);
        if (lx < leftX) return;
        g.text(font, label, lx, y, HEADER, false);
        g.text(font, value, vx, y, over ? BAD : BRIGHT, false);
    }

    public static String ellipsize(Font font, String s, int width) {
        if (font.width(s) <= width) return s;
        if (width <= font.width("...")) return "";
        return font.plainSubstrByWidth(s, width - font.width("...")) + "...";
    }

    public static boolean inside(double mx, double my, int x1, int y1, int x2, int y2) {
        return mx >= x1 && mx < x2 && my >= y1 && my < y2;
    }
}
