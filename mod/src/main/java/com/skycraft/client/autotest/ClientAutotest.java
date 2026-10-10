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

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
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

    private static void captureAndSave(Minecraft mc, String name) {
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

