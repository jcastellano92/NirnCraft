package com.nirncraft.atlas;

import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * Atlas fills the near field from its own generation (docs/ATLAS-CLIENT-GENERATION.md, stage 5): behind
 * {@code -Dnirncraft.atlas.clientGen=true}, single player, the chunks around the camera are generated on the GPU
 * (atlas_gen: the world's own generator, bit for bit) and put up by this class's own uploader (the
 * atlas_upload_section path AtlasEditsClient.put shares with the far-field worker's feed), nearest and in view first.
 * The camera comes from the render thread every frame ({@link #follow}): a move wakes the threads at once and
 * everything queued for a place the camera left is dropped. Vanilla's chunks and the server's sections keep arriving
 * through the far-field worker; a section identical to what went up is skipped there (its hash), so they only correct
 * what differs (structures, a player's edits); a loaded chunk already up is never covered by generated blocks.
 *
 * <p>{@code nirncraft.atlas.clientGen.radius} (chunks, default 24) sets the window. A chunk the kernel could not
 * prove exact is left to the server's blocks. Features (stage 4, AtlasGenFeatures: the world's own decoration over
 * the generated blocks) run on {@code .featureThreads} low-priority threads (default: a quarter of the cores, 1..4) over
 * chunks whose 3 x 3 never overlap, within {@code .featureRadius} chunks (default 16; 0: off), never in the terrain's
 * way: a chunk goes up again once its 3 x 3 are decorated, only its changed sections re-uploading.
 * {@code .ores=false} leaves ores to the server (the public / secret split).</p>
 */
final class AtlasClientGen implements AutoCloseable {
    // flight recorder zones (AtlasRecorder): the generator thread's loop
    private volatile double[] statPage;
    private volatile String statPaceLine, statCacheLine, statSubLine, statStoreLine, statVerifyLine;

    /** The generator thread's own reading of the native counters (their calls take the generator's lock, held a whole batch). */
    private void refreshStats() {
        try {
            if (devicePages) statPage = gen.pageStats();
            statPaceLine = gen.paceLine();
            statSubLine = gen.subLine(false);
            statVerifyLine = verifyLine();
            if (storeOn) {
                int[] ss = gen.storeStats();
                statStoreLine = ss == null || ss.length < 3 ? null : String.format("store{held %d, waiting %d, ready %d}", ss[0], ss[1], ss[2]);
            }
            statCacheLine = gen.cacheLine();
        } catch (RuntimeException e) {
            // (the next pass)
        }
    }

    private static final AtlasRecorder.Zone Z_PLAN = AtlasRecorder.zone("gen:plan"), Z_REPORTS = AtlasRecorder.zone("gen:gridReports"),
            Z_DIRECT = AtlasRecorder.zone("gen:directBatch"), Z_PRUNE = AtlasRecorder.zone("gen:prune"), Z_FAR = AtlasRecorder.zone("gen:farStep"),
            Z_PAGES = AtlasRecorder.zone("gen:pages"), Z_CACHE = AtlasRecorder.zone("gen:fromCache"), Z_RUN = AtlasRecorder.zone("gen:gen.run"),
            Z_RUN_STORE = AtlasRecorder.zone("gen:gen.runStore"), Z_CLAIM = AtlasRecorder.zone("gen:claimSections"),
            Z_INGEST = AtlasRecorder.zone("gen:gen.ingest (into the grid)"), Z_DECORATE = AtlasRecorder.zone("gen:decorate (features)");

    static final boolean ON = Boolean.getBoolean("nirncraft.atlas.clientGen");
    /** The window, within the grid's level 0 (ATLAS_VOXGRID_D sections, voxgrid.cpp: 8..128, 64): wider, its edge
     *  chunks would be dropped and wanted again without end. */
    static final int RADIUS = Math.min(Integer.getInteger("nirncraft.atlas.clientGen.radius", 24), (gridD() - 3) / 2);

    private static int gridD() {
        try {
            String d = System.getenv("ATLAS_VOXGRID_D");
            return d == null ? 64 : Math.max(8, Math.min(128, Integer.parseInt(d.trim())));
        } catch (NumberFormatException e) {
            return 8;  // (atoi: 0, clamped to 8)
        }
    }
    static final int FEATURE_RADIUS = Integer.getInteger("nirncraft.atlas.clientGen.featureRadius", 16);
    static final int FEATURE_THREADS = Integer.getInteger("nirncraft.atlas.clientGen.featureThreads",
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4)));
    static final boolean ORES = !"false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.clientGen.ores"));
    /**
     * (direct) The decoration on the GPU in the generator's chunk store (atlas_gen_run_store + atlas_feat_attach): chunks
     * are generated into the store and go into the grid once decorated; {@code .store=false}: Java's decoration as
     * before. {@code .storeSlots}: the store's chunks (about 1.3 MiB each).
     */
    static final boolean STORE = !"false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.clientGen.store"));
    static final int STORE_SLOTS = Integer.getInteger("nirncraft.atlas.clientGen.storeSlots", 768);
    /** Dev runs ({@code -Dnirncraft.dev} or the harness's autorun): the GPU decoration failing stops the client generator. */
    static final boolean DEV = System.getProperty("nirncraft.dev") != null || !System.getProperty("nirncraft.autorun", "").isEmpty();
    /** {@code .direct=false}: the generator's sections come back to the CPU and go up as uploads (the old path). */
    static final boolean DIRECT = !"false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.clientGen.direct"));
    /** (direct) The far terrain on the GPU into the grid's coarse levels (atlas_gen_far_ingest); false: the tiles. */
    /**
     * Called with each new client generator and its program before its first batch: the features and overlays engines
     * register their stages there (gen.handle(): atlas_gen_add_stage, atlas_gen_device_handles; prog.blockIds for the
     * blocks they place, registered beforehand with AtlasGenProgram.addBlocks). Register at mod setup (any thread).
     */
    static final java.util.List<java.util.function.BiConsumer<AtlasGenNative.Gen, AtlasGenProgram>> ATTACH =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Called with the generator before it closes: the stage engines remove their stages and free what is on its device. */
    static final java.util.List<java.util.function.Consumer<AtlasGenNative.Gen>> DETACH = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Chunks to generate again (their stages' data arrived after they went up: a settlement's tapes): the active
     * generator forgets them, so the camera's window makes them anew. Any thread.
     */
    static void regenerate(long[] chunkKeys) {
        AtlasClientGen c = active;
        if (c == null) return;
        boolean any = false;
        int[] xz = new int[chunkKeys.length * 2];
        for (int i = 0; i < chunkKeys.length; i++) {
            long k = chunkKeys[i];
            any |= c.done.remove(k);
            c.deferred.remove(k);
            xz[i * 2] = ChunkPos.getX(k);
            xz[i * 2 + 1] = ChunkPos.getZ(k);
            for (int sec = 0; sec < 40; sec++) AtlasEditsClient.unclaim(net.minecraft.core.SectionPos.asLong(xz[i * 2], sec - 4, xz[i * 2 + 1]));
        }
        // (the self-baking cache forgets them and the store runs their stages again: else the cache served the copy
        // made before the data, and the store packed its old blocks)
        try {
            c.gen.regenerate(xz);
        } catch (RuntimeException e) {
            com.nirncraft.NirnCraft.LOGGER.warn("atlas client generation: regenerate: {}", e.toString());
        }
        if (any || xz.length > 0) c.wake();
    }

    static boolean far() {
        return !"false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.clientGen.far"));
    }
    /** Far tiles a side around the camera per coarse level (the level's window is 4 tiles of 16 of its chunks). */
    static final int FAR_TILES = 2;
    /** Chunks per GPU batch; the first after a jump is small, so the blocks under the camera come first. */
    private static final int BATCH = 64, FIRST_BATCH = 16, READY_MAX = 512;
    private static volatile AtlasClientGen active;

    // the camera, from the render thread (follow)
    private static volatile double camX, camZ;
    private static volatile float camYaw;
    private static volatile boolean camSet;
    private static volatile int camCx = Integer.MIN_VALUE, camCz = Integer.MIN_VALUE;
    // the camera's motion (blocks a second, smoothed; zeroed by a jump): requests lead it by PREDICT_S
    private static volatile double camVx, camVz;
    private static long camNs;
    private static final double PREDICT_S = 1.5;

    private final AtlasGenProgram program;
    private final AtlasGenPages pages;
    private final AtlasGenNative.Gen gen;
    private final AtlasNative.Context ctx;  // null: the far-field worker's feed puts the chunks up (applySections)
    // GPU -> GPU (atlas_gen_create_for): the generator runs on the context's device and its sections go device to device
    // into the grid (atlas_gen_run + atlas_gen_ingest); the decorated chunks still go up through the uploader
    private final boolean direct;
    private final AtlasMaterials mats;
    private final int[] farIds;          // per generator id: its near id (AtlasClientSections.nearIdOf; 0: not kept)
    private final boolean[] solid;       // per generator id: renders as a full cube (AtlasSections.solidRock)
    private final boolean[] motionOrFluid, air;
    private final ThreadLocal<BiomeResolver> biomes;
    private final long zoomSeed;
    private final Set<Long> done = ConcurrentHashMap.newKeySet();
    private final Set<Long> deepRock = ConcurrentHashMap.newKeySet();  // sections left out as deep solid rock
    /**
     * A chunk waiting to go up: the generator's own 16-bit sections (gen; deep rock left out, the arrays the features'
     * store keeps: nothing copied) or, decorated, the feed's far ids (decoded), with the chunk's tints.
     */
    private record Up(int cx, int cz, short[][] gen, AtlasSections.Decoded decoded, int grass, int foliage) {}

    private final List<Up> ready = new ArrayList<>();
    private final Thread worker, uploader;
    private final List<Thread> featureWorkers = new ArrayList<>();
    // features: the generated ids of chunks near the camera (null section: air), the proto chunks over them, progress
    private final AtlasGenFeatures features;
    private final AtlasGenFeatures.Window window;
    private final Map<Long, short[][]> store = new ConcurrentHashMap<>();
    private final Set<Long> decorated = ConcurrentHashMap.newKeySet(), emitted = ConcurrentHashMap.newKeySet();
    private final Set<Long> decorating = new java.util.HashSet<>();  // (synchronized on itself) decorations under way
    private final NoiseBasedChunkGenerator generator;
    private final RandomState random;
    private volatile boolean running = true;
    // (direct, FAR) the far tiles put into the grid, per level and tile; those the grid did not take whole (outside its
    // window, its pool's cut-off) go again once the camera has moved a tile at that level
    private final Set<Long> farDone = ConcurrentHashMap.newKeySet(), farPartial = ConcurrentHashMap.newKeySet();
    private volatile long farTiles, farChunks, farTaken, farBusy;
    private volatile double farMs;
    private volatile boolean farOn;
    private volatile AtlasCoarseBake bake;  // (the whole-map coarse bake: run starts it, close stops it)
    /** The generator makes its own pages on the device (atlas_gen_set_map took the map). */
    private volatile boolean devicePages;
    private volatile boolean terrainBusy = true;  // the window has chunks to generate: features wait (terrain first)
    // (direct) the chunk store with the decoration's stages: chunks come out of it decorated (atlas_gen_run_store)
    private volatile boolean storeOn;
    /** The integrated server's chunks, from the store (docs/ATLAS-GPU-LOOP.md section 4): null unless the store is on. */
    private volatile AtlasServerGen serverGen;
    private volatile java.lang.foreign.MemorySegment featStages;
    private volatile long storeFinished;
    // stats (atlas-info)
    private volatile long chunks, flagged, pagesMade, featureChunks, uploadedChunks, uploadedSections;
    private volatile double genMs, pageMs, featureMs, uploadMs, gpuMs, decodeMs, kernelMs, readbackMs;
    // the last jump: when the camera moved, its first and its last chunk of the new window up
    private volatile long jumpNs, firstUpNs, windowUpNs, windowFeaturesNs, firstBatchPagesNs;
    private volatile String firstBatch = "";  // (direct) the first batch after the last move, stage by stage
    private volatile int jumpCx, jumpCz;
    private final Set<Long> upSinceJump = ConcurrentHashMap.newKeySet(), featuredSinceJump = ConcurrentHashMap.newKeySet();

    private AtlasClientGen(AtlasGenProgram program, AtlasGenPages pages, AtlasGenNative.Gen gen, NoiseBasedChunkGenerator g,
                           RandomState random, long seed, AtlasGenFeatures features, AtlasNative.Context ctx, AtlasMaterials mats,
                           boolean direct) {
        this.direct = direct;
        this.generator = g;
        this.random = random;
        this.features = features;
        this.program = program;
        this.ctx = ctx;
        this.mats = mats;
        this.zoomSeed = net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(seed);
        this.pages = pages;
        this.gen = gen;
        int n = program.blocks.size();
        farIds = new int[n];
        solid = new boolean[n];
        motionOrFluid = new boolean[n];
        air = new boolean[n];
        for (int i = 0; i < n; i++) {
            BlockState s = program.blocks.get(i);
            farIds[i] = AtlasClientSections.nearIdOf(s);  // (the client feed's rule: every block, full state, bricks)
            solid[i] = !s.isAir() && s.isSolidRender();
            motionOrFluid[i] = net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(s);
            air[i] = s.isAir();
        }
        biomes = ThreadLocal.withInitial(() -> g.getBiomeSource().createResolver(random.createClimateSampler(SamplerContext.EMPTY_UNCACHED)));
        worker = daemon(this::run, "atlas client generation", Thread.NORM_PRIORITY);
        uploader = daemon(this::uploadLoop, "atlas client generation upload", Thread.NORM_PRIORITY);
        window = features == null ? null : features.new Window(new StoredTerrain());
        if (window != null) window.filled = store::remove;  // the proto chunk holds the blocks now
        if (features != null)
            for (int i = 0; i < FEATURE_THREADS; i++)
                featureWorkers.add(daemon(this::decorateLoop, "atlas client features " + i, Thread.MIN_PRIORITY + 1));
    }

    private static Thread daemon(Runnable r, String name, int priority) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(priority);
        return t;
    }

    /** A generator without its threads (the parity tool decodes with it). */
    static AtlasClientGen detached(AtlasGenProgram prog, AtlasGenPages pages, AtlasGenNative.Gen gen,
                                   NoiseBasedChunkGenerator g, RandomState rs, long seed) {
        return new AtlasClientGen(prog, pages, gen, g, rs, seed, null, null, null, false);
    }

    /**
     * The map of Nirn to the generator (atlas_gen_set_map, AtlasGenMap): with it the device makes every page itself
     * (atlas_page.slang) and Java computes none. Returns what the log says: "on the device", or why they stay in Java
     * (an older library, a program or biome source the page passes do not cover; AtlasGenPages then, as before).
     */
    /**
     * (dev) {@code -Dnirncraft.atlas.dump=<dir>}: the generator's program, its map and the decoration's program and id tables
     * as little-endian int files (prog.bin, map.bin, feat.bin, g2f.bin, f2g.bin), what native/tools/gen_bake_tool loads to run
     * the far path, the trees included, without the game (the inner loop of the bake's slicing).
     */
    private static void dumpWords(String name, int[] words) {
        String dir = System.getProperty("nirncraft.atlas.dump");
        if (dir == null || dir.isEmpty()) return;
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir));
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(words.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            bb.asIntBuffer().put(words);
            java.nio.file.Files.write(java.nio.file.Path.of(dir, name), bb.array());
        } catch (java.io.IOException e) {
            com.nirncraft.NirnCraft.LOGGER.warn("atlas dump {} failed", name, e);
        }
    }

    static String devicePages(AtlasGenNative.Gen gen, AtlasGenProgram prog, net.minecraft.world.level.biome.BiomeSource biomes) {
        if (!gen.devicePagesSupported()) return "in Java (the library makes no pages)";
        if (!prog.pageGen) return "in Java (the program lacks the page passes' inputs)";
        try {
            long t0 = System.nanoTime();
            int[] map = AtlasGenMap.words(prog, biomes);
            dumpWords("prog.bin", prog.words);
            dumpWords("map.bin", map);
            gen.setMap(map);
            if (gen.pageStats()[2] == 0) return "in Java (the generator did not take them)";
            return String.format("on the device (map %d words, %.0f ms)", map.length, (System.nanoTime() - t0) / 1e6);
        } catch (RuntimeException e) {
            com.nirncraft.NirnCraft.LOGGER.warn("atlas client generation: pages stay in Java", e);
            return "in Java (" + e.getMessage() + ")";
        }
    }

    /** Starts generation for the integrated server's overworld (when the flag is on), or returns null (logged). */
    static AtlasClientGen start(MinecraftServer server, Path library, AtlasNative.Context ctx, AtlasMaterials mats) {
        return ON ? startFor(server, library, ctx, mats) : null;
    }

    /**
     * The GPU decoration asked for and not to be had: never a silent fall back to the CPU's (minutes of CPU, the frame
     * rate in single figures): an error with the cause, and in dev the client generator stops.
     */
    private static void gpuDecorationFailed(String why, Throwable cause) {
        com.nirncraft.NirnCraft.LOGGER.error("atlas client generation: the GPU decoration is OFF ({}); the decoration falls back to the CPU", why, cause);
        AtlasTileUpload.noDressingCanopy = false;  // (no far trees from it: the dressing's canopy again)
        if (DEV) throw new IllegalStateException("the GPU decoration failed (" + why + "): " + cause, cause);
    }

    /** As start, whatever the flag (the offline jump measure). */
    static AtlasClientGen startFor(MinecraftServer server, Path library, AtlasNative.Context ctx, AtlasMaterials mats) {
        if (server == null || library == null) return null;
        try {
            ServerLevel ow = server.overworld();
            if (!(ow.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator g)) return null;
            RandomState rs = ow.getChunkSource().randomState();
            long t0 = System.nanoTime();
            AtlasGenProgram prog = AtlasGenProgram.compile(g, g.generatorSettings().value(), rs, ow, ow.getSeed(), null);
            AtlasGenNative nat = AtlasGenNative.load(library);
            if (nat == null) {
                com.nirncraft.NirnCraft.LOGGER.info("atlas client generation off: the core has no generator");
                return null;
            }
            // on the context's own device (its sections device to device into the grid), else a device of its own
            AtlasGenNative.Gen gen = ctx != null && mats != null && DIRECT ? nat.createFor(ctx.handle()) : null;
            boolean direct = gen != null;
            if (gen == null) gen = nat.create(AtlasGenNative.BACKEND_VULKAN);
            if (gen == null) gen = nat.create(AtlasGenNative.BACKEND_CPU);
            if (gen == null) return null;
            // the decoration on the GPU (the store's stages): its program over the generator's blocks and biomes; the
            // blocks it places join the generator's program (AtlasGenProgram.addBlocks), compiled again if it lacked any
            AtlasFeatureProgram storeFp = null;
            if (direct && STORE && FEATURE_RADIUS > 0 && gen.storeSupported()) {
                try {
                    long zs = net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(ow.getSeed());
                    java.util.function.Predicate<String> only = ORES ? null : k -> !k.startsWith("minecraft:ore_");
                    storeFp = AtlasFeatureProgram.compile(g, ow, prog, ow.getSeed(), zs, only);
                    AtlasGenProgram base = prog;
                    List<BlockState> missing = storeFp.blocks.stream().filter(st -> !base.blockIds.containsKey(st)).toList();
                    if (!missing.isEmpty()) {
                        AtlasGenProgram.addBlocks(() -> missing);
                        prog = AtlasGenProgram.compile(g, g.generatorSettings().value(), rs, ow, ow.getSeed(), null);
                        storeFp = AtlasFeatureProgram.compile(g, ow, prog, ow.getSeed(), zs, only);
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    gpuDecorationFailed("its feature program did not compile", e);
                    storeFp = null;
                }
            }
            gen.setProgram(prog.words);
            java.lang.foreign.MemorySegment stages = null;
            if (storeFp != null) {
                try {
                    // the id tables (the same ids when the feature program's extra blocks follow the generator's order)
                    int[] g2f = new int[prog.blocks.size()], f2g = new int[storeFp.blocks.size()];
                    for (int i = 0; i < g2f.length; i++) {
                        Integer f = storeFp.blockIds.get(prog.blocks.get(i));
                        g2f[i] = f == null ? 0 : f;
                    }
                    for (int i = 0; i < f2g.length; i++) {
                        Integer gi = prog.blockIds.get(storeFp.blocks.get(i));
                        f2g[i] = gi == null ? 0 : gi;
                    }
                    long ts = System.nanoTime();
                    gen.storeEnable(STORE_SLOTS);
                    stages = gen.attachFeatures(storeFp.words, g2f, f2g);
                    dumpWords("feat.bin", storeFp.words);
                    dumpWords("g2f.bin", g2f);
                    dumpWords("f2g.bin", f2g);
                    if (System.getenv("ATLAS_FEAT_PART_TRACE") != null) {  // (dev: which feature a traced record is)
                        var reg = ow.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.PLACED_FEATURE);
                        for (int i = 0; i < storeFp.dispatched.size(); i++)
                            System.err.println("TRACE feat record " + storeFp.records.getInt(i) + " = " + reg.getKey(storeFp.dispatched.get(i)));
                    }
                    com.nirncraft.NirnCraft.LOGGER.info("atlas client generation: the decoration on the GPU ({} placed features, {} slots, {} ms)",
                            storeFp.dispatched.size(), STORE_SLOTS, (System.nanoTime() - ts) / 1_000_000);
                } catch (RuntimeException e) {
                    gpuDecorationFailed("its stages did not attach", e);
                    stages = null;
                }
            }
            // the batch's stages (atlas.h atlas_gen_add_stage): the features and overlays engines attach to this generator
            for (var attach : ATTACH) {
                try {
                    attach.accept(gen, prog);
                } catch (RuntimeException e) {
                    com.nirncraft.NirnCraft.LOGGER.warn("atlas client generation: a stage engine did not attach", e);
                }
            }
            // the decoration's placement mask keeps clear of the overlays' structure pieces (their boxes on the GPU)
            if (stages != null) {
                java.lang.foreign.MemorySegment ovl = AtlasOverlayLive.handleFor(gen);
                if (ovl != null && !gen.setFeatureOverlays(stages, ovl))
                    com.nirncraft.NirnCraft.LOGGER.info("atlas client generation: the GPU decoration's mask without the structure pieces (an older library)");
            }
            if (direct) gen.setIngest(ingestTable(prog, mats, ctx));
            // the self-baking cache: the instance's atlas-cache/<seed>/ (nirncraft.atlas.cacheDir moves it; "off": none),
            // the generator's fingerprint completed by the far identity (world generation and painting code)
            boolean cacheArm = false;
            if (direct && gen.cacheSupported() && !"off".equals(System.getProperty("nirncraft.atlas.cacheDir"))) {
                Path dir = System.getProperty("nirncraft.atlas.cacheDir") != null ? Path.of(System.getProperty("nirncraft.atlas.cacheDir"))
                        : net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("atlas-cache");
                // (the fingerprint is armed by the worker once the map is on the device: AtlasClientGen.run)
                cacheArm = true;
                if (ctx.setCache(dir, ow.getSeed(), AtlasTileCodec.VERSION, 0, 0))
                    com.nirncraft.NirnCraft.LOGGER.info("atlas client generation: the self-baking cache in {}", dir);
            }
            AtlasGenFeatures feats = null;
            if (FEATURE_RADIUS > 0 && stages == null) {
                try {
                    feats = new AtlasGenFeatures(g, ow, server.registryAccess(), ow, ow.getSeed(), prog, ORES);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    com.nirncraft.NirnCraft.LOGGER.info("atlas client features off: {}", e.toString());
                }
            }
            AtlasClientGen c = new AtlasClientGen(prog, new AtlasGenPages(prog, rs, g.getBiomeSource(), ow), gen, g, rs,
                    ow.getSeed(), feats, ctx, mats, direct);
            c.cacheArm = cacheArm;
            c.storeOn = stages != null;
            c.featStages = stages;
            if (c.storeOn && direct && com.nirncraft.atlas.server.AtlasServerMode.gpuChunks()) {
                // the server's AtlasChunkGenerator takes its chunks from the store, on this generator's worker
                c.serverGen = new AtlasServerGen(c, prog.blocks, c.farIds, ow);
                com.nirncraft.atlas.server.AtlasServerFeed.bind(g, c.serverGen);
                com.nirncraft.NirnCraft.LOGGER.info("atlas client generation: the integrated server's chunks come from the GPU store ({})",
                        g instanceof com.nirncraft.atlas.server.AtlasChunkGenerator ? "AtlasChunkGenerator" : "this world's generator is not the atlas one: unused");
            }
            // the far chunks carry the decoration's trees (its far stage, unless ATLAS_FEAT_FAR=0): no stand-in canopy
            AtlasTileUpload.noDressingCanopy = stages != null && far() && !"0".equals(System.getenv("ATLAS_FEAT_FAR"));
            com.nirncraft.NirnCraft.LOGGER.info("atlas client generation on ({}): program {} words, {} fields, pages from the worker, radius {} chunks, "
                            + "features {} chunks on {} threads, {} ms", gen.device(), prog.words.length, prog.fields.size(),
                    RADIUS, feats == null ? 0 : FEATURE_RADIUS, feats == null ? 0 : FEATURE_THREADS, (System.nanoTime() - t0) / 1_000_000);
            active = c;
            c.worker.start();
            if (ctx != null && mats != null) c.uploader.start();
            for (Thread t : c.featureWorkers) t.start();
            return c;
        } catch (Throwable t) {
            com.nirncraft.NirnCraft.LOGGER.error("atlas client generation unavailable", t);
            return null;
        }
    }

    // ---- the camera (render thread, each frame) ----

    /** The camera this frame: a new chunk wakes the generator, a jump drops the work queued for the old place. */
    static void follow(double x, double z, float yaw) {
        long now = System.nanoTime();
        double dt = camNs == 0 ? 0 : (now - camNs) / 1e9;
        if (dt > 0 && dt < 0.5) {
            double vx = (x - camX) / dt, vz = (z - camZ) / dt;
            if (vx * vx + vz * vz > 400.0 * 400.0) vx = vz = 0;  // a teleport is no motion
            double k = Math.min(1.0, dt / 0.25);  // ~a quarter second of smoothing
            camVx += (vx - camVx) * k;
            camVz += (vz - camVz) * k;
        }
        camNs = now;
        camX = x;
        camZ = z;
        camYaw = yaw;
        camSet = true;
        int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
        if (cx == camCx && cz == camCz) return;
        int ox = camCx, oz = camCz;
        camCx = cx;
        camCz = cz;
        AtlasClientGen c = active;
        if (c == null) return;
        if (ox == Integer.MIN_VALUE || Math.max(Math.abs(cx - ox), Math.abs(cz - oz)) > 4) {
            camVx = camVz = 0;
            c.jumped(cx, cz);
        }
        c.wake();
    }

    static boolean hasCamera() {
        return camSet && active != null;
    }

    static double cameraX() {
        return camX;
    }

    static double cameraZ() {
        return camZ;
    }

    /**
     * The context's side of the generator's blocks (atlas_gen_set_ingest, atlas.h): per generator id its far state id
     * (the sections' hash) and flags (air, solid render, motion blocking or fluid: decode's tests), per biome its tints
     * (tints(): the grass at the biome's own colour, no position modifier), then per biome and id the atlas material
     * (putGen's: mats.section with the block's tint), registered with the context before the first section uses them.
     */
    static int[] ingestTable(AtlasGenProgram p, AtlasMaterials mats, AtlasNative.Context ctx) {
        int n = p.blocks.size(), nb = p.biomes.size();
        int[] w = new int[2 + 2 * n + 2 * nb + n * nb];
        w[0] = n;
        w[1] = nb;
        int[] far = new int[n];
        for (int i = 0; i < n; i++) {
            BlockState s = p.blocks.get(i);
            far[i] = AtlasClientSections.nearIdOf(s);  // (the client feed's rule: every block, full state, bricks)
            int f = (s.isAir() ? 1 : 0) | (!s.isAir() && s.isSolidRender() ? 2 : 0)
                    | (net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(s) ? 4 : 0);
            w[2 + i * 2] = far[i];
            w[2 + i * 2 + 1] = f;
        }
        int tints = 2 + 2 * n, matBase = tints + 2 * nb;
        for (int b = 0; b < nb; b++) {
            Biome bio = p.biomes.get(b).value();
            int grass = bio.getGrassColor(0, 0) & 0xFFFFFF, foliage = bio.getFoliageColor() & 0xFFFFFF;
            w[tints + b * 2] = grass;
            w[tints + b * 2 + 1] = foliage;
            for (int i = 0; i < n; i++) {
                BlockState st = p.blocks.get(i);
                w[matBase + b * n + i] = far[i] == 0 ? 0 : mats.section(net.minecraft.world.level.block.Block.stateById(far[i]), AtlasMaterials.tint(st, grass, foliage)) & 0xFFFF;
            }
        }
        mats.flush(ctx);
        return w;
    }

    /** Whether this generator puts its chunks up itself (else the far-field worker's feed polls them). */
    static boolean uploadsItself() {
        AtlasClientGen c = active;
        return c != null && c.ctx != null && c.uploader.isAlive();
    }

    /** (diagnostics) the store's held / waiting / ready chunks. */
    String storeLine() {
        try {
            int[] s = gen.storeStats();
            return "store{held=" + s[0] + " waiting=" + s[1] + " ready=" + s[2] + "}";
        } catch (RuntimeException e) {
            return "store{?}";
        }
    }

    void wake() {
        LockSupport.unpark(worker);
        LockSupport.unpark(uploader);
        for (Thread t : featureWorkers) LockSupport.unpark(t);
    }

    /** A jump: what waits for the old place goes (it is far from the camera now), the clock for the new one starts. */
    private void jumped(int cx, int cz) {
        jumpCx = cx;
        jumpCz = cz;
        upSinceJump.clear();
        featuredSinceJump.clear();
        firstUpNs = windowUpNs = windowFeaturesNs = 0;
        jumpNs = System.nanoTime();
        synchronized (ready) {
            ready.removeIf(u -> !near(u.cx(), u.cz(), cx, cz, RADIUS + 1));
        }
    }

    private static boolean inWindow(int cx, int cz, int ccx, int ccz, int r) {
        int dx = cx - ccx, dz = cz - ccz;
        return dx * dx + dz * dz <= r * r;
    }

    // ---- the queue ----

    /** The generated chunk nearest block (x, z) for the far-field worker's feed (only without an uploader), or null. */
    static AtlasSections.Decoded poll(double x, double z) {
        AtlasClientGen c = active;
        if (c == null) return null;
        Up u = c.take(x, z);
        return u == null ? null : u.decoded != null ? u.decoded : c.farIds(u);
    }

    /** The waiting chunk nearest block (x, z), or null. */
    private Up take(double x, double z) {
        synchronized (ready) {
            // a chunk whose loaded blocks are up already keeps them (they hold its trees and edits)
            ready.removeIf(u -> AtlasClientSections.isReal(u.cx(), u.cz()));
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < ready.size(); i++) {
                Up u = ready.get(i);
                double dx = u.cx() * 16 + 8 - x, dz = u.cz() * 16 + 8 - z, dd = dx * dx + dz * dz;
                if (dd < bestD) {
                    bestD = dd;
                    best = i;
                }
            }
            if (best < 0) return null;
            Up u = ready.get(best);
            ready.set(best, ready.get(ready.size() - 1));
            ready.remove(ready.size() - 1);
            return u;
        }
    }

    /** A generated chunk as the feed's far ids. */
    private AtlasSections.Decoded farIds(Up u) {
        int[][] secs = new int[40][];
        for (int s = 0; s < 40; s++) {
            short[] g = u.gen[s];
            if (g == null) continue;
            int[] far = new int[4096];
            for (int b = 0; b < 4096; b++) far[b] = farIds[g[b] & 0xFFFF];
            secs[s] = far;
        }
        return new AtlasSections.Decoded(u.cx, u.cz, -4, secs, u.grass, u.foliage);
    }

    private void offer(Up u, boolean replaceTerrain) {
        synchronized (ready) {
            if (replaceTerrain) ready.removeIf(o -> o.cx() == u.cx() && o.cz() == u.cz());  // its terrain alone, not up yet
            ready.add(u);
        }
        LockSupport.unpark(uploader);
    }

    /**
     * A generated chunk's sections up: the hash of each one's far ids (the feed's, so the client feed's identical
     * sections are skipped later), the materials through a table of the chunk's few generator ids. Returns the count.
     */
    private int putGen(Up u, short[] out, int[] mat) {
        java.util.Arrays.fill(mat, -1);
        long seed = 0xcbf29ce484222325L ^ ((long) u.grass << 24 ^ u.foliage);
        int n = 0;
        for (int s = 0; s < 40; s++) {
            short[] g = u.gen[s];
            if (g == null) continue;
            long key = net.minecraft.core.SectionPos.asLong(u.cx, s - 4, u.cz);
            long h = seed;
            for (int b = 0; b < 4096; b++) h = (h ^ farIds[g[b] & 0xFFFF]) * 0x100000001b3L;
            if (!AtlasEditsClient.claim(key, h)) continue;  // the core has these blocks
            for (int b = 0; b < 4096; b++) {
                int id = g[b] & 0xFFFF;
                int m = mat[id];
                if (m < 0) {
                    int far = farIds[id];
                    if (far == 0) m = 0;
                    else {
                        BlockState st = net.minecraft.world.level.block.Block.stateById(far);
                        m = mats.section(st, AtlasMaterials.tint(st, u.grass, u.foliage)) & 0xFFFF;
                    }
                    mat[id] = m;
                }
                out[b] = (short) m;
            }
            mats.flush(ctx);
            ctx.uploadSection(u.cx, s - 4, u.cz, out);
            n++;
        }
        AtlasClientSections.up(u.cx, u.cz);
        return n;
    }

    /**
     * Whether a loaded chunk's section need not go up: generation left it out as deep solid rock and its blocks are all
     * solid (the client feed's palette test keeps such sections when the palette lists a state no block has any more).
     */
    static boolean skipRock(long sectionKey, int[] farIds) {
        AtlasClientGen c = active;
        if (c == null || !c.deepRock.contains(sectionKey)) return false;
        for (int id : farIds) if (id == 0 || !net.minecraft.world.level.block.Block.stateById(id).isSolidRender()) return false;
        return true;
    }

    /**
     * What the generator made against what the cache served (the relaunch test): {chunks generated, chunks from the cache,
     * far tiles made, far tiles from the cache}; null without a generator.
     */
    static long[] counters() {
        AtlasClientGen c = active;
        return c == null ? null : new long[]{c.generatedChunks, c.cachedChunks, c.farTiles - c.farCached, c.farCached};
    }

    /** The generator's submissions by phase since the last reset (AtlasLoadStats; atlas_gen_sub_stats), or null. */
    static String subStats(boolean reset) {
        AtlasClientGen c = active;
        if (c == null) return null;
        try {
            return c.gen.subLine(reset);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String state() {
        AtlasClientGen c = active;
        if (c == null) return "clientGen{off}";
        int r;
        synchronized (c.ready) {
            r = c.ready.size();
        }
        long now = System.nanoTime(), j = c.jumpNs;
        double[] dp = c.devicePages ? c.statPage : null;  // (cached by the generator thread: the native call waits for its lock, seconds)
        String pagesNow = dp == null ? "java" : String.format("device %d in %.0f ms", (long) dp[0], dp[1]);
        if (c.farOn) pagesNow += String.format(", far %d tiles %d/%d chunks taken %.0f ms busy %d, partial %d (retried %d), from the cache %d",
                c.farTiles, c.farTaken, c.farChunks, c.farMs, c.farBusy, c.farPartial.size(), c.farRetried, c.farCached);
        pagesNow += String.format(", cached chunks %d (%d sections)", c.cachedChunks, c.cachedSections);
        if (AtlasCoarseBake.line() != null) pagesNow += ", " + AtlasCoarseBake.line();
        String pace = c.statPaceLine;
        if (pace != null) pagesNow += ", " + pace;
        String subs = c.statSubLine;
        if (subs != null) pagesNow += ", " + subs;
        String vl = c.statVerifyLine;
        if (vl != null) pagesNow += ", " + vl;
        if (c.statStoreLine != null) pagesNow += ", " + c.statStoreLine;
        String cache = c.statCacheLine;
        pagesNow += ", " + (cache != null ? cache : "genCache{not in this library}");
        pagesNow += String.format(", generated chunks %d (cache served %d)", c.generatedChunks, c.cachedChunks);
        return String.format("clientGen{%s%s chunks=%d flagged=%d ready=%d up=%d/%d sections pages=%d (%s) genMs=%.0f pageMs=%.0f "
                        + "(gpu=%.0f: kernels %.0f readback %.0f; decode=%.0f) uploadMs=%.0f features=%d featureMs=%.0f failures=%d jump{firstUp=%.0f windowUp=%.0f features=%.0f ms; %s}}",
                c.direct ? "gpu->grid " : "", c.gen.device(), c.chunks, c.flagged, r, c.uploadedChunks, c.uploadedSections, c.pagesMade, pagesNow,
                c.genMs, c.pageMs,
                c.gpuMs, c.kernelMs, c.readbackMs, c.decodeMs,
                c.uploadMs, c.featureChunks, c.featureMs, c.features == null ? 0 : c.features.failures(),
                since(j, c.firstUpNs), since(j, c.windowUpNs), since(j, c.windowFeaturesNs), c.firstBatch);
    }

    private static double since(long t0, long t) {
        return t0 == 0 || t == 0 ? -1 : (t - t0) / 1e6;
    }

    /** The last jump's times (ms after the camera moved; -1 not yet): first chunk up, window up, window's features up. */
    double[] jumpTimes() {
        return new double[]{since(jumpNs, firstUpNs), since(jumpNs, windowUpNs), since(jumpNs, windowFeaturesNs),
                upSinceJump.size(), featuredSinceJump.size()};
    }

    // ---- terrain: the GPU ----

    /** A batch and the pages it lacks, computed while the batch before it is on the GPU. */
    private record Planned(int[] batch, int cx, int cz, java.util.concurrent.CompletableFuture<int[][]> pages) {}

    private Planned plan(int ccx, int ccz, int max) {
        int[] batch = next(ccx, ccz, max);
        if (batch == null) return null;
        for (int i = 0; i < batch.length; i += 2) done.add(ChunkPos.pack(batch[i], batch[i + 1]));  // claimed
        if (devicePages)  // the device makes the pages (atlas_page.slang): nothing to compute here
            return new Planned(batch, ccx, ccz, java.util.concurrent.CompletableFuture.completedFuture(new int[][]{new int[0]}));
        return new Planned(batch, ccx, ccz, java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            int[] miss = gen.missing(batch);  // answers while a batch runs
            int[][] made = new int[miss.length / 3 + 1][];
            made[0] = miss;
            IntStream.range(0, miss.length / 3).parallel()
                    .forEach(i -> made[i + 1] = pages.page(miss[i * 3], miss[i * 3 + 1], miss[i * 3 + 2] != 0));
            return made;
        }));
    }

    private void run() {
        // the map to the device first (seconds at most; Java's pages serve until it is up): then the device makes them
        try {
            String on = devicePages(gen, program, generator.getBiomeSource());
            devicePages = on.startsWith("on the device");
            com.nirncraft.NirnCraft.LOGGER.info("atlas client generation: pages {}", on);
            farOn = direct && far() && devicePages && gen.farIngestSupported();
            com.nirncraft.NirnCraft.LOGGER.info("atlas client generation: far terrain {}", farOn ? "into the grid's coarse levels (GPU)"
                    : "from the tiles (" + (!direct ? "not GPU to GPU" : !far() ? "nirncraft.atlas.clientGen.far=false"
                    : !devicePages ? "no device pages" : "the library has no far ingest") + ")");
        } catch (RuntimeException e) {
            com.nirncraft.NirnCraft.LOGGER.warn("atlas client generation: pages stay in Java", e);
        }
        // the self-baking cache's fingerprint, now that the generator is whole (program, stages, ingest, map): the
        // generator's completed by the far identity (world generation and painting code)
        if (cacheArm) gen.setFingerprint((long) AtlasTileCodec.generatorIdentity().hashCode() << 32 ^ AtlasTileCodec.VERSION);
        // the whole-map coarse bake and the boot load of the far tiles (its own thread; farStep keeps the tiles it does not own)
        if (cacheArm && farOn) bake = AtlasCoarseBake.start(gen);
        if (serverGen != null) serverGen.up();
        int[] ids = new int[BATCH * AtlasGenNative.CHUNK_WORDS];
        int[] flags = new int[BATCH];
        int[] info = new int[BATCH * AtlasGenNative.INFO_WORDS];
        long lastJump = 0;
        Planned cur = null;
        long loopLogNs = 0;
        while (running) {
            try {
                refreshStats();
                // the integrated server's chunks first: they are what the world waits on (a few, in the store's call)
                if (serverGen != null && direct && storeOn) {
                    boolean served = false;
                    try {
                        serverGen.prioritize(gen);
                        served = serveServer(ids, flags, info);
                    } catch (RuntimeException e) {  // (the server's chunks never stop the camera's: Java makes them from now on)
                        com.nirncraft.NirnCraft.LOGGER.warn("atlas client generation: the server feed is off", e);
                        AtlasServerGen sg = serverGen;
                        serverGen = null;
                        com.nirncraft.atlas.server.AtlasServerFeed.unbind(sg);
                        sg.close();
                    }
                    if (served) continue;
                }
                long ln = System.nanoTime();
                if (ln - loopLogNs > 20_000_000_000L) {
                    // (dev: the worker's state to the log every 20 s)
                    loopLogNs = ln;
                    com.nirncraft.NirnCraft.LOGGER.info("atlas worker: chunks {} generated {} cached {} deferred {} near idle {} far calls {} far tiles {}, camera set {}",
                            chunks, generatedChunks, cachedChunks, deferred.size(), nearIdle, farCalls, farTiles, camSet);
                }
                int waiting;
                synchronized (ready) {
                    waiting = ready.size();
                }
                // (direct: terrain goes device to device, the ready queue holds only decorated chunks: never a reason to wait)
                boolean full = !direct && waiting >= READY_MAX;
                if (camSet && !full && !AtlasCoarseBake.probing() && outerRingsWait()) {
                    // (the far tiles the bake does not own, the finer levels around the spawn, go on: the horizon gate waits for them)
                    boolean moved = farOn && farStep();
                    nearIdle = true;
                    if (!moved) LockSupport.parkNanos(20_000_000L);
                    continue;
                }
                if (!camSet || full || AtlasCoarseBake.probing()) {
                    LockSupport.parkNanos(full ? 2_000_000L : 20_000_000L);
                    continue;
                }
                boolean fresh = jumpNs != lastJump;
                lastJump = jumpNs;
                if (cur != null && (fresh || Math.max(Math.abs(cur.cx - camCx), Math.abs(cur.cz - camCz)) >= 2)) {
                    // the camera jumped, or moved on since this batch was planned (while the last one ran): it goes back
                    // (its chunks not generated) and the requests are ordered again from where the camera is now
                    for (int i = 0; i < cur.batch.length; i += 2) done.remove(ChunkPos.pack(cur.batch[i], cur.batch[i + 1]));
                    cur = null;
                }
                long rz = AtlasRecorder.begin();
                if (cur == null) cur = plan(camCx, camCz, fresh ? FIRST_BATCH : BATCH);
                AtlasRecorder.end(Z_PLAN, rz, 50_000);
                terrainBusy = cur != null;
                rz = AtlasRecorder.begin();
                if (direct) gridReports();
                AtlasRecorder.end(Z_REPORTS, rz, 50_000);
                if (cur == null && direct && storeOn && !deferred.isEmpty()) {
                    // the window looked up: the misses it deferred, nearest first (those the camera left forgotten)
                    int ccx = camCx, ccz = camCz;
                    List<Long> near = new ArrayList<>();
                    for (java.util.Iterator<Long> it = deferred.iterator(); it.hasNext(); ) {
                        long k = it.next();
                        if (!inWindow(ChunkPos.getX(k), ChunkPos.getZ(k), ccx, ccz, RADIUS + 1)) {
                            it.remove();
                            done.remove(k);
                        } else {
                            near.add(k);
                        }
                    }
                    near.sort(java.util.Comparator.comparingLong(k -> {
                        long dx = ChunkPos.getX(k) - ccx, dz = ChunkPos.getZ(k) - ccz;
                        return dx * dx + dz * dz;
                    }));
                    if (!near.isEmpty()) {
                        // one 8 x 8 patch at a time, the nearest's (as next() plans): the nearest 64 by distance were a growing
                        // ring, whose neighbourhood wants more slots than the store has (88 chunks waiting in 768 slots, none
                        // able to take a neighbour: the spawn area stuck at 488 of 1793)
                        int px0 = ChunkPos.getX(near.get(0)) >> 3, pz0 = ChunkPos.getZ(near.get(0)) >> 3;
                        near.removeIf(k -> (ChunkPos.getX(k) >> 3) != px0 || (ChunkPos.getZ(k) >> 3) != pz0);
                    }
                    int n = Math.min(BATCH, near.size());
                    if (n > 0) {
                        nearIdle = false;
                        // The store keeps a patch's neighbourhood at a time (next() sends none while 64 chunks wait). The
                        // deferred flush had no such rule: a cold window's 1793 misses went in 64 a call, past the store's 768
                        // slots, and the store let go the farthest of the chunks still waiting for their neighbours (a chunk
                        // lost for good: `done` held it, nothing asked again): 800 of 1793 chunks came out, the spawn area never.
                        int[] stNow = gen.storeStats();
                        if (stNow != null && stNow.length > 1 && stNow[1] > 0) {  // (one patch in the store at a time: with 64 waiting, patches overlapped in 768 slots and none of them could take a neighbour: 112 waiting, held 768, 1221 of 1793 up and no more)
                            long before = storeFinished;
                            directBatch(new int[0], ids, flags, info, false);  // (what waits runs on, and comes out)
                            if (storeFinished == before) LockSupport.parkNanos(2_000_000L);
                            continue;
                        }
                        int[] b = new int[n * 2];
                        for (int i = 0; i < n; i++) {
                            long k = near.get(i);
                            deferred.remove(k);
                            b[i * 2] = ChunkPos.getX(k);
                            b[i * 2 + 1] = ChunkPos.getZ(k);
                        }
                        directBatch(b, ids, flags, info, false);
                        chunks += n;
                        continue;
                    }
                }
                if (cur == null && direct && storeOn) {
                    // nothing new to generate: the store's ready chunks still go out (64 a call)
                    long before = storeFinished;
                    rz = AtlasRecorder.begin();
                    directBatch(new int[0], ids, flags, info, false);
                    AtlasRecorder.end(Z_DIRECT, rz, 50_000);
                    if (storeFinished != before) continue;
                }
                if (cur == null) {
                    nearIdle = true;
                    reconcileWindow();
                    rz = AtlasRecorder.begin();
                    if (direct) pruneDirect(camCx, camCz);
                    else prune(camCx, camCz);
                    AtlasRecorder.end(Z_PRUNE, rz, 50_000);
                    // the near window done: the far terrain (a tile a turn, so a move is answered at once)
                    rz = AtlasRecorder.begin();
                    boolean farMoved = farOn && farStep();
                    AtlasRecorder.end(Z_FAR, rz, 50_000);
                    if (farMoved) {
                        farIdle = false;
                        continue;
                    }
                    farIdle = true;
                    for (Thread t : featureWorkers) LockSupport.unpark(t);
                    verifyStep();
                    LockSupport.parkNanos(20_000_000L);
                    continue;
                }
                nearIdle = farIdle = false;
                long t0 = System.nanoTime();
                int[][] made = cur.pages.join();
                int[] miss = made[0];
                for (int i = 0; i < miss.length / 3; i++) gen.putPage(miss[i * 3], miss[i * 3 + 1], miss[i * 3 + 2] != 0, made[i + 1]);
                long t1 = System.nanoTime();
                AtlasRecorder.end(Z_PAGES, t0, 50_000);
                int[] batch = cur.batch;
                // the next batch's pages while this one is on the GPU
                rz = AtlasRecorder.begin();
                Planned next = plan(camCx, camCz, BATCH);
                AtlasRecorder.end(Z_PLAN, rz, 50_000);
                int n = batch.length / 2;
                if (direct) {
                    if (fresh) firstBatchPagesNs = t1;
                    rz = AtlasRecorder.begin();
                    directBatch(batch, ids, flags, info, fresh, true);
                    AtlasRecorder.end(Z_DIRECT, rz, 0, n);
                    rz = AtlasRecorder.begin();
                    pruneDirect(camCx, camCz);
                    AtlasRecorder.end(Z_PRUNE, rz, 50_000);
                    rz = AtlasRecorder.begin();
                    if (farOn && (chunks / BATCH) % 4 == 3 && farTurnOk()) farStep();  // the far terrain between near batches too, when the near window leaves the budget room
                    AtlasRecorder.end(Z_FAR, rz, 50_000);
                    chunks += n;
                    pagesMade += miss.length / 3;
                    pageMs += (t1 - t0) / 1e6;
                    genMs += (System.nanoTime() - t1) / 1e6;
                    cur = next;
                    for (Thread t : featureWorkers) LockSupport.unpark(t);
                    continue;
                }
                long tg = System.nanoTime();
                AtlasGenNative.Times gt = gen.chunks(batch, 0, ids, flags);
                long td = System.nanoTime();
                gpuMs += (td - tg) / 1e6;
                kernelMs += gt.kernelMs();
                readbackMs += gt.readbackMs();
                // decoded on all cores (the queue takes each as it is ready; the uploader starts on the first)
                IntStream.range(0, n).parallel().forEach(i -> {
                    int cx = batch[i * 2], cz = batch[i * 2 + 1];
                    if (flags[i] != 0) {
                        synchronized (this) {
                            flagged++;  // not proven exact here: the server's blocks
                        }
                        return;
                    }
                    // the camera may have jumped while this batch ran: what is far from it now goes back, not up
                    if (!inWindow(cx, cz, camCx, camCz, RADIUS + 1)) {
                        done.remove(ChunkPos.pack(cx, cz));
                        return;
                    }
                    short[][] secs = compact(ids, i);
                    offer(decode(cx, cz, secs), false);
                    if (features != null && near(cx, cz, camCx, camCz, FEATURE_RADIUS + 2)) store.put(ChunkPos.pack(cx, cz), secs);
                });
                decodeMs += (System.nanoTime() - td) / 1e6;
                chunks += n;
                pagesMade += miss.length / 3;
                pageMs += (t1 - t0) / 1e6;
                genMs += (System.nanoTime() - t1) / 1e6;
                cur = next;
                for (Thread t : featureWorkers) LockSupport.unpark(t);
            } catch (Throwable t) {
                if (!running) return;
                com.nirncraft.NirnCraft.LOGGER.warn("atlas client generation stopped", t);
                running = false;
            }
        }
    }

    /**
     * A batch GPU to GPU: generated on the context's device, its sections' kinds and hashes back (decode's and putGen's
     * work, done by the kernels), the ones the core lacks claimed here (AtlasEditsClient's hashes) and handed to the grid
     * device to device. Only the chunks the features will decorate bring their ids back.
     */
    private void directBatch(int[] batch, int[] ids, int[] flags, int[] info, boolean first) {
        directBatch(batch, ids, flags, info, first, false);
    }

    /**
     * (defer) The cache's chunks of the batch go up now and its misses wait (deferred) until the window has nothing
     * left to look up: a relaunch serves its whole window from disk first, not a batch's hits behind the decoration
     * of its misses (seconds a call).
     */
    private void directBatch(int[] batch, int[] ids, int[] flags, int[] info, boolean first, boolean defer) {
        directBatch(batch, ids, flags, info, first, defer, false);
    }

    /** The server's requests that are ready go into the store ahead of the window's chunks: its answer comes from the next calls. */
    private boolean serveServer(int[] ids, int[] flags, int[] info) {
        int[] b = serverGen.take(16);
        if (b.length == 0) return false;
        for (int i = 0; i < b.length; i += 2) done.add(ChunkPos.pack(b[i], b[i + 1]));  // (claimed: the window does not plan them too)
        directBatch(b, ids, flags, info, false, false, true);
        return true;
    }

    private void directBatch(int[] batch, int[] ids, int[] flags, int[] info, boolean first, boolean defer, boolean server) {
        long finished = storeFinished;
        directBatchRun(batch, ids, flags, info, first, defer, server);
        if (batch.length > 0 || storeFinished != finished) nearTurns++;  // (a turn at the generator: the bake waits for one)
    }

    /** Calls of the near window that did something (a batch in, or chunks out of the store): the bake's turn-taking while the screen holds. */
    private volatile long nearTurns;

    /**
     * The spawn window after a jump, chunks the store let go before they came out (its slots are few: 768 for a window of
     * 1793, and a chunk dropped while waiting for neighbours stays in `done`, so nothing asks again): once the near work is idle
     * and the window is still short, the ones not up are forgotten here so that the plan makes them again. Every 4 s at most.
     */
    private long reconcileNs;

    /**
     * The loading phase's order: the spawn area's nearest rings, then the horizon (the coarse levels), then the window's outer
     * rings. While the screen holds the player and the spawn area is in but the horizon is not, the outer rings wait (the GPU is
     * the bake's: they went on beside it, and the player waited for both); they follow once the horizon is loaded, in play.
     */
    private boolean outerRingsWait() {
        if (!AtlasLoadingScreen.holding() || windowUpNs != 0) return false;
        Progress p = progress();
        if (p == null || !p.nearDone()) return false;
        AtlasCoarseBake.Progress b = AtlasCoarseBake.progress();
        return b != null && b.phase() > 0 && !b.loaded();
    }

    /**
     * A chunk of the spawn window that atlas does not upload itself counts as in: one not proven exact (the server's blocks
     * supply it) or one whose loaded blocks are up already. The window was never "up" while a couple of them stood in it.
     */
    private void markCovered(int cx, int cz) {
        if (jumpNs != 0 && inWindow(cx, cz, jumpCx, jumpCz, RADIUS)) {
            upSinceJump.add(ChunkPos.pack(cx, cz));
            if (windowUpNs == 0 && upSinceJump.size() >= windowChunks()) windowUpNs = System.nanoTime();
        }
    }

    private void reconcileWindow() {
        long now = System.nanoTime();
        if (windowUpNs != 0 || jumpNs == 0 || now - reconcileNs < 4_000_000_000L || now - nearWorkNs < 3_000_000_000L || !deferred.isEmpty()) return;
        reconcileNs = now;
        int lost = 0;
        for (int dz = -RADIUS; dz <= RADIUS; dz++)
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                if (dx * dx + dz * dz > RADIUS * RADIUS) continue;
                long k = ChunkPos.pack(jumpCx + dx, jumpCz + dz);
                if (!upSinceJump.contains(k) && done.remove(k)) {
                    lost++;
                    // (its sections were claimed as uploaded, and the grid does not hold them (it refilled after the claim): asked
                    // again without this, the claim said "the core has these blocks" and the chunk never came up: 151 of 441,
                    // the loading gate waiting for good)
                    for (int sec = 0; sec < 40; sec++) AtlasEditsClient.unclaim(net.minecraft.core.SectionPos.asLong(jumpCx + dx, sec - 4, jumpCz + dz));
                }
            }
        if (lost > 0) com.nirncraft.NirnCraft.LOGGER.info("atlas client generation: {} chunks of the spawn window were not up after the near work went idle: asked again", lost);
    }

    // ---- the idle verifier ----
    // The generation and bake caches are keyed by what produces the data (the generator's sources, its data inputs, the seed:
    // cmake/buildhash.cmake ATLAS_GEN_HASH), not by the whole native build, so a render-kernel rebuild keeps them. A file the
    // key does not name that does change what the generator makes would serve stale data: so once the load is over and the
    // window idle, every VERIFY_S seconds one cached chunk or one cached stand-in far tile (alternately) is made again apart
    // from the cache and compared (a chunk: every section's hash and kind; a tile: every chunk's kind and uniform material).
    // A difference is counted (verify{} of the profile line / atlas-info) and what differed is made again.
    static final double VERIFY_S = Double.parseDouble(System.getProperty("nirncraft.atlas.verify.seconds", "12"));
    private long verifyNs;
    private int verifyTurn;
    private final java.util.Random verifyRnd = new java.util.Random(0x5EED);

    private void verifyStep() {
        if (!gen.verifySupported() || VERIFY_S <= 0 || !AtlasLoadStats.finished() || AtlasLoadingScreen.holding() || !camSet) return;
        long now = System.nanoTime();
        if (now - verifyNs < (long) (VERIFY_S * 1e9)) return;
        verifyNs = now;
        try {
            if ((verifyTurn++ & 1) == 0) verifyChunk();
            else verifyTile();
        } catch (RuntimeException e) {
            com.nirncraft.NirnCraft.LOGGER.warn("atlas verifier: {}", e.toString());
        }
    }

    private void verifyChunk() {
        int r = RADIUS / 2;
        for (int tries = 0; tries < 24; tries++) {
            int cx = camCx + verifyRnd.nextInt(2 * r + 1) - r, cz = camCz + verifyRnd.nextInt(2 * r + 1) - r;
            int[] xz = {cx, cz}, cached = new int[AtlasGenNative.INFO_WORDS];
            byte[] hit = new byte[1];
            if (gen.cacheLookup(xz, cached, hit) == 0 || hit[0] == 0) continue;
            int[] outXZ = new int[AtlasGenNative.RUN_CHUNKS * 2], fl = new int[AtlasGenNative.RUN_CHUNKS];
            int[] made = new int[AtlasGenNative.RUN_CHUNKS * AtlasGenNative.INFO_WORDS];
            int m = 0;
            for (int call = 0; call < 8 && m == 0; call++) m = gen.runStore(call == 0 ? xz : new int[0], outXZ, null, fl, made, null);
            if (m > 0) gen.ingest(new long[m]);  // (nothing sent: the batch is released)
            // (the store brings out whatever else was ready too, packed and not sent: asked for again, else holes in the window)
            for (int i = 0; i < m; i++)
                if (outXZ[i * 2] != cx || outXZ[i * 2 + 1] != cz) done.remove(ChunkPos.pack(outXZ[i * 2], outXZ[i * 2 + 1]));
            for (int i = 0; i < m; i++) {
                if (outXZ[i * 2] != cx || outXZ[i * 2 + 1] != cz || fl[i] != 0) continue;
                int o = i * AtlasGenNative.INFO_WORDS;
                boolean same = true;
                for (int w = 0; w < 3 && same; w++) same = made[o + w] == cached[w];
                for (int sec = 0; sec < 40 && same; sec++)
                    for (int w = 0; w < 3 && same; w++) same = made[o + 4 + sec * 4 + w] == cached[4 + sec * 4 + w];
                gen.verifyNote(!same);
                if (!same) {
                    com.nirncraft.NirnCraft.LOGGER.warn("atlas verifier: cached chunk ({}, {}) differs from the generator's: made again", cx, cz);
                    regenerate(new long[]{ChunkPos.pack(cx, cz)});
                }
                return;
            }
            return;
        }
    }

    private void verifyTile() {
        int[] t = AtlasCoarseBake.sampleKnownTile(verifyRnd);
        if (t == null) return;
        int res = gen.verifyFarTile(t[0], t[1], t[2]);
        if (res == 1) {
            com.nirncraft.NirnCraft.LOGGER.warn("atlas verifier: cached far tile level {} ({}, {}) differs from the generator's: baked again", t[0], t[1], t[2]);
            AtlasCoarseBake.dropped(t[0], t[1], t[2]);  // (the grid's copy is put again from the cache)
        }
    }

    /** {chunks checked, chunks that differed, tiles checked, tiles that differed} of the verifier, as a profile block. */
    private String verifyLine() {
        long[] v = gen.verifyStats();
        return v == null ? null : String.format("verify{chunks %d differed %d, tiles %d differed %d}", v[0], v[1], v[2], v[3]);
    }

    static long nearTurns() {
        AtlasClientGen c = active;
        return c == null ? 0 : c.nearTurns;
    }

    /** Whether the near window has nothing to make or send now (the bake need not wait for it). */
    static boolean nearIdleNow() {
        AtlasClientGen c = active;
        return c == null || c.nearIdle && c.deferred.isEmpty();
    }

    private void directBatchRun(int[] batch, int[] ids, int[] flags, int[] info, boolean first, boolean defer, boolean server) {
        if (batch.length > 0) nearWorkNs = System.nanoTime();
        // (the self-baking cache) the chunks it knows go into the grid from disk, not generated; the features' chunks
        // (their ids wanted here) are generated as before
        if (gen.cacheSupported() && !server) {  // (the server's chunks skip the cache: it holds sections, the server needs the ids)
            // (the chunk store) only the misses go into it: it makes the neighbourhood their stages read by itself
            // (atlas_gen_run_store), so a cached chunk is never made again nor packed again (the churn: every chunk
            // through the store, its neighbours sent back to the start by each new one)
            long cz = AtlasRecorder.begin();
            batch = fromCache(batch, info);
            AtlasRecorder.end(Z_CACHE, cz, 50_000);
            if (batch.length == 0 && !storeOn) return;
            if (defer && storeOn && batch.length > 0) {
                for (int i = 0; i < batch.length; i += 2) deferred.add(ChunkPos.pack(batch[i], batch[i + 1]));
                return;
            }
        }
        int n = batch.length / 2;
        boolean wantIds = false;
        if (features != null && !storeOn)
            for (int i = 0; i < n && !wantIds; i++) wantIds = near(batch[i * 2], batch[i * 2 + 1], camCx, camCz, FEATURE_RADIUS + 2);
        long tg = System.nanoTime();
        AtlasGenNative.Times gt;
        if (storeOn) {
            // generated into the store; what comes out (decorated) is not this batch but the chunks whose neighbourhood
            // is ready: the rest of this call works on those
            int[] outXZ = new int[AtlasGenNative.RUN_CHUNKS * 2];
            AtlasGenNative.Times[] tt = new AtlasGenNative.Times[1];
            long gz = AtlasRecorder.begin();
            generatedChunks += n;  // (these chunks the cache did not have: through the store's stages)
            AtlasServerGen sg = serverGen;
            boolean idsForServer = sg != null && sg.pending();
            int m = gen.runStore(batch, outXZ, idsForServer ? ids : null, flags, info, tt);
            if (idsForServer && m > 0) sg.delivered(outXZ, m, ids, flags, info);
            AtlasRecorder.end(Z_RUN_STORE, gz, 0, batch.length / 2);
            gt = tt[0];
            storeFinished += m;
            if (m > 0) nearWorkNs = System.nanoTime();
            batch = java.util.Arrays.copyOf(outXZ, m * 2);
            n = m;
            if (m == 0) {
                gpuMs += (System.nanoTime() - tg) / 1e6;
                return;
            }
        } else {
            long gz = AtlasRecorder.begin();
            generatedChunks += n;
            gt = gen.run(batch, wantIds ? ids : null, flags, info);
            AtlasRecorder.end(Z_RUN, gz, 0, n);
        }
        long td = System.nanoTime();
        gpuMs += (td - tg) / 1e6;
        kernelMs += gt.kernelMs();
        readbackMs += gt.readbackMs();
        long[] masks = new long[n];
        int ccx = camCx, ccz = camCz;
        long claimZ = AtlasRecorder.begin();
        for (int i = 0; i < n; i++) {
            int cx = batch[i * 2], cz = batch[i * 2 + 1];
            if (flags[i] != 0) {
                flagged++;  // not proven exact here: the server's blocks
                markCovered(cx, cz);
                continue;
            }
            if (!inWindow(cx, cz, ccx, ccz, RADIUS + 1)) {
                done.remove(ChunkPos.pack(cx, cz));  // the camera left while it ran: generated again if it comes back
                continue;
            }
            if (wantIds && near(cx, cz, ccx, ccz, FEATURE_RADIUS + 2)) store.put(ChunkPos.pack(cx, cz), compact(ids, i));
            if (AtlasClientSections.isReal(cx, cz)) {  // the loaded chunk's own blocks are up: they stay
                markCovered(cx, cz);
                continue;
            }
            int o = i * AtlasGenNative.INFO_WORDS + 4;
            int highest = -1;
            for (int sec = 0; sec < 40; sec++) {
                int kind = info[o + sec * 4 + 2];
                if (kind == AtlasGenNative.SEC_BLOCKS) highest = sec;
                else if (kind == AtlasGenNative.SEC_ROCK) deepRock.add(net.minecraft.core.SectionPos.asLong(cx, sec - 4, cz));
            }
            // the column whole from its lowest kept section to its highest with blocks (air between included: the grid
            // takes rock under the lowest, air over the highest)
            long m = 0;
            boolean started = false;
            for (int sec = 0; sec <= highest; sec++) {
                int kind = info[o + sec * 4 + 2];
                if (kind == AtlasGenNative.SEC_ROCK || (kind == AtlasGenNative.SEC_AIR && !started)) continue;
                started = true;
                long h = (info[o + sec * 4] & 0xFFFFFFFFL) | (long) info[o + sec * 4 + 1] << 32;
                if (AtlasEditsClient.claim(net.minecraft.core.SectionPos.asLong(cx, sec - 4, cz), h)) m |= 1L << sec;
            }
            masks[i] = m;
        }
        decodeMs += (System.nanoTime() - td) / 1e6;
        AtlasRecorder.end(Z_CLAIM, claimZ, 50_000);
        long tu = System.nanoTime();
        long[] asked = masks.clone();
        int sent = gen.ingest(masks);
        long t1 = System.nanoTime();
        AtlasRecorder.end(Z_INGEST, tu, 0, sent);
        boolean refused = false;
        for (int i = 0; i < n; i++) {
            long lost = asked[i] & ~masks[i];
            if (lost == 0) continue;
            // not taken (no window yet, outside it, the pool full): forgotten, so they go again when they can
            refused = true;
            done.remove(ChunkPos.pack(batch[i * 2], batch[i * 2 + 1]));
            for (int sec = 0; sec < 40; sec++)
                if ((lost >>> sec & 1) != 0) AtlasEditsClient.unclaim(net.minecraft.core.SectionPos.asLong(batch[i * 2], sec - 4, batch[i * 2 + 1]));
        }
        if (sent < 0) {
            LockSupport.parkNanos(50_000_000L);
            return;
        }
        if (refused) LockSupport.parkNanos(5_000_000L);
        uploadMs += (t1 - tu) / 1e6;
        uploadedSections += sent;
        if (first && jumpNs != 0)
            firstBatch = String.format("pages up %.0f, generated %.0f (kernels %.1f), claimed %.0f, sent %.0f ms after the move",
                    (firstBatchPagesNs - jumpNs) / 1e6, (td - jumpNs) / 1e6, gt.kernelMs(), (tu - jumpNs) / 1e6, (t1 - jumpNs) / 1e6);
        for (int i = 0; i < n; i++) {
            int cx = batch[i * 2], cz = batch[i * 2 + 1];
            if (flags[i] != 0 || !inWindow(cx, cz, ccx, ccz, RADIUS + 1) || !done.contains(ChunkPos.pack(cx, cz))) continue;
            AtlasClientSections.up(cx, cz);
            uploadedChunks++;
            if (jumpNs != 0 && inWindow(cx, cz, jumpCx, jumpCz, RADIUS)) {
                if (firstUpNs == 0) firstUpNs = t1;
                upSinceJump.add(ChunkPos.pack(cx, cz));
                if (windowUpNs == 0 && upSinceJump.size() >= windowChunks()) windowUpNs = t1;
            }
        }
    }

    private long cachedChunks, cachedSections, farCached;
    // (direct, the store) the cache's misses waiting for the window's lookups to finish (directBatch's defer); any thread
    // may drop one (regenerate)
    private final Set<Long> deferred = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean cacheArm;  // (the self-baking cache configured: its fingerprint armed by the worker, run)
    private volatile boolean nearIdle, farIdle;
    private volatile long nearWorkNs;  // (the last near batch planned, deferred, served or finished)  // (the worker found nothing to do: the window's chunks, the far tiles)

    /** {the near window has nothing left to make or send, the far tiles nothing to ask for}; null without a generator. */
    static boolean[] idle() {
        AtlasClientGen c = active;
        if (c == null) return null;
        // (near: nothing planned, deferred, sent or finished by the store for a second; the store's edge chunks may wait
        // for neighbours nobody asks for, so its waiting count is no signal)
        boolean near = c.nearIdle && c.deferred.isEmpty() && System.nanoTime() - c.nearWorkNs > 1_000_000_000L;
        return new boolean[]{near, c.farIdle || !c.farOn};
    }
    private volatile long generatedChunks;  // (chunks that went through the generator: not served by the cache)

    /** The loading phase's view of the generator (AtlasLoadingScreen). */
    record Progress(boolean running, int near, int nearTotal, boolean nearDone, int far, int farTotal, long cached, long farCached,
                    long generated) {
        String describe() {
            return String.format("spawn area %d / %d chunks (%d from the cache), horizon %d / %d tiles (%d from the cache), %d generated",
                    near, nearTotal, cached, far, farTotal, farCached, generated);
        }
    }

    /** null without an active generator. */
    static Progress progress() {
        AtlasClientGen c = active;
        if (c == null) return null;
        int farTotal = c.farOn ? 6 * (2 * FAR_TILES + 1) * (2 * FAR_TILES + 1) : 0, farUp = Math.min(c.farDone.size(), farTotal);
        // the tiles of levels 3-6 are the coarse bake's (AtlasCoarseBake: its own gate, `loaded`): farStep only counts the finer
        // levels' tiles it makes, so the far gate is those, of their share (it waited for 150 when 50 could ever be done)
        AtlasCoarseBake.Progress bk = AtlasCoarseBake.progress();
        if (c.farOn && bk != null && bk.phase() > 0) {
            farTotal = 2 * (2 * FAR_TILES + 1) * (2 * FAR_TILES + 1);
            int up = 0;
            for (long k : c.farDone)
                if ((int) (k >>> 56) < AtlasCoarseBake.LOAD_LO) up++;
            farUp = Math.min(up, farTotal);
        }
        // the spawn area the loading gate waits for: the chunks within SPAWN_RADIUS of where the player joined (nearest first
        // the generator makes them); the rest of the window goes on while the player plays
        int spawnTotal = windowChunks(SPAWN_RADIUS), spawnUp = c.jumpNs == 0 ? 0 : c.countUp(SPAWN_RADIUS);
        return new Progress(camSet, spawnUp, spawnTotal, c.windowUpNs != 0 || (c.jumpNs != 0 && spawnUp >= spawnTotal), farUp, farTotal,
                c.cachedChunks, c.farCached, c.chunks);
    }

    /** The chunks of batch the cache knows put into the grid from it; returns the others (to generate). */
    private int[] fromCache(int[] batch, int[] info) {
        int n = batch.length / 2;
        byte[] hit = new byte[n];
        if (gen.cacheLookup(batch, info, hit) == 0) return batch;
        int ccx = camCx, ccz = camCz;
        int[] rest = new int[batch.length], known = new int[batch.length];
        long[] masks = new long[n];
        int nr = 0, nk = 0;
        for (int i = 0; i < n; i++) {
            int cx = batch[i * 2], cz = batch[i * 2 + 1];
            boolean feat = features != null && near(cx, cz, ccx, ccz, FEATURE_RADIUS + 2);
            if (hit[i] == 0 || feat) {
                rest[nr++] = cx;
                rest[nr++] = cz;
                continue;
            }
            if (!inWindow(cx, cz, ccx, ccz, RADIUS + 1)) {
                done.remove(ChunkPos.pack(cx, cz));
                continue;
            }
            if (AtlasClientSections.isReal(cx, cz)) continue;  // the loaded chunk's own blocks are up: they stay
            masks[nk / 2] = masksOf(cx, cz, info, i * AtlasGenNative.INFO_WORDS + 4);
            known[nk++] = cx;
            known[nk++] = cz;
        }
        if (nk > 0) {
            int[] kb = java.util.Arrays.copyOf(known, nk);
            long[] km = java.util.Arrays.copyOf(masks, nk / 2);
            long[] asked = km.clone();
            int sent = gen.cacheIngest(kb, km);
            for (int i = 0; i < nk / 2; i++) {
                int cx = kb[i * 2], cz = kb[i * 2 + 1];
                long lost = asked[i] & ~km[i];
                if (lost != 0 || sent < 0) {
                    done.remove(ChunkPos.pack(cx, cz));  // (not taken: again when it can)
                    for (int sec = 0; sec < 40; sec++)
                        if ((lost >>> sec & 1) != 0) AtlasEditsClient.unclaim(net.minecraft.core.SectionPos.asLong(cx, sec - 4, cz));
                    continue;
                }
                AtlasClientSections.up(cx, cz);
                uploadedChunks++;
                cachedChunks++;
                if (jumpNs != 0 && inWindow(cx, cz, jumpCx, jumpCz, RADIUS)) {
                    long t = System.nanoTime();
                    if (firstUpNs == 0) firstUpNs = t;
                    upSinceJump.add(ChunkPos.pack(cx, cz));
                    if (windowUpNs == 0 && upSinceJump.size() >= windowChunks()) windowUpNs = t;
                }
            }
            if (sent > 0) cachedSections += sent;
        }
        return java.util.Arrays.copyOf(rest, nr);
    }

    /** A chunk's sections to send, from its info row at o (directBatch's rule; claims them as AtlasEditsClient's). */
    private long masksOf(int cx, int cz, int[] info, int o) {
        int highest = -1;
        for (int sec = 0; sec < 40; sec++) {
            int kind = info[o + sec * 4 + 2];
            if (kind == AtlasGenNative.SEC_BLOCKS) highest = sec;
            else if (kind == AtlasGenNative.SEC_ROCK) deepRock.add(net.minecraft.core.SectionPos.asLong(cx, sec - 4, cz));
        }
        long m = 0;
        boolean started = false;
        for (int sec = 0; sec <= highest; sec++) {
            int kind = info[o + sec * 4 + 2];
            if (kind == AtlasGenNative.SEC_ROCK || (kind == AtlasGenNative.SEC_AIR && !started)) continue;
            started = true;
            long h = (info[o + sec * 4] & 0xFFFFFFFFL) | (long) info[o + sec * 4 + 1] << 32;
            if (AtlasEditsClient.claim(net.minecraft.core.SectionPos.asLong(cx, sec - 4, cz), h)) m |= 1L << sec;
        }
        return m;
    }

    /**
     * The radius, in chunks, of the spawn area the loading phase waits for (the generator's window is RADIUS, 24, 1793 chunks;
     * the decoration of a chunk is 70 ms of GPU time, so the whole window is minutes cold). The window's nearest ring is made
     * first; the rest follows while the player plays, the coarse levels under it. -Dnirncraft.atlas.spawnRadius=24: all of it.
     */
    static final int SPAWN_RADIUS = Math.min(RADIUS, Integer.getInteger("nirncraft.atlas.spawnRadius", 12));

    private int countUp(int radius) {
        int n = 0;
        for (int dz = -radius; dz <= radius; dz++)
            for (int dx = -radius; dx <= radius; dx++)
                if (dx * dx + dz * dz <= radius * radius && upSinceJump.contains(ChunkPos.pack(jumpCx + dx, jumpCz + dz))) n++;
        return n;
    }

    private static int windowChunks(int r) {
        int w = 0;
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++) if (dx * dx + dz * dz <= r * r) w++;
        return w;
    }

    private static int windowChunks() {
        int w = 0;
        for (int dz = -RADIUS; dz <= RADIUS; dz++)
            for (int dx = -RADIUS; dx <= RADIUS; dx++) if (dx * dx + dz * dz <= RADIUS * RADIUS) w++;
        return w;
    }

    /** The next chunks to generate: within the window, not done, nearest first and in view before behind. */
    private int[] next(int ccx, int ccz, int max) {
        double yaw = Math.toRadians(camYaw);
        double dirX = -Math.sin(yaw), dirZ = Math.cos(yaw);
        // where the camera will be (chunks from the camera's chunk): what it moves towards comes before what it leaves
        double px = camVx * PREDICT_S / 16.0, pz = camVz * PREDICT_S / 16.0;
        List<long[]> cand = new ArrayList<>();
        // (the store makes the neighbourhood the window's chunks need itself; what waits in it is kept to a patch or so:
        // a batch is one 8 x 8 patch, the nearest, and none while 64 chunks wait: what waits holds its neighbourhood,
        // 14 x 14 a patch, where a ring of single chunks would want more slots than the store has)
        if (storeOn) {
            int[] st = gen.storeStats();
            if (st != null && st.length > 1 && st[1] >= 64) return null;
        }
        int r = RADIUS;
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz > r * r) continue;
                long k = ChunkPos.pack(ccx + dx, ccz + dz);
                if (done.contains(k)) continue;
                // nearest and in view first; the predicted place's nearest next (never ahead of the camera's own chunks)
                double d2 = dx * dx + dz * dz;
                double ex = dx - px, ez = dz - pz;
                double score = Math.min(d2, Math.max(2.0, 1.5 * (ex * ex + ez * ez)));
                boolean behind = dx * dirX + dz * dirZ < -1.5;
                cand.add(new long[]{k, (long) (score * (behind ? 4 : 1) * 16)});
            }
        if (cand.isEmpty()) return null;
        cand.sort(java.util.Comparator.comparingLong(c -> c[1]));
        if (storeOn) {
            int px0 = ChunkPos.getX(cand.get(0)[0]) >> 3, pz0 = ChunkPos.getZ(cand.get(0)[0]) >> 3;
            cand.removeIf(c -> (ChunkPos.getX(c[0]) >> 3) != px0 || (ChunkPos.getZ(c[0]) >> 3) != pz0);
        }
        int n = Math.min(max, cand.size());
        int[] xz = new int[n * 2];
        for (int i = 0; i < n; i++) {
            xz[i * 2] = ChunkPos.getX(cand.get(i)[0]);
            xz[i * 2 + 1] = ChunkPos.getZ(cand.get(i)[0]);
        }
        return xz;
    }

    /**
     * (direct) Forgets the chunks that left the window, and their claims: the grid drops GPU sections once they leave
     * its level-0 window (512 blocks a side from the camera at the least, more than this window's 26 chunks) and the CPU
     * World it refills from never had them, so a chunk that comes back is generated and sent again.
     */
    // ---- the far terrain (direct, FAR): the grid's coarse levels from the generator's far chunks ----

    private static long farKey(int level, int tx, int tz) {
        return (long) level << 56 ^ ((long) tx & 0xFFFFFFFL) << 28 ^ ((long) tz & 0xFFFFFFFL);
    }

    /**
     * One far tile into the grid: of the coarse levels' tiles around the camera (FAR_TILES a side), the nearest one not
     * up yet (distance in tiles of its level, finer levels first at equal distance). Returns false when all are up.
     */
    private long farRetryNs, farRetried, farTidyNs;
    private long farCalls, farNoWin, farNone, farLogNs;  // (dev: farStep's calls, levels it found unplaced, calls with no tile)
    private final Map<Long, int[]> farWin = new HashMap<>();  // (a tile: the window's origin and jumps when it was made)

    /** The columns of tile (tx, tz) (16 x 16 chunks of its level) inside the window at (ox, oz) of `side` chunks. */
    private static int tileCover(int tx, int tz, int ox, int oz, int side) {
        int ovx = Math.max(0, Math.min(16 * (tx + 1), ox + side) - Math.max(16 * tx, ox));
        int ovz = Math.max(0, Math.min(16 * (tz + 1), oz + side) - Math.max(16 * tz, oz));
        return ovx * ovz;
    }
    private final Map<Long, long[]> farRetry = new HashMap<>();  // (a partial tile: tries, next try (ns), chunks refused)

    /**
     * A far tile between two near batches: the near window comes first. The far path is the budget's overflow (the pacer's far
     * bucket is what the near bucket could not hold), and a far tile's submissions wait for it on this thread, so a tile taken
     * while the near bucket is low would hold the next near batch behind it for seconds. It goes when the near bucket is nearly
     * full, the loading screen holds the player (nothing is paced then) or no frames set a budget. While the near window keeps
     * the bucket low (a flight) the far path waits for its leftovers: the turn after the near window is idle (it runs
     * unconditionally there) is the horizon's.
     */
    private boolean farTurnOk() {
        if (AtlasLoadingScreen.holding()) return true;
        double tok = gen.nearTokensMs();
        return tok < 0.0 || tok >= 20.0;
    }

    private boolean farStep() {
        double x = camX, z = camZ;
        farCalls++;
        long dn = System.nanoTime();
        if (dn - farLogNs > 20_000_000_000L) {
            // (dev: the far terrain's steps to the log every 20 s: where a horizon with nothing on it stops)
            farLogNs = dn;
            com.nirncraft.NirnCraft.LOGGER.info("atlas far steps {}: levels without a window {}, none to ask {}, made {}, from the cache {}, busy {}, near idle {}, deferred {}",
                    farCalls, farNoWin, farNone, farTiles - farCached, farCached, farBusy, nearIdle, deferred.size());
        }
        // The tiles the grid did not take whole go again with the camera still, but never as a loop: a tile waits 4 s,
        // 8 s, 16 s between tries, tries only when its level has room for the chunks it was refused, and gives up after
        // three until the target changes (the camera moves a tile at that level: the code below forgets its tries).
        long nowNs = System.nanoTime();
        if (nowNs - farRetryNs > 1_000_000_000L && !farPartial.isEmpty()) {
            farRetryNs = nowNs;
            var it = farPartial.iterator();
            while (it.hasNext()) {
                long key = it.next();
                long[] ri = farRetry.computeIfAbsent(key, k -> new long[]{0, nowNs + 4_000_000_000L, 0});
                if (ri[0] >= 3 || nowNs < ri[1]) continue;
                int room = gen.coarseRoom((int) (key >>> 56));
                if (room >= 0 && room < Math.max(16L, ri[2])) continue;
                ri[0]++;
                ri[1] = nowNs + (4_000_000_000L << ri[0]);
                farDone.remove(key);
                it.remove();
                farRetried++;
            }
        }
        // The tiles to ask for are those that cover each level's window (the grid's: it slides with the camera, a few
        // chunks at a time), the nearest first (distance in tiles of its level, finer first at equal distance). A tile
        // is asked for again when the window slid over 16 or more more of its columns than it had when it was made
        // (the chunks it was made without were refused as outside the window), or when the level was placed afresh.
        // Before this the tiles followed the camera's tile: the window slid over a tile made while part of it was
        // outside, and nothing asked for it again: a band of empty cells at the window's leading edge, and the ring
        // right outside level 0.
        int best = -1, btx = 0, btz = 0;
        double bestD = Double.MAX_VALUE;
        int[] bestWin = null;
        boolean tidy = nowNs - farTidyNs > 1_000_000_000L;
        if (tidy) farTidyNs = nowNs;
        for (int k = 1; k <= 6; k++) {
            int[] win = gen.coarseWindow(k);
            if (win == null || win[3] == 0) {
                farNoWin++;
                continue;
            }
            int span = 256 << k, ox = win[0], oz = win[1], side = win[2];
            if (tidy) {
                final int lk = k;
                farDone.removeIf(key -> {
                    if ((int) (key >>> 56) != lk) return false;
                    int tx = (int) (key << 8 >> 36), tz = (int) (key << 36 >> 36);
                    // (a tile no window covers any more, a chunk's margin included: forgotten with its tries)
                    boolean gone = tx * 16 + 15 < ox - 16 || tx * 16 > ox + side + 16 || tz * 16 + 15 < oz - 16 || tz * 16 > oz + side + 16;
                    if (gone) {
                        farWin.remove(key);
                        farPartial.remove(key);
                        farRetry.remove(key);
                    }
                    return gone;
                });
            }
            for (int tz = Math.floorDiv(oz, 16); tz <= Math.floorDiv(oz + side - 1, 16); tz++)
                for (int tx = Math.floorDiv(ox, 16); tx <= Math.floorDiv(ox + side - 1, 16); tx++) {
                    long key = farKey(k, tx, tz);
                    int now = tileCover(tx, tz, ox, oz, side);
                    if (now == 0 || AtlasCoarseBake.owns(k, tx, tz, win)) continue;  // (the bake's tiles: baked, loaded by its thread)
                    int[] sw = farWin.get(key);
                    if (farDone.contains(key) && sw != null && sw[2] == win[4] && now - tileCover(tx, tz, sw[0], sw[1], side) < 16) continue;
                    double cx = (tx + 0.5) * span - x, cz = (tz + 0.5) * span - z;
                    double d = Math.sqrt(cx * cx + cz * cz) / span + k * 0.01;
                    if (d < bestD) {
                        bestD = d;
                        best = k;
                        btx = tx;
                        btz = tz;
                        bestWin = win;
                    }
                }
        }
        if (best < 0) {
            farNone++;
            return false;
        }
        long t0 = System.nanoTime();
        AtlasGenNative.Gen.FarIngest r;
        try {
            // (the self-baking cache: a tile seen before comes from disk; else made on the device, and baked)
            r = gen.farCacheIngest(best, btx, btz);
            if (r != null) farCached++;
            else r = gen.farIngest(best, new int[]{btx, btz}, 2000);
        } catch (RuntimeException e) {
            com.nirncraft.NirnCraft.LOGGER.warn("atlas far terrain off (the tiles' path stays): {}", e.toString());
            farOn = false;
            return false;
        }
        farMs += (System.nanoTime() - t0) / 1e6;
        if (r == null) {
            farBusy++;  // no window yet, or every far buffer still read: later
            LockSupport.parkNanos(10_000_000L);
            return true;
        }
        long key = farKey(best, btx, btz);
        farDone.add(key);
        farWin.put(key, new int[]{bestWin[0], bestWin[1], bestWin[4]});
        farRetry.remove(key);  // (asked afresh: its tries start again)
        farPartial.remove(key);
        if (r.taken() < r.chunks()) {
            farPartial.add(key);
            farRetry.computeIfAbsent(key, k -> new long[]{0, System.nanoTime() + 4_000_000_000L, 0})[2] = r.chunks() - r.taken();
        } else {
            farRetry.remove(key);
        }
        farTiles++;
        farChunks += r.chunks();
        farTaken += r.taken();
        return true;
    }

    /**
     * The grid's reports. Wanted (level 0 would take it now): made and sent again. Dropped: a near section outside the
     * window is forgotten (made again when the camera comes back), one inside waits for the grid to want it (its pool
     * was full of nearer ones: sending it again now would only churn); a coarse chunk's tile goes again once the camera
     * has moved a tile at that level.
     */
    private void gridReports() {
        if (!gen.farIngestSupported()) return;
        int[][] rep = gen.gridReports();
        int ccx = camCx, ccz = camCz;
        for (int r = 0; r < 2; r++) {
            int[] list = rep[r];
            boolean wanted = r == 1;
            for (int i = 0; i + 3 < list.length; i += 4) {
                int level = list[i], x = list[i + 1], z = list[i + 3];
                if (level == 0) {
                    if (!wanted && inWindow(x, z, ccx, ccz, RADIUS + 1)) continue;
                    // the chunk is made again; only this section's claim goes, so only it is sent again
                    AtlasEditsClient.unclaim(net.minecraft.core.SectionPos.asLong(x, list[i + 2], z));
                    done.remove(ChunkPos.pack(x, z));
                } else {
                    farPartial.add(farKey(level, Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
                    AtlasCoarseBake.dropped(level, Math.floorDiv(x, 16), Math.floorDiv(z, 16));  // (the bake's thread puts it again)
                }
            }
        }
    }

    private void pruneDirect(int ccx, int ccz) {
        int far = RADIUS + 2;
        if (storeOn) gen.storeKeep(ccx - far - 3, ccz - far - 3, ccx + far + 3, ccz + far + 3);
        done.removeIf(k -> {
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            if (Math.abs(cx - ccx) <= far && Math.abs(cz - ccz) <= far) return false;
            for (int sec = 0; sec < 40; sec++) AtlasEditsClient.unclaim(net.minecraft.core.SectionPos.asLong(cx, sec - 4, cz));
            return true;
        });
        deepRock.removeIf(k -> Math.abs(net.minecraft.core.SectionPos.x(k) - ccx) > far || Math.abs(net.minecraft.core.SectionPos.z(k) - ccz) > far);
    }

    /** Forgets chunks far outside the window (atlas drops their sections in time; a return generates them again). */
    private void prune(int ccx, int ccz) {
        if (done.size() < 4 * (2 * RADIUS + 1) * (2 * RADIUS + 1)) return;
        int far = RADIUS * 2;
        done.removeIf(k -> Math.abs(ChunkPos.getX(k) - ccx) > far || Math.abs(ChunkPos.getZ(k) - ccz) > far);
        deepRock.removeIf(k -> Math.abs(net.minecraft.core.SectionPos.x(k) - ccx) > far || Math.abs(net.minecraft.core.SectionPos.z(k) - ccz) > far);
    }

    // ---- upload: its own thread, nearest the camera first ----

    private void uploadLoop() {
        short[] out = new short[4096];
        Map<Integer, Short> local = new HashMap<>();
        int[] mat = new int[program.blocks.size()];
        int window = 0;
        for (int dz = -RADIUS; dz <= RADIUS; dz++)
            for (int dx = -RADIUS; dx <= RADIUS; dx++) if (dx * dx + dz * dz <= RADIUS * RADIUS) window++;
        int featureWindow = 0;
        for (int dz = -FEATURE_RADIUS; dz <= FEATURE_RADIUS; dz++)
            for (int dx = -FEATURE_RADIUS; dx <= FEATURE_RADIUS; dx++)
                if (dx * dx + dz * dz <= FEATURE_RADIUS * FEATURE_RADIUS) featureWindow++;
        while (running) {
            try {
                Up d = take(camX, camZ);
                if (d == null) {
                    LockSupport.parkNanos(5_000_000L);
                    continue;
                }
                long t0 = System.nanoTime();
                int n = d.decoded != null ? AtlasEditsClient.put(ctx, mats, d.decoded, true, out, local) : putGen(d, out, mat);
                long t1 = System.nanoTime();
                uploadMs += (t1 - t0) / 1e6;
                uploadedChunks++;
                uploadedSections += n;
                if (jumpNs != 0 && inWindow(d.cx(), d.cz(), jumpCx, jumpCz, RADIUS)) {
                    if (firstUpNs == 0) firstUpNs = t1;
                    upSinceJump.add(ChunkPos.pack(d.cx(), d.cz()));
                    if (windowUpNs == 0 && upSinceJump.size() >= window) windowUpNs = t1;
                }
                if (jumpNs != 0 && emitted.contains(ChunkPos.pack(d.cx(), d.cz()))
                        && inWindow(d.cx(), d.cz(), jumpCx, jumpCz, FEATURE_RADIUS - 1)) {
                    featuredSinceJump.add(ChunkPos.pack(d.cx(), d.cz()));
                    if (windowFeaturesNs == 0 && featuredSinceJump.size() >= featureWindow * 7 / 8) windowFeaturesNs = t1;
                }
            } catch (Throwable t) {
                if (!running) return;
                com.nirncraft.NirnCraft.LOGGER.warn("atlas client generation upload stopped", t);
                return;
            }
        }
    }

    /** A generated chunk (its compact sections) as it goes up: deep solid rock left out, the feed's tints. */
    private Up decode(int cx, int cz, short[][] all) {
        int topSec = -1;
        for (int s = 0; s < 40; s++) if (all[s] != null) topSec = s;
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int top = -65, ground = -65;
                for (int y = topSec * 16 + 15 - 64; y >= -64 && (top == -65 || ground == -65); y--) {
                    short[] sec = all[(y + 64) >> 4];
                    int id = sec == null ? 0 : sec[x + 16 * (z + 16 * (y & 15))] & 0xFFFF;
                    if (top == -65 && !air[id]) top = y;
                    if (ground == -65 && motionOrFluid[id]) ground = y;
                }
                low = Math.min(low, ground);
                high = Math.max(high, top);
            }
        short[][] up = new short[40][];
        for (int s = 0; s < 40; s++) {
            short[] g = all[s];
            if (g == null) continue;
            boolean rock = true;
            for (int b = 0; b < 4096 && rock; b++) rock = solid[g[b] & 0xFFFF];
            if (rock && AtlasSections.deep(s - 4, low)) {  // the tiles' stone
                deepRock.add(net.minecraft.core.SectionPos.asLong(cx, s - 4, cz));
                continue;
            }
            up[s] = g;
        }
        int[] tint = tints(cx, cz, high);
        return new Up(cx, cz, up, null, tint[0], tint[1]);
    }

    /** The client feed's tints: level.getBiome at the chunk's centre and highest top (the seeded zoom). */
    private int[] tints(int cx, int cz, int high) {
        int x = (cx << 4) + 8, z = (cz << 4) + 8;
        Holder<Biome> b = new net.minecraft.world.level.biome.BiomeManager(biomes.get(), zoomSeed)
                .getBiome(new net.minecraft.core.BlockPos(x, high, z));
        return new int[]{b.value().getGrassColor(x, z) & 0xFFFFFF, b.value().getFoliageColor() & 0xFFFFFF};
    }

    /** Chunk i of a batch as the client's feed has a chunk (the parity tool's check): far ids, deep rock left out. */
    AtlasSections.Decoded decode(int cx, int cz, int[] out, int i) {
        return farIds(decode(cx, cz, compact(out, i)));
    }


    // ---- features (stage 4): parallel, never in the terrain's way ----

    static boolean near(int cx, int cz, int ccx, int ccz, int r) {
        return Math.abs(cx - ccx) <= r && Math.abs(cz - ccz) <= r;
    }

    /** Chunk i of a batch as kept for the features: per section 4096 ids, null for air. */
    private static short[][] compact(int[] out, int i) {
        short[][] secs = new short[40][];
        int base = i * AtlasGenNative.CHUNK_WORDS;
        for (int s = 0; s < 40; s++) {
            short[] ids = null;
            for (int k = 0; k < 2048; k++) {
                int w = out[base + s * 2048 + k];
                if (w == 0 && ids == null) continue;
                if (ids == null) ids = new short[4096];
                ids[k * 2] = (short) (w & 0xFFFF);
                ids[k * 2 + 1] = (short) (w >>> 16);
            }
            secs[s] = ids;
        }
        return secs;
    }

    /** The stored chunks as the features' terrain; biomes as ChunkGenerator.createBiomes fills them. */
    private final class StoredTerrain implements AtlasGenFeatures.Terrain {
        // the last chunk this thread read (a fill reads one chunk 164 k times)
        private final ThreadLocal<Object[]> last = ThreadLocal.withInitial(() -> new Object[]{Long.MIN_VALUE, null});

        @Override
        public int id(int x, int y, int z) {
            long k = ChunkPos.pack(x >> 4, z >> 4);
            Object[] l = last.get();
            short[][] c;
            if ((Long) l[0] == k && l[1] != null) c = (short[][]) l[1];
            else {
                c = store.get(k);
                l[0] = k;
                l[1] = c;
            }
            if (c == null) return -1;
            if (y < -64 || y > 575) return 0;
            short[] s = c[(y + 64) >> 4];
            return s == null ? 0 : s[(x & 15) + 16 * ((z & 15) + 16 * (y & 15))] & 0xFFFF;
        }

        @Override
        public int quartBiome(int qx, int qy, int qz) {
            return -1;  // proto chunks carry their biomes (fillBiomes)
        }

        @Override
        public void fillBiomes(net.minecraft.world.level.chunk.ProtoChunk c, AtlasGenProgram program) {
            var climate = random.createClimateSampler(SamplerContext.builder().enableCaches().build());
            ChunkPos p = c.getPos();
            c.fillBiomesFromNoise(generator.getBiomeSource().createResolverForChunk(climate,
                    net.minecraft.core.QuartPos.fromBlock(p.getMinBlockX()), net.minecraft.core.QuartPos.fromBlock(c.getMinY()),
                    net.minecraft.core.QuartPos.fromBlock(p.getMinBlockZ()), 4, net.minecraft.core.QuartPos.fromBlock(c.getHeight()), 4));
        }
    }

    private boolean generated(int cx, int cz) {
        long k = ChunkPos.pack(cx, cz);
        return store.containsKey(k) || window.chunks.containsKey(k);
    }

    /**
     * The nearest chunk to decorate (in view first): not done, its 3 x 3 generated, and its 3 x 3 apart from every
     * decoration under way (three chunks or more: their writes never meet); claimed. -1: none now.
     */
    private long claim(int ccx, int ccz) {
        double yaw = Math.toRadians(camYaw);
        double dirX = -Math.sin(yaw), dirZ = Math.cos(yaw);
        synchronized (decorating) {
            long best = Long.MIN_VALUE;
            double bestD = Double.MAX_VALUE;
            for (int dz = -FEATURE_RADIUS; dz <= FEATURE_RADIUS; dz++)
                for (int dx = -FEATURE_RADIUS; dx <= FEATURE_RADIUS; dx++) {
                    double d = (dx * dx + dz * dz) * (dx * dirX + dz * dirZ < -1.5 ? 4.0 : 1.0);
                    if (d >= bestD || dx * dx + dz * dz > FEATURE_RADIUS * FEATURE_RADIUS) continue;
                    int cx = ccx + dx, cz = ccz + dz;
                    long k = ChunkPos.pack(cx, cz);
                    if (decorated.contains(k) || decorating.contains(k)) continue;
                    boolean apart = true;
                    for (long o : decorating)
                        if (Math.max(Math.abs(ChunkPos.getX(o) - cx), Math.abs(ChunkPos.getZ(o) - cz)) < 3) {
                            apart = false;
                            break;
                        }
                    if (!apart) continue;
                    boolean ready3 = true;
                    for (int j = -1; j <= 1; j++)
                        for (int i = -1; i <= 1; i++)
                            if (!generated(cx + i, cz + j)) {
                                ready3 = false;
                                // generated before but its ids gone (the camera came back): the terrain worker again
                                long nk = ChunkPos.pack(cx + i, cz + j);
                                if (done.remove(nk)) LockSupport.unpark(worker);
                            }
                    if (!ready3) continue;
                    best = k;
                    bestD = d;
                }
            if (best != Long.MIN_VALUE) decorating.add(best);
            return best;
        }
    }

    private void decorateLoop() {
        while (running) {
            try {
                int ccx = camCx, ccz = camCz;
                long k = camSet && !terrainBusy ? claim(ccx, ccz) : Long.MIN_VALUE;
                if (k == Long.MIN_VALUE) {
                    if (camSet) pruneFeatures(ccx, ccz);
                    LockSupport.parkNanos(20_000_000L);
                    continue;
                }
                long t0 = System.nanoTime();
                int bx = ChunkPos.getX(k), bz = ChunkPos.getZ(k);
                try {
                    long dz = AtlasRecorder.begin();
                    features.decorate(bx, bz, window);
                    AtlasRecorder.end(Z_DECORATE, dz);
                } finally {
                    synchronized (decorating) {
                        decorated.add(k);
                        decorating.remove(k);
                    }
                }
                for (int j = -1; j <= 1; j++)
                    for (int i = -1; i <= 1; i++) emitIfDone(bx + i, bz + j);
                synchronized (this) {
                    featureChunks++;
                    featureMs += (System.nanoTime() - t0) / 1e6;
                }
            } catch (Throwable t) {
                if (!running) return;
                com.nirncraft.NirnCraft.LOGGER.warn("atlas client features stopped", t);
                return;
            }
        }
    }

    /** A chunk whose 3 x 3 are decorated goes up again (no decoration under way can write it: they are 3 apart). */
    private void emitIfDone(int cx, int cz) {
        long k = ChunkPos.pack(cx, cz);
        if (emitted.contains(k)) return;
        for (int b = -1; b <= 1; b++)
            for (int a = -1; a <= 1; a++) if (!decorated.contains(ChunkPos.pack(cx + a, cz + b))) return;
        var chunk = window.chunk(cx, cz);
        if (chunk == null || !emitted.add(k)) return;
        AtlasSections.Decoded d = decodeProto(chunk);
        offer(new Up(cx, cz, null, d, d.grass(), d.foliage()), true);
    }

    /** Drops the proto chunks and ids far from the camera (a return decorates them again). */
    private void pruneFeatures(int ccx, int ccz) {
        int far = FEATURE_RADIUS + 4;
        Predicate<Long> gone = k -> Math.abs(ChunkPos.getX(k) - ccx) > far || Math.abs(ChunkPos.getZ(k) - ccz) > far;
        synchronized (decorating) {
            if (!decorating.isEmpty()) return;  // a decoration under way may still read them
            store.keySet().removeIf(gone);
            window.chunks.keySet().removeIf(gone);
            decorated.removeIf(gone);
            emitted.removeIf(gone);
        }
    }

    /** A decorated proto chunk as the feed carries a chunk: far ids, deep solid rock (by content) left out. */
    private AtlasSections.Decoded decodeProto(net.minecraft.world.level.chunk.ProtoChunk c) {
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                low = Math.min(low, c.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
                high = Math.max(high, c.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z));
            }
        var sections = c.getSections();
        int[][] secs = new int[40][];
        java.util.IdentityHashMap<BlockState, Integer> ids = new java.util.IdentityHashMap<>();
        for (int s = 0; s < Math.min(40, sections.length); s++) {
            var sec = sections[s];
            if (sec.hasOnlyAir()) continue;
            int[] far = new int[4096];
            boolean rock = true;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        BlockState st = sec.getBlockState(x, y, z);
                        if (rock && (st.isAir() || !st.isSolidRender())) rock = false;
                        Integer id = ids.get(st);
                        if (id == null) {
                            id = AtlasClientSections.nearIdOf(st);
                            ids.put(st, id);
                        }
                        far[x + 16 * (z + 16 * y)] = id;
                    }
            if (rock && AtlasSections.deep(s - 4, low)) {
                deepRock.add(net.minecraft.core.SectionPos.asLong(c.getPos().x(), s - 4, c.getPos().z()));
                continue;
            }
            secs[s] = far;
        }
        int[] tint = tints(c.getPos().x(), c.getPos().z(), high);
        return new AtlasSections.Decoded(c.getPos().x(), c.getPos().z(), -4, secs, tint[0], tint[1]);
    }

    @Override
    public void close() {
        running = false;
        if (active == this) active = null;
        if (bake != null) bake.stop();
        AtlasServerGen sg = serverGen;
        if (sg != null) {
            com.nirncraft.atlas.server.AtlasServerFeed.unbind(sg);
            sg.close();
        }
        List<Thread> all = new ArrayList<>(featureWorkers);
        all.add(worker);
        all.add(uploader);
        for (Thread t : all) t.interrupt();
        try {
            for (Thread t : all) if (t.isAlive()) t.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (featStages != null) gen.setFeatureOverlays(featStages, null);  // (before the overlays go)
        if (featStages != null) AtlasTileUpload.noDressingCanopy = false;
        for (var detach : DETACH) {
            try {
                detach.accept(gen);
            } catch (RuntimeException e) {
                com.nirncraft.NirnCraft.LOGGER.warn("atlas client generation: a stage engine did not detach", e);
            }
        }
        if (featStages != null) gen.detachFeatures(featStages);  // (its stages off the generator, then its pipelines)
        gen.close();
    }
}
