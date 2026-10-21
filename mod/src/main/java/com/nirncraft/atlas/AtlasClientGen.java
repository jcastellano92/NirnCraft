package com.nirncraft.atlas;

import net.minecraft.client.Minecraft;
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
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * Atlas fills the near field from its own generation (docs/ATLAS-CLIENT-GENERATION.md, stage 5): behind
 * {@code -Dnirncraft.atlas.clientGen=true}, single player, the chunks around the camera are generated on the GPU
 * (atlas_gen: the world's own generator, bit for bit) and go up through the far-field worker's section path
 * (AtlasEditsClient.applySections), nearest and in view first. Vanilla's chunks and the server's sections keep
 * arriving through that same path; a section identical to what went up is skipped there (its hash), so they only
 * correct what differs: the features not generated yet (trees, structures: stage 4), and a player's edits.
 *
 * <p>{@code nirncraft.atlas.clientGen.radius} (chunks, default 24) sets the window. A chunk the kernel could not
 * prove exact is left to the server's blocks. Features (stage 4, AtlasGenFeatures: the world's own decoration over
 * the generated blocks, trees, cover, rocks, ores, snow) follow on a second thread within
 * {@code nirncraft.atlas.clientGen.featureRadius} chunks (default 16; 0: off): a chunk goes up again once its 3 x 3
 * are decorated, only its changed sections re-uploading. {@code nirncraft.atlas.clientGen.ores=false} leaves ores to
 * the server (the public / secret split).</p>
 */
final class AtlasClientGen implements AutoCloseable {
    static final boolean ON = Boolean.getBoolean("nirncraft.atlas.clientGen");
    static final int RADIUS = Integer.getInteger("nirncraft.atlas.clientGen.radius", 24);
    static final int FEATURE_RADIUS = Integer.getInteger("nirncraft.atlas.clientGen.featureRadius", 16);
    static final boolean ORES = !"false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.clientGen.ores"));
    private static final int BATCH = 64, READY_MAX = 384;
    private static volatile AtlasClientGen active;

    private final AtlasGenProgram program;
    private final AtlasGenPages pages;
    private final AtlasGenNative.Gen gen;
    private final int[] farIds;          // per generator id: its near id (AtlasClientSections.nearIdOf; 0: not kept)
    private final boolean[] solid;       // per generator id: renders as a full cube (AtlasSections.solidRock)
    private final boolean[] motionOrFluid, air;
    private final ThreadLocal<BiomeResolver> biomes;
    private final long zoomSeed;
    private final Set<Long> done = ConcurrentHashMap.newKeySet();
    private final Set<Long> deepRock = ConcurrentHashMap.newKeySet();  // sections left out as deep solid rock
    private final List<AtlasSections.Decoded> ready = new ArrayList<>();
    private final Thread worker, featureWorker;
    // features: the generated ids of chunks near the camera (null section: air), the proto chunks over them, progress
    private final AtlasGenFeatures features;
    private final AtlasGenFeatures.Window window;
    private final Map<Long, short[][]> store = new ConcurrentHashMap<>();
    private final Set<Long> decorated = ConcurrentHashMap.newKeySet(), emitted = ConcurrentHashMap.newKeySet();
    private final NoiseBasedChunkGenerator generator;
    private final RandomState random;
    private volatile long featureChunks, featureMs;
    private volatile boolean running = true;
    // stats (atlas-info)
    private volatile long chunks, flagged, pagesMade;
    private volatile double genMs, pageMs;

    private AtlasClientGen(AtlasGenProgram program, AtlasGenPages pages, AtlasGenNative.Gen gen, NoiseBasedChunkGenerator g,
                           RandomState random, long seed) {
        this(program, pages, gen, g, random, seed, null);
    }

    private AtlasClientGen(AtlasGenProgram program, AtlasGenPages pages, AtlasGenNative.Gen gen, NoiseBasedChunkGenerator g,
                           RandomState random, long seed, AtlasGenFeatures features) {
        this.generator = g;
        this.random = random;
        this.features = features;
        this.program = program;
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
        worker = new Thread(this::run, "atlas client generation");
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        window = features == null ? null : features.new Window(new StoredTerrain());
        featureWorker = new Thread(this::decorateLoop, "atlas client features");
        featureWorker.setDaemon(true);
        featureWorker.setPriority(Thread.MIN_PRIORITY + 1);
    }

    /** A generator without its worker (the parity tool decodes with it). */
    static AtlasClientGen detached(AtlasGenProgram prog, AtlasGenPages pages, AtlasGenNative.Gen gen,
                                   NoiseBasedChunkGenerator g, RandomState rs, long seed) {
        return new AtlasClientGen(prog, pages, gen, g, rs, seed);
    }

    /** Starts generation for the integrated server's overworld, or returns null (logged) when it cannot. */
    static AtlasClientGen start(MinecraftServer server, Path library) {
        if (!ON || server == null || library == null) return null;
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
            AtlasGenNative.Gen gen = nat.create(AtlasGenNative.BACKEND_VULKAN);
            if (gen == null) gen = nat.create(AtlasGenNative.BACKEND_CPU);
            if (gen == null) return null;
            gen.setProgram(prog.words);
            AtlasGenFeatures feats = null;
            if (FEATURE_RADIUS > 0) {
                try {
                    feats = new AtlasGenFeatures(g, ow, server.registryAccess(), ow, ow.getSeed(), prog, ORES);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    com.nirncraft.NirnCraft.LOGGER.info("atlas client features off: {}", e.toString());
                }
            }
            AtlasClientGen c = new AtlasClientGen(prog, new AtlasGenPages(prog, rs, g.getBiomeSource(), ow), gen, g, rs, ow.getSeed(), feats);
            com.nirncraft.NirnCraft.LOGGER.info("atlas client generation on ({}): program {} words, {} fields, radius {} chunks, {} ms",
                    gen.device(), prog.words.length, prog.fields.size(), RADIUS, (System.nanoTime() - t0) / 1_000_000);
            active = c;
            c.worker.start();
            if (feats != null) c.featureWorker.start();
            return c;
        } catch (Throwable t) {
            com.nirncraft.NirnCraft.LOGGER.warn("atlas client generation unavailable", t);
            return null;
        }
    }

    /** The generated chunk nearest block (x, z), or null (far-field worker). */
    static AtlasSections.Decoded poll(double x, double z) {
        AtlasClientGen c = active;
        if (c == null) return null;
        synchronized (c.ready) {
            // a chunk whose loaded blocks are up already keeps them (they hold its trees and edits)
            c.ready.removeIf(d -> AtlasClientSections.isReal(d.cx(), d.cz()));
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < c.ready.size(); i++) {
                AtlasSections.Decoded d = c.ready.get(i);
                double dx = d.cx() * 16 + 8 - x, dz = d.cz() * 16 + 8 - z, dd = dx * dx + dz * dz;
                if (dd < bestD) {
                    bestD = dd;
                    best = i;
                }
            }
            if (best < 0) return null;
            AtlasSections.Decoded d = c.ready.get(best);
            c.ready.set(best, c.ready.get(c.ready.size() - 1));
            c.ready.remove(c.ready.size() - 1);
            return d;
        }
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
        return String.format("clientGen{%s chunks=%d flagged=%d ready=%d pages=%d genMs=%.0f pageMs=%.0f features=%d featureMs=%.0f failures=%d}",
                c.gen.device(), c.chunks, c.flagged, r, c.pagesMade, (double) c.genMs, (double) c.pageMs, c.featureChunks,
                (double) c.featureMs,
                c.features == null ? 0 : c.features.failures);
    }

    // ---- the worker ----

    private void run() {
        int[] ids = new int[BATCH * AtlasGenNative.CHUNK_WORDS];
        int[] flags = new int[BATCH];
        while (running) {
            try {
                int waiting;
                synchronized (ready) {
                    waiting = ready.size();
                }
                double vx = AtlasEditsClient.viewX, vz = AtlasEditsClient.viewZ;
                int[] batch = waiting < READY_MAX ? next(vx, vz) : null;
                if (batch == null) {
                    Thread.sleep(waiting < READY_MAX ? 50 : 5);
                    continue;
                }
                long t0 = System.nanoTime();
                int[] miss = gen.missing(batch);
                int[][] made = new int[miss.length / 3][];
                IntStream.range(0, made.length).parallel()
                        .forEach(i -> made[i] = pages.page(miss[i * 3], miss[i * 3 + 1], miss[i * 3 + 2] != 0));
                for (int i = 0; i < made.length; i++) gen.putPage(miss[i * 3], miss[i * 3 + 1], miss[i * 3 + 2] != 0, made[i]);
                long t1 = System.nanoTime();
                int n = batch.length / 2;
                int[] out = n == BATCH ? ids : new int[n * AtlasGenNative.CHUNK_WORDS];
                gen.chunks(batch, 0, out, flags);
                List<AtlasSections.Decoded> decoded = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    done.add(ChunkPos.pack(batch[i * 2], batch[i * 2 + 1]));
                    if (flags[i] != 0) {
                        flagged++;
                        continue;  // not proven exact here: the server's blocks
                    }
                    decoded.add(decode(batch[i * 2], batch[i * 2 + 1], out, i));
                    if (features != null && near(batch[i * 2], batch[i * 2 + 1], vx, vz, FEATURE_RADIUS + 2))
                        store.put(ChunkPos.pack(batch[i * 2], batch[i * 2 + 1]), compact(out, i));
                }
                synchronized (ready) {
                    ready.addAll(decoded);
                }
                chunks += n;
                pagesMade += made.length;
                pageMs += (t1 - t0) / 1e6;
                genMs += (System.nanoTime() - t1) / 1e6;
                prune(vx, vz);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                com.nirncraft.NirnCraft.LOGGER.warn("atlas client generation stopped", t);
                running = false;
            }
        }
    }

    /** The next chunks to generate: within the window, not done, nearest first and in view before behind. */
    private int[] next(double vx, double vz) {
        int ccx = (int) Math.floor(vx) >> 4, ccz = (int) Math.floor(vz) >> 4;
        double dirX = 0, dirZ = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            double yaw = Math.toRadians(mc.player.getYRot());
            dirX = -Math.sin(yaw);
            dirZ = Math.cos(yaw);
        }
        List<long[]> cand = new ArrayList<>();
        int r = RADIUS;
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz > r * r) continue;
                long k = ChunkPos.pack(ccx + dx, ccz + dz);
                if (done.contains(k)) continue;
                double d2 = dx * dx + dz * dz;
                boolean behind = dx * dirX + dz * dirZ < -1.5;
                cand.add(new long[]{k, (long) (d2 * (behind ? 4 : 1) * 16)});
            }
        if (cand.isEmpty()) return null;
        cand.sort(java.util.Comparator.comparingLong(c -> c[1]));
        int n = Math.min(BATCH, cand.size());
        int[] xz = new int[n * 2];
        for (int i = 0; i < n; i++) {
            xz[i * 2] = ChunkPos.getX(cand.get(i)[0]);
            xz[i * 2 + 1] = ChunkPos.getZ(cand.get(i)[0]);
        }
        return xz;
    }

    /** Forgets chunks far outside the window (atlas drops their sections in time; a return generates them again). */
    private void prune(double vx, double vz) {
        if (done.size() < 4 * (2 * RADIUS + 1) * (2 * RADIUS + 1)) return;
        int ccx = (int) Math.floor(vx) >> 4, ccz = (int) Math.floor(vz) >> 4, far = RADIUS * 2;
        done.removeIf(k -> Math.abs(ChunkPos.getX(k) - ccx) > far || Math.abs(ChunkPos.getZ(k) - ccz) > far);
        deepRock.removeIf(k -> Math.abs(net.minecraft.core.SectionPos.x(k) - ccx) > far || Math.abs(net.minecraft.core.SectionPos.z(k) - ccz) > far);
    }

    /** Chunk i of a batch as the client's feed has a chunk (AtlasClientSections.read): far ids, deep rock left out. */
    AtlasSections.Decoded decode(int cx, int cz, int[] out, int i) {
        int base = i * AtlasGenNative.CHUNK_WORDS;
        int[] low = {Integer.MAX_VALUE}, high = {Integer.MIN_VALUE};
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int top = -65, ground = -65;
                for (int y = 639; y >= -64 && (top == -65 || ground == -65); y--) {
                    int id = y > 575 ? 0 : id(out, base, x, y + 64, z);
                    if (top == -65 && !air[id]) top = y;
                    if (ground == -65 && motionOrFluid[id]) ground = y;
                }
                low[0] = Math.min(low[0], ground);
                high[0] = Math.max(high[0], top);
            }
        int[][] secs = new int[40][];
        for (int s = 0; s < 40; s++) {
            int sy = s - 4;
            boolean anyBlock = false, rock = true;
            int[] far = new int[4096];
            for (int b = 0; b < 4096; b++) {
                int k = base + s * 2048 + (b >> 1);
                int id = (b & 1) != 0 ? out[k] >>> 16 : out[k] & 0xFFFF;
                if (!air[id]) anyBlock = true;
                if (!solid[id]) rock = false;
                far[b] = farIds[id];
            }
            if (!anyBlock) continue;
            if (rock && AtlasSections.deep(sy, low[0])) {  // the tiles' stone
                deepRock.add(net.minecraft.core.SectionPos.asLong(cx, sy, cz));
                continue;
            }
            secs[s] = far;
        }
        int x = (cx << 4) + 8, z = (cz << 4) + 8;
        // the client feed's tint: level.getBiome at the chunk's centre and highest top (the seeded zoom)
        Holder<Biome> b = new net.minecraft.world.level.biome.BiomeManager(biomes.get(), zoomSeed)
                .getBiome(new net.minecraft.core.BlockPos(x, high[0], z));
        return new AtlasSections.Decoded(cx, cz, -4, secs, b.value().getGrassColor(x, z) & 0xFFFFFF, b.value().getFoliageColor() & 0xFFFFFF);
    }

    private static int id(int[] out, int base, int x, int ly, int z) {
        int b = x + 16 * (z + 16 * ly);
        int w = out[base + (b >> 1)];
        return (b & 1) != 0 ? w >>> 16 : w & 0xFFFF;
    }

    // ---- features (stage 4) ----

    static boolean near(int cx, int cz, double vx, double vz, int r) {
        int ccx = (int) Math.floor(vx) >> 4, ccz = (int) Math.floor(vz) >> 4;
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
        @Override
        public int id(int x, int y, int z) {
            short[][] c = store.get(ChunkPos.pack(x >> 4, z >> 4));
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

    /** The features' thread: decorate the nearest chunk whose 3 x 3 are generated; emit chunks whose 3 x 3 are done. */
    private void decorateLoop() {
        while (running) {
            try {
                double vx = AtlasEditsClient.viewX, vz = AtlasEditsClient.viewZ;
                int ccx = (int) Math.floor(vx) >> 4, ccz = (int) Math.floor(vz) >> 4;
                long best = Long.MIN_VALUE;
                int bestD = Integer.MAX_VALUE;
                for (int dz = -FEATURE_RADIUS; dz <= FEATURE_RADIUS; dz++)
                    for (int dx = -FEATURE_RADIUS; dx <= FEATURE_RADIUS; dx++) {
                        int d = dx * dx + dz * dz;
                        if (d >= bestD) continue;
                        int cx = ccx + dx, cz = ccz + dz;
                        if (decorated.contains(ChunkPos.pack(cx, cz))) continue;
                        boolean ready3 = true;
                        for (int j = -1; j <= 1 && ready3; j++)
                            for (int i = -1; i <= 1 && ready3; i++) ready3 = generated(cx + i, cz + j);
                        if (!ready3) continue;
                        best = ChunkPos.pack(cx, cz);
                        bestD = d;
                    }
                if (best == Long.MIN_VALUE) {
                    pruneFeatures(ccx, ccz);
                    Thread.sleep(50);
                    continue;
                }
                long t0 = System.nanoTime();
                int bx = ChunkPos.getX(best), bz = ChunkPos.getZ(best);
                features.decorate(bx, bz, window);
                decorated.add(best);
                for (int j = -1; j <= 1; j++)
                    for (int i = -1; i <= 1; i++) {
                        int cx = bx + i, cz = bz + j;
                        long k = ChunkPos.pack(cx, cz);
                        if (emitted.contains(k)) continue;
                        boolean all = true;
                        for (int b = -1; b <= 1 && all; b++)
                            for (int a = -1; a <= 1 && all; a++) all = decorated.contains(ChunkPos.pack(cx + a, cz + b));
                        if (!all) continue;
                        var chunk = window.chunk(cx, cz);
                        if (chunk == null) continue;
                        emitted.add(k);
                        AtlasSections.Decoded d = decodeProto(chunk);
                        synchronized (ready) {
                            ready.removeIf(o -> o.cx() == d.cx() && o.cz() == d.cz());  // its terrain alone, not up yet
                            ready.add(d);
                        }
                    }
                featureChunks++;
                featureMs += (System.nanoTime() - t0) / 1e6;
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                com.nirncraft.NirnCraft.LOGGER.warn("atlas client features stopped", t);
                return;
            }
        }
    }

    /** Drops the proto chunks and ids far from the camera (a return decorates them again). */
    private void pruneFeatures(int ccx, int ccz) {
        int far = FEATURE_RADIUS + 4;
        Predicate<Long> gone = k -> Math.abs(ChunkPos.getX(k) - ccx) > far || Math.abs(ChunkPos.getZ(k) - ccz) > far;
        store.keySet().removeIf(gone);
        window.chunks.keySet().removeIf(gone);
        decorated.removeIf(gone);
        emitted.removeIf(gone);
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
        int x = c.getPos().getMinBlockX() + 8, z = c.getPos().getMinBlockZ() + 8;
        Holder<Biome> b = new net.minecraft.world.level.biome.BiomeManager(biomes.get(), zoomSeed)
                .getBiome(new net.minecraft.core.BlockPos(x, high, z));
        return new AtlasSections.Decoded(c.getPos().x(), c.getPos().z(), -4, secs, b.value().getGrassColor(x, z) & 0xFFFFFF,
                b.value().getFoliageColor() & 0xFFFFFF);
    }

    @Override
    public void close() {
        running = false;
        if (active == this) active = null;
        worker.interrupt();
        featureWorker.interrupt();
        try {
            worker.join(5000);
            featureWorker.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        gen.close();
    }
}
