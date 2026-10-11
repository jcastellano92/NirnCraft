package com.skycraft.guide.client;

import com.skycraft.Skycraft;
import com.skycraft.client.autotest.ClientAutotest;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code -Dskycraft.autotest=true -Dskycraft.autotest.guide=true} (with {@code -Dskycraft.autotest.world=<name>}):
 * instead of {@link ClientAutotest}'s screen walk-through, size the window to a Steam Deck (1280x800 at GUI scale 3 =
 * 427x267) and screenshot the Traveler's Handbook (contents and several chapters), two tutorial hints, and the
 * handbook in a 320x200 GUI (where the book scales down). Also logs whether the first-join gift arrived.
 * Shots: {@code g_*.png} in {@code mod/run/build/autotest-screenshots}.
 */
public final class GuideAutotest {
    public static final boolean ENABLED = Boolean.getBoolean("skycraft.autotest.guide");

    private static final List<Runnable> STEPS = new ArrayList<>();
    private static int tick;
    private static int step;

    private GuideAutotest() {}

    /** Called by {@link ClientAutotest} every client tick once the world is ready. */
    public static void tick(Minecraft mc) {
        if (STEPS.isEmpty()) plan(mc);
        if (++tick % 40 != 0) return;
        if (step < STEPS.size()) STEPS.get(step++).run();
    }

    private static void plan(Minecraft mc) {
        STEPS.add(() -> {
            GLFW.glfwRestoreWindow(mc.getWindow().getWindow());
            GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 1280, 800);
            mc.options.guiScale().set(3);
            com.skycraft.network.SkyNetwork.sendToServer(new com.skycraft.network.CorePackets.ChooseRace(com.skycraft.core.Race.NORD.id()));
            mc.setScreen(null);
            if (mc.player != null) {
                mc.player.connection.sendCommand("time set 6000");
                mc.player.connection.sendCommand("weather clear");
            }
            TutorialHints.setEnabled(false); // only the hints this test forces
        });
        STEPS.add(() -> {
            mc.resizeDisplay();
            boolean gift = mc.player != null && mc.player.getInventory().contains(
                    new net.minecraft.world.item.ItemStack(com.skycraft.guide.GuideModule.HANDBOOK.get()));
            Skycraft.LOGGER.info("Guide autotest: first-join handbook in inventory: {}", gift);
        });
        for (String chapter : new String[]{"contents", "getting_started", "controls", "races", "magic", "world", "multiplayer"}) {
            STEPS.add(() -> {
                HandbookScreen.preselect(chapter);
                mc.setScreen(new HandbookScreen());
            });
            STEPS.add(() -> ClientAutotest.captureAndSave(mc, "g_handbook_" + chapter));
        }
        STEPS.add(() -> {
            mc.setScreen(null);
            TutorialHints.force("sneak");
        });
        STEPS.add(() -> {});
        STEPS.add(() -> ClientAutotest.captureAndSave(mc, "g_hint_sneak"));
        STEPS.add(() -> TutorialHints.force("word_wall"));
        STEPS.add(() -> {});
        STEPS.add(() -> ClientAutotest.captureAndSave(mc, "g_hint_word_wall"));
        // a smaller GUI than the Deck's (960x600 at scale 3 = 320x200): the book scales down to fit
        STEPS.add(() -> GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 960, 600));
        STEPS.add(() -> {
            mc.resizeDisplay();
            HandbookScreen.preselect("skills");
            mc.setScreen(new HandbookScreen());
        });
        STEPS.add(() -> ClientAutotest.captureAndSave(mc, "g_handbook_small"));
        STEPS.add(() -> {
            mc.setScreen(null);
            TutorialHints.setEnabled(true);
            Skycraft.LOGGER.info("=== Skycraft Autotest: Completed successfully! Closing client. ===");
            mc.stop();
        });
    }
}
