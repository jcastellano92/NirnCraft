/*
 * atlas_core: NirnCraft's native far-field engine (distant terrain, curved-planet projection, cloud shell, far actors).
 * Plain C ABI so Java 25 can bind it with java.lang.foreign (no JNI glue). See docs/ATLAS.md.
 *
 * Ownership and threading
 *  - Every call takes an AtlasContext created by atlas_create; calls on one context are not reentrant except where
 *    marked "any thread" (those take an internal lock and only copy data in).
 *  - Buffers passed in are copied before the call returns; the caller keeps ownership.
 *  - World coordinates are Minecraft block coordinates (block 0,0 = Whiterun, -Z north, sea level 63, y -64..640).
 *
 * Authority: atlas_core never decides what the world contains. The Java generator (TamrielHeight, plans, caves,
 * structures) and loaded chunks are authoritative; they push column tiles and chunk sections here. The preview source
 * (atlas_preview_load_raster) exists only for offline harnesses and is labelled as such in every output.
 */
#ifndef ATLAS_H
#define ATLAS_H

#include <stdint.h>

#ifdef _WIN32
#  ifdef ATLAS_BUILD
#    define ATLAS_API __declspec(dllexport)
#  else
#    define ATLAS_API __declspec(dllimport)
#  endif
#else
#  define ATLAS_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* (major << 16) | (minor << 8) | patch. Bumped on any ABI change. 0.11 = 0.10 far content (lights, sites, actor
   flags, tile codec 3) + 0.10 failure handling (status codes, memory query, fault hook). 0.12 the moon, fill near;
   0.13 primary-ray jitter (atlas_set_jitter); 0.14 material shapes (atlas_set_material_shapes), ATLAS_FRAME_GROUPS;
   0.15 the near light (ATLAS_FRAME_NEAR_LIGHT: AtlasFrame.nearInfo, AtlasFrameOutput.nearLight, AtlasGlFrameIO
   nearInfoTex / dstNearLight; every new field is read only when the frame sets the flag, so a 0.14 caller's smaller
   structs stay valid); 0.16 the air (AtlasAir, atlas_set_air, atlas_air_*: a physical sky, sun and haze) and the
   near field's textures (atlas_set_material_textures); 0.17 the near field from atlas's own primary rays
   (ATLAS_FRAME_PRIMARY_NEAR / _LIT) over the GPU-built level-0 grid, and the sky's own light (atlas_set_sky_glow). */
#define ATLAS_ABI_VERSION 0x001100

typedef struct AtlasContext AtlasContext;

/*
 * Errors never abort the process: every entry point catches what goes wrong inside (allocation failures, a lost
 * device, driver errors) and returns one of these; the caller decides what to turn off.
 *  ATLAS_ERR_DEVICE_LOST    (abi 0.10) the Vulkan device was lost (VK_ERROR_DEVICE_LOST: a driver reset such as a
 *                            Windows TDR or an amdgpu GPU reset, or a fault in atlas's own work). The context is
 *                            dead: every GPU call on it returns this again; destroy it (safe) and, after a
 *                            cool-down, create a new one.
 *  ATLAS_ERR_OUT_OF_MEMORY  (abi 0.10) device or host memory ran out (VK_ERROR_OUT_OF_*_MEMORY, std::bad_alloc).
 *                            The context stays usable: retry with smaller targets / sun map / clipmap.
 *  ATLAS_ERR_TIMEOUT        (abi 0.10) the GPU did not finish atlas's work in time (a hung or stalled device).
 *                            Treated like a lost device: the context is dead.
 *  ATLAS_ERR_INTERNAL       (abi 0.10) an unexpected C++ exception inside atlas; the context may be inconsistent:
 *                            destroy it.
 */
typedef enum AtlasStatus {
    ATLAS_OK = 0,
    ATLAS_ERR_ARG = -1,
    ATLAS_ERR_STATE = -2,
    ATLAS_ERR_IO = -3,
    ATLAS_ERR_GPU = -4,
    ATLAS_ERR_UNSUPPORTED = -5,
    ATLAS_ERR_DEVICE_LOST = -6,
    ATLAS_ERR_OUT_OF_MEMORY = -7,
    ATLAS_ERR_TIMEOUT = -8,
    ATLAS_ERR_INTERNAL = -9,
} AtlasStatus;

typedef enum AtlasBackend {
    ATLAS_BACKEND_CPU = 0,    /* reference: the same Slang kernel compiled to C++ */
    ATLAS_BACKEND_VULKAN = 1, /* the same kernel compiled to SPIR-V */
} AtlasBackend;

/* Material classes, shared with the shaders (material.a in the G-buffer). */
typedef enum AtlasMaterialClass {
    ATLAS_CLASS_TERRAIN = 0,
    ATLAS_CLASS_FOLIAGE = 1,
    ATLAS_CLASS_WATER = 2,
    ATLAS_CLASS_EMISSIVE = 3,
} AtlasMaterialClass;

typedef struct AtlasMaterial {
    float albedo[3];   /* linear RGB */
    float roughness;   /* 0..1 */
    float emissive;    /* 0..1 intensity */
    uint32_t cls;      /* AtlasMaterialClass */
} AtlasMaterial;

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
typedef struct AtlasColumnTile {
    int32_t lod, tileX, tileZ;
    const float* surface;
    const float* water;
    const uint16_t* topMat;
    const uint16_t* fillMat;
    const float* canopy;
    const uint16_t* canopyMat;
} AtlasColumnTile;

/* Camera and frame description. Matrices are column-major float[16] (OpenGL/JOML convention). */
typedef struct AtlasFrame {
    double cameraPos[3];        /* world block coordinates */
    float invViewProj[16];      /* camera-relative (translation removed) inverse view-projection */
    uint32_t width, height;
    float nearFieldEnd;         /* blocks: where the rasterised near field stops and the marcher takes over */
    float maxDistance;          /* blocks */
    float planetRadius;         /* blocks; <= 0 disables curvature */
    float sunDir[3];            /* unit, world space, towards the sun */
    float timeSec;              /* animation clock (game ticks / 20) */
    float lodBias;              /* >= 1: voxel size per pixel footprint; 1 = one voxel per pixel */
    uint32_t flags;             /* ATLAS_FRAME_* */
    /* optional (ATLAS_FRAME_NEAR_DEPTH, host path): width*height view distances of the near field, row 0 at the
       top; pixels nearer than nearFieldEnd are covered and skip the far march. The GL path reads them from the
       shared nearDepth image instead. */
    const float* nearDepth;
    /* (abi 0.15; read only with ATLAS_FRAME_NEAR_LIGHT, host path): width*height texels of the near field's surface
       info, laid out as nearDepth, in the encoding of AtlasGlFrameIO.nearInfoTex (RGBA8 packed, r in the low byte).
       NULL: no near light this frame (the flag is dropped). */
    const uint32_t* nearInfo;
} AtlasFrame;

#define ATLAS_FRAME_CLOUDS   0x1u
#define ATLAS_FRAME_SHADOWS  0x2u
#define ATLAS_FRAME_ATMOS    0x4u
#define ATLAS_FRAME_ACTORS   0x8u
#define ATLAS_FRAME_SKY      0x10u   /* draw atlas's sky behind misses (harness); in game the pack draws its own */
#define ATLAS_FRAME_NEAR_DEPTH 0x20u /* skip pixels the near field covers (see AtlasFrame.nearDepth) */
#define ATLAS_FRAME_FLAT_NORMALS 0x40u /* face normals only: cheaper, blockier in the distance */
#define ATLAS_FRAME_CLOUDS_QUARTER 0x80u /* cloud pass at quarter resolution (default half): about 4x cheaper */
#define ATLAS_FRAME_DEBUG_LOD 0x100u
#define ATLAS_FRAME_DEBUG_WEATHER 0x200u
#define ATLAS_FRAME_DEBUG_ALBEDO 0x400u      /* surfaces output their albedo (offline G-buffers: the pack lab) */
#define ATLAS_FRAME_SEE_THROUGH_WATER 0x800u /* rays pass water (the bed under it, offline) */
#define ATLAS_FRAME_FILL_NEAR 0x2000u        /* (abi 0.12) with NEAR_DEPTH: where the near field drew nothing (a chunk not
                                                 loaded yet) the far field marches from the camera and fills it; set
                                                 only when the camera is in the open (the far field has no caves) */
#define ATLAS_FRAME_LOD_DITHER 0x1000u       /* (abi 0.10) dithered LOD transitions: each pixel's LOD segment grid
                                                 starts at its own phase, so a level change is a stipple band, not a line.
                                                 Superseded: every ray's level is now continuous (its footprint and what
                                                 is resident), picked between the two levels around it per pixel (TAA
                                                 averages it to a gradient), with or without the flag */
#define ATLAS_FRAME_GROUPS 0x4000u           /* (abi 0.14) primary rays trace the content-shaped groups (docs/ATLAS-LOD.md):
                                                 per region a hierarchy of boxes over its micro-voxels, each drawn whole
                                                 with its face textures where its flattening error is under the pixel,
                                                 else opened; the first frame with it starts building them (nearest
                                                 first, on atlas_update); regions without one yet draw as before. The
                                                 outputs, shadows, sun map and hand-off are unchanged. An older core
                                                 ignores the bit. */
#define ATLAS_FRAME_VANILLA_LIGHT 0x10000u    /* (abi 0.14, optional) Minecraft's own look, for a host without a shader pack (its
                                                 near field is vanilla): the texel times the fixed face shade and the
                                                 lightmap's sky brightness for the time of day; no sun shadows, sky
                                                 visibility, light through leaves or aerial haze (the far field had them
                                                 in vanilla, a stark line where vanilla's chunks meet it). Older cores
                                                 ignore the bit. */
#define ATLAS_FRAME_LIGHT_LOW 0x8000u        /* (abi 0.14, optional) cheap far light, a quality step for the host's
                                                 adaptive controller: a constant sky occlusion and 4-tap AO instead of
                                                 traced sky visibility, binary sun shadows that foliage stops, block
                                                 lights unshadowed. Without it (the default) the far field traces its
                                                 light (docs/ATLAS-LOD.md, "Far light"). An older core ignores the bit. */
/*
 * (abi 0.15, optional) The near light: one light for near and far. With ATLAS_FRAME_NEAR_DEPTH, for every pixel the near
 * field covers atlas lights the near surface with the far field's own lighting functions: from the surface point
 * p = camera + dir x nearDepth (the view ray, curvature as the far field's) and its normal N (nearInfo), what reaches a
 * WHITE diffuse surface there: the key light (sun, or the moon at night) x max(N.L, 0) x its traced visibility (the far
 * field's secondary rays from p nudged off the surface along N, cloud shadows with ATLAS_FRAME_CLOUDS), plus the traced
 * sky ambient (sky visibility over the hemisphere about N, plus the pack's ambient floor), plus far lights; NOT block
 * light (the host adds it from its own light data). ATLAS_FRAME_LIGHT_LOW takes the far field's cheap terms (binary sun
 * shadow, constant sky occlusion). ATLAS_FRAME_VANILLA_LIGHT does not change it (the near pass replaces vanilla's light).
 * Outputs per covered pixel:
 *   nearLight  RGBA16F (GL) / RGBA32F (host): rgb = that irradiance (linear, the far field's radiance units), a = the key
 *              light's visibility 0..1 (shadows x cloud shadow); 0 at every pixel the near field does not cover
 *   color      (the frame's colour output, at covered pixels only) rgb = aerial inscatter for the distance, a = its
 *              transmittance T, with any cloud in front of the surface folded in (rgb = cloud + inscatter x cloud
 *              transmittance, a = T x cloud transmittance), so the host composites
 *                  final = albedo x nearLight.rgb x color.a + color.rgb   (+ block light x albedo x color.a)
 *              Pixels the near field does not cover keep the old meaning (premultiplied radiance, a = coverage), and
 *              without the flag a covered pixel stays the premultiplied cloud (rgb, coverage) as before.
 * Host path: AtlasFrame.nearInfo in, AtlasFrameOutput.nearLight out; GL path: AtlasGlFrameIO.nearInfoTex in,
 * .dstNearLight out (both needed, else the flag is dropped for the frame). An older core ignores the bit and the fields.
 */
#define ATLAS_FRAME_NEAR_LIGHT 0x20000u

/*
 * (abi 0.17) The near field drawn by atlas's own primary rays (the GPU-built level-0 grid: real
 * blocks, shapes, the 16 x 16 textures of atlas_set_material_textures) instead of the host's raster. Every pixel is
 * traced from the camera (AtlasFrame.nearFieldEnd is then only where near ends and far begins: no near depth is read).
 * Set with ATLAS_FRAME_NEAR_LIGHT and a near-light output (AtlasFrameOutput.nearLight, AtlasGlFrameIO.dstNearLight; the
 * near inputs are not needed). A hit nearer than nearFieldEnd is a NEAR pixel:
 *  ATLAS_FRAME_PRIMARY_NEAR (a G-buffer, the host resolves it):
 *    color     rgb its albedo, LINEAR (textured), a = 1
 *    nearLight rgb the irradiance on a white diffuse surface there, a = the key light's visibility (the near light as
 *              ATLAS_FRAME_NEAR_LIGHT computes it: traced sun, sky, one bounce, the far lights; the air left out)
 *  ATLAS_FRAME_PRIMARY_NEAR_LIT (composited, a reference): color = albedo x light x T + inscatter, premultiplied, a = 1;
 *    nearLight as above
 *  normal    octahedral normal in rg, b = the material class (0 solid, 1 foliage, 2 water, 3 emissive, 4 plant, 5 falls)
 *            | 0x80: a NEAR pixel; a = the key light's visibility
 * Every other pixel (hits at or past nearFieldEnd, sky) is a FAR pixel exactly as without the flag (premultiplied
 * radiance with the air applied, a = coverage; class without 0x80). Under either flag the depth output of EVERY pixel
 * is the NDC depth (clip z / w) in the host's own projection (the inverse of AtlasFrame.invViewProj: reverse-Z if that
 * is); a point past its far plane (or the sky) MINUS its distance (blocks; the sky -1e7): behind all the host rasters,
 * its distance kept for the host's haze. Near primary rays pass water (the host rasters the water surface itself).
 */
#define ATLAS_FRAME_PRIMARY_NEAR 0x80000u
#define ATLAS_FRAME_PRIMARY_NEAR_LIT 0x100000u

/*
 * Frame outputs (any pointer may be NULL to skip). Layout matches the shared G-buffer images:
 *  colorLinear RGBA32F linear HDR radiance, premultiplied over whatever is drawn behind (near field or the pack's
 *                      sky); a = coverage (1 on far terrain, cloud opacity elsewhere). The GL image is RGBA16F.
 *  color       RGBA8   the same through the harness tonemapper (c / (1 + c), gamma 2.2), for PNGs
 *  normal      RGBA8   octahedral normal in rg, material class in b, sun visibility (shadows) in a
 *  depth       float   view distance in blocks to the nearest thing drawn: far surface, far actor or the first
 *                      cloud (1e30 = nothing). A composite keeps a near pixel whenever it is nearer than this.
 */
typedef struct AtlasFrameOutput {
    uint32_t* color;
    uint32_t* normal;
    float* depth;
    float* colorLinear;
    float* nearLight;  /* (abi 0.15; read only with ATLAS_FRAME_NEAR_LIGHT) RGBA32F, see the flag; may be NULL */
} AtlasFrameOutput;

/* Weather fields exactly as WeatherFieldTexture uploads them (RGBA8, 128 x 128, world-fixed toroidal). */
typedef struct AtlasWeather {
    const uint32_t* nearField;  /* 64-block cells: r coverage, g storm tower, b curtain, a band/flash */
    const uint32_t* nearDyn;    /* r cloud base y/1024, g surface y/1024, b wind angle, a ground fog */
    const uint32_t* farField;   /* 512-block cells */
    const uint32_t* farDyn;
    float rainFactor;           /* 0..1 vanilla rain strength */
} AtlasWeather;

/* A far actor: a player or large creature beyond vanilla tracking range, drawn as an impostor. */
typedef struct AtlasActor {
    double pos[3];
    float yaw;
    float height, radius;       /* blocks */
    uint32_t kind;              /* impostor atlas row; 0 = generic humanoid */
    uint32_t tint;              /* RGBA8 */
    /* (abi 0.10, in what was padding; zero = the old behaviour) bits 0-7: transparency 0..255 (the impostor dissolves
       per pixel: 0 opaque, 255 gone; a cross-fade with the real entity at the tracking boundary), bits 8-9: gait
       (ATLAS_GAIT_*), bits 16-31: animation phase seed (e.g. the entity id), so a flock does not beat in step */
    uint32_t flags;
} AtlasActor;
#define ATLAS_GAIT_STILL 0u  /* on the ground: dragons fold their wings */
#define ATLAS_GAIT_GLIDE 1u
#define ATLAS_GAIT_FLAP 2u

/*
 * (abi 0.10) A far light: something that glows from kilometres away (a town's lit windows and street lamps at night,
 * braziers and camp fires, lava), or, for ATLAS_LIGHT_MIST, the spray of a waterfall lit by the sun. Drawn by the far
 * kernel as a glow on top of the far field where nothing nearer hides it, at least a pixel wide whatever the distance,
 * spreading into a halo through the haze; never inside the near field's radius (the game draws its own lights there).
 */
typedef struct AtlasLight {
    double pos[3];        /* world block coordinates */
    float radius;         /* blocks: the glowing thing's size (a window 1, a brazier 1.5, a lava pool 6, falls mist 12) */
    float intensity;      /* relative radiance (1 = a lantern) */
    uint32_t rgb;         /* sRGB colour, r in the low byte */
    uint32_t kind;        /* ATLAS_LIGHT_* */
} AtlasLight;
#define ATLAS_LIGHT_NIGHT 0u  /* windows, lamps: lit from dusk to dawn */
#define ATLAS_LIGHT_FIRE 1u   /* braziers, camp fires: always lit, flickering (they read at night) */
#define ATLAS_LIGHT_LAVA 2u   /* always lit, a slow pulse */
#define ATLAS_LIGHT_MIST 3u   /* spray, lit by the sun and the sky: a white haze by day, faint by night */
#define ATLAS_LIGHT_BLOCK 4u  /* a block's own emitter (the grid's: a torch, a lantern): steady, its own hue, no glow
                                 drawn over it (its surface draws it) */

typedef struct AtlasStats {
    uint32_t regions, regionsDirty;
    uint64_t nodeBytes, leafBytes, materialBytes;
    double lastBuildMs, lastFrameMs;
    uint64_t raySteps;
} AtlasStats;

ATLAS_API uint32_t atlas_abi_version(void);
/* Human-readable build string, static storage. */
ATLAS_API const char* atlas_build_info(void);

ATLAS_API AtlasStatus atlas_create(AtlasBackend backend, AtlasContext** out);
/* The file the Vulkan pipelines are kept in between runs (the context's and the generator's: the driver's compile of
   the big kernels takes seconds to minutes the first time), set before atlas_create / atlas_gen_create*; the
   ATLAS_PIPELINE_CACHE environment variable wins. NULL or "": none. (The loading phase: the game's instance.) */
ATLAS_API AtlasStatus atlas_set_pipeline_cache(const char* path);
/* The native build's content hash (its sources and shaders): part of every cache's key (the self-baking cache, the
   horizon cache, the cold tier's generated records), so a code change starts them fresh. */
ATLAS_API uint64_t atlas_build_hash(void);
ATLAS_API void atlas_destroy(AtlasContext* ctx);
/* Last error message for this context, static until the next call. NULL: why this thread's last atlas_create
   failed (e.g. no usable Vulkan device, and why each one was rejected). */
ATLAS_API const char* atlas_last_error(AtlasContext* ctx);

/* Material palette: ids 1..count (0 is air). Replaces the whole palette. */
ATLAS_API AtlasStatus atlas_set_palette(AtlasContext* ctx, const AtlasMaterial* materials, uint32_t count);

/* Authoritative data in (any thread). */
ATLAS_API AtlasStatus atlas_upload_column_tile(AtlasContext* ctx, const AtlasColumnTile* tile);
/* A 16^3 chunk section of palette material ids (0 = air), index x + 16 * (z + 16 * y) like vanilla. Overrides tiles. */
ATLAS_API AtlasStatus atlas_upload_section(AtlasContext* ctx, int32_t sx, int32_t sy, int32_t sz,
                                              const uint16_t* mats);
/* (abi 0.18, additive) The same with flags: ATLAS_SECTION_REGENERABLE, the client generates it again from the seed
   (its host copy is dropped, not kept or cached, unless an edit changes it). atlas_upload_section is flags 0: kept in
   the host's tiers, the cold tier on disk when one is set (atlas_set_cache). */
#define ATLAS_SECTION_REGENERABLE 1u
ATLAS_API AtlasStatus atlas_upload_section_flags(AtlasContext* ctx, int32_t sx, int32_t sy, int32_t sz,
                                                    const uint16_t* mats, uint32_t flags);
/* (abi 0.18, additive) The host's tiers of real sections (docs/ATLAS-STATUS.md step 5): `dir` the per-world cache
   directory of the cold tier (NULL or "": none; in the game e.g. .minecraft/atlas-cache/<world id>), the world's seed
   and the generator's version (what was cached for another world is another directory), the host RAM budget of the
   warm tier and the disk budget of the cold one (bytes; 0: the defaults, a 32nd of the host heap atlas_memory_info
   reports within 256 MiB..2 GiB; 4 GiB). Call before the first frame, or again to change them (the cold tier's index
   is read again). Environment for tools: ATLAS_CACHE_DIR, ATLAS_HOST_SECTIONS_MB, ATLAS_COLD_MB. */
ATLAS_API AtlasStatus atlas_set_cache(AtlasContext* ctx, const char* dir, uint64_t seed, uint32_t genVersion,
                                      uint64_t hostBytes, uint64_t diskBytes);
/* One block edit (any thread): marks the brick dirty in every LOD that contains it. */
ATLAS_API AtlasStatus atlas_notify_block(AtlasContext* ctx, int32_t x, int32_t y, int32_t z, uint16_t mat);
ATLAS_API AtlasStatus atlas_set_weather(AtlasContext* ctx, const AtlasWeather* weather);
/* Cloud noise volume: 64^3 RGBA8, index (z * 64 + y) * 64 + x (the mod's cloud_noise_volume atlas, unpacked). */
ATLAS_API AtlasStatus atlas_set_cloud_noise(AtlasContext* ctx, const uint32_t* rgba);
ATLAS_API AtlasStatus atlas_set_actors(AtlasContext* ctx, const AtlasActor* actors, uint32_t count);

/* Rebuild dirty regions around `centre` (blocks) for the configured clipmap; budgetMs <= 0 means build everything. */
ATLAS_API AtlasStatus atlas_update(AtlasContext* ctx, const double centre[3], double budgetMs);
/* Render one frame into host buffers (harness path; the game path renders into shared GPU images). */
ATLAS_API AtlasStatus atlas_render(AtlasContext* ctx, const AtlasFrame* frame, const AtlasFrameOutput* out);
ATLAS_API AtlasStatus atlas_get_stats(AtlasContext* ctx, AtlasStats* out);

/*
 * Game path (Vulkan backend; call on the render thread with the game's OpenGL 4.5 context current; zero-copy when
 * that context is on the same GPU and supports memory objects, else host copies, see atlas_info). atlas_create
 * on that thread picks the Vulkan device the context runs on. atlas_gl_attach creates color (RGBA16F linear, premultiplied), normal (RGBA8) and depth (R32F, view
 * distance in blocks) images, plus nearDepth (R32F) that the GL side writes with the near field's view distance,
 * all shared with that context; it returns their GL texture names (rows bottom-up, GL convention). Each
 * atlas_gl_render queues the frame and a GL-side semaphore wait: GL commands issued after it see the frame,
 * and the CPU never blocks on the GPU. Re-attach on resize; detach before destroying the GL context.
 * The CPU backend (a machine without a usable Vulkan device) takes the same calls with the same textures and outputs:
 * the near depth / info are linearised on the GL side and read back through a pixel-pack buffer and a fence, the
 * frame is rendered on a worker thread (below normal priority, all hardware threads but two) and uploaded when done,
 * so the textures show a frame two or more calls old (the first frame after an attach is rendered in the call);
 * ATLAS_CPU_GL=sync renders every frame in the call (no lag; the CPU waits for GL and the render). atlas_info's
 * "cpuGl" block has the worker's times and the lag. The stats' lastFrameMs is the worker's time per frame.
 */
typedef struct AtlasGlTargets {
    uint32_t color, normal, depth, nearDepth;
    uint32_t width, height;
} AtlasGlTargets;
ATLAS_API AtlasStatus atlas_gl_attach(AtlasContext* ctx, uint32_t width, uint32_t height, AtlasGlTargets* out);
ATLAS_API AtlasStatus atlas_gl_render(AtlasContext* ctx, const AtlasFrame* frame);

/*
 * The game's side of a GL frame (all optional; zero = unused):
 *  nearDepthTex      the rasterised near field's depth texture (same size as the targets), or
 *                    ATLAS_GL_BOUND_DEPTH: the depth attachment of the draw framebuffer bound when atlas is called
 *                    (e.g. a shader pack's gbuffer target, whose texture the mod never sees). atlas linearises it
 *                    into the shared nearDepth image with a small GL compute pass, so covered pixels skip the far
 *                    march (sets ATLAS_FRAME_NEAR_DEPTH for the frame). Sky = depth 1 (or 0 when reversedZ).
 *  depthZeroToOne    1 when that depth was written with [0,1] clip depth (glClipControl), 0 for [-1,1],
 *                    ATLAS_GL_DEPTH_AUTO to read the context's GL_CLIP_DEPTH_MODE
 *  reversedZ         1 when near = 1, far = 0
 *  nearInvProjection inverse of the projection used for that depth, column-major, camera-relative view space
 *  dstColor/Normal/Depth  GL textures (same size and format as the shared ones) that receive copies once the
 *                    frame is done, e.g. the textures a shader pack samples through customTexture.
 *  nearInfoTex       (abi 0.15, read only with ATLAS_FRAME_NEAR_LIGHT) the near field's surface info, a GL texture
 *                    the size of nearDepthTex's (bottom-up rows), internal format RGBA8 (GL_RGBA8 / UNORM):
 *                      r = block light level / 15, g = sky light level / 15 (atlas does not read r and g yet),
 *                      b, a = the surface's world-space unit normal, octahedral, as atlas_shared.slang octEncode:
 *                        p = n.xz / (|n.x| + |n.y| + |n.z|);
 *                        if (n.y < 0) p = (1 - |p.yx|) * (p.x >= 0 ? 1 : -1, p.y >= 0 ? 1 : -1);
 *                        (b, a) = p * 0.5 + 0.5
 *                      (decoded e = (b, a) * 2 - 1, n = (e.x, 1 - |e.x| - |e.y|, e.y), if n.y < 0 then
 *                      n.xz = (1 - |n.zx|) * sign(n.xz), normalised). atlas takes the texel its near depth took
 *                      (the farthest of the four samples over its pixel), 0 = none (no near light).
 *  dstNearLight      (abi 0.15, read only with ATLAS_FRAME_NEAR_LIGHT) GL texture, RGBA16F at atlas's resolution, that
 *                    receives the near light (see ATLAS_FRAME_NEAR_LIGHT); 0 = none (no near light). On host copies
 *                    the near depth and info are read back to the CPU for it (the CPU waits for the GL near pass).
 * atlas restores every piece of GL state it touches (program, texture/sampler units 14 and 15, image units 6 and 7,
 * pixel pack state).
 */
#define ATLAS_GL_BOUND_DEPTH 0xFFFFFFFFu
#define ATLAS_GL_DEPTH_AUTO 0xFFFFFFFFu
typedef struct AtlasGlFrameIO {
    uint32_t nearDepthTex;
    uint32_t depthZeroToOne;
    uint32_t reversedZ;
    uint32_t reserved;
    float nearInvProjection[16];
    uint32_t dstColor, dstNormal, dstDepth;
    uint32_t dstSunMap; /* RG32F size x size+1 (atlas_set_sun_map), or 0 */
    uint32_t nearInfoTex, dstNearLight; /* (abi 0.15, read only with ATLAS_FRAME_NEAR_LIGHT) */
} AtlasGlFrameIO;
ATLAS_API AtlasStatus atlas_gl_render_io(AtlasContext* ctx, const AtlasFrame* frame, const AtlasGlFrameIO* io);
ATLAS_API void atlas_gl_detach(AtlasContext* ctx);

/*
 * (abi 0.8) What this context runs on, as one line of JSON for logs: backend, device name and type, vendor id,
 * driver name and version, Vulkan API version, device/driver UUIDs, whether the device was picked because the GL
 * context current at atlas_create runs on it, and after atlas_gl_attach the interop mode:
 *   "zero-copy (win32 handles)" | "zero-copy (opaque fds)"  shared images + semaphores, nothing touches the CPU
 *   "host-copy"   the driver pair cannot share memory (no EXT_memory_object_{win32,fd} / VK_KHR_external_memory_*,
 *                 formats not exportable, or the GL context on another GPU): frames are read back and uploaded with
 *                 glTextureSubImage2D (interopReason says why; hostCopy.waitMs / uploadMs the last frame's CPU cost;
 *                 the near-depth skip is off, the composite's depth test still orders near over far; with
 *                 ATLAS_FRAME_NEAR_LIGHT the near depth and info are read back for it and the skip is on)
 * plus the GL renderer / version / window system ("wgl", "egl", "glx") and every Vulkan device seen. Static storage
 * per context until the next call. Forcing a mode for tests: ATLAS_INTEROP=hostcopy|zerocopy in the environment.
 */
ATLAS_API const char* atlas_info(AtlasContext* ctx);

/* Offline harness only: load native/data/tamriel_raster.bin and synthesise preview column tiles from it. */
ATLAS_API AtlasStatus atlas_preview_load_raster(AtlasContext* ctx, const char* path);
/* Offline harness only: fill tiles of LOD `lod` covering [x0,x1) x [z0,z1) from the preview source. */
ATLAS_API AtlasStatus atlas_preview_generate(AtlasContext* ctx, int32_t lod, int32_t x0, int32_t z0, int32_t x1,
                                                int32_t z1);
/*
 * Exact geometry, preview dressing: the caller's surface and water heights (NaN = no water) for the 256 x 256 columns
 * of tile (lod, tileX, tileZ), laid out as in AtlasColumnTile, e.g. evaluated from the game's own terrain height
 * function; atlas picks the top/fill materials and forest canopy from the loaded preview raster's climate, slope and
 * shore rules (preview palette). Needs atlas_preview_load_raster. Replaces the tile if present.
 */
ATLAS_API AtlasStatus atlas_upload_column_heights(AtlasContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                     const float* surface, const float* water);
/* As atlas_upload_column_heights, plus per-column materials (palette ids, 0 = the dressing's own) for grass-family
 * tops and broadleaf canopy: the biome's tinted grass and foliage, so the far field matches the near field's tints.
 * grassMat / foliageMat may be NULL. */
ATLAS_API AtlasStatus atlas_upload_column_heights2(AtlasContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                      const float* surface, const float* water, const uint16_t* grassMat,
                                                      const uint16_t* foliageMat);
/*
 * (abi 0.10) As atlas_upload_column_heights2, plus the surface blocks the world's own material rules paint where the
 * caller knows them: topMat / fillMat (palette ids, 0 = the dressing's) and flags per column (may be NULL): bit 0 snow
 * lies there (forest crowns over it are snowy too), bit 1 its water is frozen over. Rivers keep their flowing water
 * (direction and speed from the water levels and the raster's channels), lakes and the sea still water.
 */
ATLAS_API AtlasStatus atlas_upload_column_heights3(AtlasContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                      const float* surface, const float* water, const uint16_t* topMat,
                                                      const uint16_t* fillMat, const uint16_t* grassMat,
                                                      const uint16_t* foliageMat, const uint8_t* flags);
#define ATLAS_COLUMN_SNOW 0x1u
#define ATLAS_COLUMN_FROZEN 0x2u
/* (abi 0.13) the caller stamps this column's trees itself (as structures, where the world grows them): no forest
   canopy from the dressing here */
#define ATLAS_COLUMN_NO_CANOPY 0x4u
/*
 * (abi 0.13) As atlas_upload_column_structures2, with flags: ATLAS_STRUCTURES_REPLACE_CANOPY = these are the tile's
 * real trees: the dressing's canopy of the tile goes when they go up (and stays gone when the ground is uploaded again),
 * so a tile keeps its canopy until its trees replace it (no bare band while they are being built).
 */
#define ATLAS_STRUCTURES_REPLACE_CANOPY 0x1u
ATLAS_API AtlasStatus atlas_upload_column_structures3(AtlasContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                         const float* wallTop, const float* roofTop,
                                                         const uint16_t* wallMat, const uint16_t* roofMat,
                                                         const float* base, uint32_t flags);
/* (abi 0.10) Falling water: a material class drawn as a white, streaming sheet (waterfalls stamped as structures). */
#define ATLAS_CLASS_FALLS 5u
/*
 * Buildings over tile (lod, tileX, tileZ), 256 x 256 columns laid out as in AtlasColumnTile: walls stand on the
 * ground up to wallTop[c], the roof above them up to roofTop[c] (roofTop <= the ground, or NaN: no building there).
 * Voxels below wallTop are wallMat[c], the rest roofMat[c]. Kept across later uploads of the tile's ground (a refined
 * tile keeps its buildings) until the tile leaves the window; wallTop == NULL removes them. Copied before return.
 */
ATLAS_API AtlasStatus atlas_upload_column_structures(AtlasContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                        const float* wallTop, const float* roofTop,
                                                        const uint16_t* wallMat, const uint16_t* roofMat);
/* (abi 0.10) As atlas_upload_column_structures, plus base (may be NULL): per column the bottom of the span, for
 * structures that do not stand on the ground (an arch's deck, a tree's crown, a floating rock: air between the ground and
 * base); NaN or <= the ground = from the ground. */
ATLAS_API AtlasStatus atlas_upload_column_structures2(AtlasContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                         const float* wallTop, const float* roofTop,
                                                         const uint16_t* wallMat, const uint16_t* roofMat,
                                                         const float* base);
/* Sets palette entries first .. first + count - 1 (first >= 1), growing the palette with grey where it skips ids:
 * colours added over the current palette (building walls and roofs). */
ATLAS_API AtlasStatus atlas_set_materials(AtlasContext* ctx, uint32_t first, const AtlasMaterial* materials,
                                             uint32_t count);
/*
 * The sun map: a size x size occlusion map along the key light (sun, or moon at night) over halfExtent blocks either
 * side of the camera, traced through the far field and refreshed progressively (in full when the camera moves
 * 256 blocks or the size changes), for a shader pack to shadow the near field with beyond its own shadow map:
 * mountains over valleys, cloud shadows. Texel (i, j): RG = the first occluder's coordinate along the light from
 * the map centre (-1e30 none), cloud transmittance. Row `size` holds the parameters: (cx, cy) (cz, halfExtent)
 * (Xx, Xy) (Xz, Yx) (Yy, Yz) (Lx, Ly) (Lz, 1): centre in world blocks, the map's axes X, Y and the light L. A point
 * p is behind an occluder when dot(p - c, L) < R - bias at (u, v) = (dot(p - c, X), dot(p - c, Y)) / halfExtent.
 * size 0 turns it off (default). On the GL path it is copied into AtlasGlFrameIO.dstSunMap (RG32F, size x size+1).
 */
ATLAS_API AtlasStatus atlas_set_sun_map(AtlasContext* ctx, uint32_t size, float halfExtent);

/**
 * (abi 0.17, optional: the host binds it if present) An eclipse's shadow on the land: up to two occulting spheres (the
 * moons near the sun), each {centre relative to the camera in km (world axes), radius in km}, radius 0: none; and the
 * km a world block in that geography. Each surface's key light and each point of the air's inscatter take the share of
 * the sun's disc seen past them from where they are (an umbra's footprint dark, the air outside it lit: a total
 * eclipse's horizon glow); the sky's light on faces at the eye takes the eye's share (never below a twilight's floor).
 * All radii 0 (the default): no eclipse. Kept until set again.
 */
ATLAS_API AtlasStatus atlas_set_eclipse(AtlasContext* ctx, const float occulters[8], float kmPerBlock);

/*
 * (abi 0.12) The night's key light as the host's sky has it (the shader pack's lunar key: the brighter moon's
 * direction and the moons' light), so the far field lights the night exactly like the near field. dir: unit, world
 * space, towards the moon; radiance: linear RGB before the night fade. Not called (or a zero radiance): the old fixed
 * moonlight opposite the sun. Also the sun map's light at night.
 */
ATLAS_API AtlasStatus atlas_set_moon(AtlasContext* ctx, const float dir[3], const float radiance[3]);
/*
 * (abi 0.13) The next frames' sub-pixel offset of the primary rays, in pixels of atlas's render target (-0.5..0.5 each),
 * for a host that resolves frames temporally (the shader pack's TAA): over a jitter sequence the far field's
 * silhouettes and distant detail are sampled across each pixel instead of at its centre. Reprojection (the cloud
 * history) keeps the unjittered camera, and the fixed per-pixel dithers then vary per frame for the host to resolve.
 * (0, 0), the default: no jitter, dithers fixed on screen.
 */
ATLAS_API AtlasStatus atlas_set_jitter(AtlasContext* ctx, float x, float y);

/*
 * (abi 0.16) The air: one physical atmosphere for the sky, the sun's colour and strength (the key light is the sun's
 * transmitted light), the aerial perspective and the sky's light on surfaces (native/src/air.hpp: Rayleigh, Mie and
 * ozone over a spherical planet, single scattering marched, multiple scattering from Hillaire's LUT). Regional
 * variation is what is in the air, set by the climate (AtlasAir.java maps temperature, humidity, the aerosol band,
 * rain): molecules follow pressure and temperature, the aerosols' amount, size (Angstrom exponent), colour (albedo per
 * channel) and glow (asymmetry) follow humidity and the aerosol's kind (mist and sea salt whitish, dust pale and
 * tinted, smoke and ash reddening the sun). atlas_air_defaults fills clean continental air.
 */
typedef struct AtlasAir {
    float airDensity;        /* molecules: sea-level density over the standard atmosphere's (1) */
    float aerosolDepth;      /* aerosol optical depth at 550 nm, the vertical column: 0.05 clean, 0.3 humid haze,
                                0.8 dust, 1.5 smoke */
    float angstrom;          /* its Angstrom exponent: 0 coarse dust (grey), 0.4 sea salt / mist, 1.3 haze, 1.8 smoke */
    float aerosolAlbedo[3];  /* single-scattering albedo per channel (r, g, b): 0.99 water, dust absorbs blue */
    float aerosolG;          /* Cornette-Shanks asymmetry 0.6 .. 0.9: the glow round the sun */
    float aerosolHeight;     /* aerosol scale height, km: 0.8 fog, 1.2 boundary layer, 2.5 dust and smoke */
    float ozone;             /* ozone column over 300 DU (1) */
    float groundAlbedo;      /* the ground under the sky (0.25) */
    float airScale;          /* km of air per block (0.0025: the world is a 1 : 2.5 model of its air) */
} AtlasAir;
ATLAS_API void atlas_air_defaults(AtlasAir* out);
/*
 * The climate's handle on the air (air.hpp fromClimate): the weather at the eye in, the air out. Aerosols are an
 * external mixture of known kinds: the region's background by its aerosol band (continental haze, clean arctic air,
 * desert dust, volcanic ash) plus what the weather adds (sea salt by the coast, a dust storm, smoke and ash, mist,
 * rain, blowing snow); the water-loving ones swell with humidity (more, larger, whiter); molecules follow p / T.
 */
typedef struct AtlasClimate {
    float temperatureC;      /* the air at the eye */
    float relativeHumidity;  /* 0..1 */
    float pressure;          /* sea-level pressure over the standard atmosphere's (1) */
    float background[4];     /* the aerosol band's weights (WeatherFieldTexture.aerosolBand: water, snow, sand, ash),
                                eased by the host; summing to 1 */
    float dust, ash, snow, rain, fog, coast;  /* 0..1: a dust storm, smoke and ash, falling snow, rain, mist, the sea */
} AtlasClimate;
ATLAS_API AtlasStatus atlas_air_from_climate(const AtlasClimate* climate, AtlasAir* out);
/* The air the context's frames are drawn in (from the next frame). Builds the multiple-scattering LUT (~10 ms on all
   threads): set it when the air changes, not every frame. Not called: atlas_air_defaults. */
ATLAS_API AtlasStatus atlas_set_air(AtlasContext* ctx, const AtlasAir* air);
/*
 * (abi 0.17) The sky's own light: what the host's sky emits besides the air's scattering (NirnCraft's unified sky,
 * atlas_night.glsl: the aurora, the airglow, the stars and the galaxy, the second moon as a fill, meteors), integrated
 * by the host each frame, added to the air's sky light the far field lights with (skyAmbient): up[3] to the sky's
 * radiance over the horizon (an upward face's irradiance over pi, as atlas_air_light's [6..8]), ground[3] to the
 * ground's radiance under it ([9..11]). Set it every frame it changes (a meteor's flash, the aurora's surges); zeros,
 * the default: the air's light alone.
 */
ATLAS_API AtlasStatus atlas_set_sky_glow(AtlasContext* ctx, const float up[3], const float ground[3]);
/*
 * Context-free (any thread; the LUT of the last air asked for is cached): the light the far field lights with, for the
 * host's own shading of what it draws (entities, translucents): at world height eyeY (blocks) for the sun direction
 * and the moon (NULL dir / radiance: opposite the sun, the default moonlight). out[0..2] the key light's direction,
 * [3..5] its radiance (the sun's light through the air, or the moon's at night), [6..8] the sky's radiance over the
 * horizon (an upward face's irradiance over pi), [9..11] the ground's under it (skyAmbient = mix(ground, up, n.y/2+1/2)).
 */
ATLAS_API AtlasStatus atlas_air_light(const AtlasAir* air, const float sunDir[3], const float moonDir[3],
                                      const float moonRad[3], float eyeY, float out[12]);
/* Context-free: the per-km coefficients the marches take (FrameGpu air*, atlas_sky.glsl's AirRayleigh .. AirOzone):
   16 floats, rayleigh rgb + 1 / aerosol height, mie scattering rgb + g, mie extinction rgb + km per block, ozone rgb +
   ground albedo. */
ATLAS_API AtlasStatus atlas_air_coeffs(const AtlasAir* air, float out[16]);
/* Context-free: the multiple-scattering LUT for a host's own sky shader: 32 x 32 RGBA float (alpha 1), row-major,
   x = the sun's zenith cosine * 0.5 + 0.5, y = sqrt(altitude / 100 km), texel centres at both ends of each axis. */
ATLAS_API AtlasStatus atlas_air_multiscatter(const AtlasAir* air, float* rgba);
/* Context-free: the sky's radiance (rgb) along n unit directions from world height eyeY (the reference the host's sky
   shader is checked against: tools/dev/air_check.py). */
ATLAS_API AtlasStatus atlas_air_sky(const AtlasAir* air, const float sunDir[3], const float moonDir[3],
                                    const float moonRad[3], float eyeY, const float* dirs, uint32_t n, float* rgb);
/*
 * (abi 0.14) The shape and faces of a far material (a block state, docs/ATLAS-LOD.md "level -1"), for the content-shaped
 * groups (ATLAS_FRAME_GROUPS): its 4 x 4 x 4 occupancy from its real shape and a 4 x 4 tile of its real textures per
 * face, computed by the host from the block's model (AtlasLabExport's library entry). Voxels of real chunk sections
 * (atlas_upload_section) take their material's shape at LOD 0; everything else (column tiles, structures, the coarser
 * levels) draws a material as a full cube wearing its face tiles. A material without one stays a full cube in its
 * palette colour.
 *  occupancy  bit x + 4 * (z + 4 * y) of the block's micro-voxels; 0 = remove the shape (a plain full cube again)
 *  mean       ARGB (sRGB): the colour of a texel that shows nothing (alpha 0)
 *  flags      ATLAS_SHAPE_CUTOUT: texel alpha is how much of the micro-voxel is there (leaves: rays pass the rest,
 *             hashed per micro-voxel in world space)
 *  faces      down, up, north, south, west, east; 16 ARGB (sRGB, tints applied) texels each, laid out as AtlasLabExport
 *             lays them: (u, v) along (x, z) for down / up, (x, 3 - y) for north / south, (z, 3 - y) for west / east
 */
#define ATLAS_SHAPE_CUTOUT 0x1u
typedef struct AtlasMaterialShape {
    uint64_t occupancy;
    uint32_t mean;
    uint32_t flags;
    uint32_t faces[6][16];
} AtlasMaterialShape;
/* Sets the shapes of materials first .. first + count - 1 (first >= 1; any thread; copied). */
/*
 * (abi 0.16) The near field's textures: per material its six faces (down, up, north, south, west, east) at 16 x 16 ARGB
 * texels (sRGB, tints applied), laid out as the shapes' faces at 16 x 16: (u, v) along (x, z) for down / up, (x, 15 - y)
 * for north / south, (z, 15 - y) for west / east. `textures` holds count * 6 * 256 words, then count words: 1 where the
 * material has textures (0: none, its face tiles stand in). Sets materials first .. first + count - 1 (first >= 1).
 */
ATLAS_API AtlasStatus atlas_set_material_textures(AtlasContext* ctx, uint32_t first, const uint32_t* textures,
                                                  uint32_t count);
/*
 * (abi 0.18, optional) Micro-voxel bricks (docs/ATLAS-WORLD-STORE.md section 4): a material whose block model is not a
 * full cube (a door, a rail, redstone, a lectern, a torch, a crop) is voxelised from its model quads into a 16 x 16 x 16
 * brick (1/16 block), deduplicated per distinct content, and primary and shadow rays meet a level-0 grid voxel of that
 * material by its micro-voxels (their colour; their emission where the material emits). Materials first .. first +
 * count - 1 (first >= 1) are set: quads[] holds every quad of them (`material` relative to first; a material with no
 * quad loses its brick), sprites[] the textures the quads sample (texels: ARGB, sRGB, rows from the top; the sprite's
 * frames stacked: frame k at firstTexel + k * width * height), emission[] per material 0..1 (NULL: none). A material
 * whose quads sample an animated sprite gets a brick a frame (as many as its longest animation, at most 64), shown by
 * the frame's time: animated textures move (lava, fire, a campfire's flames, magma). Any thread; copied.
 *  pos       the quad's corners in block space (0..1 for a plain cube), in the model's order
 *  uv        each corner's (u, v) in the sprite's own 0..1 space
 *  tint      RGB the texels are multiplied by (0xFFFFFF: none)
 *  dir       the quad's face direction (0 down, 1 up, 2 north, 3 south, 4 west, 5 east; -1: from its winding)
 */
/*
 * (abi 0.18, optional) Plants' random offset (vanilla's OffsetType): per material of first .. first + count - 1 a word,
 * bits 0-1 the type (0 none, 1 XZ, 2 XYZ), bits 8-15 its largest horizontal offset x 255, bits 16-23 its largest
 * vertical one x 255; its brick is drawn shifted by the block's position hash (Mth.getSeed), as the chunk mesher does.
 */
ATLAS_API AtlasStatus atlas_set_material_offsets(AtlasContext* ctx, uint32_t first, const uint32_t* words, uint32_t count);
typedef struct AtlasBrickQuad {
    float pos[4][3];
    float uv[4][2];
    uint32_t sprite;
    uint32_t tint;
    int32_t dir;
    uint32_t material;
} AtlasBrickQuad;
typedef struct AtlasBrickSprite {
    uint32_t width, height;
    uint32_t firstTexel;  /* into texels[] */
    uint32_t frames;      /* bits 0-15 its frames (0 or 1: still), bits 16-31 game ticks a frame (0: 1) */
} AtlasBrickSprite;
ATLAS_API AtlasStatus atlas_set_material_bricks(AtlasContext* ctx, uint32_t first, uint32_t count, const AtlasBrickQuad* quads,
                                                uint32_t quadCount, const AtlasBrickSprite* sprites, uint32_t spriteCount,
                                                const uint32_t* texels, uint32_t texelCount, const float* emission);
/*
 * (abi 0.16) Pre-exposure: the GL path's half-float images (colour: radiance and the near field's inscatter; the near
 * light's irradiance) are written multiplied by this (the host's exposure for the coming frame), so physical light
 * spanning sunlight to starlight stays within half precision; transmittances, depths and the near light's visibility
 * are not scaled. 1 by default (the images in physical units, as before). The host displays them with exposure 1.
 */
ATLAS_API AtlasStatus atlas_set_pre_exposure(AtlasContext* ctx, float exposure);
ATLAS_API AtlasStatus atlas_set_material_shapes(AtlasContext* ctx, uint32_t first, const AtlasMaterialShape* shapes,
                                                uint32_t count);
/*
 * (abi 0.14) Surface cards (a surface cache captured from the game's own rendering, docs/ATLAS-LOD.md): what one face of
 * one block shows seen straight on, 16 x 16 texels (texture, tint, face shade, AO and the lightmap at the face's light,
 * ARGB sRGB, alpha 0 where the face shows nothing) with the sky and block light of each texel (level x 16, for relighting
 * later). Texel (u, v) runs along the axes after the face's normal axis: u on (axis + 1) % 3, v on (axis + 2) % 3, centres
 * at (i + 0.5) / 16. A hit on a face with a card takes its colour from the card, filtered over the pixel's footprint (a mip
 * chain of the covered texels); faces without one keep their face tiles. Replaces every card (count 0 removes them). The
 * game does not send them yet: offline tools (atlas_render surface=, from atlas_truth bake=).
 */
typedef struct AtlasSurfaceCard {
    int32_t x, y, z;  /* the block, world */
    uint32_t face;    /* 0 down, 1 up, 2 north, 3 south, 4 west, 5 east */
    uint32_t texels[256];
    uint8_t sky[256], block[256];
} AtlasSurfaceCard;
ATLAS_API AtlasStatus atlas_set_surface_cards(AtlasContext* ctx, const AtlasSurfaceCard* cards, uint32_t count);
/* The sun map as last computed (size * (size + 1) * 2 floats into out, count in floats). Waits for the GPU. */
ATLAS_API AtlasStatus atlas_read_sun_map(AtlasContext* ctx, float* out, uint32_t count);
/*
 * (abi 0.10) Refinement fades: a region rebuilt over a resident one (an exact tile replacing the raster stand-in,
 * buildings or landmarks arriving on a tile, a finer LOD entering the window over the coarser one) dissolves from what
 * was there to the new content per pixel over fadeSeconds (the old region stays packed meanwhile; a fade already running
 * keeps its start, so a tile refined twice in a row dissolves once). 0 (default) swaps at once.
 */
ATLAS_API AtlasStatus atlas_set_transitions(AtlasContext* ctx, float fadeSeconds);
/*
 * (abi 0.10) Far lights, in groups (a settlement, the sites of an area, the falls of a region ...): replaces group
 * `group`'s lights (count 0 removes the group). Any thread; copied. Each frame atlas picks the lights in view beyond
 * the near field (merged into clusters of about a pixel beyond clusterDistance), at most maxPerFrame of them, brightest
 * first (atlas_set_light_budget; defaults 4096 and 6000 blocks).
 */
ATLAS_API AtlasStatus atlas_set_light_group(AtlasContext* ctx, uint32_t group, const AtlasLight* lights, uint32_t count);
ATLAS_API AtlasStatus atlas_set_light_budget(AtlasContext* ctx, uint32_t maxPerFrame, float clusterDistance);
/* 1 if tile (lod, tileX, tileZ) is resident (uploaded and not evicted), else 0. */
ATLAS_API int32_t atlas_has_tile(AtlasContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ);
/* Offline harness only: preview terrain height at a block column (for camera placement). */
ATLAS_API float atlas_preview_height(AtlasContext* ctx, double x, double z);

/*
 * (abi 0.10) Memory of the device this context runs on, for sizing atlas's targets, sun map and clipmap to what the
 * machine can spare (an iGPU or a Steam Deck shares system RAM; a 4 GB card has little to give). Budget and usage
 * come from VK_EXT_memory_budget where the driver has it (flags bit 0), and are re-read on every call. The CPU
 * backend returns ATLAS_ERR_UNSUPPORTED.
 */
#define ATLAS_MEMORY_BUDGET_EXT 0x1u
typedef struct AtlasMemoryInfo {
    uint64_t deviceLocalBytes;  /* size of the largest device-local heap */
    uint64_t deviceLocalBudget; /* what this process may use of that heap now (VK_EXT_memory_budget), 0 = unknown */
    uint64_t deviceLocalUsage;  /* what this process uses of it now (VK_EXT_memory_budget), 0 = unknown */
    uint64_t hostHeapBytes;     /* size of the largest host-visible heap that is not device-local, 0 = none */
    uint64_t atlasBytes;       /* device memory this context has allocated (buffers and images, both frame slots) */
    uint32_t integrated;        /* 1: an integrated / unified-memory device (its heaps are system RAM) */
    uint32_t flags;             /* ATLAS_MEMORY_* */
} AtlasMemoryInfo;
ATLAS_API AtlasStatus atlas_memory_info(AtlasContext* ctx, AtlasMemoryInfo* out);

/*
 * (abi 0.10) Clipmap levels kept resident (1..7, default 7: voxel sizes 1..64 blocks, out to ~32 km). Fewer levels
 * keep fewer tiles and regions in host and device memory and end the far field sooner (6: ~16 km). Call before the
 * first atlas_update; tiles of levels at or above the count are dropped by the next update.
 */
ATLAS_API AtlasStatus atlas_set_clipmap(AtlasContext* ctx, uint32_t lodCount);

/*
 * (abi 0.10) Test hook: make the context fail as if the driver had failed, after `afterFrames` more frames
 * (atlas_render / atlas_gl_render calls): ATLAS_FAULT_DEVICE_LOST and ATLAS_FAULT_TIMEOUT kill the context
 * (as a real loss would: every later GPU call returns the same error), ATLAS_FAULT_OUT_OF_MEMORY fails that one
 * frame (the next ones work). ATLAS_FAULT_NONE disarms. The environment variable ATLAS_FAULT=<kind>[@frames]
 * (kind: device_lost, timeout, oom) arms every context created afterwards the same way, e.g. ATLAS_FAULT=
 * device_lost@600. Harmless in release builds: nothing happens unless asked for.
 */
#define ATLAS_FAULT_NONE 0
#define ATLAS_FAULT_DEVICE_LOST 1
#define ATLAS_FAULT_OUT_OF_MEMORY 2
#define ATLAS_FAULT_TIMEOUT 3
ATLAS_API AtlasStatus atlas_debug_fault(AtlasContext* ctx, uint32_t fault, uint32_t afterFrames);

/*
 * Test hook (the GPU generator's far terrain without the generator): `count` level-`level` chunks given to the grid
 * through a buffer on the context's own device (VoxGrid::ingestCoarseGpu): pos[3 i ..] the chunk (level-k chunk
 * units, cy from y -64), kinds[i] 0 mixed (ids[4096 i ..] its 16^3 voxel ids, x + 16 (z + 16 y)), 1 air,
 * 2 | id << 8 one id. accepted (optional, count bytes): 1 for each taken. Vulkan only (ATLAS_ERR_UNSUPPORTED else).
 */
/* Test hook: a section's ids as the host holds them now (out: 4096 ids), and where (*tier: 0 none, 1 raw: an upload
   not yet packed, 2 packed in the warm tier or read back from the cold one). */
ATLAS_API AtlasStatus atlas_debug_section(AtlasContext* ctx, int32_t sx, int32_t sy, int32_t sz, uint16_t* out, uint32_t* tier);
ATLAS_API AtlasStatus atlas_debug_coarse_chunks(AtlasContext* ctx, int32_t level, uint32_t count, const int32_t* pos,
                                                const uint32_t* kinds, const uint16_t* ids, uint8_t* accepted);
/* Test hook (the GPU generator's sections without the generator): `count` sections given to the grid through a buffer
   on the context's own device (VoxGrid::ingestGpu): pos[3 i ..] the section (sx, sy, sz), ids[4096 i ..] its ids,
   uniform (optional) each one's one id or 0xFFFFFFFF. accepted (optional, count bytes). Vulkan only. */
ATLAS_API AtlasStatus atlas_debug_gpu_sections(AtlasContext* ctx, uint32_t count, const int32_t* pos, const uint16_t* ids,
                                               const uint32_t* uniform, uint8_t* accepted);

/*
 * Exact seeded noise for the terrain height (shaders/atlas_height.slang): the WorldNoise evaluations the mod's
 * TamrielHeight.height makes, for a batch of columns, bit-identical to Minecraft's NoiseStack of PerlinNoise layers
 * (see the kernel's header for the argument). The caller runs height() itself, served these values. Independent of
 * AtlasContext: its own compute device; calls on one handle serialise, so any thread may call.
 *  ATLAS_BACKEND_VULKAN  the kernel on a Vulkan 1.2 device with shaderFloat64 and the float controls it needs
 *                         (RTE rounding and signed-zero/inf/nan preservation, 32 and 64 bit; denormal behaviour is
 *                         not relied on); ATLAS_ERR_UNSUPPORTED when there is none
 *  ATLAS_BACKEND_CPU     the same kernel compiled to C++ (reference)
 * The caller must still verify the results against its own CPU path before trusting a device.
 */
typedef struct AtlasHeight AtlasHeight;
#define ATLAS_HEIGHT_LAYER_WORDS 76u /* 32-bit words per noise layer, after the 8-word header */
#define ATLAS_HEIGHT_SLOTS 10u       /* noise calls per column (S_* in the kernel) */
typedef struct AtlasHeightTimes {
    double uploadMs;   /* host copy into staging + the device copy */
    double kernelMs;   /* device time of the dispatches (timestamps), or the CPU kernel's wall time */
    double readbackMs; /* the device copy back + host copy out */
    double totalMs;    /* wall time of the call */
} AtlasHeightTimes;
ATLAS_API AtlasStatus atlas_height_create(AtlasBackend backend, AtlasHeight** out);
ATLAS_API void atlas_height_destroy(AtlasHeight* h);
ATLAS_API const char* atlas_height_last_error(AtlasHeight* h);
/* Device description (static until destroy). */
ATLAS_API const char* atlas_height_device(AtlasHeight* h);
/* The noise table (8-word header, then ATLAS_HEIGHT_LAYER_WORDS per layer; ranges checked). Copied. */
ATLAS_API AtlasStatus atlas_height_set_noise(AtlasHeight* h, const uint32_t* table, uint32_t words);
/*
 * count columns, column k at block (x0 + (k % width) * step, z0 + (k / width) * step); slots[k]: bit s = evaluate
 * noise call s for column k. Out: values[s * count + k] (float; 0 where not asked), flags[k] = 1 when the kernel could
 * not prove a value of column k exact (the caller computes that column itself). width <= 65536. times may be NULL.
 */
ATLAS_API AtlasStatus atlas_height_batch(AtlasHeight* h, int32_t x0, int32_t z0, int32_t step, uint32_t width,
                                            uint32_t count, const uint32_t* slots, float* values, uint32_t* flags,
                                            AtlasHeightTimes* times);

/*
 * The client's world generator (shaders/atlas_gen.slang, docs/ATLAS-CLIENT-GENERATION.md): whole chunk columns of
 * real blocks (y -64..575: 40 sections) from a program the mod compiles from the world's own generator objects (the
 * density router, the aquifer, the material rule, the seeds; AtlasGenProgram) and per-chunk pages of the 2D Tamriel
 * inputs (AtlasGenPages). Self-contained like atlas_height_* (its own Vulkan device, or the kernels compiled to C++).
 * A chunk reads the pages of chunks x-4..x+2, z-2..z+2 (heights) and its 3 x 3 (with biomes): atlas_gen_missing_pages
 * lists those not resident (or resident without biomes where they are needed) as (cx, cz, full) triples; put them,
 * then generate. Pages stay resident (least recently used evicted past ATLAS_GEN_PAGES, default 4096).
 */
typedef struct AtlasGen AtlasGen;
typedef struct AtlasGenTimes {
    double uploadMs, kernelMs, readbackMs, ingestMs, totalMs;
    double passMs[6]; /* corners, aquifer cells, fill, tops, surface + map water, pack (device timestamps or CPU wall) */
} AtlasGenTimes;
ATLAS_API AtlasStatus atlas_gen_create(AtlasBackend backend, AtlasGen** out);
ATLAS_API void atlas_gen_destroy(AtlasGen* h);
ATLAS_API const char* atlas_gen_last_error(AtlasGen* h);
ATLAS_API const char* atlas_gen_device(AtlasGen* h);
/* The program (words[0] 'AGEN'); replaces the pages (another world). Copied. */
ATLAS_API AtlasStatus atlas_gen_set_program(AtlasGen* h, const uint32_t* words, uint32_t count);
/* Words of one page for the current program (0 before one is set). */
ATLAS_API uint32_t atlas_gen_page_words(AtlasGen* h);
/* Pages the chunks need that are not resident: up to capacity (cx, cz, full) triples into out; returns the count. */
ATLAS_API uint32_t atlas_gen_missing_pages(AtlasGen* h, const int32_t* chunkXZ, uint32_t count, int32_t* out,
                                           uint32_t capacity);
ATLAS_API AtlasStatus atlas_gen_put_page(AtlasGen* h, int32_t cx, int32_t cz, int32_t full, const uint32_t* words,
                                         uint32_t count);
/* Generates count chunks (cx, cz pairs): outIds count * 40 * 2048 words, per chunk its sections bottom up, two 16-bit
   ids to a word (index x + 16 * (z + 16 * y) like atlas_upload_section); outMode 0: generator block ids, 1: atlas
   materials (the program's table). outFlags (may be NULL): per chunk nonzero when a value could not be proven exact or
   a page was missing (take the server's blocks there). */
ATLAS_API AtlasStatus atlas_gen_chunks(AtlasGen* h, const int32_t* chunkXZ, uint32_t count, uint32_t outMode,
                                       uint32_t* outIds, uint32_t* outFlags, AtlasGenTimes* times);
/* The generator's exact math, checked against Java (StrictMath, IEEE division and sqrt): op 0 a / b, 1 sqrt a, 2 sin,
   3 cos, 4 exp, 5 pow(a, b), 6 atan2(a, b), 7 hypot(a, b), 8 atan; n <= 2^20; flags: nonzero where not proven. Replaces
   the program: set it again before generating. */
ATLAS_API AtlasStatus atlas_gen_math(AtlasGen* h, uint32_t op, uint32_t n, const double* a, const double* b, double* out,
                                     uint32_t* flags);
/* Generates and uploads the chunks' non-air sections into ctx (the atlas_upload_section path); outSections: count. */
ATLAS_API AtlasStatus atlas_gen_into(AtlasGen* h, AtlasContext* ctx, const int32_t* chunkXZ, uint32_t count,
                                     uint32_t* outFlags, uint32_t* outSections, AtlasGenTimes* times);
/*
 * GPU -> GPU: a generator on ctx's own device, whose kernels' sections go device to device into the context's grid
 * (VoxGrid::ingestGpu; the CPU World never sees them). Its kernels run on a queue of their own when the device has one
 * (async compute), else on the frames' queue. On a CPU-backend context: the kernels' C++ build, the sections going up
 * as atlas_upload_section takes them. ATLAS_ERR_UNSUPPORTED when the context's device cannot run the exact kernels or
 * its grid is off (use atlas_gen_create and atlas_upload_section then). Destroy it before the context.
 */
ATLAS_API AtlasStatus atlas_gen_create_for(AtlasContext* ctx, AtlasGen** out);
/* The context's side of the generator's blocks (atlas_gen_run, atlas_gen_ingest): words[0] = ids (the program's block
   table), words[1] = biomes (its biome list); per id 2 words: the far state id (the section hash's), flags (1 air, 2
   solid render, 4 motion blocking or fluid); per biome 2 words: grass, foliage (0xRRGGBB, the tints); then biomes x ids
   atlas materials (one word each, biome-major). count = 2 + 2 ids + 2 biomes + ids * biomes. */
ATLAS_API AtlasStatus atlas_gen_set_ingest(AtlasGen* h, const uint32_t* words, uint32_t count);
/* Generates count (<= 64) chunks for atlas_gen_ingest. outIds (may be NULL): the generator ids as atlas_gen_chunks gives
   them (the features' terrain); outFlags (may be NULL) as there; outInfo count * 164 words, per chunk: the lowest ground
   (highest motion-blocking-or-fluid block of a column, the lowest of them; -65: none), the highest top (non-air), the
   biome the tints come from (at the centre, the highest top), 0; then per section bottom up: its hash (FNV-1a 64 of the
   far ids seeded with the tints, AtlasEditsClient.claim's; lo, hi; 0 for deep rock), kind (0 air, 1 blocks, 2 deep rock: all solid render, 16 or more below the
   lowest ground), 0. */
ATLAS_API AtlasStatus atlas_gen_run(AtlasGen* h, const int32_t* chunkXZ, uint32_t count, uint32_t* outIds,
                                    uint32_t* outFlags, uint32_t* outInfo, AtlasGenTimes* times);
/* The last atlas_gen_run's sections whose bit is set in masks[chunk] (bit s: section y s - 4), as atlas materials (the
   biome's tints), into the context's grid; once per run, before the next. The grid takes them only inside its level-0
   window (they never reach the CPU World that refills it): on return masks hold the bits of the sections that went
   (outside the window: cleared, send them again when it comes). ATLAS_ERR_TIMEOUT (masks all cleared) when the grid
   has no window yet or has not taken earlier sections for 2 s (no frames). outSections: how many went. */
ATLAS_API AtlasStatus atlas_gen_ingest(AtlasGen* h, uint64_t* masks, uint32_t* outSections, AtlasGenTimes* times);
/*
 * The self-baking cache (docs/ATLAS-WORLD-STORE.md section 1). With the context's cache set (atlas_set_cache: the world's
 * directory and seed), what the generator makes is kept on disk, keyed by the seed, the generator's fingerprint (its
 * program, ingest table, map, stages and atlas_gen_set_fingerprint's value: another generator, another set; generated
 * again) and the position: atlas_gen_run writes each new chunk's info, atlas_gen_ingest has the grid pack its sections
 * with blocks on the GPU (wanted there or not) and the cache writes them compressed on a thread of its own.
 * Before generating, atlas_gen_cache_lookup tells which chunks it knows (outHit 1: outInfo's row as atlas_gen_run's);
 * atlas_gen_cache_ingest then puts their sections whose bit is set in masks into the grid as atlas_gen_ingest would
 * (inside the level-0 window; on return masks hold what went), read from disk, expanded on the GPU, an edited one with
 * its edit layer over it. No generation for those chunks.
 */
ATLAS_API AtlasStatus atlas_gen_set_fingerprint(AtlasGen* h, uint64_t extra);
/* (the call also arms the cache: no fingerprint reaches the world before it, so call it once the program, the stages,
   the ingest table and the map are in; a change after it, a stage added or removed, opens another set) */
ATLAS_API AtlasStatus atlas_gen_cache_lookup(AtlasGen* h, const int32_t* chunkXZ, uint32_t count, uint32_t* outInfo, uint8_t* outHit);
ATLAS_API AtlasStatus atlas_gen_cache_ingest(AtlasGen* h, const int32_t* chunkXZ, uint32_t count, uint64_t* masks,
                                             uint32_t* outSections);
/* The far terrain the same way (the coarse horizon): atlas_gen_far_ingest bakes the tiles the cache lacks (their kinds,
   their mixed chunks packed by the grid); atlas_gen_far_cache_ingest puts known tiles into the grid's level lod from
   disk instead (outServed[i] 1: tile i came from the cache; outChunks, outTaken as atlas_gen_far_ingest's). The horizon
   of what was seen, from the first frames. */
ATLAS_API AtlasStatus atlas_gen_far_cache_ingest(AtlasGen* h, int32_t lod, const int32_t* txTz, uint32_t count, uint8_t* outServed,
                                                 uint32_t* outChunks, uint32_t* outTaken);
/* The generator's GPU budget (docs: gen_engine.cpp, the pacer): its submissions are paced by the frame cadence so
   that frames keep their target; since the generator was made. periodMs the frames' period seen (0: no frames),
   duty the share of wall time the generator may use now, usedMs the GPU time its submissions took, waitedMs (farWaitedMs
   of it the far field's) the time they waited for the budget, maxSubmissionMs the longest submission. */
/* Free slots in the grid's coarse level `level` (1..6): a far tile it refused chunks of is asked for again when there is
   room for them. */
ATLAS_API uint32_t atlas_gen_coarse_room(AtlasGen* h, int32_t level);
/* A coarse level's window now: out[6] = origin x, z, side (chunks of the level), placed (1), jumps (how often it was placed
   afresh: what was given before is gone), rows. The far tiles to ask for are those that cover its columns. */
ATLAS_API AtlasStatus atlas_gen_coarse_window(AtlasGen* h, int32_t level, int32_t* out);
typedef struct AtlasGenPaceStats {
    double periodMs, duty, tokensMs, usedMs, waitedMs, farWaitedMs, maxSubmissionMs;
    uint64_t submissions;
    char maxPhase[48];  /* what the longest submission held (a pass, a store stage, a far stage ...) */
    double frameGpuMs;  /* the frames' own GPU time (timestamps, eased): the period's rest is the generator's to share */
    double recentMaxMs; /* the longest submission of the last 10-20 s, and what it held */
    char recentPhase[48];
} AtlasGenPaceStats;
ATLAS_API AtlasStatus atlas_gen_pace_stats(AtlasGen* h, AtlasGenPaceStats* out);
typedef struct AtlasGenCacheStats {
    uint32_t on;
    uint64_t fingerprint, chunks, sections, fileBytes, chunkHits, chunkMisses, sectionWrites, sectionReads;
    double encodeMs, decodeMs;
    uint64_t farWrites, farHits, farMisses;
    uint64_t hashErrors;  /* records whose ids did not match the GPU's content hash: refused (generated again) */
    uint64_t chunkWrites; /* chunk headers written this session */
    uint64_t queued;      /* records waiting to be written */
    uint64_t chunkIncomplete; /* of the misses: a chunk whose header is there and a section of it not (yet) */
} AtlasGenCacheStats;
/* (waits for the cache's writes first) */
ATLAS_API AtlasStatus atlas_gen_cache_stats(AtlasGen* h, AtlasGenCacheStats* out);
/* The same without waiting for the writes (a profile line's: the queue shows in `queued`). */
ATLAS_API AtlasStatus atlas_gen_cache_peek(AtlasGen* h, AtlasGenCacheStats* out);
/* The map of Nirn for the kernels (AtlasGenMap: words[0] = 'AMAP'), uploaded once; copied. With a program that carries
   the page passes' inputs (AtlasGenProgram: the climate entries, the fields' kinds, the height noises) the generator
   then makes every page itself on its device (atlas_page.slang) before each batch's terrain: atlas_gen_missing_pages
   answers 0 and nothing needs atlas_gen_put_page; a chunk that reads a page with a value not proven exact is flagged. */
ATLAS_API AtlasStatus atlas_gen_set_map(AtlasGen* h, const uint32_t* words, uint32_t count);
/* A resident page's words (count = atlas_gen_page_words), for the parity checks. ATLAS_ERR_ARG when not resident. */
ATLAS_API AtlasStatus atlas_gen_read_page(AtlasGen* h, int32_t cx, int32_t cz, uint32_t* out, uint32_t count);
/* Far tiles on the device (the map set, atlas_page.slang tileMain): count tiles as (lod, tx, tz) triples, each 256 x 256
   columns at the centre block of their 2^lod squares (AtlasExactSource.fill): outSurface count * 65536 floats
   ((float) TamrielHeight.height, row-major), outWater the same (the sea below y 63, the map's rivers and lakes at
   ceil(level), NaN dry); outBlocks (may be NULL: not made) per column the material rule's top block | the one under it
   << 16 (generator ids, AtlasSurface.column's: ore veins left out, the biomes as chunks store them; then, with a map
   that carries the far section (AtlasGenMap.far), freeze_top_layer's snow layer on top where it lies, the ground
   under it, and a far road's block on top: FarRoads.surface); outBiomes (with outBlocks, may be NULL) the biome id at
   the column's top block | flags << 16 (ATLAS_COLUMN_SNOW, ATLAS_COLUMN_FROZEN: its water's top freezes, 8: a far
   road); outFlags (may be NULL) nonzero where a column is not proven exact. */
ATLAS_API AtlasStatus atlas_gen_tiles(AtlasGen* h, const int32_t* lodTxTz, uint32_t count, float* outSurface, float* outWater,
                                      uint32_t* outBlocks, uint32_t* outBiomes, uint32_t* outFlags, AtlasGenTimes* times);
/* Far chunks on the device (GPU only; atlas_page.slang farChunkMain): count (<= 16) lod tiles (tx, tz pairs; lod 1..6),
   each 16 x 16 columns of level-lod chunks (16^3 voxels of 2^lod blocks, rows = ceil(44 / 2^lod) from y -64: the grid's
   coarse level lod), made from the tile's columns (atlas_gen_tiles with blocks) as the far World build's terrain voxel.
   outKinds: per chunk (tile, column z * 16 + x, row inner) 2 words: kind (0 mixed, 1 air, 2 | material << 8 uniform,
   0xFFFFFFFE mixed without room: capacity too small) and slot; outIds (may be NULL) capacity * 2048 words, the mixed
   chunks' voxels (x + 16 * (z + 16 * y), two ids a word); outMixed: the slots wanted. outMode 0: generator ids, 1:
   atlas materials (the ingest table's, by the column's biome). */
ATLAS_API AtlasStatus atlas_gen_far(AtlasGen* h, int32_t lod, const int32_t* txTz, uint32_t count, uint32_t outMode, uint32_t* outKinds,
                                    uint32_t* outIds, uint32_t capacity, uint32_t* outMixed, uint32_t* outFlags, AtlasGenTimes* times);
/* Far chunks into the context's grid (atlas_gen_create_for, GPU only): atlas_gen_far's chunks in atlas materials (the
   ingest table), made in a buffer of the generator's that the grid reads (VoxGrid::ingestCoarseGpu: level lod, air and
   uniform chunks without a slot), kept until the grid no longer waits for them and a frame completed. outKinds as
   atlas_gen_far's; outTaken: the chunks the grid took (the others lie outside its level's window, or past a full pool's
   cut-off: ask again later). ATLAS_ERR_TIMEOUT: no window yet, or every far buffer still read. */
ATLAS_API AtlasStatus atlas_gen_far_ingest(AtlasGen* h, int32_t lod, const int32_t* txTz, uint32_t count, uint32_t capacity,
                                           uint32_t* outKinds, uint32_t* outMixed, uint32_t* outTaken, uint32_t* outFlags,
                                           AtlasGenTimes* times);
/* The whole-map coarse bake (gen_bake.cpp; docs/ATLAS-GPU-LOOP.md section 3): the far path run over every tile of the
   levels lodFirst .. lodLast (either direction: the order made) that touch the map's bounds [minX, maxX) x [minZ, maxZ),
   grown by marginBlocks, nearest the centre (spawn) first within a level, into the self-baking cache; a tile the cache
   knows is skipped, so a later launch finds the bake whole and does nothing. atlas_gen_far_known: 1 the cache knows the
   far tile whole, 0 not, -1 no cache (not armed). The cache set is the generator's fingerprint's: another generator, another
   set, baked again. Tiles in a coarse level's window go into the grid as they are made (atlas_gen_far_ingest). */
typedef struct AtlasGenBakeSpec {
    int32_t minX, minZ, maxX, maxZ, centerX, centerZ;
    int32_t lodFirst, lodLast, marginBlocks;
    uint32_t capacity; /* mixed chunks a tile has room for (0: 2000; a tile with more is made again with room for all) */
} AtlasGenBakeSpec;
typedef struct AtlasGenBakeInfo {
    uint32_t tiles[8], known[8], made[8], failed[8]; /* by level 1..6: planned, on disk at begin, made since, given up */
    uint32_t total, knownTotal, madeTotal, failedTotal;
    uint32_t durable; /* of the made: now known to the cache (written) */
    uint32_t queued;  /* cache records waiting to be written */
    uint32_t busy;    /* the last step stopped early: 1 no window yet / far buffers still read (a frame frees them), 2 cache writes behind */
    uint32_t pad;
    int32_t last[4];  /* the last tile the last step finished: lod, tx, tz, state (0 none, 1 made, 2 given up) */
    double makeMs;    /* wall time inside atlas_gen_far_ingest */
} AtlasGenBakeInfo;
ATLAS_API int32_t atlas_gen_far_known(AtlasGen* h, int32_t lod, int32_t tx, int32_t tz);
ATLAS_API AtlasStatus atlas_gen_bake_begin(AtlasGen* h, const AtlasGenBakeSpec* spec, AtlasGenBakeInfo* out);
/* Makes the next maxTiles tiles not yet known (each one atlas_gen_far_ingest, paced as the far field is: the leftovers of
   the GPU budget). Returns early with info.busy set; call again after a frame. */
ATLAS_API AtlasStatus atlas_gen_bake_step(AtlasGen* h, uint32_t maxTiles, AtlasGenBakeInfo* out);
ATLAS_API AtlasStatus atlas_gen_bake_info(AtlasGen* h, AtlasGenBakeInfo* out);
ATLAS_API AtlasStatus atlas_gen_bake_end(AtlasGen* h);
/* The grid's reports since the last call (VoxGrid::takeGpuDropped / takeGpuWanted), {level, x, y, z} each: level 0
   sections (section coordinates) or level-k chunks the grid no longer holds (dropped), and level 0 sections it would take
   now (wanted): make and send them again. Returns how many did not fit the arrays. */
ATLAS_API uint32_t atlas_gen_grid_reports(AtlasGen* h, int32_t* outDropped, uint32_t droppedCap, uint32_t* outDroppedCount,
                                          int32_t* outWanted, uint32_t wantedCap, uint32_t* outWantedCount);
/*
 * The batch's stages (the features and overlays engines, GPU to GPU on the generator's device and in its batch). A
 * batch (atlas_gen_run / atlas_gen_chunks) runs: the pages it lacks, the terrain (density, aquifers, surface rule, map
 * water), then the registered stages in order, then the pack (blocks -> 16-bit sections), the sections' kinds and
 * hashes, the atlas materials and (atlas_gen_ingest) the grid. A stage records its Vulkan commands into the batch's
 * command buffer (vkCommandBuffer): it binds its own pipelines and descriptors (made beforehand on the device of
 * atlas_gen_device_handles), reads and writes `blocks`, and returns 0 (anything else fails the batch). The generator
 * puts a memory barrier after each stage and binds its own state again. The order numbers (ATLAS_GEN_STAGE_*):
 *   1000 + step   decoration step 0..9 (GenerationStep.Decoration's ordinal: the features engine)
 *   2000          settlement and structure pieces, 2100 their sweeps (the overlays engine)
 *   3000          freeze_top_layer (snow and ice), 3100 road and street paint
 * Stages of one order run in registration order. Callbacks run on the thread that calls atlas_gen_run, with the
 * generator's lock held: they must not call back into atlas_gen_*. Vulkan only (ATLAS_ERR_UNSUPPORTED on the C++ engine).
 */
enum {
    ATLAS_GEN_STAGE_DECORATION = 1000,  /* + the step, 0..9 */
    ATLAS_GEN_STAGE_PIECES = 2000,
    ATLAS_GEN_STAGE_SWEEPS = 2100,
    ATLAS_GEN_STAGE_FREEZE = 3000,
    ATLAS_GEN_STAGE_PAINT = 3100
};
typedef struct AtlasGenStageBatch {
    uint32_t version;              /* 1 */
    uint32_t order;                /* the stage being called */
    uint32_t chunkCount;           /* chunks in this batch (<= 64) */
    uint32_t queueFamily;          /* vkCommandBuffer's */
    const int32_t* chunks;         /* (cx, cz) pairs, host memory */
    void* vkDevice;                /* VkDevice */
    void* vkCommandBuffer;         /* VkCommandBuffer, recording, compute bind point */
    uint64_t blocks;               /* VkBuffer: per chunk blocksWordsPerChunk uint32 generator block ids (the program's
                                      block table: AtlasGenProgram.blocks), index x + 16 * (z + 16 * (y + 64)), y -64..575 */
    uint64_t blocksWordsPerChunk;  /* 16 * 16 * 640 */
    uint64_t chunkList;            /* VkBuffer: the chunks' (cx, cz) int pairs, in batch order */
    uint64_t pages;                /* VkBuffer: the pages (pageWords words each: the column fields, the map water word,
                                      the quart biomes; atlas_gen.slang's layout) */
    uint64_t pageIndex;            /* VkBuffer: int slot per page of the grid pageMinCx.., pageW x pageH (-1: none) */
    uint32_t pageWords;
    int32_t pageMinCx, pageMinCz, pageW, pageH;
    uint32_t pad0;
    uint64_t program;              /* VkBuffer: the program's words (AtlasGenProgram: seeds, noises, the block table) */
    uint64_t map;                  /* VkBuffer: the map's words (AtlasGenMap) */
} AtlasGenStageBatch;
typedef int32_t (*AtlasGenStageFn)(void* user, const AtlasGenStageBatch* batch);
/* Registers a stage at `order` (outId: for atlas_gen_remove_stage). Copied: the callback and user stay valid until removed
   or the generator is destroyed. */
ATLAS_API AtlasStatus atlas_gen_add_stage(AtlasGen* h, uint32_t order, AtlasGenStageFn fn, void* user, uint32_t* outId);
ATLAS_API AtlasStatus atlas_gen_remove_stage(AtlasGen* h, uint32_t id);
/* The generator's Vulkan device, for the stages' engines to make their pipelines and buffers on beforehand. */
typedef struct AtlasGenDevice {
    uint32_t version;              /* 1 */
    uint32_t queueFamily;          /* the generator's queue family (its batches' command buffers) */
    uint32_t frameQueueFamily;     /* the context's frames' family (atlas_gen_create_for), for buffers both read */
    uint32_t sharedWithContext;    /* 1: the context's own device (atlas_gen_create_for) */
    void* vkInstance;
    void* vkPhysicalDevice;
    void* vkDevice;
    void* vkQueue;                 /* the generator's queue: submit nothing of your own on it while a batch may run */
} AtlasGenDevice;
ATLAS_API AtlasStatus atlas_gen_device_handles(AtlasGen* h, AtlasGenDevice* out);
/* The generator's submissions on its queue completed so far (its fence passed): a stage engine that replaces a buffer
   it handed to a batch's commands keeps the old one until this count passes the submission it was recorded in (the
   one recording now is completed + 1). No lock: callable from a stage's callback. UINT64_MAX: nothing ever in flight
   (a CPU generator). */
ATLAS_API AtlasStatus atlas_gen_completed_submissions(AtlasGen* h, uint64_t* completed);
/* Pages the device made so far, the page passes' total time, and whether the device makes them (any may be NULL). */
ATLAS_API AtlasStatus atlas_gen_page_stats(AtlasGen* h, uint64_t* pagesMade, double* pageMs, int32_t* onDevice);
/*
 * The chunk store (GPU only): chunks wait on the generator's device until their neighbourhood is ready for each
 * store stage, the way the server's chunks wait for their neighbours' status. With the store on (atlas_gen_store_enable),
 * atlas_gen_run_store generates its chunks into the store (terrain, surface, map water: status 0), then runs every
 * store stage wherever it can and packs the chunks that passed them all (the same pack, kinds, hashes and materials
 * as atlas_gen_run; atlas_gen_ingest takes them into the grid). Whatever batch a chunk came in, its blocks come out the
 * same: a stage runs on a chunk only once the chunks within its radius all hold the previous stage's result.
 *
 * A store stage (atlas_gen_add_store_stage, ordered by `order`, ATLAS_GEN_STAGE_*) has a radius r: it runs on chunk C
 * (status k - 1) once every chunk within r of C (Chebyshev) is in the store with status >= k - 1; C's status becomes k.
 * It may write the blocks of chunks within r of C that have not passed it: a stage writing its neighbours must be
 * followed by one whose radius covers those writes (the features: decoration r 2 writing 3 x 3, then finishing r 1),
 * so that nothing writes a chunk after it is packed. Writes a stage makes must be the same whichever neighbour runs
 * first (the features keep the highest-priority write per block). Like a batch stage it records into the command
 * buffer given (never submits), and returns 0. One call handles up to 64 chunks; the next call the rest.
 *
 * Eviction: a full store drops packed chunks no waiting chunk needs, then waiting chunks farthest from the batch
 * (their neighbours that passed a stage go back to status 0: stages must give the same writes when run again).
 * atlas_gen_store_keep drops what lies outside a box (the camera's window).
 */
#define ATLAS_GEN_STORE_TERRAIN_WORDS (16u * 16u * 640u / 2u) /* per slot: two 16-bit generator ids a word */
#define ATLAS_GEN_STORE_BLOCK_WORDS (16u * 16u * 640u)        /* per slot: one 32-bit generator id a block */
#define ATLAS_GEN_STORE_AUX_WORDS 2048u                       /* per slot: [0, 1408) the quart biomes, [1408, 1664) each
                                                                 column's map region index, [1664, 1920) its map elevation
                                                                 (float: TamrielMap.sample), then 0 */
typedef struct AtlasGenStoreBatch {
    uint32_t version;              /* 1 */
    uint32_t order;                /* the stage being called */
    uint32_t count;                /* chunks to process (<= 64) */
    uint32_t radius;               /* the stage's radius r */
    const int32_t* chunks;         /* host: (cx, cz, slot) per chunk */
    void* vkDevice;                /* VkDevice */
    void* vkCommandBuffer;         /* VkCommandBuffer, recording, compute bind point */
    uint32_t queueFamily;
    uint32_t slots;                /* the store's slots */
    uint64_t list;                 /* VkBuffer: the chunks' (cx, cz, slot) int triples */
    uint64_t neighbors;            /* VkBuffer: per chunk (2 r + 1)^2 ints, the slot of (cx + dx, cz + dz) at
                                      (dz + r) * (2 r + 1) + dx + r (-1 none: never inside the radius) */
    const int32_t* neighborsHost;  /* host: the same */
    uint64_t terrain;              /* VkBuffer: per slot ATLAS_GEN_STORE_TERRAIN_WORDS: the terrain as generated (before
                                      any stage), block b = x + 16 * (z + 16 * (y + 64)) in the low half of word b / 2
                                      for even b, the high half for odd b */
    uint64_t blocks;               /* VkBuffer: per slot ATLAS_GEN_STORE_BLOCK_WORDS: the blocks as the stages left them
                                      (the low 16 bits the generator id; a stage may use the high bits until a later
                                      stage of its own clears them) */
    uint64_t aux;                  /* VkBuffer: per slot ATLAS_GEN_STORE_AUX_WORDS (the quart biomes, generator ids,
                                      atlas_gen.slang quartBiome's layout; the columns' map region and elevation) */
    uint64_t program;              /* VkBuffer: the generator program's words */
    uint64_t map;                  /* VkBuffer: the map's words (AtlasGenMap) */
    /* (version 2) a stage may record its work in parts, one submission each (the generator's GPU budget: a part of
       about 4 ms): it records `units` of its own units of work (0: all) and returns ATLAS_GEN_STAGE_MORE while some are
       left; the generator submits, then calls it again with the same chunks and part + 1. part 0 starts the work. */
    uint32_t part;
    uint32_t units;
    uint32_t lastPartUs; /* the part before's GPU time, microseconds (0 at part 0): a stage may fit its parts to it */
} AtlasGenStoreBatch;
#define ATLAS_GEN_STAGE_MORE 0x4D4F5245 /* a store stage's return: part recorded, call again */
typedef int32_t (*AtlasGenStoreStageFn)(void* user, const AtlasGenStoreBatch* batch);
/*
 * A far stage (atlas_gen_add_far_stage): called in the far chunks' passes (atlas_gen_far, atlas_gen_far_ingest) once a
 * tile, after its columns' planes (surface, water, blocks, biomes) and before its chunks are made from them. It records
 * into the command buffer given (never submits) and may write the tile's planes 5 and 6, which the chunks then draw
 * (both 0 on entry: nothing): plane 5 a column's canopy, fill << 27 | (crown top y + 1024) << 16 | (crown bottom y +
 * 1024), the voxels at or above the bottom and below the top (and above the ground) its leaves, fill/32 of them (a
 * hash of the voxel picks; fill 0: all); plane 6 the leaves' generator id
 * | the trunk's << 16 (0: no trunk), the voxels above the ground below the crown the trunk. The tile cache's
 * fingerprint holds each far stage's own (`fingerprint`: its engine's version and data), so a change makes the
 * cached far chunks again.
 */
#define ATLAS_GEN_TILE_COLS 65536u   /* a tile's columns, 256 x 256, row-major (z outer) */
#define ATLAS_GEN_TILE_PLANES 7u     /* a tile's planes of ATLAS_GEN_TILE_COLS words */
typedef struct AtlasGenFarBatch {
    uint32_t version;              /* 1 */
    int32_t lod;                   /* the tiles' level: a column is 2^lod blocks square */
    int32_t tx, tz;                /* the tile: blocks tx * 256 * 2^lod .. (columns from there) */
    uint32_t tile;                 /* its index in the planes buffer: planes at tile * TILE_PLANES * TILE_COLS words */
    uint32_t tiles;                /* tiles in this call */
    void* vkDevice;
    void* vkCommandBuffer;         /* recording, compute bind point */
    uint32_t queueFamily;
    uint32_t pad;
    uint64_t planes;               /* VkBuffer: the tiles' planes: 0 surface (float: ground below it), 1 water (float,
                                      NaN none), 2 the top block | the one under it << 16 (generator ids), 3 the top
                                      block's biome | flags << 16, 4 the biome above it or the water's; 5, 6 as above */
    uint64_t program;              /* VkBuffer: the generator program's words */
    uint64_t map;                  /* VkBuffer: the map's words (AtlasGenMap) */
} AtlasGenFarBatch;
typedef int32_t (*AtlasGenFarStageFn)(void* user, const AtlasGenFarBatch* batch);
/* Registers a far stage (outId: for atlas_gen_remove_stage). */
ATLAS_API AtlasStatus atlas_gen_add_far_stage(AtlasGen* h, AtlasGenFarStageFn fn, void* user, uint64_t fingerprint, uint32_t* outId);
/* Turns the store on with `slots` chunks (about 1.3 MiB each), Vulkan only. */
ATLAS_API AtlasStatus atlas_gen_store_enable(AtlasGen* h, uint32_t slots);
ATLAS_API AtlasStatus atlas_gen_add_store_stage(AtlasGen* h, uint32_t order, uint32_t radius, AtlasGenStoreStageFn fn, void* user,
                                                uint32_t* outId);
/* (atlas_gen_remove_stage removes store stages too) Generates count (<= 64, may be 0) chunks into the store, runs the
   store stages, packs up to 64 chunks that passed them all: outChunks (64 pairs) their (cx, cz), outCount how many,
   outFlags / outInfo as atlas_gen_run's for them. outIds (optional, 64 * 40 * 2048 words): their generator ids as
   atlas_gen_run gives them. Chunks already in the store are not generated again (packed already, they are packed
   again: their sections were not taken). */
ATLAS_API AtlasStatus atlas_gen_run_store(AtlasGen* h, const int32_t* chunkXZ, uint32_t count, int32_t* outChunks, uint32_t* outCount,
                                          uint32_t* outIds, uint32_t* outFlags, uint32_t* outInfo, AtlasGenTimes* times);
/* (the churn) Makes sure count (<= 64) chunks are in the store (generated into it if not), without packing them again:
   the neighbourhood a chunk's store stages read (atlas_gen_store_reach chunks around it) when only it must come out (a
   self-baking cache miss among cached chunks). */
ATLAS_API AtlasStatus atlas_gen_store_ensure(AtlasGen* h, const int32_t* chunkXZ, uint32_t count);
/* The chunks around a chunk its store stages read, through their chain (the sum of their radii): what must be in the
   store for it to pass them all. */
ATLAS_API uint32_t atlas_gen_store_reach(AtlasGen* h);
/* Chunks to be made again (their stages' data changed: a settlement's tapes arrived): the self-baking cache forgets
   those it wrote this session (one from disk was made with the data: nothing is baked inside a no-bake box), the store
   sends them back through its stages (packed again once through). */
ATLAS_API AtlasStatus atlas_gen_regenerate(AtlasGen* h, const int32_t* chunkXZ, uint32_t count);
/* Boxes (minCx, minCz, maxCx, maxCz each, inclusive) whose chunks the self-baking cache does not keep: their stages'
   data is still to come (a settlement's tapes not ready); replaces the last set (count 0: none). */
ATLAS_API AtlasStatus atlas_gen_set_no_bake(AtlasGen* h, const int32_t* boxes, uint32_t count);
/* Since the generator was made: chunks generated into the store, store stage runs (per chunk), chunks packed out. */
ATLAS_API AtlasStatus atlas_gen_store_counters(AtlasGen* h, uint64_t* made, uint64_t* stageRuns, uint64_t* packs);
/* Drops the store's chunks outside [minCx, maxCx] x [minCz, maxCz] (asked for again, they are generated again). */
ATLAS_API AtlasStatus atlas_gen_store_keep(AtlasGen* h, int32_t minCx, int32_t minCz, int32_t maxCx, int32_t maxCz);
/* held: chunks in the store; waiting: asked for (atlas_gen_run_store), not yet through every stage; ready: through every
   stage, not yet packed. (A caller keeps waiting well under the store's slots: what waits holds its neighbourhood.) */
ATLAS_API AtlasStatus atlas_gen_store_stats(AtlasGen* h, uint32_t* held, uint32_t* waiting, uint32_t* ready);
/* Probes the kernels' pieces at n (x, z) columns (n <= 2^17), 32 words each, the last a flag (nonzero: not proven): kind 0
   TamrielMap.sample (the MapSample fields in declaration order). For the parity checks. */
ATLAS_API AtlasStatus atlas_gen_probe(AtlasGen* h, uint32_t kind, uint32_t n, const int32_t* xz, uint32_t* out);

/*
 * Structures from the plans on the GPU (shaders/atlas_overlays.slang, overlays_engine.cpp; Java:
 * com.nirncraft.atlas.AtlasOverlayTapes): the named settlements' pieces as op tapes and the street painter's plan,
 * stamped in place on chunk columns of 16-bit ids of the overlay block table (b = x + 16 z + 256 (y + 64), `levels`
 * y levels from -64, two ids a word), one GPU thread per column. Vulkan only. atlas_ovl_set: which 0 the block table
 * ('AOBL': 7 words per id: flags, transforms, sweep classes, block index / facing / cover, shape table, access classes, road classes; then the shape
 * tables), 1 the tapes ('AOTP': per chunk record: 257 column starts, 256 column sweep ranges, the ops; the index after
 * the records: cx, cz, record, layer (word 3: 4, the stride; layer 0 the settlements', 1 the underground structures',
 * 2 the surface structures'), 2 the street plan ('AOSR'); resident until replaced. atlas_ovl_run stamps layer 0. atlas_ovl_run: chunks ATLAS_OVL_CHUNK_INTS ints each (cx, cz, the chunk's
 * tape record word offset or -1, its street plan bucket or -1, the batch slots of its N, E, S, W neighbours or -1);
 * kinds bit 0 the pieces, bit 2 their sweep (Sanity, then fence / wall / pane shapes), bit 1 the streets (in that
 * order: the server's).
 */
#define ATLAS_OVL_CHUNK_INTS 8u
typedef struct AtlasOvl AtlasOvl;
typedef struct AtlasOvlTimes {
    double uploadMs, kernelMs, piecesMs, streetsMs, readbackMs, totalMs, sweepsMs;  /* pieces, sweeps, streets: GPU timestamps */
} AtlasOvlTimes;
ATLAS_API AtlasStatus atlas_ovl_create(AtlasBackend backend, AtlasOvl** out);
ATLAS_API void atlas_ovl_destroy(AtlasOvl* h);
ATLAS_API const char* atlas_ovl_last_error(AtlasOvl* h);
ATLAS_API const char* atlas_ovl_device(AtlasOvl* h);
ATLAS_API AtlasStatus atlas_ovl_set(AtlasOvl* h, uint32_t which, const uint32_t* words, uint32_t count);
ATLAS_API AtlasStatus atlas_ovl_run(AtlasOvl* h, uint32_t count, const int32_t* chunks, uint32_t levels, uint32_t kinds,
                                    uint32_t* ids, AtlasOvlTimes* times);
/* The overlays as stages of the generator, GPU to GPU on its device, registered both as store stages
   (atlas_gen_add_store_stage, with their radii: atlas_gen_run_store) and as batch stages (atlas_gen_add_stage:
   atlas_gen_run / atlas_gen_chunks; they stand down once the store has called the overlays): the heightmap the road
   painter reads at ATLAS_GEN_STAGE_DECORATION - 3 (r 0), the tapes' dungeon layers (a structure's recorded writes:
   the underground structures' at DECORATION - 2, the surface structures' at DECORATION - 1, r 0; before the
   decoration, which is one stage for every step), the settlements' pieces at ATLAS_GEN_STAGE_PIECES (r 0; the store
   keeps each chunk's result per slot), their sweep at ATLAS_GEN_STAGE_SWEEPS (r 1: Sanity reads the four neighbours as
   their pieces left them), the road painter (RoadPaint) at ATLAS_GEN_STAGE_PAINT and the street painter at
   ATLAS_GEN_STAGE_PAINT + 50 (r 0), the road painter once atlas_ovl_set(which 3: 'AORD', RoadPaintGpu.words) is given.
   Each chunk's tape record and street bucket come from the data's own chunk index. The block table's first ids must
   be the generator program's blocks (AtlasOverlayTapes(prog.blocks)); the ingest table (atlas_gen_set_ingest) must
   cover the whole block table. atlas_ovl_set's data goes up at the generator's next stage call. ATLAS_ERR_UNSUPPORTED
   for a CPU generator. Destroy the overlays before the generator (it detaches itself). atlas_ovl_run is refused on such
   a handle. */
ATLAS_API AtlasStatus atlas_ovl_create_for_gen(AtlasGen* gen, AtlasOvl** out);
/* The structures' piece boxes for the generator's other store stages (the features' placement mask), valid during a
   store stage call (the buffers may be replaced between calls: ask again each call). tapes: the tape words
   (AtlasOverlayTapes.tapeWords); slotRecords: per store slot an int, the word offset of that slot's chunk's record in
   tapes, -1 none (written at the overlays' first store stage, DECORATION - 3, for every chunk of the store). A
   record's boxes: at rec + 513 + tapes[rec + 256], a count n, then n boxes of 5 ints (minX, minZ, maxX, maxZ, maxY,
   world blocks): every settlement piece and dungeon piece whose box meets that chunk (pieces over 160 blocks across
   left out, as PlacementMask.collect); a box near but not on a chunk is in its neighbours' lists. ATLAS_ERR_STATE before
   the store has called the overlays or before any tapes. */
typedef struct AtlasOvlStoreBoxes {
    uint32_t version;      /* 1 */
    uint32_t slots;        /* the store's slots (slotRecords' length) */
    uint32_t tapeWords;    /* tapes' size in words */
    uint32_t pad;
    uint64_t tapes;        /* VkBuffer (uint words) */
    uint64_t slotRecords;  /* VkBuffer (int per slot) */
} AtlasOvlStoreBoxes;
ATLAS_API AtlasStatus atlas_ovl_store_boxes(AtlasOvl* h, AtlasOvlStoreBoxes* out);
/* The passes a stage runs (bit 0 pieces, bit 2 their sweep, bit 1 streets, bit 3 roads, bit 4 the roads' heightmap,
   bit 5 the underground structures' layer, bit 6 the surface structures'; default all). */
ATLAS_API AtlasStatus atlas_ovl_set_kinds(AtlasOvl* h, uint32_t kinds);
/* Chunks a stage has stamped so far (those with a tape record or a street bucket). (The batch apron is gone: the store's
   radii make the results batch-independent.) */
ATLAS_API uint64_t atlas_ovl_stamped(AtlasOvl* h);

/*
 * The client's decoration (docs/ATLAS-CLIENT-GENERATION.md stage 4, shaders/atlas_features.slang): the world's placed
 * features (AtlasFeatureProgram) over generated terrain, Java's order, seeds and random streams. A batch: `slots`
 * terrain chunks (ids: ATLAS_FEAT_CHUNK_WORDS words each, 16-bit block ids of the feature program's table, b = x +
 * 16 z + 256 (y + 64), y -64..639; aux: ATLAS_FEAT_AUX_WORDS words each, the 4 x 4 x 176 quart biomes as 16-bit
 * program biome ids, then the 256 placement mask bytes, then 3 x 256 alchemy class bytes (by the map), the 256
 * temperature noise floats, 8 words of frozen-modifier bits, then room for the heightmaps the kernels compute); `origins`
 * chunks decorated in isolation (28 ints each: cx, cz, the 5 x 5 terrain slots around it z-major, -1 none, pad);
 * `targets` chunks assembled from their 3 x 3 origins' writes in a fixed order (12 ints each: own slot, the 9 origin
 * indices z-major (-1 none), cx, cz) into outIds (ATLAS_FEAT_CHUNK_WORDS each). outTables (optional, the isolated
 * check): per origin ATLAS_FEAT_TABLE_WORDS words: 16 meta words ([0] entries, [1] flags: 1 table full, 2 a read
 * outside the 5 x 5, 4 an unsupported piece, 8 a stack limit, 16 math not proven), then the write table (key, id)
 * pairs, key = 1 + ((y + 64) * 48 + z - (cz - 1) * 16) * 48 + x - (cx - 1) * 16, 0 empty.
 */
#define ATLAS_FEAT_CHUNK_WORDS (44u * 2048u)
#define ATLAS_FEAT_AUX_WORDS (1928u + 6u * 256u)
#define ATLAS_FEAT_TABLE_WORDS (16u + 32768u * 2u)
typedef struct AtlasFeat AtlasFeat;
typedef struct AtlasFeatTimes {
    double uploadMs, kernelMs, featureMs, gatherMs, readbackMs, totalMs;
} AtlasFeatTimes;
ATLAS_API AtlasStatus atlas_feat_create(AtlasBackend backend, AtlasFeat** out);
ATLAS_API void atlas_feat_destroy(AtlasFeat* h);
ATLAS_API const char* atlas_feat_last_error(AtlasFeat* h);
ATLAS_API const char* atlas_feat_device(AtlasFeat* h);
/* The feature program (AtlasFeatureProgram: words[0] = 'AFEA'); copied. */
ATLAS_API AtlasStatus atlas_feat_set_program(AtlasFeat* h, const uint32_t* words, uint32_t count);
ATLAS_API AtlasStatus atlas_feat_run(AtlasFeat* h, uint32_t slots, const uint32_t* ids, const uint32_t* aux, uint32_t origins,
                                     const int32_t* org, uint32_t targets, const int32_t* tgt, uint32_t* outIds,
                                     uint32_t* outTables, AtlasFeatTimes* times);
/* (checks) The store's aux kernel (atlas_features.slang featAuxMain: Biome's temperature noise, the frozen bits, ...)
   over n chunks (cx, cz pairs) with the generator's map (AtlasGenMap words): n * ATLAS_FEAT_AUX_WORDS words. */
ATLAS_API AtlasStatus atlas_feat_probe_aux(AtlasFeat* h, const uint32_t* map, uint32_t mapCount, uint32_t n, const int32_t* chunkXZ,
                                           uint32_t* outAux);
/* The decoration as the generator's store stages (atlas_features.slang on the generator's device, GPU to GPU): decoration
   at ATLAS_GEN_STAGE_DECORATION (radius 2: every origin decorated in isolation over its 5 x 5 terrain, its writes into
   the store's blocks of its 3 x 3, each block keeping the write latest in the gather's order), then its finish at
   ATLAS_GEN_STAGE_DECORATION + 999 (radius 1: a chunk all of whose origins are decorated). words: the feature program
   (AtlasFeatureProgram, compiled over the generator's biomes); gen2feat (nGen) the feature id of each generator id and
   feat2gen (nFeat) the generator id of each feature id, or both null (the same ids). The store must be on
   (atlas_gen_store_enable); the decoration's pipelines are made here (the driver's first compile can take minutes).
   Calls on the generator must not run meanwhile. atlas_feat_destroy removes the stages. */
ATLAS_API AtlasStatus atlas_feat_attach(AtlasGen* gen, const uint32_t* words, uint32_t count, const uint32_t* gen2feat, uint32_t nGen,
                                        const uint32_t* feat2gen, uint32_t nFeat, AtlasFeat** out);
/* The overlays whose structure pieces the decoration's placement mask keeps clear (atlas_ovl_store_boxes, read in every
   decoration call; until they have tapes, or with null, the mask has the plans alone: the settlements' cores, the
   roads, the streets). On a handle from atlas_feat_attach; the overlays must outlive it or be unset first. The minor
   sites' and landmarks' pads are not in the GPU's mask. */
ATLAS_API AtlasStatus atlas_feat_set_overlays(AtlasFeat* h, AtlasOvl* ovl);

#ifdef __cplusplus
}
#endif
#endif
