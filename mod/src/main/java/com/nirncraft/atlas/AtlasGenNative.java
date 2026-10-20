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
 * The atlas_gen_* calls of atlas_core (atlas.h: the client's world generator), bound on their own so the generator
 * stays apart from the renderer's binding. Null-safe: {@link #load} returns null when the library has no generator.
 */
final class AtlasGenNative {
    static final int BACKEND_CPU = 0, BACKEND_VULKAN = 1;
    /** Words per generated chunk (40 sections of 2048 words: two 16-bit ids each). */
    static final int CHUNK_WORDS = 40 * 2048;

    private final MethodHandle create, destroy, lastError, device, setProgram, pageWords, missing, putPage, chunks, into;

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

        /** Generates the chunks: ids receives count * CHUNK_WORDS words, flags count words (may be null). */
        Times chunks(int[] chunkXZ, int outMode, int[] ids, int[] flags) {
            int n = chunkXZ.length / 2;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment out = a.allocate(JAVA_INT, (long) n * CHUNK_WORDS);
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

        @Override
        public void close() {
            try {
                destroy.invokeExact(ptr);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }
    }
}
