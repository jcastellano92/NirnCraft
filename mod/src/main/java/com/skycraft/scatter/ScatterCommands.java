package com.skycraft.scatter;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.skycraft.Skycraft;
import com.skycraft.scatter.ScatterRules.Kind;
import com.skycraft.scatter.ZoneModel.Info;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /skycraft-scatter} (op 2), the debug and QA surface of the scatter module:
 * <ul>
 *     <li>{@code stats <x> <z> <radius>}: per zone, the chunks in range, how many are generated, the props placed
 *     and the commonest kinds;</li>
 *     <li>{@code zone [x z]}: the zone model's answer for a spot;</li>
 *     <li>{@code finds [radius]}: hidden stashes and formations around you;</li>
 *     <li>{@code export <x> <z> <radius>}: one CSV row per chunk to {@code <server>/skycraft-scatter/export_<x>_<z>.csv};</li>
 *     <li>{@code reset}: clears this session's placement counters.</li>
 * </ul>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class ScatterCommands {
    private static final int MAX_RADIUS = 1536;

    private ScatterCommands() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("skycraft-scatter").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("stats")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(16, MAX_RADIUS))
                                                .executes(c -> stats(c, IntegerArgumentType.getInteger(c, "x"),
                                                        IntegerArgumentType.getInteger(c, "z"), IntegerArgumentType.getInteger(c, "radius")))))))
                .then(Commands.literal("export")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(16, MAX_RADIUS))
                                                .executes(c -> export(c, IntegerArgumentType.getInteger(c, "x"),
                                                        IntegerArgumentType.getInteger(c, "z"), IntegerArgumentType.getInteger(c, "radius")))))))
                .then(Commands.literal("zone")
                        .executes(c -> {
                            Vec3 p = c.getSource().getPosition();
                            return zone(c, (int) p.x, (int) p.z);
                        })
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(c -> zone(c, IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"))))))
                .then(Commands.literal("finds")
                        .executes(c -> finds(c, 800))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(16, 4000))
                                .executes(c -> finds(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("dress")
                        .then(Commands.argument("kind", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(ScatterRules.get().kinds.keySet(), b))
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(c -> dress(c, com.mojang.brigadier.arguments.StringArgumentType.getString(c, "kind"),
                                                        IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z")))))))
                .then(Commands.literal("reset").executes(c -> {
                    ScatterStats.reset();
                    c.getSource().sendSuccess(() -> Component.literal("Scatter counters cleared"), false);
                    return 1;
                })));
    }

    private static int stats(CommandContext<CommandSourceStack> c, int x, int z, int radius) {
        CommandSourceStack src = c.getSource();
        long seed = src.getLevel().getSeed();
        ScatterRules rules = ScatterRules.get();
        int c0x = (x - radius) >> 4, c1x = (x + radius) >> 4, c0z = (z - radius) >> 4, c1z = (z + radius) >> 4;
        EnumMap<ScatterZone, int[]> zones = new EnumMap<>(ScatterZone.class);       // chunks, generated, items, human items
        EnumMap<ScatterZone, Map<String, Integer>> kinds = new EnumMap<>(ScatterZone.class);
        long r2 = (long) radius * radius;
        for (int cx = c0x; cx <= c1x; cx++) {
            for (int cz = c0z; cz <= c1z; cz++) {
                long dx = cx * 16L + 8 - x, dz = cz * 16L + 8 - z;
                if (dx * dx + dz * dz > r2) continue;
                Info info = ZoneModel.chunk(seed, cx, cz);
                ScatterStats.ChunkRecord rec = ScatterStats.chunk(cx, cz);
                // chunks are counted in the zone they were rolled in (a dungeon's start overrides the model zone)
                ScatterZone zn = rec != null ? rec.zone() : info.zone();
                int[] a = zones.computeIfAbsent(zn, k -> new int[4]);
                a[0]++;
                if (rec == null) continue;
                a[1]++;
                a[2] += rec.items();
                Map<String, Integer> km = kinds.computeIfAbsent(zn, k -> new TreeMap<>());
                for (Map.Entry<String, Integer> e : rec.kinds().entrySet()) {
                    km.merge(e.getKey(), e.getValue(), Integer::sum);
                    Kind k = rules.kinds.get(e.getKey());
                    if (k != null && k.human) a[3] += e.getValue();
                }
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "Scatter around %d %d r=%d (chunks in range by zone; props counted where generated this session)",
                x, z, radius));
        for (ScatterZone zn : ScatterZone.VALUES) {
            int[] a = zones.get(zn);
            if (a == null) continue;
            String top = "";
            Map<String, Integer> km = kinds.get(zn);
            if (km != null) {
                List<Map.Entry<String, Integer>> es = new ArrayList<>(km.entrySet());
                es.sort((p, q) -> q.getValue() - p.getValue());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(8, es.size()); i++) {
                    sb.append(i == 0 ? "" : ", ").append(es.get(i).getKey()).append(' ').append(es.get(i).getValue());
                }
                top = sb.toString();
            }
            lines.add(String.format(Locale.ROOT, "%-17s chunks %5d  generated %5d  items %5d (%.1f/chunk, human %d)  %s", zn.id, a[0], a[1], a[2],
                    a[1] == 0 ? 0.0 : a[2] / (double) a[1], a[3], top));
        }
        lines.add("clusters: " + ScatterStats.clusters());
        lines.add("discoveries built: " + ScatterStats.discoveries());
        lines.add("feature time: " + ScatterStats.timing());
        lines.add(ScatterStats.sceneLine());
        for (String l : lines) src.sendSuccess(() -> Component.literal(l), false);
        return 1;
    }

    private static int dress(CommandContext<CommandSourceStack> c, String kind, int x, int z) {
        boolean ok = Scatter.dress(c.getSource().getLevel(), kind, x, z, System.nanoTime());
        c.getSource().sendSuccess(() -> Component.literal(ok ? "Built " + kind + " at " + x + " " + z : "Could not build " + kind + " at " + x + " " + z + " (" + Scatter.LAST_WHY + ")"), false);
        return ok ? 1 : 0;
    }

    private static int zone(CommandContext<CommandSourceStack> c, int x, int z) {
        long seed = c.getSource().getLevel().getSeed();
        Info i = ZoneModel.at(seed, x, z);
        String s = String.format(Locale.ROOT,
                "%s  humanity %.2f remote %.2f  settlement %s %s edge %d  road %.2f  water %d  dungeon %d  ruin %d  %s (%s) y%.0f%s%s",
                i.zone().id, i.humanity(), i.remote(), i.settleType() == null ? "-" : i.settleType().id(), i.settleId(), i.settleEdge(),
                i.roadHint(), i.waterDist(), i.dungeonEdge(), i.ruinEdge(), i.regionId(), i.province(), i.elevation(),
                i.mountains() ? " mountains" : "", RoadProbe.live() ? " [live roads available]" : "");
        c.getSource().sendSuccess(() -> Component.literal(s), false);
        return 1;
    }

    private static int finds(CommandContext<CommandSourceStack> c, int radius) {
        CommandSourceStack src = c.getSource();
        Vec3 p = src.getPosition();
        long seed = src.getLevel().getSeed();
        List<Discoveries.Find> fs = Discoveries.within(seed, (int) p.x, (int) p.z, radius);
        fs.sort((a, b) -> Long.compare(a.distSqr((int) p.x, (int) p.z), b.distSqr((int) p.x, (int) p.z)));
        for (Discoveries.Find f : fs) {
            String line = String.format(Locale.ROOT, "%s at %d %d (%d blocks) in %s", f.type().id(), f.x(), f.z(),
                    (int) Math.sqrt(f.distSqr((int) p.x, (int) p.z)), f.regionId());
            src.sendSuccess(() -> Component.literal(line), false);
        }
        src.sendSuccess(() -> Component.literal(fs.size() + " finds within " + radius + " blocks"), false);
        return fs.size();
    }

    private static int export(CommandContext<CommandSourceStack> c, int x, int z, int radius) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        long seed = level.getSeed();
        ScatterRules rules = ScatterRules.get();
        Path dir = src.getServer().getServerDirectory().resolve("skycraft-scatter");
        Path file = dir.resolve("export_" + x + "_" + z + ".csv");
        try {
            Files.createDirectories(dir);
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
                w.println("cx,cz,x,z,zone,humanity,remote,settle_edge,settle_type,road,water,dungeon_edge,ruin_edge,region,generated,items,human_items,nature_items,kinds");
                int rows = 0;
                long r2 = (long) radius * radius;
                for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++) {
                    for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) {
                        long dx = cx * 16L + 8 - x, dz = cz * 16L + 8 - z;
                        if (dx * dx + dz * dz > r2) continue;
                        Info i = ZoneModel.chunk(seed, cx, cz);
                        ScatterStats.ChunkRecord rec = ScatterStats.chunk(cx, cz);
                        int human = 0, nature = 0;
                        StringBuilder ks = new StringBuilder();
                        if (rec != null) {
                            for (Map.Entry<String, Integer> e : rec.kinds().entrySet()) {
                                Kind k = rules.kinds.get(e.getKey());
                                if (k != null && k.human) human += e.getValue() * Math.max(1, k.items);
                                else nature += e.getValue() * Math.max(1, k.items);
                                ks.append(ks.length() == 0 ? "" : ";").append(e.getKey()).append(':').append(e.getValue());
                            }
                        }
                        String zoneId = rec != null ? rec.zone().id : i.zone().id;
                        w.println(String.format(Locale.ROOT, "%d,%d,%d,%d,%s,%.3f,%.3f,%d,%s,%.3f,%d,%d,%d,%s,%d,%d,%d,%d,%s", cx, cz, cx * 16 + 8,
                                cz * 16 + 8, zoneId, i.humanity(), i.remote(), i.settleEdge(),
                                i.settleType() == null ? "-" : i.settleType().id(), i.roadHint(), i.waterDist(), i.dungeonEdge(), i.ruinEdge(),
                                i.regionId(), rec == null ? 0 : 1, rec == null ? 0 : rec.items(), human, nature, ks));
                        rows++;
                    }
                }
                int n = rows;
                src.sendSuccess(() -> Component.literal("Wrote " + n + " chunks to " + file), false);
            }
        } catch (IOException e) {
            src.sendFailure(Component.literal("Could not write " + file + ": " + e.getMessage()));
            return 0;
        }
        return 1;
    }
}
