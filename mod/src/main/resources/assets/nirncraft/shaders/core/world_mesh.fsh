#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>
#ifdef WORLD_FOG
#include <minecraft:fog.glsl>
#endif
#ifdef TEXTURED
uniform sampler2D Sampler0;
layout(location = 1) in vec2 texCoord;
#endif
layout(location = 0) in vec4 vertexColor;
#ifdef WORLD_FOG
layout(location = 2) in vec2 fogDistance;
#endif
#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

void main() {
    vec4 color = vertexColor * ColorModulator;
#ifdef TEXTURED
    color *= texture(Sampler0, texCoord);
#endif
    if (color.a == 0.0) discard;
#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
#else
#ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
#endif
#ifdef WORLD_FOG
#ifdef OIT_ACCUMULATE
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
#else
    vec4 fogColor = FogColor;
#endif
    color = apply_fog(color, fogDistance.x, fogDistance.y,
            FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
#endif
    fragColor = color;
#endif
}
