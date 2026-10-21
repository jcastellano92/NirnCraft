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
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The atlas_feat_* calls of atlas_core (atlas.h: the client's decoration on the GPU, native/shaders/atlas_features.slang),
 * bound on their own like {@link AtlasGenNative}. {@link #load} returns null when the library has no decoration.
 */
final class AtlasFeatNative {
    static final int BACKEND_CPU = 0, BACKEND_VULKAN = 1;
    /** Words per decorated or terrain chunk: 44 sections (y -64..639) of 2048 words, two 16-bit ids each. */
    static final int CHUNK_WORDS = 44 * 2048;
    /** Words per terrain slot's pages: quart biomes, mask, alchemy classes, temperature noise, frozen bits, heightmaps. */
    static final int AUX_WORDS = 1928 + 6 * 256;
    static final int AUX_QUARTS = 0, AUX_MASK = 1408, AUX_ALCH = 1472, AUX_TEMP = 1664, AUX_FROZEN = 1920;
    static final int ORIGIN_WORDS = 28, TARGET_WORDS = 12;
    /** Words of an origin's write table (the isolated check). */
    static final int TABLE_WORDS = 16 + 32768 * 2;

    private final MethodHandle create, destroy, lastError, device, setProgram, run;

    private AtlasFeatNative(SymbolLookup lib) {
        Linker l = Linker.nativeLinker();
        create = bind(l, lib, "atlas_feat_create", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        destroy = bind(l, lib, "atlas_feat_destroy", FunctionDescriptor.ofVoid(ADDRESS));
        lastError = bind(l, lib, "atlas_feat_last_error", FunctionDescriptor.of(ADDRESS, ADDRESS));
        device = bind(l, lib, "atlas_feat_device", FunctionDescriptor.of(ADDRESS, ADDRESS));
        setProgram = bind(l, lib, "atlas_feat_set_program", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        run = bind(l, lib, "atlas_feat_run", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT,
                ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    }

    private static MethodHandle bind(Linker l, SymbolLookup lib, String name, FunctionDescriptor fd) {
        MemorySegment sym = lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError("atlas_core lacks " + name));
        return l.downcallHandle(sym, fd);
    }

    /** The decoration calls of the library at path, or null. */
    static AtlasFeatNative load(Path library) {
        SymbolLookup lib = SymbolLookup.libraryLookup(library, Arena.global());
        if (lib.find("atlas_feat_create").isEmpty()) return null;
        return new AtlasFeatNative(lib);
    }

    /** Times of one run (AtlasFeatTimes), milliseconds. */
    record Times(double uploadMs, double kernelMs, double featureMs, double gatherMs, double readbackMs, double totalMs) {
        static Times of(MemorySegment t) {
            return new Times(t.get(JAVA_DOUBLE, 0), t.get(JAVA_DOUBLE, 8), t.get(JAVA_DOUBLE, 16), t.get(JAVA_DOUBLE, 24),
                    t.get(JAVA_DOUBLE, 32), t.get(JAVA_DOUBLE, 40));
        }
    }

    /** A decoration on backend, or null when the backend is unavailable. */
    Feat create(int backend) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ADDRESS);
            int s = (int) create.invokeExact(backend, out);
            return s == 0 ? new Feat(out.get(ADDRESS, 0)) : null;
        } catch (Throwable t) {
            throw AtlasGenNative.rethrow(t);
        }
    }

    final class Feat implements AutoCloseable {
        private final MemorySegment ptr;

        private Feat(MemorySegment ptr) {
            this.ptr = ptr;
        }

        String device() {
            try {
                return ((MemorySegment) device.invokeExact(ptr)).reinterpret(512).getString(0);
            } catch (Throwable t) {
                throw AtlasGenNative.rethrow(t);
            }
        }

        private void check(int s, String what) {
            if (s == 0) return;
            String e;
            try {
                e = ((MemorySegment) lastError.invokeExact(ptr)).reinterpret(4096).getString(0);
            } catch (Throwable t) {
                throw AtlasGenNative.rethrow(t);
            }
            throw new IllegalStateException(what + ": " + s + " " + e);
        }

        void setProgram(int[] words) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment w = a.allocateFrom(JAVA_INT, words);
                check((int) setProgram.invokeExact(ptr, w, words.length), "atlas_feat_set_program");
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable t) {
                throw AtlasGenNative.rethrow(t);
            }
        }

        /**
         * One batch (atlas.h atlas_feat_run): slots terrain chunks (ids, aux), origins (ORIGIN_WORDS each), targets
         * (TARGET_WORDS each, may be empty); outIds targets * CHUNK_WORDS; outTables null or origins * TABLE_WORDS.
         */
        Times run(int slots, int[] ids, int[] aux, int origins, int[] org, int targets, int[] tgt, int[] outIds, int[] outTables) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment i = a.allocateFrom(JAVA_INT, ids), x = a.allocateFrom(JAVA_INT, aux), o = a.allocateFrom(JAVA_INT, org);
                MemorySegment t = targets > 0 ? a.allocateFrom(JAVA_INT, tgt) : MemorySegment.NULL;
                MemorySegment out = targets > 0 ? a.allocate(JAVA_INT, (long) targets * CHUNK_WORDS) : MemorySegment.NULL;
                MemorySegment tab = outTables != null ? a.allocate(JAVA_INT, (long) origins * TABLE_WORDS) : MemorySegment.NULL;
                MemorySegment tm = a.allocate(48);
                check((int) run.invokeExact(ptr, slots, i, x, origins, o, targets, t, out, tab, tm), "atlas_feat_run");
                if (targets > 0 && outIds != null) MemorySegment.copy(out, JAVA_INT, 0, outIds, 0, targets * CHUNK_WORDS);
                if (outTables != null) MemorySegment.copy(tab, JAVA_INT, 0, outTables, 0, origins * TABLE_WORDS);
                return Times.of(tm);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable t) {
                throw AtlasGenNative.rethrow(t);
            }
        }

        @Override
        public void close() {
            try {
                destroy.invokeExact(ptr);
            } catch (Throwable t) {
                throw AtlasGenNative.rethrow(t);
            }
        }
    }
}
