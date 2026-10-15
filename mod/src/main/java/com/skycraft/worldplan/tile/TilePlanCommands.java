package com.skycraft.worldplan.tile;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.skycraft.Skycraft;
import com.skycraft.perf.PerfAutorun;
import com.skycraft.worldplan.RoadPlan;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Locale;

/**
 * Event hooks and developer commands ({@code /skycraft-tile}) for stage P7 Endless Lands tile planning.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class TilePlanCommands {
    private TilePlanCommands() {
    }

    @SubscribeEvent
    public static void onAboutToStart(ServerAboutToStartEvent event) {
        PerfAutorun.register("tileplan", (task, done) -> {
            long seed = task.server().overworld().getSeed();
            String report = runSelfTest(seed);
            for (String line : report.split("\n")) {
                Skycraft.LOGGER.info("[tileplan] {}", line);
            }
            done.run();
        });
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ServerLevel overworld = event.getServer().overworld();
        TilePlanData.attach(overworld);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        TilePlanner.setPersistence(null, null);
        TilePlanner.clearCache();
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skycraft-tile")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("plan")
                        .then(Commands.argument("tileX", IntegerArgumentType.integer())
                                .then(Commands.argument("tileZ", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            int tx = IntegerArgumentType.getInteger(ctx, "tileX");
                                            int tz = IntegerArgumentType.getInteger(ctx, "tileZ");
                                            long seed = ctx.getSource().getLevel().getSeed();
                                            long t0 = System.nanoTime();
                                            TilePlan plan = TilePlanner.planSync(seed, tx, tz);
                                            long ms = (System.nanoTime() - t0) / 1_000_000L;
                                            String msg = describe(plan, ms);
                                            ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                                            return 1;
                                        }))))
                .then(Commands.literal("sheet")
                        .then(Commands.argument("tileX", IntegerArgumentType.integer())
                                .then(Commands.argument("tileZ", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            int tx = IntegerArgumentType.getInteger(ctx, "tileX");
                                            int tz = IntegerArgumentType.getInteger(ctx, "tileZ");
                                            long seed = ctx.getSource().getLevel().getSeed();
                                            TilePlan plan = TilePlanner.planSync(seed, tx, tz);
                                            List<String> errs = TileSheetGenerator.validate(plan.sheet());
                                            String summary = String.format(Locale.ROOT,
                                                    "%s (%s, %s, %.0f%% land): %d layers, %d fields, lint errors=%d\ntruth: %s",
                                                    plan.sheet().name(),
                                                    plan.sheet().climateBand(),
                                                    plan.sheet().culture(),
                                                    plan.sheet().landFraction() * 100f,
                                                    plan.sheet().layers().size(),
                                                    plan.sheet().fieldsByQualifiedId().size(),
                                                    errs.size(),
                                                    plan.sheet().truth());
                                            ctx.getSource().sendSuccess(() -> Component.literal(summary), false);
                                            return errs.isEmpty() ? 1 : 0;
                                        }))))
                .then(Commands.literal("at")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            int x = IntegerArgumentType.getInteger(ctx, "x");
                                            int z = IntegerArgumentType.getInteger(ctx, "z");
                                            int tx = TilePlan.tileCoord(x);
                                            int tz = TilePlan.tileCoord(z);
                                            long seed = ctx.getSource().getLevel().getSeed();
                                            TilePlan plan = TilePlanner.planSync(seed, tx, tz);
                                            RoadPlan.Hit hit = new RoadPlan.Hit();
                                            boolean onRoad = plan.roadPlan().at(x, z, hit);
                                            String msg = String.format(Locale.ROOT,
                                                    "block (%d, %d) -> tile (%d, %d) [%s]: roadHit=%s (dist=%.1f, h=%.1f)",
                                                    x, z, tx, tz, plan.sheet().name(),
                                                    onRoad, onRoad ? hit.distance : -1f, onRoad ? hit.height : Float.NaN);
                                            ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                                            return 1;
                                        }))))
                .then(Commands.literal("selftest")
                        .executes(ctx -> {
                            long seed = ctx.getSource().getLevel().getSeed();
                            String report = runSelfTest(seed);
                            ctx.getSource().sendSuccess(() -> Component.literal(report), false);
                            return 1;
                        })));
    }

    public static String describe(TilePlan plan, long ms) {
        return String.format(Locale.ROOT,
                "tile (%d, %d) [%d..%d, %d..%d] '%s' (%s/%s, %.0f%% land) planned in %d ms: "
                        + "%d places, %d rivers, %d road links (%d segs, %d verts), %d settlements in street plan, %d scenes",
                plan.tileX(), plan.tileZ(),
                plan.minX(), plan.maxX(), plan.minZ(), plan.maxZ(),
                plan.sheet().name(), plan.sheet().climateBand(), plan.sheet().culture(),
                plan.sheet().landFraction() * 100f,
                ms,
                plan.places().size(),
                plan.rivers().size(),
                plan.roadPlan().links(),
                plan.roadPlan().segments(),
                plan.roadPlan().vertices(),
                plan.streetPlan().settlements(),
                plan.scenePlan().scenes());
    }

    public static String runSelfTest(long seed) {
        long t0 = System.nanoTime();
        TilePlan a = TilePlanner.planSync(seed, 4, 0);
        TilePlan b = TilePlanner.planSync(seed, 5, 0);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        List<TilePlan.EdgePoint> aEast = a.edgePoints(TilePlan.Edge.EAST);
        List<TilePlan.EdgePoint> bWest = b.edgePoints(TilePlan.Edge.WEST);
        boolean seamMatch = aEast.equals(bWest);
        List<String> errsA = TileSheetGenerator.validate(a.sheet());
        List<String> errsB = TileSheetGenerator.validate(b.sheet());
        return String.format(Locale.ROOT,
                "P7 selftest (%d ms): tile(4,0)<->tile(5,0) seamMatch=%s (%d edge points), sheetErrors=%d/%d, STATUS=%s",
                ms, seamMatch, aEast.size(), errsA.size(), errsB.size(),
                (seamMatch && errsA.isEmpty() && errsB.isEmpty()) ? "PASS" : "FAIL");
    }
}
