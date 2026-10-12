package com.skycraft.roads;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.skycraft.tamriel.TamrielMap;
import com.mojang.brigadier.context.CommandContext;
import com.skycraft.Skycraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Arrays;

/**
 * {@code /skycraft-roads info | stats | render [x0 z0 x1 z1 blocksPerPixel] | export-inns | export | replan | repave | nearest |
 * traveler [kind]} (operators only).
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class RoadsCommands {
    private RoadsCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skycraft-roads").requires(s -> s.hasPermission(2))
                .then(Commands.literal("info").executes(RoadsCommands::info))
                .then(Commands.literal("replan").executes(RoadsCommands::replan))
                .then(Commands.literal("repave").executes(RoadsCommands::repave))
                .then(Commands.literal("nearest").executes(RoadsCommands::nearest))
                .then(Commands.literal("stats").executes(RoadsCommands::stats))
                .then(Commands.literal("render")
                        .executes(c -> render(c, TamrielMap.MIN_X, TamrielMap.MIN_Z, TamrielMap.MAX_X, TamrielMap.MAX_Z, 16))
                        .then(Commands.argument("x0", IntegerArgumentType.integer())
                                .then(Commands.argument("z0", IntegerArgumentType.integer())
                                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                                .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                        .then(Commands.argument("blocksPerPixel", IntegerArgumentType.integer(1, 64))
                                                                .executes(c -> render(c, IntegerArgumentType.getInteger(c, "x0"),
                                                                        IntegerArgumentType.getInteger(c, "z0"),
                                                                        IntegerArgumentType.getInteger(c, "x1"),
                                                                        IntegerArgumentType.getInteger(c, "z1"),
                                                                        IntegerArgumentType.getInteger(c, "blocksPerPixel")))))))))
                .then(Commands.literal("export-inns").executes(RoadsCommands::exportInns))
                .then(Commands.literal("export").executes(RoadsCommands::exportNetwork))
                .then(Commands.literal("inspect")
                        .then(Commands.argument("x0", IntegerArgumentType.integer())
                                .then(Commands.argument("z0", IntegerArgumentType.integer())
                                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                                .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                        .executes(c -> inspect(c, IntegerArgumentType.getInteger(c, "x0"),
                                                                IntegerArgumentType.getInteger(c, "z0"),
                                                                IntegerArgumentType.getInteger(c, "x1"),
                                                                IntegerArgumentType.getInteger(c, "z1"))))))))
                .then(Commands.literal("traveler")
                        .executes(c -> traveler(c, null))
                        .then(Commands.argument("kind", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.asList(Travelers.KINDS), b))
                                .executes(c -> traveler(c, StringArgumentType.getString(c, "kind"))))));
    }

    private static ServerLevel overworld(CommandContext<CommandSourceStack> c) {
        return c.getSource().getServer().overworld();
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        RoadsData data = RoadsData.get(overworld(c));
        int signs = 0;
        for (RoadFeature f : data.features) if (f.type == RoadFeature.SIGNPOST) signs++;
        int lanterns = (int) data.features.stream().filter(f -> f.type == RoadFeature.LANTERN).count();
        float km = 0;
        for (Road r : data.roads.values()) km += r.length() / 1000f;
        String length = String.format(java.util.Locale.ROOT, "%.1f", km);
        int s = data.settlements.size(), r = data.roads.size(), p = RoadNetwork.pendingPlans(data), q = Paver.queued(),
                t = Travelers.loadedCount(), signsF = signs;
        c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.roads.info", s, r, length, signsF, lanterns,
                p, q, t), false);
        if (!RoadsConfig.ENABLED.get()) c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.roads.disabled"), false);
        return s;
    }

    private static int stats(CommandContext<CommandSourceStack> c) {
        RoadsData data = RoadsData.get(overworld(c));
        String text = RoadNetwork.stats(data);
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        RoadNetwork.logStats(data);
        return data.roads.size();
    }

    /** Renders the planned network to skycraft-roads.png in the server folder (on a background thread). */
    private static int render(CommandContext<CommandSourceStack> c, int x0, int z0, int x1, int z1, int bpp) {
        RoadsData data = RoadsData.get(overworld(c));
        NetworkRender.Snapshot snap = NetworkRender.snapshot(data);
        java.io.File file = c.getSource().getServer().getFile("skycraft-roads.png");
        CommandSourceStack source = c.getSource();
        Thread t = new Thread(() -> {
            try {
                long t0 = System.nanoTime();
                NetworkRender.render(snap, Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1), bpp, file);
                Skycraft.LOGGER.info("Skycraft roads: rendered the network to {} in {} ms", file.getAbsolutePath(),
                        (System.nanoTime() - t0) / 1_000_000);
                source.getServer().execute(() -> source.sendSuccess(() -> Component.translatable("command.skycraft.roads.render",
                        file.getAbsolutePath()), false));
            } catch (Exception e) {
                Skycraft.LOGGER.warn("Skycraft roads: render failed", e);
            }
        }, "Skycraft Road Render");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    /**
     * Top-down picture of the loaded blocks in an area (map colours, road blocks highlighted, bridges outlined) to
     * skycraft-roads-blocks.png, with a count of paved columns: checks the paving without a client. Max 1024 x 1024.
     */
    private static int inspect(CommandContext<CommandSourceStack> c, int x0, int z0, int x1, int z1) {
        ServerLevel level = overworld(c);
        int ax = Math.min(x0, x1), az = Math.min(z0, z1);
        int w = Math.min(1024, Math.abs(x1 - x0) + 1), h = Math.min(1024, Math.abs(z1 - z0) + 1);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        int road = 0, bridge = 0, unloaded = 0;
        java.util.Map<String, Integer> blocks = new java.util.TreeMap<>();
        for (int dz = 0; dz < h; dz++) {
            for (int dx = 0; dx < w; dx++) {
                int x = ax + dx, z = az + dz;
                var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) {
                    img.setRGB(dx, dz, 0x202020);
                    unloaded++;
                    continue;
                }
                int y = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
                net.minecraft.world.level.block.state.BlockState s = level.getBlockState(pos);
                boolean onWater = !level.getBlockState(pos.below()).getFluidState().isEmpty() && s.getFluidState().isEmpty();
                int rgb = s.getMapColor(level, pos).col;
                if (Paver.isRoadMaterial(s) && !Paver.isNaturalGround(s) || s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH)) {
                    road++;
                    String id = String.valueOf(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(s.getBlock()));
                    blocks.merge(id, 1, Integer::sum);
                    rgb = ((rgb >> 1) & 0x7F7F7F) | 0x800000; // tinted red
                }
                if (onWater) {
                    bridge++;
                    rgb = 0xFFD000;
                }
                img.setRGB(dx, dz, rgb);
            }
        }
        java.io.File file = c.getSource().getServer().getFile("skycraft-roads-blocks.png");
        try {
            javax.imageio.ImageIO.write(img, "png", file);
        } catch (java.io.IOException e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        String text = String.format(java.util.Locale.ROOT, "%d road columns, %d bridge-deck columns, %d unloaded; %s -> %s",
                road, bridge, unloaded, blocks, file.getAbsolutePath());
        Skycraft.LOGGER.info("Skycraft roads: inspect {} {} {} {}: {}", x0, z0, x1, z1, text);
        RoadsData data = RoadsData.get(level);
        for (RoadFeature f : data.features) {
            if (f.type == RoadFeature.LANTERN || f.type == RoadFeature.DRESSING || f.x < ax || f.z < az || f.x >= ax + w || f.z >= az + h) continue;
            Skycraft.LOGGER.info("Skycraft roads: inspect feature {} at {} {} ({} signs)", f.type, f.x, f.z, f.dests.size());
        }
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return road;
    }

    private static int exportInns(CommandContext<CommandSourceStack> c) {
        RoadsData data = RoadsData.get(overworld(c));
        java.io.File file = c.getSource().getServer().getFile("skycraft-roadside-inns.json");
        try {
            int n = InnSites.export(data, file);
            c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.roads.export_inns", n, file.getAbsolutePath()), false);
            return n;
        } catch (java.io.IOException e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    /** The planned network (roads, settlements, gates) as skycraft-roads-export.json, for tools/qa/reach_lint.py --export. */
    private static int exportNetwork(CommandContext<CommandSourceStack> c) {
        RoadsData data = RoadsData.get(overworld(c));
        java.io.File file = c.getSource().getServer().getFile("skycraft-roads-export.json");
        try {
            int n = RoadExport.write(data, file);
            c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.roads.export", n, file.getAbsolutePath()), false);
            return n;
        } catch (java.io.IOException e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static int replan(CommandContext<CommandSourceStack> c) {
        ServerLevel level = overworld(c);
        RoadsData data = RoadsData.get(level);
        int queued = RoadNetwork.replan(level, data);
        int s = data.settlements.size();
        c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.roads.replan", s, queued), true);
        return queued;
    }

    private static int repave(CommandContext<CommandSourceStack> c) {
        ServerLevel level = overworld(c);
        int n = RoadNetwork.repave(level, RoadsData.get(level));
        c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.roads.repave", n), true);
        return n;
    }

    private static int nearest(CommandContext<CommandSourceStack> c) {
        ServerLevel level = overworld(c);
        RoadsData data = RoadsData.get(level);
        var pos = c.getSource().getPosition();
        Settlement best = null;
        double bestD = Double.MAX_VALUE;
        for (Settlement s : data.settlements) {
            double d = s.distSq(pos.x, pos.z);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        if (best == null) {
            c.getSource().sendFailure(Component.translatable("command.skycraft.roads.none"));
            return 0;
        }
        Settlement s = best;
        int dist = (int) Math.round(Math.sqrt(bestD));
        long roads = data.roads.values().stream().filter(r -> r.a == s.id || r.b == s.id).count();
        c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.roads.nearest", s.name, dist, s.x, s.z, roads), false);
        return 1;
    }

    private static int traveler(CommandContext<CommandSourceStack> c, String kind) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        if (!(player.level() instanceof ServerLevel level) || !RoadNetwork.isRoadLevel(level)) {
            c.getSource().sendFailure(Component.translatable("command.skycraft.roads.traveler_failed"));
            return 0;
        }
        String forced = kind;
        if (forced == null) {
            java.util.List<String> available = new java.util.ArrayList<>();
            for (String k : Travelers.KINDS) if (Travelers.kindAvailable(level, k)) available.add(k);
            forced = available.get(level.getRandom().nextInt(available.size())); // caravan/courier always available
        }
        String spawned = Travelers.trySpawn(level, RoadsData.get(level), player, forced);
        if (spawned == null) {
            c.getSource().sendFailure(Component.translatable("command.skycraft.roads.traveler_failed"));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.roads.traveler",
                Component.translatable("roads.skycraft.kind." + spawned)), true);
        return 1;
    }
}
