package com.nirncraft.atlas;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
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
    private final MethodHandle readPage, pageStats, tiles;
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
        tiles = lib.find("atlas_gen_tiles").isPresent()
                ? bind(l, lib, "atlas_gen_tiles", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS)) : null;
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

        /** Whether this library makes far tiles on the device (atlas_gen_tiles). */
        boolean tilesSupported() {
            return tiles != null;
        }

        /**
         * Far tiles on the device (atlas_gen_tiles): (lod, tx, tz) triples; surface / water count * TILE_COLS floats
         * each; returns per tile nonzero where a column is not proven exact. Also the kernel ms in times[0].
         */
        int[] tiles(int[] lodTxTz, float[] surface, float[] water, double[] times) {
            int n = lodTxTz.length / 3;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = a.allocate(JAVA_FLOAT, (long) n * TILE_COLS), w = a.allocate(JAVA_FLOAT, (long) n * TILE_COLS);
                MemorySegment f = a.allocate(JAVA_INT, n), t = a.allocate(96);
                check((int) tiles.invokeExact(ptr, a.allocateFrom(JAVA_INT, lodTxTz), n, s, w, f, t), "atlas_gen_tiles");
                MemorySegment.copy(s, JAVA_FLOAT, 0, surface, 0, n * TILE_COLS);
                MemorySegment.copy(w, JAVA_FLOAT, 0, water, 0, n * TILE_COLS);
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
