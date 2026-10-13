package com.skycraft.worldplan.street;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.skycraft.Skycraft;
import com.skycraft.bake.Bake;
import com.skycraft.perf.PerfAutorun;
import com.skycraft.world.PlacementMask;
import com.skycraft.worldplan.RoadPlan;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Locale;

/**
 * P5 of the plan-first world generation (docs/WORLDGEN-PIPELINE.md): the baked street plan of every named settlement.
 * Registers the street painter ({@code skycraft:street_paint}, added to every overworld biome at the last decoration step
 * by the biome modifier {@code zz_street_paint}, so it runs after the road painter), the plot and street mask, loads the
 * plan at server start on a background thread, and gives developers {@code /skycraft-streetplan}: {@code stats},
 * {@code at <x> <z>}, {@code lint [id]} (unreachable doors, gate joins), {@code verify} (plans live off the server
 * thread and compares with the loaded plan byte for byte). Autorun task {@code streetplan} = stats + lint.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class StreetPlanModule {
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, Skycraft.MODID);
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> STREET_PAINT = FEATURES.register("street_paint",
            () -> new StreetPaint(NoneFeatureConfiguration.CODEC));

    private StreetPlanModule() {}

    /** Registers the feature and the mask source. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        FEATURES.register(modBus);
        PlacementMask.register(new StreetMask());
    }

    @SubscribeEvent
    public static void onAboutToStart(ServerAboutToStartEvent event) {
        PerfAutorun.register("streetplan", (t, done) -> {
            Skycraft.LOGGER.info("[streetplan] {}", stats(StreetPlan.get()));
            Skycraft.LOGGER.info("[streetplan] {}", lint(StreetPlan.get(), null, 40));
            done.run();
        });
        Thread t = new Thread(() -> {
            try {
                StreetPlan.warm();
            } catch (Throwable e) {
                Skycraft.LOGGER.error("[streetplan] loading the street plan failed", e);
            }
        }, "Skycraft street plan loader");
        t.setDaemon(true);
        t.start();
    }

    @SubscribeEvent
    public static void onStop(ServerStoppingEvent e) {
        Skycraft.LOGGER.info(StreetPaint.stats());
    }

    static String stats(StreetPlan p) {
        int[] cls = new int[StreetPlan.CLASS_NAMES.length], nodes = new int[StreetPlan.NODE_NAMES.length];
        double[] len = new double[StreetPlan.CLASS_NAMES.length];
        for (int e = 0; e < p.edges(); e++) {
            cls[p.eClass[e]]++;
            len[p.eClass[e]] += Math.hypot(p.nx[p.eb[e]] - p.nx[p.ea[e]], p.nz[p.eb[e]] - p.nz[p.ea[e]]);
        }
        for (int n = 0; n < p.nodes(); n++) nodes[p.nClass[n]]++;
        int bad = 0;
        for (boolean r : p.dReach) if (!r) bad++;
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "street plan: %d settlements, %d doors (%d unreachable), %d plots, %d nodes (",
                p.settlements(), p.doors(), bad, p.plots(), p.nodes()));
        for (int k = 0; k < nodes.length; k++) sb.append(k > 0 ? ", " : "").append(StreetPlan.NODE_NAMES[k]).append(' ').append(nodes[k]);
        sb.append("), edges:");
        for (int k = 0; k < cls.length; k++) sb.append(String.format(Locale.ROOT, " %s %d (%.1f km)", StreetPlan.CLASS_NAMES[k], cls[k], len[k] / 1000));
        return sb.toString();
    }

    /** Unreachable doors and gate joins (all settlements, or one), first {@code max} problems listed. */
    static String lint(StreetPlan p, String only, int max) {
        RoadPlan rp = RoadPlan.get();
        int doors = 0, bad = 0, gates = 0, badJoins = 0, listed = 0;
        StringBuilder detail = new StringBuilder();
        for (int s = 0; s < p.settlements(); s++) {
            if (only != null && !only.equals(p.sId[s])) continue;
            for (int d = p.sDoorOff[s]; d < p.sDoorOff[s + 1]; d++) {
                doors++;
                if (p.dReach[d]) continue;
                bad++;
                if (listed++ < max) detail.append(String.format(Locale.ROOT, "\n  door %s %d %d %d spec %d: no path to a street, gate or road",
                        p.sId[s], p.dx[d], p.dy[d], p.dz[d], p.dSpec[d]));
            }
            for (int n = p.sNodeOff[s]; n < p.sNodeOff[s + 1]; n++) {
                if (p.nClass[n] != StreetPlan.N_GATE && p.nClass[n] != StreetPlan.N_ROAD) continue;
                gates++;
                int v = p.nJoinVert[n], seg = p.nJoinSeg[n];
                boolean ok = v >= 0 && v < rp.vertices() && seg >= 0 && seg < rp.segments() && rp.vseg[v] == seg
                        && Math.hypot(rp.vx[v] - p.nx[n], rp.vz[v] - p.nz[n]) <= 0.01;
                if (!ok) {
                    badJoins++;
                    if (listed++ < max) detail.append(String.format(Locale.ROOT, "\n  %s node %s at %.1f %.1f does not join road vertex %d (segment %d)",
                            StreetPlan.NODE_NAMES[p.nClass[n]], p.sId[s], p.nx[n], p.nz[n], v, seg));
                }
            }
        }
        return String.format(Locale.ROOT, "lint%s: %d doors, %d unreachable (%.2f%%); %d gate/road nodes, %d not on a road plan vertex%s",
                only == null ? "" : " " + only, doors, bad, doors == 0 ? 0.0 : 100.0 * bad / doors, gates, badJoins, detail);
    }

    /** The settlements whose layout, doors or graph differ between two plans (first 12). */
    static String diff(StreetPlan a, StreetPlan b) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int s = 0; s < Math.min(a.settlements(), b.settlements()) && n < 12; s++) {
            String why = null;
            if (!a.sId[s].equals(b.sId[s])) why = "order";
            else if (a.sSpecOff[s + 1] - a.sSpecOff[s] != b.sSpecOff[s + 1] - b.sSpecOff[s]) why = "spec count";
            else {
                for (int i = a.sSpecOff[s] * StreetPlan.SPEC_INTS; i < a.sSpecOff[s + 1] * StreetPlan.SPEC_INTS && why == null; i++) {
                    if (a.specInts[i] != b.specInts[i]) why = "spec " + (i / StreetPlan.SPEC_INTS - a.sSpecOff[s]) + " field " + (i % StreetPlan.SPEC_INTS)
                            + " " + a.specInts[i] + "/" + b.specInts[i];
                }
                if (why == null && a.sDoorOff[s + 1] - a.sDoorOff[s] != b.sDoorOff[s + 1] - b.sDoorOff[s]) why = "door count";
                if (why == null && a.sNodeOff[s + 1] - a.sNodeOff[s] != b.sNodeOff[s + 1] - b.sNodeOff[s]) why = "node count";
            }
            if (why != null) {
                sb.append(a.sId[s]).append(" (").append(why).append(") ");
                n++;
            }
        }
        return n == 0 ? "none in layouts, doors or node counts" : sb.toString();
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skycraft-streetplan").requires(s -> s.hasPermission(2))
                .then(Commands.literal("stats").executes(c -> {
                    String s = stats(StreetPlan.get()) + "\n" + StreetPaint.stats();
                    c.getSource().sendSuccess(() -> Component.literal(s), false);
                    return 1;
                }))
                .then(Commands.literal("lint").executes(c -> {
                    String s = lint(StreetPlan.get(), null, 20);
                    Skycraft.LOGGER.info("[streetplan] {}", s);
                    c.getSource().sendSuccess(() -> Component.literal(s), false);
                    return 1;
                }).then(Commands.argument("id", StringArgumentType.word()).executes(c -> {
                    String s = lint(StreetPlan.get(), StringArgumentType.getString(c, "id"), 40);
                    Skycraft.LOGGER.info("[streetplan] {}", s);
                    c.getSource().sendSuccess(() -> Component.literal(s), false);
                    return 1;
                })))
                .then(Commands.literal("at").then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer()).executes(c -> {
                            int x = IntegerArgumentType.getInteger(c, "x"), z = IntegerArgumentType.getInteger(c, "z");
                            StreetPlan p = StreetPlan.get();
                            StreetPlan.Hit h = new StreetPlan.Hit();
                            StringBuilder sb = new StringBuilder(!p.at(x + 0.5, z + 0.5, h) ? "No street within " + (int) StreetPlan.REACH + " blocks."
                                    : String.format(Locale.ROOT, "%s (surface %d) of %s, %.1f blocks off, width %.0f, walk y %.1f",
                                    StreetPlan.CLASS_NAMES[h.cls], h.surface, p.sId[h.settlement], h.distance, h.width, h.height));
                            int k = p.bucket(x >> StreetPlan.BUCKET_SHIFT, z >> StreetPlan.BUCKET_SHIFT);
                            if (k >= 0) {
                                for (int j = p.plotFrom(k); j < p.plotTo(k); j++) {
                                    int q = p.plotAt(j);
                                    if (x < p.pMinX[q] || x > p.pMaxX[q] || z < p.pMinZ[q] || z > p.pMaxZ[q]) continue;
                                    sb.append(String.format(Locale.ROOT, "; plot spec %d kind %d pad %s", p.pSpec[q], p.pKind[q],
                                            p.pPad[q] == StreetPlan.NO_PAD ? "-" : String.valueOf(p.pPad[q])));
                                }
                            }
                            String s = sb.toString();
                            c.getSource().sendSuccess(() -> Component.literal(s), false);
                            return 1;
                        }))))
                .then(Commands.literal("trace").then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer()).executes(c -> {
                            StreetPaint.TRACE_Z = IntegerArgumentType.getInteger(c, "z");
                            StreetPaint.TRACE_X = IntegerArgumentType.getInteger(c, "x");
                            c.getSource().sendSuccess(() -> Component.literal("tracing the painting of that column"), false);
                            return 1;
                        }))))
                .then(Commands.literal("repaint").then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer()).executes(c -> {
                            String s = StreetPaint.repaint(c.getSource().getLevel(), IntegerArgumentType.getInteger(c, "x"),
                                    IntegerArgumentType.getInteger(c, "z"));
                            c.getSource().sendSuccess(() -> Component.literal(s), false);
                            return 1;
                        }))))
                .then(Commands.literal("debug").then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(c -> {
                                    String id = StringArgumentType.getString(c, "id");
                                    var pl = com.skycraft.tamriel.Places.byId(id);
                                    if (pl.isEmpty()) return 0;
                                    String s = StreetPlanBuilder.debug(pl.get(), IntegerArgumentType.getInteger(c, "x"),
                                            IntegerArgumentType.getInteger(c, "z"), 12);
                                    Skycraft.LOGGER.info("[streetplan] debug {}:\n{}", id, s);
                                    c.getSource().sendSuccess(() -> Component.literal(s), false);
                                    return 1;
                                })))))
                .then(Commands.literal("verify").requires(s -> s.hasPermission(4)).executes(c -> {
                    CommandSourceStack src = c.getSource();
                    Thread t = new Thread(() -> {
                        try {
                            byte[] loaded = StreetPlan.get().write();
                            byte[] live = StreetPlanBuilder.computeLive();
                            boolean same = java.util.Arrays.equals(loaded, live);
                            String msg = String.format(Locale.ROOT, "[streetplan] verify: live plan %s the loaded one (live sha %s, loaded sha %s)",
                                    same ? "is identical to" : "DIFFERS from", Bake.sha(live).substring(0, 12), Bake.sha(loaded).substring(0, 12));
                            Skycraft.LOGGER.info(msg);
                            if (!same) Skycraft.LOGGER.info("[streetplan] verify differences: {}", diff(StreetPlan.get(), StreetPlan.read(live)));
                            src.getServer().execute(() -> src.sendSuccess(() -> Component.literal(msg), false));
                        } catch (Exception e) {
                            Skycraft.LOGGER.error("[streetplan] verify failed", e);
                        }
                    }, "Skycraft street plan verify");
                    t.setDaemon(true);
                    t.start();
                    src.sendSuccess(() -> Component.literal("Planning live on a background thread; the result goes to the log."), false);
                    return 1;
                })));
    }
}
