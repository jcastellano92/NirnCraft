#ifndef NIRNCRAFT_ATLAS_LOOK_GLSL
#define NIRNCRAFT_ATLAS_LOOK_GLSL
// Infinite ATLAS's look, one for near and far (com.nirncraft.atlas.AtlasNearPass): the shader pack's functions
// (shaderpack/InfiniteATLAS/shaders/lib: lighting.glsl blockLight, grade.glsl nightVision, final.fsh's ACES fit),
// the same numbers as native/tools/pack_look.hpp, which the far field's offline tools hold to.

// the light map's block light as colour: warm firelight (lighting.glsl)
// physical: a torch lights its own block at about a thousandth of noon sunlight (BLOCK_LIGHT_SCALE; atlas_shared.slang's
// far lights the same): in daylight a torch adds nothing, at night it outshines a full moon by hundreds
const float BLOCK_LIGHT_SCALE = 0.001;
vec3 atlasBlockLight(float bl) {
    return vec3(1.0, 0.62, 0.32) * (pow(bl, 3.2) * 1.8 + pow(bl, 12.0) * 2.0) * BLOCK_LIGHT_SCALE;
}

vec3 atlasNightVision(vec3 c, float strength) {
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float m = clamp((log2(4e-5) - log2(max(l, 1e-12))) / 4.0, 0.0, 1.0) * strength;  // (physical: rods under ~1e-5 of noon)
    float rod = 0.04 * c.r + 0.5 * c.g + 0.46 * c.b;
    return mix(c, vec3(0.6, 0.8, 1.2) * (rod / 0.787), m);
}

// Stephen Hill's ACES fit (final.fsh)
vec3 atlasAces(vec3 c) {
    const mat3 inM = mat3(0.59719, 0.07600, 0.02840, 0.35458, 0.90834, 0.13383, 0.04823, 0.01566, 0.83777);
    const mat3 outM = mat3(1.60475, -0.10208, -0.00327, -0.53108, 1.10813, -0.07276, -0.07367, -0.00605, 1.07602);
    vec3 v = inM * c;
    vec3 a = v * (v + 0.0245786) - 0.000090537;
    vec3 b = v * (0.983729 * v + 0.4329510) + 0.238081;
    return clamp(outM * (a / b), 0.0, 1.0);
}

vec3 atlasLinearToSrgb(vec3 v) {
    return mix(v * 12.92, 1.055 * pow(v, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), v));
}

vec3 atlasSrgbToLinear(vec3 v) {
    return mix(v / 12.92, pow((v + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), v));
}

// HDR scene radiance -> display sRGB (exposure: linear multiplier, the eye x the EXPOSURE option)
vec3 atlasDisplay(vec3 hdr, float exposure, float nightVision) {
    if (nightVision > 0.0) hdr = atlasNightVision(hdr, nightVision);
    return atlasLinearToSrgb(atlasAces(hdr * exposure));
}

#endif
