package com.skycraft.aether;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Java 25 binding of aether_core (native/include/aether/aether.h) through {@code java.lang.foreign}: no JNI glue and
 * no Minecraft types, so the same class serves the standalone probe and the mod. Struct offsets mirror the C
 * static_asserts in native/src/api.cpp; a mismatch there is an ABI bump on both sides.
 *
 * <p>Short calls that only copy data in (tile/section uploads, block edits, version) are bound as critical with
 * heap access, so Java arrays are passed in place without an off-heap copy; frame rendering is a normal downcall.</p>
 */
public final class AetherNative {
    /** The ABI this binding is written for; libraries from ABI_MIN on load too (0.10 only adds calls to 0.9). */
    public static final int ABI_VERSION = 0x000A00, ABI_MIN = 0x000900;
    public static final int BACKEND_CPU = 0, BACKEND_VULKAN = 1;
    /** AetherStatus (aether.h). */
    public static final int OK = 0, ERR_ARG = -1, ERR_STATE = -2, ERR_IO = -3, ERR_GPU = -4, ERR_UNSUPPORTED = -5,
            ERR_DEVICE_LOST = -6, ERR_OUT_OF_MEMORY = -7, ERR_TIMEOUT = -8, ERR_INTERNAL = -9;
    /** aether_debug_fault kinds. */
    public static final int FAULT_NONE = 0, FAULT_DEVICE_LOST = 1, FAULT_OUT_OF_MEMORY = 2, FAULT_TIMEOUT = 3;
    public static final int FRAME_CLOUDS = 0x1, FRAME_SHADOWS = 0x2, FRAME_ATMOS = 0x4, FRAME_ACTORS = 0x8,
            FRAME_SKY = 0x10, FRAME_NEAR_DEPTH = 0x20, FRAME_FLAT_NORMALS = 0x40, FRAME_CLOUDS_QUARTER = 0x80,
            FRAME_DEBUG_LOD = 0x100, FRAME_DEBUG_WEATHER = 0x200;
    public static final int CLASS_TERRAIN = 0, CLASS_FOLIAGE = 1, CLASS_WATER = 2, CLASS_EMISSIVE = 3;
    public static final int TILE = 256;

    // struct sizes and offsets (see api.cpp)
    static final long MATERIAL_SIZE = 24, TILE_SIZE = 64, FRAME_SIZE = 144, OUTPUT_SIZE = 32, WEATHER_SIZE = 40,
            ACTOR_SIZE = 48, STATS_SIZE = 56, GL_TARGETS_SIZE = 24, GL_IO_SIZE = 96, MEMORY_INFO_SIZE = 48;

    /**
     * A failed aether call, with its AetherStatus. {@link #deviceLost()}: the GPU was reset or hung (the context is
     * dead; make a new one after a cool-down); {@link #outOfMemory()}: retry smaller.
     */
    public static final class AetherException extends IllegalStateException {
        public final int status;

        public AetherException(int status, String message) {
            super(message);
            this.status = status;
        }

        public boolean deviceLost() {
            return status == ERR_DEVICE_LOST || status == ERR_TIMEOUT;
        }

        public boolean outOfMemory() {
            return status == ERR_OUT_OF_MEMORY;
        }

        public static String statusName(int s) {
            return switch (s) {
                case OK -> "ok";
                case ERR_ARG -> "bad argument";
                case ERR_STATE -> "bad state";
                case ERR_IO -> "i/o error";
                case ERR_GPU -> "GPU error";
                case ERR_UNSUPPORTED -> "unsupported";
                case ERR_DEVICE_LOST -> "device lost";
                case ERR_OUT_OF_MEMORY -> "out of memory";
                case ERR_TIMEOUT -> "GPU timeout";
                case ERR_INTERNAL -> "internal error";
                default -> "status " + s;
            };
        }
    }

    /**
     * Java refused this module a restricted method (java.lang.foreign needs native access: --enable-native-access,
     * or the launcher's module layer granting it). Carries the line to log.
     */
    public static final class NativeAccessDenied extends RuntimeException {
        public NativeAccessDenied(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The JVM argument that grants this class's module native access (ALL-UNNAMED for the class path). */
    public static String enableNativeAccessArg() {
        Module m = AetherNative.class.getModule();
        return "--enable-native-access=" + (m.isNamed() ? m.getName() : "ALL-UNNAMED");
    }

    /**
     * Null when this module may call restricted methods without a warning; else one actionable line (Java 25 still
     * allows the calls with a warning unless --illegal-native-access=deny; later releases will refuse them).
     */
    public static String nativeAccessAdvice() {
        Module m = AetherNative.class.getModule();
        if (m.isNativeAccessEnabled()) return null;
        return "native access is not enabled for " + (m.isNamed() ? "module " + m.getName() : "the class path")
                + " (add the JVM argument " + enableNativeAccessArg() + "; Java 25 only warns, later releases will refuse)";
    }

    static NativeAccessDenied denied(IllegalCallerException e) {
        return new NativeAccessDenied("Java denied native access (" + e.getMessage() + "): start the game with the JVM "
                + "argument " + enableNativeAccessArg() + " (Prism: Edit instance > Settings > Java > JVM arguments; "
                + "Skycraft's Prism instance zip sets it; a .mrpack cannot, add it in the launcher)", e);
    }

    private final Arena libArena;
    private final MethodHandle abiVersion, buildInfo, create, destroy, lastError, setPalette, uploadTile,
            uploadSection, notifyBlock, setWeather, setCloudNoise, setActors, update, render, getStats,
            glAttach, glRenderIo, glDetach, info, previewLoadRaster, previewGenerate, previewHeight, uploadHeights, uploadHeights2, hasTile, uploadStructures, setMaterials, setSunMap,
            heightCreate, heightDestroy, heightLastError, heightDevice, heightSetNoise, heightBatch;
    // abi 0.10 (null with a 0.9 library)
    private final MethodHandle memoryInfo, setClipmap, debugFault;
    private int abi;

    private AetherNative(Path library) {
        libArena = Arena.ofShared();
        SymbolLookup lib;
        try {
            lib = SymbolLookup.libraryLookup(library, libArena);
        } catch (IllegalCallerException e) {
            libArena.close();
            throw denied(e);
        } catch (IllegalArgumentException e) {
            libArena.close();
            // the library would not load (wrong architecture, a missing dependency, a noexec mount)
            throw new UnsatisfiedLinkError("aether_core does not load from " + library + ": " + e.getMessage());
        }
        Linker l = Linker.nativeLinker();
        Linker.Option critical = Linker.Option.critical(true);
        abiVersion = bind(l, lib, "aether_abi_version", FunctionDescriptor.of(JAVA_INT), critical);
        buildInfo = bind(l, lib, "aether_build_info", FunctionDescriptor.of(ADDRESS));
        create = bind(l, lib, "aether_create", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        destroy = bind(l, lib, "aether_destroy", FunctionDescriptor.ofVoid(ADDRESS));
        lastError = bind(l, lib, "aether_last_error", FunctionDescriptor.of(ADDRESS, ADDRESS));
        setPalette = bind(l, lib, "aether_set_palette", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        uploadTile = bind(l, lib, "aether_upload_column_tile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        uploadSection = bind(l, lib, "aether_upload_section",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS), critical);
        notifyBlock = bind(l, lib, "aether_notify_block",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_SHORT), critical);
        setWeather = bind(l, lib, "aether_set_weather", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        setCloudNoise = bind(l, lib, "aether_set_cloud_noise", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), critical);
        setActors = bind(l, lib, "aether_set_actors", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        update = bind(l, lib, "aether_update", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_DOUBLE));
        render = bind(l, lib, "aether_render", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        getStats = bind(l, lib, "aether_get_stats", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        glAttach = bind(l, lib, "aether_gl_attach", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        glRenderIo = bind(l, lib, "aether_gl_render_io", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        glDetach = bind(l, lib, "aether_gl_detach", FunctionDescriptor.ofVoid(ADDRESS));
        info = bind(l, lib, "aether_info", FunctionDescriptor.of(ADDRESS, ADDRESS));
        previewLoadRaster = bind(l, lib, "aether_preview_load_raster", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        previewGenerate = bind(l, lib, "aether_preview_generate",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        previewHeight = bind(l, lib, "aether_preview_height",
                FunctionDescriptor.of(JAVA_FLOAT, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE));
        uploadHeights = bind(l, lib, "aether_upload_column_heights",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        uploadHeights2 = bind(l, lib, "aether_upload_column_heights2",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        hasTile = bind(l, lib, "aether_has_tile", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT),
                critical);
        uploadStructures = bind(l, lib, "aether_upload_column_structures",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        setSunMap = bind(l, lib, "aether_set_sun_map", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_FLOAT));
        setMaterials = bind(l, lib, "aether_set_materials", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        heightCreate = bind(l, lib, "aether_height_create", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        heightDestroy = bind(l, lib, "aether_height_destroy", FunctionDescriptor.ofVoid(ADDRESS));
        heightLastError = bind(l, lib, "aether_height_last_error", FunctionDescriptor.of(ADDRESS, ADDRESS));
        heightDevice = bind(l, lib, "aether_height_device", FunctionDescriptor.of(ADDRESS, ADDRESS));
        heightSetNoise = bind(l, lib, "aether_height_set_noise", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        heightBatch = bind(l, lib, "aether_height_batch", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT,
                JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        memoryInfo = bindOptional(l, lib, "aether_memory_info", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        setClipmap = bindOptional(l, lib, "aether_set_clipmap", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        debugFault = bindOptional(l, lib, "aether_debug_fault", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    }

    private static MethodHandle bindOptional(Linker l, SymbolLookup lib, String name, FunctionDescriptor fd) {
        return lib.find(name).map(s -> l.downcallHandle(s, fd)).orElse(null);
    }

    /** The loaded library's ABI version. */
    public int abi() {
        return abi;
    }

    /** 32-bit words per noise layer, and noise calls (slots) per column, of the exact-noise kernel (aether.h). */
    public static final int HEIGHT_LAYER_WORDS = 76, HEIGHT_SLOTS = 10;

    /** Times of one height batch (AetherHeightTimes), milliseconds. */
    public record HeightTimes(double uploadMs, double kernelMs, double readbackMs, double totalMs) {}

    /**
     * An exact-noise engine (aether_height_create): the kernel on its own Vulkan compute device, or its C++
     * reference. Null when the backend is unavailable (no device with what the kernel's exactness needs).
     */
    public Height createHeight(int backend) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ADDRESS);
            int s = (int) heightCreate.invokeExact(backend, out);
            return s == 0 ? new Height(out.get(ADDRESS, 0)) : null;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** One aether_height handle; calls serialise natively, so any thread may use it. */
    public final class Height implements AutoCloseable {
        private final MemorySegment ptr;

        private Height(MemorySegment ptr) {
            this.ptr = ptr;
        }

        public String device() {
            try {
                return ((MemorySegment) heightDevice.invokeExact(ptr)).reinterpret(512).getString(0);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        private String error() {
            try {
                return ((MemorySegment) heightLastError.invokeExact(ptr)).reinterpret(4096).getString(0);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        public void setNoise(int[] table) {
            try (Arena a = Arena.ofConfined()) {
                int s = (int) heightSetNoise.invokeExact(ptr, copy(a, table), table.length);
                if (s != 0) throw new IllegalStateException("aether_height_set_noise: " + s + " " + error());
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * count columns, column k at block (x0 + (k % width) * step, z0 + (k / width) * step); slots: count ints
         * (bit s: evaluate noise call s of column k), values receives HEIGHT_SLOTS * count floats (slot-major), flags
         * count ints (1: the kernel could not prove a value of that column exact). Off-heap segments the caller owns.
         */
        public HeightTimes batch(int x0, int z0, int step, int width, int count, MemorySegment slots,
                                 MemorySegment values, MemorySegment flags) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment t = a.allocate(32, 8);
                int s = (int) heightBatch.invokeExact(ptr, x0, z0, step, width, count, slots, values, flags, t);
                if (s != 0) throw new IllegalStateException("aether_height_batch: " + s + " " + error());
                return new HeightTimes(t.get(JAVA_DOUBLE, 0), t.get(JAVA_DOUBLE, 8), t.get(JAVA_DOUBLE, 16),
                        t.get(JAVA_DOUBLE, 24));
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        @Override
        public void close() {
            try {
                heightDestroy.invokeExact(ptr);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }
    }

    private static MethodHandle bind(Linker l, SymbolLookup lib, String name, FunctionDescriptor fd, Linker.Option... opts) {
        MemorySegment sym = lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError("aether_core lacks " + name));
        return l.downcallHandle(sym, fd, opts);
    }

    /**
     * Loads the library and checks its ABI version (ABI_MIN .. ABI_VERSION). Throws {@link NativeAccessDenied} when
     * Java refuses this module native access, UnsatisfiedLinkError when the file is not a usable aether_core.
     */
    public static AetherNative load(Path library) {
        AetherNative n;
        try {
            n = new AetherNative(library);
        } catch (IllegalCallerException e) {
            throw denied(e);  // Linker.downcallHandle is restricted too
        }
        int v = n.abiVersion();
        if (v < ABI_MIN || v > ABI_VERSION) {
            throw new UnsatisfiedLinkError("aether_core ABI " + abiString(v) + ", this binding drives "
                    + abiString(ABI_MIN) + " .. " + abiString(ABI_VERSION));
        }
        n.abi = v;
        return n;
    }

    public static String abiString(int v) {
        return (v >> 16 & 255) + "." + (v >> 8 & 255) + "." + (v & 255);
    }

    public int abiVersion() {
        try {
            return (int) abiVersion.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public String buildInfo() {
        try {
            return ((MemorySegment) buildInfo.invokeExact()).reinterpret(512).getString(0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public Context create(int backend) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ADDRESS);
            int s = (int) create.invokeExact(backend, out);
            if (s != 0) {
                // no context to ask: aether_last_error(NULL) holds why (e.g. every Vulkan device and its reason)
                String why = ((MemorySegment) lastError.invokeExact(MemorySegment.NULL)).reinterpret(4096).getString(0);
                throw new AetherException(s, "aether_create(" + backend + ") failed: " + AetherException.statusName(s)
                        + " (" + why + ")");
            }
            return new Context(out.get(ADDRESS, 0));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new RuntimeException(t);
    }

    /** A camera frame (world block coordinates, camera-relative inverse view-projection, column-major). */
    public static final class Frame {
        public double x, y, z;
        public final float[] invViewProj = new float[16];
        public int width, height;
        public float nearFieldEnd, maxDistance = 40000, planetRadius = 6_371_000f;
        public float sunX, sunY = 1, sunZ, timeSec, lodBias = 1;
        public int flags = FRAME_SHADOWS | FRAME_ATMOS | FRAME_CLOUDS | FRAME_SKY;

        void write(MemorySegment s) {
            s.set(JAVA_DOUBLE, 0, x);
            s.set(JAVA_DOUBLE, 8, y);
            s.set(JAVA_DOUBLE, 16, z);
            MemorySegment.copy(invViewProj, 0, s, JAVA_FLOAT, 24, 16);
            s.set(JAVA_INT, 88, width);
            s.set(JAVA_INT, 92, height);
            s.set(JAVA_FLOAT, 96, nearFieldEnd);
            s.set(JAVA_FLOAT, 100, maxDistance);
            s.set(JAVA_FLOAT, 104, planetRadius);
            s.set(JAVA_FLOAT, 108, sunX);
            s.set(JAVA_FLOAT, 112, sunY);
            s.set(JAVA_FLOAT, 116, sunZ);
            s.set(JAVA_FLOAT, 120, timeSec);
            s.set(JAVA_FLOAT, 124, lodBias);
            s.set(JAVA_INT, 128, flags);
            s.set(ADDRESS, 136, MemorySegment.NULL);  // nearDepth: the game path uses the shared GL image
        }
    }

    public record Stats(int regions, long nodeBytes, long leafBytes, long materialBytes, double buildMs, double frameMs,
                        long raySteps) {}

    /** aether_memory_info (abi 0.10): bytes; budget / usage 0 when the driver lacks VK_EXT_memory_budget. */
    public record MemoryInfo(long deviceLocal, long budget, long usage, long hostHeap, long aether, boolean integrated,
                             boolean budgetExt) {}

    /** GL texture names of the images shared with the game's GL context (rows bottom-up). */
    public record GlTargets(int color, int normal, int depth, int nearDepth, int width, int height) {}

    /** The game's side of a GL frame (see AetherGlFrameIO in aether.h); zero fields are unused. */
    public static final class GlFrameIO {
        public static final int BOUND_DEPTH = 0xFFFFFFFF, DEPTH_AUTO = 0xFFFFFFFF;
        public int nearDepthTex;
        /** 1 = [0,1] clip depth, 0 = [-1,1], DEPTH_AUTO = ask the GL context. */
        public int depthZeroToOne = DEPTH_AUTO;
        public boolean reversedZ;
        public final float[] nearInvProjection = new float[16];
        public int dstColor, dstNormal, dstDepth;
        /** RG32F, sunMapSize x sunMapSize + 1 (Context.setSunMap), or 0. */
        public int dstSunMap;

        void write(MemorySegment s) {
            s.set(JAVA_INT, 0, nearDepthTex);
            s.set(JAVA_INT, 4, depthZeroToOne);
            s.set(JAVA_INT, 8, reversedZ ? 1 : 0);
            s.set(JAVA_INT, 12, 0);
            MemorySegment.copy(nearInvProjection, 0, s, JAVA_FLOAT, 16, 16);
            s.set(JAVA_INT, 80, dstColor);
            s.set(JAVA_INT, 84, dstNormal);
            s.set(JAVA_INT, 88, dstDepth);
            s.set(JAVA_INT, 92, dstSunMap);
        }
    }

    /** One aether_core context. Not thread-safe except the upload methods (which the native side locks). */
    public final class Context implements AutoCloseable {
        private final MemorySegment ptr;
        private final Arena frameArena = Arena.ofShared();
        private MemorySegment frameSeg, outSeg, colorSeg, normalSeg, depthSeg, ioSeg;
        private int outPixels = -1;

        Context(MemorySegment ptr) {
            this.ptr = ptr;
            frameSeg = frameArena.allocate(FRAME_SIZE, 8);
            outSeg = frameArena.allocate(OUTPUT_SIZE, 8);
        }

        public String lastError() {
            try {
                return ((MemorySegment) lastError.invokeExact(ptr)).reinterpret(4096).getString(0);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        private void check(int status, String what) {
            if (status != 0) {
                throw new AetherException(status, what + " failed (" + AetherException.statusName(status) + "): "
                        + lastError());
            }
        }

        /** The device's memory (abi 0.10), or null (a 0.9 library, the CPU backend, a lost device). */
        public MemoryInfo memoryInfo() {
            if (memoryInfo == null) return null;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment m = a.allocate(MEMORY_INFO_SIZE, 8);
                int s = (int) memoryInfo.invokeExact(ptr, m);
                if (s != 0) return null;
                return new MemoryInfo(m.get(JAVA_LONG, 0), m.get(JAVA_LONG, 8), m.get(JAVA_LONG, 16), m.get(JAVA_LONG, 24),
                        m.get(JAVA_LONG, 32), m.get(JAVA_INT, 40) != 0, (m.get(JAVA_INT, 44) & 1) != 0);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Clipmap levels kept (abi 0.10; before the first update). False with a 0.9 library (it keeps 7). */
        public boolean setClipmap(int lodCount) {
            if (setClipmap == null) return false;
            try {
                check((int) setClipmap.invokeExact(ptr, lodCount), "aether_set_clipmap");
                return true;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Test hook (abi 0.10): fail as the driver would after afterFrames frames. False with a 0.9 library. */
        public boolean debugFault(int fault, int afterFrames) {
            if (debugFault == null) return false;
            try {
                check((int) debugFault.invokeExact(ptr, fault, afterFrames), "aether_debug_fault");
                return true;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Palette ids 1..n: albedo linear rgb, roughness, emissive, class. */
        public void setPalette(float[][] materials) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment m = a.allocate(MATERIAL_SIZE * materials.length, 8);
                for (int i = 0; i < materials.length; i++) {
                    float[] v = materials[i];
                    long o = i * MATERIAL_SIZE;
                    for (int k = 0; k < 5; k++) m.set(JAVA_FLOAT, o + 4L * k, v[k]);
                    m.set(JAVA_INT, o + 20, (int) v[5]);
                }
                check((int) setPalette.invokeExact(ptr, m, materials.length), "aether_set_palette");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * The sun map (aether.h aether_set_sun_map): size x size along the key light over halfExtent blocks around
         * the camera, for the pack's far shadows (mountains, clouds) on the near field; size 0 turns it off.
         */
        public void setSunMap(int size, float halfExtent) {
            try {
                check((int) setSunMap.invokeExact(ptr, size, halfExtent), "aether_set_sun_map");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Palette entries first .. first + n - 1 (first >= 1), same {r, g, b, roughness, emissive, class} rows. */
        public void setMaterials(int first, float[][] materials) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment m = a.allocate(MATERIAL_SIZE * Math.max(1, materials.length), 8);
                for (int i = 0; i < materials.length; i++) {
                    float[] v = materials[i];
                    long o = i * MATERIAL_SIZE;
                    for (int k = 0; k < 5; k++) m.set(JAVA_FLOAT, o + 4L * k, v[k]);
                    m.set(JAVA_INT, o + 20, (int) v[5]);
                }
                check((int) setMaterials.invokeExact(ptr, first, m, materials.length), "aether_set_materials");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * Buildings over one tile (see aether.h): walls from the ground to wallTop, roof to roofTop (NaN = none),
         * row-major [z][x]. wallTop == null removes the tile's buildings. Copied before this returns.
         */
        public void uploadColumnStructures(int lod, int tileX, int tileZ, float[] wallTop, float[] roofTop, short[] wallMat,
                                           short[] roofMat) {
            try (Arena a = Arena.ofConfined()) {
                if (wallTop == null) {
                    check((int) uploadStructures.invokeExact(ptr, lod, tileX, tileZ, MemorySegment.NULL, MemorySegment.NULL,
                            MemorySegment.NULL, MemorySegment.NULL), "aether_upload_column_structures");
                    return;
                }
                int n = TILE * TILE;
                if (wallTop.length < n || roofTop.length < n || wallMat.length < n || roofMat.length < n)
                    throw new IllegalArgumentException("structure arrays must hold 256*256 columns");
                check((int) uploadStructures.invokeExact(ptr, lod, tileX, tileZ, copy(a, wallTop), copy(a, roofTop),
                        copy(a, wallMat), copy(a, roofMat)), "aether_upload_column_structures");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * Uploads one authoritative column tile (256 x 256 columns, row-major [z][x], see aether.h). The arrays are
         * copied before this returns. canopy/canopyMat may be null.
         */
        public void uploadColumnTile(int lod, int tileX, int tileZ, float[] surface, float[] water, short[] topMat,
                                     short[] fillMat, float[] canopy, short[] canopyMat) {
            int n = TILE * TILE;
            if (surface.length < n || water.length < n || topMat.length < n || fillMat.length < n)
                throw new IllegalArgumentException("tile arrays must hold 256*256 columns");
            try (Arena a = Arena.ofConfined()) {
                MemorySegment t = a.allocate(TILE_SIZE, 8);
                t.set(JAVA_INT, 0, lod);
                t.set(JAVA_INT, 4, tileX);
                t.set(JAVA_INT, 8, tileZ);
                t.set(ADDRESS, 16, copy(a, surface));
                t.set(ADDRESS, 24, copy(a, water));
                t.set(ADDRESS, 32, copy(a, topMat));
                t.set(ADDRESS, 40, copy(a, fillMat));
                t.set(ADDRESS, 48, canopy != null ? copy(a, canopy) : MemorySegment.NULL);
                t.set(ADDRESS, 56, canopyMat != null ? copy(a, canopyMat) : MemorySegment.NULL);
                check((int) uploadTile.invokeExact(ptr, t), "aether_upload_column_tile");
            } catch (Throwable th) {
                throw rethrow(th);
            }
        }

        /**
         * Exact geometry, preview dressing: surface and water heights (NaN = no water) of one 256 x 256 tile, row-major
         * [z][x] as in uploadColumnTile, e.g. from the game's terrain height function; aether picks materials and
         * forest canopy from the loaded preview raster. Needs previewLoadRaster. Copied before this returns.
         */
        public void uploadColumnHeights(int lod, int tileX, int tileZ, float[] surface, float[] water) {
            if (surface.length < TILE * TILE || water.length < TILE * TILE)
                throw new IllegalArgumentException("tile arrays must hold 256*256 columns");
            try (Arena a = Arena.ofConfined()) {
                check((int) uploadHeights.invokeExact(ptr, lod, tileX, tileZ, copy(a, surface), copy(a, water)),
                        "aether_upload_column_heights");
            } catch (Throwable th) {
                throw rethrow(th);
            }
        }

        /**
         * As uploadColumnHeights, plus per-column palette ids (0 = the dressing's own) for grass-family tops and
         * broadleaf canopy: the biome's tinted grass and foliage. grassMat / foliageMat may be null.
         */
        public void uploadColumnHeights2(int lod, int tileX, int tileZ, float[] surface, float[] water, short[] grassMat,
                                         short[] foliageMat) {
            if (surface.length < TILE * TILE || water.length < TILE * TILE)
                throw new IllegalArgumentException("tile arrays must hold 256*256 columns");
            try (Arena a = Arena.ofConfined()) {
                // (typed locals: invokeExact needs the exact static types, a ternary would widen them)
                MemorySegment g = grassMat != null ? copy(a, grassMat) : MemorySegment.NULL;
                MemorySegment f = foliageMat != null ? copy(a, foliageMat) : MemorySegment.NULL;
                check((int) uploadHeights2.invokeExact(ptr, lod, tileX, tileZ, copy(a, surface), copy(a, water), g, f),
                        "aether_upload_column_heights2");
            } catch (Throwable th) {
                throw rethrow(th);
            }
        }

        /** True if tile (lod, tileX, tileZ) is resident (uploaded and not evicted). */
        public boolean hasTile(int lod, int tileX, int tileZ) {
            try {
                return (int) hasTile.invokeExact(ptr, lod, tileX, tileZ) != 0;
            } catch (Throwable th) {
                throw rethrow(th);
            }
        }

        /** A 16^3 chunk section of palette ids, index x + 16 * (z + 16 * y). Passed in place (critical call). */
        public void uploadSection(int sx, int sy, int sz, short[] mats) {
            try {
                check((int) uploadSection.invokeExact(ptr, sx, sy, sz, MemorySegment.ofArray(mats)), "aether_upload_section");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        public void notifyBlock(int x, int y, int z, short mat) {
            try {
                check((int) notifyBlock.invokeExact(ptr, x, y, z, mat), "aether_notify_block");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** WeatherFieldTexture's four RGBA8 128x128 fields (r in the low byte), and the vanilla rain strength. */
        public void setWeather(int[] nearField, int[] nearDyn, int[] farField, int[] farDyn, float rain) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment w = a.allocate(WEATHER_SIZE, 8);
                w.set(ADDRESS, 0, copy(a, nearField));
                w.set(ADDRESS, 8, copy(a, nearDyn));
                w.set(ADDRESS, 16, copy(a, farField));
                w.set(ADDRESS, 24, copy(a, farDyn));
                w.set(JAVA_FLOAT, 32, rain);
                check((int) setWeather.invokeExact(ptr, w), "aether_set_weather");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** The 64^3 RGBA8 cloud noise volume, index (z * 64 + y) * 64 + x. */
        public void setCloudNoise(int[] rgba) {
            if (rgba.length < 64 * 64 * 64) throw new IllegalArgumentException("need 64^3 texels");
            try {
                check((int) setCloudNoise.invokeExact(ptr, MemorySegment.ofArray(rgba)), "aether_set_cloud_noise");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Far actors: x, y, z, yaw, height, radius, kind, tint per actor. */
        public void setActors(double[] xyz, float[] yawHeightRadius, int[] kindTint, int count) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = a.allocate(Math.max(1, count) * ACTOR_SIZE, 8);
                for (int i = 0; i < count; i++) {
                    long o = i * ACTOR_SIZE;
                    s.set(JAVA_DOUBLE, o, xyz[3 * i]);
                    s.set(JAVA_DOUBLE, o + 8, xyz[3 * i + 1]);
                    s.set(JAVA_DOUBLE, o + 16, xyz[3 * i + 2]);
                    s.set(JAVA_FLOAT, o + 24, yawHeightRadius[3 * i]);
                    s.set(JAVA_FLOAT, o + 28, yawHeightRadius[3 * i + 1]);
                    s.set(JAVA_FLOAT, o + 32, yawHeightRadius[3 * i + 2]);
                    s.set(JAVA_INT, o + 36, kindTint[2 * i]);
                    s.set(JAVA_INT, o + 40, kindTint[2 * i + 1]);
                }
                check((int) setActors.invokeExact(ptr, s, count), "aether_set_actors");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Rebuilds dirty regions of the clipmap around the camera; budgetMs <= 0 builds everything. */
        public void update(double x, double y, double z, double budgetMs) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment c = a.allocate(24, 8);
                c.set(JAVA_DOUBLE, 0, x);
                c.set(JAVA_DOUBLE, 8, y);
                c.set(JAVA_DOUBLE, 16, z);
                check((int) update.invokeExact(ptr, c, budgetMs), "aether_update");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Renders into host arrays (harness path). Any array may be null. */
        public void render(Frame f, int[] color, int[] normal, float[] depth) {
            int px = f.width * f.height;
            if (px != outPixels) {
                colorSeg = frameArena.allocate(4L * px, 8);
                normalSeg = frameArena.allocate(4L * px, 8);
                depthSeg = frameArena.allocate(4L * px, 8);
                outPixels = px;
            }
            f.write(frameSeg);
            outSeg.set(ADDRESS, 0, color != null ? colorSeg : MemorySegment.NULL);
            outSeg.set(ADDRESS, 8, normal != null ? normalSeg : MemorySegment.NULL);
            outSeg.set(ADDRESS, 16, depth != null ? depthSeg : MemorySegment.NULL);
            outSeg.set(ADDRESS, 24, MemorySegment.NULL);  // colorLinear: not needed by the harness
            try {
                check((int) render.invokeExact(ptr, frameSeg, outSeg), "aether_render");
            } catch (Throwable t) {
                throw rethrow(t);
            }
            if (color != null) MemorySegment.copy(colorSeg, JAVA_INT, 0, color, 0, px);
            if (normal != null) MemorySegment.copy(normalSeg, JAVA_INT, 0, normal, 0, px);
            if (depth != null) MemorySegment.copy(depthSeg, JAVA_FLOAT, 0, depth, 0, px);
        }

        public Stats stats() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = a.allocate(STATS_SIZE, 8);
                check((int) getStats.invokeExact(ptr, s), "aether_get_stats");
                return new Stats(s.get(JAVA_INT, 0), s.get(JAVA_LONG, 8), s.get(JAVA_LONG, 16), s.get(JAVA_LONG, 24),
                        s.get(JAVA_DOUBLE, 32), s.get(JAVA_DOUBLE, 40), s.get(JAVA_LONG, 48));
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        // ---- game path: render thread, the game's GL context current ----

        /** Creates the shared images (re-attach on resize). */
        public GlTargets glAttach(int width, int height) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment t = a.allocate(GL_TARGETS_SIZE, 4);
                check((int) glAttach.invokeExact(ptr, width, height, t), "aether_gl_attach");
                return new GlTargets(t.get(JAVA_INT, 0), t.get(JAVA_INT, 4), t.get(JAVA_INT, 8), t.get(JAVA_INT, 12),
                        t.get(JAVA_INT, 16), t.get(JAVA_INT, 20));
            } catch (Throwable th) {
                throw rethrow(th);
            }
        }

        /** Queues the frame; GL commands issued afterwards see it. The CPU does not wait for the GPU. */
        public void glRender(Frame f, GlFrameIO io) {
            f.write(frameSeg);
            if (ioSeg == null) ioSeg = frameArena.allocate(GL_IO_SIZE, 8);
            if (io != null) io.write(ioSeg);
            try {
                // a typed local: a conditional passed straight to invokeExact is typed Object (its target), and the
                // exact-type check then fails on every frame
                MemorySegment ioArg = io != null ? ioSeg : MemorySegment.NULL;
                check((int) glRenderIo.invokeExact(ptr, frameSeg, ioArg), "aether_gl_render_io");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        public void glDetach() {
            try {
                glDetach.invokeExact(ptr);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * One line of JSON for the log (abi 0.8): device, driver, Vulkan version, and after glAttach the interop mode
         * ("zero-copy (win32 handles)", "zero-copy (opaque fds)" or "host-copy" with interopReason and its cost).
         */
        public String info() {
            try {
                return ((MemorySegment) info.invokeExact(ptr)).reinterpret(1 << 16).getString(0);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        // ---- offline harness only ----

        public void previewLoadRaster(Path raster) {
            try (Arena a = Arena.ofConfined()) {
                check((int) previewLoadRaster.invokeExact(ptr, a.allocateFrom(raster.toString())), "aether_preview_load_raster");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        public void previewGenerate(int lod, int x0, int z0, int x1, int z1) {
            try {
                check((int) previewGenerate.invokeExact(ptr, lod, x0, z0, x1, z1), "aether_preview_generate");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        public float previewHeight(double x, double z) {
            try {
                return (float) previewHeight.invokeExact(ptr, x, z);
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
            } finally {
                frameArena.close();
            }
        }
    }

    private static MemorySegment copy(Arena a, float[] v) {
        return a.allocateFrom(ValueLayout.JAVA_FLOAT, v);
    }

    private static MemorySegment copy(Arena a, short[] v) {
        return a.allocateFrom(ValueLayout.JAVA_SHORT, v);
    }

    private static MemorySegment copy(Arena a, int[] v) {
        return a.allocateFrom(ValueLayout.JAVA_INT, v);
    }
}
