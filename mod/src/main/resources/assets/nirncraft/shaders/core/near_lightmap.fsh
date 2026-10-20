#version 330
#extension GL_ARB_separate_shader_objects : require
#include <nirncraft:atlas_light.glsl>
// Infinite ATLAS: vanilla's 16 x 16 lightmap rewritten in our look (com.nirncraft.atlas.AtlasNearPass, at AfterLevel
// while we own the frame): what is still lit by vanilla's lightmap after our pipelines took the world (the first-person
// hand and held items, drawn after the level; a few special renderers) sees our light and display for its block and
// sky light, a white surface facing up and sideways (the key light at half strength, as a hand turns through it).
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec2 lm = floor(texCoord * 16.0) / 15.0;  // (block, sky) levels 0..15 -> 0..1
    float open = smoothstep(0.05, 0.35, lm.y);
    float sky = lm.y * lm.y;
    vec3 n = normalize(vec3(0.0, 1.0, 0.6));
    vec3 direct = KeyRad.rgb * 0.5 * open;
    vec3 bounce = KeyRad.rgb * (max(KeyDir.y, 0.0) * LOOK_BOUNCE * 0.5 * sky);
    vec3 ambient = mix(Ground.rgb, SkyUp.rgb, n.y * 0.5 + 0.5) * (sky * LOOK_AMBIENT) + bounce + vec3(0.012, 0.012, 0.016);
    vec3 light = direct + ambient + atlasBlockLight(lm.x);
    fragColor = vec4(atlasDisplay(light, Look.x, Look.y), 1.0);
}
