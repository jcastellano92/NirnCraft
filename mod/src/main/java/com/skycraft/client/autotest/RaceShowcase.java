package com.skycraft.client.autotest;

import com.mojang.blaze3d.platform.NativeImage;
import com.skycraft.Skycraft;
import com.skycraft.client.RaceSkins;
import com.skycraft.client.screen.RaceScreen;
import com.skycraft.core.Race;
import com.skycraft.society.NpcLook;
import com.skycraft.society.NpcNames;
import com.skycraft.society.Outfit;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
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

/**
 * Developer check of the races: with {@code -Dskycraft.raceshowcase=true} the client creates a flat creative world,
 * sizes the window to a Steam Deck (1280x800 at GUI scale 3 = 427x267), and screenshots the character creator on
 * several races and both tabs, the player in third person wearing several race looks, and line-ups of townsfolk of
 * every race and sub-people, into {@code build/race-showcase/}; then closes the client. Does nothing otherwise.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class RaceShowcase {
    public static final boolean ENABLED = Boolean.getBoolean("skycraft.raceshowcase");
    private static final String WORLD = "raceshowcase";
    private static final Path DIR = Paths.get("build", "race-showcase");
    private static boolean started;
    private static int tick;
    private static int step;
    private static final List<Runnable> STEPS = new ArrayList<>();

    private RaceShowcase() {}

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!ENABLED) return;
        Minecraft mc = Minecraft.getInstance();
        if(com.skycraft.client.autotest.ReviewCaptures.pending()>0)return;
        if (mc.level == null || mc.player == null) {
            if (!started && mc.gui.screen() != null && mc.gui.overlay() == null
                    && (mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen
                    || mc.gui.screen() instanceof com.skycraft.client.screen.title.SkyTitleScreen)) {
                started = true;
                createWorld(mc);
            } else if (step > 0) {
                mc.stop();
            }
            return;
        }
        if (++tick < 160 || tick % 30 != 0) return;
        if (STEPS.isEmpty()) plan(mc);
        if (step < STEPS.size()) {
            try {
                STEPS.get(step).run();
            } catch (Exception e) {
                Skycraft.LOGGER.warn("Race showcase step {} failed", step, e);
            }
            step++;
        } else {
            Skycraft.LOGGER.info("Race showcase done");
            mc.stop();
        }
    }

    private static void createWorld(Minecraft mc) {
        try {
            if (mc.getLevelSource().levelExists(WORLD)) {
                try (var access = mc.getLevelSource().createAccess(WORLD)) {
                    access.deleteLevel();
                }
            }
            var settings = ReviewWorlds.settings(WORLD, net.minecraft.world.level.GameType.CREATIVE, false,
                    net.minecraft.world.Difficulty.PEACEFUL, true, net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
            ReviewWorlds.create(mc,WORLD, settings, new net.minecraft.world.level.levelgen.WorldOptions(77L, false, false),
                    access -> access.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                            .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).value().createWorldDimensions(),mc.gui.screen());
        } catch (Exception e) {
            Skycraft.LOGGER.error("Race showcase: could not create the world", e);
        }
    }

    private static void plan(Minecraft mc) {
        STEPS.add(() -> {
            
            mc.getWindow().setWindowed(1280,800);
            mc.options.guiScale().set(3);
            run(mc, "gamerule advance_time false");
            run(mc, "gamerule spawn_mobs false");
            run(mc, "time set 6000");
            run(mc, "weather clear");
            choose(mc);
        });
        STEPS.add(mc::resizeGui);
        // the character creator on a few races, both tabs
        Object[][] screens = {{Race.NORD, false}, {Race.TSAESCI, false}, {Race.KHAJIIT, true}, {Race.LILMOTHIIT, false},
                {Race.KA_PO_TUN, true}, {Race.TANG_MO, false}, {Race.FALMER, true}, {Race.ARGONIAN, true}, {Race.DWEMER, false}};
        for (Object[] s : screens) {
            Race race = (Race) s[0];
            boolean look = (Boolean) s[1];
            STEPS.add(() -> {
                RaceScreen.preselect(race, look);
                mc.gui.setScreen(new RaceScreen());
            });
            STEPS.add(() -> {
            });
            STEPS.add(() -> shot(mc, "screen_" + race.id() + (look ? "_look" : "")));
        }
        STEPS.add(() -> mc.gui.setScreen(null));
        // the player in third person, front and back
        STEPS.add(() -> {
            com.skycraft.client.autotest.ReviewWorlds.hudHidden(mc, true);
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            run(mc, "tp @s 0 -60 0 180 10");
        });
        int[][] players = {
                {Race.TSAESCI.ordinal(), 0, 0}, {Race.KA_PO_TUN.ordinal(), 0, 1}, {Race.TANG_MO.ordinal(), 0, 0},
                {Race.LILMOTHIIT.ordinal(), 0, 1}, {Race.IMGA.ordinal(), 0, 0}, {Race.ARGONIAN.ordinal(), 1, 0},
                {Race.KHAJIIT.ordinal(), 1, 1}, {Race.MAORMER.ordinal(), 0, 1}};
        for (int[] p : players) {
            Race race = Race.VALUES[p[0]];
            NpcNames.Race nr = NpcNames.Race.of(race);
            int look = NpcLook.withRaceVariant(NpcLook.random(nr, p[2] == 1, false, Outfit.cultural(nr),
                    net.minecraft.util.RandomSource.create(p[0] * 31L)), p[1]);
            STEPS.add(() -> {
                mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                RaceSkins.setPreviewLook(look);
            });
            STEPS.add(() -> shot(mc, "player_" + race.id() + "_front"));
            STEPS.add(() -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            STEPS.add(() -> shot(mc, "player_" + race.id() + "_back"));
        }
        STEPS.add(() -> {
            RaceSkins.setPreviewLook(NpcLook.UNSET);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            run(mc, "tp @s 0 -60 0 0 12");
        });
        // townsfolk of every race: two line-ups, then the sub-peoples; front and back
        NpcNames.Race[] all = NpcNames.Race.VALUES;
        List<List<Integer>> sets = new ArrayList<>();
        List<Integer> a = new ArrayList<>(), b = new ArrayList<>(), v = new ArrayList<>();
        for (int i = 0; i < all.length; i++) {
            NpcNames.Race r = all[i];
            int look = NpcLook.random(r, i % 3 == 1, false, Outfit.cultural(r), net.minecraft.util.RandomSource.create(1000L + i));
            (i < 12 ? a : b).add(NpcLook.withRaceVariant(look, 0));
        }
        int[][] variants = {{NpcNames.Race.IMPERIAL.ordinal(), 0}, {NpcNames.Race.IMPERIAL.ordinal(), 1}, {NpcNames.Race.ORC.ordinal(), 0},
                {NpcNames.Race.ORC.ordinal(), 1}, {NpcNames.Race.KHAJIIT.ordinal(), 0}, {NpcNames.Race.KHAJIIT.ordinal(), 1},
                {NpcNames.Race.ARGONIAN.ordinal(), 0}, {NpcNames.Race.ARGONIAN.ordinal(), 1}, {NpcNames.Race.ARGONIAN.ordinal(), 2}};
        for (int[] x : variants) {
            NpcNames.Race r = all[x[0]];
            int look = NpcLook.random(r, false, false, Outfit.cultural(r), net.minecraft.util.RandomSource.create(50L + x[0]));
            v.add(NpcLook.withRaceVariant(look, x[1]));
        }
        sets.add(a);
        sets.add(b);
        sets.add(v);
        for (int s = 0; s < sets.size(); s++) {
            List<Integer> looks = sets.get(s);
            int set = s;
            STEPS.add(() -> lineup(mc, looks, 180f));
            STEPS.add(() -> {
            });
            STEPS.add(() -> shot(mc, "npcs_" + set + "_front"));
            STEPS.add(() -> run(mc, "tp @e[type=skycraft:npc] ~ -300 ~"));
            STEPS.add(() -> lineup(mc, looks, 0f));
            STEPS.add(() -> {
            });
            STEPS.add(() -> shot(mc, "npcs_" + set + "_back"));
            STEPS.add(() -> run(mc, "tp @e[type=skycraft:npc] ~ -300 ~"));
        }
    }

    private static void choose(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        java.util.UUID id = mc.player.getUUID();
        server.execute(() -> {
            var sp = server.getPlayerList().getPlayer(id);
            if (sp != null) com.skycraft.skills.Progression.chooseRace(sp, Race.NORD);
        });
    }

    /** NPCs in a row in front of the player (who stands at 0, -60, 0 looking south). */
    private static void lineup(Minecraft mc, List<Integer> looks, float yaw) {
        int n = looks.size();
        for (int i = 0; i < n; i++) {
            double x = -(i - (n - 1) / 2.0) * 1.05;
            int look = looks.get(i);
            run(mc, String.format(Locale.ROOT,
                    "summon skycraft:npc %.2f -60 9 {Role:\"citizen\",Look:%d,SkycraftInit:1b,NoAI:1b,Rotation:[%.0ff,0f],Skin:%d}",
                    x, look, yaw, NpcLook.female(look) ? 1 : 0));
        }
    }

    private static void run(Minecraft mc, String cmd) {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        java.util.UUID id = mc.player.getUUID();
        server.execute(() -> {
            var sp = server.getPlayerList().getPlayer(id);
            if (sp != null) server.getCommands().performPrefixedCommand(sp.createCommandSourceStack().withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS), cmd);
        });
    }

    private static void shot(Minecraft mc, String name) {
        try {
            Files.createDirectories(DIR);
            com.skycraft.client.autotest.ReviewCaptures.capture(mc,DIR,name,null,null);
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Race showcase screenshot failed", e);
        }
    }
}
