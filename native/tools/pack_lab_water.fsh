#version 330 compatibility
// pack_lab only: what gbuffers_water (lib/gbuffers.glsl, TRANSLUCENT) writes for a water surface, as a full-screen
// pass over the lab's water mask (the lab has no Minecraft geometry). Keep it in step with lib/gbuffers.glsl.
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/materials.glsl"
#include "/lib/pbr.glsl"

uniform sampler2D labWater;     // rgb: water albedo (sRGB), a: 1 where the surface is water
uniform sampler2D depthtex0;    // the water surface
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform vec3 sunPosition;
uniform vec3 cameraPosition;
uniform float rainStrength;
uniform float frameTimeCounter;

#include "/lib/lighting.glsl"
#include "/lib/weather.glsl"
#include "/lib/waves.glsl"

in vec2 texcoord;

/* RENDERTARGETS: 5,6 */
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outInfo;

void main() {
    vec4 w = texture(labWater, texcoord);
    if (w.a < 0.5) {
        outColor = vec4(0.0);
        outInfo = vec4(0.0);
        return;
    }
    float d = texture(depthtex0, texcoord).r;
    vec4 v = gbufferProjectionInverse * vec4(texcoord * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    vec3 playerPos = mat3(gbufferModelViewInverse) * (v.xyz / v.w);
    vec3 sun = worldSunDir(gbufferModelViewInverse, sunPosition);
    vec2 lm = vec2(0.0, 1.0);
    float dist = length(playerPos);
    float openness = smoothstep(0.6, 1.0, lm.y);
    vec2 wxz = (playerPos + cameraPosition).xz;
    vec3 n = waveNormal(wxz, frameTimeCounter, openness, waterWind(wxz, rainStrength), vec2(0.0), dist, rainStrength);
    float a = WATER_ALPHA;
    vec3 lit = shadeForward(srgbToLinear(w.rgb), n, lm, playerPos, sun, 0.0);
    outColor = vec4(lit * a, a);
    outInfo = vec4(octEncode(n), float(MAT_WATER) / 255.0, lm.y);
}
