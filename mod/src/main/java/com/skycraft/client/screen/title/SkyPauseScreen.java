package com.skycraft.client.screen.title;

import com.skycraft.client.ClientState;
import com.skycraft.network.CorePackets;
import com.skycraft.network.SkyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * Authentic Skyrim-style System / Pause Menu:
 * Clean Nordic styling, vertical menu with Resume, Quicksave, Load, Settings, Multiplayer/Invite, Unstuck, and Quit.
 * Eliminates third-party skin/wardrobe intrusions from the pause screen while keeping Essential invite fully functional.
 */
public class SkyPauseScreen extends Screen {
    private static final int COLOR_GOLD = 0xFFE8C060;
    private static final int COLOR_HOVER = 0xFFFFFFFF;
    private static final int COLOR_NORMAL = 0xFF9E9689;
    private static final int COLOR_DIM = 0xFF605A50;

    private enum MenuItem {
        RESUME("menu.skycraft.resume", "Resume"),
        QUICKSAVE("menu.skycraft.quicksave", "Save Game"),
        LOAD("menu.skycraft.load", "Save and Choose Character"),
        SETTINGS("menu.skycraft.settings", "Settings"),
        MODS("menu.skycraft.mods", "Mods"),
        MULTIPLAYER("menu.skycraft.multiplayer_options", "World & Multiplayer"),
        UNSTUCK("menu.skycraft.unstuck", "Unstuck / Respawn"),
        QUIT_TITLE("menu.skycraft.quit_title", "Quit to Main Menu"),
        QUIT_DESKTOP("menu.skycraft.quit_desktop", "Quit to Desktop");

        final String key;
        final String fallback;

        MenuItem(String key, String fallback) {
            this.key = key;
            this.fallback = fallback;
        }

        Component getLabel() {
            return Component.translatableWithFallback(key, fallback);
        }
    }

    private final List<MenuItem> menuItems = new ArrayList<>();
    private int selectedIndex = 0;
    private String statusMessage = null;
    private long statusMessageExpire = 0;
    /** A quicksave is running on the integrated server. */
    private static volatile boolean saving;

    public SkyPauseScreen() {
        super(Component.literal("System"));
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    protected void init() {
        menuItems.clear();
        menuItems.add(MenuItem.RESUME);
        // Only the host can save; on a remote server the item is hidden.
        if (Minecraft.getInstance().getSingleplayerServer() != null) menuItems.add(MenuItem.QUICKSAVE);
        // Switching characters needs a world of your own: hidden when joined to somebody else's server.
        if (Minecraft.getInstance().isLocalServer()) menuItems.add(MenuItem.LOAD);
        menuItems.add(MenuItem.SETTINGS);
        menuItems.add(MenuItem.MODS);
        menuItems.add(MenuItem.MULTIPLAYER);
        menuItems.add(MenuItem.UNSTUCK);
        menuItems.add(MenuItem.QUIT_TITLE);
        menuItems.add(MenuItem.QUIT_DESKTOP);

        if (selectedIndex >= menuItems.size()) {
            selectedIndex = 0;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Dark Nordic slate background with drifting smoke
        g.fillGradient(0, 0, width, height, 0xB0080808, 0xD0141210);
        com.skycraft.inventory.client.SkyUi.smoke(g, width, height, (net.minecraft.util.Util.getMillis() % 1_000_000L) / 1000f, 0.6f);
        g.fillGradient(0, 0, Math.min(300, width / 2), height, 0x90000000, 0x20000000);

        int titleX = 50;
        int titleY = titleY();

        // Header
        g.pose().pushMatrix();
        g.pose().translate(titleX, titleY - 4);
        g.pose().scale(1.6f, 1.6f);
        g.text(font, "S Y S T E M", 0, 0, COLOR_GOLD, true);
        g.pose().popMatrix();
        g.text(font, "SKYCRAFT", titleX, titleY + 12, 0xFF8A8272, false);

        // Divider
        int divW = Math.min(200, width / 2 - 40);
        com.skycraft.inventory.client.SkyUi.hline(g, titleX - 6, titleX + divW, titleY + 25, 0xC0);
        com.skycraft.inventory.client.SkyUi.diamond(g, titleX - 6, titleY + 25, 2, COLOR_GOLD);

        // Menu items
        int menuStartY = menuStartY();
        int itemHeight = itemHeight();

        for (int i = 0; i < menuItems.size(); i++) {
            MenuItem item = menuItems.get(i);
            int itemY = menuStartY + i * itemHeight;

            boolean isHovered = mouseX >= titleX - 10 && mouseX <= titleX + 220 && mouseY >= itemY - 4 && mouseY < itemY - 4 + itemHeight;
            if (isHovered && selectedIndex != i) {
                selectedIndex = i;
            }

            boolean isSelected = (selectedIndex == i);
            int textColor = isSelected ? COLOR_HOVER : COLOR_NORMAL;

            if (isSelected) {
                g.fillGradient(titleX - 14, itemY - 2, titleX + 200, itemY + 10, 0x60000000, 0x80000000);
                com.skycraft.inventory.client.SkyUi.hline(g, titleX - 14, titleX + 200, itemY - 2, 0x90);
                com.skycraft.inventory.client.SkyUi.hline(g, titleX - 14, titleX + 200, itemY + 10, 0x90);
                com.skycraft.inventory.client.SkyUi.diamond(g, titleX - 7, itemY + 4, 2, COLOR_GOLD);
            }

            g.text(font, item.getLabel().getString().toUpperCase(java.util.Locale.ROOT), titleX, itemY + 2, textColor, isSelected);
        }

        // Status message if quicksave or unstuck was pressed
        if (statusMessage != null && System.currentTimeMillis() < statusMessageExpire) {
            g.text(font, statusMessage, titleX, menuStartY + menuItems.size() * itemHeight + 14, COLOR_GOLD, true);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** Layout that keeps every item on screen down to the Steam Deck's 427x267 scaled resolution. */
    private int titleY() {
        return net.minecraft.util.Mth.clamp(height / 6, 14, 70);
    }

    private int menuStartY() {
        return titleY() + 38;
    }

    private int itemHeight() {
        int n = Math.max(1, menuItems.size());
        return net.minecraft.util.Mth.clamp((height - menuStartY() - 22) / n, 13, 20);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = com.skycraft.client.screen.UiInput.actionButton(event.button());
        if (button == 0) {
            int titleX = 50;
            int menuStartY = menuStartY();
            int itemHeight = itemHeight();

            for (int i = 0; i < menuItems.size(); i++) {
                int itemY = menuStartY + i * itemHeight;
                if (mx >= titleX - 10 && mx <= titleX + 220 && my >= itemY - 4 && my < itemY - 4 + itemHeight) {
                    selectedIndex = i;
                    activateItem(menuItems.get(i));
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        if (key == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (key == InputConstants.KEY_UP || key == InputConstants.KEY_W) {
            selectedIndex = (selectedIndex - 1 + menuItems.size()) % menuItems.size();
            return true;
        }
        if (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_S) {
            selectedIndex = (selectedIndex + 1) % menuItems.size();
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_SPACE) {
            if (selectedIndex >= 0 && selectedIndex < menuItems.size()) {
                activateItem(menuItems.get(selectedIndex));
                return true;
            }
        }
        return super.keyPressed(event);
    }

    private void activateItem(MenuItem item) {
        Minecraft mc = Minecraft.getInstance();
        switch (item) {
            case RESUME -> onClose();
            case QUICKSAVE -> {
                var server = mc.getSingleplayerServer();
                if (server == null) {
                    // Remote server: saving is the host's business.
                    statusMessage = "Saving is handled by the server host";
                    statusMessageExpire = System.currentTimeMillis() + 3000;
                } else if (!saving) {
                    // Save on the server thread (players, levels and chunks), never on the render thread.
                    saving = true;
                    statusMessage = "Saving...";
                    statusMessageExpire = Long.MAX_VALUE;
                    server.execute(() -> {
                        boolean ok = server.saveEverything(false, true, false);
                        mc.execute(() -> {
                            saving = false;
                            statusMessage = ok ? "Game saved" : "Save failed";
                            statusMessageExpire = System.currentTimeMillis() + 3000;
                        });
                    });
                }
            }
            case LOAD -> mc.gui.setScreen(new ConfirmScreen(confirmed -> {
                if (confirmed) {
                    // save and leave this world first (a world list can't be used from inside a running one), then pick
                    mc.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
                    mc.gui.setScreen(new SkyLoadWorldScreen(new SkyTitleScreen()));
                } else {
                    mc.gui.setScreen(this);
                }
            }, Component.literal("Save and choose another character?"), Component.literal("Your progress is saved first.")));
            case SETTINGS -> mc.gui.setScreen(new net.minecraft.client.gui.screens.options.OptionsScreen(this, mc.options));
            case MODS -> mc.gui.setScreen(net.neoforged.neoforge.client.gui.modlist.ModListScreen.create(this));
            case MULTIPLAYER -> {
                if (mc.level != null) mc.gui.setScreen(new SkyWorldOptionsScreen(this, mc.level));
            }
            case UNSTUCK -> {
                if (ClientState.has(CorePackets.SyncVitals.IN_COMBAT)) {
                    statusMessage = "Cannot use while in combat";
                    statusMessageExpire = System.currentTimeMillis() + 3000;
                    return;
                }
                SkyNetwork.sendToServer(new CorePackets.Action(CorePackets.Action.UNSTUCK, 0));
                onClose();
            }
            case QUIT_TITLE -> {
                boolean single = mc.isLocalServer();
                mc.gui.setScreen(new ConfirmScreen(confirmed -> {
                    if (confirmed) {
                        mc.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
                        mc.gui.setScreen(new SkyTitleScreen());
                    } else {
                        mc.gui.setScreen(this);
                    }
                }, Component.literal("Quit to Main Menu?"), Component.literal("Any unsaved progress will be saved.")));
            }
            case QUIT_DESKTOP -> {
                mc.gui.setScreen(new ConfirmScreen(confirmed -> {
                    if (confirmed) {
                        mc.stop();
                    } else {
                        mc.gui.setScreen(this);
                    }
                }, Component.literal("Quit to Desktop?"), CommonComponents.EMPTY));
            }
        }
    }

}
