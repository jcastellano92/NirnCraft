#version 330
#extension GL_ARB_separate_shader_objects : require
// Infinite ATLAS near pass: the G-buffer (com.nirncraft.atlas.AtlasNearPass).
//  target 0: albedo x vertex colour (tint, ambient occlusion, face shade), alpha 1 where a surface is
//  target 1: r block light, g sky light (level / 15), b 0, a 1
// Depth is the frame's: reverse-Z, vanilla's projection, so the resolve can hand it on to entities and particles.

#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:terrainglobals.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in vec2 lightLevels;

layout(location = 0) out vec4 outAlbedo;
layout(location = 1) out vec4 outLight;

void main() {
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize)
                               : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    outAlbedo = vec4(color.rgb, 1.0);
    outLight = vec4(clamp(lightLevels / 240.0, 0.0, 1.0), 0.0, 1.0);
}
