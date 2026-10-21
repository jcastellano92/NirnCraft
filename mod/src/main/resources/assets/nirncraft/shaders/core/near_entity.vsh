#version 330
#extension GL_ARB_separate_shader_objects : require
// Infinite ATLAS: vanilla's entity shader (minecraft:core/entity) with our light instead of the lightmap and the fixed
// two-light shading (com.nirncraft.atlas.AtlasNearPass's entity modifier, while the near pass owns the frame). Every
// define vanilla's pipelines set keeps its meaning; what reaches the fragment shader is the normal and the light levels.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;

#if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
uniform sampler2D Sampler1;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec3 worldNormal;
layout(location = 4) out vec2 lightLevels;
layout(location = 8) out vec3 worldPos;
layout(location = 9) out vec4 clipPos;  // (the pixel in atlas's frame: the water in front of it)
#ifndef NO_OVERLAY
layout(location = 5) out vec4 overlayColor;
#endif
layout(location = 6) out vec2 texCoord0;
#ifdef GLINT
layout(location = 7) out vec2 texCoordGlint;
#endif

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    clipPos = gl_Position;
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color;
    worldNormal = Normal;
    worldPos = Position;
    lightLevels = clamp(vec2(UV2) / 240.0, 0.0, 1.0);
#if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
    overlayColor = texelFetch(Sampler1, UV1, 0);
#endif
    texCoord0 = UV0;
#ifdef APPLY_TEXTURE_MATRIX
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
#ifdef GLINT
    texCoordGlint = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
}
