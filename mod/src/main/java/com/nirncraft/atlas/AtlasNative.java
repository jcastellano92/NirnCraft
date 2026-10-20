package com.nirncraft.atlas;

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
 * Java 25 binding of atlas_core (native/include/atlas/atlas.h) through {@code java.lang.foreign}: no JNI glue and
 * no Minecraft types, so the same class serves the standalone probe and the mod. Struct offsets mirror the C
 * static_asserts in native/src/api.cpp; a mismatch there is an ABI bump on both sides.
 *
 * <p>Short calls that only copy data in (tile/section uploads, block edits, version) are bound as critical with
 * heap access, so Java arrays are passed in place without an off-heap copy; frame rendering is a normal downcall.</p>
 */
public final class AtlasNative {
    /** The ABI this binding is written for (0.11: far content + failure handling); the mod ships matching natives. */
    /** (abi 0.13) Structures that are a tile's real trees: its dressing's canopy goes when they go up. */
    public static final int STRUCTURES_REPLACE_CANOPY = 0x1;
    // 0.12: atlas_set_moon, 0.13: atlas_set_jitter, 0.14: atlas_set_material_shapes + FRAME_GROUPS, 0.15: the near
    // light (FRAME_NEAR_LIGHT, GlFrameIO.nearInfoTex / dstNearLight), 0.16: the air (atlas_set_air, atlas_air_*) and
    // the near field's textures (atlas_set_material_textures), 0.17: the sky's own light (atlas_set_sky_glow) (all optional)
    public static final int ABI_VERSION = 0x001100, ABI_MIN = 0x000B00;
    /** The first ABI with the near light (FRAME_NEAR_LIGHT); an older core is never handed the flag or its fields. */
    public static final int ABI_NEAR_LIGHT = 0x000F00;
    public static final int BACKEND_CPU = 0, BACKEND_VULKAN = 1;
    /** AtlasStatus (atlas.h). */
    public static final int OK = 0, ERR_ARG = -1, ERR_STATE = -2, ERR_IO = -3, ERR_GPU = -4, ERR_UNSUPPORTED = -5,
            ERR_DEVICE_LOST = -6, ERR_OUT_OF_MEMORY = -7, ERR_TIMEOUT = -8, ERR_INTERNAL = -9;
    /** atlas_debug_fault kinds. */
    public static final int FAULT_NONE = 0, FAULT_DEVICE_LOST = 1, FAULT_OUT_OF_MEMORY = 2, FAULT_TIMEOUT = 3;
    public static final int FRAME_CLOUDS = 0x1, FRAME_SHADOWS = 0x2, FRAME_ATMOS = 0x4, FRAME_ACTORS = 0x8,
            FRAME_SKY = 0x10, FRAME_NEAR_DEPTH = 0x20, FRAME_FLAT_NORMALS = 0x40, FRAME_CLOUDS_QUARTER = 0x80,
            FRAME_DEBUG_LOD = 0x100, FRAME_DEBUG_WEATHER = 0x200, FRAME_LOD_DITHER = 0x1000,
            FRAME_FILL_NEAR = 0x2000,  // (abi 0.12) the far field fills what the near field has not loaded yet
            FRAME_VANILLA_LIGHT = 0x10000,  // (abi 0.14, optional) Minecraft's own light, no pack (an older core ignores it)
            FRAME_GROUPS = 0x4000,     // (abi 0.14) primary rays trace the content-shaped groups (an older core ignores it)
            FRAME_LIGHT_LOW = 0x8000,  // (abi 0.14, optional) the far field's cheap light, near light included (atlas.h)
            // (abi 0.15, optional) atlas lights the near field's covered pixels with the far light (atlas.h
            // ATLAS_FRAME_NEAR_LIGHT): GlFrameIO.nearInfoTex in, dstNearLight out; the binding drops it for older cores
            FRAME_NEAR_LIGHT = 0x20000;
    /** (abi 0.14) AtlasMaterialShape.flags: texel alpha is coverage (cut-out leaves). */
    public static final int SHAPE_CUTOUT = 0x1;
    /** Far actor gaits (AtlasActor.flags bits 8-9): dragons fold their wings, glide or beat them. */
    public static final int GAIT_STILL = 0, GAIT_GLIDE = 1, GAIT_FLAP = 2;
    public static final int CLASS_TERRAIN = 0, CLASS_FOLIAGE = 1, CLASS_WATER = 2, CLASS_EMISSIVE = 3;
    public static final int TILE = 256;

    // struct sizes and offsets (see api.cpp)
    // (0.15 appended AtlasFrame.nearInfo, AtlasFrameOutput.nearLight and AtlasGlFrameIO's two near-light names, read by
    // the core only under FRAME_NEAR_LIGHT: the larger structs are what an older core reads the start of)
    static final long MATERIAL_SIZE = 24, TILE_SIZE = 64, FRAME_SIZE = 152, OUTPUT_SIZE = 40, WEATHER_SIZE = 40,
            ACTOR_SIZE = 48, STATS_SIZE = 56, GL_TARGETS_SIZE = 24, GL_IO_SIZE = 104, LIGHT_SIZE = 40, MEMORY_INFO_SIZE = 48,
            SHAPE_SIZE = 400;
    /** Far light kinds (AtlasLight.kind). */
    public static final int LIGHT_NIGHT = 0, LIGHT_FIRE = 1, LIGHT_LAVA = 2, LIGHT_MIST = 3;

    /**
     * A failed atlas call, with its AtlasStatus. {@link #deviceLost()}: the GPU was reset or hung (the context is
     * dead; make a new one after a cool-down); {@link #outOfMemory()}: retry smaller.
     */
    public static final class AtlasException extends IllegalStateException {
        public final int status;

        public AtlasException(int status, String message) {
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
        Module m = AtlasNative.class.getModule();
        return "--enable-native-access=" + (m.isNamed() ? m.getName() : "ALL-UNNAMED");
    }

    /**
     * Null when this module may call restricted methods without a warning; else one actionable line (Java 25 still
     * allows the calls with a warning unless --illegal-native-access=deny; later releases will refuse them).
     */
    public static String nativeAccessAdvice() {
        Module m = AtlasNative.class.getModule();
        if (m.isNativeAccessEnabled()) return null;
        return "native access is not enabled for " + (m.isNamed() ? "module " + m.getName() : "the class path")
                + " (add the JVM argument " + enableNativeAccessArg() + "; Java 25 only warns, later releases will refuse)";
    }

    static NativeAccessDenied denied(IllegalCallerException e) {
        return new NativeAccessDenied("Java denied native access (" + e.getMessage() + "): start the game with the JVM "
                + "argument " + enableNativeAccessArg() + " (Prism: Edit instance > Settings > Java > JVM arguments; "
                + "NirnCraft's Prism instance zip sets it; a .mrpack cannot, add it in the launcher)", e);
    }

    private final Arena libArena;
    private final MethodHandle abiVersion, buildInfo, create, destroy, lastError, setPalette, uploadTile,
            uploadSection, notifyBlock, setWeather, setCloudNoise, setActors, update, render, getStats,
            glAttach, glRenderIo, glDetach, info, previewLoadRaster, previewGenerate, previewHeight, uploadHeights, uploadHeights2, hasTile, uploadStructures, uploadStructures2, uploadHeights3, setMaterials, setSunMap, setMoon, setJitter, setShapes, setTextures, uploadStructures3, setTransitions, setLightGroup, setLightBudget,
            heightCreate, heightDestroy, heightLastError, heightDevice, heightSetNoise, heightBatch;
    // abi 0.10 (null with a 0.9 library)
    private final MethodHandle memoryInfo, setClipmap, debugFault;
    // abi 0.16, the air (null with an older library)
    private final MethodHandle setAir, airFromClimate, airLight, airCoeffs, airMultiscatter;
    // abi 0.17, the sky's own light (null with an older library)
    private final MethodHandle setSkyGlow;
    /** AtlasClimate and AtlasAir (atlas.h) as float arrays: 13 and 11 floats. */
    public static final int CLIMATE_FLOATS = 13, AIR_FLOATS = 11;
    private int abi;

    private AtlasNative(Path library) {
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
            throw new UnsatisfiedLinkError("atlas_core does not load from " + library + ": " + e.getMessage());
        }
        Linker l = Linker.nativeLinker();
        Linker.Option critical = Linker.Option.critical(true);
        abiVersion = bind(l, lib, "atlas_abi_version", FunctionDescriptor.of(JAVA_INT), critical);
        buildInfo = bind(l, lib, "atlas_build_info", FunctionDescriptor.of(ADDRESS));
        create = bind(l, lib, "atlas_create", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        destroy = bind(l, lib, "atlas_destroy", FunctionDescriptor.ofVoid(ADDRESS));
        lastError = bind(l, lib, "atlas_last_error", FunctionDescriptor.of(ADDRESS, ADDRESS));
        setPalette = bind(l, lib, "atlas_set_palette", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        uploadTile = bind(l, lib, "atlas_upload_column_tile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        uploadSection = bind(l, lib, "atlas_upload_section",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS), critical);
        notifyBlock = bind(l, lib, "atlas_notify_block",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_SHORT), critical);
        setWeather = bind(l, lib, "atlas_set_weather", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        setCloudNoise = bind(l, lib, "atlas_set_cloud_noise", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), critical);
        setActors = bind(l, lib, "atlas_set_actors", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        update = bind(l, lib, "atlas_update", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_DOUBLE));
        render = bind(l, lib, "atlas_render", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        getStats = bind(l, lib, "atlas_get_stats", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        glAttach = bind(l, lib, "atlas_gl_attach", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        glRenderIo = bind(l, lib, "atlas_gl_render_io", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        glDetach = bind(l, lib, "atlas_gl_detach", FunctionDescriptor.ofVoid(ADDRESS));
        info = bind(l, lib, "atlas_info", FunctionDescriptor.of(ADDRESS, ADDRESS));
        previewLoadRaster = bind(l, lib, "atlas_preview_load_raster", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        previewGenerate = bind(l, lib, "atlas_preview_generate",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        previewHeight = bind(l, lib, "atlas_preview_height",
                FunctionDescriptor.of(JAVA_FLOAT, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE));
        uploadHeights = bind(l, lib, "atlas_upload_column_heights",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        uploadHeights2 = bind(l, lib, "atlas_upload_column_heights2",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        uploadHeights3 = bind(l, lib, "atlas_upload_column_heights3", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT,
                JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        hasTile = bind(l, lib, "atlas_has_tile", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT),
                critical);
        uploadStructures = bind(l, lib, "atlas_upload_column_structures",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        uploadStructures2 = bind(l, lib, "atlas_upload_column_structures2",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        setSunMap = bind(l, lib, "atlas_set_sun_map", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_FLOAT));
        setMaterials = bind(l, lib, "atlas_set_materials", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        setTransitions = bind(l, lib, "atlas_set_transitions", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT));
        setLightGroup = bind(l, lib, "atlas_set_light_group", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        setLightBudget = bind(l, lib, "atlas_set_light_budget", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_FLOAT));
        heightCreate = bind(l, lib, "atlas_height_create", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        heightDestroy = bind(l, lib, "atlas_height_destroy", FunctionDescriptor.ofVoid(ADDRESS));
        heightLastError = bind(l, lib, "atlas_height_last_error", FunctionDescriptor.of(ADDRESS, ADDRESS));
        heightDevice = bind(l, lib, "atlas_height_device", FunctionDescriptor.of(ADDRESS, ADDRESS));
        heightSetNoise = bind(l, lib, "atlas_height_set_noise", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        heightBatch = bind(l, lib, "atlas_height_batch", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT,
                JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        memoryInfo = bindOptional(l, lib, "atlas_memory_info", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        setClipmap = bindOptional(l, lib, "atlas_set_clipmap", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        debugFault = bindOptional(l, lib, "atlas_debug_fault", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        setMoon = bindOptional(l, lib, "atlas_set_moon", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        setJitter = bindOptional(l, lib, "atlas_set_jitter", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_FLOAT));
        setShapes = bindOptional(l, lib, "atlas_set_material_shapes", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        setTextures = bindOptional(l, lib, "atlas_set_material_textures", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        uploadStructures3 = bindOptional(l, lib, "atlas_upload_column_structures3",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        setAir = bindOptional(l, lib, "atlas_set_air", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        airFromClimate = bindOptional(l, lib, "atlas_air_from_climate", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        airLight = bindOptional(l, lib, "atlas_air_light",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_FLOAT, ADDRESS));
        airCoeffs = bindOptional(l, lib, "atlas_air_coeffs", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        airMultiscatter = bindOptional(l, lib, "atlas_air_multiscatter", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        setSkyGlow = bindOptional(l, lib, "atlas_set_sky_glow", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    }

    // ---- the air (abi 0.16; atlas.h AtlasAir, native/src/air.hpp): context-free, any thread ------------------------

    private static void airCheck(int status, String what) {
        if (status != 0) throw new AtlasException(status, what + " failed (" + AtlasException.statusName(status) + ")");
    }

    /** Whether this core has the air (a physical sky, sun and haze; atlas_set_air and atlas_air_*). */
    public boolean hasAir() {
        return airLight != null && airFromClimate != null && airCoeffs != null && airMultiscatter != null;
    }

    /** atlas_air_from_climate: AtlasClimate (13 floats) to AtlasAir (11 floats); null without the air. */
    public float[] airFromClimate(float[] climate) {
        if (airFromClimate == null) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment in = a.allocateFrom(JAVA_FLOAT, climate), out = a.allocate(JAVA_FLOAT, AIR_FLOATS);
            airCheck((int) airFromClimate.invokeExact(in, out), "atlas_air_from_climate");
            return out.toArray(JAVA_FLOAT);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * atlas_air_light: {key dir xyz, key radiance rgb, sky up rgb, ground rgb} at world height eyeY for the sun and the
     * moon (null: the default moonlight opposite the sun), as the far field lights; null without the air.
     */
    public float[] airLight(float[] air, float[] sun, float[] moonDir, float[] moonRad, float eyeY) {
        if (airLight == null) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ai = a.allocateFrom(JAVA_FLOAT, air), s = a.allocateFrom(JAVA_FLOAT, sun);
            MemorySegment md = moonDir == null ? MemorySegment.NULL : a.allocateFrom(JAVA_FLOAT, moonDir);
            MemorySegment mr = moonRad == null ? MemorySegment.NULL : a.allocateFrom(JAVA_FLOAT, moonRad);
            MemorySegment out = a.allocate(JAVA_FLOAT, 12);
            airCheck((int) airLight.invokeExact(ai, s, md, mr, eyeY, out), "atlas_air_light");
            return out.toArray(JAVA_FLOAT);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** atlas_air_coeffs: the marches' per-km coefficients (16 floats, atlas_air.glsl's AirRayleigh .. AirOzone). */
    public float[] airCoeffs(float[] air) {
        if (airCoeffs == null) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ai = a.allocateFrom(JAVA_FLOAT, air), out = a.allocate(JAVA_FLOAT, 16);
            airCheck((int) airCoeffs.invokeExact(ai, out), "atlas_air_coeffs");
            return out.toArray(JAVA_FLOAT);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** atlas_air_multiscatter: the 32 x 32 RGBA multiple-scattering LUT (4096 floats; ~10 ms when the air is new). */
    public float[] airMultiscatter(float[] air) {
        if (airMultiscatter == null) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ai = a.allocateFrom(JAVA_FLOAT, air), out = a.allocate(JAVA_FLOAT, 32 * 32 * 4);
            airCheck((int) airMultiscatter.invokeExact(ai, out), "atlas_air_multiscatter");
            return out.toArray(JAVA_FLOAT);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static MethodHandle bindOptional(Linker l, SymbolLookup lib, String name, FunctionDescriptor fd) {
        return lib.find(name).map(s -> l.downcallHandle(s, fd)).orElse(null);
    }

    /** The loaded library's ABI version. */
    public int abi() {
        return abi;
    }

    /** 32-bit words per noise layer, and noise calls (slots) per column, of the exact-noise kernel (atlas.h). */
    public static final int HEIGHT_LAYER_WORDS = 76, HEIGHT_SLOTS = 10;

    /** Times of one height batch (AtlasHeightTimes), milliseconds. */
    public record HeightTimes(double uploadMs, double kernelMs, double readbackMs, double totalMs) {}

    /**
     * An exact-noise engine (atlas_height_create): the kernel on its own Vulkan compute device, or its C++
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

    /** One atlas_height handle; calls serialise natively, so any thread may use it. */
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
                if (s != 0) throw new IllegalStateException("atlas_height_set_noise: " + s + " " + error());
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
                if (s != 0) throw new IllegalStateException("atlas_height_batch: " + s + " " + error());
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
        MemorySegment sym = lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError("atlas_core lacks " + name));
        return l.downcallHandle(sym, fd, opts);
    }

    /**
     * Loads the library and checks its ABI version (ABI_MIN .. ABI_VERSION). Throws {@link NativeAccessDenied} when
     * Java refuses this module native access, UnsatisfiedLinkError when the file is not a usable atlas_core.
     */
    public static AtlasNative load(Path library) {
        AtlasNative n;
        try {
            n = new AtlasNative(library);
        } catch (IllegalCallerException e) {
            throw denied(e);  // Linker.downcallHandle is restricted too
        }
        int v = n.abiVersion();
        if (v < ABI_MIN || v > ABI_VERSION) {
            throw new UnsatisfiedLinkError("atlas_core ABI " + abiString(v) + ", this binding drives "
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
                // no context to ask: atlas_last_error(NULL) holds why (e.g. every Vulkan device and its reason)
                String why = ((MemorySegment) lastError.invokeExact(MemorySegment.NULL)).reinterpret(4096).getString(0);
                throw new AtlasException(s, "atlas_create(" + backend + ") failed: " + AtlasException.statusName(s)
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

        /** nearLight: the core takes FRAME_NEAR_LIGHT (abi 0.15); else the flag is dropped. */
        void write(MemorySegment s, boolean nearLight) {
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
            s.set(JAVA_INT, 128, nearLight ? flags : flags & ~FRAME_NEAR_LIGHT);
            s.set(ADDRESS, 136, MemorySegment.NULL);  // nearDepth: the game path uses the shared GL image
            s.set(ADDRESS, 144, MemorySegment.NULL);  // (abi 0.15) nearInfo: likewise (GlFrameIO.nearInfoTex)
        }
    }

    /** pending: regions the window wants that the last update left unbuilt (a budget ran out; 0 on cores before 0.14). */
    public record Stats(int regions, long nodeBytes, long leafBytes, long materialBytes, double buildMs, double frameMs,
                        long raySteps, int pending) {}

    /** atlas_memory_info (abi 0.10): bytes; budget / usage 0 when the driver lacks VK_EXT_memory_budget. */
    public record MemoryInfo(long deviceLocal, long budget, long usage, long hostHeap, long atlas, boolean integrated,
                             boolean budgetExt) {}

    /** GL texture names of the images shared with the game's GL context (rows bottom-up). */
    public record GlTargets(int color, int normal, int depth, int nearDepth, int width, int height) {}

    /** The game's side of a GL frame (see AtlasGlFrameIO in atlas.h); zero fields are unused. */
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
        /**
         * (abi 0.15, with FRAME_NEAR_LIGHT) the near field's surface info, RGBA8 the size of nearDepthTex's: r block light
         * / 15, g sky light / 15, b a the world-space normal, octahedral (atlas.h AtlasGlFrameIO.nearInfoTex):
         * p = n.xz / (|n.x| + |n.y| + |n.z|); if n.y &lt; 0: p = (1 - |p.yx|) * (p.x &gt;= 0 ? 1 : -1, p.y &gt;= 0 ? 1 : -1);
         * (b, a) = p * 0.5 + 0.5. 0 = none.
         */
        public int nearInfoTex;
        /** (abi 0.15, with FRAME_NEAR_LIGHT) RGBA16F at atlas's resolution: the near light (atlas.h); 0 = none. */
        public int dstNearLight;

        /** nearLight: the core takes the near light's fields (abi 0.15); else they are left zero. */
        void write(MemorySegment s, boolean nearLight) {
            s.set(JAVA_INT, 0, nearDepthTex);
            s.set(JAVA_INT, 4, depthZeroToOne);
            s.set(JAVA_INT, 8, reversedZ ? 1 : 0);
            s.set(JAVA_INT, 12, 0);
            MemorySegment.copy(nearInvProjection, 0, s, JAVA_FLOAT, 16, 16);
            s.set(JAVA_INT, 80, dstColor);
            s.set(JAVA_INT, 84, dstNormal);
            s.set(JAVA_INT, 88, dstDepth);
            s.set(JAVA_INT, 92, dstSunMap);
            s.set(JAVA_INT, 96, nearLight ? nearInfoTex : 0);
            s.set(JAVA_INT, 100, nearLight ? dstNearLight : 0);
        }
    }

    /** One atlas_core context. Not thread-safe except the upload methods (which the native side locks). */
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
                throw new AtlasException(status, what + " failed (" + AtlasException.statusName(status) + "): "
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
                check((int) setClipmap.invokeExact(ptr, lodCount), "atlas_set_clipmap");
                return true;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Test hook (abi 0.10): fail as the driver would after afterFrames frames. False with a 0.9 library. */
        public boolean debugFault(int fault, int afterFrames) {
            if (debugFault == null) return false;
            try {
                check((int) debugFault.invokeExact(ptr, fault, afterFrames), "atlas_debug_fault");
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
                check((int) setPalette.invokeExact(ptr, m, materials.length), "atlas_set_palette");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * The sun map (atlas.h atlas_set_sun_map): size x size along the key light over halfExtent blocks around
         * the camera, for the pack's far shadows (mountains, clouds) on the near field; size 0 turns it off.
         */
        public void setSunMap(int size, float halfExtent) {
            try {
                check((int) setSunMap.invokeExact(ptr, size, halfExtent), "atlas_set_sun_map");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * (abi 0.12) The night's key light (atlas.h atlas_set_moon): the pack's lunar key, so both fields light the
         * night alike. A core without it keeps its fixed moonlight; false then.
         */
        public boolean setMoon(float[] dir, float[] radiance) {
            if (setMoon == null) return false;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment d = a.allocateFrom(JAVA_FLOAT, dir), r = a.allocateFrom(JAVA_FLOAT, radiance);
                check((int) setMoon.invokeExact(ptr, d, r), "atlas_set_moon");
                return true;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * (abi 0.17) The sky's own light (atlas.h atlas_set_sky_glow: the unified sky's emitters, integrated by the host),
         * added to the air's sky light the far field lights with: up and ground radiance (rgb). A core without it lights
         * with the air alone; false then.
         */
        public boolean setSkyGlow(float[] up, float[] ground) {
            if (setSkyGlow == null) return false;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment u = a.allocateFrom(JAVA_FLOAT, up), g = a.allocateFrom(JAVA_FLOAT, ground);
                check((int) setSkyGlow.invokeExact(ptr, u, g), "atlas_set_sky_glow");
                return true;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * (abi 0.16) The air the frames are drawn in (atlas.h atlas_set_air: AtlasAir, 11 floats); builds its
         * multiple-scattering LUT (~10 ms when the air is new). A core without it keeps its default air; false then.
         */
        public boolean setAir(float[] air) {
            if (setAir == null) return false;
            try (Arena a = Arena.ofConfined()) {
                check((int) setAir.invokeExact(ptr, a.allocateFrom(JAVA_FLOAT, air)), "atlas_set_air");
                return true;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * (abi 0.13) The primary rays' sub-pixel offset in atlas pixels (atlas.h atlas_set_jitter), for the pack's TAA.
         * A core without it renders unjittered; false then.
         */
        public boolean setJitter(float x, float y) {
            if (setJitter == null) return false;
            try {
                check((int) setJitter.invokeExact(ptr, x, y), "atlas_set_jitter");
                return true;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** (abi 0.14) Whether this core takes material shapes (and traces the groups, FRAME_GROUPS). */
        public boolean hasShapes() {
            return setShapes != null;
        }

        /**
         * (abi 0.15) Whether this core lights the near field (FRAME_NEAR_LIGHT with GlFrameIO.nearInfoTex and
         * dstNearLight). An older core is never handed the flag or the fields: they are dropped on the way in.
         */
        public boolean hasNearLight() {
            return abi >= ABI_NEAR_LIGHT;
        }

        /**
         * (abi 0.14) The shapes of materials first .. first + n - 1 (atlas.h atlas_set_material_shapes); a null entry or
         * a zero occupancy leaves that material a full cube in its colour. A core without the call: false, nothing sent.
         */
        public boolean setMaterialShapes(int first, AtlasShapes.Shape[] shapes) {
            if (setShapes == null) return false;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment m = a.allocate(SHAPE_SIZE * Math.max(1, shapes.length), 8);
                for (int i = 0; i < shapes.length; i++) {
                    AtlasShapes.Shape v = shapes[i];
                    if (v == null) continue;  // zeroed: no shape
                    long o = i * SHAPE_SIZE;
                    m.set(JAVA_LONG, o, v.occupancy());
                    m.set(JAVA_INT, o + 8, v.mean());
                    m.set(JAVA_INT, o + 12, v.flags());
                    for (int f = 0; f < 6; f++)
                        for (int t = 0; t < 16; t++) m.set(JAVA_INT, o + 16 + 4L * (f * 16 + t), v.faces()[f][t]);
                }
                check((int) setShapes.invokeExact(ptr, first, m, shapes.length), "atlas_set_material_shapes");
                return true;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * (abi 0.16, optional) The 16x16 face textures of materials first .. first + n - 1 (null entries: none, their
         * face tiles stand in): what atlas shades its near hits with. False when the core does not take them.
         */
        public boolean setMaterialTextures(int first, int[][][] textures) {
            if (setTextures == null || textures.length == 0) return false;
            final long per = 6L * 256L * 4L;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment m = a.allocate(per * textures.length + 4L * textures.length, 8);
                long flags = per * textures.length;  // then one word per material: 1 when it has textures
                for (int i = 0; i < textures.length; i++) {
                    int[][] t = textures[i];
                    m.set(JAVA_INT, flags + 4L * i, t == null ? 0 : 1);
                    if (t == null) continue;
                    for (int f = 0; f < 6; f++)
                        for (int k = 0; k < 256; k++) m.set(JAVA_INT, per * i + 4L * (f * 256 + k), t[f][k]);
                }
                check((int) setTextures.invokeExact(ptr, first, m, textures.length), "atlas_set_material_textures");
                return true;
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
                check((int) setMaterials.invokeExact(ptr, first, m, materials.length), "atlas_set_materials");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * Buildings over one tile (see atlas.h): walls from the ground to wallTop, roof to roofTop (NaN = none),
         * row-major [z][x]. wallTop == null removes the tile's buildings. Copied before this returns.
         */
        public void uploadColumnStructures(int lod, int tileX, int tileZ, float[] wallTop, float[] roofTop, short[] wallMat,
                                           short[] roofMat) {
            uploadColumnStructures(lod, tileX, tileZ, wallTop, roofTop, wallMat, roofMat, null);
        }

        /**
         * (abi 0.10) As uploadColumnStructures, plus base (may be null): per column the bottom of the span for what does
         * not stand on the ground (an arch's deck, a tree's crown, a floating rock); NaN = from the ground.
         */
        /** (abi 0.13) Flags for the next structure uploads: STRUCTURES_REPLACE_CANOPY (the tile's real trees). */
        public int structureFlags;

        public void uploadColumnStructures(int lod, int tileX, int tileZ, float[] wallTop, float[] roofTop, short[] wallMat,
                                           short[] roofMat, float[] base) {
            try (Arena a = Arena.ofConfined()) {
                if (wallTop == null) {
                    check((int) uploadStructures.invokeExact(ptr, lod, tileX, tileZ, MemorySegment.NULL, MemorySegment.NULL,
                            MemorySegment.NULL, MemorySegment.NULL), "atlas_upload_column_structures");
                    return;
                }
                int n = TILE * TILE;
                if (wallTop.length < n || roofTop.length < n || wallMat.length < n || roofMat.length < n)
                    throw new IllegalArgumentException("structure arrays must hold 256*256 columns");
                MemorySegment b = base != null ? copy(a, base) : MemorySegment.NULL;
                if (structureFlags != 0 && uploadStructures3 != null)
                    check((int) uploadStructures3.invokeExact(ptr, lod, tileX, tileZ, copy(a, wallTop), copy(a, roofTop),
                            copy(a, wallMat), copy(a, roofMat), b, structureFlags), "atlas_upload_column_structures3");
                else
                    check((int) uploadStructures2.invokeExact(ptr, lod, tileX, tileZ, copy(a, wallTop), copy(a, roofTop),
                            copy(a, wallMat), copy(a, roofMat), b), "atlas_upload_column_structures2");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * Uploads one authoritative column tile (256 x 256 columns, row-major [z][x], see atlas.h). The arrays are
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
                check((int) uploadTile.invokeExact(ptr, t), "atlas_upload_column_tile");
            } catch (Throwable th) {
                throw rethrow(th);
            }
        }

        /**
         * Exact geometry, preview dressing: surface and water heights (NaN = no water) of one 256 x 256 tile, row-major
         * [z][x] as in uploadColumnTile, e.g. from the game's terrain height function; atlas picks materials and
         * forest canopy from the loaded preview raster. Needs previewLoadRaster. Copied before this returns.
         */
        public void uploadColumnHeights(int lod, int tileX, int tileZ, float[] surface, float[] water) {
            if (surface.length < TILE * TILE || water.length < TILE * TILE)
                throw new IllegalArgumentException("tile arrays must hold 256*256 columns");
            try (Arena a = Arena.ofConfined()) {
                check((int) uploadHeights.invokeExact(ptr, lod, tileX, tileZ, copy(a, surface), copy(a, water)),
                        "atlas_upload_column_heights");
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
                        "atlas_upload_column_heights2");
            } catch (Throwable th) {
                throw rethrow(th);
            }
        }

        /**
         * (abi 0.10) As uploadColumnHeights2, plus the world's surface blocks: topMat / fillMat palette ids (0 = the
         * dressing's) and per column flags (1 snow cover, 2 frozen water). grassMat / foliageMat / flags may be null.
         */
        public void uploadColumnHeights3(int lod, int tileX, int tileZ, float[] surface, float[] water, short[] topMat,
                                         short[] fillMat, short[] grassMat, short[] foliageMat, byte[] flags) {
            if (surface.length < TILE * TILE || water.length < TILE * TILE || topMat.length < TILE * TILE || fillMat.length < TILE * TILE)
                throw new IllegalArgumentException("tile arrays must hold 256*256 columns");
            try (Arena a = Arena.ofConfined()) {
                MemorySegment g = grassMat != null ? copy(a, grassMat) : MemorySegment.NULL;
                MemorySegment f = foliageMat != null ? copy(a, foliageMat) : MemorySegment.NULL;
                MemorySegment fl = flags != null ? a.allocateFrom(ValueLayout.JAVA_BYTE, flags) : MemorySegment.NULL;
                check((int) uploadHeights3.invokeExact(ptr, lod, tileX, tileZ, copy(a, surface), copy(a, water), copy(a, topMat),
                        copy(a, fillMat), g, f, fl), "atlas_upload_column_heights3");
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
                check((int) uploadSection.invokeExact(ptr, sx, sy, sz, MemorySegment.ofArray(mats)), "atlas_upload_section");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        public void notifyBlock(int x, int y, int z, short mat) {
            try {
                check((int) notifyBlock.invokeExact(ptr, x, y, z, mat), "atlas_notify_block");
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
                check((int) setWeather.invokeExact(ptr, w), "atlas_set_weather");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** The 64^3 RGBA8 cloud noise volume, index (z * 64 + y) * 64 + x. */
        public void setCloudNoise(int[] rgba) {
            if (rgba.length < 64 * 64 * 64) throw new IllegalArgumentException("need 64^3 texels");
            try {
                check((int) setCloudNoise.invokeExact(ptr, MemorySegment.ofArray(rgba)), "atlas_set_cloud_noise");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * (abi 0.10) Refinement fades: a region rebuilt over a resident one (exact tile over the stand-in, buildings
         * arriving, a finer LOD entering the window) dissolves into the new content over this many seconds; 0 = at once.
         */
        public void setTransitions(float fadeSeconds) {
            try {
                check((int) setTransitions.invokeExact(ptr, fadeSeconds), "atlas_set_transitions");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /**
         * (abi 0.10) Replaces far-light group `group` (count 0 removes it): per light x, y, z (xyz), radius and intensity
         * (radiusIntensity), sRGB colour and kind (rgbKind, LIGHT_*). Any thread; copied.
         */
        public void setLightGroup(int group, double[] xyz, float[] radiusIntensity, int[] rgbKind, int count) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = a.allocate(Math.max(1, count) * LIGHT_SIZE, 8);
                for (int i = 0; i < count; i++) {
                    long o = i * LIGHT_SIZE;
                    s.set(JAVA_DOUBLE, o, xyz[3 * i]);
                    s.set(JAVA_DOUBLE, o + 8, xyz[3 * i + 1]);
                    s.set(JAVA_DOUBLE, o + 16, xyz[3 * i + 2]);
                    s.set(JAVA_FLOAT, o + 24, radiusIntensity[2 * i]);
                    s.set(JAVA_FLOAT, o + 28, radiusIntensity[2 * i + 1]);
                    s.set(JAVA_INT, o + 32, rgbKind[2 * i]);
                    s.set(JAVA_INT, o + 36, rgbKind[2 * i + 1]);
                }
                MemorySegment arg = count > 0 ? s : MemorySegment.NULL;
                check((int) setLightGroup.invokeExact(ptr, group, arg, count), "atlas_set_light_group");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** (abi 0.10) At most maxPerFrame far lights a frame (brightest first), merged into clusters beyond clusterDistance. */
        public void setLightBudget(int maxPerFrame, float clusterDistance) {
            try {
                check((int) setLightBudget.invokeExact(ptr, maxPerFrame, clusterDistance), "atlas_set_light_budget");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Far actors: x, y, z, yaw, height, radius, kind, tint per actor. */
        public void setActors(double[] xyz, float[] yawHeightRadius, int[] kindTint, int count) {
            setActors(xyz, yawHeightRadius, kindTint, null, count);
        }

        /**
         * As setActors, plus per actor flags (abi 0.10, may be null): bits 0-7 transparency (0 opaque .. 255 gone, the
         * per-pixel dissolve of a cross-fade with the real entity), bits 8-9 the gait (GAIT_*), bits 16-31 a phase seed.
         */
        public void setActors(double[] xyz, float[] yawHeightRadius, int[] kindTint, int[] flags, int count) {
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
                    s.set(JAVA_INT, o + 44, flags != null ? flags[i] : 0);
                }
                check((int) setActors.invokeExact(ptr, s, count), "atlas_set_actors");
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
                check((int) update.invokeExact(ptr, c, budgetMs), "atlas_update");
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
            f.write(frameSeg, false);  // (the harness path hands no near depth: no near light)
            outSeg.set(ADDRESS, 0, color != null ? colorSeg : MemorySegment.NULL);
            outSeg.set(ADDRESS, 8, normal != null ? normalSeg : MemorySegment.NULL);
            outSeg.set(ADDRESS, 16, depth != null ? depthSeg : MemorySegment.NULL);
            outSeg.set(ADDRESS, 24, MemorySegment.NULL);  // colorLinear: not needed by the harness
            outSeg.set(ADDRESS, 32, MemorySegment.NULL);  // (abi 0.15) nearLight
            try {
                check((int) render.invokeExact(ptr, frameSeg, outSeg), "atlas_render");
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
                check((int) getStats.invokeExact(ptr, s), "atlas_get_stats");
                return new Stats(s.get(JAVA_INT, 0), s.get(JAVA_LONG, 8), s.get(JAVA_LONG, 16), s.get(JAVA_LONG, 24),
                        s.get(JAVA_DOUBLE, 32), s.get(JAVA_DOUBLE, 40), s.get(JAVA_LONG, 48), s.get(JAVA_INT, 4));
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        // ---- game path: render thread, the game's GL context current ----

        /** Creates the shared images (re-attach on resize). */
        public GlTargets glAttach(int width, int height) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment t = a.allocate(GL_TARGETS_SIZE, 4);
                check((int) glAttach.invokeExact(ptr, width, height, t), "atlas_gl_attach");
                return new GlTargets(t.get(JAVA_INT, 0), t.get(JAVA_INT, 4), t.get(JAVA_INT, 8), t.get(JAVA_INT, 12),
                        t.get(JAVA_INT, 16), t.get(JAVA_INT, 20));
            } catch (Throwable th) {
                throw rethrow(th);
            }
        }

        /** Queues the frame; GL commands issued afterwards see it. The CPU does not wait for the GPU. */
        public void glRender(Frame f, GlFrameIO io) {
            // the near light only to a core that has it, and only with both of its textures (atlas.h)
            boolean nearLight = hasNearLight() && io != null && io.nearInfoTex != 0 && io.dstNearLight != 0;
            f.write(frameSeg, nearLight);
            if (ioSeg == null) ioSeg = frameArena.allocate(GL_IO_SIZE, 8);
            if (io != null) io.write(ioSeg, nearLight);
            try {
                // a typed local: a conditional passed straight to invokeExact is typed Object (its target), and the
                // exact-type check then fails on every frame
                MemorySegment ioArg = io != null ? ioSeg : MemorySegment.NULL;
                check((int) glRenderIo.invokeExact(ptr, frameSeg, ioArg), "atlas_gl_render_io");
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
                check((int) previewLoadRaster.invokeExact(ptr, a.allocateFrom(raster.toString())), "atlas_preview_load_raster");
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        public void previewGenerate(int lod, int x0, int z0, int x1, int z1) {
            try {
                check((int) previewGenerate.invokeExact(ptr, lod, x0, z0, x1, z1), "atlas_preview_generate");
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
