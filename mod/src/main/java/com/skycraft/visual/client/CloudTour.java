package com.skycraft.visual.client;

import com.skycraft.Skycraft;
import com.skycraft.atmosphere.AtmosphereConfig;
import com.skycraft.atmosphere.client.ClientWeather;
import com.skycraft.atmosphere.client.CloudRenderer;
import com.skycraft.atmosphere.weather.RegionalWeather;
import com.skycraft.atmosphere.weather.WeatherKind;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The cloud and distant-weather tour of {@link VisualAutotest} ({@code -Dskycraft.visualtest=clouds}): flies a
 * spectator over Skyrim, Vvardenfell and Hammerfell, sets the regional weather around (and away from) the camera,
 * and screenshots every cloud type, the distant storms and the dawn fog. Finishes by timing frames with Skycraft
 * clouds on and off and writes the numbers next to the screenshots ({@code clouds-perf.txt}).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
final class CloudTour {
    private static final List<String> PERF = new ArrayList<>();
    private static boolean timing;
    private static long timingStart;
    private static int timingFrames;

    private CloudTour() {}

    static void steps() {
        VisualAutotest.step(100, mc -> {
            // no Helgen cart: the tour moves the camera itself
            if (com.skycraft.quest.QuestConfig.HELGEN_START.get()) com.skycraft.quest.QuestConfig.HELGEN_START.set(false);
            com.skycraft.network.SkyNetwork.sendToServer(new com.skycraft.network.CorePackets.ChooseRace(com.skycraft.core.Race.NORD.id()));
            mc.gui.setScreen(null);
            com.skycraft.client.autotest.ReviewWorlds.hudHidden(mc, true);
            mc.options.pauseOnLostFocus = false;
            mc.getWindow().setWindowed(1600,900);
        });
        VisualAutotest.cmd(2, "gamerule advance_time false");
        VisualAutotest.cmd(2, "gamerule advance_weather false");
        VisualAutotest.cmd(2, "gamerule spawn_mobs false");
        VisualAutotest.cmd(2, "gamemode spectator");
        VisualAutotest.cmd(2, "time set 5000");

        // ---- Whiterun: the fair-weather sky, then vanilla's for comparison
        view(0, 0, 60, 180f, -6f);
        all("cloudy");
        shot(500, "c01_cumulus_whiterun");
        VisualAutotest.step(2, mc -> AtmosphereConfig.CLOUDS.set(false));
        shot(60, "c00_vanilla_clouds_reference");
        VisualAutotest.step(2, mc -> AtmosphereConfig.CLOUDS.set(true));
        all("clear");
        look(-40f, 180f);
        shot(140, "c02_clear_cirrus");
        all("overcast");
        look(-8f, 180f);
        shot(140, "c03_overcast_deck");
        all("gale");
        shot(140, "c04_gale_fractus");

        // ---- a storm over the Rift, seen from Whiterun; then at dusk for the lightning
        all("clear");
        region(2435, 1418, "storm");
        lookAt(0, 0, 2435, 1418, -4f);
        shot(160, "c05_distant_storm_rift");
        region(-795, 1250, "rain");
        lookAt(0, 0, -795, 1250, -3f);
        shot(140, "c06_distant_rain_falkreath");
        VisualAutotest.cmd(2, "time set 13800");
        lookAt(0, 0, 2435, 1418, -4f);
        VisualAutotest.step(120, mc -> {});
        for (int i = 0; i < 4; i++) {
            VisualAutotest.step(30, mc -> CloudRenderer.testLightning());
            shot(2, "c07_storm_lightning_" + i);
        }

        // ---- sunset and night
        all("cloudy");
        VisualAutotest.cmd(2, "time set 12300");
        look(-4f, 90f);
        shot(160, "c08_sunset_cumulus");
        VisualAutotest.cmd(2, "time set 23300");
        look(-4f, -90f);
        shot(120, "c09_sunrise_cumulus");
        VisualAutotest.cmd(2, "time set 18000");
        look(-12f, 180f);
        shot(120, "c10_night_clouds");

        // ---- snow squalls on the northern mountains, seen from Whiterun
        VisualAutotest.cmd(2, "time set 5000");
        all("clear");
        region(174, -1801, "blizzard");
        region(1417, -1749, "snow");
        region(-928, -1176, "snow");
        look(-2f, 180f);
        shot(160, "c11_snow_squalls_north");

        // ---- the Throat of the World wearing its cap
        all("cloudy");
        view(380, 860, 40, 0f, 0f);
        lookAt(380, 860, 579, 652, -32f);
        shot(500, "c12_throat_cap_cloud");

        // ---- dawn mist over the Hjaalmarch marshes
        VisualAutotest.cmd(2, "time set 23500");
        view(-928, -1176, 30, 0f, 12f);
        all("clear");
        shot(500, "c13_dawn_marsh_fog");
        view(-928, -1176, 140, 0f, 35f);
        shot(260, "c14_marsh_fog_from_above");

        // ---- Vvardenfell: the Red Mountain plume, then an ash storm wall
        VisualAutotest.cmd(2, "time set 6000");
        view(6100, -500, 50, 0f, 0f);
        all("ashfall");
        lookAt(6100, -500, 7100, -400, -8f);
        shot(600, "c15_red_mountain_plume");
        all("clear");
        region(7100, -400, "ash_storm");
        shot(160, "c16_ash_storm_wall");
        region(7100, -400, "blight");
        shot(160, "c17_blight_storm");

        // ---- the Alik'r: desert haze and a sandstorm marching in
        view(-6400, 5700, 50, 90f, 0f);
        all("dust");
        shot(600, "c18_desert_haze");
        nearestOther(-6400, 5700, "clear", "sandstorm");
        shot(160, "c19_sandstorm_wall");

        // ---- above the deck
        view(0, 0, 0, 0f, 25f);
        VisualAutotest.cmd(2, "tp @s 0 430 0 0 25");
        all("overcast");
        shot(400, "c20_above_overcast");

        // ---- frame time: Whiterun, cumulus plus a distant storm, clouds on vs off
        view(0, 0, 60, 135f, -5f);
        all("cloudy");
        region(2435, 1418, "storm");
        VisualAutotest.step(700, mc -> {
            mc.options.enableVsync().set(false);
            
            mc.options.framerateLimit().set(260);
            mc.getFramerateLimitTracker().setFramerateLimit(260);
        });
        time(200, "skycraft clouds");
        VisualAutotest.step(2, mc -> AtmosphereConfig.CLOUDS.set(false));
        time(200, "vanilla clouds");
        VisualAutotest.step(2, mc -> {
            AtmosphereConfig.CLOUDS.set(true);
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
        });
        time(200, "no clouds");
        VisualAutotest.step(2, mc -> mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.FANCY));
        time(200, "skycraft clouds (again)");
        VisualAutotest.step(20, mc -> {
            try {
                Files.createDirectories(VisualAutotest.OUT);
                Files.write(VisualAutotest.OUT.resolve("clouds-perf.txt"), PERF);
            } catch (Exception e) {
                Skycraft.LOGGER.warn("Cloud tour: could not write the timings", e);
            }
            PERF.forEach(Skycraft.LOGGER::info);
            Skycraft.LOGGER.info("Cloud tour finished; screenshots in {}", VisualAutotest.OUT.toAbsolutePath());
            mc.stop();
        });
    }

    // ------------------------------------------------------------------ steps

    private static void shot(int wait, String name) {
        VisualAutotest.step(wait, mc -> VisualAutotest.shot(mc, name));
    }

    private static void look(float pitch, float yaw) {
        VisualAutotest.step(2, mc -> VisualAutotest.look(mc, yaw, pitch));
    }

    private static void lookAt(int fromX, int fromZ, int toX, int toZ, float pitch) {
        float yaw = (float) Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ));
        look(pitch, yaw);
    }

    /** Teleports to {@code up} blocks over the ground at (x, z) (the generator's estimate before the chunk exists). */
    private static void view(int x, int z, int up, float yaw, float pitch) {
        VisualAutotest.step(2, mc -> server(mc, server -> {
            ServerLevel level = server.overworld();
            boolean loaded = level.getChunkSource().hasChunk(x >> 4, z >> 4);
            int h = loaded ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
                    : level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level,
                    level.getChunkSource().randomState());
            String name = mc.player.getGameProfile().name();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                    String.format(Locale.ROOT, "tp %s %d %d %d %.0f %.0f", name, x, h + up, z, yaw, pitch));
        }));
    }

    /** Every region within 2 km of the player gets {@code kind}. */
    private static void all(String kind) {
        VisualAutotest.step(2, mc -> server(mc, server -> {
            WeatherKind k = WeatherKind.byId(kind);
            Set<String> done = new HashSet<>();
            int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
            for (int dx = -2200; dx <= 2200; dx += 128) {
                for (int dz = -2200; dz <= 2200; dz += 128) {
                    Region r = TamrielMap.region(px + dx, pz + dz);
                    if (done.add(r.id())) RegionalWeather.set(server, r, k, 20 * 60 * 60);
                }
            }
        }));
        settle();
    }

    /** The region at (x, z) gets {@code kind}. */
    private static void region(int x, int z, String kind) {
        VisualAutotest.step(2, mc -> server(mc, server -> RegionalWeather.set(server, TamrielMap.region(x, z), WeatherKind.byId(kind), 20 * 60 * 60)));
        settle();
    }

    /** The player's region gets {@code here}; the nearest other region (within 1.5 km) gets {@code there}, and we look at it. */
    private static void nearestOther(int x, int z, String here, String there) {
        VisualAutotest.step(2, mc -> {
            Region own = TamrielMap.region(x, z);
            for (int r = 200; r <= 1500; r += 100) {
                for (int a = 0; a < 32; a++) {
                    double ang = Math.PI * 2 * a / 32;
                    int tx = x + (int) (Math.cos(ang) * r), tz = z + (int) (Math.sin(ang) * r);
                    Region other = TamrielMap.region(tx, tz);
                    if (other.id().equals(own.id()) || other.id().startsWith("sea/")) continue;
                    Skycraft.LOGGER.info("Cloud tour: {} -> {} at {} {}", own.id(), other.id(), tx, tz);
                    server(mc, server -> {
                        RegionalWeather.set(server, own, WeatherKind.byId(here), 20 * 60 * 60);
                        RegionalWeather.set(server, other, WeatherKind.byId(there), 20 * 60 * 60);
                    });
                    VisualAutotest.look(mc, (float) Math.toDegrees(Math.atan2(-(tx - x), tz - z)), -3f);
                    return;
                }
            }
        });
        settle();
    }

    /** Lets the weather sync arrive, then snaps the eased weather so the shot shows it fully formed. */
    private static void settle() {
        VisualAutotest.step(20, mc -> ClientWeather.snapForTest());
    }

    private static void time(int frames, String label) {
        VisualAutotest.step(60, mc -> {
            CloudRenderer.resetStats();
            timing = true;
            timingFrames = 0;
            timingStart = System.nanoTime();
        });
        VisualAutotest.step(frames / 2, mc -> {
            CloudRenderer.stopStats();
            timing = false;
            double ms = (System.nanoTime() - timingStart) / 1e6 / Math.max(1, timingFrames);
            PERF.add(String.format(Locale.ROOT, "%s: %.2f ms per frame (%.0f fps) over %d frames; %s", label, ms, 1000 / ms,
                    timingFrames, CloudRenderer.stats()));
        });
    }

    private static void server(Minecraft mc, java.util.function.Consumer<MinecraftServer> task) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        server.execute(() -> task.accept(server));
    }

    @SubscribeEvent
    public static void onRenderTick(net.neoforged.neoforge.client.event.RenderFrameEvent.Post event) {
        if (timing) timingFrames++;
    }
}
