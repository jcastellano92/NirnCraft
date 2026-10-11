package com.skycraft.survival.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.skycraft.Skycraft;
import com.skycraft.homestead.HomesteadPackets;
import com.skycraft.network.CorePackets;
import com.skycraft.network.SkyNetwork;
import com.skycraft.survival.SurvivalPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
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
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Developer harness for the survival workstream: when {@code skycraft-survivaltest.txt} exists in the game directory
 * (or {@code SKYCRAFT_SURVIVALTEST=1} is set) it creates a fresh world and photographs the Survival Mode meters at
 * several stages, the freezing frost, the fishing reel bar, a tent with a bedroll, a player's body and a homestead
 * built from the drafting table, into {@code build/survivaltest-screenshots/}, then quits. Does nothing in normal play.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class SurvivalAutotest {
    private static final boolean ENABLED = Files.exists(Paths.get("skycraft-survivaltest.txt")) || "1".equals(System.getenv("SKYCRAFT_SURVIVALTEST"));
    private static final Path OUT = Paths.get("build", "survivaltest-screenshots");

    private record Step(int delay, Consumer<Minecraft> action) {}

    private static final List<Step> STEPS = new ArrayList<>();
    private static boolean worldRequested;
    private static int inWorld;
    private static int stepIndex;
    private static int waitLeft;
    private static BlockPos base = BlockPos.ZERO;

    static {
        step(100, mc -> {
            SkyNetwork.sendToServer(new CorePackets.ChooseRace(com.skycraft.core.Race.NORD.id()));
            mc.setScreen(null);
        });
        cmd(2, "gamerule doDaylightCycle false");
        cmd(2, "gamerule doMobSpawning false");
        cmd(2, "gamerule doWeatherCycle false");
        cmd(2, "time set 6000");
        cmd(2, "effect give @s minecraft:resistance 99999 255 true");
        cmd(2, "spreadplayers 400 -300 0 1 false @s");
        step(80, mc -> base = mc.player.blockPosition());
        // a stone yard with a pond to the north
        step(2, mc -> run(mc, "fill " + rel(-8, -1, -16) + " " + rel(8, -1, 4) + " minecraft:stone_bricks"));
        step(2, mc -> run(mc, "fill " + rel(-8, 0, -16) + " " + rel(8, 12, 4) + " minecraft:air"));
        step(2, mc -> run(mc, "fill " + rel(-3, -2, -8) + " " + rel(3, -1, -4) + " minecraft:water"));
        step(20, mc -> look(mc, 180f, 10f));
        // survival meters
        cmd(2, "skycraft-survival set hunger 95");
        cmd(2, "skycraft-survival set fatigue 130");
        cmd(2, "skycraft-survival set cold 150");
        step(60, mc -> shot(mc, "40_meters_hungry_weary_very_cold"));
        cmd(2, "skycraft-survival set hunger 200");
        cmd(2, "skycraft-survival set cold 200");
        step(50, mc -> shot(mc, "41_meters_starving_freezing"));
        cmd(2, "skycraft-survival set cold 270");
        step(50, mc -> shot(mc, "42_meters_numb_frost"));
        cmd(2, "skycraft-survival reset");
        cmd(2, "skycraft-survival set hunger 45");
        step(40, mc -> shot(mc, "43_meters_peckish"));
        cmd(2, "skycraft-survival reset");
        // fishing: cast into the pond, then show the reel bar
        cmd(2, "item replace entity @s weapon.mainhand with skycraft:fishing_rod");
        step(10, mc -> look(mc, 180f, 35f));
        step(10, mc -> {
            if (mc.gameMode != null) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        });
        step(60, mc -> shot(mc, "44_fishing_waiting"));
        step(2, mc -> FishingHud.receive(new SurvivalPackets.Bite((byte) 1, (short) 420, (short) 600, (short) 40, (short) 86, (byte) 0, "")));
        step(13, mc -> shot(mc, "45_fishing_reel_bar"));
        step(2, mc -> FishingHud.receive(new SurvivalPackets.Bite((byte) 0, (short) 0, (short) 0, (short) 0, (short) 0, (byte) 1,
                "item.skycraft.arctic_char")));
        step(10, mc -> shot(mc, "46_fishing_caught"));
        step(2, mc -> {
            if (mc.gameMode != null) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        });
        // a tent with a bedroll, a campfire, and a body
        cmd(2, "give @s skycraft:bedroll");
        cmd(2, "item replace entity @s weapon.mainhand with skycraft:tent_kit");
        step(10, mc -> look(mc, 90f, 20f));
        step(5, mc -> useOn(mc, base.offset(-5, -1, 0)));
        cmd(5, "item replace entity @s weapon.mainhand with skycraft:campfire_kit");
        step(5, mc -> useOn(mc, base.offset(-3, -1, 3)));
        step(5, mc -> {
            UUID id = mc.player.getUUID();
            long most = id.getMostSignificantBits(), least = id.getLeastSignificantBits();
            String uuid = "[I;" + (int) (most >> 32) + "," + (int) most + "," + (int) (least >> 32) + "," + (int) least + "]";
            BlockPos at = base.offset(-3, 0, -2);
            run(mc, "summon skycraft:player_corpse " + at.getX() + " " + at.getY() + " " + at.getZ()
                    + " {Owner:" + uuid + ",OwnerName:\"" + mc.player.getGameProfile().getName() + "\",PlayerItems:[{Slot:0b,id:\"minecraft:iron_sword\",Count:1b}]}");
        });
        cmd(5, "tp @s " + "~4 ~1 ~");
        step(30, mc -> lookAt(mc, base.offset(-6, 0, 0)));
        step(40, mc -> shot(mc, "47_tent_campfire_body"));
        step(2, mc -> run(mc, "tp @s " + (base.getX() - 2) + " " + (base.getY() + 1) + " " + (base.getZ() + 4)));
        step(20, mc -> lookAt(mc, base.offset(-6, 0, 0)));
        step(30, mc -> shot(mc, "47b_tent_close"));
        step(2, mc -> run(mc, "tp @s " + (base.getX() - 9) + " " + (base.getY() + 2) + " " + (base.getZ() + 3)));
        step(20, mc -> lookAt(mc, base.offset(-6, 0, 0)));
        step(30, mc -> shot(mc, "47c_tent_back"));
        step(2, mc -> run(mc, "tp @s " + (base.getX() - 1) + " " + (base.getY() + 1) + " " + (base.getZ() - 1)));
        step(20, mc -> lookAt(mc, base.offset(-3, -1, -2)));
        step(30, mc -> shot(mc, "47d_player_body"));
        // the homestead: a drafting table and every room (creative: no materials needed)
        cmd(2, "gamemode creative");
        cmd(2, "tp @s " + "~ ~ ~");
        step(10, mc -> run(mc, "tp @s " + base.getX() + " " + base.getY() + " " + (base.getZ() + 2) + " 180 10"));
        cmd(10, "item replace entity @s weapon.mainhand with skycraft:drafting_table");
        step(10, mc -> useOn(mc, base.offset(0, -1, 0)));
        cmd(5, "item replace entity @s weapon.mainhand with minecraft:air");
        step(10, mc -> useOn(mc, base));
        step(30, mc -> shot(mc, "48_drafting_table"));
        for (String plan : new String[]{"small_house", "main_hall", "bedrooms", "storage_room", "kitchen", "cellar", "garden",
                "animal_pen", "workshop"}) {
            step(6, mc -> SkyNetwork.sendToServer(new HomesteadPackets.Action("build", plan)));
        }
        step(20, mc -> shot(mc, "49_drafting_table_built"));
        step(2, mc -> {
            if (mc.player.containerMenu instanceof com.skycraft.homestead.HomesteadMenu menu) {
                Skycraft.LOGGER.info("Survival autotest: built {}", menu.state.get("built"));
            }
        });
        step(2, mc -> {
            mc.setScreen(null);
            mc.options.hideGui = true;
        });
        step(5, mc -> run(mc, "tp @s " + (base.getX() + 24) + " " + (base.getY() + 26) + " " + (base.getZ() + 12)));
        step(5, mc -> fly(mc));
        step(60, mc -> lookAt(mc, base.offset(0, 4, -14)));
        step(20, mc -> shotClean(mc, "50_homestead_outside"));
        step(5, mc -> run(mc, "tp @s " + (base.getX() - 26) + " " + (base.getY() + 24) + " " + (base.getZ() - 40)));
        step(5, mc -> fly(mc));
        step(60, mc -> lookAt(mc, base.offset(0, 4, -14)));
        step(20, mc -> shotClean(mc, "51_homestead_back"));
        step(5, mc -> run(mc, "tp @s " + base.getX() + " " + (base.getY() + 40) + " " + (base.getZ() - 12)));
        step(5, mc -> fly(mc));
        step(60, mc -> look(mc, 180f, 90f));
        step(20, mc -> shotClean(mc, "51b_homestead_top"));
        step(5, mc -> run(mc, "tp @s " + base.getX() + " " + base.getY() + " " + (base.getZ() - 8) + " 180 5"));
        step(40, mc -> shotClean(mc, "52_main_hall_inside"));
        step(5, mc -> run(mc, "tp @s " + base.getX() + " " + base.getY() + " " + (base.getZ() - 5) + " 0 10"));
        step(40, mc -> shotClean(mc, "53_small_house_inside"));
        step(5, mc -> run(mc, "tp @s " + (base.getX() - 13) + " " + base.getY() + " " + (base.getZ() - 14) + " 0 10"));
        step(40, mc -> shotClean(mc, "54_bedrooms_inside"));
        step(20, mc -> {
            mc.options.hideGui = false;
            Skycraft.LOGGER.info("Survival autotest finished; screenshots in {}", OUT.toAbsolutePath());
            mc.stop();
        });
    }

    private SurvivalAutotest() {}

    private static String rel(int dx, int dy, int dz) {
        return (base.getX() + dx) + " " + (base.getY() + dy) + " " + (base.getZ() + dz);
    }

    private static void step(int wait, Consumer<Minecraft> action) {
        STEPS.add(new Step(wait, action));
    }

    private static void cmd(int wait, String command) {
        step(wait, mc -> run(mc, command));
    }

    private static void run(Minecraft mc, String command) {
        if (mc.player != null) mc.player.connection.sendCommand(command);
    }

    private static void useOn(Minecraft mc, BlockPos ground) {
        if (mc.gameMode == null || mc.player == null) return;
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(ground).add(0, 0.5, 0), Direction.UP, ground, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
    }

    private static void look(Minecraft mc, float yaw, float pitch) {
        if (mc.player == null) return;
        mc.setScreen(null);
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
    }

    private static void lookAt(Minecraft mc, BlockPos pos) {
        if (mc.player == null) return;
        double dx = pos.getX() + 0.5 - mc.player.getX(), dz = pos.getZ() + 0.5 - mc.player.getZ();
        double dy = pos.getY() + 0.5 - mc.player.getEyeY();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        look(mc, yaw, pitch);
    }

    /** The world only (the HUD is hidden for the homestead views: the build banners queue up). */
    private static void shotClean(Minecraft mc, String name) {
        shot(mc, name);
    }

    private static void fly(Minecraft mc) {
        if (mc.player != null) {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
        }
    }

    private static void shot(Minecraft mc, String name) {
        mc.gui.getChat().clearMessages(false);
        try {
            Files.createDirectories(OUT);
            NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            File out = OUT.resolve(name + ".png").toFile();
            img.writeToFile(out);
            img.close();
            Skycraft.LOGGER.info("Survival autotest: saved {}", out.getAbsolutePath());
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Survival autotest: screenshot {} failed", name, e);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (mc.screen instanceof LevelLoadingScreen) return;
            if (mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) mc.setScreen(new TitleScreen());
            if (!worldRequested && (mc.screen instanceof TitleScreen || mc.screen instanceof com.skycraft.client.screen.title.SkyTitleScreen)) {
                worldRequested = true;
                String name = "survivaltest-" + System.currentTimeMillis();
                LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL, false, net.minecraft.world.Difficulty.EASY, true,
                        new GameRules(), WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261011L, false, false),
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
            Skycraft.LOGGER.warn("Survival autotest step {} failed", stepIndex - 1, t);
        }
        if (stepIndex < STEPS.size()) waitLeft = STEPS.get(stepIndex).delay();
    }
}
