package com.skycraft.worldplan;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.skycraft.Skycraft;
import com.skycraft.bake.Bake;
import com.skycraft.perf.PerfAutorun;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Loads the road plan at startup (on a background thread) and gives developers {@code /skycraft-roadplan}:
 * {@code stats}, {@code at <x> <z>}, {@code bench [millions]} (random {@link RoadPlan#at} queries over the Tamriel
 * map), {@code verify} (plans live, off the server thread, and compares with the loaded plan byte for byte). Autorun
 * task {@code roadplan} = stats + bench.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class RoadPlanCommands {
    private RoadPlanCommands() {}

    @SubscribeEvent
    public static void onAboutToStart(ServerAboutToStartEvent event) {
        PerfAutorun.register("roadplan", (t, done) -> {
            Skycraft.LOGGER.info("[roadplan] {}", stats(RoadPlan.get()));
            Skycraft.LOGGER.info("[roadplan] {}", bench(RoadPlan.get(), 1_000_000));
            done.run();
        });
        // P2 terrain check: SKYCRAFT_AUTORUN=roadterrain[:samples] (see TerrainCheck)
        PerfAutorun.register("roadterrain", (t, done) -> {
            int n = t.arg().isEmpty() ? 30000 : Integer.parseInt(t.arg());
            Thread w = new Thread(() -> {
                try {
                    Skycraft.LOGGER.info("[roadplan] terrain check:\n{}", TerrainCheck.run(t.server().overworld(), n));
                } catch (Throwable e) {
                    Skycraft.LOGGER.error("[roadplan] terrain check failed", e);
                }
                done.run();
            }, "Skycraft terrain check");
            w.setDaemon(true);
            w.start();
        });
        Thread t = new Thread(() -> {
            try {
                RoadPlan.warm();
                PlanGround.get(); // the terrain's edge index (P2), so no worldgen thread builds it
            } catch (Throwable e) {
                Skycraft.LOGGER.error("[roadplan] loading the road plan failed", e);
            }
        }, "Skycraft road plan loader");
        t.setDaemon(true);
        t.start();
    }

    static String stats(RoadPlan p) {
        int[] spans = new int[RoadPlan.SPAN_NAMES.length];
        for (byte b : p.spanType) spans[b]++;
        int failed = 0, merged = 0;
        for (byte s : p.linkStatus) {
            if (s == RoadPlan.LINK_FAILED) failed++;
            else if (s == RoadPlan.LINK_MERGED) merged++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "road plan: %d links (%d failed, %d merged), %d segments, %d vertices, %.0f km; spans:",
                p.links(), failed, merged, p.segments(), p.vertices(), p.length(-1) / 1000));
        for (int k = 1; k < spans.length; k++) sb.append(' ').append(RoadPlan.SPAN_NAMES[k]).append('=').append(spans[k]);
        return sb.toString();
    }

    /** {@code count} queries at random columns of the Tamriel map (fixed sequence): average time and hit rate. */
    static String bench(RoadPlan p, int count) {
        RoadPlan.Hit hit = new RoadPlan.Hit();
        SplittableRandom r = new SplittableRandom(42);
        int n = Math.max(1, count);
        int[] xs = new int[n], zs = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = TamrielMap.MIN_X + r.nextInt(TamrielMap.MAX_X - TamrielMap.MIN_X);
            zs[i] = TamrielMap.MIN_Z + r.nextInt(TamrielMap.MAX_Z - TamrielMap.MIN_Z);
        }
        int hits = 0;
        for (int i = 0; i < Math.min(n, 200_000); i++) if (p.at(xs[i], zs[i], hit)) hits++; // warm-up
        hits = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) if (p.at(xs[i], zs[i], hit)) hits++;
        long ns = System.nanoTime() - t0;
        // near roads only (the worst case: full buckets)
        int near = 0;
        long tn = 0;
        if (p.vertices() > 0) {
            for (int i = 0; i < n; i++) {
                int v = r.nextInt(p.vertices());
                xs[i] = (int) Math.floor(p.vx[v] + r.nextInt(41) - 20);
                zs[i] = (int) Math.floor(p.vz[v] + r.nextInt(41) - 20);
            }
            long t1 = System.nanoTime();
            for (int i = 0; i < n; i++) if (p.at(xs[i], zs[i], hit)) near++;
            tn = System.nanoTime() - t1;
        }
        return String.format(Locale.ROOT, "RoadPlan.at bench: %d random Tamriel columns, %.1f ns/query average (%d within %.0f blocks of a road); %d columns near roads: %.1f ns/query (%d hits)",
                n, ns / (double) n, hits, RoadPlan.REACH, n, tn / (double) n, near);
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skycraft-roadplan").requires(s -> s.hasPermission(2))
                .then(Commands.literal("stats").executes(c -> {
                    String s = stats(RoadPlan.get());
                    c.getSource().sendSuccess(() -> Component.literal(s), false);
                    return 1;
                }))
                .then(Commands.literal("at").then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer()).executes(c -> {
                            int x = IntegerArgumentType.getInteger(c, "x"), z = IntegerArgumentType.getInteger(c, "z");
                            RoadPlan p = RoadPlan.get();
                            RoadPlan.Hit h = new RoadPlan.Hit();
                            String s = !p.at(x, z, h) ? "No planned road within " + (int) RoadPlan.REACH + " blocks."
                                    : String.format(Locale.ROOT, "%s %s -> %s, %.1f blocks off, road y %.2f (ground %.2f), span %s, segment %d",
                                    RoadPlan.CLASS_NAMES[h.kind], p.linkA[h.link], p.linkB[h.link], h.distance, h.height, h.ground,
                                    RoadPlan.SPAN_NAMES[h.span], h.segment);
                            c.getSource().sendSuccess(() -> Component.literal(s), false);
                            return 1;
                        }))))
                .then(Commands.literal("bench").executes(c -> benchCmd(c.getSource(), 1))
                        .then(Commands.argument("millions", IntegerArgumentType.integer(1, 20))
                                .executes(c -> benchCmd(c.getSource(), IntegerArgumentType.getInteger(c, "millions")))))
                .then(Commands.literal("verify").requires(s -> s.hasPermission(4)).executes(c -> {
                    CommandSourceStack src = c.getSource();
                    Thread t = new Thread(() -> {
                        try {
                            byte[] loaded = RoadPlan.get().write();
                            byte[] live = RoadPlanBuilder.computeLive();
                            boolean same = java.util.Arrays.equals(loaded, live);
                            String msg = String.format(Locale.ROOT, "[roadplan] verify: live plan %s the loaded one (live sha %s, loaded sha %s)",
                                    same ? "is identical to" : "DIFFERS from", Bake.sha(live).substring(0, 12), Bake.sha(loaded).substring(0, 12));
                            Skycraft.LOGGER.info(msg);
                            src.getServer().execute(() -> src.sendSuccess(() -> Component.literal(msg), false));
                        } catch (Exception e) {
                            Skycraft.LOGGER.error("[roadplan] verify failed", e);
                        }
                    }, "Skycraft road plan verify");
                    t.setDaemon(true);
                    t.start();
                    src.sendSuccess(() -> Component.literal("Planning live on a background thread; the result goes to the log."), false);
                    return 1;
                })));
    }

    private static int benchCmd(CommandSourceStack src, int millions) {
        String s = bench(RoadPlan.get(), millions * 1_000_000);
        Skycraft.LOGGER.info("[roadplan] {}", s);
        src.sendSuccess(() -> Component.literal(s), false);
        return 1;
    }
}
