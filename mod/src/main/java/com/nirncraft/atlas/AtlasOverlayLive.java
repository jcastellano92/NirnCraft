package com.nirncraft.atlas;

import com.nirncraft.tamriel.settlement.SettlementPiece;
import com.nirncraft.worldplan.street.StreetPlan;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The structures from the plans in the client's own generation (single player): the overlays engine as stages of the
 * client generator's batches (atlas_ovl_create_for_gen: the pieces, their sweep and the street painter on its device,
 * GPU to GPU, in the batch before the pack pass), fed the settlements' tapes ahead of the camera.
 *
 * <p>A settlement is recorded once per world (AtlasOverlayTapes.recordSettlement: the server's postProcess over its
 * chunks, in the server's order) over terrain the GPU generator makes for it on a recording generator of its own (no
 * stages: the terrain alone), and cached on disk keyed by the world seed, the generator program and the plan
 * ({@code <game dir>/nirncraft-cache/overlays}); later visits load it. Chunks generated before their settlement's tapes
 * arrived are generated again (AtlasClientGen.regenerate). The block states the stages place are fixed with the
 * generator's program (AtlasGenProgram.addBlocks: the baked list {@link #STATES}, with its transforms and shapes).</p>
 */
final class AtlasOverlayLive {
    /** The block states the settlements' builders place (a dev bake: AtlasOverlaysGpuTest.bakeStates). */
    static final String STATES = "/nirncraft/atlas/overlay_states.txt";
    /** Bumped when the tapes' meaning changes (the cache's key). */
    static final int TAPE_VERSION = 4;  // 2: the pieces' boxes per chunk; 3: the layers (the dungeons' recorded writes); 4: block entities and mobs (the side files)
    /** Settlements within the client generator's window plus this many blocks are prepared. */
    static final int AHEAD = Integer.getInteger("nirncraft.atlas.overlays.ahead", 384);
    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.overlays"));

    private static volatile List<BlockState> states;
    private static volatile Path library;
    private static volatile MinecraftServer server;
    private static final AtlasOwnedSlot<Live> live = new AtlasOwnedSlot<>();
    private static final AtlasStageOwners<AtlasGenNative.Gen, AtlasOvlNative.Ovl, Live> prepared = new AtlasStageOwners<>();
    private static boolean registered;

    private AtlasOverlayLive() {}

    /** Before the client generator starts (its program compiles with our blocks): the library and the world. */
    static synchronized void register(Path lib, MinecraftServer srv) {
        if (!ON) return;
        library = lib;
        server = srv;
        if (registered) return;
        registered = true;
        AtlasGenProgram.addBlocks(AtlasOverlayLive::states);
        AtlasClientGen.ATTACH.add(AtlasOverlayLive::attach);
        AtlasClientGen.DETACH.add(AtlasOverlayLive::detach);
    }

    /** The states the stages may place: the bake's, closed under the kernels' transforms and shapes. */
    static List<BlockState> states() {
        List<BlockState> s = states;
        if (s != null) return s;
        synchronized (AtlasOverlayLive.class) {
            if (states != null) return states;
            Set<BlockState> placed = new LinkedHashSet<>();
            try (InputStream in = AtlasOverlayLive.class.getResourceAsStream(STATES)) {
                if (in != null) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    for (String line; (line = r.readLine()) != null; ) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        try {
                            placed.add(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, line, false).blockState());
                        } catch (Exception e) {
                            // a block this build no longer has
                        }
                    }
                }
            } catch (java.io.IOException e) {
                com.nirncraft.NirnCraft.LOGGER.warn("atlas overlays: the baked block states are unreadable", e);
            }
            placed.addAll(com.nirncraft.roads.RoadPaintGpu.states());  // the road painter's materials
            states = AtlasOverlayTapes.closure(placed);
            return states;
        }
    }

    private static void attach(AtlasGenNative.Gen gen, AtlasGenProgram prog) {
        Path lib = library;
        MinecraftServer srv = server;
        if (lib == null || srv == null) return;
        AtlasOvlNative nat = AtlasOvlNative.load(lib);
        AtlasOvlNative.Ovl ovl = nat == null ? null : nat.createForGen(gen);
        if (ovl == null) {
            com.nirncraft.NirnCraft.LOGGER.info("atlas overlays: off (the core or the generator's device cannot run them)");
            return;
        }
        prepared.prepare(gen, ovl, resource -> new Live(gen, prog, resource, srv, lib));
        com.nirncraft.NirnCraft.LOGGER.info("atlas overlays: stages on {}, {} block states", ovl.device(), prog.blocks.size());
    }

    /** Called only by the generation-checked publication transaction. */
    static void publish(AtlasGenNative.Gen gen) {
        Live l = prepared.get(gen);
        if (l == null) return;
        live.publish(l, l.thread::start);
    }

    private static void detach(AtlasGenNative.Gen gen) {
        prepared.release(gen, l -> {
            live.clear(l);
            l.readers.closeAdmission();
            boolean interrupted = l.readers.awaitDrained();
            if (interrupted) Thread.currentThread().interrupt();
            l.close();
        }, AtlasOvlNative.Ovl::close);
    }

    /** Whether the overlays have looked for the seeded starts near the camera (a chunk is final only after, finalAt). */
    static boolean scanned() {
        Live l = live.get();
        return l == null || l.scanned;
    }

    /**
     * Whether chunk (cx, cz) comes out of the store with every stage's data: no settlement or seeded start whose tapes
     * are still being recorded reaches it (the self-baking cache's no-bake boxes), and the starts near the camera have been
     * found. The integrated server keeps what it is given for good, so it waits for this.
     */
    static boolean finalAt(int cx, int cz) {
        Live l = live.get();
        if (l == null) return true;  // (the overlays are off: no tapes to wait for)
        if (!l.scanned) return false;
        int[] b = l.noBakeSent;
        if (b != null)
            for (int i = 0; i + 3 < b.length; i += 4)
                if (cx >= b[i] && cz >= b[i + 1] && cx <= b[i + 2] && cz <= b[i + 3]) return false;
        return true;
    }

    /** The overlays' handle on this generator (the decoration's mask reads their piece boxes), or null. */
    static java.lang.foreign.MemorySegment handleFor(AtlasGenNative.Gen gen) {
        Live l = prepared.get(gen);
        return l == null ? null : l.ovl.handle();
    }

    /** "overlays: settlements ready / recording, chunks stamped" (atlas-info). */
    static String state() {
        Live l = live.get();
        if (l == null) return "overlays off";
        var lease = l.readers.enter();
        if (lease == null) return "overlays off";
        try (lease) { return l.state(); }
    }

    private static final class Live {
        final AtlasGenNative.Gen gen;
        final AtlasReleaseGate readers = new AtlasReleaseGate();
        final AtlasGenProgram prog;
        final AtlasOvlNative.Ovl ovl;
        final MinecraftServer server;
        final Path library;
        final AtlasOverlayTapes tapes;
        final Thread thread;
        final Set<String> ready = new HashSet<>();
        final Path cache;
        volatile boolean running = true;
        volatile String doing = "";
        long recorded, loaded, recordMs;
        AtlasGenNative.Gen rec;  // the recording generator (the terrain alone), made when first needed
        AtlasOverlayStarts starts;  // the seeded settlements (generic, working sites) and the dungeons, found as the server finds them
        List<AtlasOverlayStarts.Start> near = List.of();
        double nearX = Double.NaN, nearZ = Double.NaN;

        Live(AtlasGenNative.Gen gen, AtlasGenProgram prog, AtlasOvlNative.Ovl ovl, MinecraftServer server, Path library) {
            this.gen = gen;
            this.prog = prog;
            this.ovl = ovl;
            this.server = server;
            this.library = library;
            tapes = new AtlasOverlayTapes(prog.blocks);
            tapes.frozen = true;
            ServerLevel ow = server.overworld();
            StreetPlan plan = StreetPlan.get();
            ovl.set(AtlasOvlNative.SET_BLOCKS, tapes.blockWords());
            ovl.set(AtlasOvlNative.SET_STREETS, AtlasOverlayTapes.streetWords(plan, ow, 0));
            ovl.set(AtlasOvlNative.SET_ROADS, com.nirncraft.roads.RoadPaintGpu.words(com.nirncraft.worldplan.RoadPlan.get(), s -> {
                int i = tapes.idIfPresent(s);
                return i < 0 ? 0 : i;
            }));
            String key = Long.toHexString(ow.getSeed()) + "-" + Integer.toHexString(java.util.Arrays.hashCode(prog.words)) + "-"
                    + Integer.toHexString(plan.settlements() * 31 + plan.nodes() * 17 + plan.edges()) + "-v" + TAPE_VERSION;
            cache = server.getServerDirectory().resolve("nirncraft-cache").resolve("overlays").resolve(key);
            noBake(plan);  // (before the generator's first batch: no settlement is ready yet)
            thread = new Thread(this::run, "nirncraft-atlas-overlays");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
        }

        /**
         * The self-baking cache keeps nothing of a place whose tapes are not ready (the plan's settlements, the seeded
         * starts found so far): a chunk made there before them is made again (regenerate) and only that one is kept.
         * Else the copy made without them was baked, and every relaunch served it, then made the settlement again.
         */
        void noBake(StreetPlan plan) {
            List<Integer> b = new ArrayList<>();
            for (int s = 0; s < plan.settlements(); s++) {
                if (ready.contains(plan.sId[s])) continue;
                int r = plan.sReach[s] + 32;  // (the decoration's reach past the settlement's: two chunks)
                b.add(Math.floorDiv(plan.sX[s] - r, 16));
                b.add(Math.floorDiv(plan.sZ[s] - r, 16));
                b.add(Math.floorDiv(plan.sX[s] + r, 16));
                b.add(Math.floorDiv(plan.sZ[s] + r, 16));
            }
            for (AtlasOverlayStarts.Start st : near) {
                if (ready.contains(st.key())) continue;
                BoundingBox x = st.box();
                b.add(Math.floorDiv(x.minX() - 32, 16));
                b.add(Math.floorDiv(x.minZ() - 32, 16));
                b.add(Math.floorDiv(x.maxX() + 32, 16));
                b.add(Math.floorDiv(x.maxZ() + 32, 16));
            }
            int[] boxes = b.stream().mapToInt(Integer::intValue).toArray();
            if (java.util.Arrays.equals(boxes, noBakeSent)) return;
            noBakeSent = boxes;
            gen.setNoBake(boxes);
        }

        volatile int[] noBakeSent;
        volatile boolean scanned;

        String state() {
            return String.format("overlays: %d settlements and seeded starts (%d recorded in %.1f s, %d from the cache), %d chunks stamped, %d states missing%s",
                    ready.size(), recorded, recordMs / 1000.0, loaded, ovl.stamped(), tapes.missing, doing.isEmpty() ? "" : ", " + doing);
        }

        void close() {
            running = false;
            thread.interrupt();
            // The overlay owns native stages on the generator.  A timed join can leave the worker using
            // those stages after ovl.close(), so release must wait until the worker has actually exited.
            boolean interrupted = false;
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            try {
                ovl.close();  // its stages leave the generator before it closes
                if (rec != null) rec.close();
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        void run() {
            StreetPlan plan = StreetPlan.get();
            while (running) {
                try {
                    if (!AtlasClientGen.hasCamera()) {
                        LockSupport.parkNanos(200_000_000L);
                        continue;
                    }
                    double x = AtlasClientGen.cameraX(), z = AtlasClientGen.cameraZ();
                    int reach = AtlasClientGen.RADIUS * 16 + AHEAD;
                    boolean nearUpdated = updateSeededNear(x, z, reach);
                    // the nearest settlement or seeded start not ready yet
                    int best = -1;
                    double bestD = Double.MAX_VALUE;
                    for (int s = 0; s < plan.settlements(); s++) {
                        if (ready.contains(plan.sId[s])) continue;
                        double d = Math.max(Math.abs(plan.sX[s] - x), Math.abs(plan.sZ[s] - z)) - plan.sReach[s];
                        if (d <= reach && d < bestD) {
                            bestD = d;
                            best = s;
                        }
                    }
                    AtlasOverlayStarts.Start seeded = null;
                    for (AtlasOverlayStarts.Start st : near) {
                        if (ready.contains(st.key())) continue;
                        BoundingBox b = st.box();
                        double d = Math.max(Math.max(b.minX() - x, x - b.maxX()), Math.max(b.minZ() - z, z - b.maxZ()));
                        if (d <= reach && d < bestD) {
                            bestD = d;
                            seeded = st;
                        }
                    }
                    if (nearUpdated || !scanned) {
                        noBake(plan);  // (the seeded starts found near the camera)
                        scanned = true;
                    }
                    if (best < 0 && seeded == null) {
                        LockSupport.parkNanos(500_000_000L);
                        continue;
                    }
                    String id = seeded != null ? seeded.key() : plan.sId[best];
                    doing = "preparing " + id;
                    long[] keys = load(id);
                    if (keys != null) {
                        ready.add(id);
                        Set<Long> batchKeys = new LinkedHashSet<>();
                        for (long k : keys) batchKeys.add(k);
                        for (int s = 0; s < plan.settlements(); s++) {
                            if (ready.contains(plan.sId[s])) continue;
                            double d = Math.max(Math.abs(plan.sX[s] - x), Math.abs(plan.sZ[s] - z)) - plan.sReach[s];
                            if (d <= reach) {
                                long[] more = load(plan.sId[s]);
                                if (more != null) {
                                    ready.add(plan.sId[s]);
                                    for (long k : more) batchKeys.add(k);
                                }
                            }
                        }
                        for (AtlasOverlayStarts.Start st : near) {
                            if (ready.contains(st.key())) continue;
                            BoundingBox b = st.box();
                            double d = Math.max(Math.max(b.minX() - x, x - b.maxX()), Math.max(b.minZ() - z, z - b.maxZ()));
                            if (d <= reach) {
                                long[] more = load(st.key());
                                if (more != null) {
                                    ready.add(st.key());
                                    for (long k : more) batchKeys.add(k);
                                }
                            }
                        }
                        keys = batchKeys.stream().mapToLong(Long::longValue).toArray();
                    } else {
                        List<? extends StructurePiece> pieces = seeded != null ? seeded.pieces() : AtlasOverlayTapes.pieces(id);
                        int layer = seeded != null ? seeded.layer() : AtlasOverlayTapes.LAYER_SETTLEMENTS;
                        keys = record(id, pieces, layer);
                        ready.add(id);
                    }
                    Map<Long, Integer> offsets = new HashMap<>();
                    ovl.set(AtlasOvlNative.SET_TAPES, tapes.tapeWords(offsets));
                    noBake(plan);  // (its chunks made from now on are kept)
                    // chunks made before their tapes were up: again
                    AtlasClientGen.regenerate(keys);
                    doing = "";
                } catch (Throwable t) {
                    if (!running) return;
                    com.nirncraft.NirnCraft.LOGGER.warn("atlas overlays: preparing settlements stopped", t);
                    return;
                }
            }
        }

        /** The seeded starts whose source chunk lies within reach (+ a start's own reach); found again after a move. */
        boolean updateSeededNear(double x, double z, int reach) {
            int margin = 256;
            if (!Double.isNaN(nearX) && Math.max(Math.abs(x - nearX), Math.abs(z - nearZ)) < margin) return false;
            if (starts == null) {
                ServerLevel ow = server.overworld();
                starts = new AtlasOverlayStarts(server.registryAccess(), ow.getChunkSource().getGenerator(), ow.getChunkSource().getGeneratorState(),
                        server.getStructureTemplateManager(), ow, AtlasOverlayStarts.ALL);
            }
            doing = "finding seeded starts";
            int r = reach + AtlasOverlayStarts.REACH + margin;
            near = starts.in((int) x - r, (int) z - r, (int) x + r, (int) z + r);
            nearX = x;
            nearZ = z;
            doing = "";
            return true;
        }

        List<AtlasOverlayStarts.Start> seededNear(double x, double z, int reach) {
            updateSeededNear(x, z, reach);
            return near;
        }

        /** A settlement's cached tapes, or null. */
        long[] load(String id) {
            Path f = cache.resolve(id + ".tape.gz");
            if (!Files.exists(f)) return null;
            try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(Files.newInputStream(f))))) {
                long[] keys = tapes.read(in);
                // (the block entities and mobs beside it; a cache without them is recorded again)
                Map<Long, List<net.minecraft.nbt.CompoundTag>> side = AtlasSideOps.read(cache.resolve(id + ".side.gz"));
                if (side == null) return null;
                AtlasSideOps.register(id, side);
                loaded++;
                return keys;
            } catch (Exception e) {
                com.nirncraft.NirnCraft.LOGGER.info("atlas overlays: the cached tapes of {} are unreadable ({}): recorded again", id, e.toString());
                return null;
            }
        }

        /** Records a settlement over the GPU generator's terrain and caches it. */
        long[] record(String id, List<? extends StructurePiece> pieces, int layer) throws Exception {
            long t0 = System.nanoTime();
            ServerLevel ow = server.overworld();
            if (rec == null) {
                AtlasGenNative nat = AtlasGenNative.load(library);
                rec = nat == null ? null : nat.create(AtlasGenNative.BACKEND_VULKAN);
                if (rec == null) throw new IllegalStateException("no recording generator");
                rec.setProgram(prog.words);
                AtlasClientGen.devicePages(rec, prog, ow.getChunkSource().getGenerator().getBiomeSource());
            }
            // the chunks the pieces touch, and one round them (a piece reads, a sweep or a bed writes, a chunk out)
            Set<Long> touched = new LinkedHashSet<>();
            int cx0 = Integer.MAX_VALUE, cz0 = Integer.MAX_VALUE, cx1 = Integer.MIN_VALUE, cz1 = Integer.MIN_VALUE;
            for (StructurePiece p : pieces) {
                BoundingBox b = p.getBoundingBox();
                for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++)
                    for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++) {
                        touched.add(ChunkPos.pack(cx, cz));
                        cx0 = Math.min(cx0, cx);
                        cz0 = Math.min(cz0, cz);
                        cx1 = Math.max(cx1, cx);
                        cz1 = Math.max(cz1, cz);
                    }
            }
            if (touched.isEmpty()) return new long[0];
            Set<Long> need = new LinkedHashSet<>();
            for (long k : touched)
                for (int dz = -1; dz <= 1; dz++)
                    for (int dx = -1; dx <= 1; dx++) need.add(ChunkPos.pack(ChunkPos.getX(k) + dx, ChunkPos.getZ(k) + dz));
            doing = "recording " + id + " (" + touched.size() + " chunks)";
            Map<Long, ProtoChunk> terrain = terrain(new ArrayList<>(need), ow);
            AtlasOverlayTapes.Window w = new AtlasOverlayTapes.Window(ow, ow.getSeed(), k -> terrain.get(k));
            w.side = new AtlasSideCapture(ow);  // (the structures' block entities and mobs, for the integrated server's chunks)
            if (layer == AtlasOverlayTapes.LAYER_SETTLEMENTS) {
                List<SettlementPiece> sp = new ArrayList<>();
                for (StructurePiece p : pieces) if (p instanceof SettlementPiece s) sp.add(s);
                tapes.record(id, sp, w, cx0, cz0, cx1, cz1);
            } else {
                tapes.recordRaw(id, new ArrayList<>(pieces), w, cx0, cz0, cx1, cz1, layer);
            }
            long[] keys = touched.stream().mapToLong(Long::longValue).toArray();
            Files.createDirectories(cache);
            Map<Long, List<net.minecraft.nbt.CompoundTag>> side = w.side.finish(w.chunks.values());
            AtlasSideOps.write(cache.resolve(id + ".side.gz"), side);
            AtlasSideOps.register(id, side);
            Path f = cache.resolve(id + ".tape.gz"), tmp = cache.resolve(id + ".tape.gz.tmp");
            try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
                tapes.write(out, keys);
            }
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            recorded++;
            recordMs += (System.nanoTime() - t0) / 1_000_000;
            com.nirncraft.NirnCraft.LOGGER.info("atlas overlays: recorded {} ({} chunks, {} pieces) in {} ms", id, touched.size(), pieces.size(),
                    (System.nanoTime() - t0) / 1_000_000);
            return keys;
        }

        /** The chunks' terrain from the recording generator (the GPU's; no stages), as proto chunks. */
        Map<Long, ProtoChunk> terrain(List<Long> keys, ServerLevel ow) {
            Map<Long, ProtoChunk> out = new HashMap<>();
            PalettedContainerFactory containers = PalettedContainerFactory.create(server.registryAccess());
            int batch = AtlasGenNative.RUN_CHUNKS;
            int[] ids = new int[batch * AtlasGenNative.CHUNK_WORDS], flags = new int[batch];
            for (int at = 0; at < keys.size() && running; at += batch) {
                int n = Math.min(batch, keys.size() - at);
                int[] xz = new int[n * 2];
                for (int i = 0; i < n; i++) {
                    xz[i * 2] = ChunkPos.getX(keys.get(at + i));
                    xz[i * 2 + 1] = ChunkPos.getZ(keys.get(at + i));
                }
                rec.chunks(xz, 0, ids, flags);
                for (int i = 0; i < n; i++) {
                    ProtoChunk c = new ProtoChunk(new ChunkPos(xz[i * 2], xz[i * 2 + 1]), UpgradeData.EMPTY, ow, containers, null);
                    LevelChunkSection[] secs = c.getSections();
                    int base = i * AtlasGenNative.CHUNK_WORDS;
                    for (int s = 0; s < Math.min(secs.length, 40); s++)
                        for (int b = 0; b < 4096; b++) {
                            int g = s * 4096 + b, w = ids[base + (g >> 1)];
                            int id = (g & 1) != 0 ? w >>> 16 : w & 0xFFFF;
                            if (id != 0) secs[s].setBlockState(b & 15, b >> 8, b >> 4 & 15, prog.blocks.get(id), false);
                        }
                    Heightmap.primeHeightmaps(c, EnumSet.allOf(Heightmap.Types.class));
                    c.setPersistedStatus(ChunkStatus.TERRAIN);
                    out.put(keys.get(at + i), c);
                }
            }
            return out;
        }
    }
}
