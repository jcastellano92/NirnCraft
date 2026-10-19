package com.nirncraft.lore.place.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.client.autotest.ClientAutotest;
import com.nirncraft.lore.place.PlaceLore;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code -Dnirncraft.autotest=true -Dnirncraft.autotest.placelore=<place id>} (with {@code -Dnirncraft.autotest.world}):
 * instead of {@link ClientAutotest}'s screen walk-through, reads each note of the place through the server
 * ({@code /skylore read}, so the reveal and quest start run too) and screenshots the reading screen, then the journal
 * (the started mini-quest) and the map. Shots: {@code pl_*.png} in {@code mod/run/build/autotest-screenshots}.
 */
public final class PlaceLoreAutotest {
    public static final String PLACE = System.getProperty("nirncraft.autotest.placelore", "");
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
            NirnCraft.LOGGER.info("Place lore autotest: {} ({} lore places loaded)", PLACE, PlaceLore.all().size());
            com.nirncraft.network.SkyNetwork.sendToServer(new com.nirncraft.network.CorePackets.ChooseRace(com.nirncraft.core.Race.NORD.id()));
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
                    NirnCraft.LOGGER.info("Place lore autotest: reading {} -> screen {}", n.bookId(),
                            mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
                    ClientAutotest.captureAndSave(mc, "pl_" + PLACE + "_" + n.id());
                });
            }
        } else {
            NirnCraft.LOGGER.warn("Place lore autotest: no lore for {}", PLACE);
        }
        STEPS.add(() -> mc.gui.setScreen(new com.nirncraft.quest.client.JournalScreen()));
        STEPS.add(() -> ClientAutotest.captureAndSave(mc, "pl_" + PLACE + "_journal"));
        STEPS.add(() -> mc.gui.setScreen(new com.nirncraft.world.client.MapScreen()));
        STEPS.add(() -> ClientAutotest.captureAndSave(mc, "pl_" + PLACE + "_map"));
        STEPS.add(() -> {
            NirnCraft.LOGGER.info("=== Place lore autotest done ===");
            mc.stop();
        });
    }
}
