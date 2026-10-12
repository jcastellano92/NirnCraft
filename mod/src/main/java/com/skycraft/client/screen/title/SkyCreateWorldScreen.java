package com.skycraft.client.screen.title;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;

/**
 * Skyrim-styled New Game Screen:
 * Completely replaces the vanilla Create World screen.
 * Strips away Creative mode, cheats, bonus chests, data pack tabs, and technical clutter.
 * Players simply choose a name and Skyrim difficulty, then embark on their adventure.
 */
public class SkyCreateWorldScreen extends Screen {
    private static final int GOLD_COLOR = 0xFFE8C060;
    private static final int BRIGHT_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFC8BC9A;
    private static final int DIM_COLOR = 0xFF8A8478;

    private final CreateWorldScreen underlying;
    private EditBox nameBox;
    private EditBox seedBox;
    private Difficulty selectedDifficulty = Difficulty.NORMAL;

    public SkyCreateWorldScreen(CreateWorldScreen underlying) {
        super(Component.literal("New Journey"));
        this.underlying = underlying;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    protected void init() {
        if (this.minecraft != null) {
            try {
                java.lang.reflect.Field mcField = Screen.class.getDeclaredField("minecraft");
                mcField.setAccessible(true);
                mcField.set(underlying, this.minecraft);
            } catch (Exception ignored) {}
            underlying.init(this.minecraft, this.width, this.height);
        }

        int cx = width / 2;
        int startY = Math.max(45, height / 5);

        // Pre-configure underlying UI state: force survival, no cheats, no bonus chest
        WorldCreationUiState state = underlying.getUiState();
        state.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
        state.setAllowCheats(false);
        state.setBonusChest(false);
        state.setGenerateStructures(true);
        state.setDifficulty(selectedDifficulty);

        // World / Journey Name EditBox
        int boxW = 200;
        int boxH = 20;
        nameBox = new EditBox(font, cx - boxW / 2, startY + 22, boxW, boxH, Component.literal("World Name"));
        nameBox.setValue(state.getName().isEmpty() ? "Skyrim" : state.getName());
        nameBox.setHint(Component.literal("Dragonborn's Journey"));
        addRenderableWidget(nameBox);

        // Difficulty Cycle Button (Novice, Apprentice, Adept, Expert)
        int diffY = startY + 62;
        CycleButton<Difficulty> diffButton = CycleButton.<Difficulty>builder(diff -> {
            return switch (diff) {
                case PEACEFUL -> Component.literal("Novice (Peaceful)");
                case EASY -> Component.literal("Apprentice (Easy)");
                case NORMAL -> Component.literal("Adept (Normal)");
                case HARD -> Component.literal("Expert (Challenging)");
            };
        }).withValues(Difficulty.PEACEFUL, Difficulty.EASY, Difficulty.NORMAL, Difficulty.HARD)
          .withInitialValue(selectedDifficulty)
          .create(cx - boxW / 2, diffY, boxW, boxH, Component.literal("Difficulty"), (btn, val) -> {
              this.selectedDifficulty = val;
              state.setDifficulty(val);
          });
        addRenderableWidget(diffButton);

        // Seed EditBox (Optional)
        int seedY = startY + 104;
        seedBox = new EditBox(font, cx - boxW / 2, seedY, boxW, boxH, Component.literal("World Seed"));
        seedBox.setHint(Component.literal("Seed (blank = " + com.skycraft.bake.WorldSeed.NAME + ")"));
        seedBox.setValue(com.skycraft.bake.WorldSeed.NAME);
        addRenderableWidget(seedBox);

        // Bottom Action Buttons: [BEGIN JOURNEY] and [CANCEL]
        int btnW = 120;
        int btnH = 22;
        int btnY = height - 44;

        addRenderableWidget(Button.builder(
                Component.literal("BEGIN JOURNEY"),
                btn -> startJourney()
        ).bounds(cx - btnW - 8, btnY, btnW, btnH).build());

        addRenderableWidget(Button.builder(
                CommonComponents.GUI_CANCEL,
                btn -> onClose()
        ).bounds(cx + 8, btnY, btnW, btnH).build());

        setInitialFocus(nameBox);
    }

    private void startJourney() {
        WorldCreationUiState state = underlying.getUiState();
        String worldName = nameBox.getValue().trim();
        if (worldName.isEmpty()) worldName = "Skyrim";
        state.setName(worldName);

        String seed = seedBox.getValue().trim();
        // blank means the fixed default world (Nirn); any other seed still works, it just skips the baked data
        state.setSeed(seed.isEmpty() ? com.skycraft.bake.WorldSeed.NAME : seed);

        state.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
        state.setAllowCheats(false);
        state.setBonusChest(false);
        state.setGenerateStructures(true);
        state.setDifficulty(selectedDifficulty);

        // Ensure underlying has valid minecraft reference before onCreate
        if (this.minecraft != null) {
            for (Class<?> cl = underlying.getClass(); cl != null && cl != Object.class; cl = cl.getSuperclass()) {
                for (java.lang.reflect.Field f : cl.getDeclaredFields()) {
                    if (f.getType() == net.minecraft.client.Minecraft.class) {
                        try {
                            f.setAccessible(true);
                            f.set(underlying, this.minecraft);
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }

        // Invoke onCreate on underlying CreateWorldScreen
        try {
            Method onCreate = null;
            for (String name : new String[]{"onCreate", "m_100972_", "C"}) {
                try {
                    onCreate = CreateWorldScreen.class.getDeclaredMethod(name);
                    if (onCreate != null) break;
                } catch (Throwable ignored) {}
            }
            if (onCreate == null) {
                try {
                    onCreate = net.minecraftforge.fml.util.ObfuscationReflectionHelper.findMethod(CreateWorldScreen.class, "m_100972_");
                } catch (Throwable ignored) {}
            }
            if (onCreate != null) {
                onCreate.setAccessible(true);
                onCreate.invoke(underlying);
                return;
            }

            // Fallback: trigger Create button on underlying screen
            for (net.minecraft.client.gui.components.events.GuiEventListener child : underlying.children()) {
                if (child instanceof Button b && b.getMessage().getString().toLowerCase(java.util.Locale.ROOT).contains("create")) {
                    b.onPress();
                    return;
                }
            }
            underlying.onClose();
        } catch (Throwable ex) {
            ex.printStackTrace();
            if (this.minecraft != null) {
                this.minecraft.setScreen(new SkyTitleScreen());
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Deep Nordic slate background
        g.fillGradient(0, 0, width, height, 0xFF080808, 0xFF141210);

        int cx = width / 2;
        int startY = Math.max(45, height / 5);

        // Header
        g.drawCenteredString(font, "NEW JOURNEY", cx, startY - 28, GOLD_COLOR);
        g.drawCenteredString(font, "Begin an adventure in the lands of Skyrim", cx, startY - 16, DIM_COLOR);

        // Decorative horizontal gold line
        int divW = 140;
        g.fill(cx - divW, startY - 6, cx + divW, startY - 5, 0x80E8C060);

        // Labels
        int boxW = 200;
        g.drawString(font, "ADVENTURE NAME", cx - boxW / 2, startY + 10, TEXT_COLOR, false);
        g.drawString(font, "DIFFICULTY", cx - boxW / 2, startY + 50, TEXT_COLOR, false);
        g.drawString(font, "WORLD SEED (DEFAULT: NIRN)", cx - boxW / 2, startY + 92, TEXT_COLOR, false);

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER) {
            startJourney();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new SkyTitleScreen());
        }
    }
}

