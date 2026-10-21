#version 330
#extension GL_ARB_separate_shader_objects : require
// Infinite ATLAS: vanilla's block shader (minecraft:core/block: falling blocks, moving pistons, blocks drawn as
// models outside the terrain) with our light instead of the lightmap (com.nirncraft.atlas.AtlasNearPass's entity
// modifier, while the near pass owns the frame). Its vertex format has no normal: the fragment shader takes the face's.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) out vec2 lightLevels;
layout(location = 5) out vec3 worldPos;

void main() {
    vec3 pos = Position + ModelOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = Color;
    texCoord0 = UV0;
    lightLevels = clamp(vec2(UV2) / 240.0, 0.0, 1.0);
    worldPos = pos;
}
