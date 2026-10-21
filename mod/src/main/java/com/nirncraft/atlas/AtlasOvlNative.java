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
 * The atlas_ovl_* calls of atlas_core (atlas.h: structures from the plans stamped on the GPU,
 * native/shaders/atlas_overlays.slang; the data from {@link AtlasOverlayTapes}), bound on their own like
 * {@link AtlasGenNative}. {@link #load} returns null when the library has no overlays.
 */
final class AtlasOvlNative {
    static final int BACKEND_VULKAN = 1;
    static final int SET_BLOCKS = 0, SET_TAPES = 1, SET_STREETS = 2;
    static final int KIND_PIECES = 1, KIND_STREETS = 2, KIND_SWEEPS = 4;

    private final MethodHandle create, destroy, lastError, device, set, run;

    private AtlasOvlNative(SymbolLookup lib) {
        Linker l = Linker.nativeLinker();
        create = bind(l, lib, "atlas_ovl_create", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        destroy = bind(l, lib, "atlas_ovl_destroy", FunctionDescriptor.ofVoid(ADDRESS));
        lastError = bind(l, lib, "atlas_ovl_last_error", FunctionDescriptor.of(ADDRESS, ADDRESS));
        device = bind(l, lib, "atlas_ovl_device", FunctionDescriptor.of(ADDRESS, ADDRESS));
        set = bind(l, lib, "atlas_ovl_set", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        run = bind(l, lib, "atlas_ovl_run", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    }

    private static MethodHandle bind(Linker l, SymbolLookup lib, String name, FunctionDescriptor fd) {
        MemorySegment sym = lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError("atlas_core lacks " + name));
        return l.downcallHandle(sym, fd);
    }

    /** The overlay calls of the library at path, or null. */
    static AtlasOvlNative load(Path library) {
        SymbolLookup lib = SymbolLookup.libraryLookup(library, Arena.global());
        if (lib.find("atlas_ovl_create").isEmpty()) return null;
        return new AtlasOvlNative(lib);
    }

    /** Times of one run (AtlasOvlTimes), milliseconds; pieces, sweeps and streets from GPU timestamps. */
    record Times(double uploadMs, double kernelMs, double piecesMs, double streetsMs, double readbackMs, double totalMs, double sweepsMs) {
        static Times of(MemorySegment t) {
            return new Times(t.get(JAVA_DOUBLE, 0), t.get(JAVA_DOUBLE, 8), t.get(JAVA_DOUBLE, 16), t.get(JAVA_DOUBLE, 24),
                    t.get(JAVA_DOUBLE, 32), t.get(JAVA_DOUBLE, 40), t.get(JAVA_DOUBLE, 48));
        }
    }

    /** An overlay stamper on the Vulkan device, or null when there is none that can run it. */
    Ovl create() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ADDRESS);
            int s = (int) create.invokeExact(BACKEND_VULKAN, out);
            return s == 0 ? new Ovl(out.get(ADDRESS, 0)) : null;
        } catch (Throwable t) {
            throw AtlasGenNative.rethrow(t);
        }
    }

    final class Ovl implements AutoCloseable {
        private final MemorySegment ptr;

        private Ovl(MemorySegment ptr) {
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

        /** Puts the block table (SET_BLOCKS), the tapes (SET_TAPES) or the street plan (SET_STREETS) on the device. */
        void set(int which, int[] words) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment w = a.allocateFrom(JAVA_INT, words);
                check((int) set.invokeExact(ptr, which, w, words.length), "atlas_ovl_set");
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable t) {
                throw AtlasGenNative.rethrow(t);
            }
        }

        /**
         * Stamps count chunks in place (atlas.h atlas_ovl_run): chunks AtlasOverlayTapes.CHUNK_INTS ints each, ids
         * count * levels * 128 words (two 16-bit table ids a word), kinds KIND_PIECES | KIND_SWEEPS | KIND_STREETS.
         */
        Times run(int count, int[] chunks, int levels, int kinds, int[] ids) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment c = a.allocateFrom(JAVA_INT, chunks), i = a.allocateFrom(JAVA_INT, ids);
                MemorySegment tm = a.allocate(56);
                check((int) run.invokeExact(ptr, count, c, levels, kinds, i, tm), "atlas_ovl_run");
                MemorySegment.copy(i, JAVA_INT, 0, ids, 0, count * levels * 128);
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
