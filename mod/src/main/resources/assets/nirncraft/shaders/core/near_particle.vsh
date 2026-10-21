#version 330
#extension GL_ARB_separate_shader_objects : require
// Infinite ATLAS: vanilla's particle shader (minecraft:core/particle) with our light instead of the lightmap
// (com.nirncraft.atlas.AtlasNearPass's entity modifier, while the near pass owns the frame).

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in ivec2 UV2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec2 texCoord0;
layout(location = 3) out vec4 vertexColor;
layout(location = 4) out vec2 lightLevels;
layout(location = 5) out vec3 toCamera;
layout(location = 6) out vec4 clipPos;  // (the pixel in atlas's frame: the water in front of it)

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    clipPos = gl_Position;
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    texCoord0 = UV0;
    vertexColor = Color;
    lightLevels = clamp(vec2(UV2) / 240.0, 0.0, 1.0);
    toCamera = -Position;
}
