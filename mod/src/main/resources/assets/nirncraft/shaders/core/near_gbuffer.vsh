#version 330
#extension GL_ARB_separate_shader_objects : require
// Infinite ATLAS near pass (com.nirncraft.atlas.AtlasNearPass): vanilla's own section meshes, drawn into our
// G-buffer instead of lit into the frame. The same inputs as minecraft:core/terrain (a pipeline modifier swaps the
// shaders and keeps everything else); the lightmap is not sampled here: the block and sky light levels go out as
// they are, and the resolve pass lights them (one light for near and far).

#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
#ifdef MULTIDRAW_TERRAIN
layout(location = 4) in ivec3 ChunkPosition;
layout(location = 5) in float ChunkVisibility;
#endif

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out vec2 lightLevels;
layout(location = 3) out vec3 viewPos;   // camera-relative world position (the face normal from its derivatives)

void main() {
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    vertexColor = Color;      // biome tint x ambient occlusion x face shade, as vanilla baked it
    texCoord0 = UV0;
    lightLevels = vec2(UV2);  // block, sky: light level x 16 (0..240), smooth light interpolates them
    viewPos = pos;
}
