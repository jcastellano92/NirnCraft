package com.skycraft.society.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.skycraft.Skycraft;
import com.skycraft.society.NpcLook;
import com.skycraft.society.NpcNames;
import com.skycraft.society.Outfit;
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

/**
 * Developer check of townsfolk looks: with {@code -Dskycraft.npcshowcase=true} (and a singleplayer world opened, e.g.
 * {@code --quickPlaySingleplayer}), lines up every race in several outfits in front of the player, takes screenshots
 * into {@code build/npc-showcase/}, then closes the client. Does nothing otherwise.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class NpcShowcase {
    /** On with {@code -Dskycraft.npcshowcase=true} or a file {@code npc-showcase.flag} in the game directory. */
    public static final boolean ENABLED = Boolean.getBoolean("skycraft.npcshowcase") || Files.exists(Paths.get("npc-showcase.flag"));
    /** Saved world the showcase opens from the title screen ({@code saves/npcws}), if it exists. */
    private static final String WORLD = "npcws";
    private static boolean opened;
    private static final Path DIR = Paths.get("build", "npc-showcase");
    private static int tick;
    private static int step;

    private NpcShowcase() {}

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!ENABLED) return;
        if (com.skycraft.client.autotest.ReviewCaptures.pending() > 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            if (step > 0) {
                Skycraft.LOGGER.warn("NPC showcase: left the world (screen {}), closing", mc.gui.screen());
                mc.stop();
                return;
            }
            if (!opened && mc.gui.screen() != null && mc.gui.overlay() == null && Files.isDirectory(Paths.get("saves", WORLD))) {
                opened = true;
                open(mc);
            }
            return;
        }
        if (++tick < 200 || tick % 60 != 0) return;
        Skycraft.LOGGER.info("NPC showcase step {} (screen {})", step, mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
        if (step == 0) chooseRace(mc);
        if (mc.gui.screen() instanceof com.skycraft.client.screen.RaceScreen && step > 0) mc.gui.setScreen(null);

        switch (step++) {
            case 0 -> {
                run(mc, "gamerule advance_time false");
                run(mc, "gamerule spawn_mobs false");
                run(mc, "time set 6000");
                run(mc, "weather clear");
                run(mc, "gamemode creative");
                run(mc, "tp @s ~ 200 ~ 0 14");
            }
            case 1 -> {
                // a stage in the sky: nothing in the way, plain background
                run(mc, "fill ~-10 199 ~-3 ~10 199 ~12 minecraft:smooth_stone");
                com.skycraft.client.autotest.ReviewWorlds.hudHidden(mc,true);
            }
            case 2 -> lineup(mc, 0);
            case 3 -> shot(mc, "01_humans");
            case 4 -> clear(mc);
            case 5 -> lineup(mc, 1);
            case 6 -> shot(mc, "02_mer_and_beastfolk");
            case 7 -> clear(mc);
            case 8 -> lineup(mc, 2);
            case 9 -> shot(mc, "03_trades");
            case 10 -> clear(mc);
            case 11 -> lineup(mc, 3);
            case 12 -> shot(mc, "04_backs");
            case 13 -> clear(mc);
            case 14 -> lineup(mc, 4);
            case 15 -> shot(mc, "05_closeup");
            case 16 -> {
                clear(mc);
                com.skycraft.client.autotest.ReviewWorlds.hudHidden(mc,false);
                run(mc, "execute at @s positioned over motion_blocking_no_leaves run tp @s ~ ~1 ~ 0 25");
            }
            case 17 -> run(mc, "execute positioned ^ ^ ^9 run skycraft-society populate town");
            case 18, 19 -> {
            }
            case 20 -> shot(mc, "06_town");
            default -> mc.stop();
        }
    }

    /** Sends the lineup into the void (no death puffs in the next picture). */
    private static void clear(Minecraft mc) {
        run(mc, "tp @e[type=skycraft:npc] ~ -300 ~");
    }

    /** Runs a command as the player with full permissions on the integrated server (the test world has no cheats). */
    private static void run(Minecraft mc, String cmd) {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        java.util.UUID id = mc.player.getUUID();
        server.execute(() -> {
            var sp = server.getPlayerList().getPlayer(id);
            if (sp != null) server.getCommands().performPrefixedCommand(sp.createCommandSourceStack().withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS), cmd);
        });
    }

    /** Picks a race for the test player so character creation doesn't cover the screenshots. */
    private static void chooseRace(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        java.util.UUID id = mc.player.getUUID();
        server.execute(() -> {
            var sp = server.getPlayerList().getPlayer(id);
            if (sp != null) com.skycraft.skills.Progression.chooseRace(sp, com.skycraft.core.Race.NORD);
        });
    }

    /** Opens the test world, skipping the backup / experimental-settings prompts (dev tool only). */
    private static void open(Minecraft mc) {
        var flows = mc.createWorldOpenFlows();
        try {
            for (var m : flows.getClass().getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 4 && p[0] == net.minecraft.client.gui.screens.Screen.class && p[1] == String.class
                        && p[2] == boolean.class && p[3] == boolean.class) {
                    m.setAccessible(true);
                    m.invoke(flows, mc.gui.screen(), WORLD, false, false);
                    return;
                }
            }
        } catch (Exception e) {
            Skycraft.LOGGER.warn("NPC showcase: direct world load failed, using the normal flow", e);
        }
        com.skycraft.client.autotest.ReviewWorlds.open(mc,WORLD,mc.gui.screen());
    }

    private static void lineup(Minecraft mc, int set) {
        List<Integer> looks = new ArrayList<>();
        NpcNames.Race[] races = NpcNames.Race.values();
        switch (set) {
            case 0 -> {
                looks.add(NpcLook.pack(NpcNames.Race.NORD, false, 0, NpcLook.SHOULDER, 5, NpcLook.FULL, Outfit.NORD_FUR, 0, false));
                looks.add(NpcLook.pack(NpcNames.Race.NORD, true, 1, NpcLook.BRAIDS, 7, 0, Outfit.FARM, 1, false));
                looks.add(NpcLook.pack(NpcNames.Race.IMPERIAL, false, 1, NpcLook.SHORT, 1, NpcLook.SHORT_BEARD, Outfit.IMPERIAL, 0, false));
                looks.add(NpcLook.pack(NpcNames.Race.IMPERIAL, true, 2, NpcLook.BUN, 2, 0, Outfit.FINE, 0, false));
                looks.add(NpcLook.pack(NpcNames.Race.BRETON, false, 0, NpcLook.PONYTAIL, 8, NpcLook.GOATEE, Outfit.BRETON, 0, false));
                looks.add(NpcLook.pack(NpcNames.Race.BRETON, true, 0, NpcLook.LONG, 3, 0, Outfit.FINE, 1, false));
                looks.add(NpcLook.pack(NpcNames.Race.REDGUARD, false, 2, NpcLook.SHAVED, 0, NpcLook.GOATEE, Outfit.REDGUARD, 0, false));
                looks.add(NpcLook.pack(NpcNames.Race.REDGUARD, true, 1, NpcLook.BUN, 0, 0, Outfit.REDGUARD, 1, false));
                looks.add(NpcLook.pack(NpcNames.Race.NORD, false, 1, NpcLook.SHORT, 5, 0, Outfit.CHILD, 0, true));
            }
            case 1 -> {
                looks.add(NpcLook.pack(NpcNames.Race.ALTMER, false, 0, NpcLook.PONYTAIL, 6, 0, Outfit.ALTMER, 0, false));
                looks.add(NpcLook.pack(NpcNames.Race.ALTMER, true, 1, NpcLook.LONG, 5, 0, Outfit.THALMOR, 0, false));
                looks.add(NpcLook.pack(NpcNames.Race.BOSMER, false, 1, NpcLook.SHAVED, 2, 0, Outfit.BOSMER, 0, false));
                looks.add(NpcLook.pack(NpcNames.Race.DUNMER, false, 1, NpcLook.MOHAWK, 0, NpcLook.GOATEE, Outfit.DUNMER, 0, false));
                looks.add(NpcLook.pack(NpcNames.Race.DUNMER, true, 2, NpcLook.SHOULDER, 7, 0, Outfit.DUNMER, 2, false));
                looks.add(NpcLook.pack(NpcNames.Race.ORC, false, 1, NpcLook.MOHAWK, 0, NpcLook.BRAIDED, Outfit.ORC, 0, false));
                looks.add(NpcLook.pack(NpcNames.Race.KHAJIIT, false, 0, NpcLook.KHAJIIT_MANE, 2, 0, Outfit.CARAVAN, 0, false));
                looks.add(NpcLook.pack(NpcNames.Race.KHAJIIT, true, 1, NpcLook.KHAJIIT_BRAIDS, 1, 0, Outfit.KHAJIIT, 1, false));
                looks.add(NpcLook.pack(NpcNames.Race.ARGONIAN, false, 0, NpcLook.ARGONIAN_CREST, 12, 0, Outfit.ARGONIAN, 0, false));
                looks.add(NpcLook.pack(NpcNames.Race.ARGONIAN, true, 2, NpcLook.ARGONIAN_FRILLS, 13, 0, Outfit.FARM, 2, false));
            }
            case 2 -> {
                Outfit[] trades = {Outfit.BLACKSMITH, Outfit.MERCHANT, Outfit.PRIEST, Outfit.MAGE, Outfit.BEGGAR, Outfit.JARL, Outfit.STEWARD,
                        Outfit.MINER, Outfit.HUNTER, Outfit.BARD, Outfit.INNKEEPER, Outfit.SCHOLAR};
                for (int i = 0; i < trades.length; i++) {
                    NpcNames.Race race = races[i % races.length];
                    looks.add(NpcLook.pack(race, i % 3 == 1, i % 4, i % 2 == 0 ? NpcLook.SHORT : NpcLook.LONG, i % 12, i % 2 == 0 ? NpcLook.FULL : 0,
                            trades[i], i % 3, false));
                }
            }
            case 4 -> {
                looks.add(NpcLook.pack(NpcNames.Race.KHAJIIT, false, 0, NpcLook.KHAJIIT_MANE, 2, 0, Outfit.CARAVAN, 1, false));
                looks.add(NpcLook.pack(NpcNames.Race.ARGONIAN, false, 2, NpcLook.ARGONIAN_CREST, 12, 0, Outfit.ARGONIAN, 1, false));
                looks.add(NpcLook.pack(NpcNames.Race.ORC, false, 0, NpcLook.MOHAWK, 0, NpcLook.FULL, Outfit.BLACKSMITH, 0, false));
                looks.add(NpcLook.pack(NpcNames.Race.ALTMER, true, 0, NpcLook.BUN, 5, 0, Outfit.ALTMER, 2, false));
            }
            default -> {
                for (int i = 0; i < races.length; i++) {
                    looks.add(NpcLook.pack(races[i], i % 2 == 1, i % 4, races[i] == NpcNames.Race.KHAJIIT ? NpcLook.KHAJIIT_MANE
                            : races[i] == NpcNames.Race.ARGONIAN ? NpcLook.ARGONIAN_SPINES : NpcLook.LONG, i, 0, Outfit.cultural(races[i]), 0, false));
                }
            }
        }
        int n = looks.size();
        for (int i = 0; i < n; i++) {
            double x = -(i - (n - 1) / 2.0) * (set == 4 ? 1.2 : 1.0);
            int look = looks.get(i);
            // the player looks south: the lineup stands south of them, facing them (or away, for the backs)
            String z = set == 4 ? "~3" : "~6";
            float yaw = set == 3 ? 0f : 180f;
            run(mc, String.format(java.util.Locale.ROOT,
                    "summon skycraft:npc ~%.2f ~ %s {Role:\"%s\",Look:%d,SkycraftInit:1b,NoAI:1b,Rotation:[%.0ff,0f],Skin:%d}",
                    x, z, NpcLook.child(look) ? "child" : "citizen", look, yaw, NpcLook.female(look) ? 1 : 0));
        }
    }

    private static void shot(Minecraft mc, String name) {
        com.skycraft.client.autotest.ReviewCaptures.capture(mc, DIR, name,
                output -> Skycraft.LOGGER.info("NPC showcase: saved {}", output.toAbsolutePath()));
    }
}
