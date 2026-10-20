#ifndef NIRNCRAFT_ATLAS_LIGHT_GLSL
#define NIRNCRAFT_ATLAS_LIGHT_GLSL
// Infinite ATLAS's forward light (translucent terrain, entities, block entities, particles: what is drawn after the
// near pass's resolve, com.nirncraft.atlas.AtlasNearPass): the key light, the sky over the surface (vanilla's sky light
// as its visibility), the block light, the pack's display. The opaque terrain takes atlas's traced light instead; this
// is the same light without the tracing (no shadow yet).
#include <nirncraft:atlas_look.glsl>

layout(std140) uniform NearInfo {
    vec4 Look;     // x exposure, y night vision, z mode (1: our light), w unused
    vec4 KeyDir;   // the key light's direction (sun or moon), w 1: the near light is on
    vec4 KeyRad;   // its radiance
    vec4 SkyUp;    // the sky's radiance over the horizon (skyAmbient = mix(Ground, SkyUp, n.y * 0.5 + 0.5))
    vec4 Ground;   // and the ground's under it
    vec4 SunDir;   // towards the sun (the sky's own; KeyDir is the moon at night), w the time in seconds
    vec4 WaterStill;  // the block atlas's water sprites (u0, v0, u1, v1): water fragments are found by their texels
    vec4 WaterFlow;
    mat4 ShadowMat;   // camera-relative world -> the key light's shadow map (reverse-Z depth: nearer the light = larger)
    mat4 InvViewRot;  // view space -> camera-relative world
    vec4 CamPos;      // the camera's world position, x and z modulo 6144 (world-anchored patterns)
    vec4 WaterTint;   // the biomes' water colour around the camera, eased (atlas_water.glsl)
};
uniform sampler2D ShadowMap;  // the near terrain seen from the key light (AtlasNearPass's shadow pass)

// how much of the key light reaches camera-relative world position p (a little lifted along n): 4 taps of the shadow
// map; 1 outside it (the near light's traced shadows and the far field cover farther)
float atlasShadow(vec3 p, vec3 n) {
    vec4 s = ShadowMat * vec4(p + n * 0.08, 1.0);
    vec2 uv = s.xy * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || ShadowMat[3][3] == 0.0) return 1.0;
    vec2 texel = 1.0 / vec2(textureSize(ShadowMap, 0));
    float d = s.z + 0.0015;  // reverse-Z: an occluder nearer the light has the larger depth
    float lit = 0.0;
    lit += texture(ShadowMap, uv + vec2(-0.5, -0.5) * texel).r <= d ? 1.0 : 0.0;
    lit += texture(ShadowMap, uv + vec2(0.5, -0.5) * texel).r <= d ? 1.0 : 0.0;
    lit += texture(ShadowMap, uv + vec2(-0.5, 0.5) * texel).r <= d ? 1.0 : 0.0;
    lit += texture(ShadowMap, uv + vec2(0.5, 0.5) * texel).r <= d ? 1.0 : 0.0;
    return lit * 0.25;
}

// albedo sRGB, n world-space unit normal, lm = (block, sky) light 0..1 -> linear radiance
// the look, as atlas_shared.slang's shadeSurfaceAmb (LOOK_AMBIENT, LOOK_BOUNCE): keep them equal. KeyRad, SkyUp and
// Ground are the air's (AtlasLook.light: atlas_air_light), the sun's transmitted light against the physical sky: 1
const float LOOK_AMBIENT = 1.0;
const float LOOK_BOUNCE = 0.25;

vec3 atlasForwardRadianceShadowed(vec3 albedo, vec3 n, vec2 lm, float keyVis) {
    float open = smoothstep(0.05, 0.35, lm.y) * keyVis;
    float sky = lm.y * lm.y;
    vec3 direct = KeyRad.rgb * max(dot(n, KeyDir.xyz), 0.0) * open;
    vec3 bounce = KeyRad.rgb * (max(KeyDir.y, 0.0) * LOOK_BOUNCE * (0.6 - 0.4 * n.y) * sky);
    vec3 ambient = mix(Ground.rgb, SkyUp.rgb, n.y * 0.5 + 0.5) * (sky * LOOK_AMBIENT) + bounce + vec3(0.0025, 0.0025, 0.0035);
    return atlasSrgbToLinear(albedo) * (direct + ambient + atlasBlockLight(lm.x));
}

vec3 atlasForwardRadiance(vec3 albedo, vec3 n, vec2 lm) {
    return atlasForwardRadianceShadowed(albedo, n, lm, 1.0);
}

// the same, displayed (sRGB)
vec3 atlasForwardLit(vec3 albedo, vec3 n, vec2 lm) {
    return atlasDisplay(atlasForwardRadiance(albedo, n, lm), Look.x, Look.y);
}

// lit at camera-relative world position p, in the key light's shadow
vec3 atlasForwardLitAt(vec3 albedo, vec3 n, vec2 lm, vec3 p) {
    return atlasDisplay(atlasForwardRadianceShadowed(albedo, n, lm, atlasShadow(p, n)), Look.x, Look.y);
}

// emitters (EMISSIVE pipelines: eyes, glowing parts): full brightness through the same display
vec3 atlasForwardEmissive(vec3 albedo) {
    return atlasDisplay(atlasSrgbToLinear(albedo) * 2.0, Look.x, Look.y);
}

#endif
