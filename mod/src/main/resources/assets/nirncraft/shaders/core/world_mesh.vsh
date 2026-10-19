#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#ifdef WORLD_FOG
#include <minecraft:fog.glsl>
#endif

layout(location = 0) in vec3 Position;
#ifdef TEXTURED
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 1) out vec2 texCoord;
#else
layout(location = 1) in vec4 Color;
#endif
layout(location = 0) out vec4 vertexColor;
#ifdef WORLD_FOG
layout(location = 2) out vec2 fogDistance;
#endif

void main() {
    vec4 viewPosition = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPosition;
#ifdef CLAMP_FAR_DEPTH
    if (gl_Position.w > 0.0) gl_Position.z = max(gl_Position.z, 0.0);
#endif
    vertexColor = Color;
#ifdef TEXTURED
    texCoord = UV0;
#endif
#ifdef WORLD_FOG
    fogDistance = vec2(fog_spherical_distance(viewPosition.xyz), fog_cylindrical_distance(viewPosition.xyz));
#endif
}
