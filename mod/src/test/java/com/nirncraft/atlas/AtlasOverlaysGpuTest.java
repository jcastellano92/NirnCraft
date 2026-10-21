package com.nirncraft.atlas;

import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.settlement.SettlementPiece;
import com.nirncraft.tamriel.settlement.build.Builders;
import com.nirncraft.tamriel.settlement.build.Canvas;
import com.nirncraft.tamriel.settlement.plan.Cities;
import com.nirncraft.tamriel.settlement.plan.Spec;
import com.nirncraft.testing.BoundedServerProvider;
import com.nirncraft.worldplan.street.StreetPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Structures from the plans on the GPU (atlas_overlays.slang) against Java's own generation of the same chunks: named
 * settlements' pieces (the server's SettlementPiece.postProcess per chunk, in piece order) and the street painter
 * (StreetPaint), over terrain made by Minecraft's NoiseBasedChunkGenerator (seed 1234; the GPU generator's terrain is
 * the same block for block, AtlasGenParityTest). Java's results are computed once per test set and cached under
 * NIRNCRAFT_OVL_REF (default E:/NirnCraft-scratch/overlays/ref; NIRNCRAFT_OVL_REFRESH=1 recomputes them, needed after
 * a builder changes). A dev tool, off by default: NIRNCRAFT_OVL_TEST=1, NIRNCRAFT_ATLAS_LIB=<atlas_core.dll>, optional
 * NIRNCRAFT_OVL_SETS="place:halfChunks,..." and NIRNCRAFT_OVL_OUT=<report.txt>. Seeded starts (generic settlements, working
 * sites; AtlasOverlayStarts, found as the server finds them) join with NIRNCRAFT_OVL_SEEDED="structure,..." (default
 * none; e.g. working_site,hamlet,farmstead,village): the nearest start of each to (NIRNCRAFT_OVL_SEEDED_AT, default
 * 6000,6000), its whole box the window.
 */
@EnabledIfEnvironmentVariable(named = "NIRNCRAFT_OVL_TEST", matches = "1")
@ExtendWith(BoundedServerProvider.class)
class AtlasOverlaysGpuTest {
    static final long SEED = 1234L;
    static final int REF_VERSION = 2;  // 2: Sanity keeps fluids (the owner, 2026-10-21)

    /** A test set: a named settlement and the window of chunks [cx0, cx1] x [cz0, cz1] compared. */
    record TestSet(String place, int cx0, int cz0, int cx1, int cz1) {
        String file() {
            return place + "_" + cx0 + "_" + cz0 + "_" + cx1 + "_" + cz1 + "_s" + SEED + ".bin.gz";
        }

        boolean inWindow(int cx, int cz) {
            return cx >= cx0 && cx <= cx1 && cz >= cz0 && cz <= cz1;
        }
    }

    /** Java's blocks for a set (global state ids, b = x + 16 z + 256 (y - minY)): terrain, pieces, pieces without sweeps, + streets. */
    record Ref(Map<Long, int[]> terrain, Map<Long, int[]> pieces, Map<Long, int[]> builders, Map<Long, int[]> streets) {}

    @Test
    void overlaysMatchJava(MinecraftServer server) throws Exception {
        long t0 = System.nanoTime();
        trace("begin setup");
        RegistryAccess ra = server.registryAccess();
        WorldDimensions dims = ra.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions();
        LevelStem stem = dims.dimensions().get(LevelStem.OVERWORLD);
        NoiseBasedChunkGenerator gen = (NoiseBasedChunkGenerator) stem.generator();
        NoiseGeneratorSettings settings = gen.generatorSettings().value();
        RandomState rs = RandomState.create(ra.lookupOrThrow(Registries.NOISE), SEED, settings);
        DimensionType dt = stem.type().value();
        LevelHeightAccessor heights = LevelHeightAccessor.create(dt.minY(), dt.height());
        PalettedContainerFactory containers = PalettedContainerFactory.create(ra);
        StructureManager structures = new StructureManager(null, null, null) {
            @Override
            public List<StructureStart> startsForStructure(int x, int z, Predicate<Structure> m) {
                return List.of();
            }
        };
        trace("end setup");
        StringBuilder report = new StringBuilder(String.format("overlays on the GPU against Java: seed %d, world y %d..%d%n", SEED,
                heights.getMinY(), heights.getMaxY()));
        trace("begin streetplan");
        long ps = System.nanoTime();
        StreetPlan plan = StreetPlan.get();
        double planS = (System.nanoTime() - ps) / 1e9;
        trace("end streetplan");
        trace(String.format("done streetplan %.1fs (%d settlements, %d nodes, %d edges, %d plots)", planS, plan.settlements(), plan.nodes(),
                plan.edges(), plan.plots()));
        report.append(String.format("street plan: %d settlements, %d nodes, %d edges, %d plots (%.1f s to load or plan)%n", plan.settlements(),
                plan.nodes(), plan.edges(), plan.plots(), planS));

        List<TestSet> sets = sets(plan);
        sets.addAll(seededSets(server, gen, rs, heights));
        report.append("sets: ").append(sets).append('\n');
        Path refDir = Path.of(System.getenv().getOrDefault("NIRNCRAFT_OVL_REF", "E:/NirnCraft-scratch/overlays/ref"));
        Files.createDirectories(refDir);
        boolean refresh = "1".equals(System.getenv("NIRNCRAFT_OVL_REFRESH"));

        Path lib = Path.of(System.getenv("NIRNCRAFT_ATLAS_LIB"));
        AtlasOvlNative nat = AtlasOvlNative.load(lib);
        if (nat == null) throw new IllegalStateException("no overlays in " + lib);
        trace("begin device");
        AtlasOvlNative.Ovl ovl = nat.create();
        if (ovl == null) throw new IllegalStateException("no Vulkan device for the overlays");
        trace("end device");
        report.append("device: ").append(ovl.device()).append('\n');

        // the street plan goes up once (all of it)
        int buckets = plan.buckets();
        long sw = System.nanoTime();
        int[] streetWords = AtlasOverlayTapes.streetWords(plan, heights, buckets);
        report.append(String.format("street plan words: %d (%.1f MB, %d buckets, packed in %.0f ms)%n", streetWords.length,
                streetWords.length * 4 / 1e6, buckets, (System.nanoTime() - sw) / 1e6));
        ovl.set(AtlasOvlNative.SET_STREETS, streetWords);

        // the generator with the overlays as a stage of its own batches (GPU to GPU), when the core has both
        StageRig rig = "0".equals(System.getenv("NIRNCRAFT_OVL_GEN")) ? null : StageRig.open(lib, nat, gen, settings, rs, heights, report);
        Totals all = new Totals();
        Score stageAll = new Score();
        int si = 0;
        for (TestSet set : sets) {
            si++;
            trace("begin set " + set.place());
            trace("progress sets " + (si - 1) + "/" + sets.size());
            AtlasOverlayStarts.Start raw = SEEDED_RAW.get(set.place());
            List<SettlementPiece> pieces = raw != null ? List.of() : SEEDED.containsKey(set.place()) ? SEEDED.get(set.place())
                    : pieces(plan, Places.byId(set.place()).orElseThrow());
            Path file = refDir.resolve(set.file());
            String key = raw != null ? rawKey(set, raw) : refKey(plan, set, pieces);
            Ref ref = refresh ? null : readRef(file, key);
            double refMs = 0;
            if (ref == null) {
                long rt = System.nanoTime();
                ref = raw != null ? referenceRaw(set, raw, gen, rs, heights, containers, structures)
                        : reference(set, pieces, gen, rs, heights, containers, structures);
                refMs = (System.nanoTime() - rt) / 1e6;
                writeRef(file, key, ref);
                trace(String.format("done reference %s %.1fs (%d chunks, cached in %s)", set.place(), refMs / 1000, ref.pieces().size(), file));
            } else {
                trace("done reference " + set.place() + " 0.0s (from the cache " + file + ")");
            }
            report.append(String.format("%n== %s: chunks %d..%d x %d..%d (%d), %d pieces in the plan; Java reference %s%n", set.place(),
                    set.cx0(), set.cx1(), set.cz0(), set.cz1(), ref.pieces().size(), raw != null ? raw.pieces().size() : pieces.size(),
                    refMs > 0 ? String.format("%.1f s (%.0f ms a chunk)", refMs / 1000, refMs / ref.pieces().size()) : "from the cache"));
            if (raw == null) compareSet(report, set, pieces, ref, heights, containers, ovl, plan, all);
            if (rig != null) stageSet(report, set, pieces, raw, ref, heights, containers, rig, plan, stageAll);
            trace("end set " + set.place());
        }
        trace("progress sets " + sets.size() + "/" + sets.size());
        report.append(String.format("%n== all sets%n"));
        all.print(report);
        if (rig != null) {
            report.append("  the generator's batches with the overlays as a stage (GPU to GPU): ").append(stageAll).append('\n');
            rig.close();
        }
        ovl.close();
        String text = report.toString();
        String out = System.getenv("NIRNCRAFT_OVL_OUT");
        if (out != null) Files.writeString(Path.of(out), text);
        System.out.println(text);
        trace(String.format("done overlaysMatchJava %.1fs pieces %.4f%% streets %.4f%%", (System.nanoTime() - t0) / 1e9,
                all.pieces.pct(), all.streets.pct()));
    }

    /**
     * The bake of the states the settlements' builders place (AtlasOverlayLive.STATES, for the generator program's
     * table): every piece of every settlement in the street plan built once over the plan's terrain (StreetTerrain:
     * stone, grass on top, water below the sea), the states its ops would write collected (charred where the piece
     * burnt), then the kernels' transforms and shapes. NIRNCRAFT_OVL_BAKE=<the resource file to write>.
     */
    @Test
    void bakeStates(MinecraftServer server) throws Exception {
        String outPath = System.getenv("NIRNCRAFT_OVL_BAKE");
        org.junit.jupiter.api.Assumptions.assumeTrue(outPath != null, "NIRNCRAFT_OVL_BAKE not set");
        long t0 = System.nanoTime();
        trace("begin streetplan");
        StreetPlan plan = StreetPlan.get();
        trace("end streetplan");
        java.util.Set<BlockState> placed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Canvas.Tape collect = (c, kind, x, y, z, st, a, b) -> {
            switch (kind) {
                case Canvas.Tape.FOUNDATION -> {
                    for (int yy = a; yy <= y; yy++) placed.add(AtlasOverlayTapes.charred(c, x, yy, z, st));
                }
                case Canvas.Tape.BED -> placed.add(st);
                case Canvas.Tape.NOT_AIR, Canvas.Tape.PLANT -> { }
                default -> placed.add(AtlasOverlayTapes.charred(c, x, y, z, st));
            }
        };
        long pieces = 0, failed = 0;
        trace("begin builders");
        for (int si = 0; si < plan.settlements(); si++) {
            progress("builders", si, plan.settlements());
            AtlasCapture.Env env = new AtlasCapture.Env((x, z) -> net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(),
                    (x, z) -> com.nirncraft.worldplan.street.StreetTerrain.land(x, z) < 63 ? 62 : Integer.MIN_VALUE, null, null);
            WorldGenLevel level = AtlasCapture.reader(com.nirncraft.worldplan.street.StreetTerrain::land, SEED, env);
            for (SettlementPiece p : AtlasOverlayTapes.pieces(plan.sId[si])) {
                BoundingBox b = p.getBoundingBox();
                BoundingBox clip = new BoundingBox(b.minX(), Math.max(b.minY() - SettlementPiece.FOUNDATION, -63), b.minZ(), b.maxX(),
                        Math.min(b.maxY(), 639), b.maxZ());
                Canvas c = AtlasOverlayTapes.canvas(p, level, clip);
                c.owner = p.owner();
                c.tape = collect;
                try {
                    Builders.build(c, p);
                } catch (RuntimeException e) {
                    failed++;
                }
                pieces++;
            }
        }
        progress("builders", plan.settlements(), plan.settlements());
        trace("end builders");
        // the seeded settlements (generic, working sites) of all Tamriel, found as the server finds them, built over
        // the same stand-in ground
        long seeded = 0;
        {
            RegistryAccess ra = server.registryAccess();
            WorldDimensions dims = ra.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions();
            LevelStem stem = dims.dimensions().get(LevelStem.OVERWORLD);
            NoiseBasedChunkGenerator gen = (NoiseBasedChunkGenerator) stem.generator();
            RandomState rs = RandomState.create(ra.lookupOrThrow(Registries.NOISE), SEED, gen.generatorSettings().value());
            DimensionType dt = stem.type().value();
            LevelHeightAccessor heights = LevelHeightAccessor.create(dt.minY(), dt.height());
            AtlasOverlayStarts starts = new AtlasOverlayStarts(ra, gen, gen.createState(ra.lookupOrThrow(Registries.STRUCTURE_SET), rs, SEED),
                    server.getStructureTemplateManager(), heights, AtlasOverlayStarts.ALL);
            // the dungeons: every start's plan, the first few of each structure, kit and theme painted (their writes)
            Map<String, Integer> painted = new HashMap<>();
            int perKind = Integer.parseInt(System.getenv().getOrDefault("NIRNCRAFT_OVL_BAKE_DUNGEONS", "3"));
            AtlasCapture.Env env = new AtlasCapture.Env((x, z) -> net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(),
                    (x, z) -> com.nirncraft.worldplan.street.StreetTerrain.land(x, z) < 63 ? 62 : Integer.MIN_VALUE, null, null);
            WorldGenLevel level = AtlasCapture.reader(com.nirncraft.worldplan.street.StreetTerrain::land, SEED, env);
            int x0 = com.nirncraft.tamriel.TamrielMap.MIN_X, x1 = com.nirncraft.tamriel.TamrielMap.MAX_X;
            int z0 = com.nirncraft.tamriel.TamrielMap.MIN_Z, z1 = com.nirncraft.tamriel.TamrielMap.MAX_Z;
            int strips = (z1 - z0 + 2047) / 2048;
            trace("begin seeded");
            for (int st = 0; st < strips; st++) {
                progress("seeded", st, strips);
                int sz0 = z0 + st * 2048, sz1 = Math.min(z1, sz0 + 2047);
                for (AtlasOverlayStarts.Start start : starts.in(x0, sz0, x1, sz1)) {
                    seeded++;
                    if (start.layer() != AtlasOverlayTapes.LAYER_SETTLEMENTS) {
                        String kind = start.structure();
                        if (start.pieces().get(0) instanceof com.nirncraft.dungeons.world.DungeonPiece dp) kind += " " + dp.kit + " " + dp.theme;
                        if (painted.merge(kind, 1, Integer::sum) > perKind) continue;
                        WorldGenLevel collecting = collecting(level, placed);
                        com.nirncraft.dungeons.world.Painter.RECORDING.set(Boolean.TRUE);
                        try {
                            for (net.minecraft.world.level.levelgen.structure.StructurePiece sp : start.pieces()) {
                                BoundingBox b = sp.getBoundingBox();
                                BoundingBox box = new BoundingBox(b.minX(), Math.max(b.minY(), -63), b.minZ(), b.maxX(), Math.min(b.maxY(), 639), b.maxZ());
                                try {
                                    sp.postProcess(collecting, null, gen, RandomSource.create(0L), box, new ChunkPos(b.minX() >> 4, b.minZ() >> 4),
                                            BlockPos.ZERO);
                                } catch (RuntimeException e) {
                                    failed++;
                                }
                                pieces++;
                            }
                        } finally {
                            com.nirncraft.dungeons.world.Painter.RECORDING.remove();
                        }
                        continue;
                    }
                    for (net.minecraft.world.level.levelgen.structure.StructurePiece sp : start.pieces()) {
                        if (!(sp instanceof SettlementPiece p)) continue;
                        BoundingBox b = p.getBoundingBox();
                        BoundingBox clip = new BoundingBox(b.minX(), Math.max(b.minY() - SettlementPiece.FOUNDATION, -63), b.minZ(), b.maxX(),
                                Math.min(b.maxY(), 639), b.maxZ());
                        Canvas c = AtlasOverlayTapes.canvas(p, level, clip);
                        c.owner = p.owner();
                        c.tape = collect;
                        try {
                            Builders.build(c, p);
                        } catch (RuntimeException e) {
                            failed++;
                        }
                        pieces++;
                    }
                }
            }
            progress("seeded", strips, strips);
            trace("end seeded");
        }
        List<BlockState> all = AtlasOverlayTapes.closure(placed);
        List<String> lines = new ArrayList<>();
        for (BlockState st : all) lines.add(net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(st));
        java.util.Collections.sort(lines);
        List<String> out = new ArrayList<>();
        out.add("# the block states the settlements' builders place (AtlasOverlaysGpuTest.bakeStates; " + plan.settlements()
                + " settlements and " + seeded + " seeded starts, " + pieces + " pieces), with the overlay kernels' transforms and shapes");
        out.addAll(lines);
        Files.createDirectories(Path.of(outPath).getParent());
        Files.write(Path.of(outPath), out);
        trace(String.format("done bakeStates %.1fs %d states (%d placed), %d pieces, %d failed", (System.nanoTime() - t0) / 1e9, all.size(),
                placed.size(), pieces, failed));
    }

    /** The level, every state written to it collected (the bake's dungeons: their writes go through the level). */
    static WorldGenLevel collecting(WorldGenLevel base, java.util.Set<BlockState> placed) {
        return (WorldGenLevel) java.lang.reflect.Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("setBlock") && args != null && args.length >= 2 && args[1] instanceof BlockState st) placed.add(st);
                    try {
                        return m.invoke(base, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    // ---- the overlays as a stage of the generator (atlas_ovl_create_for_gen) ----

    /** A Vulkan generator over seed 1234's program with the overlays attached to its batches. */
    record StageRig(AtlasGenProgram prog, AtlasGenPages pages, AtlasGenNative.Gen gen, AtlasOvlNative.Ovl ovl) implements AutoCloseable {
        static StageRig open(Path lib, AtlasOvlNative nat, NoiseBasedChunkGenerator g, NoiseGeneratorSettings settings, RandomState rs,
                             LevelHeightAccessor heights, StringBuilder report) {
            AtlasGenNative gn = AtlasGenNative.load(lib);
            if (gn == null) return null;
            trace("begin stage rig");
            // as in the game: the program carries the overlays' baked states (AtlasOverlayLive.register)
            AtlasGenProgram.addBlocks(AtlasOverlayLive::states);
            AtlasGenProgram prog = AtlasGenProgram.compile(g, settings, rs, heights, SEED, s -> 0);
            AtlasGenNative.Gen gen = gn.create(AtlasGenNative.BACKEND_VULKAN);
            if (gen == null) return null;
            gen.setProgram(prog.words);
            // the map on the device (its pages made there; the road stage reads TamrielMap.sample from it)
            report.append("stage: pages ").append(AtlasClientGen.devicePages(gen, prog, g.getBiomeSource())).append('\n');
            if (STORE) {
                if (!gen.storeSupported()) throw new IllegalStateException("NIRNCRAFT_OVL_STORE: no chunk store in this library");
                gen.storeEnable(STORE_SLOTS);
                report.append("stage: the generator's chunk store, ").append(STORE_SLOTS).append(" slots (atlas_gen_run_store)\n");
            }
            AtlasOvlNative.Ovl ovl = nat.createForGen(gen);
            trace("end stage rig");
            if (ovl == null) {
                gen.close();
                report.append("stage: unavailable (no atlas_ovl_create_for_gen, or the generator's device cannot)\n");
                return null;
            }
            report.append("stage: ").append(ovl.device()).append(", generator program ").append(prog.blocks.size()).append(" blocks\n");
            return new StageRig(prog, new AtlasGenPages(prog, rs, g.getBiomeSource(), heights), gen, ovl);
        }

        @Override
        public void close() {
            ovl.close();  // first: it detaches itself from the generator
            gen.close();
        }
    }

    /**
     * The window made by the generator with the overlays in its batch: the table's first ids the program's blocks,
     * tapes recorded over the same terrain, the batch's chunks stamped before they are packed; against Java's terrain +
     * postProcess + street paint (y -64 .. 575, the generator's columns).
     */
    static void stageSet(StringBuilder report, TestSet set, List<SettlementPiece> pieces, AtlasOverlayStarts.Start raw, Ref ref,
                         LevelHeightAccessor heights,
                         PalettedContainerFactory containers,
                         StageRig rig, StreetPlan plan, Score total) {
        trace("begin stage " + set.place());
        AtlasOverlayTapes tapes = new AtlasOverlayTapes(rig.prog().blocks);
        tapes.frozen = true;  // the program's table, as in the game
        tapes.keep = new BoundingBox(set.cx0() << 4, heights.getMinY(), set.cz0() << 4, (set.cx1() << 4) + 15, heights.getMaxY(),
                (set.cz1() << 4) + 15);
        if (raw != null) {
            int built = tapes.recordRaw(set.place(), raw.pieces(), window(ref.terrain(), heights, containers), set.cx0(), set.cz0(), set.cx1(),
                    set.cz1(), raw.layer());
            report.append(String.format("  recorded writes (layer %d): %d (piece, chunk) builds, %d ops, %d failed %s%n", raw.layer(), built, tapes.ops,
                    tapes.failed, tapes.failures));
        } else {
            tapes.record(set.place(), pieces, window(ref.terrain(), heights, containers), set.cx0(), set.cz0(), set.cx1(), set.cz1());
        }
        Map<Long, Integer> offsets = new HashMap<>();
        int[] tapeWords = tapes.tapeWords(offsets);
        int[] blockWords = tapes.blockWords();
        rig.ovl().set(AtlasOvlNative.SET_BLOCKS, blockWords);
        rig.ovl().set(AtlasOvlNative.SET_TAPES, tapeWords);
        rig.ovl().set(AtlasOvlNative.SET_STREETS, AtlasOverlayTapes.streetWords(plan, heights, 0));
        List<Long> keys = new ArrayList<>();
        for (int cz = set.cz0(); cz <= set.cz1(); cz++)
            for (int cx = set.cx0(); cx <= set.cx1(); cx++) keys.add(ChunkPos.pack(cx, cz));
        int n = keys.size();
        int[] xz = new int[n * 2];
        for (int i = 0; i < n; i++) {
            xz[i * 2] = ChunkPos.getX(keys.get(i));
            xz[i * 2 + 1] = ChunkPos.getZ(keys.get(i));
        }
        int[] miss = rig.gen().missing(xz);
        for (int i = 0; i < miss.length; i += 3)
            rig.gen().putPage(miss[i], miss[i + 1], miss[i + 2] != 0, rig.pages().page(miss[i], miss[i + 1], miss[i + 2] != 0));
        int[] ids = new int[n * AtlasGenNative.CHUNK_WORDS], flags = new int[n];
        int kindsWith = AtlasOvlNative.KIND_PIECES | AtlasOvlNative.KIND_SWEEPS | (raw != null ? 0 : AtlasOvlNative.KIND_STREETS)
                | AtlasOvlNative.KIND_UNDER | AtlasOvlNative.KIND_SURFACE;
        if (STORE) {
            storeSet(report, set, ref, rig, tapes, keys, xz, ids, flags, kindsWith, total, raw);
            return;
        }
        // without the stage, then with it (twice: the first pays the pipelines): the stage's own cost from the kernels
        rig.ovl().kinds(0);
        rig.gen().chunks(xz, 0, ids, flags);
        AtlasGenNative.Times bare = rig.gen().chunks(xz, 0, ids, flags);
        // (a dungeon's reference has no street paint: its set leaves the street painter out)
        rig.ovl().kinds(AtlasOvlNative.KIND_PIECES | AtlasOvlNative.KIND_SWEEPS | (raw != null ? 0 : AtlasOvlNative.KIND_STREETS)
                | AtlasOvlNative.KIND_UNDER | AtlasOvlNative.KIND_SURFACE);
        rig.gen().chunks(xz, 0, ids, flags);
        long stamped0 = rig.ovl().stamped();
        AtlasGenNative.Times with = rig.gen().chunks(xz, 0, ids, flags);
        // batch independence: every chunk again as a batch of its own (its neighbours only in the apron) must equal the
        // whole window's
        long batchDiff = 0;
        int[] one = new int[AtlasGenNative.CHUNK_WORDS], oneFlag = new int[1];
        for (int i = 0; i < n; i++) {
            rig.gen().chunks(new int[] {xz[i * 2], xz[i * 2 + 1]}, 0, one, oneFlag);
            for (int k = 0; k < AtlasGenNative.CHUNK_WORDS; k++) {
                int a = one[k], b2 = ids[i * AtlasGenNative.CHUNK_WORDS + k];
                if (a != b2) batchDiff += ((a ^ b2) & 0xFFFF) != 0 ? 1 : 0;
                if (a != b2) batchDiff += ((a ^ b2) >>> 16) != 0 ? 1 : 0;
            }
        }
        report.append(String.format("  batch independence: each chunk alone vs the whole window: %d blocks differ%n", batchDiff));
        Score sc = new Score();
        Map<String, Long> stagePairs = new HashMap<>();
        Map<String, String> stageEx = new HashMap<>();
        long flagged = 0;
        int levels = 640;
        for (int i = 0; i < n; i++) {
            if (flags[i] != 0) flagged++;
            int[] t = ref.terrain().get(keys.get(i)), rs = ref.streets().get(keys.get(i));
            for (int b = 0; b < 256 * levels; b++) {
                int w = ids[i * AtlasGenNative.CHUNK_WORDS + (b >> 1)];
                int id = (b & 1) != 0 ? w >>> 16 : w & 0xFFFF;
                int g = id < tapes.blocks.size() ? Block.getId(tapes.blocks.get(id)) : -1;
                boolean jc = rs[b] != t[b], gc = g != t[b];
                if (jc || gc) tally(sc, null, rs[b], g, jc, gc);
                if ((jc || gc) && rs[b] != g) {
                    String pk = name(t[b]) + ": " + name(rs[b]) + " -> " + (g < 0 ? "?" : name(g));
                    stagePairs.merge(pk, 1L, Long::sum);
                    ChunkPos cp = ChunkPos.unpack(keys.get(i));
                    stageEx.putIfAbsent(pk, (cp.getMinBlockX() + (b & 15)) + " " + (b / 256 - 64) + " " + (cp.getMinBlockZ() + (b >> 4 & 15)));
                }
            }
        }
        double stageMs = with.kernelMs() - bare.kernelMs();
        report.append(String.format("  frozen table: %d states, %d ops dropped (states it lacks: %s)%n", tapes.blocks.size(), tapes.missing,
                tapes.missingStates.stream().limit(5).map(Object::toString).toList()));
        report.append(String.format("  the generator's batch with the overlays as a stage: %s; %d chunks flagged; batch kernels %.2f ms without, %.2f ms with "
                        + "(the stage %.2f ms, %.4f ms a chunk); %d chunks stamped a batch%n", sc, flagged, bare.kernelMs(), with.kernelMs(), stageMs,
                stageMs / n, rig.ovl().stamped() - stamped0));
        report.append("  stage differences (terrain: Java -> GPU):\n");
        stagePairs.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(12)
                .forEach(e -> report.append(String.format("    %8d  %s   (e.g. %s)%n", e.getValue(), e.getKey(), stageEx.get(e.getKey()))));
        total.add(sc);
        trace(String.format("done stage %s %.4f%%", set.place(), sc.pct()));
    }

    /** NIRNCRAFT_OVL_STORE=1: the stages run as store stages (atlas_gen_run_store) instead of the batch's. */
    static final boolean STORE = "1".equals(System.getenv("NIRNCRAFT_OVL_STORE"));
    static final int STORE_SLOTS = Integer.parseInt(System.getenv().getOrDefault("NIRNCRAFT_OVL_STORE_SLOTS", "320"));

    /**
     * The window through the generator's store: its chunks and a ring round them (the sweeps' radius) asked for in the
     * given order, 64 a call, then calls without new chunks until no window chunk is left waiting; the window's
     * chunks' generator ids into ids (keys' order). Starts from an empty store. Returns the kernel ms.
     */
    static double storeWindow(StageRig rig, List<Long> keys, int[] ids, int[] flags, boolean reverse, int per) {
        rig.gen().storeKeep(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);  // empty
        Map<Long, Integer> want = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) want.put(keys.get(i), i);
        java.util.LinkedHashSet<Long> ask = new java.util.LinkedHashSet<>();
        for (long k : keys)
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) ask.add(ChunkPos.pack(ChunkPos.getX(k) + dx, ChunkPos.getZ(k) + dz));
        List<Long> order = new ArrayList<>(ask);
        if (reverse) java.util.Collections.reverse(order);
        int[] done = new int[AtlasGenNative.RUN_CHUNKS * 2], out = new int[AtlasGenNative.RUN_CHUNKS * AtlasGenNative.CHUNK_WORDS],
                fl = new int[AtlasGenNative.RUN_CHUNKS];
        AtlasGenNative.Times[] t = new AtlasGenNative.Times[1];
        int got = 0;
        double ms = 0;
        java.util.function.IntConsumer take = m -> {
            for (int j = 0; j < m; j++) {
                Integer i = want.remove(ChunkPos.pack(done[j * 2], done[j * 2 + 1]));
                if (i == null) continue;
                System.arraycopy(out, j * AtlasGenNative.CHUNK_WORDS, ids, i * AtlasGenNative.CHUNK_WORDS, AtlasGenNative.CHUNK_WORDS);
                flags[i] = fl[j];
            }
        };
        for (int at = 0; at < order.size(); at += per) {
            int m = Math.min(per, order.size() - at);
            int[] xz = new int[m * 2];
            for (int j = 0; j < m; j++) {
                xz[j * 2] = ChunkPos.getX(order.get(at + j));
                xz[j * 2 + 1] = ChunkPos.getZ(order.get(at + j));
            }
            take.accept(rig.gen().runStore(xz, done, out, fl, null, t));
            ms += t[0].kernelMs();
        }
        for (int round = 0; round < 64 && !want.isEmpty(); round++) {
            int m = rig.gen().runStore(new int[0], done, out, fl, null, t);
            ms += t[0].kernelMs();
            if (m == 0) break;
            take.accept(m);
        }
        if (!want.isEmpty()) throw new IllegalStateException("the store kept back " + want.size() + " window chunks");
        return ms;
    }

    /** stageSet through the generator's store: bare, with the stages, then another batching (each chunk alone, reversed). */
    static void storeSet(StringBuilder report, TestSet set, Ref ref, StageRig rig, AtlasOverlayTapes tapes, List<Long> keys, int[] xz,
                         int[] ids, int[] flags, int kindsWith, Score total, AtlasOverlayStarts.Start raw) {
        int n = keys.size();
        rig.ovl().kinds(0);
        storeWindow(rig, keys, ids, flags, false, 64);
        double bare = storeWindow(rig, keys, ids, flags, false, 64);
        rig.ovl().kinds(kindsWith);
        storeWindow(rig, keys, ids, flags, false, 64);
        long stamped0 = rig.ovl().stamped();
        double with = storeWindow(rig, keys, ids, flags, false, 64);
        int[] other = new int[ids.length], otherFlags = new int[n];
        storeWindow(rig, keys, other, otherFlags, true, 1);
        long batchDiff = 0;
        for (int k = 0; k < ids.length; k++) {
            int a = ids[k], b2 = other[k];
            if (a == b2) continue;
            batchDiff += ((a ^ b2) & 0xFFFF) != 0 ? 1 : 0;
            batchDiff += ((a ^ b2) >>> 16) != 0 ? 1 : 0;
        }
        report.append(String.format("  batch independence (the store): 64 a call vs one at a time, reversed: %d blocks differ%n", batchDiff));
        Score sc = new Score();
        Map<String, Long> pairs = new HashMap<>();
        Map<String, String> ex = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int[] t = ref.terrain().get(keys.get(i)), rs = ref.streets().get(keys.get(i));
            for (int b = 0; b < 256 * 640; b++) {
                int w = ids[i * AtlasGenNative.CHUNK_WORDS + (b >> 1)];
                int id = (b & 1) != 0 ? w >>> 16 : w & 0xFFFF;
                int g = id < tapes.blocks.size() ? Block.getId(tapes.blocks.get(id)) : -1;
                boolean jc = rs[b] != t[b], gc = g != t[b];
                if (jc || gc) tally(sc, null, rs[b], g, jc, gc);
                if ((jc || gc) && rs[b] != g) {
                    String pk = name(t[b]) + ": " + name(rs[b]) + " -> " + (g < 0 ? "?" : name(g));
                    pairs.merge(pk, 1L, Long::sum);
                    ChunkPos cp = ChunkPos.unpack(keys.get(i));
                    ex.putIfAbsent(pk, (cp.getMinBlockX() + (b & 15)) + " " + (b / 256 - 64) + " " + (cp.getMinBlockZ() + (b >> 4 & 15)));
                }
            }
        }
        report.append(String.format("  frozen table: %d states, %d ops dropped%n", tapes.blocks.size(), tapes.missing));
        report.append(String.format("  the generator's store with the overlays as store stages: %s; kernels %.2f ms without, %.2f ms with (%.4f ms a "
                + "chunk of the window and its ring); %d chunks stamped%n", sc, bare, with, (with - bare) / n, rig.ovl().stamped() - stamped0));
        report.append("  store differences (terrain: Java -> GPU):\n");
        pairs.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(12)
                .forEach(e -> report.append(String.format("    %8d  %s   (e.g. %s)%n", e.getValue(), e.getKey(), ex.get(e.getKey()))));
        total.add(sc);
        trace(String.format("done stage %s %.4f%% (store, %d differ between batchings)", set.place(), sc.pct(), batchDiff));
    }

    // ---- the road painter (RoadPaint) as a stage ----

    /**
     * The road painter on the GPU (atlas_overlays_roads.slang, a stage at ATLAS_GEN_STAGE_PAINT) against Java's
     * RoadPaint.Painter on the same terrain: chunks along the road plan by span kind (normal, bridge, culvert, ford,
     * stairs, tunnel, cut, fill, switchback); no structure mask in either (no tapes here; the reference level is not a
     * WorldGenRegion).
     * NIRNCRAFT_OVL_ROADS_N chunks a kind (default 6).
     */
    @Test
    void roadsMatchJava(MinecraftServer server) throws Exception {
        long t0 = System.nanoTime();
        RegistryAccess ra = server.registryAccess();
        WorldDimensions dims = ra.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions();
        LevelStem stem = dims.dimensions().get(LevelStem.OVERWORLD);
        NoiseBasedChunkGenerator gen = (NoiseBasedChunkGenerator) stem.generator();
        NoiseGeneratorSettings settings = gen.generatorSettings().value();
        RandomState rs = RandomState.create(ra.lookupOrThrow(Registries.NOISE), SEED, settings);
        DimensionType dt = stem.type().value();
        LevelHeightAccessor heights = LevelHeightAccessor.create(dt.minY(), dt.height());
        PalettedContainerFactory containers = PalettedContainerFactory.create(ra);
        StructureManager structures = new StructureManager(null, null, null) {
            @Override
            public List<StructureStart> startsForStructure(int x, int z, Predicate<Structure> m) {
                return List.of();
            }
        };
        trace("begin plans");
        StreetPlan streets = StreetPlan.get();
        com.nirncraft.worldplan.RoadPlan roads = com.nirncraft.worldplan.RoadPlan.get();
        trace("end plans");
        StringBuilder report = new StringBuilder("the road painter on the GPU against Java's RoadPaint.Painter (seed " + SEED + ")\n");
        // the chunks: by span kind, far from places
        int per = Integer.parseInt(System.getenv().getOrDefault("NIRNCRAFT_OVL_ROADS_N", "6"));
        String[] spanNames = {"normal", "cut", "fill", "bridge", "culvert", "ford", "switchback", "stairs", "tunnel"};
        Map<Long, String> chunksKind = new LinkedHashMap<>();
        int[] count = new int[spanNames.length];
        for (int i = 0; i + 1 < roads.vx.length; i++) {
            int sp = roads.vspan[i];
            if (sp < 0 || sp >= spanNames.length || count[sp] >= per) continue;
            if (roads.vseg[i] != roads.vseg[i + 1]) continue;
            int x = (int) Math.floor(roads.vx[i]), z = (int) Math.floor(roads.vz[i]);
            long k = ChunkPos.pack(x >> 4, z >> 4);
            if (chunksKind.containsKey(k)) continue;
            chunksKind.put(k, spanNames[sp]);
            count[sp]++;
        }
        report.append("chunks: ").append(chunksKind.size()).append(' ').append(java.util.Arrays.toString(count)).append('\n');
        // Java: terrain, then the painter (cached)
        Path refDir = Path.of(System.getenv().getOrDefault("NIRNCRAFT_OVL_REF", "E:/NirnCraft-scratch/overlays/ref"));
        Files.createDirectories(refDir);
        Path file = refDir.resolve("roads_n" + per + "_s" + SEED + ".bin.gz");
        String key = "ovlroads v1 seed " + SEED + " " + chunksKind.keySet() + " plan " + roads.vx.length + " " + roads.segStart.length
                + " states " + Block.BLOCK_STATE_REGISTRY.size() + " order " + registryOrder();
        Ref ref = "1".equals(System.getenv("NIRNCRAFT_OVL_REFRESH")) ? null : readRef(file, key);
        if (ref == null) {
            trace("begin road reference");
            Map<ChunkPos, ProtoChunk> biomeCache = new HashMap<>();
            Map<Long, int[]> terrain = new LinkedHashMap<>(), painted = new LinkedHashMap<>();
            java.lang.reflect.Constructor<?> painterNew = Class.forName("com.nirncraft.roads.RoadPaint$Painter")
                    .getDeclaredConstructor(WorldGenLevel.class, com.nirncraft.worldplan.RoadPlan.class, int.class, int.class);
            painterNew.setAccessible(true);
            java.lang.reflect.Method run = painterNew.getDeclaringClass().getDeclaredMethod("run");
            run.setAccessible(true);
            int done = 0;
            for (long k : chunksKind.keySet()) {
                ChunkPos cp = ChunkPos.unpack(k);
                ProtoChunk c = AtlasGenParityTest.javaChunk(cp, gen, rs, heights, containers, structures, biomeCache);
                int[] t = ids(c, heights);
                terrain.put(k, t);
                Map<Long, int[]> one = new HashMap<>();
                one.put(k, t);
                AtlasOverlayTapes.Window w = window(one, heights, containers);
                com.nirncraft.worldplan.RoadPlan.Hit hit = new com.nirncraft.worldplan.RoadPlan.Hit();
                if (roads.at(cp.getMinBlockX() + 8, cp.getMinBlockZ() + 8, hit))
                    run.invoke(painterNew.newInstance(w.region(cp.x(), cp.z()), roads, cp.getMinBlockX(), cp.getMinBlockZ()));
                painted.put(k, ids(w.chunk(cp.x(), cp.z()), heights));
                progress("road reference", ++done, chunksKind.size());
            }
            ref = new Ref(terrain, painted, painted, painted);
            writeRef(file, key, ref);
            trace("end road reference");
        }
        // the GPU: the generator with the overlays' stages (no tapes: roads alone)
        Path lib = Path.of(System.getenv("NIRNCRAFT_ATLAS_LIB"));
        AtlasOvlNative nat = AtlasOvlNative.load(lib);
        StageRig rig = StageRig.open(lib, nat, gen, settings, rs, heights, report);
        if (rig == null) throw new IllegalStateException("no stage rig");
        AtlasOverlayTapes tapes = new AtlasOverlayTapes(rig.prog().blocks);
        tapes.frozen = true;
        rig.ovl().set(AtlasOvlNative.SET_BLOCKS, tapes.blockWords());
        rig.ovl().set(AtlasOvlNative.SET_TAPES, tapes.tapeWords(new HashMap<>()));
        rig.ovl().set(AtlasOvlNative.SET_STREETS, AtlasOverlayTapes.streetWords(streets, heights, 0));
        rig.ovl().set(AtlasOvlNative.SET_ROADS, com.nirncraft.roads.RoadPaintGpu.words(roads, st -> {
            int i = tapes.idIfPresent(st);
            return i < 0 ? 0 : i;
        }));
        rig.ovl().kinds(AtlasOvlNative.KIND_ROADS | AtlasOvlNative.KIND_MBNL);
        Score all = new Score();
        Map<String, Score> byKind = new TreeMap<>();
        Map<String, Long> pairs = new TreeMap<>();
        Map<String, String> example = new HashMap<>();
        int[] ids = new int[AtlasGenNative.CHUNK_WORDS], flag = new int[1];
        double gpuMs = 0;
        int done = 0;
        for (var e : chunksKind.entrySet()) {
            ChunkPos cp = ChunkPos.unpack(e.getKey());
            // the batch's kernels without the road stage, then with it (pages warm): the stage's own time
            rig.ovl().kinds(AtlasOvlNative.KIND_MBNL);
            rig.gen().chunks(new int[] {cp.x(), cp.z()}, 0, ids, flag);
            AtlasGenNative.Times bare = rig.gen().chunks(new int[] {cp.x(), cp.z()}, 0, ids, flag);
            rig.ovl().kinds(AtlasOvlNative.KIND_ROADS | AtlasOvlNative.KIND_MBNL);
            AtlasGenNative.Times tm = rig.gen().chunks(new int[] {cp.x(), cp.z()}, 0, ids, flag);
            gpuMs += tm.totalMs() - bare.totalMs();
            int[] t = ref.terrain().get(e.getKey()), r = ref.pieces().get(e.getKey());
            Score k = byKind.computeIfAbsent(e.getValue(), x -> new Score());
            for (int b = 0; b < 256 * 640; b++) {
                int w = ids[b >> 1], id = (b & 1) != 0 ? w >>> 16 : w & 0xFFFF;
                int g = id < tapes.blocks.size() ? Block.getId(tapes.blocks.get(id)) : -1;
                boolean jc = r[b] != t[b], gc = g != t[b];
                if (!jc && !gc) continue;
                tally(all, k, r[b], g, jc, gc);
                if (r[b] != g) {
                    String pk = name(r[b]) + " -> " + (g < 0 ? "?" : name(g));
                    pairs.merge(pk, 1L, Long::sum);
                    example.putIfAbsent(pk, (cp.getMinBlockX() + (b & 15)) + " " + (b / 256 - 64) + " " + (cp.getMinBlockZ() + (b >> 4 & 15)));
                }
            }
            progress("road gpu", ++done, chunksKind.size());
        }
        rig.close();
        report.append("road paint: ").append(all).append('\n');
        byKind.forEach((k, v) -> report.append(String.format("  %-10s %s%n", k, v)));
        report.append(String.format("  the road stage: %.2f ms for %d chunks, %.3f ms a chunk (a batch with it minus without, one chunk a batch)%n",
                gpuMs, chunksKind.size(), gpuMs / chunksKind.size()));
        report.append("  differences (Java -> GPU):\n");
        pairs.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(30)
                .forEach(e -> report.append(String.format("    %8d  %s   (e.g. %s)%n", e.getValue(), e.getKey(), example.get(e.getKey()))));
        String out = System.getenv("NIRNCRAFT_OVL_OUT");
        if (out != null) Files.writeString(Path.of(out + ".roads.txt"), report.toString());
        System.out.println(report);
        trace(String.format("done roadsMatchJava %.1fs %.4f%%", (System.nanoTime() - t0) / 1e9, all.pct()));
    }

    // ---- test sets --------------------------------------------------------------------------------------------

    static List<TestSet> sets(StreetPlan plan) {
        String spec = System.getenv().getOrDefault("NIRNCRAFT_OVL_SETS", "riverwood:3,whiterun:4,rorikstead:3,pelagia_farm:2");
        List<TestSet> out = new ArrayList<>();
        for (String part : spec.split(",")) {
            String[] kv = part.trim().split(":");
            int s = plan.settlement(kv[0]);
            if (s < 0) {
                System.out.println("TRACE skip set " + kv[0] + " (not in the street plan)");
                continue;
            }
            int r = kv.length > 1 ? Integer.parseInt(kv[1]) : 3;
            int cx = plan.sX[s] >> 4, cz = plan.sZ[s] >> 4;
            out.add(new TestSet(kv[0], cx - r, cz - r, cx + r - 1, cz + r - 1));
        }
        return out;
    }

    /**
     * The state registry's order (the references hold global state ids, and some mod states take other ids from run
     * to run: a cached reference read with the new ids would name other states, so it is made again).
     */
    static String registryOrder() {
        long h = 0xcbf29ce484222325L;
        for (BlockState st : Block.BLOCK_STATE_REGISTRY) {
            h ^= st.toString().hashCode();
            h *= 0x100000001b3L;
        }
        return Long.toHexString(h);
    }

    /** The seeded starts' pieces by their key (the test set's place). */
    static final Map<String, List<SettlementPiece>> SEEDED = new HashMap<>();
    /** The starts recorded as raw writes (the dungeons) by their key. */
    static final Map<String, AtlasOverlayStarts.Start> SEEDED_RAW = new HashMap<>();

    /** A raw start's reference key: its pieces' classes and boxes. */
    static String rawKey(TestSet set, AtlasOverlayStarts.Start raw) {
        long h = 0xcbf29ce484222325L;
        for (net.minecraft.world.level.levelgen.structure.StructurePiece p : raw.pieces()) {
            BoundingBox b = p.getBoundingBox();
            for (int v : new int[] {p.getClass().getSimpleName().hashCode(), b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()}) {
                h ^= v;
                h *= 0x100000001b3L;
            }
        }
        return "ovlraw v" + REF_VERSION + " seed " + SEED + " " + set + " " + raw.structure() + " " + Long.toHexString(h) + " states "
                + Block.BLOCK_STATE_REGISTRY.size();
    }

    /**
     * A raw start's reference: Java's terrain for the window and two chunks round it, then the server's placeInChunk
     * per chunk (z, then x): every piece meeting the chunk's writable area, its postProcess on the chunk's region
     * (spawns left out on both sides: Painter.RECORDING).
     */
    static Ref referenceRaw(TestSet set, AtlasOverlayStarts.Start raw, NoiseBasedChunkGenerator gen, RandomState rs, LevelHeightAccessor heights,
                            PalettedContainerFactory containers, StructureManager structures) throws Exception {
        Map<ChunkPos, ProtoChunk> biomeCache = new HashMap<>();
        Map<Long, int[]> terrain = new LinkedHashMap<>();
        int n = (set.cx1() - set.cx0() + 5) * (set.cz1() - set.cz0() + 5), done = 0;
        trace("begin terrain " + set.place());
        for (int cz = set.cz0() - 2; cz <= set.cz1() + 2; cz++)
            for (int cx = set.cx0() - 2; cx <= set.cx1() + 2; cx++) {
                ProtoChunk c = AtlasGenParityTest.javaChunk(new ChunkPos(cx, cz), gen, rs, heights, containers, structures, biomeCache);
                terrain.put(ChunkPos.pack(cx, cz), ids(c, heights));
                progress("terrain", ++done, n);
            }
        trace("end terrain " + set.place());
        AtlasOverlayTapes.Window w = window(terrain, heights, containers);
        BoundingBox first = raw.pieces().get(0).getBoundingBox();
        BlockPos reference = new BlockPos(first.getCenter().getX(), first.minY(), first.getCenter().getZ());
        trace("begin postProcess " + set.place());
        com.nirncraft.dungeons.world.Painter.RECORDING.set(Boolean.TRUE);
        try {
            for (int cz = set.cz0(); cz <= set.cz1(); cz++)
                for (int cx = set.cx0(); cx <= set.cx1(); cx++) {
                    ChunkPos cp = new ChunkPos(cx, cz);
                    BoundingBox chunkBox = new BoundingBox(cp.getMinBlockX(), heights.getMinY() + 1, cp.getMinBlockZ(), cp.getMaxBlockX(),
                            heights.getMaxY(), cp.getMaxBlockZ());
                    WorldGenLevel level = w.region(cx, cz);
                    for (net.minecraft.world.level.levelgen.structure.StructurePiece p : raw.pieces()) {
                        if (!p.getBoundingBox().intersects(chunkBox)) continue;
                        try {
                            p.postProcess(level, null, gen, RandomSource.create(0L), chunkBox, cp, reference);
                        } catch (RuntimeException e) {
                            System.out.println("TRACE piece failed " + set.place() + " " + e);
                        }
                    }
                }
        } finally {
            com.nirncraft.dungeons.world.Painter.RECORDING.remove();
        }
        trace("end postProcess " + set.place());
        Map<Long, int[]> withPieces = new LinkedHashMap<>();
        for (int cz = set.cz0(); cz <= set.cz1(); cz++)
            for (int cx = set.cx0(); cx <= set.cx1(); cx++) withPieces.put(ChunkPos.pack(cx, cz), ids(w.chunk(cx, cz), heights));
        return new Ref(terrain, withPieces, withPieces, withPieces);
    }

    /**
     * The nearest start of each structure NIRNCRAFT_OVL_SEEDED names to NIRNCRAFT_OVL_SEEDED_AT, found as the server
     * finds them (AtlasOverlayStarts over the generator's structure state), in rings of 2048 blocks out to 16384.
     */
    static List<TestSet> seededSets(MinecraftServer server, NoiseBasedChunkGenerator gen, RandomState rs, LevelHeightAccessor heights) {
        String spec = System.getenv().getOrDefault("NIRNCRAFT_OVL_SEEDED", "");
        List<TestSet> out = new ArrayList<>();
        if (spec.isBlank()) return out;
        String[] at = System.getenv().getOrDefault("NIRNCRAFT_OVL_SEEDED_AT", "6000,6000").split(",");
        int ox = Integer.parseInt(at[0].trim()), oz = Integer.parseInt(at[1].trim());
        trace("begin seeded starts");
        var state = gen.createState(server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET), rs, SEED);
        AtlasOverlayStarts starts = new AtlasOverlayStarts(server.registryAccess(), gen, state, server.getStructureTemplateManager(), heights,
                AtlasOverlayStarts.ALL);
        java.util.Set<String> want = new java.util.LinkedHashSet<>();
        for (String w : spec.split(",")) if (!w.isBlank()) want.add(w.trim());
        Map<String, AtlasOverlayStarts.Start> found = new LinkedHashMap<>();
        for (int r = 2048; r <= 16384 && found.size() < want.size(); r += 2048) {
            List<AtlasOverlayStarts.Start> list = new ArrayList<>(starts.in(ox - r, oz - r, ox + r, oz + r));
            list.sort(java.util.Comparator.comparingLong(st -> {
                long dx = st.chunk().getMiddleBlockX() - ox, dz = st.chunk().getMiddleBlockZ() - oz;
                return dx * dx + dz * dz;
            }));
            for (AtlasOverlayStarts.Start st : list) {
                String name = st.structure().substring(st.structure().indexOf(':') + 1);
                if (want.contains(name) && !found.containsKey(name)) found.put(name, st);
            }
            trace("progress seeded " + found.size() + "/" + want.size() + " (" + starts.sets() + " sets, ring " + r + ")");
        }
        for (AtlasOverlayStarts.Start st : found.values()) {
            BoundingBox b = st.box();
            if (st.layer() == AtlasOverlayTapes.LAYER_SETTLEMENTS) SEEDED.put(st.key(), st.settlementPieces());
            else SEEDED_RAW.put(st.key(), st);
            out.add(new TestSet(st.key(), b.minX() >> 4, b.minZ() >> 4, b.maxX() >> 4, b.maxZ() >> 4));
        }
        for (String w : want) if (!found.containsKey(w)) System.out.println("TRACE skip seeded " + w + " (no start found)");
        trace("end seeded starts");
        return out;
    }

    /** The settlement's pieces as SettlementStructure adds them (OMIT left out, index and seed kept). */
    static List<SettlementPiece> pieces(StreetPlan plan, Place place) {
        List<Spec> specs = plan.layout(place.id());
        long seed = Cities.seed(place);
        List<SettlementPiece> out = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            Spec s = specs.get(i);
            if (s.has(Spec.OMIT)) continue;
            out.add(new SettlementPiece(s, seed ^ (i * 0x9E3779B97F4A7C15L), place.id(), i));
        }
        return out;
    }

    // ---- Java's reference ---------------------------------------------------------------------------------------

    static Ref reference(TestSet set, List<SettlementPiece> pieces, NoiseBasedChunkGenerator gen, RandomState rs,
                         LevelHeightAccessor heights, PalettedContainerFactory containers, StructureManager structures) throws Exception {
        // terrain for the window and two chunks round it (a piece reads and a bed or a sweep writes a chunk out)
        Map<ChunkPos, ProtoChunk> biomeCache = new HashMap<>();
        Map<Long, int[]> terrain = new LinkedHashMap<>();
        int n = (set.cx1() - set.cx0() + 5) * (set.cz1() - set.cz0() + 5), done = 0;
        trace("begin terrain " + set.place());
        for (int cz = set.cz0() - 2; cz <= set.cz1() + 2; cz++)
            for (int cx = set.cx0() - 2; cx <= set.cx1() + 2; cx++) {
                ProtoChunk c = AtlasGenParityTest.javaChunk(new ChunkPos(cx, cz), gen, rs, heights, containers, structures, biomeCache);
                terrain.put(ChunkPos.pack(cx, cz), ids(c, heights));
                progress("terrain", ++done, n);
            }
        trace("end terrain " + set.place());
        Map<Long, int[]> withPieces = new LinkedHashMap<>(), builders = new LinkedHashMap<>(), streets = new LinkedHashMap<>();
        // the server's path: per chunk, every piece whose box meets the chunk's writable area, in piece order
        // (SettlementPiece.postProcess: the builder on a chunk-clipped canvas, then its sweeps)
        for (int pass = 0; pass < 2; pass++) {
            trace("begin " + (pass == 0 ? "postProcess " : "builders ") + set.place());
            AtlasOverlayTapes.Window w = window(terrain, heights, containers);
            int k = 0, total = (set.cx1() - set.cx0() + 1) * (set.cz1() - set.cz0() + 1);
            for (int cz = set.cz0(); cz <= set.cz1(); cz++)
                for (int cx = set.cx0(); cx <= set.cx1(); cx++) {
                    WorldGenLevel level = w.region(cx, cz);
                    ChunkPos cp = new ChunkPos(cx, cz);
                    BoundingBox chunkBox = new BoundingBox(cp.getMinBlockX(), heights.getMinY() + 1, cp.getMinBlockZ(), cp.getMaxBlockX(),
                            heights.getMaxY(), cp.getMaxBlockZ());
                    for (SettlementPiece p : pieces) {
                        if (!p.getBoundingBox().intersects(chunkBox)) continue;
                        if (pass == 0) {
                            p.postProcess(level, null, gen, RandomSource.create(0L), chunkBox, cp, BlockPos.ZERO);
                        } else {
                            BoundingBox b = p.getBoundingBox();
                            BoundingBox clip = new BoundingBox(Math.max(chunkBox.minX(), b.minX()),
                                    Math.max(chunkBox.minY(), b.minY() - SettlementPiece.FOUNDATION), Math.max(chunkBox.minZ(), b.minZ()),
                                    Math.min(chunkBox.maxX(), b.maxX()), Math.min(chunkBox.maxY(), b.maxY()), Math.min(chunkBox.maxZ(), b.maxZ()));
                            if (clip.minX() > clip.maxX() || clip.minZ() > clip.maxZ() || clip.minY() > clip.maxY()) continue;
                            Canvas c = AtlasOverlayTapes.canvas(p, level, clip);
                            c.owner = p.owner();
                            try {
                                Builders.build(c, p);
                            } catch (RuntimeException e) {
                                // as postProcess: one piece failing must not stop the chunk
                            }
                        }
                    }
                    progress(pass == 0 ? "postProcess" : "builders", ++k, total);
                }
            for (int cz = set.cz0(); cz <= set.cz1(); cz++)
                for (int cx = set.cx0(); cx <= set.cx1(); cx++)
                    (pass == 0 ? withPieces : builders).put(ChunkPos.pack(cx, cz), ids(w.chunk(cx, cz), heights));
            if (pass == 0) {
                // then the street painter over the same chunks (the top layer step's features run after its structures)
                java.lang.reflect.Method repaint = Class.forName("com.nirncraft.worldplan.street.StreetPaint")
                        .getDeclaredMethod("repaint", WorldGenLevel.class, int.class, int.class);
                repaint.setAccessible(true);
                for (int cz = set.cz0(); cz <= set.cz1(); cz++)
                    for (int cx = set.cx0(); cx <= set.cx1(); cx++) {
                        StreetPlan sp = StreetPlan.get();
                        if (sp.bucket(cx, cz) < 0) continue;
                        repaint.invoke(null, w.region(cx, cz), cx << 4, cz << 4);
                    }
                for (int cz = set.cz0(); cz <= set.cz1(); cz++)
                    for (int cx = set.cx0(); cx <= set.cx1(); cx++) streets.put(ChunkPos.pack(cx, cz), ids(w.chunk(cx, cz), heights));
            }
            trace("end " + (pass == 0 ? "postProcess " : "builders ") + set.place());
        }
        return new Ref(terrain, withPieces, builders, streets);
    }

    /** A window of fresh proto chunks over the cached terrain (null outside it: hasChunk false there). */
    static AtlasOverlayTapes.Window window(Map<Long, int[]> terrain, LevelHeightAccessor heights, PalettedContainerFactory containers) {
        return new AtlasOverlayTapes.Window(heights, SEED, k -> {
            int[] ids = terrain.get(k);
            return ids == null ? null : chunk(ChunkPos.unpack(k), ids, heights, containers);
        });
    }

    static ProtoChunk chunk(ChunkPos pos, int[] ids, LevelHeightAccessor heights, PalettedContainerFactory containers) {
        ProtoChunk c = new ProtoChunk(pos, UpgradeData.EMPTY, heights, containers, null);
        LevelChunkSection[] secs = c.getSections();
        for (int s = 0; s < secs.length; s++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        int id = ids[x + 16 * z + 256 * (s * 16 + y)];
                        if (id != 0) secs[s].setBlockState(x, y, z, Block.stateById(id), false);
                    }
        Heightmap.primeHeightmaps(c, EnumSet.allOf(Heightmap.Types.class));
        c.setPersistedStatus(ChunkStatus.TERRAIN);
        return c;
    }

    /** A proto chunk's blocks as global state ids, b = x + 16 z + 256 (y - minY). */
    static int[] ids(ProtoChunk c, LevelHeightAccessor heights) {
        int levels = heights.getHeight();
        int[] out = new int[256 * levels];
        LevelChunkSection[] secs = c.getSections();
        for (int s = 0; s < secs.length; s++) {
            if (secs[s].hasOnlyAir()) continue;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) out[x + 16 * z + 256 * (s * 16 + y)] = Block.getId(secs[s].getBlockState(x, y, z));
        }
        return out;
    }

    // ---- the cache ----------------------------------------------------------------------------------------------

    static String refKey(StreetPlan plan, TestSet set, List<SettlementPiece> pieces) {
        long h = 0xcbf29ce484222325L;
        for (SettlementPiece p : pieces) {
            BoundingBox b = p.getBoundingBox();
            for (int v : new int[] {p.index, p.spec.kind.ordinal(), b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), p.spec.variant,
                    p.spec.flags, p.spec.floor}) {
                h ^= v;
                h *= 0x100000001b3L;
            }
        }
        h ^= plan.nodes() * 31L + plan.edges();
        return "ovlref v" + REF_VERSION + " seed " + SEED + " " + set + " plan " + Long.toHexString(h) + " states " + Block.BLOCK_STATE_REGISTRY.size() + " order " + registryOrder();
    }

    static Ref readRef(Path file, String key) {
        if (!Files.exists(file)) return null;
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (!in.readUTF().equals(key)) return null;
            List<Map<Long, int[]>> maps = new ArrayList<>();
            for (int m = 0; m < 4; m++) {
                Map<Long, int[]> map = new LinkedHashMap<>();
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    long k = in.readLong();
                    int[] a = new int[in.readInt()];
                    for (int j = 0; j < a.length; j++) a[j] = in.readInt();
                    map.put(k, a);
                }
                maps.add(map);
            }
            return new Ref(maps.get(0), maps.get(1), maps.get(2), maps.get(3));
        } catch (Exception e) {
            System.out.println("TRACE cache unreadable " + file + ": " + e);
            return null;
        }
    }

    static void writeRef(Path file, String key, Ref r) throws Exception {
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file))))) {
            out.writeUTF(key);
            for (Map<Long, int[]> map : List.of(r.terrain(), r.pieces(), r.builders(), r.streets())) {
                out.writeInt(map.size());
                for (var e : map.entrySet()) {
                    out.writeLong(e.getKey());
                    out.writeInt(e.getValue().length);
                    for (int v : e.getValue()) out.writeInt(v);
                }
            }
        }
    }

    // ---- the GPU and the comparison -----------------------------------------------------------------------------

    /** Agreement over the blocks either side changed. */
    static final class Score {
        long touched, same, javaOnly, gpuOnly;

        double pct() {
            return touched == 0 ? 100.0 : 100.0 * same / touched;
        }

        void add(Score o) {
            touched += o.touched;
            same += o.same;
            javaOnly += o.javaOnly;
            gpuOnly += o.gpuOnly;
        }

        @Override
        public String toString() {
            return String.format("%.4f %% of %d blocks (%d differ; Java changed and the GPU not: %d, the GPU and Java not: %d)", pct(), touched,
                    touched - same, javaOnly, gpuOnly);
        }
    }

    static final class Totals {
        final Score pieces = new Score(), builders = new Score(), streets = new Score();
        final Map<String, Score> byKind = new TreeMap<>();
        long blocks, blocksSame, columns;
        double piecesMs, streetsMs;

        void print(StringBuilder r) {
            r.append("  pieces + sweep vs the server's postProcess:         ").append(pieces).append('\n');
            r.append("  pieces alone vs the builders alone (no sweeps):     ").append(builders).append('\n');
            r.append("  street paint:                                       ").append(streets).append('\n');
            r.append("  by piece kind (postProcess):\n");
            byKind.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().touched, a.getValue().touched))
                    .forEach(e -> r.append(String.format("    %-15s %s%n", e.getKey(), e.getValue())));
            r.append(String.format("  whole window: %.5f %% of %d blocks equal (terrain + pieces + streets)%n", 100.0 * blocksSame / Math.max(1, blocks), blocks));
            r.append(String.format("  GPU: pieces + sweep %.3f ms, streets %.3f ms for %d columns: %.5f ms a column (%.4f ms a chunk)%n", piecesMs, streetsMs,
                    columns, (piecesMs + streetsMs) / Math.max(1, columns), (piecesMs + streetsMs) / Math.max(1, columns / 256.0)));
        }
    }

    static void compareSet(StringBuilder report, TestSet set, List<SettlementPiece> pieces, Ref ref, LevelHeightAccessor heights,
                           PalettedContainerFactory containers, AtlasOvlNative.Ovl ovl, StreetPlan plan, Totals all) throws Exception {
        // tapes: each piece built once, unclipped but for the window (its ops outside it are not kept)
        AtlasOverlayTapes tapes = new AtlasOverlayTapes();
        int bx0 = set.cx0() << 4, bz0 = set.cz0() << 4, bx1 = (set.cx1() << 4) + 15, bz1 = (set.cz1() << 4) + 15;
        tapes.keep = new BoundingBox(bx0, heights.getMinY(), bz0, bx1, heights.getMaxY(), bz1);
        trace("begin record " + set.place());
        long rt = System.nanoTime();
        // in the server's order over the window's chunks (the reference's order: z, then x)
        int built = tapes.record(set.place(), pieces, window(ref.terrain(), heights, containers), set.cx0(), set.cz0(), set.cx1(), set.cz1());
        double recMs = (System.nanoTime() - rt) / 1e6;
        trace(String.format("end record %s", set.place()));
        trace(String.format("done record %s %.1fs (%d pieces, %d ops, %d failed)", set.place(), recMs / 1000, built, tapes.ops, tapes.failed));
        // the batch: the window's chunks in table ids
        List<Long> keys = new ArrayList<>();
        for (int cz = set.cz0(); cz <= set.cz1(); cz++)
            for (int cx = set.cx0(); cx <= set.cx1(); cx++) keys.add(ChunkPos.pack(cx, cz));
        int levels = heights.getHeight(), words = levels * 128, n = keys.size();
        int[] terrainIds = new int[n * words];
        for (int i = 0; i < n; i++) pack(ref.terrain().get(keys.get(i)), tapes, terrainIds, i * words);
        Map<Long, Integer> offsets = new HashMap<>();
        int[] tapeWords = tapes.tapeWords(offsets);
        int[] blockWords = tapes.blockWords();  // after every id the tapes and terrain use (and the transforms)
        int ci = AtlasOverlayTapes.CHUNK_INTS;
        int[] chunks = new int[n * ci];
        for (int i = 0; i < n; i++) {
            ChunkPos cp = ChunkPos.unpack(keys.get(i));
            chunks[i * ci] = cp.x();
            chunks[i * ci + 1] = cp.z();
            chunks[i * ci + 2] = offsets.getOrDefault(keys.get(i), -1);
            chunks[i * ci + 3] = plan.bucket(cp.x(), cp.z());
            chunks[i * ci + 4] = keys.indexOf(ChunkPos.pack(cp.x(), cp.z() - 1));
            chunks[i * ci + 5] = keys.indexOf(ChunkPos.pack(cp.x() + 1, cp.z()));
            chunks[i * ci + 6] = keys.indexOf(ChunkPos.pack(cp.x(), cp.z() + 1));
            chunks[i * ci + 7] = keys.indexOf(ChunkPos.pack(cp.x() - 1, cp.z()));
        }
        ovl.set(AtlasOvlNative.SET_BLOCKS, blockWords);
        ovl.set(AtlasOvlNative.SET_TAPES, tapeWords);
        report.append(String.format("tapes: %d pieces recorded in %.0f ms (%.2f ms a piece), %d ops, %d tape words (%.1f MB), %d block states, %d pieces failed%s%n",
                built, recMs, recMs / Math.max(1, built), tapes.ops, tapeWords.length, tapeWords.length * 4 / 1e6, tapes.blocks.size(), tapes.failed,
                tapes.failures.isEmpty() ? "" : " " + tapes.failures));
        // the pieces alone (against the builders alone), the pieces and their sweep (against postProcess), then the
        // streets too (each from the terrain); the first run pays the pipelines' first use, the second is the steady cost
        int[] g0 = terrainIds.clone();
        ovl.run(n, chunks, levels, AtlasOvlNative.KIND_PIECES | AtlasOvlNative.KIND_SWEEPS | AtlasOvlNative.KIND_STREETS, g0);
        g0 = terrainIds.clone();
        ovl.run(n, chunks, levels, AtlasOvlNative.KIND_PIECES, g0);
        int[] g1 = terrainIds.clone();
        AtlasOvlNative.Times t1 = ovl.run(n, chunks, levels, AtlasOvlNative.KIND_PIECES | AtlasOvlNative.KIND_SWEEPS, g1);
        int[] g2 = terrainIds.clone();
        AtlasOvlNative.Times t2 = ovl.run(n, chunks, levels, AtlasOvlNative.KIND_PIECES | AtlasOvlNative.KIND_SWEEPS | AtlasOvlNative.KIND_STREETS, g2);
        report.append(String.format("GPU: pieces %.3f ms, sweeps %.3f ms, streets %.3f ms (timestamps) for %d chunks; run %.1f ms (upload %.1f, kernels %.1f, readback %.1f)%n",
                t2.piecesMs(), t2.sweepsMs(), t2.streetsMs(), n, t2.totalMs(), t2.uploadMs(), t2.kernelMs(), t2.readbackMs()));
        // kinds: the last piece (in order) whose box (with foundations) holds a block
        Totals here = new Totals();
        here.piecesMs = t2.piecesMs() + t2.sweepsMs();
        here.streetsMs = t2.streetsMs();
        here.columns = (long) n * 256;
        Map<String, Long> pairs = new TreeMap<>(), streetPairs = new TreeMap<>();
        Map<String, String> example = new HashMap<>();
        for (int i = 0; i < n; i++) {
            ChunkPos cp = ChunkPos.unpack(keys.get(i));
            int[] t = ref.terrain().get(keys.get(i)), rp = ref.pieces().get(keys.get(i)), rb = ref.builders().get(keys.get(i)),
                    rs = ref.streets().get(keys.get(i));
            String[] kindOf = kinds(cp, pieces, heights);
            for (int b = 0; b < 256 * levels; b++) {
                int tg = t[b];
                int gp = Block.getId(tapes.blocks.get(unpack(g1, i * words, b))), gs = Block.getId(tapes.blocks.get(unpack(g2, i * words, b)));
                int gb = Block.getId(tapes.blocks.get(unpack(g0, i * words, b)));
                // pieces vs postProcess
                boolean jc = rp[b] != tg, gc = gp != tg;
                if (jc || gc) {
                    Score k = here.byKind.computeIfAbsent(kindOf[b] == null ? "?" : kindOf[b], x -> new Score());
                    tally(here.pieces, k, rp[b], gp, jc, gc);
                    if (rp[b] != gp) {
                        String key = name(rp[b]) + " -> " + name(gp);
                        pairs.merge(key, 1L, Long::sum);
                        example.putIfAbsent(key, (cp.getMinBlockX() + (b & 15)) + " " + (heights.getMinY() + b / 256) + " " + (cp.getMinBlockZ() + (b >> 4 & 15)));
                    }
                }
                // the pieces without their sweep vs the builders alone
                jc = rb[b] != tg;
                boolean gbc = gb != tg;
                if (jc || gbc) tally(here.builders, null, rb[b], gb, jc, gbc);
                // streets: what the painter changed over the pieces
                jc = rs[b] != rp[b];
                gc = gs != gp;
                if (jc || gc) {
                    tally(here.streets, null, rs[b], gs, jc, gc);
                    if (rs[b] != gs) {
                        String key = name(rs[b]) + " -> " + name(gs);
                        streetPairs.merge(key, 1L, Long::sum);
                        example.putIfAbsent("s " + key, (cp.getMinBlockX() + (b & 15)) + " " + (heights.getMinY() + b / 256) + " " + (cp.getMinBlockZ() + (b >> 4 & 15)));
                    }
                }
                here.blocks++;
                if (rs[b] == gs) here.blocksSame++;
            }
        }
        here.print(report);
        report.append("  pieces' differences (Java -> GPU):\n");
        pairs.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(25)
                .forEach(e -> report.append(String.format("    %8d  %s   (e.g. %s)%n", e.getValue(), e.getKey(), example.get(e.getKey()))));
        report.append("  streets' differences (Java -> GPU):\n");
        streetPairs.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(15)
                .forEach(e -> report.append(String.format("    %8d  %s   (e.g. %s)%n", e.getValue(), e.getKey(), example.get("s " + e.getKey()))));
        all.pieces.add(here.pieces);
        all.builders.add(here.builders);
        all.streets.add(here.streets);
        here.byKind.forEach((k, v) -> all.byKind.computeIfAbsent(k, x -> new Score()).add(v));
        all.blocks += here.blocks;
        all.blocksSame += here.blocksSame;
        all.columns += here.columns;
        all.piecesMs += here.piecesMs;
        all.streetsMs += here.streetsMs;
    }

    static void tally(Score s, Score k, int java, int gpu, boolean jc, boolean gc) {
        for (Score x : k == null ? new Score[] {s} : new Score[] {s, k}) {
            x.touched++;
            if (java == gpu) x.same++;
            if (jc && !gc) x.javaOnly++;
            if (gc && !jc) x.gpuOnly++;
        }
    }

    /** Per block of the chunk the archetype of the last piece whose box (with its foundations) holds it. */
    static String[] kinds(ChunkPos cp, List<SettlementPiece> pieces, LevelHeightAccessor heights) {
        int levels = heights.getHeight();
        String[] out = new String[256 * levels];
        for (SettlementPiece p : pieces) {
            BoundingBox b = p.getBoundingBox();
            int x0 = Math.max(b.minX(), cp.getMinBlockX()), x1 = Math.min(b.maxX(), cp.getMaxBlockX());
            int z0 = Math.max(b.minZ(), cp.getMinBlockZ()), z1 = Math.min(b.maxZ(), cp.getMaxBlockZ());
            if (x0 > x1 || z0 > z1) continue;
            int y0 = Math.max(b.minY() - SettlementPiece.FOUNDATION, heights.getMinY()), y1 = Math.min(b.maxY(), heights.getMaxY());
            String k = p.spec.grammar() ? "building" : p.spec.kind.name().toLowerCase(java.util.Locale.ROOT);
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++)
                    for (int x = x0; x <= x1; x++) out[(x & 15) + 16 * (z & 15) + 256 * (y - heights.getMinY())] = k;
        }
        return out;
    }

    static void pack(int[] global, AtlasOverlayTapes tapes, int[] out, int at) {
        for (int b = 0; b < global.length; b += 2) {
            int lo = tapes.id(Block.stateById(global[b])), hi = tapes.id(Block.stateById(global[b + 1]));
            out[at + (b >> 1)] = lo | hi << 16;
        }
    }

    static int unpack(int[] ids, int at, int b) {
        int w = ids[at + (b >> 1)];
        return (b & 1) != 0 ? w >>> 16 : w & 0xFFFF;
    }

    static String name(int globalId) {
        BlockState s = Block.stateById(globalId);
        String n = s.getBlock().builtInRegistryHolder().key().identifier().getPath();
        return s == s.getBlock().defaultBlockState() ? n : n + s.toString().substring(s.toString().indexOf('['));
    }

    static void trace(String what) {
        System.out.println("TRACE " + what);
        System.out.flush();
    }

    private static long lastProgress;

    static void progress(String phase, int i, int n) {
        long now = System.nanoTime();
        if (i < n && now - lastProgress < 20_000_000_000L) return;
        lastProgress = now;
        trace("progress " + phase + " " + i + "/" + n);
    }
}
