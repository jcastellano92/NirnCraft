package com.skycraft.client.screen.title;

import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Authentic Skyrim-themed Main Title Screen:
 * Atmospheric revolving 3D world panorama background showing off the Skycraft world,
 * gold Nordic styling, vertical menu with Continue, New Game, Load, Multiplayer, Settings, and Quit.
 */
public class SkyTitleScreen extends Screen {
    private static final int COLOR_GOLD = 0xFFE8C060;
    private static final int COLOR_HOVER = 0xFFFFFFFF;
    private static final int COLOR_NORMAL = 0xFF9E9689;
    private static final int COLOR_DIM = 0xFF605A50;

    private static final ResourceLocation TITLE_BACKGROUND = new ResourceLocation("skycraft", "textures/gui/title_background.png");

    private final PanoramaRenderer panorama = new PanoramaRenderer(TitleScreen.CUBE_MAP);

    private enum MenuItem {
        CONTINUE("menu.skycraft.continue", "Continue Journey"),
        NEW_GAME("menu.skycraft.new_game", "New Game"),
        LOAD("menu.skycraft.load", "Load Game"),
        MULTIPLAYER("menu.skycraft.multiplayer", "Multiplayer"),
        FRIENDS("menu.skycraft.friends", "Friends & Host (Essential)"),
        SETTINGS("menu.skycraft.settings", "Settings"),
        MODS("menu.skycraft.mods", "Mods"),
        QUIT("menu.skycraft.quit", "Quit");

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
        super(Component.literal("Skycraft"));
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
            titleMusicInstance = net.minecraft.client.resources.sounds.SimpleSoundInstance.forMusic(com.skycraft.world.WorldSounds.MUSIC_SOVNGARDE.get());
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
        menuItems.add(MenuItem.FRIENDS);
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

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Draw the cinematic Skycraft Skyrim landscape backdrop
        g.blit(TITLE_BACKGROUND, 0, 0, 0, 0, width, height, width, height);

        // Subtle overall atmosphere vignette
        g.fillGradient(0, 0, width, height, 0x10000000, 0x40000000);
        // Nordic slate gradient behind left menu column for sharp text contrast
        g.fillGradient(0, 0, Math.min(340, width / 2 + 60), height, 0xD0080808, 0x15080808);

        // Drifting ethereal fog layers across screen
        float time = animTick * 0.4f;
        for (int layer = 0; layer < 4; layer++) {
            float speed = (layer + 1) * 0.4f;
            float wave = (float) Math.sin((time * speed * 0.05f) + layer * 1.5f);
            int baseMistY = height - 60 - layer * 18 + (int) (wave * 6);
            int alpha = 10 + layer * 6;
            int mistColor = (alpha << 24) | 0x1E1A16;
            g.fillGradient(0, baseMistY, width, height, mistColor, 0x001E1A16);
        }

        // Title and Dragon Sigil styling
        int titleX = 40;
        int titleY = height / 5;

        // Skyrim Gold Title
        g.drawString(font, "S K Y C R A F T", titleX, titleY, COLOR_GOLD, true);
        g.drawString(font, "THE ELDER SCROLLS IN MINECRAFT", titleX, titleY + 12, 0xFF8A8272, false);

        // Decorative horizontal divider
        int divW = 160;
        g.fill(titleX, titleY + 25, titleX + divW, titleY + 26, COLOR_GOLD);
        g.fill(titleX + divW, titleY + 25, titleX + divW + 20, titleY + 26, 0x40E8C060);

        // Menu items layout
        int menuStartY = titleY + 42;
        int itemHeight = 22;

        if (menuItems.isEmpty()) {
            rebuildMenuItems();
        }

        for (int i = 0; i < menuItems.size(); i++) {
            MenuItem item = menuItems.get(i);
            int itemY = menuStartY + i * itemHeight;

            boolean isHovered = mouseX >= titleX - 10 && mouseX <= titleX + 180 && mouseY >= itemY && mouseY < itemY + itemHeight;
            if (isHovered && selectedIndex != i) {
                selectedIndex = i;
                playUiSound(com.skycraft.world.WorldSounds.UI_MENU_CLICK.get(), 1.0f);
            }

            boolean isSelected = (selectedIndex == i);
            int textColor = isSelected ? COLOR_HOVER : COLOR_NORMAL;

            if (isSelected) {
                // Skyrim diamond / pip selection indicator with pulsing glow
                int pulseAlpha = (int) (200 + 55 * Math.sin(animTick * 0.2f));
                int pulseColor = (pulseAlpha << 24) | (COLOR_GOLD & 0x00FFFFFF);
                g.drawString(font, "▶", titleX - 12, itemY + 2, pulseColor, false);
                g.fill(titleX - 2, itemY + itemHeight - 3, titleX + 130, itemY + itemHeight - 2, 0x50E8C060);
            }

            Component label = item.getLabel();
            if (item == MenuItem.CONTINUE && mostRecentSave != null) {
                String saveName = mostRecentSave.getLevelName();
                if (saveName.length() > 18) saveName = saveName.substring(0, 16) + "...";
                g.drawString(font, "CONTINUE", titleX, itemY + 1, textColor, isSelected);
                g.drawString(font, "(" + saveName + ")", titleX + font.width("CONTINUE") + 6, itemY + 2, COLOR_DIM, false);
            } else {
                g.drawString(font, label.getString().toUpperCase(java.util.Locale.ROOT), titleX, itemY + 1, textColor, isSelected);
            }
        }

        // Bottom version & lore footer
        g.drawString(font, "Skycraft v0.1.0 • Skyrim Adventure System", width - 210, height - 16, COLOR_DIM, false);

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            int titleX = 40;
            int menuStartY = height / 5 + 42;
            int itemHeight = 22;

            for (int i = 0; i < menuItems.size(); i++) {
                int itemY = menuStartY + i * itemHeight;
                if (mx >= titleX - 10 && mx <= titleX + 180 && my >= itemY && my < itemY + itemHeight) {
                    selectedIndex = i;
                    playUiSound(com.skycraft.world.WorldSounds.UI_MENU_OPEN.get(), 1.0f);
                    activateItem(menuItems.get(i));
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_W) {
            if (!menuItems.isEmpty()) {
                selectedIndex = Math.floorMod(selectedIndex - 1, menuItems.size());
                playUiSound(com.skycraft.world.WorldSounds.UI_MENU_CLICK.get(), 1.0f);
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_S) {
            if (!menuItems.isEmpty()) {
                selectedIndex = Math.floorMod(selectedIndex + 1, menuItems.size());
                playUiSound(com.skycraft.world.WorldSounds.UI_MENU_CLICK.get(), 1.0f);
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_SPACE) {
            if (selectedIndex >= 0 && selectedIndex < menuItems.size()) {
                playUiSound(com.skycraft.world.WorldSounds.UI_MENU_OPEN.get(), 1.0f);
                activateItem(menuItems.get(selectedIndex));
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            playUiSound(com.skycraft.world.WorldSounds.UI_MENU_CLOSE.get(), 1.0f);
            confirmQuit();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    private void activateItem(MenuItem item) {
        Minecraft mc = Minecraft.getInstance();
        switch (item) {
            case CONTINUE -> {
                if (mostRecentSave != null) {
                    stopTitleMusic();
                    mc.createWorldOpenFlows().loadLevel(this, mostRecentSave.getLevelId());
                }
            }
            case NEW_GAME -> {
                stopTitleMusic();
                CreateWorldScreen.openFresh(mc, this);
            }
            case LOAD -> {
                mc.setScreen(new SkyLoadWorldScreen(this));
            }
            case MULTIPLAYER -> {
                stopTitleMusic();
                mc.setScreen(new JoinMultiplayerScreen(this));
            }
            case FRIENDS -> {
                try {
                    // Method 1: Essential's official CommandMcFriends handler
                    Class<?> cmdClass = Class.forName("gg.essential.commands.impl.CommandMcFriends");
                    Object cmd = cmdClass.getConstructor().newInstance();
                    cmdClass.getMethod("handle").invoke(cmd);
                    return;
                } catch (Throwable t1) {
                    try {
                        // Method 2: Instantiate SocialMenu directly and open via GuiUtil
                        Class<?> socialMenu = Class.forName("gg.essential.gui.friends.SocialMenu");
                        Object inst = socialMenu.getConstructor().newInstance();
                        if (inst instanceof Screen s) {
                            try {
                                Class<?> guiUtil = Class.forName("gg.essential.util.GuiUtil");
                                java.lang.reflect.Field f = guiUtil.getField("INSTANCE");
                                Object guiUtilInst = f.get(null);
                                java.lang.reflect.Method openM = guiUtil.getMethod("openScreen", Screen.class);
                                openM.invoke(guiUtilInst, s);
                            } catch (Throwable ignored) {
                                mc.setScreen(s);
                            }
                            return;
                        }
                    } catch (Throwable t2) {
                        mc.setScreen(new JoinMultiplayerScreen(this));
                    }
                }
            }
            case SETTINGS -> {
                mc.setScreen(new OptionsScreen(this, mc.options));
            }
            case MODS -> {
                mc.setScreen(new net.minecraftforge.client.gui.ModListScreen(this));
            }
            case QUIT -> {
                confirmQuit();
            }
        }
    }

    private void confirmQuit() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new ConfirmScreen(
                confirm -> {
                    if (confirm) {
                        mc.stop();
                    } else {
                        mc.setScreen(this);
                    }
                },
                Component.translatableWithFallback("menu.skycraft.quit_confirm_title", "Quit Skycraft?"),
                Component.translatableWithFallback("menu.skycraft.quit_confirm_msg", "Return to desktop?"),
                CommonComponents.GUI_PROCEED,
                CommonComponents.GUI_CANCEL
        ));
    }
}

