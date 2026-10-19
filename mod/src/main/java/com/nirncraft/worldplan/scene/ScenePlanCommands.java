package com.nirncraft.worldplan.scene;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.nirncraft.NirnCraft;
import com.nirncraft.bake.Bake;
import com.nirncraft.perf.PerfAutorun;
import com.nirncraft.worldplan.RoadPlan;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads the scene plan at startup (on a background thread, after the road plan) and gives developers
 * {@code /nirncraft-sceneplan}: {@code stats} (scenes per anchor, density per km of road and per km²), {@code near <x> <z>}
 * (the nearest scene, with its reason and props), {@code list <anchor> [n]} (coordinates for renders) and
 * {@code verify} (plans live and compares byte for byte). Autorun task {@code sceneplan} = stats.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class ScenePlanCommands {
    private ScenePlanCommands() {}

    @SubscribeEvent
    public static void onAboutToStart(ServerAboutToStartEvent event) {
        PerfAutorun.register("sceneplan", (t, done) -> {
            for (String line : stats(ScenePlan.get()).split("\n")) NirnCraft.LOGGER.info("[sceneplan] {}", line);
            done.run();
        });
        if (!com.nirncraft.tamriel.terrain.TamrielSpawn.isTamriel(event.getServer())) return;
        Thread t = new Thread(() -> {
            try {
                RoadPlan.warm();
                ScenePlan.warm();
            } catch (Throwable e) {
                NirnCraft.LOGGER.error("[sceneplan] loading the scene plan failed", e);
            }
        }, "NirnCraft scene plan loader");
        t.setDaemon(true);
        t.start();
    }

    static String stats(ScenePlan p) {
        int[] per = new int[ScenePlan.ANCHOR_NAMES.length];
        int[] roadPerClass = new int[4];
        int signs = 0, notes = 0, paths = 0, lm = 0;
        Map<String, Integer> kinds = new TreeMap<>();
        RoadPlan rp = RoadPlan.get();
        for (int s = 0; s < p.scenes(); s++) {
            per[p.anchor[s]]++;
            kinds.merge(p.kindOf(s), 1, Integer::sum);
            if (p.pathX[s] != ScenePlan.NO_PATH) paths++;
            if (p.ref[s].startsWith("seg:")) {
                int seg = Integer.parseInt(p.ref[s].substring(4, p.ref[s].indexOf(':', 4)));
                if (seg >= 0 && seg < rp.segments()) roadPerClass[Math.max(0, Math.min(3, rp.segKind[seg]))]++;
            }
            for (int q = p.propOff[s]; q < p.propOff[s + 1]; q++) {
                String k = p.propKindOf(q);
                if (k.equals(ScenePlan.SIGN)) signs++;
                else if (k.equals(ScenePlan.NOTE)) notes++;
                else if (k.startsWith(ScenePlan.LANDMARK_PREFIX)) lm++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "scene plan: %d scenes, %d props (%d landmark vignettes, %d clue signs, %d notes), %d worn paths%n",
                p.scenes(), p.props(), lm, signs, notes, paths));
        sb.append("per anchor:");
        for (int a = 0; a < per.length; a++) sb.append(' ').append(ScenePlan.ANCHOR_NAMES[a]).append('=').append(per[a]);
        sb.append('\n');
        float main = p.roadKm[0] + p.roadKm[1];
        sb.append(String.format(Locale.ROOT, "waysides per km of road: highway+road %.2f (%d over %.0f km; target 0.33-0.67), lane %.2f (%d / %.0f km; target 0.25-0.71), trail %.2f (%d / %.0f km; target 0.17-0.29)%n",
                main <= 0 ? 0 : (roadPerClass[0] + roadPerClass[1]) / main, roadPerClass[0] + roadPerClass[1], main,
                p.roadKm[2] <= 0 ? 0 : roadPerClass[2] / p.roadKm[2], roadPerClass[2], p.roadKm[2],
                p.roadKm[3] <= 0 ? 0 : roadPerClass[3] / p.roadKm[3], roadPerClass[3], p.roadKm[3]));
        sb.append(String.format(Locale.ROOT, "scenes per km² of land: %.2f (%d over %.0f km²; target 5-15)%n",
                p.landKm2 <= 0 ? 0 : p.scenes() / p.landKm2, p.scenes(), p.landKm2));
        sb.append("kinds: ").append(kinds);
        return sb.toString();
    }

    static String describe(ScenePlan p, int s) {
        StringBuilder props = new StringBuilder();
        for (int q = p.propOff[s]; q < p.propOff[s + 1]; q++) {
            props.append(q == p.propOff[s] ? "" : ", ").append(p.propKindOf(q)).append('@').append(p.propDx[q]).append(',').append(p.propDz[q]);
        }
        return String.format(Locale.ROOT, "#%d %s at %d %d (h %.1f, facing %d, r %d) anchor %s %s; reason: %s; props: %s%s%s", s, p.kindOf(s),
                p.x[s], p.z[s], p.h[s], p.facing[s], p.radius[s], ScenePlan.ANCHOR_NAMES[p.anchor[s]], p.ref[s], p.reason[s], props,
                p.clue[s].isEmpty() ? "" : "; clue: " + p.clue[s], p.note[s].isEmpty() ? "" : "; note: " + p.note[s]);
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("nirncraft-sceneplan").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("stats").executes(c -> {
                    String s = stats(ScenePlan.get());
                    for (String line : s.split("\n")) c.getSource().sendSuccess(() -> Component.literal(line), false);
                    return 1;
                }))
                .then(Commands.literal("near").then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer()).executes(c -> {
                            ScenePlan p = ScenePlan.get();
                            int s = p.nearest(IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"), 4000);
                            String msg = s < 0 ? "No scene within 4000 blocks." : describe(p, s);
                            NirnCraft.LOGGER.info("[sceneplan] {}", msg);
                            c.getSource().sendSuccess(() -> Component.literal(msg), false);
                            return 1;
                        }))))
                .then(Commands.literal("list").then(Commands.argument("anchor", StringArgumentType.word())
                        .executes(c -> list(c.getSource(), StringArgumentType.getString(c, "anchor"), 10))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 200))
                                .executes(c -> list(c.getSource(), StringArgumentType.getString(c, "anchor"), IntegerArgumentType.getInteger(c, "n"))))))
                .then(Commands.literal("verify").requires(Commands.hasPermission(Commands.LEVEL_OWNERS)).executes(c -> {
                    CommandSourceStack src = c.getSource();
                    Thread t = new Thread(() -> {
                        try {
                            byte[] loaded = ScenePlan.get().write();
                            byte[] live = ScenePlanBuilder.computeLive();
                            boolean same = java.util.Arrays.equals(loaded, live);
                            String msg = String.format(Locale.ROOT, "[sceneplan] verify: live plan %s the loaded one (live sha %s, loaded sha %s)",
                                    same ? "is identical to" : "DIFFERS from", Bake.sha(live).substring(0, 12), Bake.sha(loaded).substring(0, 12));
                            NirnCraft.LOGGER.info(msg);
                            src.getServer().execute(() -> src.sendSuccess(() -> Component.literal(msg), false));
                        } catch (Exception e) {
                            NirnCraft.LOGGER.error("[sceneplan] verify failed", e);
                        }
                    }, "NirnCraft scene plan verify");
                    t.setDaemon(true);
                    t.start();
                    src.sendSuccess(() -> Component.literal("Planning live on a background thread; the result goes to the log."), false);
                    return 1;
                })));
    }

    /** The first {@code n} scenes of an anchor type (or a kind id), one line each, also to the log. */
    private static int list(CommandSourceStack src, String what, int n) {
        ScenePlan p = ScenePlan.get();
        int shown = 0;
        for (int s = 0; s < p.scenes() && shown < n; s++) {
            if (!ScenePlan.ANCHOR_NAMES[p.anchor[s]].equalsIgnoreCase(what) && !p.kindOf(s).equals(what)) continue;
            String line = describe(p, s);
            NirnCraft.LOGGER.info("[sceneplan] {}", line);
            src.sendSuccess(() -> Component.literal(line), false);
            shown++;
        }
        int total = shown;
        src.sendSuccess(() -> Component.literal(total + " shown"), false);
        return 1;
    }
}
