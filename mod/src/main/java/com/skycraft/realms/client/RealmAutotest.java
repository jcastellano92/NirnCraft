package com.skycraft.realms.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.skycraft.Skycraft;
import com.skycraft.network.CorePackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Developer harness for the realms: when {@code skycraft-realmtest.txt} exists in the game directory (or
 * {@code SKYCRAFT_REALMTEST=1}, or {@code -Dskycraft.realmtest=true}) it creates a fresh creative world, travels to every
 * realm through {@code /skycraft-realm go}, and photographs each arrival and landmark from fixed vantage points
 * ({@code build/realmtest-screenshots/} under the game directory), then an Oblivion gate and the Door to the Isles in
 * Nirn, and quits. A comma list in the file (or {@code -Dskycraft.realmtest.only=deadlands,sovngarde}) limits the tour.
 * Does nothing in normal play.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class RealmAutotest {
    private static final Path FLAG = Paths.get("skycraft-realmtest.txt");
    private static final boolean ENABLED = Files.exists(FLAG) || "1".equals(System.getenv("SKYCRAFT_REALMTEST"))
            || Boolean.getBoolean("skycraft.realmtest");
    private static final Path OUT = Paths.get("build", "realmtest-screenshots");
    private static final int SETTLE = 340;

    private record Step(int delay, Consumer<Minecraft> action) {}

    private static final List<Step> STEPS = new ArrayList<>();
    private static boolean worldRequested;
    private static int inWorld;
    private static int stepIndex;
    private static int waitLeft;

    private RealmAutotest() {}

    private static String only() {
        String o = System.getProperty("skycraft.realmtest.only", "");
        if (o.isEmpty() && Files.exists(FLAG)) {
            try {
                o = Files.readString(FLAG).trim();
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    private static boolean wants(String realm) {
        String o = only();
        if (o.isEmpty()) return true;
        for (String s : o.split("[,\\s]+")) if (s.equals(realm)) return true;
        return false;
    }

    static {
        step(100, mc -> {
            com.skycraft.network.SkyNetwork.sendToServer(new CorePackets.ChooseRace(com.skycraft.core.Race.NORD.id()));
            mc.setScreen(null);
        });
        cmd(2, "gamerule doDaylightCycle false");
        cmd(2, "gamerule doMobSpawning false");
        cmd(2, "time set 6000");
        cmd(2, "effect give @s minecraft:resistance 99999 255 true");
        cmd(2, "effect give @s minecraft:fire_resistance 99999 0 true");
        // spectators hover where the vantage commands put them (no falling between teleport and screenshot)
        cmd(2, "gamemode spectator");
        if (wants("deadlands")) {
            go("deadlands");
            shotAt("01_deadlands_arrival", null, 180, -12);
            view("02_deadlands_keep", "8 8 60 120 40");
            shotAt("03_deadlands_sigil_chamber", "tp @s 8 170 14", 180, 8);
            view("04_deadlands_overview", "8 8 -90 140 70");
            view("05_deadlands_wilds", "300 300 -60 -60 30");
        }
        if (wants("sovngarde")) {
            go("sovngarde");
            shotAt("10_sovngarde_arrival", null, 180, -4);
            view("11_sovngarde_arena", "0 0 0 70 20");
            view("12_sovngarde_bridge", "0 -126 -50 30 12");
            view("13_sovngarde_hall", "0 -275 40 60 20");
            shotAt("14_sovngarde_hall_inside", "tp @s 0 103 -252", 180, 4);
            view("15_sovngarde_chasm", "300 -125 0 40 10");
        }
        if (wants("shivering_isles")) {
            go("shivering_isles");
            shotAt("20_isles_fringe", null, 90, -4);
            view("21_isles_gates", "670 0 60 25 12");
            view("22_isles_new_sheoth", "0 0 0 190 8");
            view("23_isles_mania", "100 -400 30 40 25");
            view("24_isles_dementia", "100 400 30 40 15");
        }
        if (wants("apocrypha")) {
            go("apocrypha 1");
            shotAt("30_apocrypha_chapter", null, 180, -4);
            view("31_apocrypha_chapter_above", "520 8 0 70 40");
            go("apocrypha 0");
            shotAt("32_apocrypha_summit", null, 180, 0);
            view("33_apocrypha_summit_above", "8 8 0 70 45");
            view("34_apocrypha_sea", "250 250 30 30 20");
        }
        if (wants("soul_cairn")) {
            go("soul_cairn");
            shotAt("40_soul_cairn_arrival", null, 180, -4);
            view("41_soul_cairn_keep", "0 -180 0 80 25");
            view("42_soul_cairn_boneyard", "480 -60 0 60 25");
            view("43_soul_cairn_wastes", "-200 100 30 30 15");
        }
        if (wants("coldharbour")) {
            go("coldharbour");
            shotAt("50_coldharbour_arrival", null, 180, -4);
            view("51_coldharbour_city", "0 -200 0 140 45");
            view("52_coldharbour_wastes", "250 250 30 30 20");
        }
        if (wants("nirn")) {
            cmd(2, "skycraft-realm home");
            step(120, mc -> look(mc, 0, 0));
            view("60_nirn_kvatch_gate", "-2480 8190 22 22 8");
            view("61_nirn_isles_door", "3600 10330 24 24 8");
            view("62_nirn_volkihar_portal", "-3148 -2402 20 20 8");
            view("63_nirn_dark_anchor", "1010 2530 24 24 10");
            view("64_nirn_black_book_altar", "4990 -3000 14 14 5");
        }
        step(20, mc -> {
            Skycraft.LOGGER.info("Realm autotest finished; screenshots in {}", OUT.toAbsolutePath());
            mc.stop();
        });
    }

    private static void step(int wait, Consumer<Minecraft> action) {
        STEPS.add(new Step(wait, action));
    }

    private static void cmd(int wait, String command) {
        step(wait, mc -> {
            if (mc.player != null) mc.player.connection.sendCommand(command);
        });
    }

    private static void go(String realmAndParam) {
        cmd(4, "skycraft-realm go " + realmAndParam);
    }

    /** Stands at a vantage point looking at a target ({@code /skycraft-realm look x z dx dz up}) and saves a screenshot. */
    private static void view(String name, String lookArgs) {
        cmd(4, "skycraft-realm look " + lookArgs);
        step(SETTLE, RealmAutotest::clearScreen);
        step(25, RealmAutotest::clearScreen);
        step(5, mc -> shot(mc, name));
    }

    /** Optionally teleports, waits for the chunks to load and render, faces yaw/pitch, and saves a screenshot. */
    private static void shotAt(String name, String tp, float yaw, float pitch) {
        if (tp != null) cmd(4, tp + " " + yaw + " " + pitch);
        step(SETTLE, mc -> {
            clearScreen(mc);
            look(mc, yaw, pitch);
        });
        step(25, RealmAutotest::clearScreen);
        step(5, mc -> shot(mc, name));
    }

    /** Closes whatever opened meanwhile (the character creator re-opens until the race sticks). */
    private static void clearScreen(Minecraft mc) {
        if (mc.screen != null) {
            com.skycraft.network.SkyNetwork.sendToServer(new CorePackets.ChooseRace(com.skycraft.core.Race.NORD.id()));
            mc.setScreen(null);
        }
    }

    private static void look(Minecraft mc, float yaw, float pitch) {
        if (mc.player == null) return;
        mc.setScreen(null);
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
    }

    private static void shot(Minecraft mc, String name) {
        try {
            Files.createDirectories(OUT);
            NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            File out = OUT.resolve(name + ".png").toFile();
            img.writeToFile(out);
            img.close();
            Skycraft.LOGGER.info("Realm autotest: saved {}", out.getAbsolutePath());
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Realm autotest: screenshot {} failed", name, e);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) mc.setScreen(new TitleScreen());
            if (!worldRequested && (mc.screen instanceof TitleScreen || mc.screen instanceof com.skycraft.client.screen.title.SkyTitleScreen)) {
                worldRequested = true;
                String name = "realmtest-" + System.currentTimeMillis();
                LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                        new GameRules(), WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261011L, true, false),
                        WorldPresets::createNormalWorldDimensions);
            }
            return;
        }
        if (mc.player == null || stepIndex >= STEPS.size()) return;
        if (inWorld++ == 0) waitLeft = STEPS.get(0).delay();
        if (--waitLeft > 0) return;
        Step s = STEPS.get(stepIndex++);
        try {
            s.action().accept(mc);
        } catch (Throwable t) {
            Skycraft.LOGGER.warn("Realm autotest step {} failed", stepIndex - 1, t);
        }
        if (stepIndex < STEPS.size()) waitLeft = STEPS.get(stepIndex).delay();
    }
}
