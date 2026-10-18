package com.skycraft.client.screen.title;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

/**
 * Skyrim-styled Settings Screen:
 * Atmospheric Nordic dark layout with gold headers and dividers,
 * direct sliders for master/music audio, FOV, brightness, and sensitivity,
 * and quick access to video and control settings.
 */
public class SkyOptionsScreen extends Screen {
    private static final int GOLD_COLOR = 0xFFE8C060;
    private static final int BRIGHT_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFC8BC9A;
    private static final int DIM_COLOR = 0xFF8A8478;

    private final Screen lastScreen;
    private final Options options;

    public SkyOptionsScreen(Screen lastScreen, Options options) {
        super(Component.literal("Settings"));
        this.lastScreen = lastScreen;
        this.options = options;
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int colW = 150;
        int itemH = 20;
        int spacing = 24;

        int leftX = cx - colW - 10;
        int rightX = cx + 10;
        int startY = Math.max(40, height / 5);

        // --- Left Column: Sliders ---

        // 1. Master Volume
        double masterVol = options.getSoundSourceOptionInstance(SoundSource.MASTER).get();
        addRenderableWidget(new AbstractSliderButton(leftX, startY, colW, itemH, Component.empty(), masterVol) {
            { updateMessage(); }
            @Override
            protected void updateMessage() {
                setMessage(Component.literal("Master Volume: " + (int)(value * 100) + "%"));
            }
            @Override
            protected void applyValue() {
                options.getSoundSourceOptionInstance(SoundSource.MASTER).set(value);
            }
        });

        // 2. Music Volume
        double musicVol = options.getSoundSourceOptionInstance(SoundSource.MUSIC).get();
        addRenderableWidget(new AbstractSliderButton(leftX, startY + spacing, colW, itemH, Component.empty(), musicVol) {
            { updateMessage(); }
            @Override
            protected void updateMessage() {
                setMessage(Component.literal("Music Volume: " + (int)(value * 100) + "%"));
            }
            @Override
            protected void applyValue() {
                options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(value);
            }
        });

        // 3. Field of View (30 to 110)
        double fovVal = (options.fov().get() - 30) / 80.0;
        addRenderableWidget(new AbstractSliderButton(leftX, startY + spacing * 2, colW, itemH, Component.empty(), fovVal) {
            { updateMessage(); }
            @Override
            protected void updateMessage() {
                int fov = (int)(30 + value * 80);
                String label = (fov == 70) ? "Normal" : (fov == 110 ? "Quake Pro" : String.valueOf(fov));
                setMessage(Component.literal("FOV: " + label));
            }
            @Override
            protected void applyValue() {
                options.fov().set((int)(30 + value * 80));
            }
        });

        // 4. Brightness (Gamma)
        double gammaVal = options.gamma().get();
        addRenderableWidget(new AbstractSliderButton(leftX, startY + spacing * 3, colW, itemH, Component.empty(), gammaVal) {
            { updateMessage(); }
            @Override
            protected void updateMessage() {
                int pct = (int)(value * 100);
                String label = (pct == 0) ? "Moody" : (pct == 100 ? "Bright" : pct + "%");
                setMessage(Component.literal("Brightness: " + label));
            }
            @Override
            protected void applyValue() {
                options.gamma().set(value);
            }
        });

        // 5. Look Sensitivity
        double sensVal = options.sensitivity().get();
        addRenderableWidget(new AbstractSliderButton(leftX, startY + spacing * 4, colW, itemH, Component.empty(), sensVal) {
            { updateMessage(); }
            @Override
            protected void updateMessage() {
                int pct = (int)(value * 200);
                setMessage(Component.literal("Sensitivity: " + pct + "%"));
            }
            @Override
            protected void applyValue() {
                options.sensitivity().set(value);
            }
        });

        // --- Right Column: Menus & Toggles ---

        // 1. Video Settings
        addRenderableWidget(Button.builder(
                Component.literal("Video Settings..."),
                btn -> minecraft.gui.setScreen(new VideoSettingsScreen(this,minecraft,options))
        ).bounds(rightX, startY, colW, itemH).build());

        // 2. Controls & Key Binds
        addRenderableWidget(Button.builder(
                Component.literal("Controls & Binds..."),
                btn -> minecraft.gui.setScreen(new ControlsScreen(this, options))
        ).bounds(rightX, startY + spacing, colW, itemH).build());

        // 3. Audio & Sounds
        addRenderableWidget(Button.builder(
                Component.literal("Audio Settings..."),
                btn -> minecraft.gui.setScreen(new SoundOptionsScreen(this, options))
        ).bounds(rightX, startY + spacing * 2, colW, itemH).build());

        // 4. Fullscreen Toggle
        CycleButton<Boolean> fsBtn = CycleButton.onOffBuilder(options.fullscreen().get())
                .create(rightX, startY + spacing * 3, colW, itemH, Component.literal("Fullscreen"), (btn, val) -> {
                    options.fullscreen().set(val);
                    Window window = minecraft.getWindow();
                    if (window.isWindowFullscreen() != val) {
                        window.setFullscreen(val);
                        options.fullscreen().set(window.isWindowFullscreen());
                    }
                });
        addRenderableWidget(fsBtn);

        // 5. Language
        addRenderableWidget(Button.builder(
                Component.literal("Language..."),
                btn -> minecraft.gui.setScreen(new LanguageSelectScreen(this, options, minecraft.getLanguageManager()))
        ).bounds(rightX, startY + spacing * 4, colW, itemH).build());

        // --- Bottom: Done Button ---
        int doneW = 140;
        int doneH = 22;
        addRenderableWidget(Button.builder(
                CommonComponents.GUI_DONE,
                btn -> onClose()
        ).bounds(cx - doneW / 2, height - 36, doneW, doneH).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Deep Nordic slate background
        g.fillGradient(0, 0, width, height, 0xFF080808, 0xFF141210);

        int cx = width / 2;
        int startY = Math.max(40, height / 5);

        // Header
        g.centeredText(font, "SETTINGS", cx, startY - 26, GOLD_COLOR);
        g.fill(cx - 100, startY - 14, cx + 100, startY - 13, 0x80E8C060);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        options.save();
        minecraft.gui.setScreen(lastScreen);
    }
}

