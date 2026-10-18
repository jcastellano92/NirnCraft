// aether's frame (the mod's far-field renderer: distant terrain, the cloud shell, far actors) as customTextures.
// Without the mod's renderer (other dimensions, the mod absent or aether off) the textures are 1x1 blanks: then the
// pack draws its own fallback cloud deck (lib/clouds.glsl) and the near field simply fogs out.
#ifndef SKYCRAFT_AETHER
#define SKYCRAFT_AETHER

#ifdef AETHER_FAR_FIELD
uniform sampler2D aetherColor;  // customTexture (shaders.properties): RGBA16F linear, premultiplied
uniform sampler2D aetherDepth;  // customTexture: R32F view distance of the nearest thing aether drew

bool aetherPresent() { return textureSize(aetherColor, 0).x >= 2 && textureSize(aetherDepth, 0).x >= 2; }

// aether renders at its own (dynamic) resolution: colour upsampled bilinearly (at scale 1 the texel centres fall on
// the pixels: exact), depth from the nearest texel (it stays a real distance at edges)
vec4 aetherColorAt(vec2 uv) {
    ivec2 sz = textureSize(aetherColor, 0);
    vec2 p = uv * vec2(sz) - 0.5;
    ivec2 i = ivec2(floor(p));
    vec2 f = p - vec2(i);
    ivec2 m = sz - 1;
    vec4 a = texelFetch(aetherColor, clamp(i, ivec2(0), m), 0), b = texelFetch(aetherColor, clamp(i + ivec2(1, 0), ivec2(0), m), 0);
    vec4 c = texelFetch(aetherColor, clamp(i + ivec2(0, 1), ivec2(0), m), 0), d = texelFetch(aetherColor, clamp(i + ivec2(1, 1), ivec2(0), m), 0);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float aetherDepthAt(vec2 uv) {
    ivec2 sz = textureSize(aetherDepth, 0);
    return texelFetch(aetherDepth, clamp(ivec2(uv * vec2(sz)), ivec2(0), sz - 1), 0).r;
}
#else
bool aetherPresent() { return false; }
vec4 aetherColorAt(vec2 uv) { return vec4(0.0); }
float aetherDepthAt(vec2 uv) { return 1e30; }
#endif

#endif
