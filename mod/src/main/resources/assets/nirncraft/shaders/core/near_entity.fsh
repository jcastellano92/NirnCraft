#version 330
#extension GL_ARB_separate_shader_objects : require
// Infinite ATLAS: vanilla's entity shader with our light (near_entity.vsh). The overlay (hurt flash), glint, dissolve,
// cutout and translucency behave as vanilla's; the light is atlasForwardLit, the colour the pack's display.

#ifdef GLINT
#include <minecraft:globals.glsl>
#endif
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>
#include <nirncraft:atlas_light.glsl>
#include <nirncraft:atlas_water.glsl>

uniform sampler2D Sampler0;
#ifdef DISSOLVE
uniform sampler2D DissolveMaskSampler;
#endif
#ifdef GLINT
uniform sampler2D GlintSampler;
#endif

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec3 worldNormal;
layout(location = 4) in vec2 lightLevels;
layout(location = 8) in vec3 worldPos;
#ifndef NO_OVERLAY
layout(location = 5) in vec4 overlayColor;
#endif
layout(location = 6) in vec2 texCoord0;
#ifdef GLINT
layout(location = 7) in vec2 texCoordGlint;
#endif

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

void main() {
    vec4 color = texture(Sampler0, texCoord0);
#ifdef OIT_ADDITIVE
    color.a = min(0.99, color.a);
#endif
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    vec4 tint = vertexColor;
#ifdef DISSOLVE
    if (tint.a < texture(DissolveMaskSampler, texCoord0).a) {
        discard;
    }
    tint.a = 1.0;
#endif
    color *= tint * ColorModulator;
#ifdef GLINT
    color.a = max(color.a, GlintAlpha);
#endif
#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
#else
#ifndef NO_OVERLAY
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
#endif
    vec3 n = normalize(gl_FrontFacing ? worldNormal : -worldNormal);
#ifdef EMISSIVE
    color.rgb = atlasForwardEmissive(color.rgb);
#else
    color.rgb = atlasForwardLitAt(color.rgb, n, lightLevels, worldPos);
#endif
#ifdef GLINT
    vec4 glintColor = GlintAlpha * texture(GlintSampler, texCoordGlint);
    color.rgb += glintColor.rgb * glintColor.rgb;
#endif
#ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
#else
    vec4 fogColor = FogColor;
    if (atlasEyeInWater()) {  // seen through the water between (atlas_water.glsl), not vanilla's linear fog
        fragColor = vec4(atlasUnderwater(color.rgb, sphericalVertexDistance, vec3(0.0)), color.a);
        return;
    }
#endif
    if (Ground.w < 0.5) { fragColor = color; return; }  // (no vanilla distance fog under our look: the air's haze is the resolve's)
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
                          FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
#endif
}
