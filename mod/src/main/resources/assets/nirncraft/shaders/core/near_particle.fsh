#version 330
#extension GL_ARB_separate_shader_objects : require
// Infinite ATLAS: particles lit by our light (near_particle.vsh). A billboard has no surface of its own: it is lit as a
// small round puff, its normal between up and the camera.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>
#include <nirncraft:atlas_light.glsl>
#include <nirncraft:atlas_water.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec4 vertexColor;
layout(location = 4) in vec2 lightLevels;
layout(location = 5) in vec3 toCamera;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
#else
    vec3 n = normalize(vec3(0.0, 1.0, 0.0) + normalize(toCamera) * 0.6);
    color.rgb = atlasForwardLitAt(color.rgb, n, lightLevels, -toCamera);
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
    // (our look: no vanilla distance fog, the air's haze is the resolve's; a medium if the eye is in one)
    if (Look.z > 0.5) { fragColor = vec4(atlasMedium(color.rgb, sphericalVertexDistance), color.a); return; }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
                          FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
#endif
}
