/*
 * aether_core: Skycraft's native far-field engine (distant terrain, curved-planet projection, cloud shell, far actors).
 * Plain C ABI so Java 25 can bind it with java.lang.foreign (no JNI glue). See docs/AETHER.md.
 *
 * Ownership and threading
 *  - Every call takes an AetherContext created by aether_create; calls on one context are not reentrant except where
 *    marked "any thread" (those take an internal lock and only copy data in).
 *  - Buffers passed in are copied before the call returns; the caller keeps ownership.
 *  - World coordinates are Minecraft block coordinates (block 0,0 = Whiterun, -Z north, sea level 63, y -64..640).
 *
 * Authority: aether_core never decides what the world contains. The Java generator (TamrielHeight, plans, caves,
 * structures) and loaded chunks are authoritative; they push column tiles and chunk sections here. The preview source
 * (aether_preview_load_raster) exists only for offline harnesses and is labelled as such in every output.
 */
#ifndef AETHER_H
#define AETHER_H

#include <stdint.h>

#ifdef _WIN32
#  ifdef AETHER_BUILD
#    define AETHER_API __declspec(dllexport)
#  else
#    define AETHER_API __declspec(dllimport)
#  endif
#else
#  define AETHER_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* (major << 16) | (minor << 8) | patch. Bumped on any ABI change. */
#define AETHER_ABI_VERSION 0x000A00

typedef struct AetherContext AetherContext;

typedef enum AetherStatus {
    AETHER_OK = 0,
    AETHER_ERR_ARG = -1,
    AETHER_ERR_STATE = -2,
    AETHER_ERR_IO = -3,
    AETHER_ERR_GPU = -4,
    AETHER_ERR_UNSUPPORTED = -5,
} AetherStatus;

typedef enum AetherBackend {
    AETHER_BACKEND_CPU = 0,    /* reference: the same Slang kernel compiled to C++ */
    AETHER_BACKEND_VULKAN = 1, /* the same kernel compiled to SPIR-V */
} AetherBackend;

/* Material classes, shared with the shaders (material.a in the G-buffer). */
typedef enum AetherMaterialClass {
    AETHER_CLASS_TERRAIN = 0,
    AETHER_CLASS_FOLIAGE = 1,
    AETHER_CLASS_WATER = 2,
    AETHER_CLASS_EMISSIVE = 3,
} AetherMaterialClass;

typedef struct AetherMaterial {
    float albedo[3];   /* linear RGB */
    float roughness;   /* 0..1 */
    float emissive;    /* 0..1 intensity */
    uint32_t cls;      /* AetherMaterialClass */
} AetherMaterial;

/*
 * One column tile at LOD level `lod` (voxel size 2^lod blocks): 256 x 256 columns covering 256 * 2^lod blocks per
 * side, with column (i, j) centred at block (tileX * span + (i + 0.5) * 2^lod, ..., same for z). Row-major [j][i].
 *  surface   : top of solid ground in blocks (float)
 *  water     : water surface in blocks, or NaN when the column holds no water
 *  topMat    : material of the top voxel (grass, sand, snow ...)
 *  fillMat   : material of the few voxels under it (dirt, sandstone ...); deeper is stone
 *  canopy    : optional (may be NULL) height in blocks of tree canopy above the surface, 0 = none
 *  canopyMat : optional (may be NULL) material of the canopy voxels
 */
typedef struct AetherColumnTile {
    int32_t lod, tileX, tileZ;
    const float* surface;
    const float* water;
    const uint16_t* topMat;
    const uint16_t* fillMat;
    const float* canopy;
    const uint16_t* canopyMat;
} AetherColumnTile;

/* Camera and frame description. Matrices are column-major float[16] (OpenGL/JOML convention). */
typedef struct AetherFrame {
    double cameraPos[3];        /* world block coordinates */
    float invViewProj[16];      /* camera-relative (translation removed) inverse view-projection */
    uint32_t width, height;
    float nearFieldEnd;         /* blocks: where the rasterised near field stops and the marcher takes over */
    float maxDistance;          /* blocks */
    float planetRadius;         /* blocks; <= 0 disables curvature */
    float sunDir[3];            /* unit, world space, towards the sun */
    float timeSec;              /* animation clock (game ticks / 20) */
    float lodBias;              /* >= 1: voxel size per pixel footprint; 1 = one voxel per pixel */
    uint32_t flags;             /* AETHER_FRAME_* */
    /* optional (AETHER_FRAME_NEAR_DEPTH, host path): width*height view distances of the near field, row 0 at the
       top; pixels nearer than nearFieldEnd are covered and skip the far march. The GL path reads them from the
       shared nearDepth image instead. */
    const float* nearDepth;
} AetherFrame;

#define AETHER_FRAME_CLOUDS   0x1u
#define AETHER_FRAME_SHADOWS  0x2u
#define AETHER_FRAME_ATMOS    0x4u
#define AETHER_FRAME_ACTORS   0x8u
#define AETHER_FRAME_SKY      0x10u   /* draw aether's sky behind misses (harness); in game the pack draws its own */
#define AETHER_FRAME_NEAR_DEPTH 0x20u /* skip pixels the near field covers (see AetherFrame.nearDepth) */
#define AETHER_FRAME_FLAT_NORMALS 0x40u /* face normals only: cheaper, blockier in the distance */
#define AETHER_FRAME_CLOUDS_QUARTER 0x80u /* cloud pass at quarter resolution (default half): about 4x cheaper */
#define AETHER_FRAME_DEBUG_LOD 0x100u
#define AETHER_FRAME_DEBUG_WEATHER 0x200u
#define AETHER_FRAME_DEBUG_ALBEDO 0x400u      /* surfaces output their albedo (offline G-buffers: the pack lab) */
#define AETHER_FRAME_SEE_THROUGH_WATER 0x800u /* rays pass water (the bed under it, offline) */
#define AETHER_FRAME_LOD_DITHER 0x1000u       /* (abi 0.10) dithered LOD transitions: each pixel's LOD segment grid
                                                 starts at its own phase, so a level change is a stipple band, not a line */

/*
 * Frame outputs (any pointer may be NULL to skip). Layout matches the shared G-buffer images:
 *  colorLinear RGBA32F linear HDR radiance, premultiplied over whatever is drawn behind (near field or the pack's
 *                      sky); a = coverage (1 on far terrain, cloud opacity elsewhere). The GL image is RGBA16F.
 *  color       RGBA8   the same through the harness tonemapper (c / (1 + c), gamma 2.2), for PNGs
 *  normal      RGBA8   octahedral normal in rg, material class in b, sun visibility (shadows) in a
 *  depth       float   view distance in blocks to the nearest thing drawn: far surface, far actor or the first
 *                      cloud (1e30 = nothing). A composite keeps a near pixel whenever it is nearer than this.
 */
typedef struct AetherFrameOutput {
    uint32_t* color;
    uint32_t* normal;
    float* depth;
    float* colorLinear;
} AetherFrameOutput;

/* Weather fields exactly as WeatherFieldTexture uploads them (RGBA8, 128 x 128, world-fixed toroidal). */
typedef struct AetherWeather {
    const uint32_t* nearField;  /* 64-block cells: r coverage, g storm tower, b curtain, a band/flash */
    const uint32_t* nearDyn;    /* r cloud base y/1024, g surface y/1024, b wind angle, a ground fog */
    const uint32_t* farField;   /* 512-block cells */
    const uint32_t* farDyn;
    float rainFactor;           /* 0..1 vanilla rain strength */
} AetherWeather;

/* A far actor: a player or large creature beyond vanilla tracking range, drawn as an impostor. */
typedef struct AetherActor {
    double pos[3];
    float yaw;
    float height, radius;       /* blocks */
    uint32_t kind;              /* impostor atlas row; 0 = generic humanoid */
    uint32_t tint;              /* RGBA8 */
    /* (abi 0.10, in what was padding; zero = the old behaviour) bits 0-7: transparency 0..255 (the impostor dissolves
       per pixel: 0 opaque, 255 gone; a cross-fade with the real entity at the tracking boundary), bits 8-9: gait
       (AETHER_GAIT_*), bits 16-31: animation phase seed (e.g. the entity id), so a flock does not beat in step */
    uint32_t flags;
} AetherActor;
#define AETHER_GAIT_STILL 0u  /* on the ground: dragons fold their wings */
#define AETHER_GAIT_GLIDE 1u
#define AETHER_GAIT_FLAP 2u

/*
 * (abi 0.10) A far light: something that glows from kilometres away (a town's lit windows and street lamps at night,
 * braziers and camp fires, lava), or, for AETHER_LIGHT_MIST, the spray of a waterfall lit by the sun. Drawn by the far
 * kernel as a glow on top of the far field where nothing nearer hides it, at least a pixel wide whatever the distance,
 * spreading into a halo through the haze; never inside the near field's radius (the game draws its own lights there).
 */
typedef struct AetherLight {
    double pos[3];        /* world block coordinates */
    float radius;         /* blocks: the glowing thing's size (a window 1, a brazier 1.5, a lava pool 6, falls mist 12) */
    float intensity;      /* relative radiance (1 = a lantern) */
    uint32_t rgb;         /* sRGB colour, r in the low byte */
    uint32_t kind;        /* AETHER_LIGHT_* */
} AetherLight;
#define AETHER_LIGHT_NIGHT 0u  /* windows, lamps: lit from dusk to dawn */
#define AETHER_LIGHT_FIRE 1u   /* braziers, camp fires: always lit, flickering (they read at night) */
#define AETHER_LIGHT_LAVA 2u   /* always lit, a slow pulse */
#define AETHER_LIGHT_MIST 3u   /* spray, lit by the sun and the sky: a white haze by day, faint by night */

typedef struct AetherStats {
    uint32_t regions, regionsDirty;
    uint64_t nodeBytes, leafBytes, materialBytes;
    double lastBuildMs, lastFrameMs;
    uint64_t raySteps;
} AetherStats;

AETHER_API uint32_t aether_abi_version(void);
/* Human-readable build string, static storage. */
AETHER_API const char* aether_build_info(void);

AETHER_API AetherStatus aether_create(AetherBackend backend, AetherContext** out);
AETHER_API void aether_destroy(AetherContext* ctx);
/* Last error message for this context, static until the next call. NULL: why this thread's last aether_create
   failed (e.g. no usable Vulkan device, and why each one was rejected). */
AETHER_API const char* aether_last_error(AetherContext* ctx);

/* Material palette: ids 1..count (0 is air). Replaces the whole palette. */
AETHER_API AetherStatus aether_set_palette(AetherContext* ctx, const AetherMaterial* materials, uint32_t count);

/* Authoritative data in (any thread). */
AETHER_API AetherStatus aether_upload_column_tile(AetherContext* ctx, const AetherColumnTile* tile);
/* A 16^3 chunk section of palette material ids (0 = air), index x + 16 * (z + 16 * y) like vanilla. Overrides tiles. */
AETHER_API AetherStatus aether_upload_section(AetherContext* ctx, int32_t sx, int32_t sy, int32_t sz,
                                              const uint16_t* mats);
/* One block edit (any thread): marks the brick dirty in every LOD that contains it. */
AETHER_API AetherStatus aether_notify_block(AetherContext* ctx, int32_t x, int32_t y, int32_t z, uint16_t mat);
AETHER_API AetherStatus aether_set_weather(AetherContext* ctx, const AetherWeather* weather);
/* Cloud noise volume: 64^3 RGBA8, index (z * 64 + y) * 64 + x (the mod's cloud_noise_volume atlas, unpacked). */
AETHER_API AetherStatus aether_set_cloud_noise(AetherContext* ctx, const uint32_t* rgba);
AETHER_API AetherStatus aether_set_actors(AetherContext* ctx, const AetherActor* actors, uint32_t count);

/* Rebuild dirty regions around `centre` (blocks) for the configured clipmap; budgetMs <= 0 means build everything. */
AETHER_API AetherStatus aether_update(AetherContext* ctx, const double centre[3], double budgetMs);
/* Render one frame into host buffers (harness path; the game path renders into shared GPU images). */
AETHER_API AetherStatus aether_render(AetherContext* ctx, const AetherFrame* frame, const AetherFrameOutput* out);
AETHER_API AetherStatus aether_get_stats(AetherContext* ctx, AetherStats* out);

/*
 * Game path (Vulkan backend; call on the render thread with the game's OpenGL 4.5 context current; zero-copy when
 * that context is on the same GPU and supports memory objects, else host copies, see aether_info). aether_create
 * on that thread picks the Vulkan device the context runs on. aether_gl_attach creates color (RGBA16F linear, premultiplied), normal (RGBA8) and depth (R32F, view
 * distance in blocks) images, plus nearDepth (R32F) that the GL side writes with the near field's view distance,
 * all shared with that context; it returns their GL texture names (rows bottom-up, GL convention). Each
 * aether_gl_render queues the frame and a GL-side semaphore wait: GL commands issued after it see the frame,
 * and the CPU never blocks on the GPU. Re-attach on resize; detach before destroying the GL context.
 */
typedef struct AetherGlTargets {
    uint32_t color, normal, depth, nearDepth;
    uint32_t width, height;
} AetherGlTargets;
AETHER_API AetherStatus aether_gl_attach(AetherContext* ctx, uint32_t width, uint32_t height, AetherGlTargets* out);
AETHER_API AetherStatus aether_gl_render(AetherContext* ctx, const AetherFrame* frame);

/*
 * The game's side of a GL frame (all optional; zero = unused):
 *  nearDepthTex      the rasterised near field's depth texture (same size as the targets), or
 *                    AETHER_GL_BOUND_DEPTH: the depth attachment of the draw framebuffer bound when aether is called
 *                    (e.g. a shader pack's gbuffer target, whose texture the mod never sees). aether linearises it
 *                    into the shared nearDepth image with a small GL compute pass, so covered pixels skip the far
 *                    march (sets AETHER_FRAME_NEAR_DEPTH for the frame). Sky = depth 1 (or 0 when reversedZ).
 *  depthZeroToOne    1 when that depth was written with [0,1] clip depth (glClipControl), 0 for [-1,1],
 *                    AETHER_GL_DEPTH_AUTO to read the context's GL_CLIP_DEPTH_MODE
 *  reversedZ         1 when near = 1, far = 0
 *  nearInvProjection inverse of the projection used for that depth, column-major, camera-relative view space
 *  dstColor/Normal/Depth  GL textures (same size and format as the shared ones) that receive copies once the
 *                    frame is done, e.g. the textures a shader pack samples through customTexture.
 * aether restores every piece of GL state it touches (program, texture/sampler unit 15, image unit 7).
 */
#define AETHER_GL_BOUND_DEPTH 0xFFFFFFFFu
#define AETHER_GL_DEPTH_AUTO 0xFFFFFFFFu
typedef struct AetherGlFrameIO {
    uint32_t nearDepthTex;
    uint32_t depthZeroToOne;
    uint32_t reversedZ;
    uint32_t reserved;
    float nearInvProjection[16];
    uint32_t dstColor, dstNormal, dstDepth;
    uint32_t dstSunMap; /* RG32F size x size+1 (aether_set_sun_map), or 0 */
} AetherGlFrameIO;
AETHER_API AetherStatus aether_gl_render_io(AetherContext* ctx, const AetherFrame* frame, const AetherGlFrameIO* io);
AETHER_API void aether_gl_detach(AetherContext* ctx);

/*
 * (abi 0.8) What this context runs on, as one line of JSON for logs: backend, device name and type, vendor id,
 * driver name and version, Vulkan API version, device/driver UUIDs, whether the device was picked because the GL
 * context current at aether_create runs on it, and after aether_gl_attach the interop mode:
 *   "zero-copy (win32 handles)" | "zero-copy (opaque fds)"  shared images + semaphores, nothing touches the CPU
 *   "host-copy"   the driver pair cannot share memory (no EXT_memory_object_{win32,fd} / VK_KHR_external_memory_*,
 *                 formats not exportable, or the GL context on another GPU): frames are read back and uploaded with
 *                 glTextureSubImage2D (interopReason says why; hostCopy.waitMs / uploadMs the last frame's CPU cost;
 *                 the near-depth skip is off, the composite's depth test still orders near over far)
 * plus the GL renderer / version / window system ("wgl", "egl", "glx") and every Vulkan device seen. Static storage
 * per context until the next call. Forcing a mode for tests: AETHER_INTEROP=hostcopy|zerocopy in the environment.
 */
AETHER_API const char* aether_info(AetherContext* ctx);

/* Offline harness only: load native/data/tamriel_raster.bin and synthesise preview column tiles from it. */
AETHER_API AetherStatus aether_preview_load_raster(AetherContext* ctx, const char* path);
/* Offline harness only: fill tiles of LOD `lod` covering [x0,x1) x [z0,z1) from the preview source. */
AETHER_API AetherStatus aether_preview_generate(AetherContext* ctx, int32_t lod, int32_t x0, int32_t z0, int32_t x1,
                                                int32_t z1);
/*
 * Exact geometry, preview dressing: the caller's surface and water heights (NaN = no water) for the 256 x 256 columns
 * of tile (lod, tileX, tileZ), laid out as in AetherColumnTile, e.g. evaluated from the game's own terrain height
 * function; aether picks the top/fill materials and forest canopy from the loaded preview raster's climate, slope and
 * shore rules (preview palette). Needs aether_preview_load_raster. Replaces the tile if present.
 */
AETHER_API AetherStatus aether_upload_column_heights(AetherContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                     const float* surface, const float* water);
/* As aether_upload_column_heights, plus per-column materials (palette ids, 0 = the dressing's own) for grass-family
 * tops and broadleaf canopy: the biome's tinted grass and foliage, so the far field matches the near field's tints.
 * grassMat / foliageMat may be NULL. */
AETHER_API AetherStatus aether_upload_column_heights2(AetherContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                      const float* surface, const float* water, const uint16_t* grassMat,
                                                      const uint16_t* foliageMat);
/*
 * (abi 0.10) As aether_upload_column_heights2, plus the surface blocks the world's own material rules paint where the
 * caller knows them: topMat / fillMat (palette ids, 0 = the dressing's) and flags per column (may be NULL): bit 0 snow
 * lies there (forest crowns over it are snowy too), bit 1 its water is frozen over. Rivers keep their flowing water
 * (direction and speed from the water levels and the raster's channels), lakes and the sea still water.
 */
AETHER_API AetherStatus aether_upload_column_heights3(AetherContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                      const float* surface, const float* water, const uint16_t* topMat,
                                                      const uint16_t* fillMat, const uint16_t* grassMat,
                                                      const uint16_t* foliageMat, const uint8_t* flags);
#define AETHER_COLUMN_SNOW 0x1u
#define AETHER_COLUMN_FROZEN 0x2u
/* (abi 0.10) Falling water: a material class drawn as a white, streaming sheet (waterfalls stamped as structures). */
#define AETHER_CLASS_FALLS 5u
/*
 * Buildings over tile (lod, tileX, tileZ), 256 x 256 columns laid out as in AetherColumnTile: walls stand on the
 * ground up to wallTop[c], the roof above them up to roofTop[c] (roofTop <= the ground, or NaN: no building there).
 * Voxels below wallTop are wallMat[c], the rest roofMat[c]. Kept across later uploads of the tile's ground (a refined
 * tile keeps its buildings) until the tile leaves the window; wallTop == NULL removes them. Copied before return.
 */
AETHER_API AetherStatus aether_upload_column_structures(AetherContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                        const float* wallTop, const float* roofTop,
                                                        const uint16_t* wallMat, const uint16_t* roofMat);
/* (abi 0.10) As aether_upload_column_structures, plus base (may be NULL): per column the bottom of the span, for
 * structures that do not stand on the ground (an arch's deck, a tree's crown, a floating rock: air between the ground and
 * base); NaN or <= the ground = from the ground. */
AETHER_API AetherStatus aether_upload_column_structures2(AetherContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                         const float* wallTop, const float* roofTop,
                                                         const uint16_t* wallMat, const uint16_t* roofMat,
                                                         const float* base);
/* Sets palette entries first .. first + count - 1 (first >= 1), growing the palette with grey where it skips ids:
 * colours added over the current palette (building walls and roofs). */
AETHER_API AetherStatus aether_set_materials(AetherContext* ctx, uint32_t first, const AetherMaterial* materials,
                                             uint32_t count);
/*
 * The sun map: a size x size occlusion map along the key light (sun, or moon at night) over halfExtent blocks either
 * side of the camera, traced through the far field and refreshed progressively (in full when the camera moves
 * 256 blocks or the size changes), for a shader pack to shadow the near field with beyond its own shadow map:
 * mountains over valleys, cloud shadows. Texel (i, j): RG = the first occluder's coordinate along the light from
 * the map centre (-1e30 none), cloud transmittance. Row `size` holds the parameters: (cx, cy) (cz, halfExtent)
 * (Xx, Xy) (Xz, Yx) (Yy, Yz) (Lx, Ly) (Lz, 1): centre in world blocks, the map's axes X, Y and the light L. A point
 * p is behind an occluder when dot(p - c, L) < R - bias at (u, v) = (dot(p - c, X), dot(p - c, Y)) / halfExtent.
 * size 0 turns it off (default). On the GL path it is copied into AetherGlFrameIO.dstSunMap (RG32F, size x size+1).
 */
AETHER_API AetherStatus aether_set_sun_map(AetherContext* ctx, uint32_t size, float halfExtent);
/* The sun map as last computed (size * (size + 1) * 2 floats into out, count in floats). Waits for the GPU. */
AETHER_API AetherStatus aether_read_sun_map(AetherContext* ctx, float* out, uint32_t count);
/*
 * (abi 0.10) Refinement fades: a region rebuilt over a resident one (an exact tile replacing the raster stand-in,
 * buildings or landmarks arriving on a tile, a finer LOD entering the window over the coarser one) dissolves from what
 * was there to the new content per pixel over fadeSeconds (the old region stays packed meanwhile; a fade already running
 * keeps its start, so a tile refined twice in a row dissolves once). 0 (default) swaps at once.
 */
AETHER_API AetherStatus aether_set_transitions(AetherContext* ctx, float fadeSeconds);
/*
 * (abi 0.10) Far lights, in groups (a settlement, the sites of an area, the falls of a region ...): replaces group
 * `group`'s lights (count 0 removes the group). Any thread; copied. Each frame aether picks the lights in view beyond
 * the near field (merged into clusters of about a pixel beyond clusterDistance), at most maxPerFrame of them, brightest
 * first (aether_set_light_budget; defaults 4096 and 6000 blocks).
 */
AETHER_API AetherStatus aether_set_light_group(AetherContext* ctx, uint32_t group, const AetherLight* lights, uint32_t count);
AETHER_API AetherStatus aether_set_light_budget(AetherContext* ctx, uint32_t maxPerFrame, float clusterDistance);
/* 1 if tile (lod, tileX, tileZ) is resident (uploaded and not evicted), else 0. */
AETHER_API int32_t aether_has_tile(AetherContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ);
/* Offline harness only: preview terrain height at a block column (for camera placement). */
AETHER_API float aether_preview_height(AetherContext* ctx, double x, double z);

/*
 * Exact seeded noise for the terrain height (shaders/aether_height.slang): the WorldNoise evaluations the mod's
 * TamrielHeight.height makes, for a batch of columns, bit-identical to Minecraft's NoiseStack of PerlinNoise layers
 * (see the kernel's header for the argument). The caller runs height() itself, served these values. Independent of
 * AetherContext: its own compute device; calls on one handle serialise, so any thread may call.
 *  AETHER_BACKEND_VULKAN  the kernel on a Vulkan 1.2 device with shaderFloat64 and the float controls it needs
 *                         (RTE rounding and signed-zero/inf/nan preservation, 32 and 64 bit; denormal behaviour is
 *                         not relied on); AETHER_ERR_UNSUPPORTED when there is none
 *  AETHER_BACKEND_CPU     the same kernel compiled to C++ (reference)
 * The caller must still verify the results against its own CPU path before trusting a device.
 */
typedef struct AetherHeight AetherHeight;
#define AETHER_HEIGHT_LAYER_WORDS 76u /* 32-bit words per noise layer, after the 8-word header */
#define AETHER_HEIGHT_SLOTS 10u       /* noise calls per column (S_* in the kernel) */
typedef struct AetherHeightTimes {
    double uploadMs;   /* host copy into staging + the device copy */
    double kernelMs;   /* device time of the dispatches (timestamps), or the CPU kernel's wall time */
    double readbackMs; /* the device copy back + host copy out */
    double totalMs;    /* wall time of the call */
} AetherHeightTimes;
AETHER_API AetherStatus aether_height_create(AetherBackend backend, AetherHeight** out);
AETHER_API void aether_height_destroy(AetherHeight* h);
AETHER_API const char* aether_height_last_error(AetherHeight* h);
/* Device description (static until destroy). */
AETHER_API const char* aether_height_device(AetherHeight* h);
/* The noise table (8-word header, then AETHER_HEIGHT_LAYER_WORDS per layer; ranges checked). Copied. */
AETHER_API AetherStatus aether_height_set_noise(AetherHeight* h, const uint32_t* table, uint32_t words);
/*
 * count columns, column k at block (x0 + (k % width) * step, z0 + (k / width) * step); slots[k]: bit s = evaluate
 * noise call s for column k. Out: values[s * count + k] (float; 0 where not asked), flags[k] = 1 when the kernel could
 * not prove a value of column k exact (the caller computes that column itself). width <= 65536. times may be NULL.
 */
AETHER_API AetherStatus aether_height_batch(AetherHeight* h, int32_t x0, int32_t z0, int32_t step, uint32_t width,
                                            uint32_t count, const uint32_t* slots, float* values, uint32_t* flags,
                                            AetherHeightTimes* times);

#ifdef __cplusplus
}
#endif
#endif
