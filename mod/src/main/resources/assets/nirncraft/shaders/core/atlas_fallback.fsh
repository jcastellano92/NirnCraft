#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>
// NirnCraft's fallback compositor (com.nirncraft.atlas.AtlasFallback): atlas's far field behind the vanilla near
// field when no shader pack is active. A full-screen triangle at the far plane (reverse-Z depth 0, depth test >=):
// only pixels no opaque terrain covered (the sky) take it.
//  Sampler0: atlas colour, linear HDR radiance, premultiplied (a = coverage), rows bottom-up like the screen
//  Sampler1: atlas depth, view distance in blocks (1e30 = nothing far there)
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 c = texture(Sampler0, texCoord);
    float d = texture(Sampler1, texCoord).r;
    if (c.a <= 0.002 || d > 1e29) discard;
    // atlas's harness tonemapper (c / (1 + c), gamma 2.2), on the straight colour
    vec3 straight = max(c.rgb / max(c.a, 1e-4), vec3(0.0));
    vec3 ldr = pow(straight / (1.0 + straight), vec3(1.0 / 2.2));
    // fog continuity: at the edge of the loaded terrain vanilla fades into its fog colour; the far field starts from
    // that colour there and clears into its own aerial haze over the next 1.5 render distances. Environmental fog
    // (rain, water, lava, blindness) is taken as the near field has it at that edge, not kilometres further out
    // (vanilla's 1 km clear-air fog would otherwise hide the whole far field).
    float edge = max(FogRenderDistanceEnd, 16.0);
    float start = min(FogRenderDistanceStart, edge - 1.0);
    float band = clamp((d - start) / (edge - start), 0.0, 1.0) * (1.0 - smoothstep(edge, edge * 2.5, d));
    float env = linear_fog_value(min(d, edge), FogEnvironmentalStart, FogEnvironmentalEnd);
    float f = clamp(max(band, env), 0.0, 1.0) * FogColor.a;
    vec3 rgb = mix(ldr, FogColor.rgb, f);
    fragColor = vec4(rgb * c.a, c.a);
}
