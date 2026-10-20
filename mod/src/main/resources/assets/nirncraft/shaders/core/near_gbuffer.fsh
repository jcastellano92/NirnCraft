#version 330
#extension GL_ARB_separate_shader_objects : require
// Infinite ATLAS near pass: the G-buffer (com.nirncraft.atlas.AtlasNearPass).
//  target 0: albedo x vertex colour (tint, ambient occlusion, face shade), alpha 1 where a surface is
//  target 1: r block light, g sky light (level / 15), b a the world-space face normal, octahedral (atlas_shared.slang
//            octEncode: atlas_core decodes it for the near light)
// Depth is the frame's: reverse-Z, vanilla's projection, so the resolve can hand it on to entities and particles.

#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:terrainglobals.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in vec2 lightLevels;
layout(location = 3) in vec3 viewPos;

layout(location = 0) out vec4 outAlbedo;
layout(location = 1) out vec4 outLight;

vec2 octEncode(vec3 n) {
    n /= (abs(n.x) + abs(n.y) + abs(n.z));
    vec2 e = n.xz;
    if (n.y < 0.0) e = (1.0 - abs(e.yx)) * vec2(e.x >= 0.0 ? 1.0 : -1.0, e.y >= 0.0 ? 1.0 : -1.0);
    return e * 0.5 + 0.5;
}

void main() {
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize)
                               : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    // the face's normal (flat: blocks are flat), towards the camera
    vec3 n = normalize(cross(dFdx(viewPos), dFdy(viewPos)));
    if (dot(n, viewPos) > 0.0) n = -n;
    // vanilla baked its fixed face shade (down 0.5, up 1, north / south 0.8, west / east 0.6) into the vertex colour:
    // out again on axis-aligned faces, so the light decides how bright a face is (cross plants are not shaded)
    vec3 a = abs(n);
    if (max(a.x, max(a.y, a.z)) > 0.99) {
        float shade = n.y > 0.5 ? 1.0 : n.y < -0.5 ? 0.5 : a.z > 0.5 ? 0.8 : 0.6;
        color.rgb = min(color.rgb / shade, vec3(1.0));
    }
    outAlbedo = vec4(color.rgb, 1.0);
    outLight = vec4(clamp(lightLevels / 240.0, 0.0, 1.0), octEncode(n));
}
