package com.skycraft.client.autotest;

import com.mojang.blaze3d.platform.NativeImage;
import com.skycraft.Skycraft;
import com.skycraft.client.screen.HubScreen;
import com.skycraft.client.screen.RaceScreen;
import com.skycraft.client.screen.SkillsScreen;
import com.skycraft.crafting.arcane.client.AlchemyScreen;
import com.skycraft.inventory.client.SkyrimInventoryScreen;
import com.skycraft.magic.client.MagicMenuScreen;
import com.skycraft.quest.client.JournalScreen;
import com.skycraft.world.client.MapScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;

/**
 * Workstream N: Scripted autotest enabled when -Dskycraft.autotest=true is set.
 * - Waits for singleplayer world load
 * - Steps through major Skyrim screens: RaceScreen, SkillsScreen, HubScreen, SkyrimInventoryScreen,
 *   MagicMenuScreen, JournalScreen, MapScreen.
 * - Summons core Skycraft entities into view
 * - Captures screenshots, saves to disk and prints downscaled base64 JPEG markers for CI
 * - Gracefully exits client when complete.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class ClientAutotest {
    public static final boolean ENABLED = Boolean.getBoolean("skycraft.autotest");
    private static int tick = 0;
    private static int step = 0;
    private static boolean worldReady = false;
    private static final Path SCREENSHOT_DIR = Paths.get("build", "autotest-screenshots");

    private static final List<String> SUMMON_COMMANDS = List.of(
            "summon skycraft:deer ~2 ~ ~",
            "summon skycraft:sabre_cat ~3 ~ ~1",
            "summon skycraft:bear ~-3 ~ ~2",
            "summon skycraft:mudcrab ~1 ~ ~3",
            "summon skycraft:mammoth ~5 ~ ~-2"
    );

    private ClientAutotest() {}

    /**
     * Optional {@code -Dskycraft.autotest.world=<name>}: from the title screen, create that singleplayer world afresh
     * (creative, cheats on; an old one of that name is deleted) so the screen walk-through runs without a person. CI
     * leaves it unset and only checks resource loading.
     */
    private static final String AUTO_WORLD = System.getProperty("skycraft.autotest.world", "");
    private static boolean autoWorldStarted;

    private static void openAutoWorld(Minecraft mc) {
        autoWorldStarted = true;
        try {
            // Always a fresh world: loading a modded world asks to confirm its experimental settings, which would wait
            // for a person.
            if (mc.getLevelSource().levelExists(AUTO_WORLD)) {
                try (var access = mc.getLevelSource().createAccess(AUTO_WORLD)) {
                    access.deleteLevel();
                }
            }
            Skycraft.LOGGER.info("Autotest: creating creative world {}", AUTO_WORLD);
            net.minecraft.world.level.LevelSettings settings = new net.minecraft.world.level.LevelSettings(AUTO_WORLD,
                    net.minecraft.world.level.GameType.CREATIVE, false, net.minecraft.world.Difficulty.PEACEFUL, true,
                    new net.minecraft.world.level.GameRules(), net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(AUTO_WORLD, settings, new net.minecraft.world.level.levelgen.WorldOptions(1234L, true, false),
                    access -> access.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                            .getHolderOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL).value().createWorldDimensions());
        } catch (Exception e) {
            Skycraft.LOGGER.error("Autotest: could not open world {}", AUTO_WORLD, e);
        }
    }

    /**
     * Optional {@code -Dskycraft.autotest.createflow=true}: create the world the way a player does, through the title
     * screen's New Game and {@link com.skycraft.client.screen.title.SkyCreateWorldScreen}, and screenshot any prompt
     * that shows up on the way (a prompt here would block a real player).
     */
    private static final boolean CREATE_FLOW = Boolean.getBoolean("skycraft.autotest.createflow");
    /** With {@link #CREATE_FLOW}: {@code -Dskycraft.autotest.load=<save folder>} loads that save instead of creating one. */
    private static final String LOAD_WORLD = System.getProperty("skycraft.autotest.load", "");
    private static int createTicks;
    private static int createStage;
    private static final java.util.Set<String> SEEN_PROMPTS = new java.util.HashSet<>();

    private static void createFlowTick(Minecraft mc) {
        createTicks++;
        net.minecraft.client.gui.screens.Screen s = mc.screen;
        if (s instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) {
            mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
            return;
        }
        if (createStage == 0 && (s instanceof net.minecraft.client.gui.screens.TitleScreen
                || s instanceof com.skycraft.client.screen.title.SkyTitleScreen) && createTicks > 40) {
            createStage = 1;
            createTicks = 0;
            try {
                Files.createDirectories(SCREENSHOT_DIR);
            } catch (Exception ignored) {}
            if (!LOAD_WORLD.isEmpty()) {
                // Continue an existing save, as the title screen's Continue does
                Skycraft.LOGGER.info("Autotest: loading saved world {}", LOAD_WORLD);
                createStage = 2;
                mc.createWorldOpenFlows().loadLevel(s, LOAD_WORLD);
                return;
            }
            Skycraft.LOGGER.info("Autotest: New Game from the title screen ({})", s.getClass().getSimpleName());
            net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.openFresh(mc, s);
        } else if (createStage == 1 && s instanceof com.skycraft.client.screen.title.SkyCreateWorldScreen && createTicks > 40) {
            createStage = 2;
            createTicks = 0;
            captureAndSave(mc, "00_create_world_screen");
            s.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
        } else if (createStage >= 2) {
            String name = s == null ? "none" : s.getClass().getName();
            if (!name.equals(lastScreen)) {
                lastScreen = name;
                createTicks = 0;
                Skycraft.LOGGER.info("Autotest: world creation shows {} \"{}\"", name, s == null ? "" : s.getTitle().getString());
            } else if (createTicks == 60 && s != null && SEEN_PROMPTS.add(name)) {
                // a screen that stays up for 3 s during creation is a prompt or a hang a player would see
                Skycraft.LOGGER.warn("Autotest: world creation waits at {} \"{}\"", name, s.getTitle().getString());
                captureAndSave(mc, "00_create_world_wait_" + SEEN_PROMPTS.size());
            }
        }
    }

    private static String lastScreen = "";

    /**
     * Optional {@code -Dskycraft.autotest.tour=Name:x:z,Name:x:z,...}: instead of the screen walk-through, fly to each
     * spot (spectator, through the integrated server, so cheats needn't be on) and screenshot it from the ground and
     * from the air. The map screen and the HUD are captured at the start.
     */
    private static final String TOUR = System.getProperty("skycraft.autotest.tour", "");
    private static final int TOUR_WAIT = Integer.getInteger("skycraft.autotest.tourWait", 400);
    private static int tourStep;
    private static int tourWait;

    private static void serverCommand(Minecraft mc, String command) {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        String name = mc.player.getGameProfile().getName();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                command.replace("@p", name)));
    }

    /**
     * Teleports the player to (x, surface + up, z) on the server thread: the generator's estimate before the chunk
     * exists, the real height (above trees) once it is loaded. Then clears the regional weather there.
     */
    private static void tourTeleport(Minecraft mc, int x, int z, int up, float yaw, float pitch) {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        String name = mc.player.getGameProfile().getName();
        server.execute(() -> {
            var level = server.overworld();
            boolean loaded = level.getChunkSource().hasChunk(x >> 4, z >> 4);
            int h = loaded ? level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z)
                    : level.getChunkSource().getGenerator().getBaseHeight(x, z,
                    net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState());
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    String.format(java.util.Locale.ROOT, "tp %s %d %d %d %.0f %.0f", name, x, h + up, z, yaw, pitch));
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                    "execute as " + name + " at " + name + " run skycraft-weather set clear 30");
            Skycraft.LOGGER.info("Autotest tour: {} at {} {} {} (surface {}, {})", name, x, h + up, z, h, loaded ? "loaded" : "estimate");
        });
    }

    private static void tourTick(Minecraft mc) {
        if (tourWait-- > 0) return;
        String[] spots = TOUR.split(",");
        int s = tourStep++;
        tourWait = 40;
        switch (s) {
            case 0 -> {
                com.skycraft.network.SkyNetwork.sendToServer(new com.skycraft.network.CorePackets.ChooseRace(com.skycraft.core.Race.NORD.id()));
                mc.setScreen(null);
                serverCommand(mc, "gamerule doDaylightCycle false");
                serverCommand(mc, "gamerule doMobSpawning false");
                serverCommand(mc, "time set 5000");
                serverCommand(mc, "weather clear 1000000");
                serverCommand(mc, "execute as @p at @p run skycraft-weather set clear 600");
                tourWait = 100;
            }
            case 1 -> captureAndSave(mc, "t00_spawn_hud");
            case 2 -> mc.setScreen(new MapScreen());
            case 3 -> {
                captureAndSave(mc, "t01_map_screen");
                mc.setScreen(null);
                serverCommand(mc, "gamemode spectator @p");
            }
            default -> tourSpotTick(mc, spots);
        }
    }

    /**
     * Optional {@code -Dskycraft.autotest.tourFar=<blocks>}: at each tour spot also take a "far" shot from that height
     * above the ground, looking at the horizon (Distant Horizons LODs, clouds, fog). 0 (default) = no far shot.
     */
    private static final int TOUR_FAR = Integer.getInteger("skycraft.autotest.tourFar", 0);
    /**
     * Optional {@code -Dskycraft.autotest.shaderToggle=true}: while an Oculus shader pack is active, repeat the last
     * shot of each spot with shaders off (through the Iris API), then turn them back on.
     */
    private static final boolean SHADER_TOGGLE = Boolean.getBoolean("skycraft.autotest.shaderToggle");
    private static int spotIndex;
    private static int spotPhase;
    private static boolean spotShadersToggled;
    // frame-rate samples since the last teleport (one per second)
    private static int fpsSum, fpsCount, fpsMin = Integer.MAX_VALUE;

    private static void resetFps() {
        fpsSum = 0;
        fpsCount = 0;
        fpsMin = Integer.MAX_VALUE;
    }

    private static void sampleFps(Minecraft mc) {
        if (tick % 20 != 0) return;
        int fps = mc.getFps();
        fpsSum += fps;
        fpsCount++;
        fpsMin = Math.min(fpsMin, fps);
    }

    private static void logPerf(Minecraft mc, String shot) {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
        Skycraft.LOGGER.info("Autotest perf: {} shaders={} skySeesShaders={} fps_now={} fps_avg={} fps_min={} samples={} heap={}MB/{}MB | {}",
                shot, irisShaders(), skySeesShaders(), mc.getFps(), fpsCount == 0 ? 0 : fpsSum / fpsCount, fpsCount == 0 ? 0 : fpsMin, fpsCount,
                used, rt.maxMemory() >> 20, mc.fpsString);
    }

    /** What the Skycraft sky renderer believes (it skips its extra sky while a shader pack is active). */
    private static String skySeesShaders() {
        try {
            var m = Class.forName("com.skycraft.atmosphere.client.ShaderCompat").getDeclaredMethod("shadersActive");
            m.setAccessible(true);
            return String.valueOf(m.invoke(null));
        } catch (Throwable t) {
            return "n/a";
        }
    }

    /** Iris API config by reflection (no dependency): null when Oculus isn't installed. */
    private static Object irisConfig() {
        try {
            Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = c.getMethod("getInstance").invoke(null);
            return c.getMethod("getConfig").invoke(api);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean irisShaders() {
        try {
            Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = c.getMethod("getInstance").invoke(null);
            return Boolean.TRUE.equals(c.getMethod("isShaderPackInUse").invoke(api));
        } catch (Throwable t) {
            return false;
        }
    }

    private static void setIrisShaders(boolean on) {
        Object cfg = irisConfig();
        if (cfg == null) return;
        try {
            Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig").getMethod("setShadersEnabledAndApply", boolean.class).invoke(cfg, on);
            Skycraft.LOGGER.info("Autotest: shaders {}", on ? "on" : "off");
        } catch (Throwable t) {
            Skycraft.LOGGER.warn("Autotest: could not switch shaders {}", on ? "on" : "off", t);
        }
    }

    private static void tourSpotTick(Minecraft mc, String[] spots) {
        int i = spotIndex;
        if (i >= spots.length) {
            Skycraft.LOGGER.info("=== Skycraft Autotest: tour finished. Closing client. ===");
            mc.stop();
            return;
        }
        String[] p = spots[i].split(":");
        int x = Integer.parseInt(p[1].trim()), z = Integer.parseInt(p[2].trim());
        float yaw = p.length > 3 ? Float.parseFloat(p[3]) : 0f;
        String tag = String.format(java.util.Locale.ROOT, "t%02d_%s", i + 2, p[0].trim());
        String last = TOUR_FAR > 0 ? "_far" : "_air";
        switch (spotPhase++) {
            case 0 -> {
                tourTeleport(mc, x, z, 3, yaw, 8f);
                tourWait = TOUR_WAIT;
            }
            case 1 -> {
                tourTeleport(mc, x, z, 2, yaw, 8f);
                resetFps();
                tourWait = 60;
            }
            case 2 -> {
                captureAndSave(mc, tag + "_ground");
                logPerf(mc, tag + "_ground");
                tourTeleport(mc, x, z, 70, yaw, 28f);
                tourWait = TOUR_WAIT * 3 / 4;
                resetFps();
            }
            case 3 -> {
                captureAndSave(mc, tag + "_air");
                logPerf(mc, tag + "_air");
                if (TOUR_FAR > 0) {
                    tourTeleport(mc, x, z, TOUR_FAR, yaw, 6f);
                    tourWait = TOUR_WAIT / 2;
                    resetFps();
                }
            }
            case 4 -> {
                if (TOUR_FAR > 0) {
                    captureAndSave(mc, tag + "_far");
                    logPerf(mc, tag + "_far");
                }
                spotShadersToggled = SHADER_TOGGLE && irisShaders();
                if (spotShadersToggled) {
                    setIrisShaders(false);
                    tourWait = 160;
                    resetFps();
                }
            }
            case 5 -> {
                if (spotShadersToggled) {
                    captureAndSave(mc, tag + last + "_noshaders");
                    logPerf(mc, tag + last + "_noshaders");
                    setIrisShaders(true);
                    tourWait = 200;
                }
            }
            default -> {
                spotIndex++;
                spotPhase = 0;
                tourWait = 0;
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null && CREATE_FLOW) {
            createFlowTick(mc);
            return;
        }
        if (mc.level == null && !AUTO_WORLD.isEmpty() && !autoWorldStarted
                && (mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen
                || mc.screen instanceof com.skycraft.client.screen.title.SkyTitleScreen)) {
            openAutoWorld(mc);
            return;
        }
        if (mc.level == null || mc.player == null) return;

        tick++;
        if (!worldReady) {
            if (tick > 100) { // wait ~5 seconds for world generation and client settling
                worldReady = true;
                tick = 0;
                step = 0;
                try {
                    Files.createDirectories(SCREENSHOT_DIR);
                } catch (Exception ignored) {}
                Skycraft.LOGGER.info("=== Skycraft Autotest: World loaded, beginning test suite ===");
            }
            return;
        }
        if (!TOUR.isEmpty()) {
            sampleFps(mc);
            tourTick(mc);
            return;
        }
        if (com.skycraft.guide.client.GuideAutotest.ENABLED) { // -Dskycraft.autotest.guide=true: the handbook and hints
            com.skycraft.guide.client.GuideAutotest.tick(mc);
            return;
        }

        // Run steps with pauses between to let renderers stabilize and draw
        if (tick % 40 == 0) {
            runStep(mc, step++);
        }
    }

    private static void runStep(Minecraft mc, int currentStep) {
        switch (currentStep) {
            case 0 -> {
                Skycraft.LOGGER.info("Autotest [1/10]: Opening Character Creator (RaceScreen)");
                mc.setScreen(new RaceScreen());
            }
            case 1 -> {
                captureAndSave(mc, "01_race_screen");
                mc.setScreen(null);
            }
            case 2 -> {
                Skycraft.LOGGER.info("Autotest [2/10]: Opening Skills Screen");
                mc.setScreen(new SkillsScreen());
            }
            case 3 -> {
                captureAndSave(mc, "02_skills_screen");
                mc.setScreen(null);
            }
            case 4 -> {
                Skycraft.LOGGER.info("Autotest [3/10]: Opening Menu Hub (Tab)");
                mc.setScreen(new HubScreen());
            }
            case 5 -> {
                captureAndSave(mc, "03_hub_screen");
                mc.setScreen(null);
            }
            case 6 -> {
                Skycraft.LOGGER.info("Autotest [4/10]: Opening Skyrim Inventory");
                mc.setScreen(new SkyrimInventoryScreen());
            }
            case 7 -> {
                captureAndSave(mc, "04_inventory_screen");
                mc.setScreen(null);
            }
            case 8 -> {
                Skycraft.LOGGER.info("Autotest [5/10]: Opening Magic Menu");
                mc.setScreen(new MagicMenuScreen());
            }
            case 9 -> {
                captureAndSave(mc, "05_magic_screen");
                mc.setScreen(null);
            }
            case 10 -> {
                Skycraft.LOGGER.info("Autotest [6/10]: Opening Quest Journal");
                mc.setScreen(new JournalScreen());
            }
            case 11 -> {
                captureAndSave(mc, "06_journal_screen");
                mc.setScreen(null);
            }
            case 12 -> {
                Skycraft.LOGGER.info("Autotest [7/10]: Opening Skyrim Map");
                mc.setScreen(new MapScreen());
            }
            case 13 -> {
                captureAndSave(mc, "07_map_screen");
                mc.setScreen(null);
            }
            case 14 -> {
                Skycraft.LOGGER.info("Autotest [8/10]: Summoning Skycraft Entities in view");
                for (String cmd : SUMMON_COMMANDS) {
                    if (mc.player != null) mc.player.connection.sendCommand(cmd);
                }
            }
            case 15 -> {
                captureAndSave(mc, "08_wildlife_entities");
            }
            // magic & skills workstream: the new constellations, the Mysticism tab, spellmaking and staff enchanting
            case 16 -> {
                SkillsScreen.preselect(com.skycraft.core.Skill.MYSTICISM);
                mc.setScreen(new SkillsScreen());
            }
            case 17 -> {
                captureAndSave(mc, "09_skills_mysticism");
                mc.setScreen(null);
            }
            case 18 -> mc.setScreen(new com.skycraft.magic.client.SpellmakingScreen(mc.player.blockPosition()).withEffects(
                    com.skycraft.magic.spell.spellmaking.SpellEffectType.FIRE_DAMAGE, com.skycraft.magic.spell.spellmaking.SpellEffectType.SOUL_TRAP,
                    com.skycraft.magic.spell.spellmaking.SpellEffectType.TURN_UNDEAD, com.skycraft.magic.spell.spellmaking.SpellEffectType.SHIELD));
            case 19 -> {
                captureAndSave(mc, "10_spellmaking_screen");
                mc.setScreen(null);
            }
            case 20 -> mc.setScreen(new com.skycraft.arsenal.client.StaffEnchanterScreen(mc.player.blockPosition()));
            case 21 -> {
                captureAndSave(mc, "11_staff_enchanter_screen");
                mc.setScreen(null);
            }
            case 22 -> {
                com.skycraft.magic.client.MagicMenuScreen.preselect(com.skycraft.magic.spell.School.MYSTICISM);
                mc.setScreen(new MagicMenuScreen());
            }
            case 23 -> {
                captureAndSave(mc, "12_magic_mysticism");
                mc.setScreen(null);
            }
            case 24 -> {
                Skycraft.LOGGER.info("=== Skycraft Autotest: Completed successfully! Closing client. ===");
                mc.stop();
            }
        }
    }

    public static void captureAndSave(Minecraft mc, String name) {
        try {
            NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            File outFile = SCREENSHOT_DIR.resolve(name + ".png").toFile();
            img.writeToFile(outFile);

            // Downscale to JPEG and log as base64 markers for CI log inspection
            byte[] bytes = img.asByteArray();
            img.close();

            BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
            if (src != null) {
                int thumbW = 400;
                int thumbH = (int) (src.getHeight() * (400.0 / src.getWidth()));
                BufferedImage thumb = new BufferedImage(thumbW, thumbH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = thumb.createGraphics();
                g.drawImage(src.getScaledInstance(thumbW, thumbH, Image.SCALE_SMOOTH), 0, 0, null);
                g.dispose();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(thumb, "jpg", baos);
                String b64 = Base64.getEncoder().encodeToString(baos.toByteArray());

                System.out.println("=== SCREENSHOT_START:" + name + " ===");
                System.out.println(b64);
                System.out.println("=== SCREENSHOT_END:" + name + " ===");
            }
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Autotest could not capture screenshot for {}", name, e);
        }
    }
}

