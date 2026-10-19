package com.nirncraft.client.screen.title;

import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Authentic Skyrim-themed Main Title Screen:
 * The drifting painted Tamriel landscape under smoke ({@link com.nirncraft.client.screen.SkyBackdrop}),
 * gold Nordic styling, vertical menu with Continue, New Game, Load, Multiplayer, Settings, and Quit.
 */
public class SkyTitleScreen extends Screen {
    private static final int COLOR_GOLD = 0xFFE8C060;
    private static final int COLOR_HOVER = 0xFFFFFFFF;
    private static final int COLOR_NORMAL = 0xFF9E9689;
    private static final int COLOR_DIM = 0xFF605A50;

    private enum MenuItem {
        CONTINUE("menu.nirncraft.continue", "Continue Journey"),
        NEW_GAME("menu.nirncraft.new_game", "New Game"),
        LOAD("menu.nirncraft.load", "Load Game"),
        MULTIPLAYER("menu.nirncraft.multiplayer", "Multiplayer"),
        SETTINGS("menu.nirncraft.settings", "Settings"),
        MODS("menu.nirncraft.mods", "Mods"),
        QUIT("menu.nirncraft.quit", "Quit");

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
    private LevelSummary mostRecentSave = null;
    private boolean savesLoaded = false;
    private float animTick = 0f;
    private static net.minecraft.client.resources.sounds.SimpleSoundInstance titleMusicInstance = null;

    public SkyTitleScreen() {
        super(Component.literal("NirnCraft"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    protected void init() {
        menuItems.clear();
        loadSavesAsync();
        playTitleMusic();
    }

    @Override
    public void removed() {
        super.removed();
    }

    private void playTitleMusic() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MUSIC) <= 0f) return;
        if (titleMusicInstance == null || !mc.getSoundManager().isActive(titleMusicInstance)) {
            titleMusicInstance = net.minecraft.client.resources.sounds.SimpleSoundInstance.forMusic(com.nirncraft.world.WorldSounds.MUSIC_SOVNGARDE.get());
            mc.getSoundManager().play(titleMusicInstance);
        }
    }

    public static void stopTitleMusic() {
        if (titleMusicInstance != null) {
            Minecraft.getInstance().getSoundManager().stop(titleMusicInstance);
            titleMusicInstance = null;
        }
    }

    private void playUiSound(net.minecraft.sounds.SoundEvent sound, float pitch) {
        Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(sound, pitch));
    }

    private void loadSavesAsync() {
        Minecraft mc = Minecraft.getInstance();
        LevelStorageSource source = mc.getLevelSource();
        try {
            LevelStorageSource.LevelCandidates candidates = source.findLevelCandidates();
            // Summaries load on a worker thread; the menu is only ever changed on the render thread.
            source.loadLevelSummaries(candidates).thenAccept(summaries -> {
                LevelSummary newest = null;
                if (summaries != null && !summaries.isEmpty()) {
                    List<LevelSummary> sorted = new ArrayList<>(summaries);
                    sorted.sort((a, b) -> Long.compare(b.getLastPlayed(), a.getLastPlayed()));
                    newest = sorted.get(0);
                }
                LevelSummary found = newest;
                mc.execute(() -> {
                    this.mostRecentSave = found;
                    this.savesLoaded = true;
                    rebuildMenuItems();
                });
            }).exceptionally(ex -> {
                mc.execute(() -> {
                    this.savesLoaded = true;
                    this.mostRecentSave = null;
                    rebuildMenuItems();
                });
                return null;
            });
        } catch (Exception e) {
            this.savesLoaded = true;
            this.mostRecentSave = null;
            rebuildMenuItems();
        }
    }

    /** Render thread only. */
    private void rebuildMenuItems() {
        menuItems.clear();
        if (mostRecentSave != null) {
            menuItems.add(MenuItem.CONTINUE);
        }
        menuItems.add(MenuItem.NEW_GAME);
        if (mostRecentSave != null) {
            menuItems.add(MenuItem.LOAD);
        }
        menuItems.add(MenuItem.MULTIPLAYER);
        menuItems.add(MenuItem.SETTINGS);
        menuItems.add(MenuItem.MODS);
        menuItems.add(MenuItem.QUIT);

        if (selectedIndex < 0 || selectedIndex >= menuItems.size()) {
            selectedIndex = 0;
        }
    }

    @Override
    public void tick() {
        super.tick();
        animTick += 0.5f;
    }

    /**
     * Like vanilla's title screen: no panorama, blur or menu tint underneath. The NirnCraft scene is drawn sharp in
     * {@link #extractRenderState} instead (this also spares the cube map and the full-screen blur pass every frame).
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        minecraft.gui.hud.extractDeferredSubtitles();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // The drifting NirnCraft landscape (shared with every other out-of-world menu, see SkyBackdrop)
        com.nirncraft.client.screen.SkyBackdrop.drawScene(g, width, height);
        // Nordic slate gradient behind left menu column for sharp text contrast
        g.fillGradient(0, 0, Math.min(340, width / 2 + 60), height, 0xD0080808, 0x15080808);
        // Drifting smoke across the scene, darkened foot
        com.nirncraft.client.screen.SkyBackdrop.drawAtmosphere(g, width, height, 0.8f);

        // Title: large spaced capitals over an ornament
        int titleX = 40;
        int titleY = titleY();
        g.pose().pushMatrix();
        g.pose().translate(titleX, titleY - 6);
        g.pose().scale(2.0f, 2.0f);
        g.text(font, "S K Y C R A F T", 0, 0, COLOR_GOLD, true);
        g.pose().popMatrix();
        g.text(font, "THE ELDER SCROLLS IN MINECRAFT", titleX, titleY + 14, 0xFF8A8272, false);

        // Decorative ornament divider
        int divW = Math.min(180, width / 2 - 30);
        com.nirncraft.inventory.client.SkyUi.hline(g, titleX - 6, titleX + divW, titleY + 27, 0xC0);
        com.nirncraft.inventory.client.SkyUi.diamond(g, titleX - 6, titleY + 27, 2, COLOR_GOLD);

        // Menu items layout
        int menuStartY = menuStartY();
        if (menuItems.isEmpty()) {
            rebuildMenuItems();
        }
        int itemHeight = itemHeight();

        for (int i = 0; i < menuItems.size(); i++) {
            MenuItem item = menuItems.get(i);
            int itemY = menuStartY + i * itemHeight;

            boolean isHovered = mouseX >= titleX - 10 && mouseX <= titleX + 180 && mouseY >= itemY - 4 && mouseY < itemY - 4 + itemHeight;
            if (isHovered && selectedIndex != i) {
                selectedIndex = i;
                playUiSound(com.nirncraft.world.WorldSounds.UI_MENU_CLICK.get(), 1.0f);
            }

            boolean isSelected = (selectedIndex == i);
            int textColor = isSelected ? COLOR_HOVER : COLOR_NORMAL;

            if (isSelected) {
                // Skyrim selection: a smoky band between fading gold lines, with a pulsing diamond
                int pulseAlpha = (int) (200 + 55 * Math.sin(animTick * 0.2f));
                int pulseColor = (pulseAlpha << 24) | (COLOR_GOLD & 0x00FFFFFF);
                g.fillGradient(titleX - 14, itemY - 2, titleX + 170, itemY + 10, 0x60000000, 0x80000000);
                com.nirncraft.inventory.client.SkyUi.hline(g, titleX - 14, titleX + 170, itemY - 2, 0x90);
                com.nirncraft.inventory.client.SkyUi.hline(g, titleX - 14, titleX + 170, itemY + 10, 0x90);
                com.nirncraft.inventory.client.SkyUi.diamond(g, titleX - 7, itemY + 4, 2, pulseColor);
            }

            Component label = item.getLabel();
            if (item == MenuItem.CONTINUE && mostRecentSave != null) {
                String saveName = mostRecentSave.getLevelName();
                if (saveName.length() > 18) saveName = saveName.substring(0, 16) + "...";
                g.text(font, "CONTINUE", titleX, itemY + 1, textColor, isSelected);
                g.text(font, "(" + saveName + ")", titleX + font.width("CONTINUE") + 6, itemY + 2, COLOR_DIM, false);
            } else {
                g.text(font, label.getString().toUpperCase(java.util.Locale.ROOT), titleX, itemY + 1, textColor, isSelected);
            }
        }

        // Bottom version & lore footer
        String footer = "NirnCraft v0.1.0 • Skyrim Adventure System";
        g.text(font, footer, width - font.width(footer) - 8, height - 12, COLOR_DIM, false);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** Layout that fits the whole menu down to the Steam Deck's 427x267 scaled screen. */
    private int titleY() {
        return Mth.clamp(height / 5, 16, 80);
    }

    private int menuStartY() {
        return titleY() + 40;
    }

    private int itemHeight() {
        int n = Math.max(1, menuItems.size());
        return Mth.clamp((height - menuStartY() - 16) / n, 13, 22);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = com.nirncraft.client.screen.UiInput.actionButton(event.button());
        if (button == 0) {
            int titleX = 40;
            int menuStartY = menuStartY();
            int itemHeight = itemHeight();

            for (int i = 0; i < menuItems.size(); i++) {
                int itemY = menuStartY + i * itemHeight;
                if (mx >= titleX - 10 && mx <= titleX + 180 && my >= itemY - 4 && my < itemY - 4 + itemHeight) {
                    selectedIndex = i;
                    playUiSound(com.nirncraft.world.WorldSounds.UI_MENU_OPEN.get(), 1.0f);
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
        if (key == InputConstants.KEY_UP || key == InputConstants.KEY_W) {
            if (!menuItems.isEmpty()) {
                selectedIndex = Math.floorMod(selectedIndex - 1, menuItems.size());
                playUiSound(com.nirncraft.world.WorldSounds.UI_MENU_CLICK.get(), 1.0f);
                return true;
            }
        }
        if (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_S) {
            if (!menuItems.isEmpty()) {
                selectedIndex = Math.floorMod(selectedIndex + 1, menuItems.size());
                playUiSound(com.nirncraft.world.WorldSounds.UI_MENU_CLICK.get(), 1.0f);
                return true;
            }
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_SPACE) {
            if (selectedIndex >= 0 && selectedIndex < menuItems.size()) {
                playUiSound(com.nirncraft.world.WorldSounds.UI_MENU_OPEN.get(), 1.0f);
                activateItem(menuItems.get(selectedIndex));
                return true;
            }
        }
        if (key == InputConstants.KEY_ESCAPE) {
            playUiSound(com.nirncraft.world.WorldSounds.UI_MENU_CLOSE.get(), 1.0f);
            confirmQuit();
            return true;
        }
        return super.keyPressed(event);
    }

    private void activateItem(MenuItem item) {
        Minecraft mc = Minecraft.getInstance();
        switch (item) {
            case CONTINUE -> {
                if (mostRecentSave != null) {
                    stopTitleMusic();
                    mc.createWorldOpenFlows().openWorld(mostRecentSave.getLevelId(), () -> mc.gui.setScreen(this));
                }
            }
            case NEW_GAME -> {
                stopTitleMusic();
                CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(this));
            }
            case LOAD -> {
                mc.gui.setScreen(new SkyLoadWorldScreen(this));
            }
            case MULTIPLAYER -> {
                stopTitleMusic();
                mc.gui.setScreen(new SkyMultiplayerScreen(this));
            }
            case SETTINGS -> {
                mc.gui.setScreen(new OptionsScreen(this, mc.options));
            }
            case MODS -> {
                mc.gui.setScreen(net.neoforged.neoforge.client.gui.modlist.ModListScreen.create(this));
            }
            case QUIT -> {
                confirmQuit();
            }
        }
    }

    private void confirmQuit() {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(new ConfirmScreen(
                confirm -> {
                    if (confirm) {
                        mc.stop();
                    } else {
                        mc.gui.setScreen(this);
                    }
                },
                Component.translatableWithFallback("menu.nirncraft.quit_confirm_title", "Quit NirnCraft?"),
                Component.translatableWithFallback("menu.nirncraft.quit_confirm_msg", "Return to desktop?"),
                CommonComponents.GUI_PROCEED,
                CommonComponents.GUI_CANCEL
        ));
    }
}

