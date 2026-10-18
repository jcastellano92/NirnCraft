// Shared layouts and lighting for aether_core kernels. The C++ mirror is native/src/gpu_layout.hpp; keep both in
// sync (static_asserts there check sizes). All members are 16-byte vectors so std140 and C++ agree.

static const uint FRAME_CLOUDS = 0x1u;
static const uint FRAME_SHADOWS = 0x2u;
static const uint FRAME_ATMOS = 0x4u;
static const uint FRAME_ACTORS = 0x8u;
static const uint FRAME_SKY = 0x10u;         // draw aether's sky behind misses (harness); in game the pack draws it
static const uint FRAME_NEAR_DEPTH = 0x20u;  // nearDepth holds the near field's view distance: covered pixels skip
static const uint FRAME_FLAT_NORMALS = 0x40u;  // face normals only (cheaper; a quality setting)
static const uint FRAME_CLOUDS_QUARTER = 0x80u;  // cloud pass at quarter instead of half resolution (performance)
static const uint FRAME_DEBUG_LOD = 0x100u;
static const uint FRAME_DEBUG_ALBEDO = 0x400u;        // surfaces output their albedo (the pack lab's G-buffer)
static const uint FRAME_SEE_THROUGH_WATER = 0x800u;  // rays pass water voxels (the bed under a lake, for the lab)
static const uint FRAME_DEBUG_WEATHER = 0x200u;  // ground shows the sampled weather column (cov, tower, curtain)
static const int MAX_LODS = 8;

struct LodGpu {
    int4 origin;   // region coordinates of table cell (0, 0, 0); w = world y of region row 0 (the floor, -64)
    int4 dim;      // table size in regions; w = offset into regionTable
};

struct RegionGpu {
    int4 origin;   // world block origin of voxel (0, 0, 0); w = lod
    uint4 bases;   // node base, leaf base, root (node word offset), height-mip base
    uint4 runs;    // material run base, run count
};

struct MaterialGpu {
    float4 albedoRough;  // linear rgb, roughness
    float4 emisClass;    // emissive, class, 0, 0
};

struct FrameGpu {
    float4 invViewProj[4];  // columns of the camera-relative inverse view-projection
    int4 camInt;            // integer camera block
    float4 camFrac;         // camera position within that block; w = planet radius (0 = flat)
    float4 sunDir;          // world, towards the sun; w = time in seconds
    float4 params;          // near-field end, max distance, lod bias, pixel angle (radians)
    uint4 dims;             // width, height, flags, lod count
    float4 weatherParams;   // rain factor, 0, 0, far actor count
    LodGpu lods[MAX_LODS];
    float4 prevViewProj[4];  // last frame's camera-relative view-projection (columns), for cloud reprojection
    float4 prevCam;          // last frame's camera relative to this frame's camInt
    uint4 temporal;          // frame index, history valid (0/1)
    float4 sunMapCentre;     // sun map centre, camera-relative; w = half extent (blocks)
    float4 sunMapX;          // sun map basis (perpendicular to the key light)
    float4 sunMapY;
    float4 sunMapL;          // the key light the map is traced along
    uint4 sunMapRows;        // first row, row count, size, enabled
};

float3 unproject(FrameGpu f, float2 ndc) {
    float4 p = f.invViewProj[0] * ndc.x + f.invViewProj[1] * ndc.y + f.invViewProj[2] * 0.5 + f.invViewProj[3];
    return p.xyz / p.w;
}

float3 lodTint(int lod) {
    float3 t[8] = { float3(0.9, 0.2, 0.2), float3(0.9, 0.6, 0.2), float3(0.85, 0.85, 0.2), float3(0.3, 0.85, 0.3),
                    float3(0.2, 0.8, 0.8), float3(0.25, 0.4, 0.9), float3(0.6, 0.3, 0.9), float3(0.9, 0.9, 0.9) };
    return t[clamp(lod, 0, 7)];
}

float3 sunRadiance(float3 sun) {
    // warmer and dimmer as the sun nears the horizon (air mass), dark below it
    float e = sun.y;
    float3 warm = float3(1.0, 0.62, 0.38);
    float3 noon = float3(1.0, 0.96, 0.9);
    float a = saturate(e * 3.0);
    return lerp(warm, noon, a) * (3.2 * smoothstep(-0.04, 0.12, e));
}

float3 skyColor(float3 dir, float3 sun) {
    float mu = dot(dir, sun);
    float up = dir.y;
    float day = smoothstep(-0.12, 0.25, sun.y);
    float3 zenith = lerp(float3(0.008, 0.012, 0.03), float3(0.16, 0.34, 0.78), day);
    float3 horizon = lerp(float3(0.03, 0.035, 0.06), float3(0.62, 0.75, 0.92), day);
    // sunset band along the horizon on the sun's side
    float dusk = (1.0 - smoothstep(0.0, 0.3, abs(sun.y))) * smoothstep(-0.2, 0.9, mu);
    horizon = lerp(horizon, float3(1.0, 0.5, 0.25), dusk * 0.7);
    float hz = pow(1.0 - saturate(abs(up)), 5.0);
    float3 c = lerp(zenith, horizon, hz);
    if (up < 0.0) c = lerp(c, horizon * 0.55, saturate(-up * 6.0));
    // Mie forward lobe and the disk
    float g = 0.76;
    float mie = (1.0 - g * g) / pow(max(1.0 + g * g - 2.0 * g * mu, 1e-4), 1.5) * 0.012;
    c += sunRadiance(sun) * mie * 0.5;
    if (mu > 0.99995 && up > -0.01) c += sunRadiance(sun) * 18.0;
    return c * 1.6;
}

float3 skyAmbient(float3 n, float3 sun) {
    float day = smoothstep(-0.12, 0.25, sun.y);
    float3 skyUp = lerp(float3(0.02, 0.025, 0.05), float3(0.33, 0.45, 0.7), day);
    float3 ground = lerp(float3(0.01, 0.01, 0.012), float3(0.2, 0.18, 0.14), day);
    return lerp(ground, skyUp, n.y * 0.5 + 0.5);
}

// The key light: the sun, or the moon (opposite it) once the sun is below the horizon, with the shader pack's
// moonlight (shaderpack lib/lighting.glsl shadeForward): the near and far fields light the night identically.
float3 keyLightDir(float3 sun) { return sun.y > -0.05 ? sun : -sun; }

float3 keyLightRadiance(float3 sun) {
    return sun.y > -0.05 ? sunRadiance(sun) : float3(0.035, 0.05, 0.09) * smoothstep(-0.05, -0.2, sun.y);
}

// ao: 0..1 sky-light occlusion (crevices, forest floors), applied to the ambient term only.
// lightVis: shadowing of the key light. reflection: what water mirrors (the sky, or traced far terrain).
float3 shadeSurface(float3 albedo, MaterialGpu m, float3 n, float3 v, float3 sun, float lightVis, uint cls, float ao,
                    float3 reflection) {
    float3 L = keyLightDir(sun);
    float ndl = saturate(dot(n, L));
    float3 direct = keyLightRadiance(sun) * ndl * lightVis;
    float3 c = albedo * (direct + skyAmbient(n, sun) * ao);
    if (cls == 2u) {
        // water: Schlick Fresnel against what it reflects, plus the key light's glint; m.albedoRough.a carries the
        // glint's alpha (the far field sets it from the waves it can no longer draw: a sun path widening with distance)
        float cosv = saturate(dot(-v, n));
        float f = 0.02 + 0.98 * pow(1.0 - cosv, 5.0);
        float3 r = reflect(v, n);
        float alpha = clamp(m.albedoRough.a, 0.005, 0.5);
        float e = 2.0 / (alpha * alpha) - 2.0;  // the Phong lobe matching that alpha, energy-normalised
        float spec = pow(saturate(dot(r, L)), e) * (e + 8.0) / 25.1327 * 0.37 * lightVis;
        c = lerp(c, reflection, f) + keyLightRadiance(sun) * spec;
    }
    c += albedo * m.emisClass.x * 2.0;
    return c;
}

// Aerial perspective over kilometres: extinction thins with altitude (scale height ~ 1.2 km in blocks).
float aerialTransmittance(float t, FrameGpu f) {
    float camY = float(f.camInt.y) + f.camFrac.y;
    float h = max(camY - 63.0, 0.0);
    float density = exp(-h / 1200.0);
    float beta = (1.0 / 22000.0) * (1.0 + 3.0 * f.weatherParams.x);
    return exp(-t * beta * density);
}

float3 aerialFog(float3 dir, float3 sun) {
    return skyColor(normalize(float3(dir.x, max(dir.y, 0.02), dir.z)), sun) * 0.9;
}

float3 applyAerial(float3 c, float3 dir, float3 sun, float t, FrameGpu f) {
    return lerp(aerialFog(dir, sun), c, aerialTransmittance(t, f));
}

// Smooth value noise on a world-space grid (stable under camera motion and LOD changes).
float hash21(int2 p) {
    uint h = uint(p.x) * 374761393u + uint(p.y) * 668265263u;
    h = (h ^ (h >> 13)) * 1274126177u;
    return float(h & 0xFFFFFFu) / 16777215.0;
}

float valueNoise(float2 x) {
    float2 i = floor(x), f = x - i;
    f = f * f * (3.0 - 2.0 * f);
    int2 b = int2(i);
    float a = hash21(b), bb = hash21(b + int2(1, 0)), c = hash21(b + int2(0, 1)), d = hash21(b + int2(1, 1));
    return lerp(lerp(a, bb, f.x), lerp(c, d, f.x), f.y);
}

uint packColor(float3 c, float a) {
    // simple filmic curve, then gamma 2.2 (the game composite replaces this with the pack's tonemapper)
    c = c / (1.0 + c);
    c = pow(saturate(c), float3(1.0 / 2.2, 1.0 / 2.2, 1.0 / 2.2));
    uint r = uint(c.x * 255.0 + 0.5), g = uint(c.y * 255.0 + 0.5), b = uint(c.z * 255.0 + 0.5);
    uint aa = uint(saturate(a) * 255.0 + 0.5);
    return r | (g << 8) | (b << 16) | (aa << 24);
}

float2 octEncode(float3 n) {
    n /= (abs(n.x) + abs(n.y) + abs(n.z));
    float2 e = n.xz;
    if (n.y < 0.0) e = (1.0 - abs(e.yx)) * float2(e.x >= 0.0 ? 1.0 : -1.0, e.y >= 0.0 ? 1.0 : -1.0);
    return e * 0.5 + 0.5;
}

uint packNormal(float3 n, uint cls, float shadow) {
    float2 e = octEncode(n);
    return uint(saturate(e.x) * 255.0 + 0.5) | (uint(saturate(e.y) * 255.0 + 0.5) << 8) | ((cls & 255u) << 16)
        | (uint(saturate(shadow) * 255.0 + 0.5) << 24);
}
