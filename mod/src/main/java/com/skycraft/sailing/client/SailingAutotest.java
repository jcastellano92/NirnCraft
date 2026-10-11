package com.skycraft.sailing.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.skycraft.Skycraft;
import com.skycraft.network.CorePackets;
import com.skycraft.sailing.ShipEntity;
import com.skycraft.sailing.ShipType;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
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
 * Developer harness for sailing (like {@code visual.client.VisualAutotest}): when {@code skycraft-sailtest.txt} exists
 * in the game directory (or {@code SKYCRAFT_SAILTEST=1}), it creates a fresh world, builds the Solitude pier and
 * teleports there, launches each ship, sets the longship's sail, boards it, opens the map and the shipwright's bench,
 * takes the ferry to Raven Rock, and saves screenshots to {@code build/sailtest-screenshots/}, then quits. Does
 * nothing in normal play.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class SailingAutotest {
    private static final boolean ENABLED = Files.exists(Paths.get("skycraft-sailtest.txt")) || "1".equals(System.getenv("SKYCRAFT_SAILTEST"));
    private static final Path OUT = Paths.get("build", "sailtest-screenshots");
    private static final String PORT = System.getProperty("skycraft.sailtest.port", "raven_rock");

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
            com.skycraft.network.SkyNetwork.sendToServer(new CorePackets.ChooseRace(com.skycraft.core.Race.NORD.id()));
            mc.setScreen(null);
        });
        cmd(2, "gamerule doDaylightCycle false");
        cmd(2, "gamerule doMobSpawning false");
        cmd(2, "gamerule doWeatherCycle false");
        cmd(2, "effect give @s minecraft:resistance 99999 255 true");
        cmd(2, "time set 5000");
        cmd(2, "skycraft-weather set clear 60");
        cmd(2, "gamemode creative");
        cmd(40, "skycraft-sailing tp " + PORT);
        step(260, mc -> look(mc, mc.player.getYRot() + 180f, 12f));
        step(40, mc -> shot(mc, "01_pier_from_head"));
        cmd(2, "gamemode spectator");
        cmd(2, "tp @s ~ ~12 ~");
        step(2, mc -> look(mc, mc.player.getYRot(), 50f));
        step(80, mc -> shot(mc, "02_pier_overview"));
        cmd(2, "skycraft-sailing tp " + PORT);
        cmd(20, "skycraft-sailing ship longship");
        step(40, mc -> lookAt(mc, ShipType.LONGSHIP));
        cmd(2, "gamemode spectator");
        step(2, mc -> backOff(mc, ShipType.LONGSHIP, 12));
        step(30, mc -> lookAt(mc, ShipType.LONGSHIP));
        step(30, mc -> shot(mc, "10_longship_furled"));
        cmd(2, "data merge entity @e[type=skycraft:longship,limit=1,sort=nearest] {Sail:2}");
        step(100, mc -> shot(mc, "11_longship_sail"));
        cmd(2, "skycraft-sailing tp " + PORT);
        cmd(20, "skycraft-sailing ship cog");
        step(40, mc -> lookAt(mc, ShipType.COG));
        step(2, mc -> backOff(mc, ShipType.COG, 14));
        step(30, mc -> lookAt(mc, ShipType.COG));
        step(40, mc -> shot(mc, "12_cog"));
        cmd(2, "data merge entity @e[type=skycraft:cog,limit=1,sort=nearest] {Sail:2}");
        step(100, mc -> shot(mc, "13_cog_sail"));
        cmd(2, "skycraft-sailing tp " + PORT);
        cmd(20, "skycraft-sailing ship rowboat");
        step(40, mc -> backOff(mc, ShipType.ROWBOAT, 6));
        step(30, mc -> lookAt(mc, ShipType.ROWBOAT));
        step(40, mc -> shot(mc, "14_rowboat"));
        cmd(2, "skycraft-sailing tp " + PORT);
        cmd(20, "skycraft-sailing wreck");
        step(80, mc -> shot(mc, "15_wreck_and_flotsam"));
        cmd(2, "tp @s ~ ~-9 ~");
        step(60, mc -> shot(mc, "16_sea_wreck_underwater"));
        cmd(2, "skycraft-sailing tp " + PORT);
        cmd(2, "gamemode creative");
        cmd(2, "ride @s mount @e[type=skycraft:longship,limit=1,sort=nearest]");
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
                Skycraft.LOGGER.info("Sailing autotest: rowed {} blocks in 5 s (input {}, controller {}, in water {})",
                        String.format(java.util.Locale.ROOT, "%.1f", s.position().distanceTo(startPos)), s.localInput(),
                        s.getControllingPassenger() == mc.player, s.isInWater());
            } else {
                Skycraft.LOGGER.info("Sailing autotest: not aboard a ship ({})", mc.player.getVehicle());
            }
        });
        step(2, mc -> mc.options.keyUp.setDown(false));
        step(2, mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
        step(2, mc -> mc.setScreen(new com.skycraft.world.client.MapScreen()));
        step(60, mc -> shot(mc, "30_map_ports"));
        step(2, mc -> mc.setScreen(new ShipwrightScreen(mc.player.blockPosition())));
        step(30, mc -> shot(mc, "31_shipwright"));
        step(2, mc -> mc.setScreen(null));
        cmd(2, "ride @s dismount");
        cmd(20, "skycraft-sailing voyage windhelm");
        step(50, mc -> shot(mc, "40_voyage"));
        step(400, mc -> look(mc, mc.player.getYRot(), 15f));
        step(40, mc -> shot(mc, "41_arrival_windhelm"));
        step(20, mc -> {
            Skycraft.LOGGER.info("Sailing autotest finished; screenshots in {}", OUT.toAbsolutePath());
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
        if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen || mc.screen instanceof com.skycraft.client.screen.title.SkyPauseScreen) {
            mc.setScreen(null);
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
        try {
            Files.createDirectories(OUT);
            NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            File out = OUT.resolve(name + ".png").toFile();
            img.writeToFile(out);
            img.close();
            Skycraft.LOGGER.info("Sailing autotest: saved {}", out.getAbsolutePath());
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Sailing autotest: screenshot {} failed", name, e);
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
                String name = "sailtest-" + System.currentTimeMillis();
                LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                        new GameRules(), WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261010L, false, false),
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
            Skycraft.LOGGER.warn("Sailing autotest step {} failed", stepIndex - 1, t);
        }
        if (stepIndex < STEPS.size()) waitLeft = STEPS.get(stepIndex).delay();
    }
}
