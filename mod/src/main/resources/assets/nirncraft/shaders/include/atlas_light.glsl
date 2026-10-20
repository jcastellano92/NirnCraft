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
};

// albedo sRGB, n world-space unit normal, lm = (block, sky) light 0..1 -> display sRGB
// the look, as atlas_shared.slang's shadeSurfaceAmb (LOOK_AMBIENT, LOOK_BOUNCE): keep them equal
const float LOOK_AMBIENT = 3.0;
const float LOOK_BOUNCE = 0.25;

vec3 atlasForwardLit(vec3 albedo, vec3 n, vec2 lm) {
    float open = smoothstep(0.05, 0.35, lm.y);
    float sky = lm.y * lm.y;
    vec3 direct = KeyRad.rgb * max(dot(n, KeyDir.xyz), 0.0) * open;
    vec3 bounce = KeyRad.rgb * (max(KeyDir.y, 0.0) * LOOK_BOUNCE * (0.6 - 0.4 * n.y) * sky);
    vec3 ambient = mix(Ground.rgb, SkyUp.rgb, n.y * 0.5 + 0.5) * (sky * LOOK_AMBIENT) + bounce + vec3(0.012, 0.012, 0.016);
    return atlasDisplay(atlasSrgbToLinear(albedo) * (direct + ambient + atlasBlockLight(lm.x)), Look.x, Look.y);
}

// emitters (EMISSIVE pipelines: eyes, glowing parts): full brightness through the same display
vec3 atlasForwardEmissive(vec3 albedo) {
    return atlasDisplay(atlasSrgbToLinear(albedo) * 2.0, Look.x, Look.y);
}

#endif
