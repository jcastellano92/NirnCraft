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
    static final boolean ON = Boolean.getBoolean("nirncraft.atlas.clientGen");
    static final int RADIUS = Integer.getInteger("nirncraft.atlas.clientGen.radius", 24);
    static final int FEATURE_RADIUS = Integer.getInteger("nirncraft.atlas.clientGen.featureRadius", 16);
    static final int FEATURE_THREADS = Integer.getInteger("nirncraft.atlas.clientGen.featureThreads",
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4)));
    static final boolean ORES = !"false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.clientGen.ores"));
    /** {@code .direct=false}: the generator's sections come back to the CPU and go up as uploads (the old path). */
    static final boolean DIRECT = !"false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.clientGen.direct"));
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
    private final int[] farIds;          // per generator id: its far state id (0: not kept, like AtlasSections.ids)
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
    /** The generator makes its own pages on the device (atlas_gen_set_map took the map). */
    private volatile boolean devicePages;
    private volatile boolean terrainBusy = true;  // the window has chunks to generate: features wait (terrain first)
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
            farIds[i] = AtlasSections.kept(s) ? AtlasFarState.farId(s) : 0;
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
    static String devicePages(AtlasGenNative.Gen gen, AtlasGenProgram prog, net.minecraft.world.level.biome.BiomeSource biomes) {
        if (!gen.devicePagesSupported()) return "in Java (the library makes no pages)";
        if (!prog.pageGen) return "in Java (the program lacks the page passes' inputs)";
        try {
            long t0 = System.nanoTime();
            int[] map = AtlasGenMap.words(prog, biomes);
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
            gen.setProgram(prog.words);
            if (direct) gen.setIngest(ingestTable(prog, mats, ctx));
            AtlasGenFeatures feats = null;
            if (FEATURE_RADIUS > 0) {
                try {
                    feats = new AtlasGenFeatures(g, ow, server.registryAccess(), ow, ow.getSeed(), prog, ORES);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    com.nirncraft.NirnCraft.LOGGER.info("atlas client features off: {}", e.toString());
                }
            }
            AtlasClientGen c = new AtlasClientGen(prog, new AtlasGenPages(prog, rs, g.getBiomeSource(), ow), gen, g, rs,
                    ow.getSeed(), feats, ctx, mats, direct);
            com.nirncraft.NirnCraft.LOGGER.info("atlas client generation on ({}): program {} words, {} fields, pages from the worker, radius {} chunks, "
                            + "features {} chunks on {} threads, {} ms", gen.device(), prog.words.length, prog.fields.size(),
                    RADIUS, feats == null ? 0 : FEATURE_RADIUS, feats == null ? 0 : FEATURE_THREADS, (System.nanoTime() - t0) / 1_000_000);
            active = c;
            c.worker.start();
            if (ctx != null && mats != null) c.uploader.start();
            for (Thread t : c.featureWorkers) t.start();
            return c;
        } catch (Throwable t) {
            com.nirncraft.NirnCraft.LOGGER.warn("atlas client generation unavailable", t);
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
            far[i] = AtlasSections.kept(s) ? AtlasFarState.farId(s) : 0;
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
                w[matBase + b * n + i] = far[i] == 0 ? 0 : mats.section(st, AtlasMaterials.tint(st, grass, foliage)) & 0xFFFF;
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

    private void wake() {
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

    static String state() {
        AtlasClientGen c = active;
        if (c == null) return "clientGen{off}";
        int r;
        synchronized (c.ready) {
            r = c.ready.size();
        }
        long now = System.nanoTime(), j = c.jumpNs;
        double[] dp = c.devicePages ? c.gen.pageStats() : null;
        String pagesNow = dp == null ? "java" : String.format("device %d in %.0f ms", (long) dp[0], dp[1]);
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
        } catch (RuntimeException e) {
            com.nirncraft.NirnCraft.LOGGER.warn("atlas client generation: pages stay in Java", e);
        }
        int[] ids = new int[BATCH * AtlasGenNative.CHUNK_WORDS];
        int[] flags = new int[BATCH];
        int[] info = new int[BATCH * AtlasGenNative.INFO_WORDS];
        long lastJump = 0;
        Planned cur = null;
        while (running) {
            try {
                int waiting;
                synchronized (ready) {
                    waiting = ready.size();
                }
                // (direct: terrain goes device to device, the ready queue holds only decorated chunks: never a reason to wait)
                boolean full = !direct && waiting >= READY_MAX;
                if (!camSet || full) {
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
                if (cur == null) cur = plan(camCx, camCz, fresh ? FIRST_BATCH : BATCH);
                terrainBusy = cur != null;
                if (cur == null) {
                    if (direct) pruneDirect(camCx, camCz);
                    else prune(camCx, camCz);
                    for (Thread t : featureWorkers) LockSupport.unpark(t);
                    LockSupport.parkNanos(20_000_000L);
                    continue;
                }
                long t0 = System.nanoTime();
                int[][] made = cur.pages.join();
                int[] miss = made[0];
                for (int i = 0; i < miss.length / 3; i++) gen.putPage(miss[i * 3], miss[i * 3 + 1], miss[i * 3 + 2] != 0, made[i + 1]);
                long t1 = System.nanoTime();
                int[] batch = cur.batch;
                // the next batch's pages while this one is on the GPU
                Planned next = plan(camCx, camCz, BATCH);
                int n = batch.length / 2;
                if (direct) {
                    if (fresh) firstBatchPagesNs = t1;
                    directBatch(batch, ids, flags, info, fresh);
                    pruneDirect(camCx, camCz);
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
        int n = batch.length / 2;
        boolean wantIds = false;
        if (features != null)
            for (int i = 0; i < n && !wantIds; i++) wantIds = near(batch[i * 2], batch[i * 2 + 1], camCx, camCz, FEATURE_RADIUS + 2);
        long tg = System.nanoTime();
        AtlasGenNative.Times gt = gen.run(batch, wantIds ? ids : null, flags, info);
        long td = System.nanoTime();
        gpuMs += (td - tg) / 1e6;
        kernelMs += gt.kernelMs();
        readbackMs += gt.readbackMs();
        long[] masks = new long[n];
        int ccx = camCx, ccz = camCz;
        for (int i = 0; i < n; i++) {
            int cx = batch[i * 2], cz = batch[i * 2 + 1];
            if (flags[i] != 0) {
                flagged++;  // not proven exact here: the server's blocks
                continue;
            }
            if (!inWindow(cx, cz, ccx, ccz, RADIUS + 1)) {
                done.remove(ChunkPos.pack(cx, cz));  // the camera left while it ran: generated again if it comes back
                continue;
            }
            if (wantIds && near(cx, cz, ccx, ccz, FEATURE_RADIUS + 2)) store.put(ChunkPos.pack(cx, cz), compact(ids, i));
            if (AtlasClientSections.isReal(cx, cz)) continue;  // the loaded chunk's own blocks are up: they stay
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
        long tu = System.nanoTime();
        long[] asked = masks.clone();
        int sent = gen.ingest(masks);
        long t1 = System.nanoTime();
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
    private void pruneDirect(int ccx, int ccz) {
        int far = RADIUS + 2;
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
                    features.decorate(bx, bz, window);
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
                            id = AtlasSections.kept(st) ? AtlasFarState.farId(st) : 0;
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
        List<Thread> all = new ArrayList<>(featureWorkers);
        all.add(worker);
        all.add(uploader);
        for (Thread t : all) t.interrupt();
        try {
            for (Thread t : all) if (t.isAlive()) t.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        gen.close();
    }
}
