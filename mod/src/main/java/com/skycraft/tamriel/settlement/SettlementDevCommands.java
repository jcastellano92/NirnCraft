package com.skycraft.tamriel.settlement;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.skycraft.Skycraft;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.settlement.grammar.Blueprint;
import com.skycraft.tamriel.settlement.grammar.Metrics;
import com.skycraft.tamriel.settlement.grammar.Styles;
import com.skycraft.tamriel.settlement.grammar.Use;
import com.skycraft.tamriel.settlement.grammar.Wealth;
import com.skycraft.tamriel.settlement.grammar.Zoning;
import com.skycraft.tamriel.settlement.kit.Kits;
import com.skycraft.tamriel.settlement.plan.Cities;
import com.skycraft.tamriel.settlement.plan.Layouts;
import com.skycraft.tamriel.settlement.plan.Plan;
import com.skycraft.tamriel.settlement.plan.Spec;
import com.skycraft.tamriel.settlement.plan.Terrain;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Developer preview tool for the building grammar and the zoning planner (operators, or the server console). Images
 * go to {@code skycraft-previews/} in the server folder.
 * <ul>
 *   <li>{@code /skysettle gallery <culture> [style] [group]}: builds every function of a group ({@code homes},
 *   {@code trades}, {@code civic}, {@code work}, {@code all}) at all four wealth tiers on a platform in the sky, through
 *   the real chunk-clipped piece path, and renders it isometrically.</li>
 *   <li>{@code /skysettle generic <culture> <size> <x> <z> [style]}: plans a generic settlement on the real terrain
 *   there, paints all its pieces, prints its metrics and renders plan, top-down and isometric images.</li>
 *   <li>{@code /skysettle place <id>}: generates the chunks round a named place (the real structure pipeline) and
 *   renders it.</li>
 *   <li>{@code /skysettle compare <culture> <size> <x> <z>}: plans the same site with the old layouts and the zoning
 *   planner (nothing is built) and prints density and variety for both, with plan images.</li>
 *   <li>{@code /skysettle survey}: compare for one site per culture and every size, plus every named non-unique
 *   settlement; writes {@code survey.csv}.</li>
 *   <li>{@code /skysettle render <x1> <z1> <x2> <z2> <name>}: isometric and top-down images of any built area.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class SettlementDevCommands {
    private SettlementDevCommands() {}

    static final File DIR = new File("skycraft-previews");

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skysettle").requires(s -> s.hasPermission(2))
                .then(Commands.literal("gallery").then(Commands.argument("culture", StringArgumentType.word())
                        .executes(c -> gallery(c, 0, "all"))
                        .then(Commands.argument("style", IntegerArgumentType.integer(0, 15))
                                .executes(c -> gallery(c, IntegerArgumentType.getInteger(c, "style"), "all"))
                                .then(Commands.argument("group", StringArgumentType.word())
                                        .executes(c -> gallery(c, IntegerArgumentType.getInteger(c, "style"), StringArgumentType.getString(c, "group")))))))
                .then(Commands.literal("generic").then(Commands.argument("culture", StringArgumentType.word())
                        .then(Commands.argument("size", StringArgumentType.word())
                                .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(c -> generic(c, -1))
                                        .then(Commands.argument("style", IntegerArgumentType.integer(0, 15))
                                                .executes(c -> generic(c, IntegerArgumentType.getInteger(c, "style")))))))))
                .then(Commands.literal("place").then(Commands.argument("id", StringArgumentType.word()).executes(SettlementDevCommands::place)))
                .then(Commands.literal("compare").then(Commands.argument("culture", StringArgumentType.word())
                        .then(Commands.argument("size", StringArgumentType.word())
                                .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(SettlementDevCommands::compare))))))
                .then(Commands.literal("survey").executes(SettlementDevCommands::survey))
                .then(Commands.literal("site").then(Commands.argument("kind", StringArgumentType.word())
                        .then(Commands.argument("culture", StringArgumentType.word())
                                .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(SettlementDevCommands::site))))))
                .then(Commands.literal("plan").then(Commands.argument("id", StringArgumentType.word()).executes(SettlementDevCommands::planOne)))
                .then(Commands.literal("plans").executes(SettlementDevCommands::planAll))
                .then(Commands.literal("reload").executes(c -> {
                    Styles.reload();
                    com.skycraft.tamriel.settlement.plan.CityPlans.reload();
                    c.getSource().sendSuccess(() -> Component.literal("Style sheets reloaded"), false);
                    return 1;
                }))
                .then(Commands.literal("render").then(Commands.argument("x1", IntegerArgumentType.integer()).then(Commands.argument("z1", IntegerArgumentType.integer())
                        .then(Commands.argument("x2", IntegerArgumentType.integer()).then(Commands.argument("z2", IntegerArgumentType.integer())
                                .then(Commands.argument("name", StringArgumentType.word()).executes(SettlementDevCommands::render))))))));
    }

    /**
     * Unattended previews: {@code -Dskycraft.settle.script=<file>} runs the file's commands (one per line, {@code #}
     * comments) on the console once the server has started, then stops the server.
     */
    @SubscribeEvent
    public static void onStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        String path = System.getProperty("skycraft.settle.script", "");
        if (path.isEmpty()) return;
        var server = event.getServer();
        server.execute(() -> {
            try {
                for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(path))) {
                    String cmd = line.trim();
                    if (cmd.isEmpty() || cmd.startsWith("#")) continue;
                    Skycraft.LOGGER.info("[skysettle] script: {}", cmd);
                    long t0 = System.nanoTime();
                    try {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
                    } catch (RuntimeException e) {
                        Skycraft.LOGGER.error("[skysettle] script command failed: {}", cmd, e);
                    }
                    Skycraft.LOGGER.info("[skysettle] script: done in {} ms", (System.nanoTime() - t0) / 1_000_000);
                }
            } catch (IOException e) {
                Skycraft.LOGGER.error("[skysettle] could not read script {}", path, e);
            }
            server.halt(false);
        });
    }

    static void say(CommandContext<CommandSourceStack> c, String msg) {
        Skycraft.LOGGER.info("[skysettle] {}", msg);
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
    }

    // ------------------------------------------------------------------ painting plans into the live level

    static Structure.GenerationContext context(ServerLevel level, int x, int z) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        return new Structure.GenerationContext(level.registryAccess(), gen, gen.getBiomeSource(), level.getChunkSource().randomState(),
                level.getStructureManager(), level.getSeed(), new ChunkPos(x >> 4, z >> 4), level, b -> true);
    }

    /** Paints the pieces chunk by chunk through {@link SettlementPiece#postProcess}, like world generation does. */
    static void paint(ServerLevel level, List<SettlementPiece> pieces) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomSource rnd = RandomSource.create(1);
        for (SettlementPiece p : pieces) {
            BoundingBox b = p.getBoundingBox();
            for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++) {
                for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
                    level.getChunk(cx, cz);
                    BoundingBox chunk = new BoundingBox(cx << 4, level.getMinBuildHeight(), cz << 4, (cx << 4) + 15, level.getMaxBuildHeight() - 1, (cz << 4) + 15);
                    p.postProcess(level, level.structureManager(), gen, rnd, chunk, new ChunkPos(cx, cz), BlockPos.ZERO);
                }
            }
        }
    }

    static Culture culture(String s) {
        return Culture.of(s);
    }

    static Layouts.Size size(String s) {
        return Layouts.Size.of(s);
    }

    // ------------------------------------------------------------------ gallery

    static List<Use> group(String g) {
        List<Use> out = new ArrayList<>();
        for (Use u : Use.VALUES) {
            boolean in = switch (g) {
                case "homes" -> u.home() || u == Use.SHACK;
                case "trades" -> u.trade && !u.home() && u != Use.STABLES && u != Use.MILL && u != Use.SAWMILL && u != Use.SMELTER;
                case "civic" -> u == Use.TEMPLE || u == Use.HALL_OF_DEAD || u == Use.GUARDHOUSE || u == Use.BARRACKS || u == Use.KEEP
                        || u == Use.LONGHOUSE || u == Use.GUILD_HALL || u == Use.INN || u == Use.WATCHTOWER || u == Use.MAGE_TOWER;
                case "work" -> u == Use.STABLES || u == Use.BARN || u == Use.MILL || u == Use.SAWMILL || u == Use.SMELTER || u == Use.WAREHOUSE
                        || u == Use.FISHERY || u == Use.FARMHOUSE || u == Use.SHACK;
                default -> true;
            };
            if (in) out.add(u);
        }
        return out;
    }

    static int gallery(CommandContext<CommandSourceStack> c, int style, String group) {
        ServerLevel level = c.getSource().getLevel();
        Culture cul = culture(StringArgumentType.getString(c, "culture"));
        List<Use> uses = group(group);
        int cell = 34;
        int gi = switch (group) {
            case "homes" -> 0;
            case "trades" -> 1;
            case "civic" -> 2;
            case "work" -> 3;
            default -> 4;
        };
        int ox = 60000 + cul.ordinal() * 800 + style * 160, oz = 60000 + gi * 1400;
        int y = 300;
        int cols = Wealth.VALUES.length;
        int rows = uses.size();
        // a grass platform in the sky
        int x1 = ox + cols * cell, z1 = oz + rows * cell;
        for (int cx = ox >> 4; cx <= x1 >> 4; cx++) for (int cz = oz >> 4; cz <= z1 >> 4; cz++) level.getChunk(cx, cz);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = ox - 2; x <= x1 + 2; x++) {
            for (int z = oz - 2; z <= z1 + 2; z++) {
                level.setBlock(m.set(x, y - 1, z), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(m.set(x, y, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int yy = y + 1; yy < y + 70; yy++) {
                    if (!level.getBlockState(m.set(x, yy, z)).isAir()) level.setBlock(m, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        List<SettlementPiece> pieces = new ArrayList<>();
        StringBuilder log = new StringBuilder();
        RandomSource r = RandomSource.create(cul.ordinal() * 31L + style);
        int i = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                Use u = uses.get(row);
                Wealth w = Wealth.VALUES[col];
                int[] sz = u.size(w, r);
                Spec s = Spec.at(u.archetype(), ox + col * cell + cell / 2, oz + row * cell + cell / 2, Direction.SOUTH).size(sz[0], sz[1]);
                s.use(u, w);
                s.culture = cul.ordinal();
                s.style = style;
                s.gseed = r.nextInt();
                s.floor = y + 1;
                s.margin = 2;
                if (w.atLeast(Wealth.WEALTHY)) s.flags |= Spec.RICH;
                if (u == Use.MILL) s.variant = col % 2;
                pieces.add(new SettlementPiece(s, r.nextLong(), "preview", i++));
                log.append(Blueprint.of(s)).append('\n');
            }
        }
        paint(level, pieces);
        String name = "gallery_" + cul.id() + "_" + Kits.palette(cul, style).name + "_" + group;
        try {
            SettlementPreview.iso(level, ox - 2, oz - 2, x1 + 2, z1 + 2, 3, new File(DIR, name + "_iso.png"));
            SettlementPreview.top(level, ox - 2, oz - 2, x1 + 2, z1 + 2, 3, new File(DIR, name + "_top.png"));
            try (FileWriter fw = new FileWriter(new File(DIR, name + ".txt"))) {
                fw.write(log.toString());
            }
        } catch (IOException e) {
            Skycraft.LOGGER.error("Preview render failed", e);
        }
        say(c, "Gallery " + name + ": " + pieces.size() + " buildings at " + ox + " " + y + " " + oz);
        return pieces.size();
    }

    // ------------------------------------------------------------------ settlements

    static String lastForm = "";

    static Plan planGeneric(ServerLevel level, Culture cul, int style, Layouts.Size sz, int x, int z, boolean legacy) {
        Structure.GenerationContext ctx = context(level, x, z);
        int radius = legacy ? legacyRadius(sz) : sz.radius();
        long seed = level.getSeed() ^ ChunkPos.asLong(x >> 4, z >> 4) * 0x5DEECE66DL ^ sz.ordinal();
        Direction back = Direction.from2DDataValue((int) Math.floorMod(seed >>> 7, 4));
        Plan plan = new Plan("gen:" + (x >> 4) + "," + (z >> 4), x, z, radius, cul, style, seed, new Terrain(ctx, radius > 60 ? 16 : 8), back);
        if (legacy) Layouts.generic(plan, sz, "", Layouts.Names.NONE);
        else {
            Zoning.generic(plan, sz, "", Layouts.Names.NONE);
            lastForm = Zoning.skeleton(seed, cul, style, sz, x, z, back, radius).form.name().toLowerCase(Locale.ROOT);
        }
        plan.resolve();
        return plan;
    }

    /** The radii the old layouts were planned at. */
    static int legacyRadius(Layouts.Size s) {
        return switch (s) {
            case CITY -> 100;
            case TOWN -> 55;
            case VILLAGE -> 40;
            case HAMLET -> 26;
            default -> 24;
        };
    }

    static int defaultStyle(Culture c, int x, int z) {
        var region = com.skycraft.tamriel.TamrielMap.region(x, z);
        if (c == Culture.NORD) return Layouts.nordStyle(region.id());
        if (c == Culture.DUNMER) return Culture.dunmerStyle(region.id());
        return 0;
    }

    /** The nearest dry, fairly level spot (spiral search, 64-block steps) clear of named places; the input if none. */
    static int[] findSite(ServerLevel level, int x, int z, int radius) {
        Terrain t = new Terrain(context(level, x, z), 16);
        int half = Math.max(12, radius / 3);
        for (int ring = 0; ring <= 10; ring++) {
            for (int i = -ring; i <= ring; i++) {
                for (int j = -ring; j <= ring; j++) {
                    if (Math.max(Math.abs(i), Math.abs(j)) != ring) continue;
                    int px = x + i * 64, pz = z + j * 64;
                    if (!Places.overlapping(px - radius - 40, pz - radius - 40, px + radius + 40, pz + radius + 40).isEmpty()) continue;
                    int h = t.land(px, pz);
                    if (h <= t.sea + 1 || t.anyWet(px, pz, half, half) || t.spread(px, pz, half, half) > 10) continue;
                    return new int[]{px, pz};
                }
            }
        }
        return new int[]{x, z};
    }

    static int generic(CommandContext<CommandSourceStack> c, int styleArg) {
        ServerLevel level = c.getSource().getLevel();
        Culture cul = culture(StringArgumentType.getString(c, "culture"));
        Layouts.Size sz = size(StringArgumentType.getString(c, "size"));
        int x = IntegerArgumentType.getInteger(c, "x"), z = IntegerArgumentType.getInteger(c, "z");
        // look for buildable land near the requested spot, clear of named places (they generate with the chunks)
        int[] spot = findSite(level, x, z, sz.radius());
        x = spot[0];
        z = spot[1];
        int style = styleArg >= 0 ? styleArg : defaultStyle(cul, x, z);
        long t0 = System.nanoTime();
        Plan plan = planGeneric(level, cul, style, sz, x, z, false);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Metrics m = Metrics.of(plan, 0, Zoning.builtRadius(sz, plan.radius) + 8);
        List<SettlementPiece> pieces = new ArrayList<>();
        for (int i = 0; i < plan.specs.size(); i++) pieces.add(new SettlementPiece(plan.specs.get(i), plan.seed ^ (i * 0x9E3779B97F4A7C15L), plan.key, i));
        paint(level, pieces);
        String name = "generic_" + cul.id() + "_" + sz.name().toLowerCase(Locale.ROOT) + "_" + x + "_" + z;
        int r = plan.radius + 4;
        try {
            SettlementPreview.plan(plan, 0, 3, new File(DIR, name + "_plan.png"));
            SettlementPreview.top(level, x - r, z - r, x + r, z + r, 3, new File(DIR, name + "_top.png"));
            int ri = Zoning.builtRadius(sz, plan.radius) + 6;
            SettlementPreview.iso(level, x - ri, z - ri, x + ri, z + ri, 3, new File(DIR, name + "_iso.png"));
        } catch (IOException e) {
            Skycraft.LOGGER.error("Preview render failed", e);
        }
        say(c, name + " planned in " + ms + " ms, " + pieces.size() + " pieces: " + m);
        return pieces.size();
    }

    /** {@code /skysettle site <kind> <culture> <x> <z>}: lays out and paints an outlying working site there. */
    static int site(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        com.skycraft.tamriel.settlement.grammar.Sites.Kind kind;
        try {
            kind = com.skycraft.tamriel.settlement.grammar.Sites.Kind.valueOf(StringArgumentType.getString(c, "kind").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            c.getSource().sendFailure(Component.literal("Kinds: " + java.util.Arrays.toString(com.skycraft.tamriel.settlement.grammar.Sites.Kind.values())));
            return 0;
        }
        Culture cul = culture(StringArgumentType.getString(c, "culture"));
        int x = IntegerArgumentType.getInteger(c, "x"), z = IntegerArgumentType.getInteger(c, "z");
        Structure.GenerationContext ctx = context(level, x, z);
        long seed = level.getSeed() ^ x * 31L ^ z;
        Plan plan = new Plan("site:" + (x >> 4) + "," + (z >> 4), x, z, Layouts.Size.SITE.radius(), cul, defaultStyle(cul, x, z), seed,
                new Terrain(ctx), Direction.NORTH);
        com.skycraft.tamriel.settlement.grammar.Sites.layout(plan, kind);
        plan.resolve();
        List<SettlementPiece> pieces = new ArrayList<>();
        for (int i = 0; i < plan.specs.size(); i++) pieces.add(new SettlementPiece(plan.specs.get(i), seed ^ (i * 0x9E3779B97F4A7C15L), plan.key, i));
        paint(level, pieces);
        String name = "site_" + kind.name().toLowerCase(Locale.ROOT) + "_" + cul.id();
        int r = plan.radius;
        try {
            SettlementPreview.iso(level, x - r, z - r, x + r, z + r, 4, new File(DIR, name + "_iso.png"));
        } catch (IOException e) {
            Skycraft.LOGGER.error("Preview render failed", e);
        }
        say(c, name + ": " + pieces.size() + " pieces at " + x + " " + z);
        return pieces.size();
    }

    static int place(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        String id = StringArgumentType.getString(c, "id");
        Place p = Places.byId(id).orElse(null);
        if (p == null) {
            c.getSource().sendFailure(Component.literal("No place " + id));
            return 0;
        }
        int r = Math.max(p.radius(), Cities.reach(p)) + 8;
        for (int cx = (p.x() - r) >> 4; cx <= (p.x() + r) >> 4; cx++) {
            for (int cz = (p.z() - r) >> 4; cz <= (p.z() + r) >> 4; cz++) level.getChunk(cx, cz);
        }
        try {
            SettlementPreview.top(level, p.x() - r, p.z() - r, p.x() + r, p.z() + r, 2, new File(DIR, "place_" + id + "_top.png"));
            SettlementPreview.iso(level, p.x() - r, p.z() - r, p.x() + r, p.z() + r, 2, new File(DIR, "place_" + id + "_iso.png"));
        } catch (IOException e) {
            Skycraft.LOGGER.error("Preview render failed", e);
        }
        say(c, "Rendered " + id + " (" + p.x() + ", " + p.z() + ", r " + r + ")");
        return 1;
    }

    /**
     * {@code /skysettle plan <id>}: plans a named place exactly as world generation does (city plan, zoning, quarters),
     * without building it, and writes {@code plan_<id>.png} (a plan diagram) and {@code plan_<id>.json} (every spec in
     * local coordinates) to the preview folder.
     */
    static int planOne(CommandContext<CommandSourceStack> c) {
        String id = StringArgumentType.getString(c, "id");
        return exportPlan(c.getSource().getLevel(), id, msg -> say(c, msg)) ? 1 : 0;
    }

    /** {@code /skysettle plans}: {@link #planOne} for every place that has a city plan file. */
    static int planAll(CommandContext<CommandSourceStack> c) {
        int n = 0;
        for (var cp : com.skycraft.tamriel.settlement.plan.CityPlans.all()) {
            if (exportPlan(c.getSource().getLevel(), cp.id, msg -> say(c, msg))) n++;
        }
        say(c, "Exported " + n + " city plans");
        return n;
    }

    static boolean exportPlan(ServerLevel level, String id, java.util.function.Consumer<String> say) {
        Place p = Places.byId(id).orElse(null);
        if (p == null) {
            say.accept("No place " + id);
            return false;
        }
        long t0 = System.nanoTime();
        Plan plan = SettlementStructure.planPlace(context(level, p.x(), p.z()), p, "auto");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        try {
            SettlementPreview.plan(plan, 0, 3, new File(DIR, "plan_" + id + ".png"));
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("id", id);
            o.addProperty("x", p.x());
            o.addProperty("z", p.z());
            o.addProperty("radius", plan.radius);
            o.addProperty("reach", Cities.reach(p));
            o.addProperty("back", plan.back.getName());
            o.addProperty("culture", plan.culture.id());
            o.addProperty("style", plan.style);
            o.addProperty("base", plan.base);
            o.add("specs", com.skycraft.tamriel.settlement.plan.CityPlans.describe(plan));
            DIR.mkdirs();
            try (FileWriter fw = new FileWriter(new File(DIR, "plan_" + id + ".json"))) {
                fw.write(o.toString());
            }
        } catch (IOException e) {
            Skycraft.LOGGER.error("Plan export failed", e);
            return false;
        }
        say.accept("Plan " + id + ": " + plan.specs.size() + " specs in " + ms + " ms");
        return true;
    }

    static int compare(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        Culture cul = culture(StringArgumentType.getString(c, "culture"));
        Layouts.Size sz = size(StringArgumentType.getString(c, "size"));
        int x = IntegerArgumentType.getInteger(c, "x"), z = IntegerArgumentType.getInteger(c, "z");
        String[] row = compareRow(level, cul, defaultStyle(cul, x, z), sz, x, z, true);
        say(c, "before: " + row[0]);
        say(c, "after:  " + row[1]);
        return 1;
    }

    /** Plans a site the old way and the new way; returns {before, after, csv}. */
    static String[] compareRow(ServerLevel level, Culture cul, int style, Layouts.Size sz, int x, int z, boolean images) {
        Plan old = planGeneric(level, cul, style, sz, x, z, true);
        Metrics mo = Metrics.of(old, 0, old.radius);
        long t0 = System.nanoTime();
        Plan neu = planGeneric(level, cul, style, sz, x, z, false);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Metrics mn = Metrics.of(neu, 0, Zoning.builtRadius(sz, neu.radius) + 8);
        if (images) {
            String name = "compare_" + cul.id() + "_" + sz.name().toLowerCase(Locale.ROOT) + "_" + x + "_" + z;
            try {
                SettlementPreview.plan(old, 0, 3, new File(DIR, name + "_before.png"));
                SettlementPreview.plan(neu, 0, 3, new File(DIR, name + "_after.png"));
            } catch (IOException e) {
                Skycraft.LOGGER.error("Preview render failed", e);
            }
        }
        String csv = String.format(Locale.ROOT, "%s,%s,%d,%d,%.3f,%.3f,%d,%d,%d,%.3f,%.3f,%d,%d,%d,%d,%d,%.3f,%.1f,%.3f,%.1f,%s",
                cul.id(), sz.name().toLowerCase(Locale.ROOT), x, z, mo.density(), mo.variety(), mo.townBuildings(), mo.props(), mo.walls(),
                mn.density(), mn.variety(), mn.townBuildings(), mn.props() + mn.stalls(), mn.walls(), mn.distinct(), ms,
                mo.coverage(), mo.meanGap(), mn.coverage(), mn.meanGap(), lastForm);
        return new String[]{mo.toString(), mn + " (" + ms + " ms)", csv};
    }

    static int survey(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        List<String> lines = new ArrayList<>();
        lines.add("culture,size,x,z,before_density,before_variety,before_buildings,before_props,before_walls,after_density,after_variety,after_buildings,after_props,after_walls,after_designs,plan_ms,before_coverage,before_gap,after_coverage,after_gap,form");
        // one land site per culture: the anchor of a named place of that culture
        java.util.Map<Culture, Place> sites = new java.util.EnumMap<>(Culture.class);
        for (Place p : Places.all()) {
            if (!p.type().isSettlement()) continue;
            Culture cul = Culture.of(p.culture());
            sites.putIfAbsent(cul, p);
        }
        double bd = 0, ad = 0, bv = 0, av = 0, bc = 0, ac = 0, bg = 0, ag = 0;
        int n = 0;
        for (var e : sites.entrySet()) {
            Place p = e.getValue();
            for (Layouts.Size sz : new Layouts.Size[]{Layouts.Size.HAMLET, Layouts.Size.VILLAGE, Layouts.Size.TOWN, Layouts.Size.CITY}) {
                String[] row = compareRow(level, e.getKey(), defaultStyle(e.getKey(), p.x(), p.z()), sz, p.x(), p.z(), sz == Layouts.Size.TOWN);
                lines.add(row[2]);
                String[] f = row[2].split(",");
                bd += Double.parseDouble(f[4]);
                bv += Double.parseDouble(f[5]);
                ad += Double.parseDouble(f[9]);
                av += Double.parseDouble(f[10]);
                bc += Double.parseDouble(f[16]);
                bg += Double.parseDouble(f[17]);
                ac += Double.parseDouble(f[18]);
                ag += Double.parseDouble(f[19]);
                n++;
                Skycraft.LOGGER.info("[skysettle] {} {} before: {}", e.getKey().id(), sz, row[0]);
                Skycraft.LOGGER.info("[skysettle] {} {} after:  {}", e.getKey().id(), sz, row[1]);
            }
        }
        DIR.mkdirs();
        try (FileWriter fw = new FileWriter(new File(DIR, "survey.csv"))) {
            for (String l : lines) fw.write(l + "\n");
        } catch (IOException ex) {
            Skycraft.LOGGER.error("Could not write survey", ex);
        }
        say(c, String.format(Locale.ROOT, "Survey of %d plans: density %.0f%% -> %.0f%%, coverage %.0f%% -> %.0f%%, mean gap %.1f -> %.1f, variety %.0f%% -> %.0f%%", n,
                bd / n * 100, ad / n * 100, bc / n * 100, ac / n * 100, bg / n, ag / n, bv / n * 100, av / n * 100));
        return n;
    }

    static int render(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        int x1 = IntegerArgumentType.getInteger(c, "x1"), z1 = IntegerArgumentType.getInteger(c, "z1");
        int x2 = IntegerArgumentType.getInteger(c, "x2"), z2 = IntegerArgumentType.getInteger(c, "z2");
        String name = StringArgumentType.getString(c, "name");
        for (int cx = Math.min(x1, x2) >> 4; cx <= Math.max(x1, x2) >> 4; cx++)
            for (int cz = Math.min(z1, z2) >> 4; cz <= Math.max(z1, z2) >> 4; cz++) level.getChunk(cx, cz);
        try {
            SettlementPreview.iso(level, Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2), 3, new File(DIR, name + "_iso.png"));
            SettlementPreview.top(level, Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2), 3, new File(DIR, name + "_top.png"));
        } catch (IOException e) {
            Skycraft.LOGGER.error("Preview render failed", e);
        }
        say(c, "Rendered " + name);
        return 1;
    }
}
