package com.skycraft.visual.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.skycraft.Skycraft;
import com.skycraft.client.hud.HudNotifications;
import com.skycraft.client.screen.title.SkyPauseScreen;
import com.skycraft.network.CorePackets;
import com.skycraft.network.NotifyKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Zombie;
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
 * Developer harness for the visual workstream: when {@code skycraft-visualtest.txt} exists in the game directory
 * (or {@code SKYCRAFT_VISUALTEST=1} is set), it creates a fresh world from the title screen, captures the loading
 * screen, then walks through the night sky, regional weather, the HUD with a target, banners, the sneak eye and the
 * restyled menus, saving screenshots to {@code build/visualtest-screenshots/} under the game directory, and quits.
 * Does nothing in normal play.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class VisualAutotest {
    private static final boolean ENABLED = Files.exists(Paths.get("skycraft-visualtest.txt")) || "1".equals(System.getenv("SKYCRAFT_VISUALTEST"));
    private static final Path OUT = Paths.get("build", "visualtest-screenshots");

    private record Step(int delay, Consumer<Minecraft> action) {}

    private static final List<Step> STEPS = new ArrayList<>();
    private static boolean worldRequested;
    private static boolean loadingShot;
    private static int loadingTicks;
    private static int inWorld;
    private static int stepIndex;
    private static int waitLeft;

    static {
        step(100, mc -> {
            com.skycraft.network.SkyNetwork.sendToServer(new CorePackets.ChooseRace(com.skycraft.core.Race.NORD.id()));
            mc.setScreen(null);
        });
        cmd(2, "gamerule doDaylightCycle false");
        cmd(2, "gamerule doMobSpawning false");
        cmd(2, "effect give @s minecraft:resistance 99999 255 true");
        cmd(2, "give @s skycraft:daedric_helmet");
        cmd(2, "give @s skycraft:dragonbone_sword");
        cmd(2, "tp @s ~ ~40 ~");
        cmd(2, "effect give @s minecraft:slow_falling 99999 0 true");
        cmd(2, "time set 18000");
        cmd(2, "skycraft-weather set clear 60");
        step(200, mc -> look(mc, 180f, -35f));
        step(40, mc -> shot(mc, "01_night_sky_north"));
        step(2, mc -> look(mc, 90f, -70f));
        step(40, mc -> shot(mc, "02_night_sky_up"));
        step(2, mc -> look(mc, -90f, -20f));
        step(40, mc -> shot(mc, "03_night_sky_east"));
        cmd(2, "time set 6000");
        weather("snow");
        weather("blizzard");
        weather("fog");
        weather("rain");
        weather("ash_storm");
        weather("sandstorm");
        cmd(2, "skycraft-weather set clear 60");
        cmd(2, "time set 1000");
        step(260, mc -> look(mc, 0f, 0f));
        cmd(2, "summon minecraft:zombie ^ ^ ^5 {NoAI:1b,PersistenceRequired:1b}");
        step(30, mc -> lookAtZombie(mc));
        step(10, mc -> hitZombie(mc));
        step(16, mc -> shot(mc, "20_target_hud"));
        step(2, mc -> HudNotifications.add(new CorePackets.Notify(NotifyKind.LEVEL_UP, Component.literal("Level Up"), Component.literal("You have reached level 5"), 0, 0f)));
        step(30, mc -> shot(mc, "21_level_up_banner"));
        step(120, mc -> HudNotifications.add(new CorePackets.Notify(NotifyKind.BIG_TITLE, Component.literal("Dragon Soul Absorbed"), Component.empty(), 0, 0f)));
        step(30, mc -> shot(mc, "22_dragon_soul"));
        step(100, mc -> HudNotifications.add(new CorePackets.Notify(NotifyKind.QUEST_STARTED, Component.literal("Bleak Falls Barrow"), Component.literal("Retrieve the Dragonstone"), 0, 0f)));
        step(30, mc -> shot(mc, "23_quest_banner"));
        step(90, mc -> mc.options.keyShift.setDown(true));
        step(30, mc -> shot(mc, "24_sneak_eye"));
        step(2, mc -> mc.options.keyShift.setDown(false));
        step(10, mc -> mc.setScreen(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel"))));
        step(40, mc -> shot(mc, "30_loading_screen_a"));
        step(200, mc -> shot(mc, "31_loading_screen_b"));
        step(2, mc -> mc.setScreen(new SkyPauseScreen()));
        step(30, mc -> shot(mc, "32_pause"));
        step(2, mc -> mc.setScreen(new ConfirmScreen(b -> mc.setScreen(null), Component.literal("Quit to Main Menu?"), Component.literal("Any unsaved progress will be saved."))));
        step(30, mc -> shot(mc, "33_confirm"));
        step(2, mc -> mc.setScreen(new DeathScreen(Component.literal("Zombie was slain by Dovahkiin's own folly"), false)));
        step(80, mc -> shot(mc, "34_death"));
        step(2, mc -> mc.setScreen(new com.skycraft.inventory.client.SkyrimInventoryScreen()));
        step(30, mc -> shot(mc, "35_inventory"));
        step(2, mc -> {
            if (mc.screen != null) mc.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_X, 0, 0);
        });
        step(40, mc -> shot(mc, "35b_inventory_inspect"));
        step(2, mc -> mc.setScreen(new com.skycraft.client.screen.RaceScreen()));
        step(40, mc -> shot(mc, "36_race"));
        step(2, mc -> mc.setScreen(null));
        step(20, mc -> {
            Skycraft.LOGGER.info("Visual autotest finished; screenshots in {}", OUT.toAbsolutePath());
            mc.stop();
        });
    }

    private VisualAutotest() {}

    private static void step(int wait, Consumer<Minecraft> action) {
        STEPS.add(new Step(wait, action));
    }

    private static void cmd(int wait, String command) {
        step(wait, mc -> {
            if (mc.player != null) mc.player.connection.sendCommand(command);
        });
    }

    private static void weather(String kind) {
        cmd(2, "skycraft-weather set " + kind + " 60");
        step(2, mc -> look(mc, 30f, -8f));
        step(320, mc -> shot(mc, "10_weather_" + kind));
    }

    private static void look(Minecraft mc, float yaw, float pitch) {
        if (mc.player == null) return;
        mc.setScreen(null);
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
    }

    private static Zombie zombie(Minecraft mc) {
        if (mc.level == null) return null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof Zombie z) return z;
        }
        return null;
    }

    private static void lookAtZombie(Minecraft mc) {
        Zombie z = zombie(mc);
        if (z == null || mc.player == null) return;
        double dx = z.getX() - mc.player.getX(), dz = z.getZ() - mc.player.getZ();
        double dy = z.getEyeY() - 0.4 - mc.player.getEyeY();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        look(mc, yaw, pitch);
    }

    private static void hitZombie(Minecraft mc) {
        Zombie z = zombie(mc);
        if (z != null && mc.gameMode != null && mc.player != null) {
            mc.gameMode.attack(mc.player, z);
            mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
    }

    private static void shot(Minecraft mc, String name) {
        try {
            Files.createDirectories(OUT);
            NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            File out = OUT.resolve(name + ".png").toFile();
            img.writeToFile(out);
            img.close();
            Skycraft.LOGGER.info("Visual autotest: saved {}", out.getAbsolutePath());
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Visual autotest: screenshot {} failed", name, e);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (mc.screen instanceof LevelLoadingScreen && !loadingShot && ++loadingTicks > 30) {
                loadingShot = true;
                shot(mc, "00_world_loading");
            }
            if (mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) mc.setScreen(new TitleScreen());
            if (!worldRequested && (mc.screen instanceof TitleScreen || mc.screen instanceof com.skycraft.client.screen.title.SkyTitleScreen)) {
                worldRequested = true;
                String name = "visualtest-" + System.currentTimeMillis();
                LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL, false, Difficulty.EASY, true,
                        new GameRules(), WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261010L, false, false),
                        WorldPresets::createNormalWorldDimensions);
            }
            return;
        }
        if (mc.player == null || stepIndex >= STEPS.size()) return;
        if (inWorld++ == 0) waitLeft = STEPS.get(0).delay();
        // keep the race picker and other prompts out of the way, except the screens we opened on purpose
        if (--waitLeft > 0) return;
        Step s = STEPS.get(stepIndex++);
        try {
            s.action().accept(mc);
        } catch (Throwable t) {
            Skycraft.LOGGER.warn("Visual autotest step {} failed", stepIndex - 1, t);
        }
        if (stepIndex < STEPS.size()) waitLeft = STEPS.get(stepIndex).delay();
    }
}
