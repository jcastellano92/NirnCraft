package com.nirncraft.sailing.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.nirncraft.NirnCraft;
import com.nirncraft.network.CorePackets;
import com.nirncraft.sailing.ShipEntity;
import com.nirncraft.sailing.ShipType;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Developer harness for sailing (like {@code visual.client.VisualAutotest}): when {@code nirncraft-sailtest.txt} exists
 * in the game directory (or {@code NIRNCRAFT_SAILTEST=1}), it creates a fresh world, builds the Solitude pier and
 * teleports there, launches each ship, sets the longship's sail, boards it, opens the map and the shipwright's bench,
 * takes the ferry to Raven Rock, and saves screenshots to {@code build/sailtest-screenshots/}, then quits. Does
 * nothing in normal play.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class SailingAutotest {
    private static final boolean ENABLED = Files.exists(Paths.get("nirncraft-sailtest.txt")) || "1".equals(System.getenv("NIRNCRAFT_SAILTEST"));
    private static final Path OUT = Paths.get("build", "sailtest-screenshots");
    private static final String PORT = System.getProperty("nirncraft.sailtest.port", "raven_rock");

    private record Step(int delay, Consumer<Minecraft> action) {}

    private static final List<Step> STEPS = new ArrayList<>();
    private static boolean worldRequested;
    private static int inWorld;
    private static int stepIndex;
    private static int waitLeft;
    private static net.minecraft.world.phys.Vec3 startPos;

    static {
        step(100, mc -> {
            mc.options.pauseOnLostFocus = false;
            com.nirncraft.network.SkyNetwork.sendToServer(new CorePackets.ChooseRace(com.nirncraft.core.Race.NORD.id()));
            mc.gui.setScreen(null);
        });
        cmd(2, "gamerule advance_time false");
        cmd(2, "gamerule spawn_mobs false");
        cmd(2, "gamerule advance_weather false");
        cmd(2, "effect give @s minecraft:resistance 99999 255 true");
        cmd(2, "time set 5000");
        cmd(2, "nirncraft-weather set clear 60");
        cmd(2, "gamemode creative");
        cmd(40, "nirncraft-sailing tp " + PORT);
        step(260, mc -> look(mc, mc.player.getYRot() + 180f, 12f));
        step(40, mc -> shot(mc, "01_pier_from_head"));
        cmd(2, "gamemode spectator");
        cmd(2, "tp @s ~ ~12 ~");
        step(2, mc -> look(mc, mc.player.getYRot(), 50f));
        step(80, mc -> shot(mc, "02_pier_overview"));
        cmd(2, "nirncraft-sailing tp " + PORT);
        cmd(20, "nirncraft-sailing ship longship");
        step(40, mc -> lookAt(mc, ShipType.LONGSHIP));
        cmd(2, "gamemode spectator");
        step(2, mc -> backOff(mc, ShipType.LONGSHIP, 12));
        step(30, mc -> lookAt(mc, ShipType.LONGSHIP));
        step(30, mc -> shot(mc, "10_longship_furled"));
        cmd(2, "data merge entity @e[type=nirncraft:longship,limit=1,sort=nearest] {Sail:2}");
        step(100, mc -> shot(mc, "11_longship_sail"));
        cmd(2, "nirncraft-sailing tp " + PORT);
        cmd(20, "nirncraft-sailing ship cog");
        step(40, mc -> lookAt(mc, ShipType.COG));
        step(2, mc -> backOff(mc, ShipType.COG, 14));
        step(30, mc -> lookAt(mc, ShipType.COG));
        step(40, mc -> shot(mc, "12_cog"));
        cmd(2, "data merge entity @e[type=nirncraft:cog,limit=1,sort=nearest] {Sail:2}");
        step(100, mc -> shot(mc, "13_cog_sail"));
        cmd(2, "nirncraft-sailing tp " + PORT);
        cmd(20, "nirncraft-sailing ship rowboat");
        step(40, mc -> backOff(mc, ShipType.ROWBOAT, 6));
        step(30, mc -> lookAt(mc, ShipType.ROWBOAT));
        step(40, mc -> shot(mc, "14_rowboat"));
        cmd(2, "nirncraft-sailing tp " + PORT);
        cmd(20, "nirncraft-sailing wreck");
        step(80, mc -> shot(mc, "15_wreck_and_flotsam"));
        cmd(2, "tp @s ~ ~-9 ~");
        step(60, mc -> shot(mc, "16_sea_wreck_underwater"));
        cmd(2, "nirncraft-sailing tp " + PORT);
        cmd(2, "gamemode creative");
        cmd(2, "ride @s mount @e[type=nirncraft:longship,limit=1,sort=nearest]");
        step(40, mc -> look(mc, mc.player.getYRot(), 10f));
        step(40, mc -> shot(mc, "20_aboard_longship_hud"));
        step(2, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
        step(40, mc -> shot(mc, "21_aboard_third_person"));
        step(2, mc -> {
            mc.options.keyUp.setDown(true);
            if (mc.player.getVehicle() != null) startPos = mc.player.getVehicle().position();
        });
        step(100, mc -> {
            shot(mc, "22_rowing_underway");
            if (mc.player.getVehicle() instanceof ShipEntity s && startPos != null) {
                NirnCraft.LOGGER.info("Sailing autotest: rowed {} blocks in 5 s (input {}, controller {}, in water {})",
                        String.format(java.util.Locale.ROOT, "%.1f", s.position().distanceTo(startPos)), s.localInput(),
                        s.getControllingPassenger() == mc.player, s.isInWater());
            } else {
                NirnCraft.LOGGER.info("Sailing autotest: not aboard a ship ({})", mc.player.getVehicle());
            }
        });
        step(2, mc -> mc.options.keyUp.setDown(false));
        step(2, mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
        step(2, mc -> mc.gui.setScreen(new com.nirncraft.world.client.MapScreen()));
        step(60, mc -> shot(mc, "30_map_ports"));
        step(2, mc -> mc.gui.setScreen(new ShipwrightScreen(mc.player.blockPosition())));
        step(30, mc -> shot(mc, "31_shipwright"));
        step(2, mc -> mc.gui.setScreen(null));
        cmd(2, "ride @s dismount");
        cmd(20, "nirncraft-sailing voyage windhelm");
        step(50, mc -> shot(mc, "40_voyage"));
        step(400, mc -> look(mc, mc.player.getYRot(), 15f));
        step(40, mc -> shot(mc, "41_arrival_windhelm"));
        step(20, mc -> {
            NirnCraft.LOGGER.info("Sailing autotest finished; screenshots in {}", OUT.toAbsolutePath());
            mc.stop();
        });
    }

    private SailingAutotest() {}

    private static void step(int wait, Consumer<Minecraft> action) {
        STEPS.add(new Step(wait, action));
    }

    private static void cmd(int wait, String command) {
        step(wait, mc -> {
            if (mc.player != null) mc.player.connection.sendCommand(command);
        });
    }

    private static void look(Minecraft mc, float yaw, float pitch) {
        if (mc.player == null) return;
        if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen || mc.gui.screen() instanceof com.nirncraft.client.screen.title.SkyPauseScreen) {
            mc.gui.setScreen(null);
        }
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
    }

    private static ShipEntity nearest(Minecraft mc, ShipType type) {
        ShipEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ShipEntity s && s.shipType == type && !s.isMoored()) {
                double d = s.distanceToSqr(mc.player);
                if (d < bestD) {
                    bestD = d;
                    best = s;
                }
            }
        }
        return best;
    }

    private static void lookAt(Minecraft mc, ShipType type) {
        ShipEntity s = nearest(mc, type);
        if (s == null || mc.player == null) return;
        double dx = s.getX() - mc.player.getX(), dz = s.getZ() - mc.player.getZ();
        double dy = s.getY() + 2 - mc.player.getEyeY();
        look(mc, (float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
    }

    /** Steps back from the ship (and up a little) so all of it fits in the frame. */
    private static void backOff(Minecraft mc, ShipType type, double dist) {
        ShipEntity s = nearest(mc, type);
        if (s == null || mc.player == null) return;
        double dx = mc.player.getX() - s.getX(), dz = mc.player.getZ() - s.getZ();
        double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
        double x = s.getX() + dx / len * dist, z = s.getZ() + dz / len * dist;
        mc.player.connection.sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f", x, s.getY() + 6, z));
    }

    private static void shot(Minecraft mc, String name) {
        com.nirncraft.client.autotest.ReviewCaptures.capture(mc, OUT, name,
                output -> NirnCraft.LOGGER.info("Sailing autotest: saved {}", output.toAbsolutePath()));
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!ENABLED) return;
        if (com.nirncraft.client.autotest.ReviewCaptures.pending() > 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) mc.gui.setScreen(new TitleScreen());
            if (!worldRequested && (mc.gui.screen() instanceof TitleScreen || mc.gui.screen() instanceof com.nirncraft.client.screen.title.SkyTitleScreen)) {
                worldRequested = true;
                String name = "sailtest-" + System.currentTimeMillis();
                LevelSettings settings = com.nirncraft.client.autotest.ReviewWorlds.settings(name,  GameType.CREATIVE,  false,  Difficulty.PEACEFUL,  true,  WorldDataConfiguration.DEFAULT);
                com.nirncraft.client.autotest.ReviewWorlds.create(mc, name,  settings,  new WorldOptions(20261010L, false, false), 
                        com.nirncraft.client.autotest.ReviewWorlds::normal, mc.gui.screen());
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
            NirnCraft.LOGGER.warn("Sailing autotest step {} failed", stepIndex - 1, t);
        }
        if (stepIndex < STEPS.size()) waitLeft = STEPS.get(stepIndex).delay();
    }
}
