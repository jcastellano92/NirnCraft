#version 330
#extension GL_ARB_separate_shader_objects : require
// Infinite ATLAS near pass: translucent terrain (water, glass, ice) drawn forward over the resolved frame
// (com.nirncraft.atlas.AtlasNearPass), lit with the same light and look as the opaque terrain: the key light, the sky
// over the surface (vanilla's sky light as its visibility), the block light, then the pack's display. Not traced yet:
// no shadow on the water's surface, no reflection (R3).

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:terrainglobals.glsl>
#include <nirncraft:atlas_light.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in vec2 lightLevels;
layout(location = 3) in vec3 viewPos;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize)
                               : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;
    if (color.a < 0.004) discard;
    vec3 n = normalize(cross(dFdx(viewPos), dFdy(viewPos)));
    if (dot(n, viewPos) > 0.0) n = -n;
    vec3 a = abs(n);
    if (max(a.x, max(a.y, a.z)) > 0.99) {  // vanilla's baked face shade out, as in the G-buffer
        float shade = n.y > 0.5 ? 1.0 : n.y < -0.5 ? 0.5 : a.z > 0.5 ? 0.8 : 0.6;
        color.rgb = min(color.rgb / shade, vec3(1.0));
    }
    vec3 rgb = atlasForwardLit(color.rgb, n, clamp(lightLevels / 240.0, 0.0, 1.0));
    float dist = length(viewPos);
    rgb = mix(rgb, FogColor.rgb, linear_fog_value(dist, FogEnvironmentalStart, FogEnvironmentalEnd) * FogColor.a);
    fragColor = vec4(rgb, color.a);
}
