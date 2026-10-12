package com.skycraft.client.screen;

import com.skycraft.Skycraft;
import com.skycraft.inventory.client.SkyUi;
import com.skycraft.visual.VisualConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Restyles vanilla screens that still appear in a Skycraft session so they match the Skycraft menus: the death
 * screen (dark red vignette, "YOU DIED" in large capitals, the cause of death, Skyrim text buttons) and plain
 * confirmation dialogs (smoky panel between ornaments). Only the drawing is replaced: the screens' own widgets,
 * delays and callbacks are untouched, and anything we can't read is simply not drawn.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class ScreenSkins {
    private static Screen deathScreen;
    private static long deathSince;

    private ScreenSkins() {}

    private static boolean enabled() {
        try {
            return VisualConfig.SKIN_VANILLA_SCREENS.get();
        } catch (IllegalStateException notLoadedYet) {
            return false;
        }
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Pre event) {
        Screen screen = event.getScreen();
        if (!enabled()) return;
        if (screen.getClass() == DeathScreen.class) {
            event.setCanceled(true);
            renderDeath((DeathScreen) screen, event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
        } else if (screen.getClass() == ConfirmScreen.class) {
            event.setCanceled(true);
            renderConfirm((ConfirmScreen) screen, event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
        }
    }

    /**
     * Dying should just respawn you: the death screen's "Title Screen" button (which dropped players out to the
     * character/world selector) is removed. Quitting is still possible from the pause menu once respawned.
     */
    @SubscribeEvent
    public static void onDeathScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen().getClass() != DeathScreen.class) return;
        for (GuiEventListener child : List.copyOf(event.getListenersList())) {
            if (child instanceof AbstractButton b && b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                    && t.getKey().equals("deathScreen.titleScreen")) {
                b.visible = false;
                b.active = false;
            }
        }
    }

    // ------------------------------------------------------------------ death

    private static void renderDeath(DeathScreen screen, GuiGraphics g, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        if (deathScreen != screen) {
            deathScreen = screen;
            deathSince = Util.getMillis();
        }
        float t = Mth.clamp((Util.getMillis() - deathSince) / 2500f, 0f, 1f);
        float ease = 1f - (1f - t) * (1f - t);
        int w = screen.width, h = screen.height;
        g.fillGradient(0, 0, w, h, (int) (0x70 * ease) << 24 | 0x1A0000, (int) (0xD0 * ease) << 24 | 0x080000);
        SkyUi.smoke(g, w, h, (Util.getMillis() % 1_000_000L) / 1000f, 0.5f * ease);

        int cy = h / 4 + 10;
        String title = SkyUi.caps(screen.getTitle());
        float scale = 2.6f + 0.25f * (1f - ease);
        int glow = SkyUi.fade(0x50B02020, ease);
        for (int i = 0; i < 4; i++) {
            SkyUi.scaledCentered(g, font, title, w / 2f + ((i & 1) == 0 ? -1 : 1), cy + ((i & 2) == 0 ? -1 : 1), scale, glow, false);
        }
        SkyUi.scaledCentered(g, font, title, w / 2f, cy, scale, SkyUi.fade(0xFFE8D8C0, ease), true);
        SkyUi.ornament(g, w / 2, cy + 30, Math.min(120, w / 3), SkyUi.fade(0xFFC8BC9A, ease));
        Component cause = ScreenFields.component(screen, DeathScreen.class, 0);
        if (cause != null) {
            List<FormattedCharSequence> lines = font.split(cause, Math.min(w - 40, 320));
            for (int i = 0; i < lines.size(); i++) {
                FormattedCharSequence line = lines.get(i);
                g.drawString(font, line, (w - font.width(line)) / 2, cy + 40 + i * 10, SkyUi.fade(0xFFBDB5A0, ease), true);
            }
        }
        drawButtons(screen, g, font, mouseX, mouseY, ease);
    }

    // ------------------------------------------------------------------ confirmation

    private static void renderConfirm(ConfirmScreen screen, GuiGraphics g, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = screen.width, h = screen.height;
        if (mc.level == null) g.fill(0, 0, w, h, 0xFF08080A);
        else g.fillGradient(0, 0, w, h, 0xB0000000, 0xD0000000);
        SkyUi.smoke(g, w, h, (Util.getMillis() % 1_000_000L) / 1000f, 0.7f);

        int panelW = Math.min(w - 24, 340);
        Component message = ScreenFields.component(screen, ConfirmScreen.class, 0);
        List<FormattedCharSequence> lines = message == null ? List.of() : font.split(message, panelW - 30);
        int top = Math.max(10, h / 2 - 50 - lines.size() * 5);
        int bottom = buttonsBottom(screen, top + 60 + lines.size() * 10);
        SkyUi.panel(g, (w - panelW) / 2, top, (w + panelW) / 2, bottom + 8);
        SkyUi.scaledCentered(g, font, SkyUi.caps(screen.getTitle()), w / 2f, top + 10, 1.2f, SkyUi.GOLD, true);
        SkyUi.ornament(g, w / 2, top + 26, Math.min(100, panelW / 2 - 20), 0xC0C8BC9A);
        for (int i = 0; i < lines.size(); i++) {
            FormattedCharSequence line = lines.get(i);
            g.drawString(font, line, (w - font.width(line)) / 2, top + 34 + i * 10, SkyUi.TEXT, false);
        }
        drawButtons(screen, g, font, mouseX, mouseY, 1f);
    }

    private static int buttonsBottom(Screen screen, int fallback) {
        int bottom = fallback;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractButton b && b.visible) bottom = Math.max(bottom, b.getY() + b.getHeight());
        }
        return bottom;
    }

    private static void drawButtons(Screen screen, GuiGraphics g, Font font, int mouseX, int mouseY, float alpha) {
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractButton b && b.visible) {
                boolean hover = SkyUi.inside(mouseX, mouseY, b.getX(), b.getY(), b.getX() + b.getWidth(), b.getY() + b.getHeight());
                SkyUi.button(g, font, b.getMessage(), b.getX(), b.getY(), b.getWidth(), b.getHeight(), hover || b.isFocused(), b.active, alpha);
            }
        }
    }
}
