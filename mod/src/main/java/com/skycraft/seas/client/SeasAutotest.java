package com.skycraft.seas.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.skycraft.Skycraft;
import com.skycraft.network.CorePackets;
import com.skycraft.sailing.DeckPhysics;
import com.skycraft.sailing.ShipEntity;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Developer harness for the seas module (like {@code sailing.client.SailingAutotest}): when
 * {@code skycraft-seastest.txt} exists in the game directory (or {@code SKYCRAFT_SEASTEST=1}), it creates a fresh
 * world, goes out to sea from a port, builds sea-bed sites and an islet, brings ships of the traffic alongside, stands
 * the player on a sailing ship's deck and checks it is carried, lines up the sea creatures, puts gulls in the sky, and
 * saves screenshots to {@code build/seastest-screenshots/}, then quits. Does nothing in normal play.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class SeasAutotest {
    private static final boolean ENABLED = Files.exists(Paths.get("skycraft-seastest.txt")) || "1".equals(System.getenv("SKYCRAFT_SEASTEST"));
    private static final Path OUT = Paths.get("build", "seastest-screenshots");
    private static final double SEA_X = Double.parseDouble(System.getProperty("skycraft.seastest.x", "-6120"));
    private static final double SEA_Z = Double.parseDouble(System.getProperty("skycraft.seastest.z", "9380"));

    private record Step(int delay, Consumer<Minecraft> action) {}

    private static final List<Step> STEPS = new ArrayList<>();
    private static boolean worldRequested;
    private static int inWorld;
    private static int stepIndex;
    private static int waitLeft;
    private static Vec3 sea;
    private static Vec3 deckStart;
    private static int deckShip = -1;

    static {
        step(100, mc -> {
            mc.options.pauseOnLostFocus = false;
            com.skycraft.network.SkyNetwork.sendToServer(new CorePackets.ChooseRace(com.skycraft.core.Race.NORD.id()));
            mc.gui.setScreen(null);
        });
        // no Helgen cart: the intro would carry the player off mid-test
        cmd(60, "skycraft-quest skip main_unbound");
        cmd(2, "gamerule advance_time false");
        cmd(2, "gamerule advance_weather false");
        cmd(2, "gamerule spawn_mobs false");
        cmd(2, "effect give @s minecraft:resistance 99999 255 true");
        cmd(2, "effect give @s minecraft:water_breathing 99999 0 true");
        cmd(2, "effect give @s minecraft:night_vision 99999 0 true");
        cmd(2, "time set 3000");
        cmd(2, "skycraft-weather set clear 60");
        cmd(2, "weather clear 99999");
        cmd(2, "gamemode creative");
        // out to sea: open water of the Abecean (a fixed point; the map is the same for every seed)
        cmd(2, "gamemode spectator");
        step(2, mc -> {
            sea = new Vec3(SEA_X, 66, SEA_Z);
            mc.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.1f 70 %.1f", sea.x, sea.z));
        });
        step(400, mc -> shot(mc, "00_open_sea"));
        // under the sea: a Dwemer ruin with its dry chamber, a Yokudan ruin with its sea-ward, a Dreugh lair
        site("sea_dwemer_ruin", 0, 0, "10_dwemer_ruin_underwater", 3, 12, true);
        site("sea_yokudan_ruin", 48, 0, "11_yokudan_ruin_underwater", 5, 15, true);
        site("sea_dreugh_lair", 96, 0, "12_dreugh_lair", 2, 13, true);
        site("sea_nordic_grave", 0, 48, "13_nordic_ship_grave", 3, 11, true);
        site("sea_sload_spire", 48, 48, "14_sload_spire", 4, 17, true);
        site("sea_maormer_wreck", 96, 48, "15_maormer_wreck", 3, 13, true);
        // above the sea: islets
        site("islet_lighthouse", 0, 96, "20_islet_lighthouse", 14, 26, false);
        site("islet_pirate_cove", 48, 96, "21_islet_pirate_cove", 14, 26, false);
        site("islet_sea_stack", 96, 96, "22_islet_sea_stack", 10, 26, false);
        // the traffic: a pirate cog close by
        step(2, mc -> tpSea(mc, -60, 3, -60));
        cmd(60, "skycraft-seas ship pirate_cog");
        cmd(20, "gamemode spectator");
        step(20, mc -> lookAtShip(mc, true));
        step(60, mc -> shot(mc, "30_pirate_ship"));
        cmd(2, "skycraft-seas ship merchant");
        step(40, mc -> lookAtShip(mc, true));
        step(60, mc -> shot(mc, "31_merchant_ship"));
        // walk the deck of a sailing ship: stand on the merchant cog's deck while it sails
        cmd(2, "gamemode creative");
        step(2, mc -> {
            mc.player.getAbilities().flying = false;
            ShipEntity s = nearestShip(mc);
            if (s == null) return;
            deckShip = s.getId();
            Vec3 at = s.toWorld(0, 1.5, -1.2);
            mc.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f", at.x, at.y, at.z));
        });
        step(40, mc -> {
            mc.player.getAbilities().flying = false;
            deckStart = mc.player.position();
            Entity s = mc.level.getEntity(deckShip);
            Skycraft.LOGGER.info("Seas autotest: on deck {} (ship {}, at {})", DeckPhysics.onDeck(mc.player), s, mc.player.position());
        });
        step(2, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
        step(100, mc -> {
            Entity s = mc.level.getEntity(deckShip);
            if (s instanceof ShipEntity ship) {
                Vec3 local = ship.toLocal(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                Skycraft.LOGGER.info("Seas autotest: after 5 s on deck: onDeck={}, moved {} blocks with the ship (ship moved {}), local {}",
                        DeckPhysics.onDeck(mc.player), String.format(Locale.ROOT, "%.1f", mc.player.position().distanceTo(deckStart)),
                        String.format(Locale.ROOT, "%.1f", Math.hypot(ship.getX() - ship.xo, ship.getZ() - ship.zo) * 100), local);
            }
            shot(mc, "32_standing_on_sailing_deck");
        });
        step(2, mc -> mc.options.keyUp.setDown(true));
        step(25, mc -> mc.options.keyUp.setDown(false));
        step(10, mc -> {
            Skycraft.LOGGER.info("Seas autotest: walked on deck: onDeck={}, pos {}", DeckPhysics.onDeck(mc.player), mc.player.position());
            shot(mc, "33_walked_on_deck");
        });
        step(2, mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
        // a ghost ship by night
        cmd(2, "time set 18000");
        cmd(2, "gamemode spectator");
        cmd(2, "skycraft-seas ship ghost");
        step(40, mc -> lookAtShip(mc, false));
        step(60, mc -> shot(mc, "34_ghost_ship_night"));
        cmd(2, "time set 3000");
        // creatures of the sea, side by side in the water
        cmd(2, "gamemode spectator");
        step(2, mc -> tpSea(mc, 200, 2, 0));
        step(60, mc -> {
            String[] ids = {"dreugh", "dreugh_queen", "sea_netch", "giant_slaughterfish", "sea_crab", "seal", "whale", "sea_leviathan"};
            for (int i = 0; i < ids.length; i++) {
                mc.player.connection.sendCommand(String.format(Locale.ROOT, "summon skycraft:%s %.1f 60 %.1f {NoAI:1b}", ids[i],
                        sea.x + 200 - 18 + i * 5, sea.z + 14));
            }
            mc.player.connection.sendCommand(String.format(Locale.ROOT, "summon skycraft:hawk %.1f 66 %.1f {NoAI:1b}", sea.x + 204, sea.z + 8));
            for (int i = 0; i < 6; i++) {
                mc.player.connection.sendCommand(String.format(Locale.ROOT, "summon skycraft:abecean_longfin %.1f 58 %.1f {NoAI:1b}",
                        sea.x + 196 + i, sea.z + 8));
            }
        });
        step(2, mc -> {
            mc.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.1f 61 %.1f 0 10", sea.x + 200, sea.z - 6));
        });
        step(80, mc -> shot(mc, "40_sea_creatures"));
        step(2, mc -> {
            mc.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.1f 70 %.1f 0 -25", sea.x + 200, sea.z - 6));
            SkyFlocks.debugFlock("GULL", sea.x + 200, 80, sea.z + 14);
            SkyFlocks.debugFlock("CROW", sea.x + 210, 84, sea.z + 20);
        });
        step(100, mc -> shot(mc, "50_birds"));
        step(20, mc -> {
            Skycraft.LOGGER.info("Seas autotest finished; screenshots in {}", OUT.toAbsolutePath());
            mc.stop();
        });
    }

    private SeasAutotest() {}

    /** Builds a site in a sea chunk offset {@code dx} from the sea point, then looks at it from {@code back} out, {@code up} high. */
    private static void site(String kind, int dx, int dz, String shot, int up, int dist, boolean under) {
        step(2, mc -> tpSea(mc, dx, 2, dz));
        cmd(40, "gamemode creative");
        cmd(20, "skycraft-seas site " + kind);
        cmd(2, "gamemode spectator");
        step(40, mc -> {
            int cx = (((int) Math.floor(sea.x + dx)) >> 4 << 4) + 8, cz = (((int) Math.floor(sea.z + dz)) >> 4 << 4) + 8;
            double px = cx - dist, pz = cz - dist * 0.4;
            double y = under ? Math.min(60, seaBed(mc, (int) Math.floor(px), (int) Math.floor(pz)) + up) : 63 + up;
            float yaw = (float) Math.toDegrees(Math.atan2(-(cx - px), cz - pz));
            float pitch = under ? 12f : 25f;
            mc.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.1f %.1f %.1f %.1f %.1f", px, y, pz, yaw, pitch));
        });
        step(80, mc -> shot(mc, shot));
    }

    /** The sea bed under a column, found by looking down through the water (the client keeps no ocean-floor heightmap). */
    private static int seaBed(Minecraft mc, int x, int z) {
        net.minecraft.core.BlockPos.MutableBlockPos p = new net.minecraft.core.BlockPos.MutableBlockPos(x, 62, z);
        while (p.getY() > 0 && !mc.level.getFluidState(p).isEmpty()) p.move(0, -1, 0);
        return p.getY() + 1;
    }

    private static void tpSea(Minecraft mc, int dx, int up, int dz) {
        mc.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.1f %d %.1f", sea.x + dx, 63 + up, sea.z + dz));
    }

    private static ShipEntity nearestShip(Minecraft mc) {
        return nearestShip(mc, false);
    }

    private static ShipEntity nearestShip(Minecraft mc, boolean ghost) {
        ShipEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ShipEntity s && !s.isMoored() && (!ghost || s.getLivery() == com.skycraft.sailing.ShipLivery.GHOST)) {
                double d = s.distanceToSqr(mc.player);
                if (d < bestD) {
                    bestD = d;
                    best = s;
                }
            }
        }
        return best;
    }

    /** Frames the nearest ship (any, or with {@code anyKind} false the ghost ship) from off its beam. */
    private static void lookAtShip(Minecraft mc, boolean anyKind) {
        ShipEntity s = nearestShip(mc, !anyKind);
        if (s == null) return;
        Vec3 side = s.toWorld(-16, 7, -6);
        mc.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.1f %.1f %.1f", side.x, side.y, side.z));
        double dx = s.getX() - side.x, dz = s.getZ() - side.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.player.setYRot(yaw);
        mc.player.setXRot(18f);
        mc.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.1f %.1f %.1f %.1f 18", side.x, side.y, side.z, yaw));
    }

    private static void step(int wait, Consumer<Minecraft> action) {
        STEPS.add(new Step(wait, action));
    }

    private static void cmd(int wait, String command) {
        step(wait, mc -> {
            if (mc.player != null) mc.player.connection.sendCommand(command);
        });
    }

    private static void shot(Minecraft mc, String name) {
        com.skycraft.client.autotest.ReviewCaptures.capture(mc, OUT, name,
                output -> Skycraft.LOGGER.info("Seas autotest: saved {}", output.toAbsolutePath()));
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!ENABLED) return;
        if (com.skycraft.client.autotest.ReviewCaptures.pending() > 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) mc.gui.setScreen(new TitleScreen());
            if (!worldRequested && (mc.gui.screen() instanceof TitleScreen || mc.gui.screen() instanceof com.skycraft.client.screen.title.SkyTitleScreen)) {
                worldRequested = true;
                String name = "seastest-" + System.currentTimeMillis();
                LevelSettings settings = com.skycraft.client.autotest.ReviewWorlds.settings(name,  GameType.CREATIVE,  false,  Difficulty.NORMAL,  true,  WorldDataConfiguration.DEFAULT);
                com.skycraft.client.autotest.ReviewWorlds.create(mc, name,  settings,  new WorldOptions(20261011L, false, false), 
                        com.skycraft.client.autotest.ReviewWorlds::normal, mc.gui.screen());
            }
            return;
        }
        if (mc.player == null || stepIndex >= STEPS.size()) return;
        if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen || mc.gui.screen() instanceof com.skycraft.client.screen.title.SkyPauseScreen) {
            mc.gui.setScreen(null);
        }
        if (inWorld++ == 0) waitLeft = STEPS.get(0).delay();
        if (--waitLeft > 0) return;
        Step s = STEPS.get(stepIndex++);
        try {
            s.action().accept(mc);
        } catch (Throwable t) {
            Skycraft.LOGGER.warn("Seas autotest step {} failed", stepIndex - 1, t);
        }
        if (stepIndex < STEPS.size()) waitLeft = STEPS.get(stepIndex).delay();
    }
}
