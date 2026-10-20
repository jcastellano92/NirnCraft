#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>
#include <minecraft:projection.glsl>
// Infinite ATLAS near pass, the resolve (com.nirncraft.atlas.AtlasNearPass): one full-screen triangle in the frame's
// main pass right after opaque terrain would have been drawn. Near pixels are lit from the G-buffer, far pixels take
// atlas's frame, and both write the frame's depth (reverse-Z, vanilla's projection), so entities, block entities,
// particles, water, clouds and weather drawn after it depth-test against our terrain, near and far.
//  Sampler0: G-buffer albedo (alpha 1 where a surface is)     Sampler1: G-buffer light (r block, g sky: level / 15)
//  Sampler2: vanilla's lightmap (16 x 16: block x sky)        Sampler3: G-buffer depth (reverse-Z, 0 = nothing)
//  Sampler4: atlas colour (linear HDR, premultiplied)         Sampler5: atlas depth (view distance, 1e30 = none)
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform sampler2D Sampler5;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec3 viewRay(vec2 uv, float depth) {
    vec4 v = inverse(ProjMat) * vec4(uv * 2.0 - 1.0, depth, 1.0);
    return v.xyz / v.w;
}

void main() {
    float nearDepth = texture(Sampler3, texCoord).r;
    if (nearDepth > 0.0) {
        vec3 albedo = texture(Sampler0, texCoord).rgb;
        vec2 levels = texture(Sampler1, texCoord).rg;
        // stage A: vanilla's own light curve (the lightmap: day, night, flicker, night vision, effects)
        vec3 light = texture(Sampler2, clamp(levels * (15.0 / 16.0) + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0))).rgb;
        float dist = length(viewRay(texCoord, nearDepth));
        vec4 lit = vec4(albedo * light, 1.0);
        fragColor = vec4(mix(lit.rgb, FogColor.rgb,
                linear_fog_value(dist, FogEnvironmentalStart, FogEnvironmentalEnd) * FogColor.a), 1.0);
        gl_FragDepth = nearDepth;
        return;
    }
    vec4 c = texture(Sampler4, texCoord);
    float d = texture(Sampler5, texCoord).r;
    if (c.a <= 0.002 || d > 1e29) discard;  // the sky: what the sky pass drew stays
    // atlas's harness tonemapper (c / (1 + c), gamma 2.2) on the straight colour, then the near field's
    // environmental fog as it stands at the edge of the loaded terrain (AtlasFallback's rule)
    vec3 straight = max(c.rgb / max(c.a, 1e-4), vec3(0.0));
    vec3 ldr = pow(straight / (1.0 + straight), vec3(1.0 / 2.2));
    float edge = max(FogRenderDistanceEnd, 16.0);
    float f = clamp(linear_fog_value(min(d, edge), FogEnvironmentalStart, FogEnvironmentalEnd), 0.0, 1.0) * FogColor.a;
    fragColor = vec4(mix(ldr, FogColor.rgb, f) * c.a, c.a);
    // its depth: the point at that distance along this pixel's ray (beyond the projection's far plane: 0)
    vec4 clip = ProjMat * vec4(normalize(viewRay(texCoord, 0.5)) * d, 1.0);
    gl_FragDepth = clamp(clip.z / clip.w, 0.0, 1.0);
}
