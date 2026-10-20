#version 330
#extension GL_ARB_separate_shader_objects : require
// Infinite ATLAS near pass: the sun's shadow map (com.nirncraft.atlas.AtlasNearPass), vanilla's section meshes drawn
// from the key light (near_gbuffer.vsh with the light's view and projection): depth only, cutout texels let light through.

#include <minecraft:globals.glsl>
#include <minecraft:terrainglobals.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;

void main() {
#ifdef ALPHA_CUTOUT
    if (texture(Sampler0, texCoord0).a < ALPHA_CUTOUT) {
        discard;
    }
#endif
}
