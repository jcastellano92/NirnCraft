package com.skycraft.lore.place.client;

import com.skycraft.Skycraft;
import com.skycraft.client.autotest.ClientAutotest;
import com.skycraft.lore.place.PlaceLore;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code -Dskycraft.autotest=true -Dskycraft.autotest.placelore=<place id>} (with {@code -Dskycraft.autotest.world}):
 * instead of {@link ClientAutotest}'s screen walk-through, reads each note of the place through the server
 * ({@code /skylore read}, so the reveal and quest start run too) and screenshots the reading screen, then the journal
 * (the started mini-quest) and the map. Shots: {@code pl_*.png} in {@code mod/run/build/autotest-screenshots}.
 */
public final class PlaceLoreAutotest {
    public static final String PLACE = System.getProperty("skycraft.autotest.placelore", "");
    public static final boolean ENABLED = !PLACE.isEmpty();

    private static final List<Runnable> STEPS = new ArrayList<>();
    private static int tick;
    private static int step;

    private PlaceLoreAutotest() {}

    /** Called by {@link ClientAutotest} every client tick once the world is ready. */
    public static void tick(Minecraft mc) {
        if (STEPS.isEmpty()) plan(mc);
        if (++tick % 40 != 0) return;
        if (step < STEPS.size()) STEPS.get(step++).run();
    }

    private static void plan(Minecraft mc) {
        PlaceLore.Content c = PlaceLore.contentFor(PLACE).orElse(null);
        STEPS.add(() -> {
            Skycraft.LOGGER.info("Place lore autotest: {} ({} lore places loaded)", PLACE, PlaceLore.all().size());
            com.skycraft.network.SkyNetwork.sendToServer(new com.skycraft.network.CorePackets.ChooseRace(com.skycraft.core.Race.NORD.id()));
            mc.gui.setScreen(null);
            if (mc.player != null) {
                mc.player.connection.sendCommand("time set 6000");
                mc.player.connection.sendCommand("skylore stats");
            }
        });
        if (c != null) {
            for (PlaceLore.Note n : c.notes()) {
                STEPS.add(() -> {
                    mc.gui.setScreen(null);
                    if (mc.player != null) mc.player.connection.sendCommand("skylore read " + PLACE + " " + n.id());
                });
                STEPS.add(() -> {
                    Skycraft.LOGGER.info("Place lore autotest: reading {} -> screen {}", n.bookId(),
                            mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
                    ClientAutotest.captureAndSave(mc, "pl_" + PLACE + "_" + n.id());
                });
            }
        } else {
            Skycraft.LOGGER.warn("Place lore autotest: no lore for {}", PLACE);
        }
        STEPS.add(() -> mc.gui.setScreen(new com.skycraft.quest.client.JournalScreen()));
        STEPS.add(() -> ClientAutotest.captureAndSave(mc, "pl_" + PLACE + "_journal"));
        STEPS.add(() -> mc.gui.setScreen(new com.skycraft.world.client.MapScreen()));
        STEPS.add(() -> ClientAutotest.captureAndSave(mc, "pl_" + PLACE + "_map"));
        STEPS.add(() -> {
            Skycraft.LOGGER.info("=== Place lore autotest done ===");
            mc.stop();
        });
    }
}
