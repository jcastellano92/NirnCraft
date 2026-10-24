package com.nirncraft.atlas;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The atlas_gen_* calls of atlas_core (atlas.h: the client's world generator), bound on their own so the generator
 * stays apart from the renderer's binding. Null-safe: {@link #load} returns null when the library has no generator.
 */
final class AtlasGenNative {
    static final int BACKEND_CPU = 0, BACKEND_VULKAN = 1;
    /** Words per generated chunk (40 sections of 2048 words: two 16-bit ids each). */
    static final int CHUNK_WORDS = 40 * 2048;
    /** Words of a chunk's section info (atlas_gen_run): ground, top, biome, 0, then per section hash lo, hi, kind, 0. */
    static final int INFO_WORDS = 4 + 40 * 4;
    static final int SEC_AIR = 0, SEC_BLOCKS = 1, SEC_ROCK = 2;
    /** Most chunks of one atlas_gen_run. */
    static final int RUN_CHUNKS = 64;

    private final MethodHandle create, destroy, lastError, device, setProgram, pageWords, missing, putPage, chunks, into;
    // GPU -> GPU (atlas_gen_create_for ...): null in a library without them
    private final MethodHandle createFor, setIngest, run, ingest;
    // the map on the GPU (atlas_gen_set_map, atlas_gen_probe): null in a library without them
    private final MethodHandle setMap, probe;
    // the pages made on the device (atlas_gen_read_page, atlas_gen_page_stats): null in a library without them
    private final MethodHandle readPage, pageStats, tiles, far, farIngest, gridReports;
    // the self-baking cache (atlas_gen_cache_lookup, atlas_gen_cache_ingest, atlas_gen_set_fingerprint): null without
    private final MethodHandle cacheLookup, cacheIngest, setFingerprint, farCacheIngest, regenerate, coarseRoom, paceStats, coarseWindow, cachePeek;
    // the chunk store and the decoration's store stages (atlas_gen_store_*, atlas_feat_attach): null in a library without them
    private final MethodHandle storeEnable, runStore, storeKeep, storeStats, featAttach, featDestroy, featOverlays;
    /** Columns of a far tile (atlas_gen_tiles). */
    static final int TILE_COLS = 65536;
    /** Words per probe (atlas_gen_probe). */
    static final int PROBE_WORDS = 32;

    private AtlasGenNative(SymbolLookup lib) {
        Linker l = Linker.nativeLinker();
        create = bind(l, lib, "atlas_gen_create", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        destroy = bind(l, lib, "atlas_gen_destroy", FunctionDescriptor.ofVoid(ADDRESS));
        lastError = bind(l, lib, "atlas_gen_last_error", FunctionDescriptor.of(ADDRESS, ADDRESS));
        device = bind(l, lib, "atlas_gen_device", FunctionDescriptor.of(ADDRESS, ADDRESS));
        setProgram = bind(l, lib, "atlas_gen_set_program", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        pageWords = bind(l, lib, "atlas_gen_page_words", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        missing = bind(l, lib, "atlas_gen_missing_pages", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        putPage = bind(l, lib, "atlas_gen_put_page", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
        chunks = bind(l, lib, "atlas_gen_chunks", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        into = bind(l, lib, "atlas_gen_into", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        boolean gpu = lib.find("atlas_gen_create_for").isPresent();
        createFor = gpu ? bind(l, lib, "atlas_gen_create_for", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)) : null;
        setIngest = gpu ? bind(l, lib, "atlas_gen_set_ingest", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)) : null;
        run = gpu ? bind(l, lib, "atlas_gen_run", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS)) : null;
        ingest = gpu ? bind(l, lib, "atlas_gen_ingest", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS)) : null;
        boolean map = lib.find("atlas_gen_set_map").isPresent();
        setMap = map ? bind(l, lib, "atlas_gen_set_map", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)) : null;
        probe = map ? bind(l, lib, "atlas_gen_probe", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)) : null;
        boolean dp = lib.find("atlas_gen_page_stats").isPresent();
        readPage = dp ? bind(l, lib, "atlas_gen_read_page", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)) : null;
        pageStats = dp ? bind(l, lib, "atlas_gen_page_stats", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS)) : null;
        far = lib.find("atlas_gen_far").isPresent()
                ? bind(l, lib, "atlas_gen_far", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS,
                JAVA_INT, ADDRESS, ADDRESS, ADDRESS)) : null;
        farIngest = lib.find("atlas_gen_far_ingest").isPresent()
                ? bind(l, lib, "atlas_gen_far_ingest", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS,
                ADDRESS, ADDRESS, ADDRESS, ADDRESS)) : null;
        gridReports = lib.find("atlas_gen_grid_reports").isPresent()
                ? bind(l, lib, "atlas_gen_grid_reports", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT,
                ADDRESS)) : null;
        boolean cache = lib.find("atlas_gen_cache_lookup").isPresent();
        cacheLookup = cache ? bind(l, lib, "atlas_gen_cache_lookup", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS)) : null;
        cacheIngest = cache ? bind(l, lib, "atlas_gen_cache_ingest", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS)) : null;
        setFingerprint = cache ? bind(l, lib, "atlas_gen_set_fingerprint", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG)) : null;
        cachePeek = lib.find("atlas_gen_cache_peek").isPresent()
                ? bind(l, lib, "atlas_gen_cache_peek", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)) : null;
        coarseWindow = lib.find("atlas_gen_coarse_window").isPresent()
                ? bind(l, lib, "atlas_gen_coarse_window", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)) : null;
        coarseRoom = lib.find("atlas_gen_coarse_room").isPresent()
                ? bind(l, lib, "atlas_gen_coarse_room", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)) : null;
        paceStats = lib.find("atlas_gen_pace_stats").isPresent()
                ? bind(l, lib, "atlas_gen_pace_stats", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)) : null;
        regenerate = lib.find("atlas_gen_regenerate").isPresent()
                ? bind(l, lib, "atlas_gen_regenerate", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)) : null;
        farCacheIngest = lib.find("atlas_gen_far_cache_ingest").isPresent()
                ? bind(l, lib, "atlas_gen_far_cache_ingest", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
                : null;
        boolean st = lib.find("atlas_gen_run_store").isPresent();
        storeEnable = st ? bind(l, lib, "atlas_gen_store_enable", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)) : null;
        runStore = st ? bind(l, lib, "atlas_gen_run_store", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS,
                ADDRESS, ADDRESS, ADDRESS, ADDRESS)) : null;
        storeKeep = st ? bind(l, lib, "atlas_gen_store_keep", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)) : null;
        storeStats = st ? bind(l, lib, "atlas_gen_store_stats", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS)) : null;
        boolean fa = lib.find("atlas_feat_attach").isPresent();
        featAttach = fa ? bind(l, lib, "atlas_feat_attach", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT,
                ADDRESS, JAVA_INT, ADDRESS)) : null;
        featDestroy = fa ? bind(l, lib, "atlas_feat_destroy", FunctionDescriptor.ofVoid(ADDRESS)) : null;
        featOverlays = lib.find("atlas_feat_set_overlays").isPresent()
                ? bind(l, lib, "atlas_feat_set_overlays", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)) : null;
        tiles = lib.find("atlas_gen_tiles").isPresent()
                ? bind(l, lib, "atlas_gen_tiles", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)) : null;
    }

    private static MethodHandle bind(Linker l, SymbolLookup lib, String name, FunctionDescriptor fd) {
        MemorySegment sym = lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError("atlas_core lacks " + name));
        return l.downcallHandle(sym, fd);
    }

    /** The generator calls of the library at path (loaded into a shared arena for the process), or null. */
    static AtlasGenNative load(Path library) {
        SymbolLookup lib = SymbolLookup.libraryLookup(library, Arena.global());
        if (lib.find("atlas_gen_create").isEmpty()) return null;
        return new AtlasGenNative(lib);
    }

    /** Device times of one call (AtlasGenTimes), milliseconds. */
    record Times(double uploadMs, double kernelMs, double readbackMs, double ingestMs, double totalMs, double[] passMs) {
        static Times of(MemorySegment t) {
            double[] p = new double[6];
            for (int i = 0; i < 6; i++) p[i] = t.get(JAVA_DOUBLE, 40 + i * 8L);
            return new Times(t.get(JAVA_DOUBLE, 0), t.get(JAVA_DOUBLE, 8), t.get(JAVA_DOUBLE, 16), t.get(JAVA_DOUBLE, 24),
                    t.get(JAVA_DOUBLE, 32), p);
        }
    }

    /** A generator on backend, or null when the backend is unavailable (why on stderr from the library). */
    Gen create(int backend) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ADDRESS);
            int s = (int) create.invokeExact(backend, out);
            return s == 0 ? new Gen(out.get(ADDRESS, 0)) : null;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * A generator on the context's own device whose sections go device to device into its grid (atlas_gen_create_for),
     * or null when that cannot be (no such call, the device cannot run the exact kernels, the grid is off).
     */
    Gen createFor(MemorySegment ctx) {
        if (createFor == null) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ADDRESS);
            int s = (int) createFor.invokeExact(ctx, out);
            return s == 0 ? new Gen(out.get(ADDRESS, 0)) : null;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    final class Gen implements AutoCloseable {
        private final MemorySegment ptr;

        /** The native AtlasGen* (atlas.h), for the stages' engines to call atlas_gen_add_stage / atlas_gen_device_handles. */
        MemorySegment handle() {
            return ptr;
        }

        private Gen(MemorySegment ptr) {
            this.ptr = ptr;
        }

        String device() {
            try {
                return ((MemorySegment) device.invokeExact(ptr)).reinterpret(512).getString(0);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        private String error() {
            try {
                return ((MemorySegment) lastError.invokeExact(ptr)).reinterpret(4096).getString(0);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        private void check(int s, String what) {
            if (s != 0) throw new IllegalStateException(what + ": " + s + " " + error());
        }

        void setProgram(int[] words) {
            try (Arena a = Arena.ofConfined()) {
                check((int) setProgram.invokeExact(ptr, a.allocateFrom(JAVA_INT, words), words.length), "atlas_gen_set_program");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        int pageWords() {
            try {
                return (int) pageWords.invokeExact(ptr);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** The (cx, cz, full) triples of the pages chunkXZ need that are not resident. */
        int[] missing(int[] chunkXZ) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment in = a.allocateFrom(JAVA_INT, chunkXZ);
                int n = (int) missing.invokeExact(ptr, in, chunkXZ.length / 2, MemorySegment.NULL, 0);
                if (n == 0) return new int[0];
                MemorySegment out = a.allocate(JAVA_INT, n * 3L);
                int m = (int) missing.invokeExact(ptr, in, chunkXZ.length / 2, out, n);
                return out.asSlice(0, Math.min(n, m) * 12L).toArray(JAVA_INT);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        void putPage(int cx, int cz, boolean full, int[] words) {
            try (Arena a = Arena.ofConfined()) {
                check((int) putPage.invokeExact(ptr, cx, cz, full ? 1 : 0, a.allocateFrom(JAVA_INT, words), words.length),
                        "atlas_gen_put_page");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        // the output buffer, kept between calls (a batch's 21 MB would otherwise be allocated and zeroed each time)
        private Arena outArena;
        private MemorySegment outSeg;
        private long outWords;

        /** Generates the chunks: ids receives count * CHUNK_WORDS words, flags count words (may be null). */
        synchronized Times chunks(int[] chunkXZ, int outMode, int[] ids, int[] flags) {
            int n = chunkXZ.length / 2;
            if (outSeg == null || outWords < (long) n * CHUNK_WORDS) {
                if (outArena != null) outArena.close();
                outArena = Arena.ofShared();
                outWords = Math.max(64L, n) * CHUNK_WORDS;
                outSeg = outArena.allocate(JAVA_INT, outWords);
            }
            try (Arena a = Arena.ofConfined()) {
                MemorySegment out = outSeg;
                MemorySegment fl = a.allocate(JAVA_INT, n);
                MemorySegment t = a.allocate(88, 8);
                check((int) chunks.invokeExact(ptr, a.allocateFrom(JAVA_INT, chunkXZ), n, outMode, out, fl, t), "atlas_gen_chunks");
                MemorySegment.copy(out, JAVA_INT, 0, ids, 0, n * CHUNK_WORDS);
                if (flags != null) MemorySegment.copy(fl, JAVA_INT, 0, flags, 0, n);
                return Times.of(t);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Generates the chunks into an atlas context (its atlas_upload_section path); returns the times. */
        Times into(MemorySegment ctx, int[] chunkXZ, int[] flags, int[] sections) {
            int n = chunkXZ.length / 2;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment fl = a.allocate(JAVA_INT, n);
                MemorySegment sec = a.allocate(JAVA_INT);
                MemorySegment t = a.allocate(88, 8);
                check((int) into.invokeExact(ptr, ctx, a.allocateFrom(JAVA_INT, chunkXZ), n, fl, sec, t), "atlas_gen_into");
                if (flags != null) MemorySegment.copy(fl, JAVA_INT, 0, flags, 0, n);
                if (sections != null) sections[0] = sec.get(JAVA_INT, 0);
                return Times.of(t);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Whether this library makes the pages on the device (it has atlas_gen_page_stats). */
        boolean devicePagesSupported() {
            return setMap != null && pageStats != null;
        }

        /** Level-lod far chunks of lod tiles (atlas_gen_far): kinds and slots per chunk, the mixed ones' voxels. */
        record FarChunks(int[] kinds, int[] ids, int mixed, int[] flags, double kernelMs) {}

        /** Far chunks on the device (atlas_gen_far); mode 0 generator ids, 1 atlas materials. */
        FarChunks far(int lod, int[] txTz, int mode, int capacity) {
            int n = txTz.length / 2, rows = (44 + (1 << lod) - 1) >> lod;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment k = a.allocate(JAVA_INT, (long) n * 256 * rows * 2), ids = a.allocate(JAVA_INT, (long) capacity * 2048);
                MemorySegment m = a.allocate(JAVA_INT), f = a.allocate(JAVA_INT, n), t = a.allocate(96);
                check((int) far.invokeExact(ptr, lod, a.allocateFrom(JAVA_INT, txTz), n, mode, k, ids, capacity, m, f, t), "atlas_gen_far");
                int mixed = m.get(JAVA_INT, 0);
                return new FarChunks(k.toArray(JAVA_INT), ids.asSlice(0, (long) Math.min(mixed, capacity) * 2048 * 4).toArray(JAVA_INT), mixed,
                        f.toArray(JAVA_INT), t.get(JAVA_DOUBLE, 8));
            } catch (Throwable e) {
                throw rethrow(e);
            }
        }

        /** Whether this library puts far chunks into a context's grid (atlas_gen_far_ingest). */
        boolean farIngestSupported() {
            return farIngest != null && gridReports != null;
        }

        /** A far call into the grid: chunks taken, chunks made (all of the call's), mixed ones; -1 taken when busy. */
        record FarIngest(int taken, int chunks, int mixed, double kernelMs) {}

        /**
         * A far tile from the self-baking cache into the grid's level lod (atlas_gen_far_cache_ingest), as
         * {@link #farIngest} would put it; null when the cache does not know it (generate it then).
         */
        /**
         * A coarse level's window now: {origin x, z, side (chunks of the level), placed (1), jumps, rows}; null with an older
         * library. The far tiles to ask for are those that cover its columns.
         */
        int[] coarseWindow(int level) {
            if (coarseWindow == null) return null;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment o = a.allocate(JAVA_INT, 6);
                check((int) coarseWindow.invokeExact(ptr, level, o), "atlas_gen_coarse_window");
                int[] r = new int[6];
                for (int i = 0; i < 6; i++) r[i] = o.getAtIndex(JAVA_INT, i);
                return r;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Free slots in the grid's coarse level (a refused far tile is asked for again when there is room); -1: unknown. */
        int coarseRoom(int level) {
            if (coarseRoom == null) return -1;
            try {
                return (int) coarseRoom.invokeExact(ptr, level);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * The self-baking cache's numbers (atlas_gen_cache_peek) as a profile block: whether it is on and which
         * fingerprint, what is on disk, what waits to be written, what this session wrote and read; null with an older
         * library.
         */
        String cacheLine() {
            long[] s = cacheStats();
            if (s == null) return null;
            return String.format("genCache{%s fp %016x on disk %d chunks %d sections %.1f MB; queued writes %d; written %d chunks %d sections %d far tiles; "
                            + "chunk hits %d misses %d, section reads %d, far tile hits %d, hash errors %d}",
                    s[0] != 0 ? "on" : "OFF", s[1], s[2], s[3], s[4] / 1048576.0, s[13], s[12], s[7], s[9], s[5], s[6], s[8], s[10], s[11]);
        }

        /**
         * {on, fingerprint, chunks, sections, bytes, chunkHits, chunkMisses, sectionWrites, sectionReads, farWrites, farHits,
         * hashErrors, chunkWrites, queued}; null with an older library.
         */
        long[] cacheStats() {
            if (cachePeek == null) return null;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = a.allocate(8L * 20);
                check((int) cachePeek.invokeExact(ptr, s), "atlas_gen_cache_peek");
                // (AtlasGenCacheStats: on at 0; fingerprint 8, chunks 16, sections 24, bytes 32, chunkHits 40, chunkMisses 48,
                // sectionWrites 56, sectionReads 64, encodeMs 72, decodeMs 80, farWrites 88, farHits 96, farMisses 104,
                // hashErrors 112, chunkWrites 120, queued 128)
                return new long[]{s.get(JAVA_INT, 0), s.get(JAVA_LONG, 8), s.get(JAVA_LONG, 16), s.get(JAVA_LONG, 24), s.get(JAVA_LONG, 32),
                        s.get(JAVA_LONG, 40), s.get(JAVA_LONG, 48), s.get(JAVA_LONG, 56), s.get(JAVA_LONG, 64), s.get(JAVA_LONG, 88),
                        s.get(JAVA_LONG, 96), s.get(JAVA_LONG, 112), s.get(JAVA_LONG, 120), s.get(JAVA_LONG, 128)};
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** The GPU budget's numbers (atlas_gen_pace_stats) as a profile line; null with an older library. */
        String paceLine() {
            if (paceStats == null) return null;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = a.allocate(8L * 8 + 48);
                check((int) paceStats.invokeExact(ptr, s), "atlas_gen_pace_stats");
                return String.format("budget{frame %.1f ms duty %.2f used %.0f waited %.0f (far %.0f) longest %.0f ms (%s), %d submissions}",
                        s.get(JAVA_DOUBLE, 0), s.get(JAVA_DOUBLE, 8), s.get(JAVA_DOUBLE, 24), s.get(JAVA_DOUBLE, 32),
                        s.get(JAVA_DOUBLE, 40), s.get(JAVA_DOUBLE, 48), s.getString(64), s.get(JAVA_LONG, 56));
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Chunks made again (their stages' data changed): out of the self-baking cache, back through the store. */
        synchronized void regenerate(int[] chunkXZ) {
            if (regenerate == null || chunkXZ.length == 0) return;
            try (Arena a = Arena.ofConfined()) {
                check((int) regenerate.invokeExact(ptr, a.allocateFrom(JAVA_INT, chunkXZ), chunkXZ.length / 2), "atlas_gen_regenerate");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        synchronized FarIngest farCacheIngest(int lod, int tx, int tz) {
            if (farCacheIngest == null) return null;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment served = a.allocate(1), ch = a.allocate(JAVA_INT), tk = a.allocate(JAVA_INT);
                check((int) farCacheIngest.invokeExact(ptr, lod, a.allocateFrom(JAVA_INT, tx, tz), 1, served, ch, tk), "atlas_gen_far_cache_ingest");
                if (served.get(JAVA_BYTE, 0) == 0) return null;
                return new FarIngest(tk.get(JAVA_INT, 0), ch.get(JAVA_INT, 0), 0, 0);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Far chunks of lod tiles (tx, tz pairs) into the context's grid (atlas_gen_far_ingest); null when busy. */
        FarIngest farIngest(int lod, int[] txTz, int capacity) {
            int n = txTz.length / 2, rows = (44 + (1 << lod) - 1) >> lod;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment k = a.allocate(JAVA_INT, (long) n * 256 * rows * 2), m = a.allocate(JAVA_INT), tk = a.allocate(JAVA_INT);
                MemorySegment f = a.allocate(JAVA_INT, n), t = a.allocate(96);
                int s = (int) farIngest.invokeExact(ptr, lod, a.allocateFrom(JAVA_INT, txTz), n, capacity, k, m, tk, f, t);
                if (s == -8) return null;  // ATLAS_ERR_TIMEOUT: no window yet, or the far buffers still read
                check(s, "atlas_gen_far_ingest");
                return new FarIngest(tk.get(JAVA_INT, 0), n * 256 * rows, m.get(JAVA_INT, 0), t.get(JAVA_DOUBLE, 8));
            } catch (Throwable e) {
                throw rethrow(e);
            }
        }

        /** The grid's reports since the last call: [0] dropped {level, x, y, z}..., [1] wanted (atlas_gen_grid_reports). */
        int[][] gridReports() {
            try (Arena a = Arena.ofConfined()) {
                int cap = 1 << 16;
                MemorySegment d = a.allocate(JAVA_INT, (long) cap * 4), w = a.allocate(JAVA_INT, (long) cap * 4);
                MemorySegment dn = a.allocate(JAVA_INT), wn = a.allocate(JAVA_INT);
                int lost = (int) gridReports.invokeExact(ptr, d, cap, dn, w, cap, wn);
                if (lost > 0) com.nirncraft.NirnCraft.LOGGER.warn("atlas generator: {} grid reports did not fit", lost);
                return new int[][]{d.asSlice(0, (long) dn.get(JAVA_INT, 0) * 16).toArray(JAVA_INT),
                        w.asSlice(0, (long) wn.get(JAVA_INT, 0) * 16).toArray(JAVA_INT)};
            } catch (Throwable e) {
                throw rethrow(e);
            }
        }

        /** Whether this library makes far tiles on the device (atlas_gen_tiles). */
        boolean tilesSupported() {
            return tiles != null;
        }

        /**
         * Far tiles on the device (atlas_gen_tiles): (lod, tx, tz) triples; surface / water count * TILE_COLS floats
         * each; returns per tile nonzero where a column is not proven exact. Also the kernel ms in times[0].
         */
        int[] tiles(int[] lodTxTz, float[] surface, float[] water, double[] times) {
            return tiles(lodTxTz, surface, water, null, null, times);
        }

        /** As tiles, with each column's top | under << 16 (generator ids) and its top's biome when blocks != null. */
        int[] tiles(int[] lodTxTz, float[] surface, float[] water, int[] blocks, int[] biomes, double[] times) {
            int n = lodTxTz.length / 3;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = a.allocate(JAVA_FLOAT, (long) n * TILE_COLS), w = a.allocate(JAVA_FLOAT, (long) n * TILE_COLS);
                MemorySegment b = blocks != null ? a.allocate(JAVA_INT, (long) n * TILE_COLS) : MemorySegment.NULL;
                MemorySegment bi = blocks != null && biomes != null ? a.allocate(JAVA_INT, (long) n * TILE_COLS) : MemorySegment.NULL;
                MemorySegment f = a.allocate(JAVA_INT, n), t = a.allocate(96);
                check((int) tiles.invokeExact(ptr, a.allocateFrom(JAVA_INT, lodTxTz), n, s, w, b, bi, f, t), "atlas_gen_tiles");
                MemorySegment.copy(s, JAVA_FLOAT, 0, surface, 0, n * TILE_COLS);
                MemorySegment.copy(w, JAVA_FLOAT, 0, water, 0, n * TILE_COLS);
                if (blocks != null) MemorySegment.copy(b, JAVA_INT, 0, blocks, 0, n * TILE_COLS);
                if (blocks != null && biomes != null) MemorySegment.copy(bi, JAVA_INT, 0, biomes, 0, n * TILE_COLS);
                if (times != null && times.length > 0) times[0] = t.get(JAVA_DOUBLE, 8);
                return f.toArray(JAVA_INT);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** A resident page's words (atlas_gen_read_page), or null when it is not resident. */
        int[] readPage(int cx, int cz, int words) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment out = a.allocate(JAVA_INT, words);
                int s = (int) readPage.invokeExact(ptr, cx, cz, out, words);
                return s == 0 ? out.toArray(JAVA_INT) : null;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Pages made on the device so far, their passes' total ms, and whether the device makes them. */
        double[] pageStats() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment n = a.allocate(JAVA_LONG), ms = a.allocate(JAVA_DOUBLE), on = a.allocate(JAVA_INT);
                check((int) pageStats.invokeExact(ptr, n, ms, on), "atlas_gen_page_stats");
                return new double[]{n.get(JAVA_LONG, 0), ms.get(JAVA_DOUBLE, 0), on.get(JAVA_INT, 0)};
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** The map of Nirn for the kernels (atlas_gen_set_map, AtlasGenMap). */
        void setMap(int[] words) {
            try (Arena a = Arena.ofConfined()) {
                check((int) setMap.invokeExact(ptr, a.allocateFrom(JAVA_INT, words), words.length), "atlas_gen_set_map");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** The kernels' pieces at (x, z) columns (atlas_gen_probe): PROBE_WORDS words each, the last a flag. */
        synchronized int[] probe(int kind, int[] xz) {
            int n = xz.length / 2;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment out = a.allocate(JAVA_INT, (long) n * PROBE_WORDS);
                check((int) probe.invokeExact(ptr, kind, n, a.allocateFrom(JAVA_INT, xz), out), "atlas_gen_probe");
                return out.toArray(JAVA_INT);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** The context's side of the blocks (atlas_gen_set_ingest). */
        void setIngest(int[] words) {
            try (Arena a = Arena.ofConfined()) {
                check((int) setIngest.invokeExact(ptr, a.allocateFrom(JAVA_INT, words), words.length), "atlas_gen_set_ingest");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * Generates up to RUN_CHUNKS chunks for {@link #ingest}: ids (null: not wanted) count * CHUNK_WORDS generator ids,
         * flags count words, info count * INFO_WORDS words (atlas_gen_run).
         */
        synchronized Times run(int[] chunkXZ, int[] ids, int[] flags, int[] info) {
            int n = chunkXZ.length / 2;
            if (ids != null && (outSeg == null || outWords < (long) n * CHUNK_WORDS)) {
                if (outArena != null) outArena.close();
                outArena = Arena.ofShared();
                outWords = Math.max(64L, n) * CHUNK_WORDS;
                outSeg = outArena.allocate(JAVA_INT, outWords);
            }
            try (Arena a = Arena.ofConfined()) {
                MemorySegment out = ids != null ? outSeg : MemorySegment.NULL;
                MemorySegment fl = a.allocate(JAVA_INT, n);
                MemorySegment inf = a.allocate(JAVA_INT, (long) n * INFO_WORDS);
                MemorySegment t = a.allocate(88, 8);
                check((int) run.invokeExact(ptr, a.allocateFrom(JAVA_INT, chunkXZ), n, out, fl, inf, t), "atlas_gen_run");
                if (ids != null) MemorySegment.copy(out, JAVA_INT, 0, ids, 0, n * CHUNK_WORDS);
                if (flags != null) MemorySegment.copy(fl, JAVA_INT, 0, flags, 0, n);
                MemorySegment.copy(inf, JAVA_INT, 0, info, 0, n * INFO_WORDS);
                return Times.of(t);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * The last run's sections whose bit is set in masks[chunk] (bit s: section y s - 4) into the context's grid, device
         * to device; masks then hold the bits that went (the grid's level-0 window takes no others). Returns how many went,
         * or -1 (none went) when the grid has no window yet or has not taken earlier ones (no frames for 2 s).
         */
        /** The self-baking cache is in the library (it is on once the context has a cache directory, Context.setCache). */
        boolean cacheSupported() {
            return cacheLookup != null;
        }

        /** The caller's part of the generator's fingerprint (the stages' engines' versions): another, another cache. */
        void setFingerprint(long extra) {
            if (setFingerprint == null) return;
            try {
                check((int) setFingerprint.invokeExact(ptr, extra), "atlas_gen_set_fingerprint");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * The chunks the cache knows (atlas_gen_cache_lookup): hit[i] 1 and info's rows as {@link #run}'s for those.
         * Returns how many.
         */
        synchronized int cacheLookup(int[] chunkXZ, int[] info, byte[] hit) {
            int n = chunkXZ.length / 2;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment inf = a.allocate(JAVA_INT, (long) n * INFO_WORDS);
                MemorySegment h = a.allocate(n);
                check((int) cacheLookup.invokeExact(ptr, a.allocateFrom(JAVA_INT, chunkXZ), n, inf, h), "atlas_gen_cache_lookup");
                MemorySegment.copy(inf, JAVA_INT, 0, info, 0, n * INFO_WORDS);
                int hits = 0;
                for (int i = 0; i < n; i++) {
                    hit[i] = h.get(JAVA_BYTE, i);
                    hits += hit[i];
                }
                return hits;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * The known chunks' sections whose bit is set in masks into the grid from the cache, as {@link #ingest} (no run
         * before); masks then hold what went. Returns how many, or -1 (none went: the grid has no window yet).
         */
        synchronized int cacheIngest(int[] chunkXZ, long[] masks) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment sec = a.allocate(JAVA_INT);
                MemorySegment m = a.allocateFrom(JAVA_LONG, masks);
                int s = (int) cacheIngest.invokeExact(ptr, a.allocateFrom(JAVA_INT, chunkXZ), chunkXZ.length / 2, m, sec);
                MemorySegment.copy(m, JAVA_LONG, 0, masks, 0, masks.length);
                if (s == -8) return -1;  // ATLAS_ERR_TIMEOUT
                check(s, "atlas_gen_cache_ingest");
                return sec.get(JAVA_INT, 0);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        synchronized int ingest(long[] masks) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment sec = a.allocate(JAVA_INT);
                MemorySegment m = a.allocateFrom(JAVA_LONG, masks);
                int s = (int) ingest.invokeExact(ptr, m, sec, MemorySegment.NULL);
                MemorySegment.copy(m, JAVA_LONG, 0, masks, 0, masks.length);
                if (s == -8) return -1;  // ATLAS_ERR_TIMEOUT
                check(s, "atlas_gen_ingest");
                return sec.get(JAVA_INT, 0);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Whether this library has the chunk store and the decoration's store stages (atlas_gen_run_store, atlas_feat_attach). */
        boolean storeSupported() {
            return runStore != null && featAttach != null;
        }

        /** Turns the chunk store on with that many slots (atlas_gen_store_enable). */
        void storeEnable(int slots) {
            try {
                check((int) storeEnable.invokeExact(ptr, slots), "atlas_gen_store_enable");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * Chunks into the store and, from it, up to RUN_CHUNKS chunks through every store stage (atlas_gen_run_store):
         * done receives their (cx, cz) pairs, ids (null: not wanted) their CHUNK_WORDS generator ids, flags their flags,
         * info (null: no ingest passes; else {@link #ingest} takes them next) their INFO_WORDS words. Returns how many.
         */
        synchronized int runStore(int[] chunkXZ, int[] done, int[] ids, int[] flags, int[] info, Times[] times) {
            int n = chunkXZ.length / 2;
            if (ids != null && (outSeg == null || outWords < (long) RUN_CHUNKS * CHUNK_WORDS)) {
                if (outArena != null) outArena.close();
                outArena = Arena.ofShared();
                outWords = (long) RUN_CHUNKS * CHUNK_WORDS;
                outSeg = outArena.allocate(JAVA_INT, outWords);
            }
            try (Arena a = Arena.ofConfined()) {
                MemorySegment in = n > 0 ? a.allocateFrom(JAVA_INT, chunkXZ) : MemorySegment.NULL;
                MemorySegment dn = a.allocate(JAVA_INT, RUN_CHUNKS * 2L), cnt = a.allocate(JAVA_INT);
                MemorySegment out = ids != null ? outSeg : MemorySegment.NULL;
                MemorySegment fl = a.allocate(JAVA_INT, RUN_CHUNKS);
                MemorySegment inf = info != null ? a.allocate(JAVA_INT, (long) RUN_CHUNKS * INFO_WORDS) : MemorySegment.NULL;
                MemorySegment t = a.allocate(88, 8);
                check((int) runStore.invokeExact(ptr, in, n, dn, cnt, out, fl, inf, t), "atlas_gen_run_store");
                int m = cnt.get(JAVA_INT, 0);
                MemorySegment.copy(dn, JAVA_INT, 0, done, 0, m * 2);
                if (ids != null) MemorySegment.copy(out, JAVA_INT, 0, ids, 0, m * CHUNK_WORDS);
                if (flags != null) MemorySegment.copy(fl, JAVA_INT, 0, flags, 0, m);
                if (info != null) MemorySegment.copy(inf, JAVA_INT, 0, info, 0, m * INFO_WORDS);
                if (times != null && times.length > 0) times[0] = Times.of(t);
                return m;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Drops the store's chunks outside the box (atlas_gen_store_keep). */
        void storeKeep(int minCx, int minCz, int maxCx, int maxCz) {
            try {
                check((int) storeKeep.invokeExact(ptr, minCx, minCz, maxCx, maxCz), "atlas_gen_store_keep");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** The store's chunks: held, waiting (not through every stage), ready (through, not packed). */
        int[] storeStats() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment h = a.allocate(JAVA_INT), w = a.allocate(JAVA_INT), r = a.allocate(JAVA_INT);
                check((int) storeStats.invokeExact(ptr, h, w, r), "atlas_gen_store_stats");
                return new int[]{h.get(JAVA_INT, 0), w.get(JAVA_INT, 0), r.get(JAVA_INT, 0)};
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * The decoration as this generator's store stages (atlas_feat_attach): the feature program, and the id tables
         * (null both: the same ids). Returns the handle for {@link #detachFeatures}.
         */
        MemorySegment attachFeatures(int[] words, int[] gen2feat, int[] feat2gen) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment out = a.allocate(ADDRESS);
                MemorySegment g2f = gen2feat != null ? a.allocateFrom(JAVA_INT, gen2feat) : MemorySegment.NULL;
                MemorySegment f2g = feat2gen != null ? a.allocateFrom(JAVA_INT, feat2gen) : MemorySegment.NULL;
                check((int) featAttach.invokeExact(ptr, a.allocateFrom(JAVA_INT, words), words.length, g2f,
                        gen2feat != null ? gen2feat.length : 0, f2g, feat2gen != null ? feat2gen.length : 0, out), "atlas_feat_attach");
                return out.get(ADDRESS, 0);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * The overlays whose structure pieces the decoration's placement mask keeps clear (atlas_feat_set_overlays; null
         * none). False when this library has not the call.
         */
        boolean setFeatureOverlays(MemorySegment feat, MemorySegment ovl) {
            if (featOverlays == null) return false;
            try {
                MemorySegment o = ovl == null ? MemorySegment.NULL : ovl;  // (a typed local: invokeExact's signature)
                check((int) featOverlays.invokeExact(feat, o), "atlas_feat_set_overlays");
                return true;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Removes the decoration's store stages (atlas_feat_destroy). */
        void detachFeatures(MemorySegment feat) {
            try {
                featDestroy.invokeExact(feat);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        @Override
        public synchronized void close() {
            if (outArena != null) {
                outArena.close();
                outArena = null;
                outSeg = null;
            }
            try {
                destroy.invokeExact(ptr);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }
    }
}
